package simpleclans.simpleclans;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import java.sql.*;
import java.util.*;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;


public class SimpleclansPlugin extends JavaPlugin implements Listener {
    private FileConfiguration languageConfig;
    private String languageCode;
    private Connection connection;
    private ModrinthUpdater updater;
    private ClanMenu clanMenu;
    private ClanWars       clanWars;
    private ClanAlliances  clanAlliances;
    private ClanHome       clanHome;
    private ClanDuel       clanDuel;
    private ClanLandClaim  clanLandClaim;
    private ClanBank       clanBank;
    private ClanRaid       clanRaid;
    private ClanBounty     clanBounty;
    private ClanLeaderboard clanLeaderboard;

    private final Map<UUID, Boolean> clanChatToggled = new HashMap<>();
    private final Map<String, FileConfiguration> languages = new HashMap<>();
    
    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        languageCode = getConfig().getString("Language.default", "EN").toUpperCase();
        loadAllLanguageFiles();
        setLanguage(languageCode); 
        getLogger().info("SimpleClans started with language: " + languageCode);
        Bukkit.getPluginManager().registerEvents(this, this);
        
        // Initialize and register ClanMenu once
        clanMenu = new ClanMenu(this);
        Bukkit.getPluginManager().registerEvents(clanMenu, this);
        connectDatabase();
        createTables();
        // ── New feature modules ──
        clanWars        = new ClanWars(this);
        clanAlliances   = new ClanAlliances(this);
        clanHome        = new ClanHome(this);
        clanDuel        = new ClanDuel(this);
        clanLandClaim   = new ClanLandClaim(this);
        clanRaid        = new ClanRaid(this);
        clanBounty      = new ClanBounty(this);
        clanLeaderboard = new ClanLeaderboard(this);


// Vault-dependent features
        if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
            clanBank = new ClanBank(this);
            clanLeaderboard.setClanBank(clanBank);
        }

// Register listeners
        Bukkit.getPluginManager().registerEvents(clanWars, this);
        Bukkit.getPluginManager().registerEvents(clanAlliances, this);
        Bukkit.getPluginManager().registerEvents(clanHome, this);
        Bukkit.getPluginManager().registerEvents(clanDuel, this);
        Bukkit.getPluginManager().registerEvents(clanLandClaim, this);
        Bukkit.getPluginManager().registerEvents(clanRaid, this);
        Bukkit.getPluginManager().registerEvents(clanBounty, this);
        Bukkit.getPluginManager().registerEvents(new ClanJoinNotify(this), this);

        updater = new ModrinthUpdater(this, "simpleclans-plus");
        updater.checkForUpdates();
        Bukkit.getPluginManager().registerEvents(new UpdateNotifyListener(updater), this);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new ClanPlaceholder(this).register();
            getLogger().info("[Simpleclan-PLUS] PlaceholderAPI Activated!");
        } else {
            getLogger().warning("[Simpleclan-PLUS]NO placeholderAPI found, placeholders won't work.");
        }

        getCommand("clan").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(getMessage("only_players", Map.of()));
                return true;
            }

            UUID uuid = player.getUniqueId();

            if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
                player.sendMessage("§6=========== §fClan Commands ===========");
                player.sendMessage("§e/clan create <name> §7- Create a new clan.");
                player.sendMessage("§e/clan invite <player> §7- Invite a player to your clan.");
                player.sendMessage("§e/clan join <name> §7- Join a clan you were invited to.");
                player.sendMessage("§e/clan leave §7- Leave your current clan.");
                player.sendMessage("§e/clan info [name] §7- View info about a clan.");
                player.sendMessage("§e/clan promote <player> §7- Promote a member.");
                player.sendMessage("§e/clan demote <player> §7- Demote a member.");
                player.sendMessage("§e/clan kick <player> §7- Kick a player from your clan.");
                player.sendMessage("§e/clan chatmsg §7- Send a single message in clanchat.");
                player.sendMessage("§e/clan chat §7- Toggle clan chat.");
                player.sendMessage("§e/clan list §7- shows a clan list.");
                player.sendMessage("§e/clan menu §7- Open the clan menu.");
                player.sendMessage("§e/clan update §7- Update to newest version.");
                player.sendMessage("§e/clan war <challenge|accept|surrender|status> §7- Clan war system");
                player.sendMessage("§e/clan ally <add|accept|remove|list> §7- Clan alliance system");
                player.sendMessage("§e/clan home [set|delete] §7- Clan home teleport");
                player.sendMessage("§e/clan duel <player> §7- Challenge a player to a duel");
                player.sendMessage("§e/clan claim / unclaim §7- Claim land for your clan");
                player.sendMessage("§e/clan bank <deposit|withdraw|balance|log> §7- Clan bank");
                player.sendMessage("§e/clan raid <start|end|status> §7- Clan raid system");
                player.sendMessage("§e/clan bounty <set|list|remove> §7- Place bounties");
                player.sendMessage("§e/clan top [kills|level|members|bank] §7- Clan leaderboards");
                player.sendMessage("§6=======================================");
                return true;
            }

            String sub = args[0].toLowerCase();

            switch (sub) {
                case "create" -> {
                        if (!player.hasPermission("simpleclans.create")) {
                                player.sendMessage(getMessage("no_permission", Map.of()));
                                return true;
                        }
                        if (args.length < 2) {
                                player.sendMessage(getMessage("clan_create", Map.of()));
                                return true;
                        }
                        if (getClanOf(uuid) != null) {
                                player.sendMessage(getMessage("already_in_clan", Map.of()));
                                return true;
                        }
                        String clanName = args[1];

                        if (getAllClanNames().stream().anyMatch(name -> name.equalsIgnoreCase(clanName))) {
                            player.sendMessage(getMessage("clan_exists", Map.of()));
                            return true;
                        }
                        
                        createClan(clanName, uuid);
                        player.sendMessage(getMessage("clan_created", Map.of("clan", clanName)));
                }


                case "menu" -> {
                    clanMenu.openMenu(player);
                }

                case "invite" -> {
                        if (!player.hasPermission("simpleclans.invite")) {
                                player.sendMessage(getMessage("no_permission", Map.of()));
                                return true;
                        }
                        String clan = getClanOf(uuid);
                        if (clan == null) {
                                player.sendMessage(getMessage("not_in_clan", Map.of()));
                                return true;
                        }
                        String role = getRoleOf(uuid);
                        if (!role.equalsIgnoreCase("LEADER") && !role.equalsIgnoreCase("CO-LEADER")) {
                                player.sendMessage(getMessage("promote_not_permission", Map.of("clan", clan)));
                                return true;
                        }
                        if (args.length < 2) {
                                player.sendMessage(getMessage("clan_invite", Map.of("clan", clan)));
                                return true;
                        }
                        Player target = Bukkit.getPlayerExact(args[1]);
                        if (target == null) {
                                player.sendMessage(getMessage("player_not_found", Map.of("clan", clan)));
                                return true;
                        }
                        UUID targetId = target.getUniqueId();
                        if (getClanOf(targetId) != null) {
                                player.sendMessage(getMessage("already_in_clan_player", Map.of("clan", clan)));
                                return true;
                        }

                        try (PreparedStatement ps = connection.prepareStatement(
                                        "INSERT INTO clan_invites(target_uuid, clan_name, inviter_uuid, timestamp) VALUES(?, ?, ?, ?)")) {
                                ps.setString(1, targetId.toString());
                                ps.setString(2, clan);
                                ps.setString(3, uuid.toString());
                                ps.setLong(4, System.currentTimeMillis());
                                ps.executeUpdate();
                        } catch (Exception e) {
                                e.printStackTrace();
                        }

                        player.sendMessage(getMessage("invited_player", Map.of("player", target.getName(), "clan", clan)));
                        target.sendMessage(getMessage("invited_you", Map.of("clan", clan)));
                }

                case "join" -> {
                        if (!player.hasPermission("simpleclans.join")) {
                                player.sendMessage(getMessage("no_permission", Map.of()));
                                return true;
                        }
                        if (args.length < 2) {
                                player.sendMessage(getMessage("clan_join", Map.of()));
                                return true;
                        }
                        String clanName = args[1];

                        try (PreparedStatement ps = connection.prepareStatement(
                                        "SELECT * FROM clan_invites WHERE target_uuid = ? AND clan_name = ?")) {
                                ps.setString(1, uuid.toString());
                                ps.setString(2, clanName);
                                var rs = ps.executeQuery();
                                if (!rs.next()) {
                                        player.sendMessage(getMessage("invite_expired", Map.of()));
                                        return true;
                                }
                                long timestamp = rs.getLong("timestamp");
                                if (System.currentTimeMillis() - timestamp > 5 * 60 * 1000) {
                                        player.sendMessage(getMessage("invite_expired", Map.of()));
                                        try (PreparedStatement del = connection.prepareStatement(
                                                        "DELETE FROM clan_invites WHERE target_uuid = ? AND clan_name = ?")) {
                                                del.setString(1, uuid.toString());
                                                del.setString(2, clanName);
                                                del.executeUpdate();
                                        }
                                        return true;
                                }
                        } catch (Exception e) {
                                e.printStackTrace();
                        }

                        addMemberToClan(uuid, clanName, "RECRUIT");

                        try (PreparedStatement ps = connection.prepareStatement(
                                        "DELETE FROM clan_invites WHERE target_uuid = ? AND clan_name = ?")) {
                                ps.setString(1, uuid.toString());
                                ps.setString(2, clanName);
                                ps.executeUpdate();
                        } catch (Exception e) {
                                e.printStackTrace();
                        }

                        player.sendMessage(getMessage("joined_clan", Map.of("clan", clanName)));
                }
                case "war" -> {
                    if (clanWars != null) clanWars.handleCommand(player, args);
                }

                case "ally" -> {
                    if (clanAlliances != null) clanAlliances.handleCommand(player, args);
                }

                case "home" -> {
                    if (clanHome != null) clanHome.handleCommand(player, args);
                }

                case "duel" -> {
                    if (clanDuel != null) clanDuel.handleCommand(player, args);
                }

                case "claim", "unclaim", "remove", "claimlist", "claiminfo", "buy" -> {
                    if (clanLandClaim != null) {
                        String[] shiftedArgs = new String[args.length + 1];
                        shiftedArgs[0] = args[0];
                        shiftedArgs[1] = sub;
                        System.arraycopy(args, 1, shiftedArgs, 2, args.length - 1);
                        clanLandClaim.handleCommand(player, shiftedArgs);
                    }
                }

                case "bank" -> {
                    if (clanBank != null) clanBank.handleCommand(player, args);
                    else player.sendMessage("§c[!] Clan Bank requires Vault.");
                }

                case "raid" -> {
                    if (clanRaid != null) clanRaid.handleCommand(player, args);
                }

                case "bounty" -> {
                    if (clanBounty != null) clanBounty.handleCommand(player, args);
                }

                case "top", "leaderboard" -> {
                    if (clanLeaderboard != null) clanLeaderboard.handleCommand(player, args);
                }

                case "leave" -> {
                        if (!player.hasPermission("simpleclans.leave")) {
                                player.sendMessage(getMessage("no_permission", Map.of()));
                                return true;
                        }
                        String clan = getClanOf(uuid);
                        if (clan == null) {
                                player.sendMessage(getMessage("not_in_clan", Map.of()));
                                return true;
                        }
                        removeMemberFromClan(uuid);
                        player.sendMessage(getMessage("left_clan", Map.of("clan", clan)));
                }

                case "info" -> {
                    if (!player.hasPermission("simpleclans.info")) {
                        player.sendMessage(getMessage("no_permission", Map.of()));
                        return true;
                    }
                    
                    String clan = args.length > 1 ? args[1] : getClanOf(uuid);
                    if (clan == null) {
                        player.sendMessage(getMessage("not_in_clan", Map.of()));
                        return true;
                    }

                    int level = getClanLevel(clan);
                    int kills = getClanKills(clan);
                    Map<UUID, String> members = getClanMembers(clan);

                    player.sendMessage("§6===== §eClan Info §6===== ");
                    player.sendMessage("§eClan Name: §f" + clan);
                    player.sendMessage("§eLevel: §f" + level);
                    player.sendMessage("§eKills: §f" + kills);
                    player.sendMessage("§eMembers: ");

                    for (Map.Entry<UUID, String> entry : members.entrySet()) {
                        UUID memberUUID = entry.getKey();
                        String role = entry.getValue();
                        OfflinePlayer member = Bukkit.getOfflinePlayer(memberUUID);

                        boolean online = member.isOnline();
                        String color = online ? "§a" : "§c";
                        String status = online ? "§7(Online)" : "§7(Offline)";

                        player.sendMessage(color + " - " + member.getName() + " §8[" + role + "] " + status);
                    }

                    player.sendMessage("§6=========================");
                    return true;
                }

                case "promote" -> {
                    if (!player.hasPermission("simpleclans.promote")) {
                        player.sendMessage(getMessage("clan_promote_usage", Map.of()));
                        return true;
                    }
                    String clan = getClanOf(uuid);
                    if (clan == null) {
                        player.sendMessage(getMessage("not_in_clan", Map.of()));
                        return true;
                    }
                    String role = getRoleOf(uuid);
                    if (!role.equalsIgnoreCase("LEADER") && !role.equalsIgnoreCase("CO-LEADER")) {
                        player.sendMessage(getMessage("no_permission", Map.of()));
                        return true;
                    }

                    if (args.length < 2) {
                        player.sendMessage(getMessage("clan_promote_usage", Map.of()));
                        return true;
                    }

                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        player.sendMessage(getMessage("player_not_found", Map.of()));
                        return true;
                    }
                    UUID targetId = target.getUniqueId();
                    if (!clan.equals(getClanOf(targetId))) {
                        player.sendMessage(getMessage("not_in_your_clan", Map.of()));
                        return true;
                    }

                    String targetRole = getRoleOf(targetId);
                    String newRole = switch (targetRole.toUpperCase()) {
                        case "RECRUIT" -> "MEMBER";
                        case "MEMBER" -> "CO-LEADER";
                        default -> null;
                    };

                    if (newRole == null) {
                        player.sendMessage(getMessage("cannot_promote", Map.of()));
                        return true;
                    }

                    addMemberToClan(targetId, clan, newRole);
                    player.sendMessage(getMessage("promote_success", Map.of("player", target.getName(), "role", newRole)));
                    target.sendMessage(getMessage("promoted_to", Map.of("role", newRole)));
                }

                case "demote" -> {
                    if (!player.hasPermission("simpleclans.demote")) {
                        player.sendMessage(getMessage("clan_demote_usage", Map.of()));
                        return true;
                    }
                    String clan = getClanOf(uuid);
                    if (clan == null) {
                        player.sendMessage(getMessage("not_in_clan", Map.of()));
                        return true;
                    }
                    String role = getRoleOf(uuid);
                    if (!role.equalsIgnoreCase("LEADER") && !role.equalsIgnoreCase("CO-LEADER")) {
                        player.sendMessage(getMessage("no_permission", Map.of()));
                        return true;
                    }

                    if (args.length < 2) {
                        player.sendMessage(getMessage("clan_demote_usage", Map.of()));
                        return true;
                    }

                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        player.sendMessage(getMessage("player_not_found", Map.of()));
                        return true;
                    }
                    UUID targetId = target.getUniqueId();
                    if (!clan.equals(getClanOf(targetId))) {
                        player.sendMessage(getMessage("not_in_your_clan", Map.of()));
                        return true;
                    }

                    String targetRole = getRoleOf(targetId);
                    String newRole = switch (targetRole.toUpperCase()) {
                        case "CO-LEADER" -> "MEMBER";
                        case "MEMBER" -> "RECRUIT";
                        default -> null;
                    };

                    if (newRole == null) {
                        player.sendMessage(getMessage("cannot_demote", Map.of()));
                        return true;
                    }

                    addMemberToClan(targetId, clan, newRole);
                    player.sendMessage(getMessage("demote_success", Map.of("player", target.getName(), "role", newRole)));
                    target.sendMessage(getMessage("demoted_to", Map.of("role", newRole)));
                }

                case "kick" -> {
                    if (!player.hasPermission("simpleclans.kick")) {
                        player.sendMessage(getMessage("no_permission", Map.of()));
                        return true;
                    }
                    String clan = getClanOf(uuid);
                    if (clan == null) {
                        player.sendMessage(getMessage("not_in_clan", Map.of()));
                        return true;
                    }
                    String role = getRoleOf(uuid);
                    if (!role.equalsIgnoreCase("LEADER") && !role.equalsIgnoreCase("CO-LEADER")) {
                        player.sendMessage(getMessage("no_permission", Map.of()));
                        return true;
                    }
                    
                    if (args.length < 2) {
                        player.sendMessage(getMessage("clan_kick_usage", Map.of()));
                        return true;
                    }
                    
                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        player.sendMessage(getMessage("player_not_found", Map.of()));
                        return true;
                    }
                    
                    UUID targetId = target.getUniqueId();
                    if (!clan.equals(getClanOf(targetId))) {
                        player.sendMessage(getMessage("not_in_your_clan", Map.of()));
                        return true;
                    }
                    
                    String targetRole = getRoleOf(targetId);
                    if (targetRole.equalsIgnoreCase("LEADER")) {
                        player.sendMessage(getMessage("cannot_kick_leader", Map.of()));
                        return true;
                    }
                    
                    // Co-Leaders kunnen alleen Members en Recruits kicken
                    if (role.equalsIgnoreCase("CO-LEADER") && targetRole.equalsIgnoreCase("CO-LEADER")) {
                        player.sendMessage(getMessage("cannot_kick_coleader", Map.of()));
                        return true;
                    }
                    
                    removeMemberFromClan(targetId);
                    player.sendMessage(getMessage("kicked_player", Map.of("player", target.getName(), "clan", clan)));
                    target.sendMessage(getMessage("you_were_kicked", Map.of("clan", clan)));
                }

                case "disband" -> {
                    if (!player.hasPermission("simpleclans.disband")) {
                        player.sendMessage(getMessage("no_permission", Map.of()));
                        return true;
                    }
                    String clan = getClanOf(uuid);
                    if (clan == null) {
                        player.sendMessage(getMessage("not_in_clan", Map.of()));
                        return true;
                    }

                    String role = getRoleOf(uuid);
                    if (!"LEADER".equalsIgnoreCase(role)) {
                        player.sendMessage(getMessage("clan_disband_leader", Map.of()));
                        return true;
                    }
                    if (clanAlliances != null)  clanAlliances.removeAllAlliancesFor(clan);
                    if (clanHome      != null)  clanHome.deleteHomeForClan(clan);
                    if (clanLandClaim != null)  clanLandClaim.removeAllClaimsFor(clan);
                    if (clanBank      != null)  clanBank.disbandBank(clan);
                    try (PreparedStatement ps = connection.prepareStatement(
                            "DELETE FROM clans WHERE name = ?")) {
                        ps.setString(1, clan);
                        ps.executeUpdate();
                    } catch (SQLException e) {
                        e.printStackTrace();
                        player.sendMessage(getMessage("clan_disband_error", Map.of()));
                        return true;
                    }

                    try (PreparedStatement ps = connection.prepareStatement(
                            "UPDATE clan_members SET clan = NULL, role = NULL WHERE clan = ?")) {
                        ps.setString(1, clan);
                        ps.executeUpdate();
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }

                    player.sendMessage(getMessage("you_disbanded", Map.of("clan", clan)));
                }

                case "list" -> {
                    if (!player.hasPermission("simpleclans.list")) {
                        player.sendMessage(getMessage("no_permission", Map.of()));
                        return true;
                    }
                    player.sendMessage("§6===== Online Players & Clans =====");
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        String clan = getClanOf(p.getUniqueId());
                        player.sendMessage("§e" + p.getName() + " §7- Clan: " + (clan != null ? clan : "None"));
                    }
                }

                case "chat" -> {
                    UUID playerUUID = player.getUniqueId();
                    boolean toggled = clanChatToggled.getOrDefault(playerUUID, false);
                    clanChatToggled.put(playerUUID, !toggled);
                    player.sendMessage(getMessage(!toggled ? "chat_enabled" : "chat_disabled", Map.of()));
                }

                case "chatmsg" -> {
                    if (!player.hasPermission("simpleclans.chatmsg")) {
                        player.sendMessage(getMessage("chat_usage", Map.of()));
                        return true;
                    }
                    String clan = getClanOf(player.getUniqueId());
                    if (clan == null) {
                        player.sendMessage(getMessage("not_in_clan", Map.of()));
                        return true;
                    }
                    if (args.length < 2) {
                        player.sendMessage(getMessage("chat_usage", Map.of()));
                        return true;
                    }
                    String msg = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                    for (Map.Entry<UUID, String> member : getClanMembers(clan).entrySet()) {
                        Player target = Bukkit.getPlayer(member.getKey());
                        if (target != null) {
                            target.sendMessage(getMessage("chat_message", Map.of("player", player.getName(), "message", msg)));
                        }
                    }
                }
                
                case "admin" -> {
                    if (!player.hasPermission("simpleclans.admin")) {
                        player.sendMessage(getMessage("admin_no_perm", Map.of()));
                        return true;
                    }

                    if (args.length < 2) {
                        player.sendMessage(getMessage("admin_default1", Map.of()));
                        return true;
                    }

                    String adminCommand = args[1].toLowerCase();

                    switch (adminCommand) {
                        case "promote" -> {
                            if (!player.hasPermission("simpleclans.admin.promote")) {
                                player.sendMessage(getMessage("admin_no_perm", Map.of()));
                                return true;
                            }
                            if (args.length < 4) {
                                player.sendMessage(getMessage("admin_promote_usage", Map.of()));
                                return true;
                            }
                            Player target = Bukkit.getPlayerExact(args[2]);
                            String clan = args[3];

                            if (target == null) {
                                player.sendMessage(getMessage("player_not_found", Map.of("clan", clan)));
                                return true;
                            }

                            String targetRole = getRoleOf(target.getUniqueId());
                            String newRole = switch (targetRole.toUpperCase()) {
                                case "RECRUIT" -> "MEMBER";
                                case "MEMBER" -> "CO-LEADER";
                                default -> null;
                            };

                            if (newRole == null) {
                                player.sendMessage(getMessage("cannot_promote", Map.of()));
                                return true;
                            }

                            addMemberToClan(target.getUniqueId(), clan, newRole);
                            player.sendMessage(getMessage("promote_success", Map.of("player", target.getName(), "role", newRole)));
                            target.sendMessage(getMessage("promoted_to", Map.of("role", newRole)));
                        }
                        case "update" -> {
                            if (!player.hasPermission("simpleclans.admin")) {
                                player.sendMessage(getMessage("no_permission", Map.of()));
                                return true;
                            }

                            if (updater.isUpdateAvailable()) {
                                player.sendMessage("§6[Simpleclan-PLUS] §eDownloading latest update...");
                                updater.downloadLatestUpdate();
                                player.sendMessage("§6[Simpleclan-PLUS] §aUpdate downloaded! It will be installed on the next restart.");
                            } else {
                                player.sendMessage("§6[Simpleclan-PLUS] §aYou are already running the latest version!");
                            }
                        }
                        case "purge" -> {
                            if (!player.hasPermission("simpleclans.admin.purge")) {
                                player.sendMessage(getMessage("admin_no_perm", Map.of()));
                                return true;
                            }
                            if (args.length < 3) {
                                player.sendMessage(getMessage("admin_usage_purge", Map.of()));
                                return true;
                            }
                            Player target = Bukkit.getPlayerExact(args[2]);
                            if (target == null) {
                                player.sendMessage(getMessage("player_not_found", Map.of()));
                                return true;
                            }
                            addMemberToClan(target.getUniqueId(), null, null);
                            player.sendMessage(getMessage("admin_purge_success", Map.of("player", target.getName())));
                            target.sendMessage(getMessage("admin_purge_target", Map.of()));
                        }

                        case "reset" -> {
                            if (!player.hasPermission("simpleclans.admin.reset")) {
                                player.sendMessage(getMessage("admin_no_perm", Map.of()));
                                return true;
                            }
                            if (args.length < 3) {
                                player.sendMessage(getMessage("admin_usage_reset", Map.of()));
                                return true;
                            }
                            String clan = args[2];
                            
                            updateClanKills(clan, 0);
                            
                            for (UUID memberUUID : getClanMembers(clan).keySet()) {
                                addMemberToClan(memberUUID, clan, "RECRUIT");
                            }

                            player.sendMessage(getMessage("admin_reset", Map.of("clan", clan))); 
                        }

                        case "place" -> {
                            if (!player.hasPermission("simpleclans.admin.place")) {
                                player.sendMessage(getMessage("admin_no_perm", Map.of()));
                                return true;
                            }

                            if (args.length < 4) {
                                player.sendMessage(getMessage("admin_usage_place", Map.of()));
                                return true;
                            }

                            Player target = Bukkit.getPlayerExact(args[2]);
                            String clan = args[3];
                            String role = args.length >= 5 ? args[4].toUpperCase() : "MEMBER";

                            if (target == null) {
                                player.sendMessage(getMessage("player_not_found", Map.of("clan", clan)));
                                return true;
                            }

                            if (!getAllClanNames().contains(clan)) {
                                player.sendMessage(getMessage("admin_clan_not_exist", Map.of("clan", clan)));
                                return true;
                            }

                            addMemberToClan(target.getUniqueId(), clan, role);

                            player.sendMessage(getMessage("admin_place_success_player", Map.of("player", target.getName(),"clan", clan,"role", role)));
                            target.sendMessage(getMessage("admin_place_success_target", Map.of("clan", clan,"role", role)));
                        }

                        case "invite" -> {
                            if (!player.hasPermission("simpleclans.admin.invite")) {
                                player.sendMessage(getMessage("admin_no_perm", Map.of()));
                                return true;
                            }
                            if (args.length < 4) {
                                player.sendMessage(getMessage("admin_invite_usage", Map.of()));
                                return true;
                            }
                            Player target = Bukkit.getPlayerExact(args[2]);
                            String clan = args[3];

                            if (target == null) {
                                player.sendMessage(getMessage("player_not_found", Map.of()));
                                return true;
                            }

                            try (PreparedStatement ps = connection.prepareStatement(
                                    "INSERT INTO clan_invites(target_uuid, clan_name, inviter_uuid, timestamp) VALUES(?, ?, ?, ?)")) {
                                ps.setString(1, target.getUniqueId().toString());
                                ps.setString(2, clan);
                                ps.setString(3, player.getUniqueId().toString());
                                ps.setLong(4, System.currentTimeMillis());
                                ps.executeUpdate();
                            } catch (SQLException e) {
                                e.printStackTrace();
                            }

                            player.sendMessage(getMessage("invited_player", Map.of("player", target.getName(), "clan", clan)));
                            target.sendMessage(getMessage("invited_you", Map.of("clan", clan)));
                        }

                        case "reload" -> {
                            if (!player.hasPermission("simpleclans.admin.reload")) {
                                player.sendMessage(getMessage("admin_no_perm", Map.of()));
                                return true;
                            }
                            reloadConfig();
                            languageCode = getConfig().getString("Language.default", "EN").toUpperCase();
                            loadAllLanguageFiles(); 
                            setLanguage(languageCode);
                            player.sendMessage(getMessage("admin_reload_lang", Map.of("language", languageCode)));
                        }

                        case "demote" -> {
                            if (!player.hasPermission("simpleclans.admin.demote")) {
                                player.sendMessage(getMessage("admin_no_perm", Map.of()));
                                return true;
                            }
                            if (args.length < 4) {
                                player.sendMessage(getMessage("admin_demote_usage", Map.of()));
                                return true;
                            }
                            Player target = Bukkit.getPlayerExact(args[2]);
                            String clan = args[3];

                            if (target == null) {
                                player.sendMessage(getMessage("player_not_found", Map.of()));
                                return true;
                            }

                            String targetRole = getRoleOf(target.getUniqueId());
                            String newRole = switch (targetRole.toUpperCase()) {
                                case "CO-LEADER" -> "MEMBER";
                                case "MEMBER" -> "RECRUIT";
                                default -> null;
                            };

                            if (newRole == null) {
                                player.sendMessage(getMessage("cannot_demote", Map.of()));
                                return true;
                            }

                            addMemberToClan(target.getUniqueId(), clan, newRole);
                            player.sendMessage(getMessage("demote_success", Map.of("player", target.getName(), "role", newRole)));
                            target.sendMessage(getMessage("demoted_to", Map.of("role", newRole)));
                        }

                        case "kick" -> {
                            if (!player.hasPermission("simpleclans.admin.kick")) {
                                player.sendMessage(getMessage("admin_no_perm", Map.of()));
                                return true;
                            }
                            if (args.length < 4) {
                                player.sendMessage(getMessage("admin_kick_usage", Map.of()));
                                return true;
                            }
                            Player target = Bukkit.getPlayerExact(args[2]);
                            String clan = args[3];

                            if (target == null) {
                                player.sendMessage(getMessage("player_not_found", Map.of()));
                                return true;
                            }

                            if (!clan.equals(getClanOf(target.getUniqueId()))) {
                                player.sendMessage(getMessage("admin_notin_clan", Map.of()));
                                return true;
                            }

                            addMemberToClan(target.getUniqueId(), null, null);
                            player.sendMessage(getMessage("admin_kick_success", Map.of("player", target.getName(),"clan", clan)));
                            target.sendMessage(getMessage("admin_kick_target", Map.of("clan", clan)));
                        }

                        case "help" -> {
                            if (!player.hasPermission("simpleclans.admin")) {
                                player.sendMessage(getMessage("admin_no_perm", Map.of()));
                                return true;
                            }

                            player.sendMessage("§6===== §eAdmin Commands §6=====");
                            player.sendMessage("§e/clan admin promote <player> <clan> §7- Promote a player in a clan");
                            player.sendMessage("§e/clan admin demote <player> <clan> §7- Demote a player in a clan");
                            player.sendMessage("§e/clan admin kick <player> <clan> §7- Kick a player from a clan");
                            player.sendMessage("§e/clan admin disband <clan> §7- Disband any clan");
                            player.sendMessage("§e/clan admin purge <player> §7- Reset a player's clan data");
                            player.sendMessage("§e/clan admin reset <clan> §7- Reset a clan's data");
                            player.sendMessage("§e/clan admin place §7- Place clan items/blocks");
                            player.sendMessage("§e/clan admin invite <player> <clan> §7- Invite a player to any clan");
                            player.sendMessage("§e/clan admin reload §7- Reload the plugin configuration");
                            player.sendMessage("§6==============================");
                        }

                        case "disband" -> {
                            if (!player.hasPermission("simpleclans.admin.disband")) {
                                player.sendMessage(getMessage("admin_no_perm", Map.of()));
                                return true;
                            }
                            if (args.length < 3) {
                                player.sendMessage(getMessage("admin_disband_usage", Map.of()));
                                return true;
                            }
                            String clan = args[2];

                            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM clans WHERE name = ?")) {
                                ps.setString(1, clan);
                                ps.executeUpdate();
                            } catch (SQLException e) {
                                e.printStackTrace();
                                player.sendMessage(getMessage("clan_disband_error", Map.of()));
                                return true;
                            }

                            try (PreparedStatement ps = connection.prepareStatement("UPDATE clan_members SET clan = NULL, role = NULL WHERE clan = ?")) {
                                ps.setString(1, clan);
                                ps.executeUpdate();
                            } catch (SQLException e) {
                                e.printStackTrace();
                            }

                            player.sendMessage(getMessage("you_disbanded", Map.of("clan", clan)));
                        }
                        
                        default -> player.sendMessage(getMessage("admin_default", Map.of()));
                    }
                }

                default -> player.sendMessage(getMessage("default", Map.of()));
            }

            return true;
        });

        getCommand("clan").setTabCompleter((sender, command, alias, args) -> {
            List<String> completions = new ArrayList<>();

            if (!(sender instanceof Player)) return completions;

            if (args.length == 1) {
                completions.addAll(Arrays.asList(
                    "create", "invite", "join", "leave", "info", "promote", "demote", "kick", "disband", "help", "update", "list", "chat", "chatmsg", "admin", "menu","war", "ally", "home", "duel", "claim", "unclaim","claimlist", "claiminfo", "bank", "raid","bounty", "top", "leaderboard"
                ));
            }

            else if (args.length == 2) {
                String sub = args[0].toLowerCase();
                switch (sub) {
                    case "invite", "promote", "demote", "kick" ->
                        completions.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
                    case "join", "info", "disband" ->
                        completions.addAll(getAllClanNames());
                    case "admin" ->
                        completions.addAll(Arrays.asList(
                            "promote", "demote", "kick", "invite", "disband", "reset", "purge",
                            "place", "reload", "help"
                        ));
                }
            }

            else if (args.length == 3) {
                String sub = args[0].toLowerCase();

                if (sub.equals("admin")) {
                    String adminSub = args[1].toLowerCase();
                    switch (adminSub) {
                        case "promote", "demote", "kick", "invite", "purge", "place" ->
                            completions.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
                        case "disband", "reset" ->
                            completions.addAll(getAllClanNames());
                    }
                }
            }

            else if (args.length == 4) {
                String sub = args[0].toLowerCase();

                if (sub.equals("admin")) {
                    String adminSub = args[1].toLowerCase();
                    switch (adminSub) {
                        case "promote", "demote", "kick", "invite", "place", "reset" ->
                            completions.addAll(getAllClanNames());
                    }
                }
            }

            else if (args.length == 5) {
                String sub = args[0].toLowerCase();

                if (sub.equals("admin")) {
                    String adminSub = args[1].toLowerCase();
                    if (adminSub.equals("place")) {
                        completions.addAll(Arrays.asList("LEADER", "CO-LEADER", "MEMBER", "RECRUIT"));
                    }
                }
            }

            String lastArg = args[args.length - 1].toLowerCase();
            completions.removeIf(s -> !s.toLowerCase().startsWith(lastArg));

            return completions;
        });
    }
    private void loadAllLanguageFiles() {
        File langFolder = new File(getDataFolder(), "languages");
        if (!langFolder.exists()) langFolder.mkdirs();

        String[] builtInLangs = {"EN", "NL","CH", "SP", "HI", "AR", "BE", "PO", "RU", "JA","PU", "DE", "WU", "KO", "FR", "TE", "MA","TU", "TA", "VI", "UR", "KA", "GU", "OR", "BH","MI", "HA", "JI", "IN", "IT"};

        for (String lang : builtInLangs) {
            File langFile = new File(langFolder, lang + ".yml");

            if (!langFile.exists() && getResource("languages/" + lang + ".yml") != null) {
                saveResource("languages/" + lang + ".yml", false);
            }

            if (langFile.exists()) {
                FileConfiguration config = YamlConfiguration.loadConfiguration(langFile);

                InputStream defaultStream = getResource("languages/EN.yml");
                if (defaultStream != null) {
                    YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(defaultStream, StandardCharsets.UTF_8)
                    );
                    config.setDefaults(defConfig);
                }

                languages.put(lang.toUpperCase(), config);
            } else {
                getLogger().warning("Could not find language file for " + lang + "!");
            }
        }
    }

    public String getMessage(String path, Map<String, String> placeholders) {
        String msg = languageConfig.getString("messages." + path, "Message not found: " + path);
        if (msg == null) return "Message not found: " + path;

        msg = msg.replace("%prefix%", languageConfig.getString("messages.prefix", "§6[Simpleclan-PLUS]"));
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            msg = msg.replace("%" + entry.getKey() + "%", entry.getValue());
        }
        return msg;
    }

    public String getLanguage() {
        return languageCode;
    }

    @Override
    public void onDisable() {
        getLogger().info("[Simpleclan-PLUS] ClanPlugin is disabled!");
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void connectDatabase() {
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + getDataFolder() + "/database.db");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void createTables() {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS clans (" +
                    "name TEXT PRIMARY KEY," +
                    "leader TEXT," +
                    "kills INTEGER DEFAULT 0," +
                    "level INTEGER DEFAULT 1," +
                    "tag TEXT DEFAULT ''," +
                    "description TEXT DEFAULT '')");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS clan_members (" +
                    "uuid TEXT PRIMARY KEY," +
                    "clan TEXT," +
                    "role TEXT)");

            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS clan_invites (" +
                    "target_uuid TEXT," +
                    "clan_name TEXT," +
                    "inviter_uuid TEXT," +
                    "timestamp INTEGER DEFAULT (strftime('%s','now')))");

            // Migrate existing databases that don't have tag/description yet
            try { stmt.executeUpdate("ALTER TABLE clans ADD COLUMN tag TEXT DEFAULT ''"); } catch (SQLException ignored) {}
            try { stmt.executeUpdate("ALTER TABLE clans ADD COLUMN description TEXT DEFAULT ''"); } catch (SQLException ignored) {}
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void setLanguage(String langCode) {
        langCode = langCode.toUpperCase();
        if (languages.containsKey(langCode)) {
            languageConfig = languages.get(langCode);
            getLogger().info("Active language set to: " + langCode);
        } else {
            languageConfig = languages.get("EN");
            getLogger().warning("Language " + langCode + " not found! Falling back to EN.");
        }
    }

    public void createClan(String name, UUID leader) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO clans(name, leader, kills, level) VALUES(?, ?, 0, 1)")) {
            ps.setString(1, name);
            ps.setString(2, leader.toString());
            ps.executeUpdate();

            addMemberToClan(leader, name, "LEADER");

            if (clanBank != null) clanBank.initBankForClan(name);

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }


    public void addMemberToClan(UUID uuid, String clan, String role) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO clan_members(uuid, clan, role) VALUES(?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, clan);
            ps.setString(3, role);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * Fully removes a player's clan membership row from the database.
     * Prefer this over addMemberToClan(uuid, null, null) to avoid orphan rows.
     */
    public void removeMemberFromClan(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM clan_members WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public ClanMenu getClanMenu() {
        return clanMenu;
    }

    public String getClanOf(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT clan FROM clan_members WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("clan");
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public String getRoleOf(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT role FROM clan_members WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("role");
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Get clan chat toggle status for a player
     * @param uuid Player UUID
     * @return true if clan chat is enabled, false otherwise
     */
    public boolean getClanChatStatus(UUID uuid) {
        return clanChatToggled.getOrDefault(uuid, false);
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!clanChatToggled.getOrDefault(uuid, false)) {
            return; 
        }

        event.setCancelled(true);

        String clan = getClanOf(uuid);
        if (clan == null) {
            player.sendMessage("§6[Simpleclan-PLUS] §cYou are not in a clan!");
            return;
        }

        String msg = event.getMessage();

        for (Map.Entry<UUID, String> member : getClanMembers(clan).entrySet()) {
            Player target = Bukkit.getPlayer(member.getKey());
            if (target != null && target.isOnline()) {
                target.sendMessage("§6[Clan] §e" + player.getName() + "§f: " + msg);
            }
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null) return;
        if (killer.getUniqueId().equals(victim.getUniqueId())) return;

        String killerClan = getClanOf(killer.getUniqueId());
        if (killerClan == null) return;

        int currentKills = getClanKills(killerClan);
        updateClanKills(killerClan, currentKills + 1);
    }

    public void updateClanKills(String clan, int kills) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE clans SET kills = ?, level = (? / 5) + 1 WHERE name = ?")) {
            ps.setInt(1, kills);
            ps.setInt(2, kills);
            ps.setString(3, clan);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public int getClanKills(String clan) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT kills FROM clans WHERE name = ?")) {
            ps.setString(1, clan);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt("kills");
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public int getClanMemberCount(String clan) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) AS total FROM clan_members WHERE clan = ?")) {
            ps.setString(1, clan);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt("total");
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public int getOnlineClanMembers(String clan) {
        int count = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (clan.equals(getClanOf(player.getUniqueId()))) count++;
        }
        return count;
    }

    public int getClanLevel(String clan) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT level FROM clans WHERE name = ?")) {
            ps.setString(1, clan);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt("level");
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 1;
    }

    public Map<UUID, String> getClanMembers(String clan) {
        Map<UUID, String> members = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT uuid, role FROM clan_members WHERE clan = ?")) {
            ps.setString(1, clan);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                members.put(UUID.fromString(rs.getString("uuid")), rs.getString("role"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return members;
    }

    public String getClanLeader(String clan) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT leader FROM clans WHERE name = ?")) {
            ps.setString(1, clan);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                UUID leaderUUID = UUID.fromString(rs.getString("leader"));
                OfflinePlayer leaderPlayer = Bukkit.getOfflinePlayer(leaderUUID);
                return leaderPlayer.getName();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return "None";
    }

    public String getClanTag(String clan) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT tag FROM clans WHERE name = ?")) {
            ps.setString(1, clan);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String tag = rs.getString("tag");
                return (tag != null && !tag.isEmpty()) ? tag : clan;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return clan;
    }

    public void setClanTag(String clan, String tag) {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE clans SET tag = ? WHERE name = ?")) {
            ps.setString(1, tag);
            ps.setString(2, clan);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
    public Connection getConnection() {
        return connection;
    }
    public net.milkbowl.vault.economy.Economy getEconomy() {
        return clanBank != null ? clanBank.getEconomy() : null;
    }
    public String getClanDescription(String clan) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT description FROM clans WHERE name = ?")) {
            ps.setString(1, clan);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String desc = rs.getString("description");
                return (desc != null && !desc.isEmpty()) ? desc : "No description set.";
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return "No description set.";
    }

    public void setClanDescription(String clan, String description) {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE clans SET description = ? WHERE name = ?")) {
            ps.setString(1, description);
            ps.setString(2, clan);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void resetClanData(String clan) {
        updateClanKills(clan, 0);
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE clans SET level = 1 WHERE name = ?")) {
            ps.setString(1, clan);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        // Reset all non-leader members to RECRUIT
        Map<UUID, String> members = getClanMembers(clan);
        for (Map.Entry<UUID, String> entry : members.entrySet()) {
            if (!entry.getValue().equalsIgnoreCase("LEADER")) {
                addMemberToClan(entry.getKey(), clan, "RECRUIT");
            }
        }
    }

    public void forceDisbandClan(String clan) {

        for (UUID uuid : getClanMembers(clan).keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.sendMessage(getMessage("you_disbanded", Map.of("clan", clan)));
            }
        }

        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM clans WHERE name = ?")) {
            ps.setString(1, clan);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE clan_members SET clan = NULL, role = NULL WHERE clan = ?")) {
            ps.setString(1, clan);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }

        // NEW CLEANUP
        if (clanAlliances != null) clanAlliances.removeAllAlliancesFor(clan);
        if (clanHome != null) clanHome.deleteHomeForClan(clan);
        if (clanLandClaim != null) clanLandClaim.removeAllClaimsFor(clan);
        if (clanBank != null) clanBank.disbandBank(clan);
    }


    // ─── CLAN BANK HELPERS (used by ClanLandClaim upkeep system) ──────────────

    /**
     * Returns the current balance of the clan's bank account.
     * Delegates to ClanBank if Vault is available, otherwise returns 0.
     */
    public double getClanBankBalance(String clan) {
        if (clanBank == null) return 0.0;
        return clanBank.getBalance(clan);
    }

    /**
     * Withdraws {@code amount} from the clan's bank account.
     * Returns true on success, false if the balance is too low or Vault is absent.
     */
    public boolean withdrawFromClanBank(String clan, double amount) {
        if (clanBank == null) return false;
        if (clanBank.getBalance(clan) < amount) return false;
        clanBank.withdraw(clan, amount);
        return true;
    }

    /**
     * Sends {@code message} to every online member of {@code clan}.
     * Used by the upkeep scheduler to broadcast warnings and decay notices.
     */
    public void notifyClanMembers(String clan, String message) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (clan.equalsIgnoreCase(getClanOf(online.getUniqueId()))) {
                online.sendMessage(message);
            }
        }
    }

    public List<String> getAllClanNames() {
        List<String> clans = new ArrayList<>();
        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT name FROM clans");
            while (rs.next()) clans.add(rs.getString("name"));
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return clans;
    }

    class ClanPlaceholder extends PlaceholderExpansion {
        private final SimpleclansPlugin plugin;

        public ClanPlaceholder(SimpleclansPlugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public @NotNull String getIdentifier() { return "simpleclans"; }

        @Override
        public @NotNull String getAuthor() { return "[Robin]"; }

        @Override
        public @NotNull String getVersion() { return plugin.getDescription().getVersion(); }

        @Override
        public boolean persist() { return true; }

        @Override
        public @Nullable String onRequest(OfflinePlayer player, @NotNull String identifier) {
            if (identifier.equalsIgnoreCase("clan_name")) {
                String clan = plugin.getClanOf(player.getUniqueId());
                return clan != null ? clan : "No Clan";
            }
            if (identifier.equalsIgnoreCase("clan_role")) {
                String role = plugin.getRoleOf(player.getUniqueId());
                return role != null ? role : "None";
            }
            if (identifier.equalsIgnoreCase("clan_level")) {
                String clan = plugin.getClanOf(player.getUniqueId());
                return clan != null ? String.valueOf(plugin.getClanLevel(clan)) : "0";
            }
            if (identifier.equalsIgnoreCase("clan_kills")) {
                String clan = plugin.getClanOf(player.getUniqueId());
                return clan != null ? String.valueOf(plugin.getClanKills(clan)) : "0";
            }
            if (identifier.equalsIgnoreCase("member_count")) {
                String clan = plugin.getClanOf(player.getUniqueId());
                return clan != null ? String.valueOf(plugin.getClanMemberCount(clan)) : "0";
            }

            if (identifier.equalsIgnoreCase("online_members")) {
                String clan = plugin.getClanOf(player.getUniqueId());
                return clan != null ? String.valueOf(plugin.getOnlineClanMembers(clan)) : "0";
            }

            if (identifier.equalsIgnoreCase("clan_leader")) {
                String clan = plugin.getClanOf(player.getUniqueId());
                return clan != null ? plugin.getClanLeader(clan) : "None";
            }

            return null;
        }
    }
}