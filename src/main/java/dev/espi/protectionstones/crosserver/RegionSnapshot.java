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

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.managers.RemovalStrategy;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import dev.espi.protectionstones.FlagHandler;
import dev.espi.protectionstones.ProtectionStones;
import dev.espi.protectionstones.utils.WGUtils;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A serializable, server-independent representation of a ProtectionStones region.
 *
 * It captures everything needed to recreate or update the underlying WorldGuard region on another
 * server: its geometry, owners, members, parent and all of its flags (stored in the canonical
 * WorldGuard "marshalled" form, the same representation WorldGuard itself uses to persist regions).
 *
 * Instances are (de)serialized as JSON by FancyCore's Redis messenger, so all fields are plain data
 * and a no-argument constructor is required.
 */
public class RegionSnapshot {

    // the server that produced this snapshot, used to ignore our own broadcasts
    String serverId;
    String world;
    String id;

    int minX, minY, minZ, maxX, maxY, maxZ;
    int priority;
    String parentId;

    List<String> owners = new ArrayList<>();
    List<String> members = new ArrayList<>();

    // WorldGuard flags keyed by flag name, stored in their marshalled (persistable) form
    Map<String, Object> flags = new HashMap<>();

    public RegionSnapshot() {} // required for JSON deserialization

    /**
     * Build a snapshot from a live WorldGuard region. Must be called on the server thread.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static RegionSnapshot capture(String serverId, World world, ProtectedRegion region) {
        RegionSnapshot s = new RegionSnapshot();
        s.serverId = serverId;
        s.world = world.getName();
        s.id = region.getId();

        BlockVector3 min = region.getMinimumPoint(), max = region.getMaximumPoint();
        s.minX = min.getX(); s.minY = min.getY(); s.minZ = min.getZ();
        s.maxX = max.getX(); s.maxY = max.getY(); s.maxZ = max.getZ();
        s.priority = region.getPriority();
        s.parentId = region.getParent() == null ? null : region.getParent().getId();

        for (UUID u : region.getOwners().getUniqueIds()) s.owners.add(u.toString());
        for (UUID u : region.getMembers().getUniqueIds()) s.members.add(u.toString());

        for (Map.Entry<Flag<?>, Object> e : region.getFlags().entrySet()) {
            try {
                Object marshalled = ((Flag) e.getKey()).marshal(e.getValue());
                if (marshalled != null) s.flags.put(e.getKey().getName(), marshalled);
            } catch (Exception ignored) {
                // skip flags that fail to marshal rather than dropping the whole snapshot
            }
        }
        return s;
    }

    /**
     * Create or replace the matching region in this server's WorldGuard manager. Must be called on
     * the server thread. No-op if this server does not host the snapshot's world.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    void applyUpsert() {
        World w = Bukkit.getWorld(world);
        if (w == null) return; // this server does not have the world; nothing to sync
        RegionManager rgm = WGUtils.getRegionManagerWithWorld(w);
        if (rgm == null) return;

        ProtectedCuboidRegion region = new ProtectedCuboidRegion(id,
                BlockVector3.at(minX, minY, minZ), BlockVector3.at(maxX, maxY, maxZ));
        region.setPriority(priority);

        for (String u : owners) region.getOwners().addPlayer(UUID.fromString(u));
        for (String u : members) region.getMembers().addPlayer(UUID.fromString(u));

        FlagRegistry registry = WGUtils.getFlagRegistry();
        for (Map.Entry<String, Object> e : flags.entrySet()) {
            Flag flag = registry.get(e.getKey());
            if (flag == null) continue;
            try {
                Object value = flag.unmarshal(e.getValue());
                if (value != null) region.setFlag(flag, value);
            } catch (Exception ignored) {
                // ignore flags that fail to unmarshal (e.g. a flag not registered on this server)
            }
        }

        rgm.addRegion(region); // replaces any existing region sharing this id

        if (parentId != null) {
            ProtectedRegion parent = rgm.getRegion(parentId);
            if (parent != null) {
                try {
                    region.setParent(parent);
                } catch (ProtectedRegion.CircularInheritanceException ignored) {
                }
            }
        }

        refreshNameCache(w);
    }

    /**
     * Remove the matching region from this server's WorldGuard manager. Must be called on the server
     * thread. No-op if this server does not host the snapshot's world.
     */
    void applyDelete() {
        World w = Bukkit.getWorld(world);
        if (w == null) return;
        RegionManager rgm = WGUtils.getRegionManagerWithWorld(w);
        if (rgm == null) return;

        rgm.removeRegion(id, RemovalStrategy.UNSET_PARENT_IN_CHILDREN);
        removeFromNameCache(w);
    }

    // keep ProtectionStones' alias-to-id cache consistent after a remote change
    private void refreshNameCache(World w) {
        HashMap<String, ArrayList<String>> m = ProtectionStones.regionNameToID.computeIfAbsent(w.getUID(), k -> new HashMap<>());
        m.values().forEach(list -> list.remove(id));
        String name = nameFlag();
        if (name != null && !name.isEmpty()) {
            m.computeIfAbsent(name, k -> new ArrayList<>()).add(id);
        }
    }

    private void removeFromNameCache(World w) {
        HashMap<String, ArrayList<String>> m = ProtectionStones.regionNameToID.get(w.getUID());
        if (m != null) m.values().forEach(list -> list.remove(id));
    }

    // ~~~~~~ helpers used by the cross-server teleport routing ~~~~~~

    String nameFlag() {
        Object o = flags.get(FlagHandler.PS_NAME.getName());
        return o instanceof String s ? s : null;
    }

    String homeFlag() {
        Object o = flags.get(FlagHandler.PS_HOME.getName());
        return o instanceof String s ? s : null;
    }

    boolean isOwnerOrMember(UUID uuid) {
        String u = uuid.toString();
        return owners.contains(u) || members.contains(u);
    }
}
