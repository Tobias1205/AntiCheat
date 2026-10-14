package nl.shieldguard.movement;

import nl.shieldguard.ShieldGuard;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.BoundingBox;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Conservative combat heuristics. These checks alert staff only; they never cancel hits or punish players. */
public final class CombatGuard implements Listener {
    private final ShieldGuard plugin;
    private final Map<UUID, Deque<Long>> swings = new HashMap<>();
    private final Map<UUID, Long> lastAlert = new HashMap<>();
    private boolean enabled;
    private boolean reachEnabled;
    private boolean swingRateEnabled;
    private double maxReach;
    private int maxSwingsPerSecond;
    private int swingWindowSeconds;
    private long alertCooldownMs;

    public CombatGuard(ShieldGuard plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        var config = plugin.getConfig();
        enabled = config.getBoolean("combat.enabled", true);
        reachEnabled = config.getBoolean("combat.reach.enabled", true);
        maxReach = Math.max(3.0, config.getDouble("combat.reach.max-distance", 3.85));
        swingRateEnabled = config.getBoolean("combat.swing-rate.enabled", true);
        maxSwingsPerSecond = Math.max(10, config.getInt("combat.swing-rate.max-per-second", 30));
        swingWindowSeconds = Math.max(2, config.getInt("combat.swing-rate.window-seconds", 3));
        alertCooldownMs = Math.max(1000L, config.getLong("combat.alert-cooldown-seconds", 10) * 1000L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMeleeHit(EntityDamageByEntityEvent event) {
        if (!enabled || !reachEnabled || !(event.getDamager() instanceof Player attacker)
                || !(event.getEntity() instanceof LivingEntity target)) return;
        if (attacker.getGameMode() == GameMode.CREATIVE || attacker.getGameMode() == GameMode.SPECTATOR
                || attacker.hasPermission("shieldguard.combat.bypass") || attacker.getWorld() != target.getWorld()) return;

        double distance = distanceToBox(attacker.getEyeLocation(), target.getBoundingBox());
        if (distance > maxReach) {
            alert(attacker, "reach", String.format(java.util.Locale.ROOT,
                    "hit-distance=%.2f blocks (limit %.2f)", distance, maxReach));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent event) {
        if (!enabled || !swingRateEnabled) return;
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR
                || player.hasPermission("shieldguard.combat.bypass")) return;

        long now = System.nanoTime();
        long windowNanos = swingWindowSeconds * 1_000_000_000L;
        Deque<Long> recent = swings.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayDeque<>());
        recent.addLast(now);
        while (!recent.isEmpty() && now - recent.peekFirst() > windowNanos) recent.removeFirst();
        int limit = maxSwingsPerSecond * swingWindowSeconds;
        if (recent.size() > limit) {
            alert(player, "swing-rate", recent.size() + " arm swings in " + swingWindowSeconds + " seconds");
            recent.clear();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        swings.remove(id);
        lastAlert.remove(id);
    }

    private static double distanceToBox(Location point, BoundingBox box) {
        double dx = Math.max(Math.max(box.getMinX() - point.getX(), 0.0), point.getX() - box.getMaxX());
        double dy = Math.max(Math.max(box.getMinY() - point.getY(), 0.0), point.getY() - box.getMaxY());
        double dz = Math.max(Math.max(box.getMinZ() - point.getZ(), 0.0), point.getZ() - box.getMaxZ());
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private void alert(Player player, String check, String details) {
        long now = System.currentTimeMillis();
        Long previous = lastAlert.get(player.getUniqueId());
        if (previous != null && now - previous < alertCooldownMs) return;
        lastAlert.put(player.getUniqueId(), now);
        String message = "[ShieldGuard] " + player.getName() + " combat check " + check + ": " + details
                + " (heuristic; review before action)";
        plugin.getLogger().warning(message);
        Component component = Component.text(message, NamedTextColor.RED);
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("shieldguard.alerts")) staff.sendMessage(component);
        }
    }
}
