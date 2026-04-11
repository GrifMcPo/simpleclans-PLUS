package simpleclans.simpleclans;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.sql.*;
import java.util.*;

/**
 * Clan Alliances System
 * Commands: /clan ally add <clan> | accept <clan> | remove <clan> | list
 * Permissions: simpleclans.ally.*
 * Config toggle: features.clan-alliances
 */
public class ClanAlliances implements Listener {

    private final SimpleclansPlugin plugin;

    // Pending proposals: proposingClan -> (targetClan, timestamp)
    private final Map<String, Map.Entry<String, Long>> pendingProposals = new HashMap<>();

    public ClanAlliances(SimpleclansPlugin plugin) {
        this.plugin = plugin;
        createTables();
    }

    private void createTables() {
        try (Statement stmt = plugin.getConnection().createStatement()) {
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS clan_alliances (" +
                "clan_a TEXT NOT NULL," +
                "clan_b TEXT NOT NULL," +
                "since INTEGER NOT NULL," +
                "PRIMARY KEY(clan_a, clan_b))"
            );
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // ─── COMMAND HANDLER ───────────────────────────────────────────────────────

    public boolean handleCommand(Player player, String[] args) {
        if (!plugin.getConfig().getBoolean("features.clan-alliances", true)) {
            player.sendMessage(plugin.getMessage("feature_disabled", Map.of()));
            return true;
        }

        if (args.length < 2) { sendAllyHelp(player); return true; }
        return switch (args[1].toLowerCase()) {
            case "add"    -> handleAdd(player, args);
            case "accept" -> handleAccept(player, args);
            case "remove" -> handleRemove(player, args);
            case "list"   -> handleList(player);
            default -> { sendAllyHelp(player); yield true; }
        };
    }

    private boolean handleAdd(Player player, String[] args) {
        if (!player.hasPermission("simpleclans.ally.add")) {
            player.sendMessage(plugin.getMessage("no_permission", Map.of())); return true;
        }
        String myClan = plugin.getClanOf(player.getUniqueId());
        if (myClan == null) { player.sendMessage(plugin.getMessage("not_in_clan", Map.of())); return true; }

        String role = plugin.getRoleOf(player.getUniqueId());
        if (!role.equalsIgnoreCase("LEADER") && !role.equalsIgnoreCase("CO-LEADER")) {
            player.sendMessage(plugin.getMessage("no_permission", Map.of())); return true;
        }
        if (args.length < 3) { player.sendMessage("§c[!] Usage: /clan ally add <clan>"); return true; }

        String targetClan = args[2];
        if (!plugin.getAllClanNames().stream().anyMatch(c -> c.equalsIgnoreCase(targetClan))) {
            player.sendMessage("§c[!] Clan '" + targetClan + "' does not exist."); return true;
        }
        String exact = plugin.getAllClanNames().stream()
            .filter(c -> c.equalsIgnoreCase(targetClan)).findFirst().orElse(targetClan);

        if (exact.equalsIgnoreCase(myClan)) {
            player.sendMessage("§c[!] You cannot ally your own clan."); return true;
        }

        int maxAlliances = plugin.getConfig().getInt("clan-alliances.max-alliances", 5);
        if (getAllyCount(myClan) >= maxAlliances) {
            player.sendMessage("§c[!] You have reached the maximum number of alliances (" + maxAlliances + ")."); return true;
        }

        if (areAllied(myClan, exact)) {
            player.sendMessage("§c[!] You are already allied with " + exact + "."); return true;
        }

        pendingProposals.put(myClan, Map.entry(exact, System.currentTimeMillis()));

        broadcastToClan(exact,
            "§a§l🤝 ALLIANCE OFFER! §r§e" + myClan + " §7has proposed an alliance. " +
            "A leader can type §f/clan ally accept " + myClan + " §7to accept.");
        player.sendMessage("§6[Simpleclan-PLUS] §aAlliance proposal sent to §e" + exact + "§a!");
        return true;
    }

    private boolean handleAccept(Player player, String[] args) {
        if (!player.hasPermission("simpleclans.ally.accept")) {
            player.sendMessage(plugin.getMessage("no_permission", Map.of())); return true;
        }
        String myClan = plugin.getClanOf(player.getUniqueId());
        if (myClan == null) { player.sendMessage(plugin.getMessage("not_in_clan", Map.of())); return true; }

        String role = plugin.getRoleOf(player.getUniqueId());
        if (!role.equalsIgnoreCase("LEADER") && !role.equalsIgnoreCase("CO-LEADER")) {
            player.sendMessage(plugin.getMessage("no_permission", Map.of())); return true;
        }
        if (args.length < 3) { player.sendMessage("§c[!] Usage: /clan ally accept <clan>"); return true; }

        String proposer = args[2];
        Map.Entry<String, Long> proposal = pendingProposals.get(proposer);
        if (proposal == null || !proposal.getKey().equalsIgnoreCase(myClan)) {
            player.sendMessage("§c[!] No pending alliance from '" + proposer + "'."); return true;
        }
        if (System.currentTimeMillis() - proposal.getValue() > 5 * 60 * 1000L) {
            pendingProposals.remove(proposer);
            player.sendMessage("§c[!] That alliance proposal has expired."); return true;
        }

        pendingProposals.remove(proposer);
        formAlliance(proposer, myClan);

        broadcastToClan(myClan,   "§a§l🤝 ALLIANCE FORMED! §r§7You are now allied with §e" + proposer + "§7!");
        broadcastToClan(proposer, "§a§l🤝 ALLIANCE FORMED! §r§7You are now allied with §e" + myClan + "§7!");
        return true;
    }

    private boolean handleRemove(Player player, String[] args) {
        if (!player.hasPermission("simpleclans.ally.remove")) {
            player.sendMessage(plugin.getMessage("no_permission", Map.of())); return true;
        }
        String myClan = plugin.getClanOf(player.getUniqueId());
        if (myClan == null) { player.sendMessage(plugin.getMessage("not_in_clan", Map.of())); return true; }

        String role = plugin.getRoleOf(player.getUniqueId());
        if (!role.equalsIgnoreCase("LEADER")) {
            player.sendMessage("§c[!] Only the leader can remove alliances."); return true;
        }
        if (args.length < 3) { player.sendMessage("§c[!] Usage: /clan ally remove <clan>"); return true; }

        String targetClan = args[2];
        if (!areAllied(myClan, targetClan)) {
            player.sendMessage("§c[!] You are not allied with '" + targetClan + "'."); return true;
        }

        removeAlliance(myClan, targetClan);
        broadcastToClan(myClan,      "§e[Alliances] §7Alliance with §e" + targetClan + " §7has been dissolved.");
        broadcastToClan(targetClan,  "§e[Alliances] §7Alliance with §e" + myClan + " §7has been dissolved.");
        return true;
    }

    private boolean handleList(Player player) {
        if (!player.hasPermission("simpleclans.ally.list")) {
            player.sendMessage(plugin.getMessage("no_permission", Map.of())); return true;
        }
        String myClan = plugin.getClanOf(player.getUniqueId());
        if (myClan == null) { player.sendMessage(plugin.getMessage("not_in_clan", Map.of())); return true; }

        List<String> allies = getAllies(myClan);
        player.sendMessage("§6===== §a🤝 Allies of §e" + myClan + " §6=====");
        if (allies.isEmpty()) {
            player.sendMessage("§7No alliances formed yet.");
        } else {
            for (String ally : allies) {
                int members = plugin.getClanMemberCount(ally);
                int online  = plugin.getOnlineClanMembers(ally);
                player.sendMessage("§a✔ §e" + ally + " §7(§f" + members + " §7members, §a" + online + " §7online)");
            }
        }
        player.sendMessage("§6====================================");
        return true;
    }

    // ─── FRIENDLY FIRE PROTECTION ─────────────────────────────────────────────

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!plugin.getConfig().getBoolean("features.clan-alliances", true)) return;
        if (!plugin.getConfig().getBoolean("clan-alliances.friendly-fire-protection", true)) return;

        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity()  instanceof Player victim))   return;

        String attackerClan = plugin.getClanOf(attacker.getUniqueId());
        String victimClan   = plugin.getClanOf(victim.getUniqueId());
        if (attackerClan == null || victimClan == null) return;

        if (areAllied(attackerClan, victimClan)) {
            event.setCancelled(true);
            attacker.sendMessage("§a[Ally] §7You cannot attack your ally §e" + victim.getName() + "§7!");
        }
    }

    // ─── DATABASE HELPERS ──────────────────────────────────────────────────────

    private void formAlliance(String clanA, String clanB) {
        // Store both directions for easy lookup
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "INSERT OR IGNORE INTO clan_alliances(clan_a, clan_b, since) VALUES(?, ?, ?)")) {
            long now = System.currentTimeMillis();
            ps.setString(1, clanA); ps.setString(2, clanB); ps.setLong(3, now); ps.executeUpdate();
            ps.setString(1, clanB); ps.setString(2, clanA); ps.setLong(3, now); ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private void removeAlliance(String clanA, String clanB) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "DELETE FROM clan_alliances WHERE (clan_a = ? AND clan_b = ?) OR (clan_a = ? AND clan_b = ?)")) {
            ps.setString(1, clanA); ps.setString(2, clanB);
            ps.setString(3, clanB); ps.setString(4, clanA);
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public boolean areAllied(String clanA, String clanB) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "SELECT 1 FROM clan_alliances WHERE clan_a = ? AND clan_b = ?")) {
            ps.setString(1, clanA); ps.setString(2, clanB);
            return ps.executeQuery().next();
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    public List<String> getAllies(String clan) {
        List<String> allies = new ArrayList<>();
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "SELECT clan_b FROM clan_alliances WHERE clan_a = ?")) {
            ps.setString(1, clan);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) allies.add(rs.getString("clan_b"));
        } catch (SQLException e) { e.printStackTrace(); }
        return allies;
    }

    public int getAllyCount(String clan) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "SELECT COUNT(*) AS c FROM clan_alliances WHERE clan_a = ?")) {
            ps.setString(1, clan);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt("c");
        } catch (SQLException e) { e.printStackTrace(); }
        return 0;
    }

    /** Called when a clan is disbanded — removes all its alliances. */
    public void removeAllAlliancesFor(String clan) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "DELETE FROM clan_alliances WHERE clan_a = ? OR clan_b = ?")) {
            ps.setString(1, clan); ps.setString(2, clan);
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private void broadcastToClan(String clan, String message) {
        for (Map.Entry<UUID, String> entry : plugin.getClanMembers(clan).entrySet()) {
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p != null) p.sendMessage(message);
        }
    }

    private void sendAllyHelp(Player player) {
        player.sendMessage("§6===== §a🤝 Alliance Commands §6=====");
        player.sendMessage("§e/clan ally add <clan>    §7- Propose an alliance");
        player.sendMessage("§e/clan ally accept <clan> §7- Accept an alliance");
        player.sendMessage("§e/clan ally remove <clan> §7- Dissolve an alliance");
        player.sendMessage("§e/clan ally list          §7- List your allies");
        player.sendMessage("§6===================================");
    }
}
