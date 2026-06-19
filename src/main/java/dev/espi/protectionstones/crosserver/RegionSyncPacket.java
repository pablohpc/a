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

import dev.ephemeral.core.database.redis.PacketChannel;
import dev.ephemeral.core.database.redis.RedisPacket;
import dev.espi.protectionstones.ProtectionStones;
import org.bukkit.Bukkit;

/**
 * Redis packet that propagates a region create/update or delete to every other server on the network.
 *
 * It is published through FancyCore's {@link dev.ephemeral.core.database.redis.RedisMessenger} and
 * executed on each receiving server. The actual WorldGuard mutation is scheduled back onto the server
 * thread, since region managers must not be touched asynchronously.
 */
@PacketChannel("protectionstones:region:sync")
public class RegionSyncPacket implements RedisPacket {

    public enum Action {
        UPSERT, DELETE
    }

    private Action action;
    private RegionSnapshot snapshot;

    public RegionSyncPacket() {} // required for JSON deserialization

    public RegionSyncPacket(Action action, RegionSnapshot snapshot) {
        this.action = action;
        this.snapshot = snapshot;
    }

    @Override
    public void execute() {
        if (action == null || snapshot == null) return;

        // ignore the broadcast we sent ourselves
        if (snapshot.serverId != null && snapshot.serverId.equals(PSCrossServer.serverId())) return;

        ProtectionStones plugin = ProtectionStones.getInstance();
        if (plugin == null) return;

        // WorldGuard mutations must happen on the server thread
        Bukkit.getScheduler().runTask(plugin, () -> {
            PSCrossServer.setApplyingRemote(true);
            try {
                switch (action) {
                    case UPSERT -> snapshot.applyUpsert();
                    case DELETE -> snapshot.applyDelete();
                }
            } finally {
                PSCrossServer.setApplyingRemote(false);
            }
        });
    }
}
