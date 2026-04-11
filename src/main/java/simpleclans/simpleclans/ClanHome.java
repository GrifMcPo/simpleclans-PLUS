package simpleclans.simpleclans;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.sql.*;
import java.util.*;

/**
 * Clan Home System
 * Commands: /clan home | /clan home set | /clan home delete
 * Permissions: simpleclans.home.*
 * Config toggle: features.clan-home
 */
public class ClanHome implements Listener {

    private final SimpleclansPlugin plugin;

    // UUID -> countdown task id (for teleport delay)
    private final Map<UUID, Integer>   teleportCountdown = new HashMap<>();
    private final Map<UUID, Location>  teleportFrom      = new HashMap<>();

    public ClanHome(SimpleclansPlugin plugin) {
        this.plugin = plugin;
        createTables();
    }

    private void createTables() {
        try (Statement stmt = plugin.getConnection().createStatement()) {
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS clan_homes (" +
                "clan TEXT PRIMARY KEY," +
                "world TEXT NOT NULL," +
                "x REAL NOT NULL," +
                "y REAL NOT NULL," +
                "z REAL NOT NULL," +
                "yaw REAL DEFAULT 0," +
                "pitch REAL DEFAULT 0," +
                "set_by TEXT NOT NULL," +
                "set_time INTEGER NOT NULL)"
            );
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // ─── COMMAND HANDLER ───────────────────────────────────────────────────────

    public boolean handleCommand(Player player, String[] args) {
        if (!plugin.getConfig().getBoolean("features.clan-home", true)) {
            player.sendMessage(plugin.getMessage("feature_disabled", Map.of()));
            return true;
        }

        if (args.length == 1) {
            // /clan home — teleport
            return handleTeleport(player);
        }

        return switch (args[1].toLowerCase()) {
            case "set"    -> handleSet(player);
            case "delete" -> handleDelete(player);
            default -> { handleTeleport(player); yield true; }
        };
    }

    private boolean handleTeleport(Player player) {
        if (!player.hasPermission("simpleclans.home.teleport")) {
            player.sendMessage(plugin.getMessage("no_permission", Map.of())); return true;
        }

        String clan = plugin.getClanOf(player.getUniqueId());
        if (clan == null) { player.sendMessage(plugin.getMessage("not_in_clan", Map.of())); return true; }

        Location home = getHome(clan);
        if (home == null) {
            player.sendMessage("§c[!] Your clan does not have a home set. A leader can use §f/clan home set§c.");
            return true;
        }

        // Check if already teleporting
        if (teleportCountdown.containsKey(player.getUniqueId())) {
            player.sendMessage("§c[!] Teleport already in progress. Don't move!");
            return true;
        }

        int delay = plugin.getConfig().getInt("clan-home.teleport-delay", 3);

        if (delay <= 0) {
            player.teleport(home);
            player.sendMessage("§6[Simpleclan-PLUS] §aTeleported to §e" + clan + "§a's home!");
            return true;
        }

        teleportFrom.put(player.getUniqueId(), player.getLocation().clone());
        player.sendMessage("§6[Simpleclan-PLUS] §eTeleporting in §f" + delay + " §eseconds. Don't move!");

        int[] secondsLeft = {delay};
        int taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            secondsLeft[0]--;
            if (secondsLeft[0] <= 0) {
                cancelCountdown(player.getUniqueId());
                if (player.isOnline()) {
                    player.teleport(home);
                    player.sendMessage("§6[Simpleclan-PLUS] §aTeleported to §e" + clan + "§a's home!");
                }
            }
        }, 20L, 20L);

        teleportCountdown.put(player.getUniqueId(), taskId);
        return true;
    }

    private boolean handleSet(Player player) {
        if (!player.hasPermission("simpleclans.home.set")) {
            player.sendMessage(plugin.getMessage("no_permission", Map.of())); return true;
        }

        String clan = plugin.getClanOf(player.getUniqueId());
        if (clan == null) { player.sendMessage(plugin.getMessage("not_in_clan", Map.of())); return true; }

        String role = plugin.getRoleOf(player.getUniqueId());
        if (!role.equalsIgnoreCase("LEADER") && !role.equalsIgnoreCase("CO-LEADER")) {
            player.sendMessage("§c[!] Only leaders and co-leaders can set the clan home."); return true;
        }

        setHome(clan, player.getLocation(), player.getName());
        player.sendMessage("§6[Simpleclan-PLUS] §aClan home set at your current location!");
        broadcastToClan(clan, "§6[Clan] §7Clan home was set by §e" + player.getName() + "§7.", player.getUniqueId());
        return true;
    }

    private boolean handleDelete(Player player) {
        if (!player.hasPermission("simpleclans.home.delete")) {
            player.sendMessage(plugin.getMessage("no_permission", Map.of())); return true;
        }

        String clan = plugin.getClanOf(player.getUniqueId());
        if (clan == null) { player.sendMessage(plugin.getMessage("not_in_clan", Map.of())); return true; }

        String role = plugin.getRoleOf(player.getUniqueId());
        if (!role.equalsIgnoreCase("LEADER")) {
            player.sendMessage("§c[!] Only the clan leader can delete the clan home."); return true;
        }

        if (getHome(clan) == null) {
            player.sendMessage("§c[!] Your clan has no home to delete."); return true;
        }

        deleteHome(clan);
        player.sendMessage("§6[Simpleclan-PLUS] §aClan home deleted.");
        return true;
    }

    // ─── MOVE CANCEL ───────────────────────────────────────────────────────────

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (!teleportCountdown.containsKey(uuid)) return;

        Location from = teleportFrom.get(uuid);
        Location to   = event.getTo();
        if (to == null) return;

        if (from.getBlockX() != to.getBlockX() ||
            from.getBlockY() != to.getBlockY() ||
            from.getBlockZ() != to.getBlockZ()) {
            cancelCountdown(uuid);
            event.getPlayer().sendMessage("§c[!] Teleport cancelled — you moved!");
        }
    }

    private void cancelCountdown(UUID uuid) {
        Integer taskId = teleportCountdown.remove(uuid);
        if (taskId != null) Bukkit.getScheduler().cancelTask(taskId);
        teleportFrom.remove(uuid);
    }

    // ─── DATABASE ──────────────────────────────────────────────────────────────

    private void setHome(String clan, Location loc, String setBy) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "INSERT OR REPLACE INTO clan_homes(clan, world, x, y, z, yaw, pitch, set_by, set_time) " +
            "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, clan);
            ps.setString(2, loc.getWorld().getName());
            ps.setDouble(3, loc.getX());
            ps.setDouble(4, loc.getY());
            ps.setDouble(5, loc.getZ());
            ps.setFloat(6, loc.getYaw());
            ps.setFloat(7, loc.getPitch());
            ps.setString(8, setBy);
            ps.setLong(9, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public Location getHome(String clan) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "SELECT world, x, y, z, yaw, pitch FROM clan_homes WHERE clan = ?")) {
            ps.setString(1, clan);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                var world = Bukkit.getWorld(rs.getString("world"));
                if (world == null) return null;
                return new Location(world,
                    rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                    rs.getFloat("yaw"), rs.getFloat("pitch"));
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return null;
    }

    private void deleteHome(String clan) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "DELETE FROM clan_homes WHERE clan = ?")) {
            ps.setString(1, clan);
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    /** Called when a clan is disbanded. */
    public void deleteHomeForClan(String clan) { deleteHome(clan); }

    private void broadcastToClan(String clan, String message, UUID exclude) {
        for (Map.Entry<UUID, String> entry : plugin.getClanMembers(clan).entrySet()) {
            if (entry.getKey().equals(exclude)) continue;
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p != null) p.sendMessage(message);
        }
    }
}
