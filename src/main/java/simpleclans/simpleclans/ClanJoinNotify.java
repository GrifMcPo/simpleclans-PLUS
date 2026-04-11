package simpleclans.simpleclans;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;

/**
 * Clan Join Notifications
 * When a clan member logs in or out, all online clan members are notified.
 * Config toggle: features.join-notifications
 */
public class ClanJoinNotify implements Listener {

    private final SimpleclansPlugin plugin;

    public ClanJoinNotify(SimpleclansPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("features.join-notifications", true)) return;

        Player player = event.getPlayer();
        String clan   = plugin.getClanOf(player.getUniqueId());
        if (clan == null) return;

        String role = plugin.getRoleOf(player.getUniqueId());
        int online  = plugin.getOnlineClanMembers(clan);

        String message = plugin.getConfig().getString(
            "join-notifications.join-message",
            "§6[Clan] §a→ §e%player% §7[%role%] §7joined. §a%online% §7members online."
        );
        message = message
            .replace("%player%", player.getName())
            .replace("%role%",   role != null ? role : "?")
            .replace("%clan%",   clan)
            .replace("%online%", String.valueOf(online));

        final String finalMsg = message;

        for (Map.Entry<UUID, String> entry : plugin.getClanMembers(clan).entrySet()) {
            Player member = Bukkit.getPlayer(entry.getKey());
            // Send to all online members except the joining player
            if (member != null && !member.getUniqueId().equals(player.getUniqueId())) {
                member.sendMessage(finalMsg);
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (!plugin.getConfig().getBoolean("features.join-notifications", true)) return;

        Player player = event.getPlayer();
        String clan   = plugin.getClanOf(player.getUniqueId());
        if (clan == null) return;

        String role = plugin.getRoleOf(player.getUniqueId());
        // online count before they've actually left
        int online = plugin.getOnlineClanMembers(clan) - 1;

        String message = plugin.getConfig().getString(
            "join-notifications.quit-message",
            "§6[Clan] §c← §e%player% §7[%role%] §7left. §a%online% §7members online."
        );
        message = message
            .replace("%player%", player.getName())
            .replace("%role%",   role != null ? role : "?")
            .replace("%clan%",   clan)
            .replace("%online%", String.valueOf(Math.max(0, online)));

        final String finalMsg = message;

        for (Map.Entry<UUID, String> entry : plugin.getClanMembers(clan).entrySet()) {
            Player member = Bukkit.getPlayer(entry.getKey());
            if (member != null && !member.getUniqueId().equals(player.getUniqueId())) {
                member.sendMessage(finalMsg);
            }
        }
    }
}
