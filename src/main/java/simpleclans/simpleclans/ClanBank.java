package simpleclans.simpleclans;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.sql.*;
import java.util.Map;
import java.util.UUID;

/**
 * Clan Bank System (requires Vault + an economy plugin)
 * Commands: /clan bank deposit <amount> | withdraw <amount> | balance | log
 * Permissions: simpleclans.bank.*
 * Config toggle: features.clan-bank
 */
public class ClanBank {

    private final SimpleclansPlugin plugin;
    private Economy economy = null;

    public ClanBank(SimpleclansPlugin plugin) {
        this.plugin = plugin;
        setupEconomy();
        createTables();
    }

    private boolean setupEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp =
            Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }

    private void createTables() {
        try (Statement stmt = plugin.getConnection().createStatement()) {
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS clan_bank (" +
                "clan TEXT PRIMARY KEY," +
                "balance REAL DEFAULT 0)"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS clan_bank_log (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "clan TEXT NOT NULL," +
                "player_name TEXT NOT NULL," +
                "action TEXT NOT NULL," +   // DEPOSIT / WITHDRAW
                "amount REAL NOT NULL," +
                "balance_after REAL NOT NULL," +
                "timestamp INTEGER NOT NULL)"
            );
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // ─── COMMAND HANDLER ───────────────────────────────────────────────────────

    public boolean handleCommand(Player player, String[] args) {
        if (!plugin.getConfig().getBoolean("features.clan-bank", true)) {
            player.sendMessage(plugin.getMessage("feature_disabled", Map.of())); return true;
        }

        if (economy == null) {
            player.sendMessage("§c[!] Clan Bank requires Vault and an economy plugin. Please install them.");
            return true;
        }

        if (args.length < 2) { sendBankHelp(player); return true; }

        return switch (args[1].toLowerCase()) {
            case "deposit"  -> handleDeposit(player, args);
            case "withdraw" -> handleWithdraw(player, args);
            case "balance", "bal" -> handleBalance(player);
            case "log"      -> handleLog(player);
            default -> { sendBankHelp(player); yield true; }
        };
    }

    private boolean handleDeposit(Player player, String[] args) {
        if (!player.hasPermission("simpleclans.bank.deposit")) {
            player.sendMessage(plugin.getMessage("no_permission", Map.of())); return true;
        }
        String clan = plugin.getClanOf(player.getUniqueId());
        if (clan == null) { player.sendMessage(plugin.getMessage("not_in_clan", Map.of())); return true; }

        if (args.length < 3) { player.sendMessage("§c[!] Usage: /clan bank deposit <amount>"); return true; }

        double amount;
        try { amount = Double.parseDouble(args[2]); }
        catch (NumberFormatException e) { player.sendMessage("§c[!] Invalid amount."); return true; }

        if (amount <= 0) { player.sendMessage("§c[!] Amount must be greater than 0."); return true; }

        if (!economy.has(player, amount)) {
            player.sendMessage("§c[!] You don't have enough money. Your balance: §f" +
                economy.format(economy.getBalance(player))); return true;
        }

        economy.withdrawPlayer(player, amount);
        double newBalance = addToBank(clan, amount);
        addLog(clan, player.getName(), "DEPOSIT", amount, newBalance);

        player.sendMessage("§6[Bank] §aDeposited §f" + economy.format(amount) +
            " §ato §e" + clan + "§a's bank. New balance: §f" + economy.format(newBalance));
        broadcastToClan(clan, "§6[Bank] §e" + player.getName() + " §7deposited §f" +
            economy.format(amount) + " §7into the clan bank.", player.getUniqueId());
        return true;
    }

    private boolean handleWithdraw(Player player, String[] args) {
        if (!player.hasPermission("simpleclans.bank.withdraw")) {
            player.sendMessage(plugin.getMessage("no_permission", Map.of())); return true;
        }
        String clan = plugin.getClanOf(player.getUniqueId());
        if (clan == null) { player.sendMessage(plugin.getMessage("not_in_clan", Map.of())); return true; }

        String role = plugin.getRoleOf(player.getUniqueId());
        if (!role.equalsIgnoreCase("LEADER") && !role.equalsIgnoreCase("CO-LEADER")) {
            player.sendMessage("§c[!] Only leaders and co-leaders can withdraw from the clan bank."); return true;
        }

        if (args.length < 3) { player.sendMessage("§c[!] Usage: /clan bank withdraw <amount>"); return true; }

        double amount;
        try { amount = Double.parseDouble(args[2]); }
        catch (NumberFormatException e) { player.sendMessage("§c[!] Invalid amount."); return true; }

        if (amount <= 0) { player.sendMessage("§c[!] Amount must be greater than 0."); return true; }

        double current = getBalance(clan);
        if (current < amount) {
            player.sendMessage("§c[!] The clan bank only has §f" + economy.format(current) + "§c. Not enough funds."); return true;
        }

        double newBalance = removeFromBank(clan, amount);
        economy.depositPlayer(player, amount);
        addLog(clan, player.getName(), "WITHDRAW", amount, newBalance);

        player.sendMessage("§6[Bank] §aWithdrew §f" + economy.format(amount) +
            " §afrom §e" + clan + "§a's bank. New balance: §f" + economy.format(newBalance));
        broadcastToClan(clan, "§6[Bank] §e" + player.getName() + " §7withdrew §f" +
            economy.format(amount) + " §7from the clan bank.", player.getUniqueId());
        return true;
    }

    private boolean handleBalance(Player player) {
        if (!player.hasPermission("simpleclans.bank.balance")) {
            player.sendMessage(plugin.getMessage("no_permission", Map.of())); return true;
        }
        String clan = plugin.getClanOf(player.getUniqueId());
        if (clan == null) { player.sendMessage(plugin.getMessage("not_in_clan", Map.of())); return true; }

        double balance = getBalance(clan);
        player.sendMessage("§6[Bank] §e" + clan + "§6's bank balance: §a" + economy.format(balance));
        return true;
    }
    public double withdraw(String clan, double amount) {
        return removeFromBank(clan, amount);
    }
    public Economy getEconomy() {
        return economy;
    }
    private boolean handleLog(Player player) {
        if (!player.hasPermission("simpleclans.bank.log")) {
            player.sendMessage(plugin.getMessage("no_permission", Map.of())); return true;
        }
        String clan = plugin.getClanOf(player.getUniqueId());
        if (clan == null) { player.sendMessage(plugin.getMessage("not_in_clan", Map.of())); return true; }

        player.sendMessage("§6===== §e💰 Bank Log: §f" + clan + " §6=====");
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "SELECT player_name, action, amount, balance_after FROM clan_bank_log " +
            "WHERE clan = ? ORDER BY id DESC LIMIT 10")) {
            ps.setString(1, clan);
            ResultSet rs = ps.executeQuery();
            boolean any = false;
            while (rs.next()) {
                any = true;
                String action = rs.getString("action");
                String colour = action.equals("DEPOSIT") ? "§a+" : "§c-";
                player.sendMessage("§7• §e" + rs.getString("player_name") + " §7" +
                    colour + economy.format(rs.getDouble("amount")) +
                    " §7(bal: §f" + economy.format(rs.getDouble("balance_after")) + "§7)");
            }
            if (!any) player.sendMessage("§7No transactions yet.");
        } catch (SQLException e) { e.printStackTrace(); }
        player.sendMessage("§6=====================================");
        return true;
    }

    // ─── DATABASE ──────────────────────────────────────────────────────────────

    public double getBalance(String clan) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "SELECT balance FROM clan_bank WHERE clan = ?")) {
            ps.setString(1, clan);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getDouble("balance");
        } catch (SQLException e) { e.printStackTrace(); }
        return 0.0;
    }

    private double addToBank(String clan, double amount) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "INSERT INTO clan_bank(clan, balance) VALUES(?, ?) ON CONFLICT(clan) " +
            "DO UPDATE SET balance = balance + ?")) {
            ps.setString(1, clan); ps.setDouble(2, amount); ps.setDouble(3, amount);
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
        return getBalance(clan);
    }

    private double removeFromBank(String clan, double amount) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "UPDATE clan_bank SET balance = balance - ? WHERE clan = ?")) {
            ps.setDouble(1, amount); ps.setString(2, clan);
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
        return getBalance(clan);
    }

    public void initBankForClan(String clan) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "INSERT OR IGNORE INTO clan_bank(clan, balance) VALUES(?, 0)")) {
            ps.setString(1, clan); ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    /** Called on clan disband — optionally return money to leader. */
    public void disbandBank(String clan) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "DELETE FROM clan_bank WHERE clan = ?")) {
            ps.setString(1, clan); ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private void addLog(String clan, String player, String action, double amount, double balAfter) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "INSERT INTO clan_bank_log(clan, player_name, action, amount, balance_after, timestamp) " +
            "VALUES(?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, clan); ps.setString(2, player); ps.setString(3, action);
            ps.setDouble(4, amount); ps.setDouble(5, balAfter); ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private void broadcastToClan(String clan, String message, UUID exclude) {
        for (Map.Entry<UUID, String> entry : plugin.getClanMembers(clan).entrySet()) {
            if (entry.getKey().equals(exclude)) continue;
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p != null) p.sendMessage(message);
        }
    }

    private void sendBankHelp(Player player) {
        player.sendMessage("§6===== §e💰 Bank Commands §6=====");
        player.sendMessage("§e/clan bank deposit <amount>  §7- Deposit money");
        player.sendMessage("§e/clan bank withdraw <amount> §7- Withdraw money (leaders)");
        player.sendMessage("§e/clan bank balance           §7- Check bank balance");
        player.sendMessage("§e/clan bank log               §7- View last 10 transactions");
        player.sendMessage("§6================================");
    }
}
