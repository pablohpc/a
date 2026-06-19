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

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Applies any cross-server teleport that was queued for a player on another server once they arrive.
 * Only registered when FancyCore-backed cross-server support is enabled.
 */
public class CrossServerListener implements Listener {

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!PSCrossServer.isEnabled()) return;
        PSCrossServer.flushPendingTeleport(event.getPlayer());
    }
}
