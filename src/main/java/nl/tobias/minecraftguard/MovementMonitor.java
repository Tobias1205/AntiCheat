package nl.tobias.minecraftguard;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

final class MovementMonitor implements Listener {
    private final MinecraftGuardPlugin plugin;
    private final Map<UUID, Long> lastAlert = new HashMap<>();
    private final Map<UUID, Integer> suspiciousMoves = new HashMap<>();
    private final Map<UUID, Long> airborneSince = new HashMap<>();

    MovementMonitor(MinecraftGuardPlugin plugin) { this.plugin = plugin; }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getConfig().getBoolean("movement.enabled", true)) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() != to.getWorld()) return;
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.SURVIVAL || player.isFlying() || player.isGliding()
                || player.isInsideVehicle() || player.isDead() || player.hasPermission("minecraftguard.bypass")) return;
        if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.LEVITATION)
                || player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING)) {
            airborneSince.remove(player.getUniqueId());
            return;
        }

        UUID id = player.getUniqueId();
        if (player.isOnGround()) {
            airborneSince.remove(id);
        } else if (!player.isInWater() && !player.isClimbing() && !player.isRiptiding()) {
            long since = airborneSince.computeIfAbsent(id, ignored -> System.currentTimeMillis());
            long flightMillis = plugin.getConfig().getLong("movement.sustained-flight-seconds", 8) * 1000L;
            if (System.currentTimeMillis() - since >= flightMillis) {
                alert(player, "sustained airborne movement", 0, 0);
                airborneSince.put(id, System.currentTimeMillis());
            }
        }

        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double vertical = Math.abs(to.getY() - from.getY());
        double maxHorizontal = plugin.getConfig().getDouble("movement.horizontal-distance-per-tick", 1.25);
        double maxVertical = plugin.getConfig().getDouble("movement.vertical-distance-per-tick", 1.65);
        if (horizontal <= maxHorizontal && vertical <= maxVertical) return;

        int count = suspiciousMoves.merge(id, 1, Integer::sum);
        int required = plugin.getConfig().getInt("movement.alerts-to-notify", 3);
        if (count < required) return;
        long now = System.currentTimeMillis();
        long cooldown = plugin.getConfig().getLong("movement.alert-cooldown-seconds", 12) * 1000L;
        if (now - lastAlert.getOrDefault(id, 0L) < cooldown) return;
        suspiciousMoves.put(id, 0);
        alert(player, String.format("horizontal=%.2f, vertical=%.2f", horizontal, vertical), horizontal, vertical);
    }

    private void alert(Player player, String reason, double horizontal, double vertical) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        long cooldown = plugin.getConfig().getLong("movement.alert-cooldown-seconds", 12) * 1000L;
        if (now - lastAlert.getOrDefault(id, 0L) < cooldown) return;
        lastAlert.put(id, now);
        String detail = reason;
        plugin.getLogger().warning("Movement alert for " + player.getName() + " (" + detail + "). Review manually; this can be a false positive.");
        for (Player staff : player.getServer().getOnlinePlayers()) {
            if (staff.hasPermission("minecraftguard.alerts")) staff.sendMessage("§c[Movement alert] §f" + player.getName() + " §7(" + detail + ") — review manually");
        }
    }
}
