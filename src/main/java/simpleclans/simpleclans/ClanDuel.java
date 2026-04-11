package simpleclans.simpleclans;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;

import java.util.*;

/**
 * Clan Duel System — 1v1 within the same clan or between clans.
 * Commands: /clan duel <player> | accept <player> | decline <player> | cancel
 * Permissions: simpleclans.duel.*
 * Config toggle: features.clan-duel
 */
public class ClanDuel implements Listener {

    private final SimpleclansPlugin plugin;

    // challenger UUID -> (challenged UUID, timestamp)
    private final Map<UUID, Map.Entry<UUID, Long>> pendingDuels = new HashMap<>();

    // Active duel pairs: UUID -> opponent UUID
    private final Map<UUID, UUID> activeDuels = new HashMap<>();

    // Saved locations to restore after duel: UUID -> location
    private final Map<UUID, Location> duelStartLocations = new HashMap<>();

    public ClanDuel(SimpleclansPlugin plugin) {
        this.plugin = plugin;
    }

    // ─── COMMAND HANDLER ───────────────────────────────────────────────────────

    public boolean handleCommand(Player player, String[] args) {
        if (!plugin.getConfig().getBoolean("features.clan-duel", true)) {
            player.sendMessage(plugin.getMessage("feature_disabled", Map.of())); return true;
        }

        if (args.length < 2) { sendDuelHelp(player); return true; }

        return switch (args[1].toLowerCase()) {
            case "accept"  -> handleAccept(player, args);
            case "decline" -> handleDecline(player, args);
            case "cancel"  -> handleCancel(player);
            default        -> handleChallenge(player, args);
        };
    }

    private boolean handleChallenge(Player player, String[] args) {
        if (!player.hasPermission("simpleclans.duel.challenge")) {
            player.sendMessage(plugin.getMessage("no_permission", Map.of())); return true;
        }

        // args[1] is the target name when it's not a sub-command
        String targetName = args[1];
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            player.sendMessage(plugin.getMessage("player_not_found", Map.of())); return true;
        }
        if (target.equals(player)) {
            player.sendMessage("§c[!] You cannot duel yourself."); return true;
        }

        if (isInDuel(player.getUniqueId()) || isInDuel(target.getUniqueId())) {
            player.sendMessage("§c[!] One of you is already in a duel."); return true;
        }

        if (pendingDuels.containsKey(player.getUniqueId())) {
            player.sendMessage("§c[!] You already have a pending duel request."); return true;
        }

        int timeout = plugin.getConfig().getInt("clan-duel.timeout", 60);
        pendingDuels.put(player.getUniqueId(), Map.entry(target.getUniqueId(), System.currentTimeMillis()));

        player.sendMessage("§6[Simpleclan-PLUS] §eDuel request sent to §f" + target.getName() + "§e.");
        target.sendMessage("§6[Simpleclan-PLUS] §e§f" + player.getName() +
            " §echallenged you to a duel! Type §f/clan duel accept " + player.getName() +
            " §eor §f/clan duel decline " + player.getName() +
            " §e(expires in §f" + timeout + "s§e).");

        // Auto-expire
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Map.Entry<UUID, Long> req = pendingDuels.get(player.getUniqueId());
            if (req != null && req.getKey().equals(target.getUniqueId())) {
                pendingDuels.remove(player.getUniqueId());
                if (player.isOnline()) player.sendMessage("§7[Duel] §7Duel request to §e" + target.getName() + " §7expired.");
                if (target.isOnline()) target.sendMessage("§7[Duel] §7Duel request from §e" + player.getName() + " §7expired.");
            }
        }, timeout * 20L);

        return true;
    }

    private boolean handleAccept(Player player, String[] args) {
        if (!player.hasPermission("simpleclans.duel.accept")) {
            player.sendMessage(plugin.getMessage("no_permission", Map.of())); return true;
        }
        if (args.length < 3) { player.sendMessage("§c[!] Usage: /clan duel accept <player>"); return true; }

        Player challenger = Bukkit.getPlayerExact(args[2]);
        if (challenger == null) { player.sendMessage(plugin.getMessage("player_not_found", Map.of())); return true; }

        Map.Entry<UUID, Long> request = pendingDuels.get(challenger.getUniqueId());
        if (request == null || !request.getKey().equals(player.getUniqueId())) {
            player.sendMessage("§c[!] No pending duel request from §f" + challenger.getName() + "§c."); return true;
        }

        pendingDuels.remove(challenger.getUniqueId());
        startDuel(challenger, player);
        return true;
    }

    private boolean handleDecline(Player player, String[] args) {
        if (args.length < 3) { player.sendMessage("§c[!] Usage: /clan duel decline <player>"); return true; }

        Player challenger = Bukkit.getPlayerExact(args[2]);
        if (challenger == null) { player.sendMessage(plugin.getMessage("player_not_found", Map.of())); return true; }

        Map.Entry<UUID, Long> request = pendingDuels.get(challenger.getUniqueId());
        if (request == null || !request.getKey().equals(player.getUniqueId())) {
            player.sendMessage("§c[!] No pending duel request from §f" + challenger.getName() + "§c."); return true;
        }

        pendingDuels.remove(challenger.getUniqueId());
        player.sendMessage("§7[Duel] You declined the duel from §e" + challenger.getName() + "§7.");
        challenger.sendMessage("§7[Duel] §e" + player.getName() + " §7declined your duel request.");
        return true;
    }

    private boolean handleCancel(Player player) {
        if (!isInDuel(player.getUniqueId())) {
            player.sendMessage("§c[!] You are not in a duel."); return true;
        }
        UUID opponentId = activeDuels.get(player.getUniqueId());
        Player opponent = Bukkit.getPlayer(opponentId);
        endDuel(player.getUniqueId(), opponentId, null); // null = cancelled, no winner
        player.sendMessage("§7[Duel] You forfeited the duel.");
        if (opponent != null) opponent.sendMessage("§7[Duel] §e" + player.getName() + " §7forfeited the duel.");
        return true;
    }

    // ─── DUEL LIFECYCLE ────────────────────────────────────────────────────────

    private void startDuel(Player p1, Player p2) {
        // Save positions
        duelStartLocations.put(p1.getUniqueId(), p1.getLocation().clone());
        duelStartLocations.put(p2.getUniqueId(), p2.getLocation().clone());

        activeDuels.put(p1.getUniqueId(), p2.getUniqueId());
        activeDuels.put(p2.getUniqueId(), p1.getUniqueId());

        p1.sendMessage("§c§l⚔ DUEL STARTED! §r§7You are now dueling §e" + p2.getName() + "§7. Good luck!");
        p2.sendMessage("§c§l⚔ DUEL STARTED! §r§7You are now dueling §e" + p1.getName() + "§7. Good luck!");
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!plugin.getConfig().getBoolean("features.clan-duel", true)) return;

        Player victim = event.getEntity();
        if (!isInDuel(victim.getUniqueId())) return;

        UUID winnerId = activeDuels.get(victim.getUniqueId());
        Player winner = Bukkit.getPlayer(winnerId);

        event.setDeathMessage(null);
        event.getDrops().clear();
        event.setDroppedExp(0);

        // Restore health instead of killing
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (victim.isOnline()) {
                victim.spigot().respawn();
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    victim.setHealth(20.0);
                    victim.setFoodLevel(20);
                    victim.setFireTicks(0);
                    victim.getActivePotionEffects().forEach(e -> victim.removePotionEffect(e.getType()));
                    Location origin = duelStartLocations.get(victim.getUniqueId());
                    if (origin != null) victim.teleport(origin);
                    victim.sendMessage("§c[Duel] You lost the duel against §e" +
                        (winner != null ? winner.getName() : "your opponent") + "§c.");
                }, 5L);
            }
        });

        endDuel(victim.getUniqueId(), winnerId, winnerId);

        if (winner != null) {
            winner.sendMessage("§a[Duel] §l🏆 You won the duel against §e" + victim.getName() + "§a!");
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (isInDuel(uuid)) {
            UUID opponentId = activeDuels.get(uuid);
            endDuel(uuid, opponentId, opponentId);
            Player opponent = Bukkit.getPlayer(opponentId);
            if (opponent != null) opponent.sendMessage("§7[Duel] §e" + event.getPlayer().getName() + " §7disconnected. You win!");
        }
        // Cancel any pending requests sent by this player
        pendingDuels.remove(uuid);
        // Remove this player as a pending target
        pendingDuels.entrySet().removeIf(e -> e.getValue().getKey().equals(uuid));
    }

    private void endDuel(UUID p1, UUID p2, UUID winner) {
        activeDuels.remove(p1);
        activeDuels.remove(p2);
        duelStartLocations.remove(p1);
        duelStartLocations.remove(p2);
    }

    public boolean isInDuel(UUID uuid) {
        return activeDuels.containsKey(uuid);
    }

    private void sendDuelHelp(Player player) {
        player.sendMessage("§6===== §c⚔ Duel Commands §6=====");
        player.sendMessage("§e/clan duel <player>          §7- Challenge a player");
        player.sendMessage("§e/clan duel accept <player>   §7- Accept a duel");
        player.sendMessage("§e/clan duel decline <player>  §7- Decline a duel");
        player.sendMessage("§e/clan duel cancel            §7- Forfeit active duel");
        player.sendMessage("§6==============================");
    }
}
