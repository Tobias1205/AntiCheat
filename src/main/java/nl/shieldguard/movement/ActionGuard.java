package nl.shieldguard.movement;

import nl.shieldguard.ShieldGuard;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Low-confidence building-action heuristics. Alerts staff only; never cancels or punishes a player. */
public final class ActionGuard implements Listener {
    private final ShieldGuard plugin;
    private final Map<UUID, Deque<Long>> placements = new HashMap<>();
    private final Map<UUID, Deque<Long>> scaffoldLike = new HashMap<>();
    private final Map<UUID, Long> lastAlert = new HashMap<>();
    private boolean enabled;
    private boolean scaffoldEnabled;
    private boolean fastPlaceEnabled;
    private int windowMs;
    private int scaffoldThreshold;
    private int fastPlaceThreshold;
    private long alertCooldownMs;

    public ActionGuard(ShieldGuard plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        var config = plugin.getConfig();
        enabled = config.getBoolean("actions.enabled", true);
        scaffoldEnabled = config.getBoolean("actions.scaffold.enabled", true);
        fastPlaceEnabled = config.getBoolean("actions.fast-place.enabled", true);
        windowMs = Math.max(500, config.getInt("actions.window-ms", 2500));
        scaffoldThreshold = Math.max(4, config.getInt("actions.scaffold.minimum-placements", 7));
        fastPlaceThreshold = Math.max(8, config.getInt("actions.fast-place.minimum-per-second", 18));
        alertCooldownMs = Math.max(1000L, config.getLong("actions.alert-cooldown-seconds", 10) * 1000L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!enabled) return;
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR
                || player.hasPermission("shieldguard.action.bypass")) return;

        long now = System.nanoTime();
        UUID id = player.getUniqueId();
        if (fastPlaceEnabled) {
            Deque<Long> recent = placements.computeIfAbsent(id, ignored -> new ArrayDeque<>());
            recent.addLast(now);
            trim(recent, now, 1_000_000_000L);
            if (recent.size() >= fastPlaceThreshold) {
                alert(player, "fast-place", recent.size() + " blocks placed in one second");
                recent.clear();
            }
        }

        if (scaffoldEnabled && isNearFeet(player, event.getBlockPlaced().getLocation())) {
            Deque<Long> recent = scaffoldLike.computeIfAbsent(id, ignored -> new ArrayDeque<>());
            recent.addLast(now);
            trim(recent, now, windowMs * 1_000_000L);
            if (recent.size() >= scaffoldThreshold) {
                alert(player, "scaffold-pattern", recent.size() + " blocks placed around/below the player's feet in "
                        + (windowMs / 1000.0) + " seconds");
                recent.clear();
            }
        }
    }

    private static boolean isNearFeet(Player player, Location block) {
        Location feet = player.getLocation();
        if (feet.getWorld() != block.getWorld()) return false;
        int y = block.getBlockY();
        int feetY = feet.getBlockY();
        double dx = block.getX() + 0.5 - feet.getX();
        double dz = block.getZ() + 0.5 - feet.getZ();
        return y >= feetY - 2 && y <= feetY && dx * dx + dz * dz <= 4.0;
    }

    private static void trim(Deque<Long> values, long now, long windowNanos) {
        while (!values.isEmpty() && now - values.peekFirst() > windowNanos) values.removeFirst();
    }

    private void alert(Player player, String check, String details) {
        long now = System.currentTimeMillis();
        Long previous = lastAlert.get(player.getUniqueId());
        if (previous != null && now - previous < alertCooldownMs) return;
        lastAlert.put(player.getUniqueId(), now);
        String message = "[ShieldGuard] " + player.getName() + " action check " + check + ": " + details
                + " (heuristic; controleer handmatig)";
        plugin.getLogger().warning(message);
        Component component = Component.text(message, NamedTextColor.RED);
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("shieldguard.alerts")) staff.sendMessage(component);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        placements.remove(id);
        scaffoldLike.remove(id);
        lastAlert.remove(id);
    }
}
