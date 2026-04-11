package simpleclans.simpleclans;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Clan Land Claim System
 *
 * Commands:
 *   /clan claim                     – claim the chunk you're standing in
 *   /clan claim unclaim             – unclaim the chunk you're standing in
 *   /clan claim remove              – remove current chunk from your claims
 *   /clan claim remove <cx> <cz>    – remove a specific chunk by chunk-coords
 *   /clan claim claimlist           – list claims + upkeep info
 *   /clan claim claiminfo           – who owns the chunk you're in
 *   /clan claim buy                 – purchase an extra claim slot (Vault)
 *
 * Permissions: simpleclans.claim.*
 * Config toggle: features.clan-land-claim
 *
 * Config keys (under clan-land-claim):
 *   max-start-claims          int    default 4       – free claim slots on start
 *   upgrade-base-cost         double default 100.0   – base cost for /claim buy
 *   upgrade-cost-multiplier   double default 2.0     – multiplier per bought slot
 *   protect-from-enemies      bool   default true
 *   protect-pvp-in-claims     bool   default true
 *   show-border-messages      bool   default true
 *   upkeep-cost-per-chunk     double default 10.0    – clan bank charge per chunk per cycle
 *   upkeep-interval-hours     int    default 24      – hours between upkeep charges
 *   upkeep-grace-period-hours int    default 48      – warning window before decay starts
 *
 * Required methods on SimpleclansPlugin (implement these if not present):
 *   double  getClanBankBalance(String clan)
 *   boolean withdrawFromClanBank(String clan, double amount)  // returns false if too low
 *   void    notifyClanMembers(String clan, String message)
 */
public class ClanLandClaim implements Listener {

    private final SimpleclansPlugin plugin;

    /** How often the upkeep scanner wakes up. Actual charging is gated by last_charged_ms. */
    private static final long SCAN_INTERVAL_TICKS = 20L * 60 * 10; // every 10 minutes

    public ClanLandClaim(SimpleclansPlugin plugin) {
        this.plugin = plugin;
        createTables();
        startUpkeepScheduler();
    }

    // ─── TABLE SETUP ───────────────────────────────────────────────────────────

    private void createTables() {
        try (Statement stmt = plugin.getConnection().createStatement()) {

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS clan_claims (" +
                "clan         TEXT    NOT NULL," +
                "world        TEXT    NOT NULL," +
                "chunk_x      INTEGER NOT NULL," +
                "chunk_z      INTEGER NOT NULL," +
                "claimed_by   TEXT    NOT NULL," +
                "claimed_time INTEGER NOT NULL," +
                "PRIMARY KEY(world, chunk_x, chunk_z))"
            );

            // Tracks per-clan: purchased slots, last successful upkeep charge, grace period start
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS clan_claim_upgrades (" +
                "clan            TEXT    NOT NULL PRIMARY KEY," +
                "extra_claims    INTEGER NOT NULL DEFAULT 0," +
                "last_charged_ms INTEGER NOT NULL DEFAULT 0," +
                "grace_since_ms  INTEGER NOT NULL DEFAULT 0)"
            );

        } catch (SQLException e) {
            e.printStackTrace();
        }

        // Safe migration for existing databases missing the new columns
        migrateColumn("clan_claim_upgrades", "last_charged_ms", "INTEGER NOT NULL DEFAULT 0");
        migrateColumn("clan_claim_upgrades", "grace_since_ms",  "INTEGER NOT NULL DEFAULT 0");
    }

    /** Silently adds a column if it does not already exist (SQLite throws on duplicates). */
    private void migrateColumn(String table, String column, String definition) {
        try (Statement stmt = plugin.getConnection().createStatement()) {
            stmt.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (SQLException ignored) { /* column already exists */ }
    }

    // ─── UPKEEP SCHEDULER ─────────────────────────────────────────────────────

    /**
     * Runs every 10 minutes on an async thread.
     * Only charges a clan once their configured interval has elapsed since last_charged_ms.
     *
     * Charge flow per clan:
     *   1. Charge succeeds  → reset last_charged_ms, clear grace_since_ms.
     *   2. Charge fails, no grace yet → start grace clock, warn all members.
     *   3. Charge fails, grace elapsed → decay oldest chunks until balance covers
     *      remaining upkeep (or until no chunks are left).
     */
    private void startUpkeepScheduler() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {

            if (!plugin.getConfig().getBoolean("features.clan-land-claim", true)) return;

            double costPerChunk  = plugin.getConfig().getDouble("clan-land-claim.upkeep-cost-per-chunk", 10.0);
            long   intervalMs    = TimeUnit.HOURS.toMillis(
                                       plugin.getConfig().getInt("clan-land-claim.upkeep-interval-hours", 24));
            long   gracePeriodMs = TimeUnit.HOURS.toMillis(
                                       plugin.getConfig().getInt("clan-land-claim.upkeep-grace-period-hours", 48));
            long   now           = System.currentTimeMillis();

            for (String clan : getAllClansWithClaims()) {
                int count = getClaimCount(clan);
                if (count == 0) continue;

                long lastCharged = getLastCharged(clan);
                if (now - lastCharged < intervalMs) continue; // not yet time

                double totalCost = costPerChunk * count;
                boolean paid     = plugin.withdrawFromClanBank(clan, totalCost);

                if (paid) {
                    setLastCharged(clan, now);
                    setGraceSince(clan, 0);

                } else {
                    long graceSince = getGraceSince(clan);

                    if (graceSince == 0) {
                        // First missed payment — begin grace period
                        setGraceSince(clan, now);
                        long graceHours = TimeUnit.MILLISECONDS.toHours(gracePeriodMs);
                        plugin.notifyClanMembers(clan,
                            "§c§l[!] UPKEEP WARNING §r§c— Your clan cannot pay §f$" +
                            String.format("%.2f", totalCost) +
                            " §cfor §f" + count + " §cclaimed chunks. " +
                            "Chunks will begin decaying in §f" + graceHours + "h §cunless funds are deposited.");

                    } else if (now - graceSince >= gracePeriodMs) {
                        // Grace period has expired — decay oldest chunks
                        decayChunksForClan(clan, costPerChunk, now);
                    }
                    // Still inside grace period → do nothing this cycle
                }
            }

        }, SCAN_INTERVAL_TICKS, SCAN_INTERVAL_TICKS);
    }

    /**
     * Removes chunks oldest-first until:
     *   - the remaining upkeep fits inside the clan's current bank balance, or
     *   - the clan has no chunks left.
     *
     * After decaying, resets both last_charged_ms and grace_since_ms.
     */
    private void decayChunksForClan(String clan, double costPerChunk, long now) {
        List<String[]> chunks = getChunksByAgeWithWorld(clan); // oldest first
        int removed = 0;

        for (String[] row : chunks) {
            double balance   = plugin.getClanBankBalance(clan);
            int    remaining = getClaimCount(clan);

            if (remaining == 0 || balance >= costPerChunk * remaining) break;

            unclaimChunk(row[0], Integer.parseInt(row[1]), Integer.parseInt(row[2]));
            removed++;
        }

        setLastCharged(clan, now);
        setGraceSince(clan, 0);

        if (removed > 0) {
            plugin.notifyClanMembers(clan,
                "§c§l[!] LAND DECAY §r§c— §f" + removed +
                " §cof your clan's oldest claimed chunk" + (removed == 1 ? "" : "s") +
                " have been lost due to unpaid upkeep. " +
                "Deposit funds to your clan bank to prevent further decay.");
        }
    }

    // ─── COMMAND ROUTER ────────────────────────────────────────────────────────

    public boolean handleCommand(Player player, String[] args) {
        if (!plugin.getConfig().getBoolean("features.clan-land-claim", true)) {
            player.sendMessage(plugin.getMessage("feature_disabled", Map.of()));
            return true;
        }

        // /clan claim  (bare command → claim current chunk)
        if (args.length == 1) return handleClaim(player);

        return switch (args[1].toLowerCase()) {
            case "claim"     -> handleClaim(player);
            case "unclaim"   -> handleRemove(player, args);  // kept as alias
            case "remove"    -> handleRemove(player, args);
            case "claimlist" -> handleClaimList(player);
            case "claiminfo" -> handleClaimInfo(player);
            case "buy"       -> handleBuy(player);
            default          -> handleClaim(player);
        };
    }

    // ─── /clan claim ───────────────────────────────────────────────────────────

    private boolean handleClaim(Player player) {
        if (!player.hasPermission("simpleclans.claim.claim")) {
            player.sendMessage(plugin.getMessage("no_permission", Map.of())); return true;
        }
        String clan = plugin.getClanOf(player.getUniqueId());
        if (clan == null) { player.sendMessage(plugin.getMessage("not_in_clan", Map.of())); return true; }

        String role = plugin.getRoleOf(player.getUniqueId());
        if (!role.equalsIgnoreCase("LEADER") && !role.equalsIgnoreCase("CO-LEADER")) {
            player.sendMessage("§c[!] Only leaders and co-leaders can claim land."); return true;
        }

        int maxClaims = getMaxClaims(clan);
        int current   = getClaimCount(clan);
        if (current >= maxClaims) {
            player.sendMessage("§c[!] Claim limit reached (§f" + maxClaims +
                " §cchunks). Use §e/clan claim buy §cto unlock more.");
            return true;
        }

        Chunk  chunk = player.getLocation().getChunk();
        String world = chunk.getWorld().getName();
        int    cx    = chunk.getX(), cz = chunk.getZ();

        String existing = getClaimOwner(world, cx, cz);
        if (existing != null) {
            player.sendMessage("§c[!] This chunk is already claimed by §e" + existing + "§c."); return true;
        }

        claimChunk(clan, world, cx, cz, player.getName());

        double costPerChunk = plugin.getConfig().getDouble("clan-land-claim.upkeep-cost-per-chunk", 10.0);
        int    intervalHrs  = plugin.getConfig().getInt("clan-land-claim.upkeep-interval-hours", 24);

        player.sendMessage("§6[Simpleclan-PLUS] §aChunk [§f" + cx + "§a, §f" + cz +
            "§a] claimed for §e" + clan + " §a(§f" + (current + 1) + "§a/§f" + maxClaims + "§a).");
        player.sendMessage(String.format(
            "§7New upkeep total: §f$%.2f §7every §f%dh§7.",
            costPerChunk * (current + 1), intervalHrs));
        return true;
    }

    // ─── /clan claim remove [cx cz] ────────────────────────────────────────────

    /**
     * Two modes:
     *   /clan claim remove           – removes the chunk the player is standing in
     *   /clan claim remove <cx> <cz> – removes the chunk at the given chunk-coordinates
     *                                  inside the player's current world
     *
     * Note: chunk coordinates ≠ block coordinates. chunk X = blockX >> 4.
     */
    private boolean handleRemove(Player player, String[] args) {
        if (!player.hasPermission("simpleclans.claim.unclaim")) {
            player.sendMessage(plugin.getMessage("no_permission", Map.of())); return true;
        }
        String clan = plugin.getClanOf(player.getUniqueId());
        if (clan == null) { player.sendMessage(plugin.getMessage("not_in_clan", Map.of())); return true; }

        String role = plugin.getRoleOf(player.getUniqueId());
        if (!role.equalsIgnoreCase("LEADER") && !role.equalsIgnoreCase("CO-LEADER")) {
            player.sendMessage("§c[!] Only leaders and co-leaders can remove claims."); return true;
        }

        String world;
        int cx, cz;

        if (args.length >= 4) {
            // Explicit chunk coords provided
            try {
                cx    = Integer.parseInt(args[2]);
                cz    = Integer.parseInt(args[3]);
                world = player.getWorld().getName();
            } catch (NumberFormatException e) {
                player.sendMessage("§c[!] Usage: §e/clan claim remove §ror §e/clan claim remove <chunkX> <chunkZ>");
                return true;
            }
        } else {
            // Use current chunk
            Chunk chunk = player.getLocation().getChunk();
            world = chunk.getWorld().getName();
            cx    = chunk.getX();
            cz    = chunk.getZ();
        }

        String owner = getClaimOwner(world, cx, cz);
        if (owner == null) {
            player.sendMessage("§c[!] Chunk [§f" + cx + "§c, §f" + cz + "§c] in §f" + world + " §cis not claimed.");
            return true;
        }
        if (!owner.equalsIgnoreCase(clan)) {
            player.sendMessage("§c[!] That chunk belongs to §e" + owner + "§c, not your clan.");
            return true;
        }

        unclaimChunk(world, cx, cz);

        double costPerChunk = plugin.getConfig().getDouble("clan-land-claim.upkeep-cost-per-chunk", 10.0);
        int    intervalHrs  = plugin.getConfig().getInt("clan-land-claim.upkeep-interval-hours", 24);
        int    remaining    = getClaimCount(clan);

        player.sendMessage("§6[Simpleclan-PLUS] §aChunk [§f" + cx + "§a, §f" + cz + "§a] removed.");
        player.sendMessage(String.format(
            "§7New upkeep total: §f$%.2f§7/§f%dh §7(§f%d §7chunk%s remaining).",
            costPerChunk * remaining, intervalHrs, remaining, remaining == 1 ? "" : "s"));
        return true;
    }

    // ─── /clan claim buy ───────────────────────────────────────────────────────

    /**
     * Charges the player's personal wallet (Vault) for one extra claim slot.
     *
     * Cost = baseCost × multiplier^(slots already purchased)
     *   base=100, multiplier=2 →  $100 / $200 / $400 / $800 …
     */
    private boolean handleBuy(Player player) {
        if (!player.hasPermission("simpleclans.claim.buy")) {
            player.sendMessage(plugin.getMessage("no_permission", Map.of())); return true;
        }
        String clan = plugin.getClanOf(player.getUniqueId());
        if (clan == null) { player.sendMessage(plugin.getMessage("not_in_clan", Map.of())); return true; }

        String role = plugin.getRoleOf(player.getUniqueId());
        if (!role.equalsIgnoreCase("LEADER") && !role.equalsIgnoreCase("CO-LEADER")) {
            player.sendMessage("§c[!] Only leaders and co-leaders can purchase claim slots."); return true;
        }

        if (plugin.getEconomy() == null) {
            player.sendMessage("§c[!] Economy is not available on this server."); return true;
        }

        double baseCost   = plugin.getConfig().getDouble("clan-land-claim.upgrade-base-cost", 100.0);
        double multiplier = plugin.getConfig().getDouble("clan-land-claim.upgrade-cost-multiplier", 2.0);
        int    extra      = getExtraClaims(clan);
        double cost       = baseCost * Math.pow(multiplier, extra);

        if (!plugin.getEconomy().has(player, cost)) {
            player.sendMessage(String.format(
                "§c[!] Need §f$%.2f §cto unlock a slot. Your balance: §f$%.2f§c.",
                cost, plugin.getEconomy().getBalance(player)));
            return true;
        }

        plugin.getEconomy().withdrawPlayer(player, cost);
        addExtraClaimSlot(clan);

        int    newMax       = getMaxClaims(clan);
        double nextCost     = baseCost * Math.pow(multiplier, extra + 1);
        double costPerChunk = plugin.getConfig().getDouble("clan-land-claim.upkeep-cost-per-chunk", 10.0);
        int    intervalHrs  = plugin.getConfig().getInt("clan-land-claim.upkeep-interval-hours", 24);

        player.sendMessage(String.format(
            "§6[Simpleclan-PLUS] §aSlot purchased for §f$%.2f§a. Clan limit: §f%d §achunks.", cost, newMax));
        player.sendMessage(String.format(
            "§7Max upkeep if fully claimed: §f$%.2f§7/§f%dh§7. Next slot: §f$%.2f§7.",
            costPerChunk * newMax, intervalHrs, nextCost));
        return true;
    }

    // ─── /clan claimlist ───────────────────────────────────────────────────────

    private boolean handleClaimList(Player player) {
        if (!player.hasPermission("simpleclans.claim.list")) {
            player.sendMessage(plugin.getMessage("no_permission", Map.of())); return true;
        }
        String clan = plugin.getClanOf(player.getUniqueId());
        if (clan == null) { player.sendMessage(plugin.getMessage("not_in_clan", Map.of())); return true; }

        int    maxClaims    = getMaxClaims(clan);
        int    used         = getClaimCount(clan);
        int    extraSlots   = getExtraClaims(clan);
        double costPerChunk = plugin.getConfig().getDouble("clan-land-claim.upkeep-cost-per-chunk", 10.0);
        int    intervalHrs  = plugin.getConfig().getInt("clan-land-claim.upkeep-interval-hours", 24);
        long   graceSince   = getGraceSince(clan);

        player.sendMessage("§6===== §e🏔 Claims for §f" + clan + " §6=====");
        player.sendMessage("§7Chunks: §f" + used + " §7/ §f" + maxClaims +
            " §7(§f" + extraSlots + " §7purchased slot" + (extraSlots == 1 ? "" : "s") + ")");
        player.sendMessage(String.format(
            "§7Upkeep: §f$%.2f§7/chunk every §f%dh §7→ total §f$%.2f§7/§f%dh",
            costPerChunk, intervalHrs, costPerChunk * used, intervalHrs));

        if (graceSince > 0) {
            long graceMs      = TimeUnit.HOURS.toMillis(
                                    plugin.getConfig().getInt("clan-land-claim.upkeep-grace-period-hours", 48));
            long expiresInHrs = Math.max(0,
                                    TimeUnit.MILLISECONDS.toHours(graceMs - (System.currentTimeMillis() - graceSince)));
            player.sendMessage("§c§l⚠ UPKEEP OVERDUE §r§c— decay begins in §f" + expiresInHrs + "h§c. Deposit to clan bank!");
        }

        player.sendMessage("§7Chunks (oldest first):");
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "SELECT world, chunk_x, chunk_z, claimed_by, claimed_time " +
            "FROM clan_claims WHERE clan = ? ORDER BY claimed_time ASC")) {
            ps.setString(1, clan);
            ResultSet rs = ps.executeQuery();
            boolean any = false;
            while (rs.next()) {
                any = true;
                long ageDays = TimeUnit.MILLISECONDS.toDays(
                                   System.currentTimeMillis() - rs.getLong("claimed_time"));
                player.sendMessage("§7• §f" + rs.getString("world") +
                    " §7[§f" + rs.getInt("chunk_x") + "§7, §f" + rs.getInt("chunk_z") + "§7]" +
                    " §7by §e" + rs.getString("claimed_by") +
                    " §7(§f" + ageDays + "d §7ago)");
            }
            if (!any) player.sendMessage("§7  No chunks claimed yet.");
        } catch (SQLException e) { e.printStackTrace(); }

        if (plugin.getEconomy() != null) {
            double baseCost   = plugin.getConfig().getDouble("clan-land-claim.upgrade-base-cost", 100.0);
            double multiplier = plugin.getConfig().getDouble("clan-land-claim.upgrade-cost-multiplier", 2.0);
            player.sendMessage(String.format(
                "§7Next slot: §f$%.2f §7via §e/clan claim buy",
                baseCost * Math.pow(multiplier, extraSlots)));
        }
        player.sendMessage("§6=================================");
        return true;
    }

    // ─── /clan claiminfo ───────────────────────────────────────────────────────

    private boolean handleClaimInfo(Player player) {
        if (!player.hasPermission("simpleclans.claim.info")) {
            player.sendMessage(plugin.getMessage("no_permission", Map.of())); return true;
        }

        Chunk  chunk = player.getLocation().getChunk();
        String owner = getClaimOwner(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());

        if (owner == null) {
            player.sendMessage("§7Chunk [§f" + chunk.getX() + "§7, §f" + chunk.getZ() + "§7] is §aunclaimed§7.");
        } else {
            boolean inGrace = getGraceSince(owner) > 0;
            player.sendMessage("§7Chunk [§f" + chunk.getX() + "§7, §f" + chunk.getZ() +
                "§7] is claimed by §e" + owner + "§7." + (inGrace ? " §c(upkeep overdue)" : ""));
        }
        return true;
    }

    // ─── PROTECTION EVENTS ─────────────────────────────────────────────────────

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (!plugin.getConfig().getBoolean("features.clan-land-claim", true)) return;
        if (!plugin.getConfig().getBoolean("clan-land-claim.protect-from-enemies", true)) return;

        Player player = event.getPlayer();
        if (player.hasPermission("simpleclans.claim.bypass")) return;

        String chunkOwner = getClaimOwner(
            event.getBlock().getWorld().getName(),
            event.getBlock().getChunk().getX(),
            event.getBlock().getChunk().getZ());
        if (chunkOwner == null) return;

        String playerClan = plugin.getClanOf(player.getUniqueId());
        if (playerClan == null || !playerClan.equalsIgnoreCase(chunkOwner)) {
            event.setCancelled(true);
            player.sendMessage("§c[!] This chunk belongs to clan §e" + chunkOwner + "§c.");
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!plugin.getConfig().getBoolean("features.clan-land-claim", true)) return;
        if (!plugin.getConfig().getBoolean("clan-land-claim.protect-from-enemies", true)) return;

        Player player = event.getPlayer();
        if (player.hasPermission("simpleclans.claim.bypass")) return;

        String chunkOwner = getClaimOwner(
            event.getBlock().getWorld().getName(),
            event.getBlock().getChunk().getX(),
            event.getBlock().getChunk().getZ());
        if (chunkOwner == null) return;

        String playerClan = plugin.getClanOf(player.getUniqueId());
        if (playerClan == null || !playerClan.equalsIgnoreCase(chunkOwner)) {
            event.setCancelled(true);
            player.sendMessage("§c[!] This chunk belongs to clan §e" + chunkOwner + "§c.");
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!plugin.getConfig().getBoolean("features.clan-land-claim", true)) return;
        if (!plugin.getConfig().getBoolean("clan-land-claim.protect-pvp-in-claims", true)) return;

        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity()  instanceof Player victim))   return;
        if (attacker.hasPermission("simpleclans.claim.bypass")) return;

        String chunkOwner = getClaimOwner(
            victim.getLocation().getWorld().getName(),
            victim.getLocation().getChunk().getX(),
            victim.getLocation().getChunk().getZ());
        if (chunkOwner == null) return;

        String victimClan = plugin.getClanOf(victim.getUniqueId());
        if (victimClan != null && victimClan.equalsIgnoreCase(chunkOwner)) {
            String attackerClan = plugin.getClanOf(attacker.getUniqueId());
            if (attackerClan == null || !attackerClan.equalsIgnoreCase(chunkOwner)) {
                event.setCancelled(true);
                attacker.sendMessage("§c[!] You cannot attack players in §e" + chunkOwner + "§c's territory.");
            }
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!plugin.getConfig().getBoolean("features.clan-land-claim", true)) return;
        if (!plugin.getConfig().getBoolean("clan-land-claim.show-border-messages", true)) return;
        if (event.getFrom().getChunk().equals(event.getTo().getChunk())) return;

        Player player    = event.getPlayer();
        String world     = event.getTo().getChunk().getWorld().getName();
        String oldOwner  = getClaimOwner(world, event.getFrom().getChunk().getX(), event.getFrom().getChunk().getZ());
        String newOwner  = getClaimOwner(world, event.getTo().getChunk().getX(),   event.getTo().getChunk().getZ());

        if (!Objects.equals(oldOwner, newOwner)) {
            if (newOwner != null) {
                boolean inGrace = getGraceSince(newOwner) > 0;
                player.sendMessage("§e[Territory] §7Entering §e" + newOwner + "§7's territory." +
                    (inGrace ? " §c(upkeep overdue)" : ""));
            } else {
                player.sendMessage("§e[Territory] §7Entering §awilderness§7.");
            }
        }
    }

    // ─── DATABASE — CLAIMS ─────────────────────────────────────────────────────

    public String getClaimOwner(String world, int cx, int cz) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "SELECT clan FROM clan_claims WHERE world = ? AND chunk_x = ? AND chunk_z = ?")) {
            ps.setString(1, world); ps.setInt(2, cx); ps.setInt(3, cz);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("clan");
        } catch (SQLException e) { e.printStackTrace(); }
        return null;
    }

    private void claimChunk(String clan, String world, int cx, int cz, String claimedBy) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "INSERT OR REPLACE INTO clan_claims(clan, world, chunk_x, chunk_z, claimed_by, claimed_time) " +
            "VALUES(?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, clan);     ps.setString(2, world);
            ps.setInt(3, cx);          ps.setInt(4, cz);
            ps.setString(5, claimedBy); ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private void unclaimChunk(String world, int cx, int cz) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "DELETE FROM clan_claims WHERE world = ? AND chunk_x = ? AND chunk_z = ?")) {
            ps.setString(1, world); ps.setInt(2, cx); ps.setInt(3, cz);
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public int getClaimCount(String clan) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "SELECT COUNT(*) AS c FROM clan_claims WHERE clan = ?")) {
            ps.setString(1, clan);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt("c");
        } catch (SQLException e) { e.printStackTrace(); }
        return 0;
    }

    private List<String> getAllClansWithClaims() {
        List<String> list = new ArrayList<>();
        try (Statement stmt = plugin.getConnection().createStatement();
             ResultSet rs   = stmt.executeQuery("SELECT DISTINCT clan FROM clan_claims")) {
            while (rs.next()) list.add(rs.getString("clan"));
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    /**
     * Returns all chunks for a clan sorted oldest → newest.
     * Each element is { world, chunkX (as string), chunkZ (as string) }.
     */
    private List<String[]> getChunksByAgeWithWorld(String clan) {
        List<String[]> list = new ArrayList<>();
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "SELECT world, chunk_x, chunk_z FROM clan_claims " +
            "WHERE clan = ? ORDER BY claimed_time ASC")) {
            ps.setString(1, clan);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(new String[]{
                    rs.getString("world"),
                    String.valueOf(rs.getInt("chunk_x")),
                    String.valueOf(rs.getInt("chunk_z"))
                });
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    // ─── DATABASE — UPGRADES & UPKEEP STATE ────────────────────────────────────

    public int getMaxClaims(String clan) {
        return plugin.getConfig().getInt("clan-land-claim.max-start-claims", 4) + getExtraClaims(clan);
    }

    public int getExtraClaims(String clan) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "SELECT extra_claims FROM clan_claim_upgrades WHERE clan = ?")) {
            ps.setString(1, clan);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt("extra_claims");
        } catch (SQLException e) { e.printStackTrace(); }
        return 0;
    }

    private void addExtraClaimSlot(String clan) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "INSERT INTO clan_claim_upgrades(clan, extra_claims, last_charged_ms, grace_since_ms) " +
            "VALUES(?, 1, 0, 0) " +
            "ON CONFLICT(clan) DO UPDATE SET extra_claims = extra_claims + 1")) {
            ps.setString(1, clan);
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private long getLastCharged(String clan) { return getLongColumn(clan, "last_charged_ms"); }
    private void setLastCharged(String clan, long ts) { setLongColumn(clan, "last_charged_ms", ts); }

    public long getGraceSince(String clan) { return getLongColumn(clan, "grace_since_ms"); }
    private void setGraceSince(String clan, long ts) { setLongColumn(clan, "grace_since_ms", ts); }

    private long getLongColumn(String clan, String col) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "SELECT " + col + " FROM clan_claim_upgrades WHERE clan = ?")) {
            ps.setString(1, clan);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getLong(col);
        } catch (SQLException e) { e.printStackTrace(); }
        return 0L;
    }

    private void setLongColumn(String clan, String col, long value) {
        // Upsert: insert row if missing, then update the target column
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "INSERT INTO clan_claim_upgrades(clan, extra_claims, last_charged_ms, grace_since_ms) " +
            "VALUES(?, 0, 0, 0) ON CONFLICT(clan) DO UPDATE SET " + col + " = ?")) {
            ps.setString(1, clan);
            ps.setLong(2, value);
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    // ─── CLAN DISBAND ──────────────────────────────────────────────────────────

    /** Called on clan disband — wipes all claims and upgrade/upkeep state. */
    public void removeAllClaimsFor(String clan) {
        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "DELETE FROM clan_claims WHERE clan = ?")) {
            ps.setString(1, clan); ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }

        try (PreparedStatement ps = plugin.getConnection().prepareStatement(
            "DELETE FROM clan_claim_upgrades WHERE clan = ?")) {
            ps.setString(1, clan); ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }
}