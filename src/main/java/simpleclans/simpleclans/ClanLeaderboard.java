package simpleclans.simpleclans;

import org.bukkit.entity.Player;

import java.sql.*;
import java.util.*;

/**
 * Clan Leaderboards
 * Commands: /clan top [kills|level|members|bank]
 * Permissions: simpleclans.leaderboard
 * Config toggle: features.clan-leaderboards
 */
public class ClanLeaderboard {

    private final SimpleclansPlugin plugin;
    private ClanBank clanBank = null; // optional, set via setter if Vault present

    public ClanLeaderboard(SimpleclansPlugin plugin) {
        this.plugin = plugin;
    }

    public void setClanBank(ClanBank bank) { this.clanBank = bank; }

    // ─── COMMAND HANDLER ───────────────────────────────────────────────────────

    public boolean handleCommand(Player player, String[] args) {
        if (!plugin.getConfig().getBoolean("features.clan-leaderboards", true)) {
            player.sendMessage(plugin.getMessage("feature_disabled", Map.of())); return true;
        }
        if (!player.hasPermission("simpleclans.leaderboard")) {
            player.sendMessage(plugin.getMessage("no_permission", Map.of())); return true;
        }

        String type = (args.length >= 2) ? args[1].toLowerCase() : "kills";

        return switch (type) {
            case "kills"   -> showKillsLeaderboard(player);
            case "level"   -> showLevelLeaderboard(player);
            case "members" -> showMembersLeaderboard(player);
            case "bank"    -> showBankLeaderboard(player);
            default -> {
                player.sendMessage("§c[!] Usage: /clan top [kills|level|members|bank]");
                yield true;
            }
        };
    }

    private boolean showKillsLeaderboard(Player player) {
        int limit = plugin.getConfig().getInt("clan-leaderboards.display-count", 10);
        player.sendMessage("§6===== §c⚔ Top " + limit + " Clans — Kills §6=====");

        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "SELECT name, kills, level FROM clans ORDER BY kills DESC LIMIT ?")) {
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            int rank = 1;
            while (rs.next()) {
                String medal = getMedal(rank);
                player.sendMessage(medal + " §e" + rs.getString("name") +
                    " §7| Kills: §c" + rs.getInt("kills") +
                    " §7| Level: §b" + rs.getInt("level"));
                rank++;
            }
            if (rank == 1) player.sendMessage("§7No clans found.");
        } catch (SQLException e) { e.printStackTrace(); }

        player.sendMessage("§6======================================");
        player.sendMessage("§7Other: §e/clan top level §7| §e/clan top members §7| §e/clan top bank");
        return true;
    }

    private boolean showLevelLeaderboard(Player player) {
        int limit = plugin.getConfig().getInt("clan-leaderboards.display-count", 10);
        player.sendMessage("§6===== §b⭐ Top " + limit + " Clans — Level §6=====");

        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "SELECT name, level, kills FROM clans ORDER BY level DESC, kills DESC LIMIT ?")) {
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            int rank = 1;
            while (rs.next()) {
                String medal = getMedal(rank);
                player.sendMessage(medal + " §e" + rs.getString("name") +
                    " §7| Level: §b" + rs.getInt("level") +
                    " §7| Kills: §c" + rs.getInt("kills"));
                rank++;
            }
            if (rank == 1) player.sendMessage("§7No clans found.");
        } catch (SQLException e) { e.printStackTrace(); }

        player.sendMessage("§6======================================");
        return true;
    }

    private boolean showMembersLeaderboard(Player player) {
        int limit = plugin.getConfig().getInt("clan-leaderboards.display-count", 10);
        player.sendMessage("§6===== §a👥 Top " + limit + " Clans — Members §6=====");

        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "SELECT c.name, COUNT(m.uuid) as member_count, c.level " +
            "FROM clans c LEFT JOIN clan_members m ON c.name = m.clan " +
            "GROUP BY c.name ORDER BY member_count DESC LIMIT ?")) {
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            int rank = 1;
            while (rs.next()) {
                String clan = rs.getString("name");
                int count   = rs.getInt("member_count");
                int online  = plugin.getOnlineClanMembers(clan);
                String medal = getMedal(rank);
                player.sendMessage(medal + " §e" + clan +
                    " §7| Members: §a" + count +
                    " §7(§a" + online + " §7online) | Level: §b" + rs.getInt("level"));
                rank++;
            }
            if (rank == 1) player.sendMessage("§7No clans found.");
        } catch (SQLException e) { e.printStackTrace(); }

        player.sendMessage("§6=========================================");
        return true;
    }

    private boolean showBankLeaderboard(Player player) {
        if (clanBank == null) {
            player.sendMessage("§c[!] The bank leaderboard requires Vault and the Clan Bank feature.");
            return true;
        }

        int limit = plugin.getConfig().getInt("clan-leaderboards.display-count", 10);
        player.sendMessage("§6===== §e💰 Top " + limit + " Clans — Bank Balance §6=====");

        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "SELECT clan, balance FROM clan_bank ORDER BY balance DESC LIMIT ?")) {
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            int rank = 1;
            while (rs.next()) {
                String medal = getMedal(rank);
                player.sendMessage(medal + " §e" + rs.getString("clan") +
                    " §7| Balance: §a" + String.format("%.2f", rs.getDouble("balance")));
                rank++;
            }
            if (rank == 1) player.sendMessage("§7No clans have a bank balance yet.");
        } catch (SQLException e) { e.printStackTrace(); }

        player.sendMessage("§6===========================================");
        return true;
    }

    // ─── HELPERS ───────────────────────────────────────────────────────────────

    private String getMedal(int rank) {
        return switch (rank) {
            case 1 -> "§6§l#1 🥇";
            case 2 -> "§7§l#2 🥈";
            case 3 -> "§c§l#3 🥉";
            default -> "§f§l#" + rank;
        };
    }
}
