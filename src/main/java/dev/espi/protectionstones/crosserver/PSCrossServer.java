/*
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.espi.protectionstones.crosserver;

import com.sk89q.worldguard.protection.managers.RegionManager;
import dev.ephemeral.core.api.CoreAPI;
import dev.ephemeral.core.config.CoreSettings;
import dev.ephemeral.core.database.redis.RedisMessenger;
import dev.espi.protectionstones.PSRegion;
import dev.espi.protectionstones.ProtectionStones;
import dev.espi.protectionstones.utils.WGUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Entry point for ProtectionStones' cross-server features, built on top of FancyMonolith's FancyCore.
 *
 * <p>ProtectionStones does not start its own FancyCore instance; it reuses the one already initialized
 * by the host plugin (FancySurvival) and only borrows its Redis messenger. Every method here is a
 * no-op unless {@link #isEnabled()} is true, and the class is only ever touched when FancyCore is
 * actually present on the server (see {@link ProtectionStones#crossServerEnabled}).</p>
 *
 * <p>Two things travel over Redis:</p>
 * <ul>
 *     <li>Region data: create/update/delete are broadcast as {@link RegionSyncPacket}s so every
 *     server that hosts the same world keeps an identical copy of the region.</li>
 *     <li>Teleports: when a player asks to teleport to a region that lives on another server, a
 *     pending-teleport marker is written to Redis and the player is sent across via BungeeCord.</li>
 * </ul>
 */
public final class PSCrossServer {

    // index used by the teleport routing to find which server hosts a region
    private static final String REGION_KEY = "protectionstones:region:";        // + <world>:<id>
    private static final String NAME_KEY = "protectionstones:regionname:";      // + <lowercase name>
    private static final String PLAYER_KEY = "protectionstones:player:";        // + <uuid>:<world>:<id>
    static final String PENDING_TP_KEY = "protectionstones:pendingtp:";         // + <player uuid>

    // index entries are refreshed on every change; the TTL only reaps regions that vanish silently
    private static final long REGION_TTL_SECONDS = 60L * 60L * 24L * 30L;
    private static final long PENDING_TP_TTL_SECONDS = 30L;

    private static volatile boolean enabled = false;
    private static String serverId;
    private static RedisMessenger redis;

    // set while we are applying a packet received from another server, to avoid re-broadcasting it
    private static final ThreadLocal<Boolean> APPLYING_REMOTE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private PSCrossServer() {}

    /**
     * Hook into the FancyCore instance owned by the host plugin. Throws (caught by the caller) if
     * FancyCore is not on the classpath; returns quietly if it is present but not initialized.
     */
    public static void init() {
        RedisMessenger messenger = CoreAPI.getOptional(RedisMessenger.class).orElse(null);
        if (messenger == null) return;

        redis = messenger;
        serverId = CoreAPI.get(CoreSettings.class).serverId();

        CoreAPI.registerPackets(RegionSyncPacket.class);
        enabled = true;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static String serverId() {
        return serverId;
    }

    static boolean isApplyingRemote() {
        return APPLYING_REMOTE.get();
    }

    static void setApplyingRemote(boolean applying) {
        APPLYING_REMOTE.set(applying);
    }

    // ~~~~~~~~~~~~~~~~~ region data sync ~~~~~~~~~~~~~~~~~

    /**
     * Broadcast a region create/update to the rest of the network and refresh its location index.
     */
    public static void onRegionChanged(PSRegion region) {
        if (!enabled || APPLYING_REMOTE.get()) return;
        if (region == null || region.getWGRegion() == null) return;

        RegionSnapshot snapshot = RegionSnapshot.capture(serverId, region.getWorld(), region.getWGRegion());

        CoreAPI.sendPacket(new RegionSyncPacket(RegionSyncPacket.Action.UPSERT, snapshot));
        indexRegion(snapshot);
    }

    /**
     * Broadcast a region deletion to the rest of the network and drop it from the location index.
     */
    public static void onRegionDeleted(World world, String id) {
        if (!enabled || APPLYING_REMOTE.get()) return;
        if (world == null || id == null) return;

        RegionSnapshot snapshot = new RegionSnapshot();
        snapshot.serverId = serverId;
        snapshot.world = world.getName();
        snapshot.id = id;

        CoreAPI.sendPacket(new RegionSyncPacket(RegionSyncPacket.Action.DELETE, snapshot));
        redis.delete(REGION_KEY + world.getName().toLowerCase() + ":" + id);
    }

    private static void indexRegion(RegionSnapshot snapshot) {
        String world = snapshot.world.toLowerCase();
        redis.set(REGION_KEY + world + ":" + snapshot.id, indexLine(snapshot), REGION_TTL_SECONDS);

        String name = snapshot.nameFlag();
        if (name != null && !name.isEmpty()) {
            redis.set(NAME_KEY + name.toLowerCase(), snapshot.world + " " + snapshot.id, REGION_TTL_SECONDS);
        }

        // per-player index so a player's regions can be enumerated across the whole network
        Set<String> people = new HashSet<>(snapshot.owners);
        people.addAll(snapshot.members);
        for (String uuid : people) {
            redis.set(PLAYER_KEY + uuid + ":" + world + ":" + snapshot.id, "", REGION_TTL_SECONDS);
        }
    }

    // compact, dependency-free serialization of the data the listing/teleport routing needs.
    // home values and names may contain spaces, so a tab is used as the field separator.
    private static String indexLine(RegionSnapshot snapshot) {
        String home = snapshot.homeFlag() == null ? "" : snapshot.homeFlag();
        String name = snapshot.nameFlag() == null ? "" : snapshot.nameFlag();
        return String.join("\t", snapshot.serverId, snapshot.world, home, name,
                String.join(",", snapshot.owners), String.join(",", snapshot.members));
    }

    private static RegionLocation parseLine(String line) {
        String[] p = line.split("\t", -1);
        if (p.length < 6) return null;
        Set<String> owners = p[4].isEmpty() ? Set.of() : new HashSet<>(Arrays.asList(p[4].split(",")));
        Set<String> members = p[5].isEmpty() ? Set.of() : new HashSet<>(Arrays.asList(p[5].split(",")));
        return new RegionLocation(p[0], p[1], p[2].isEmpty() ? null : p[2], p[3].isEmpty() ? null : p[3], owners, members);
    }

    // where a region lives on the network, plus just enough to list it and teleport a player to it
    private record RegionLocation(String serverId, String world, String home, String name,
                                  Set<String> owners, Set<String> members) {
        boolean isOwner(UUID uuid) {
            return owners.contains(uuid.toString());
        }

        boolean isMember(UUID uuid) {
            return members.contains(uuid.toString());
        }

        boolean isOwnerOrMember(UUID uuid) {
            return isOwner(uuid) || isMember(uuid);
        }
    }

    // ~~~~~~~~~~~~~~~~~ network-wide region listing ~~~~~~~~~~~~~~~~~

    /**
     * A region that lives on another server, as seen from this one. Used by /ps list, /ps count,
     * /ps tp and /ps home to show and reach regions the local WorldGuard does not have.
     */
    public record NetworkRegion(String serverId, String world, String id, String name, String home,
                                boolean owner, boolean member) {
        public String displayName() {
            return name == null ? id : name + " (" + id + ")";
        }
    }

    /**
     * Enumerate the regions a player owns or is a member of on OTHER servers of the network. Local
     * regions are excluded (they are already returned by the normal WorldGuard queries). Must be
     * called from an asynchronous context (it performs blocking Redis lookups).
     */
    public static List<NetworkRegion> getNetworkRegions(UUID uuid) {
        List<NetworkRegion> result = new ArrayList<>();
        if (!enabled) return result;

        try {
            String prefix = PLAYER_KEY + uuid + ":";
            for (String key : redis.scanKeys(prefix + "*").join()) {
                String rest = key.substring(prefix.length()); // "<world>:<id>"
                int sep = rest.indexOf(':');
                if (sep < 0) continue;
                String world = rest.substring(0, sep), id = rest.substring(sep + 1);

                Optional<String> line = redis.get(REGION_KEY + world + ":" + id).join();
                if (line.isEmpty()) continue; // region was deleted; stale index entry

                RegionLocation loc = parseLine(line.get());
                if (loc == null || serverId.equals(loc.serverId())) continue; // skip malformed or local

                boolean owner = loc.isOwner(uuid), member = loc.isMember(uuid);
                if (!owner && !member) continue; // player no longer in the region; stale entry

                // skip regions that already exist in this server's WorldGuard (shared-world
                // replication), otherwise they would be listed/counted twice
                if (existsLocally(loc.world(), id)) continue;

                result.add(new NetworkRegion(loc.serverId(), loc.world(), id, loc.name(), loc.home(), owner, member));
            }
        } catch (Exception ignored) {
            // a Redis failure simply yields no remote regions
        }
        return result;
    }

    private static boolean existsLocally(String worldName, String id) {
        World w = Bukkit.getWorld(worldName);
        if (w == null) return false;
        RegionManager rgm = WGUtils.getRegionManagerWithWorld(w);
        return rgm != null && rgm.getRegion(id) != null;
    }

    /**
     * Send a player to a region that lives on another server and teleport them to its home on arrival.
     * Returns false if the region has no home set.
     */
    public static boolean teleportTo(Player player, NetworkRegion region) {
        if (!enabled || region.home() == null) return false;

        redis.set(PENDING_TP_KEY + player.getUniqueId(), region.world() + " " + region.home(), PENDING_TP_TTL_SECONDS).join();
        player.sendMessage("§7Teleporting you to §b" + region.serverId() + "§7...");
        connect(player, region.serverId());
        return true;
    }

    // ~~~~~~~~~~~~~~~~~ cross-server teleport ~~~~~~~~~~~~~~~~~

    /**
     * If {@code query} matches a region that lives on another server, send the player there and
     * teleport them to its home on arrival.
     *
     * <p>Must be called from an asynchronous context (it performs blocking Redis lookups). Returns
     * {@code true} only when the teleport was routed to another server; {@code false} means the
     * region was not found on the network, lives on this server, or has no home set, in which case
     * the caller should fall back to its normal "region not found" handling.</p>
     *
     * @param requireMembership whether the player must be an owner or member of the region (used by
     *                          {@code /ps home}, but not by {@code /ps tp})
     */
    public static boolean attemptCrossServerTeleport(Player player, String query, boolean requireMembership) {
        if (!enabled) return false;

        RegionLocation location = lookup(player.getWorld(), query);
        if (location == null) return false;

        // the region is on this server; let the normal local code path handle it
        if (serverId.equals(location.serverId())) return false;

        if (requireMembership && !location.isOwnerOrMember(player.getUniqueId())) return false;

        String home = location.home();
        if (home == null) return false;

        // remember where to drop the player once they reach the destination server
        redis.set(PENDING_TP_KEY + player.getUniqueId(), location.world() + " " + home, PENDING_TP_TTL_SECONDS).join();

        player.sendMessage("§7Teleporting you to §b" + location.serverId() + "§7...");
        connect(player, location.serverId());
        return true;
    }

    private static RegionLocation lookup(World playerWorld, String query) {
        try {
            // 1) treat the query as a region id scoped to the player's world (matches /ps tp <id>)
            Optional<String> byId = redis.get(REGION_KEY + playerWorld.getName().toLowerCase() + ":" + query).join();
            if (byId.isPresent()) {
                RegionLocation loc = parseLine(byId.get());
                if (loc != null) return loc;
            }

            // 2) treat the query as a region name (names are global in ProtectionStones)
            Optional<String> pointer = redis.get(NAME_KEY + query.toLowerCase()).join();
            if (pointer.isPresent()) {
                String[] parts = pointer.get().split(" ", 2);
                if (parts.length == 2) {
                    Optional<String> regionJson = redis.get(REGION_KEY + parts[0].toLowerCase() + ":" + parts[1]).join();
                    if (regionJson.isPresent()) {
                        RegionLocation loc = parseLine(regionJson.get());
                        if (loc != null && query.equalsIgnoreCase(loc.name())) return loc;
                    }
                }
            }

            // 3) treat the query as a region id on any world of the network (covers regions whose
            //    world does not exist on this server)
            for (String key : redis.scanKeys(REGION_KEY + "*:" + query).join()) {
                Optional<String> line = redis.get(key).join();
                if (line.isPresent()) {
                    RegionLocation loc = parseLine(line.get());
                    if (loc != null) return loc;
                }
            }
        } catch (Exception ignored) {
            // any Redis or parsing failure simply means "not found on the network"
        }
        return null;
    }

    /**
     * Teleport a player to a pending cross-server destination, if one was queued for them. Called
     * from the join listener once the player has arrived on this server.
     */
    static void flushPendingTeleport(Player player) {
        if (!enabled) return;

        UUID uuid = player.getUniqueId();
        redis.get(PENDING_TP_KEY + uuid).thenAccept(opt -> opt.ifPresent(value -> {
            redis.delete(PENDING_TP_KEY + uuid);

            Location loc = parseDestination(value);
            if (loc == null) return;

            Bukkit.getScheduler().runTaskLater(ProtectionStones.getInstance(), () -> {
                Player online = Bukkit.getPlayer(uuid);
                if (online != null && online.isOnline()) online.teleport(loc);
            }, 20L); // wait a second so the world finishes loading the player in
        }));
    }

    // value format: "<world> <x> <y> <z> [yaw] [pitch]" (home flag appended to the world name)
    private static Location parseDestination(String value) {
        try {
            String[] p = value.split(" ");
            if (p.length < 4) return null;
            World w = Bukkit.getWorld(p[0]);
            if (w == null) return null;

            double x = Double.parseDouble(p[1]), y = Double.parseDouble(p[2]), z = Double.parseDouble(p[3]);
            float yaw = p.length >= 6 ? Float.parseFloat(p[4]) : 0f;
            float pitch = p.length >= 6 ? Float.parseFloat(p[5]) : 0f;
            return new Location(w, x, y, z, yaw, pitch);
        } catch (Exception e) {
            return null;
        }
    }

    private static void connect(Player player, String server) {
        ProtectionStones plugin = ProtectionStones.getInstance();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                 DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeUTF("Connect");
                out.writeUTF(server);
                player.sendPluginMessage(plugin, "BungeeCord", bytes.toByteArray());
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to send cross-server teleport for " + player.getName() + ": " + e.getMessage());
            }
        });
    }
}
