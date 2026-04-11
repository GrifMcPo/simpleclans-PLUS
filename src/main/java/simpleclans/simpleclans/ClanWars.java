package simpleclans.simpleclans;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.sql.*;
import java.util.*;

/**
 * Clan Wars System
 * Commands: /clan war challenge <clan> | accept <clan> | status | surrender | info <clan>
 * Permissions: simpleclans.war.*
 * Config toggle: features.clan-wars
 */
public class ClanWars implements Listener {

    private final SimpleclansPlugin plugin;

    // Pending war challenges: challenger clan -> (challenged clan, timestamp)
    private final Map<String, Map.Entry<String, Long>> pendingChallenges = new HashMap<>();

    public ClanWars(SimpleclansPlugin plugin) {
        this.plugin = plugin;
        createTables();
    }

    // ─── TABLE ─────────────────────────────────────────────────────────────────

    private void createTables() {
        try (Statement stmt = plugin.getConnection().createStatement()) {
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS clan_wars (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "clan_a TEXT NOT NULL," +
                "clan_b TEXT NOT NULL," +
                "kills_a INTEGER DEFAULT 0," +
                "kills_b INTEGER DEFAULT 0," +
                "start_time INTEGER NOT NULL," +
                "end_time INTEGER," +
                "winner TEXT," +
                "active INTEGER DEFAULT 1)"
            );
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // ─── COMMAND HANDLER ───────────────────────────────────────────────────────

    public boolean handleCommand(Player player, String[] args) {
        if (!plugin.getConfig().getBoolean("features.clan-wars", true)) {
            player.sendMessage(plugin.getMessage("feature_disabled", Map.of()));
            return true;
        }

        if (args.length < 2) {
            sendWarHelp(player);
            return true;
        }

        String sub = args[1].toLowerCase();

        return switch (sub) {
            case "challenge" -> handleChallenge(player, args);
            case "accept"    -> handleAccept(player, args);
            case "surrender" -> handleSurrender(player);
            case "status"    -> handleStatus(player);
            case "info"      -> handleInfo(player, args);
            default -> { sendWarHelp(player); yield true; }
        };
    }

    private boolean handleChallenge(Player player, String[] args) {
        if (!player.hasPermission("simpleclans.war.challenge")) {
            player.sendMessage(plugin.getMessage("no_permission", Map.of()));
            return true;
        }

        UUID uuid  = player.getUniqueId();
        String myClan = plugin.getClanOf(uuid);
        if (myClan == null) { player.sendMessage(plugin.getMessage("not_in_clan", Map.of())); return true; }

        String role = plugin.getRoleOf(uuid);
        if (!role.equalsIgnoreCase("LEADER") && !role.equalsIgnoreCase("CO-LEADER")) {
            player.sendMessage(plugin.getMessage("no_permission", Map.of()));
            return true;
        }

        if (args.length < 3) {
            player.sendMessage("§c[!] Usage: /clan war challenge <clan>");
            return true;
        }

        String targetClan = args[2];
        if (!plugin.getAllClanNames().stream().anyMatch(c -> c.equalsIgnoreCase(targetClan))) {
            player.sendMessage("§c[!] Clan '" + targetClan + "' does not exist.");
            return true;
        }

        String exactTarget = plugin.getAllClanNames().stream()
            .filter(c -> c.equalsIgnoreCase(targetClan)).findFirst().orElse(targetClan);

        if (exactTarget.equalsIgnoreCase(myClan)) {
            player.sendMessage("§c[!] You cannot challenge your own clan.");
            return true;
        }

        if (isAtWar(myClan)) {
            player.sendMessage("§c[!] Your clan is already at war!");
            return true;
        }
        if (isAtWar(exactTarget)) {
            player.sendMessage("§c[!] That clan is already at war.");
            return true;
        }

        int minMembers = plugin.getConfig().getInt("clan-wars.min-members", 2);
        if (plugin.getClanMemberCount(myClan) < minMembers) {
            player.sendMessage("§c[!] Your clan needs at least " + minMembers + " members to declare war.");
            return true;
        }

        pendingChallenges.put(myClan, Map.entry(exactTarget, System.currentTimeMillis()));

        // Notify target clan
        broadcastToClan(exactTarget,
            "§c§l⚔ WAR CHALLENGE! §r§e" + myClan + " §7has challenged §e" + exactTarget + " §7to war! " +
            "A leader/co-leader can type §f/clan war accept " + myClan + " §7to accept.");
        player.sendMessage("§6[Simpleclan-PLUS] §aWar challenge sent to §e" + exactTarget + "§a!");
        return true;
    }

    private boolean handleAccept(Player player, String[] args) {
        if (!player.hasPermission("simpleclans.war.accept")) {
            player.sendMessage(plugin.getMessage("no_permission", Map.of()));
            return true;
        }

        UUID uuid = player.getUniqueId();
        String myClan = plugin.getClanOf(uuid);
        if (myClan == null) { player.sendMessage(plugin.getMessage("not_in_clan", Map.of())); return true; }

        String role = plugin.getRoleOf(uuid);
        if (!role.equalsIgnoreCase("LEADER") && !role.equalsIgnoreCase("CO-LEADER")) {
            player.sendMessage(plugin.getMessage("no_permission", Map.of()));
            return true;
        }

        if (args.length < 3) {
            player.sendMessage("§c[!] Usage: /clan war accept <clan>");
            return true;
        }

        String challengerClan = args[2];
        Map.Entry<String, Long> challenge = pendingChallenges.get(challengerClan);
        if (challenge == null || !challenge.getKey().equalsIgnoreCase(myClan)) {
            player.sendMessage("§c[!] No pending war challenge from '" + challengerClan + "'.");
            return true;
        }

        // Expire after 5 minutes
        if (System.currentTimeMillis() - challenge.getValue() > 5 * 60 * 1000L) {
            pendingChallenges.remove(challengerClan);
            player.sendMessage("§c[!] That war challenge has expired.");
            return true;
        }

        pendingChallenges.remove(challengerClan);
        startWar(challengerClan, myClan);

        broadcastToClan(myClan,       "§c§l⚔ WAR STARTED! §r§7You are now at war with §e" + challengerClan + "§7!");
        broadcastToClan(challengerClan, "§c§l⚔ WAR STARTED! §r§7You are now at war with §e" + myClan + "§7!");
        return true;
    }

    private boolean handleSurrender(Player player) {
        if (!player.hasPermission("simpleclans.war.surrender")) {
            player.sendMessage(plugin.getMessage("no_permission", Map.of()));
            return true;
        }

        UUID uuid = player.getUniqueId();
        String myClan = plugin.getClanOf(uuid);
        if (myClan == null) { player.sendMessage(plugin.getMessage("not_in_clan", Map.of())); return true; }

        String role = plugin.getRoleOf(uuid);
        if (!role.equalsIgnoreCase("LEADER")) {
            player.sendMessage("§c[!] Only the clan leader can surrender.");
            return true;
        }

        String opponent = getWarOpponent(myClan);
        if (opponent == null) {
            player.sendMessage("§c[!] Your clan is not currently at war.");
            return true;
        }

        endWar(myClan, opponent, opponent); // opponent wins

        broadcastToClan(myClan,    "§c§l☠ SURRENDER! §r§7Your clan has surrendered the war against §e" + opponent + "§7.");
        broadcastToClan(opponent,  "§a§l🏆 VICTORY! §r§e" + myClan + " §7has surrendered! Your clan wins the war!");
        return true;
    }

    private boolean handleStatus(Player player) {
        if (!player.hasPermission("simpleclans.war.status")) {
            player.sendMessage(plugin.getMessage("no_permission", Map.of()));
            return true;
        }

        String myClan = plugin.getClanOf(player.getUniqueId());
        if (myClan == null) { player.sendMessage(plugin.getMessage("not_in_clan", Map.of())); return true; }

        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "SELECT * FROM clan_wars WHERE (clan_a = ? OR clan_b = ?) AND active = 1")) {
            ps.setString(1, myClan);
            ps.setString(2, myClan);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) {
                player.sendMessage("§7Your clan is not currently at war.");
                return true;
            }
            String clanA  = rs.getString("clan_a");
            String clanB  = rs.getString("clan_b");
            int killsA    = rs.getInt("kills_a");
            int killsB    = rs.getInt("kills_b");
            long start    = rs.getLong("start_time");
            long elapsed  = (System.currentTimeMillis() - start) / 1000;

            player.sendMessage("§6===== §c⚔ War Status §6=====");
            player.sendMessage("§e" + clanA + " §7vs §e" + clanB);
            player.sendMessage("§7Kills: §c" + clanA + " §f" + killsA + " §7| §c" + clanB + " §f" + killsB);
            player.sendMessage("§7Duration: §f" + formatTime(elapsed));
            player.sendMessage("§6==========================");
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return true;
    }

    private boolean handleInfo(Player player, String[] args) {
        if (!player.hasPermission("simpleclans.war.info")) {
            player.sendMessage(plugin.getMessage("no_permission", Map.of()));
            return true;
        }

        if (args.length < 3) { player.sendMessage("§c[!] Usage: /clan war info <clan>"); return true; }
        String targetClan = args[2];

        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "SELECT * FROM clan_wars WHERE (clan_a = ? OR clan_b = ?) ORDER BY id DESC LIMIT 5")) {
            ps.setString(1, targetClan);
            ps.setString(2, targetClan);
            ResultSet rs = ps.executeQuery();
            player.sendMessage("§6===== §eWar History: §f" + targetClan + " §6=====");
            boolean any = false;
            while (rs.next()) {
                any = true;
                String clanA  = rs.getString("clan_a");
                String clanB  = rs.getString("clan_b");
                String winner = rs.getString("winner");
                int kA = rs.getInt("kills_a"), kB = rs.getInt("kills_b");
                String result = winner == null ? "§eOngoing" : (winner.equalsIgnoreCase(targetClan) ? "§aWon" : "§cLost");
                player.sendMessage("§7vs §e" + (clanA.equalsIgnoreCase(targetClan) ? clanB : clanA) +
                    " §7| §7Score: §f" + kA + "§7-§f" + kB + " §7| " + result);
            }
            if (!any) player.sendMessage("§7No war history found.");
            player.sendMessage("§6=====================================");
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return true;
    }

    // ─── WAR KILL TRACKING ─────────────────────────────────────────────────────

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!plugin.getConfig().getBoolean("features.clan-wars", true)) return;

        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) return;

        String killerClan = plugin.getClanOf(killer.getUniqueId());
        String victimClan = plugin.getClanOf(victim.getUniqueId());
        if (killerClan == null || victimClan == null) return;
        if (killerClan.equalsIgnoreCase(victimClan)) return;

        // Check if these two clans are at war
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "SELECT id, clan_a, clan_b, kills_a, kills_b FROM clan_wars " +
            "WHERE ((clan_a = ? AND clan_b = ?) OR (clan_a = ? AND clan_b = ?)) AND active = 1")) {
            ps.setString(1, killerClan); ps.setString(2, victimClan);
            ps.setString(3, victimClan); ps.setString(4, killerClan);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return;

            int id     = rs.getInt("id");
            String clanA = rs.getString("clan_a");
            boolean killerIsA = killerClan.equalsIgnoreCase(clanA);
            String col = killerIsA ? "kills_a" : "kills_b";
            int newKills = rs.getInt(col) + 1;

            try (PreparedStatement upd = plugin.getConnection().prepareStatement(
                "UPDATE clan_wars SET " + col + " = ? WHERE id = ?")) {
                upd.setInt(1, newKills);
                upd.setInt(2, id);
                upd.executeUpdate();
            }

            int warKillLimit = plugin.getConfig().getInt("clan-wars.kill-limit", 0);
            if (warKillLimit > 0 && newKills >= warKillLimit) {
                String loser = killerIsA ? rs.getString("clan_b") : clanA;
                endWar(killerClan, loser, killerClan);
                broadcastToClan(killerClan, "§a§l🏆 WAR WON! §r§7You have reached the kill limit and won the war!");
                broadcastToClan(loser,      "§c§l☠ WAR LOST! §r§7You have lost the war against §e" + killerClan + "§7.");
            } else {
                broadcastToClan(killerClan, "§c⚔ War kill! §e" + killer.getName() + " §7killed §e" + victim.getName() +
                    " §7(§e" + victimClan + "§7). War kill: §f" + newKills);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // ─── HELPERS ───────────────────────────────────────────────────────────────

    private void startWar(String clanA, String clanB) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "INSERT INTO clan_wars(clan_a, clan_b, start_time, active) VALUES(?, ?, ?, 1)")) {
            ps.setString(1, clanA);
            ps.setString(2, clanB);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void endWar(String clanA, String clanB, String winner) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "UPDATE clan_wars SET active = 0, end_time = ?, winner = ? " +
            "WHERE ((clan_a = ? AND clan_b = ?) OR (clan_a = ? AND clan_b = ?)) AND active = 1")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, winner);
            ps.setString(3, clanA); ps.setString(4, clanB);
            ps.setString(5, clanB); ps.setString(6, clanA);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public boolean isAtWar(String clan) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "SELECT id FROM clan_wars WHERE (clan_a = ? OR clan_b = ?) AND active = 1")) {
            ps.setString(1, clan); ps.setString(2, clan);
            return ps.executeQuery().next();
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    public String getWarOpponent(String clan) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "SELECT clan_a, clan_b FROM clan_wars WHERE (clan_a = ? OR clan_b = ?) AND active = 1")) {
            ps.setString(1, clan); ps.setString(2, clan);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String a = rs.getString("clan_a"), b = rs.getString("clan_b");
                return a.equalsIgnoreCase(clan) ? b : a;
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return null;
    }

    public boolean areAtWar(String clan1, String clan2) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "SELECT id FROM clan_wars WHERE ((clan_a = ? AND clan_b = ?) OR (clan_a = ? AND clan_b = ?)) AND active = 1")) {
            ps.setString(1, clan1); ps.setString(2, clan2);
            ps.setString(3, clan2); ps.setString(4, clan1);
            return ps.executeQuery().next();
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    private void broadcastToClan(String clan, String message) {
        for (Map.Entry<UUID, String> entry : plugin.getClanMembers(clan).entrySet()) {
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p != null) p.sendMessage(message);
        }
    }

    private String formatTime(long seconds) {
        long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        return String.format("%02dh %02dm %02ds", h, m, s);
    }

    private void sendWarHelp(Player player) {
        player.sendMessage("§6===== §c⚔ War Commands §6=====");
        player.sendMessage("§e/clan war challenge <clan> §7- Challenge a clan to war");
        player.sendMessage("§e/clan war accept <clan>    §7- Accept a war challenge");
        player.sendMessage("§e/clan war surrender        §7- Surrender the current war");
        player.sendMessage("§e/clan war status           §7- View war status");
        player.sendMessage("§e/clan war info <clan>      §7- View war history");
        player.sendMessage("§6================================");
    }
}
