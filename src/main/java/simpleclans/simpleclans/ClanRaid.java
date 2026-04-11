package simpleclans.simpleclans;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.sql.*;
import java.util.*;

/**
 * Clan Raid System
 * A raid is a declared offensive against another clan's claimed territory.
 * Commands: /clan raid start <clan> | end | status | history
 * Permissions: simpleclans.raid.*
 * Config toggle: features.clan-raid
 */
public class ClanRaid implements Listener {

    private final SimpleclansPlugin plugin;

    public ClanRaid(SimpleclansPlugin plugin) {
        this.plugin = plugin;
        createTables();
    }

    private void createTables() {
        try (Statement stmt = plugin.getConnection().createStatement()) {
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS clan_raids (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "raider TEXT NOT NULL," +
                "defender TEXT NOT NULL," +
                "kills_raider INTEGER DEFAULT 0," +
                "kills_defender INTEGER DEFAULT 0," +
                "start_time INTEGER NOT NULL," +
                "end_time INTEGER," +
                "outcome TEXT," +       // RAIDER_WIN / DEFENDER_WIN / ENDED
                "active INTEGER DEFAULT 1)"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS clan_raid_cooldowns (" +
                "clan TEXT PRIMARY KEY," +
                "last_raid INTEGER NOT NULL)"
            );
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // ─── COMMAND HANDLER ───────────────────────────────────────────────────────

    public boolean handleCommand(Player player, String[] args) {
        if (!plugin.getConfig().getBoolean("features.clan-raid", true)) {
            player.sendMessage(plugin.getMessage("feature_disabled", Map.of())); return true;
        }

        if (args.length < 2) { sendRaidHelp(player); return true; }

        return switch (args[1].toLowerCase()) {
            case "start"   -> handleStart(player, args);
            case "end"     -> handleEnd(player);
            case "status"  -> handleStatus(player);
            case "history" -> handleHistory(player);
            default -> { sendRaidHelp(player); yield true; }
        };
    }

    private boolean handleStart(Player player, String[] args) {
        if (!player.hasPermission("simpleclans.raid.start")) {
            player.sendMessage(plugin.getMessage("no_permission", Map.of())); return true;
        }

        String myClan = plugin.getClanOf(player.getUniqueId());
        if (myClan == null) { player.sendMessage(plugin.getMessage("not_in_clan", Map.of())); return true; }

        String role = plugin.getRoleOf(player.getUniqueId());
        if (!role.equalsIgnoreCase("LEADER") && !role.equalsIgnoreCase("CO-LEADER")) {
            player.sendMessage("§c[!] Only leaders and co-leaders can start a raid."); return true;
        }

        if (args.length < 3) { player.sendMessage("§c[!] Usage: /clan raid start <clan>"); return true; }

        String targetClan = args[2];
        if (!plugin.getAllClanNames().stream().anyMatch(c -> c.equalsIgnoreCase(targetClan))) {
            player.sendMessage("§c[!] Clan '" + targetClan + "' does not exist."); return true;
        }
        String exact = plugin.getAllClanNames().stream()
            .filter(c -> c.equalsIgnoreCase(targetClan)).findFirst().orElse(targetClan);

        if (exact.equalsIgnoreCase(myClan)) {
            player.sendMessage("§c[!] You cannot raid yourself."); return true;
        }

        if (isRaiding(myClan) || isBeingRaided(myClan)) {
            player.sendMessage("§c[!] Your clan is already involved in a raid."); return true;
        }
        if (isRaiding(exact) || isBeingRaided(exact)) {
            player.sendMessage("§c[!] That clan is already involved in a raid."); return true;
        }

        // Cooldown check
        long cooldownMs = plugin.getConfig().getLong("clan-raid.cooldown", 7200) * 1000L;
        long lastRaid   = getLastRaidTime(myClan);
        long elapsed    = System.currentTimeMillis() - lastRaid;
        if (elapsed < cooldownMs) {
            long remaining = (cooldownMs - elapsed) / 1000;
            player.sendMessage("§c[!] Your clan is on raid cooldown. §7Remaining: §f" + formatTime(remaining)); return true;
        }

        startRaid(myClan, exact);
        updateCooldown(myClan);

        int duration = plugin.getConfig().getInt("clan-raid.duration", 1800);

        broadcastToClan(myClan, "§c§l🔥 RAID STARTED! §r§7You are now raiding §e" + exact + "§7!");
        broadcastToClan(exact,  "§c§l🔥 RAID! §r§7Your clan is being raided by §e" + myClan + "§7! Defend your territory!");

        // Auto-end raid after duration
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (isActiveRaidBetween(myClan, exact)) {
                endRaid(myClan, exact, determineWinner(myClan, exact));
                broadcastToClan(myClan, "§6[Raid] §7The raid against §e" + exact + " §7has ended (time limit reached).");
                broadcastToClan(exact,  "§6[Raid] §7The raid from §e" + myClan  + " §7has ended (time limit reached).");
            }
        }, duration * 20L);

        return true;
    }

    private boolean handleEnd(Player player) {
        if (!player.hasPermission("simpleclans.raid.end")) {
            player.sendMessage(plugin.getMessage("no_permission", Map.of())); return true;
        }
        String myClan = plugin.getClanOf(player.getUniqueId());
        if (myClan == null) { player.sendMessage(plugin.getMessage("not_in_clan", Map.of())); return true; }

        String role = plugin.getRoleOf(player.getUniqueId());
        if (!role.equalsIgnoreCase("LEADER")) {
            player.sendMessage("§c[!] Only the clan leader can end a raid."); return true;
        }

        String opponent = getRaidOpponent(myClan);
        if (opponent == null) { player.sendMessage("§c[!] Your clan is not in a raid."); return true; }

        String winner = determineWinner(myClan, opponent);
        endRaid(myClan, opponent, winner);
        broadcastToClan(myClan,    "§6[Raid] §7The raid has ended. Winner: §e" + (winner != null ? winner : "draw"));
        broadcastToClan(opponent,  "§6[Raid] §7The raid has ended. Winner: §e" + (winner != null ? winner : "draw"));
        return true;
    }

    private boolean handleStatus(Player player) {
        if (!player.hasPermission("simpleclans.raid.status")) {
            player.sendMessage(plugin.getMessage("no_permission", Map.of())); return true;
        }
        String myClan = plugin.getClanOf(player.getUniqueId());
        if (myClan == null) { player.sendMessage(plugin.getMessage("not_in_clan", Map.of())); return true; }

        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "SELECT * FROM clan_raids WHERE (raider = ? OR defender = ?) AND active = 1")) {
            ps.setString(1, myClan); ps.setString(2, myClan);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) { player.sendMessage("§7Your clan is not in a raid."); return true; }
            String raider   = rs.getString("raider");
            String defender = rs.getString("defender");
            int kr = rs.getInt("kills_raider"), kd = rs.getInt("kills_defender");
            long elapsed = (System.currentTimeMillis() - rs.getLong("start_time")) / 1000;
            player.sendMessage("§6===== §c🔥 Raid Status §6=====");
            player.sendMessage("§7Raider: §c" + raider);
            player.sendMessage("§7Defender: §b" + defender);
            player.sendMessage("§7Kills (raider/defender): §c" + kr + " §7/ §b" + kd);
            player.sendMessage("§7Elapsed: §f" + formatTime(elapsed));
            player.sendMessage("§6========================");
        } catch (SQLException e) { e.printStackTrace(); }
        return true;
    }

    private boolean handleHistory(Player player) {
        if (!player.hasPermission("simpleclans.raid.history")) {
            player.sendMessage(plugin.getMessage("no_permission", Map.of())); return true;
        }
        String myClan = plugin.getClanOf(player.getUniqueId());
        if (myClan == null) { player.sendMessage(plugin.getMessage("not_in_clan", Map.of())); return true; }

        player.sendMessage("§6===== §e🔥 Raid History: §f" + myClan + " §6=====");
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "SELECT * FROM clan_raids WHERE (raider = ? OR defender = ?) AND active = 0 ORDER BY id DESC LIMIT 10")) {
            ps.setString(1, myClan); ps.setString(2, myClan);
            ResultSet rs = ps.executeQuery();
            boolean any = false;
            while (rs.next()) {
                any = true;
                String outcome = rs.getString("outcome");
                String vs = rs.getString("raider").equalsIgnoreCase(myClan) ? rs.getString("defender") : rs.getString("raider");
                String result = outcome != null && outcome.contains(myClan) ? "§aWon" : "§cLost";
                player.sendMessage("§7vs §e" + vs + " §7| §7Score: §c" + rs.getInt("kills_raider") +
                    "§7-§b" + rs.getInt("kills_defender") + " §7| " + result);
            }
            if (!any) player.sendMessage("§7No raid history.");
        } catch (SQLException e) { e.printStackTrace(); }
        player.sendMessage("§6=====================================");
        return true;
    }

    // ─── RAID KILL TRACKING ────────────────────────────────────────────────────

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!plugin.getConfig().getBoolean("features.clan-raid", true)) return;

        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null) return;

        String victimClan = plugin.getClanOf(victim.getUniqueId());
        String killerClan = plugin.getClanOf(killer.getUniqueId());
        if (victimClan == null || killerClan == null) return;

        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "SELECT id, raider, defender, kills_raider, kills_defender FROM clan_raids " +
            "WHERE ((raider = ? AND defender = ?) OR (raider = ? AND defender = ?)) AND active = 1")) {
            ps.setString(1, killerClan); ps.setString(2, victimClan);
            ps.setString(3, victimClan); ps.setString(4, killerClan);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return;

            int id = rs.getInt("id");
            boolean killerIsRaider = killerClan.equalsIgnoreCase(rs.getString("raider"));
            String col = killerIsRaider ? "kills_raider" : "kills_defender";

            try (PreparedStatement upd = plugin.getConnection().prepareStatement(
                "UPDATE clan_raids SET " + col + " = " + col + " + 1 WHERE id = ?")) {
                upd.setInt(1, id); upd.executeUpdate();
            }
        } catch (SQLException e) { e.printStackTrace(); }
    }

    // ─── DB HELPERS ────────────────────────────────────────────────────────────

    private void startRaid(String raider, String defender) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "INSERT INTO clan_raids(raider, defender, start_time, active) VALUES(?, ?, ?, 1)")) {
            ps.setString(1, raider); ps.setString(2, defender);
            ps.setLong(3, System.currentTimeMillis()); ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private void endRaid(String clanA, String clanB, String winner) {
        String outcome = winner != null ? (winner.equalsIgnoreCase(clanA) ? clanA + "_WIN" : clanB + "_WIN") : "DRAW";
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "UPDATE clan_raids SET active = 0, end_time = ?, outcome = ? " +
            "WHERE ((raider = ? AND defender = ?) OR (raider = ? AND defender = ?)) AND active = 1")) {
            ps.setLong(1, System.currentTimeMillis()); ps.setString(2, outcome);
            ps.setString(3, clanA); ps.setString(4, clanB);
            ps.setString(5, clanB); ps.setString(6, clanA);
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private String determineWinner(String raider, String defender) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "SELECT kills_raider, kills_defender FROM clan_raids " +
            "WHERE raider = ? AND defender = ? AND active = 1")) {
            ps.setString(1, raider); ps.setString(2, defender);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                int kr = rs.getInt("kills_raider"), kd = rs.getInt("kills_defender");
                if (kr > kd) return raider;
                if (kd > kr) return defender;
                return null;
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return null;
    }

    public boolean isRaiding(String clan) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "SELECT id FROM clan_raids WHERE raider = ? AND active = 1")) {
            ps.setString(1, clan); return ps.executeQuery().next();
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    public boolean isBeingRaided(String clan) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "SELECT id FROM clan_raids WHERE defender = ? AND active = 1")) {
            ps.setString(1, clan); return ps.executeQuery().next();
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    private boolean isActiveRaidBetween(String a, String b) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "SELECT id FROM clan_raids WHERE ((raider = ? AND defender = ?) OR (raider = ? AND defender = ?)) AND active = 1")) {
            ps.setString(1, a); ps.setString(2, b); ps.setString(3, b); ps.setString(4, a);
            return ps.executeQuery().next();
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    private String getRaidOpponent(String clan) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "SELECT raider, defender FROM clan_raids WHERE (raider = ? OR defender = ?) AND active = 1")) {
            ps.setString(1, clan); ps.setString(2, clan);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String r = rs.getString("raider"), d = rs.getString("defender");
                return r.equalsIgnoreCase(clan) ? d : r;
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return null;
    }

    private long getLastRaidTime(String clan) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "SELECT last_raid FROM clan_raid_cooldowns WHERE clan = ?")) {
            ps.setString(1, clan);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getLong("last_raid");
        } catch (SQLException e) { e.printStackTrace(); }
        return 0;
    }

    private void updateCooldown(String clan) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "INSERT OR REPLACE INTO clan_raid_cooldowns(clan, last_raid) VALUES(?, ?)")) {
            ps.setString(1, clan); ps.setLong(2, System.currentTimeMillis()); ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private void broadcastToClan(String clan, String msg) {
        for (Map.Entry<UUID, String> e : plugin.getClanMembers(clan).entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) p.sendMessage(msg);
        }
    }

    private String formatTime(long seconds) {
        long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        return String.format("%02dh %02dm %02ds", h, m, s);
    }

    private void sendRaidHelp(Player player) {
        player.sendMessage("§6===== §c🔥 Raid Commands §6=====");
        player.sendMessage("§e/clan raid start <clan>  §7- Start a raid");
        player.sendMessage("§e/clan raid end           §7- End the current raid");
        player.sendMessage("§e/clan raid status        §7- View raid status");
        player.sendMessage("§e/clan raid history       §7- View raid history");
        player.sendMessage("§6=============================");
    }
}
