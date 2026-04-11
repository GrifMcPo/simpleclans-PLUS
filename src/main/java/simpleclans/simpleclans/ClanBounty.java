package simpleclans.simpleclans;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.sql.*;
import java.util.*;

/**
 * Clan Bounty System (requires Vault)
 * Commands: /clan bounty set <player> <amount> | list | remove <player>
 * Permissions: simpleclans.bounty.*
 * Config toggle: features.clan-bounty
 */
public class ClanBounty implements Listener {

    private final SimpleclansPlugin plugin;
    private Economy economy = null;

    public ClanBounty(SimpleclansPlugin plugin) {
        this.plugin = plugin;
        setupEconomy();
        createTables();
    }

    private void setupEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return;
        RegisteredServiceProvider<Economy> rsp =
            Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp != null) economy = rsp.getProvider();
    }

    private void createTables() {
        try (Statement stmt = plugin.getConnection().createStatement()) {
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS clan_bounties (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "target_uuid TEXT NOT NULL," +
                "target_name TEXT NOT NULL," +
                "placed_by_uuid TEXT NOT NULL," +
                "placed_by_name TEXT NOT NULL," +
                "amount REAL NOT NULL," +
                "placed_time INTEGER NOT NULL," +
                "active INTEGER DEFAULT 1)"
            );
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // ─── COMMAND HANDLER ───────────────────────────────────────────────────────

    public boolean handleCommand(Player player, String[] args) {
        if (!plugin.getConfig().getBoolean("features.clan-bounty", true)) {
            player.sendMessage(plugin.getMessage("feature_disabled", Map.of())); return true;
        }

        if (args.length < 2) { sendBountyHelp(player); return true; }

        return switch (args[1].toLowerCase()) {
            case "set"    -> handleSet(player, args);
            case "list"   -> handleList(player);
            case "remove" -> handleRemove(player, args);
            default -> { sendBountyHelp(player); yield true; }
        };
    }

    private boolean handleSet(Player player, String[] args) {
        if (!player.hasPermission("simpleclans.bounty.set")) {
            player.sendMessage(plugin.getMessage("no_permission", Map.of())); return true;
        }

        if (economy == null) {
            player.sendMessage("§c[!] Clan Bounty requires Vault and an economy plugin."); return true;
        }

        if (args.length < 4) {
            player.sendMessage("§c[!] Usage: /clan bounty set <player> <amount>"); return true;
        }

        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            player.sendMessage(plugin.getMessage("player_not_found", Map.of())); return true;
        }
        if (target.equals(player)) {
            player.sendMessage("§c[!] You cannot place a bounty on yourself."); return true;
        }

        double amount;
        try { amount = Double.parseDouble(args[3]); }
        catch (NumberFormatException e) { player.sendMessage("§c[!] Invalid amount."); return true; }

        double minBounty = plugin.getConfig().getDouble("clan-bounty.min-bounty", 100);
        if (amount < minBounty) {
            player.sendMessage("§c[!] Minimum bounty amount is §f" + minBounty + "§c."); return true;
        }

        if (!economy.has(player, amount)) {
            player.sendMessage("§c[!] You don't have enough money."); return true;
        }

        economy.withdrawPlayer(player, amount);
        placeBounty(target.getUniqueId(), target.getName(), player.getUniqueId(), player.getName(), amount);

        // Announce to the server
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage("§6§l[BOUNTY] §e" + player.getName() + " §7has placed a bounty of §a" +
                economy.format(amount) + " §7on §c" + target.getName() + "§7!");
        }
        return true;
    }

    private boolean handleList(Player player) {
        if (!player.hasPermission("simpleclans.bounty.list")) {
            player.sendMessage(plugin.getMessage("no_permission", Map.of())); return true;
        }

        player.sendMessage("§6===== §e💰 Active Bounties §6=====");
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "SELECT target_name, placed_by_name, amount FROM clan_bounties WHERE active = 1 ORDER BY amount DESC")) {
            ResultSet rs = ps.executeQuery();
            boolean any = false;
            while (rs.next()) {
                any = true;
                String amount = economy != null ? economy.format(rs.getDouble("amount"))
                    : String.valueOf(rs.getDouble("amount"));
                player.sendMessage("§c" + rs.getString("target_name") + " §7- §a" + amount +
                    " §7(by §e" + rs.getString("placed_by_name") + "§7)");
            }
            if (!any) player.sendMessage("§7No active bounties.");
        } catch (SQLException e) { e.printStackTrace(); }
        player.sendMessage("§6================================");
        return true;
    }

    private boolean handleRemove(Player player, String[] args) {
        if (!player.hasPermission("simpleclans.bounty.remove")) {
            player.sendMessage(plugin.getMessage("no_permission", Map.of())); return true;
        }
        if (args.length < 3) { player.sendMessage("§c[!] Usage: /clan bounty remove <player>"); return true; }

        String targetName = args[2];
        Player target = Bukkit.getPlayerExact(targetName);
        UUID targetUUID = target != null ? target.getUniqueId() : null;

        // Allow removing by name even if offline (admin perm)
        boolean isAdmin = player.hasPermission("simpleclans.admin");

        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "SELECT id, amount, placed_by_uuid FROM clan_bounties WHERE target_name = ? AND active = 1 LIMIT 1")) {
            ps.setString(1, targetName);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) {
                player.sendMessage("§c[!] No active bounty found for §f" + targetName + "§c."); return true;
            }
            String placedBy = rs.getString("placed_by_uuid");
            if (!isAdmin && !placedBy.equals(player.getUniqueId().toString())) {
                player.sendMessage("§c[!] You can only remove bounties you placed."); return true;
            }
            int id = rs.getInt("id");
            double amount = rs.getDouble("amount");

            // Refund
            if (economy != null) {
                economy.depositPlayer(player, amount);
                player.sendMessage("§6[Bounty] §aRefunded §f" + economy.format(amount) + " §ato your account.");
            }
            try (PreparedStatement del = plugin.getConnection().prepareStatement(
                "UPDATE clan_bounties SET active = 0 WHERE id = ?")) {
                del.setInt(1, id); del.executeUpdate();
            }
            player.sendMessage("§6[Bounty] §aBounty on §e" + targetName + " §aremoved.");
        } catch (SQLException e) { e.printStackTrace(); }
        return true;
    }

    // ─── BOUNTY CLAIM ON KILL ──────────────────────────────────────────────────

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!plugin.getConfig().getBoolean("features.clan-bounty", true)) return;

        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null) return;
        if (economy == null) return;

        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "SELECT id, amount, placed_by_name FROM clan_bounties WHERE target_uuid = ? AND active = 1")) {
            ps.setString(1, victim.getUniqueId().toString());
            ResultSet rs = ps.executeQuery();
            double total = 0;
            List<Integer> ids = new ArrayList<>();
            List<String> placers = new ArrayList<>();
            while (rs.next()) {
                total += rs.getDouble("amount");
                ids.add(rs.getInt("id"));
                placers.add(rs.getString("placed_by_name"));
            }
            if (total <= 0) return;

            for (int id : ids) {
                try (PreparedStatement upd = plugin.getConnection().prepareStatement(
                    "UPDATE clan_bounties SET active = 0 WHERE id = ?")) {
                    upd.setInt(1, id); upd.executeUpdate();
                }
            }

            economy.depositPlayer(killer, total);
            String formatted = economy.format(total);

            for (Player online : Bukkit.getOnlinePlayers()) {
                online.sendMessage("§6§l[BOUNTY CLAIMED!] §e" + killer.getName() +
                    " §7collected §a" + formatted + " §7for killing §c" + victim.getName() + "§7!");
            }
        } catch (SQLException e) { e.printStackTrace(); }
    }

    // ─── DB ────────────────────────────────────────────────────────────────────

    private void placeBounty(UUID targetUUID, String targetName, UUID placerUUID, String placerName, double amount) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "INSERT INTO clan_bounties(target_uuid, target_name, placed_by_uuid, placed_by_name, amount, placed_time, active) " +
            "VALUES(?, ?, ?, ?, ?, ?, 1)")) {
            ps.setString(1, targetUUID.toString());
            ps.setString(2, targetName);
            ps.setString(3, placerUUID.toString());
            ps.setString(4, placerName);
            ps.setDouble(5, amount);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private void sendBountyHelp(Player player) {
        player.sendMessage("§6===== §e💰 Bounty Commands §6=====");
        player.sendMessage("§e/clan bounty set <player> <amount>  §7- Place a bounty");
        player.sendMessage("§e/clan bounty list                   §7- List active bounties");
        player.sendMessage("§e/clan bounty remove <player>        §7- Remove your bounty");
        player.sendMessage("§6==================================");
    }
}
