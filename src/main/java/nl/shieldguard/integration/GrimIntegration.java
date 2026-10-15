package nl.shieldguard.integration;

import ac.grim.grimac.api.GrimAPIProvider;
import ac.grim.grimac.api.event.EventBus;
import ac.grim.grimac.api.event.events.FlagEvent;
import ac.grim.grimac.api.plugin.GrimPlugin;
import nl.shieldguard.ShieldGuard;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Forwards GrimAC detections to ShieldGuard staff alerts; Grim retains control of punishment. */
public final class GrimIntegration {
    private final ShieldGuard plugin;
    private final Map<String, Long> lastAlertByPlayer = new ConcurrentHashMap<>();
    private GrimPlugin owner;
    private EventBus eventBus;
    private long cooldownMs;

    public GrimIntegration(ShieldGuard plugin) {
        this.plugin = plugin;
    }

    public void register() {
        reload();
        var api = GrimAPIProvider.get();
        owner = api.getGrimPlugin(plugin);
        eventBus = api.getEventBus();
        eventBus.get(FlagEvent.class).onFlag(owner, (user, check, verbose, cancelled) -> {
            String key = user.getName().toLowerCase(Locale.ROOT);
            long now = System.currentTimeMillis();
            AtomicBoolean sendAlert = new AtomicBoolean();
            lastAlertByPlayer.compute(key, (ignored, previous) -> {
                if (previous == null || now - previous >= cooldownMs) {
                    sendAlert.set(true);
                    return now;
                }
                return previous;
            });
            if (sendAlert.get()) {
                String playerName = user.getName();
                String checkName = check.getCheckName();
                String details = verbose == null ? "" : verbose.replace('\n', ' ').replace('\r', ' ');
                Bukkit.getScheduler().runTask(plugin, () -> alertStaff(playerName, checkName, details));
            }
            return cancelled; // observe only; never cancel Grim's own response
        });
        plugin.getLogger().info("GrimAC-integratie actief: Grim-vlaggen worden aan ShieldGuard-staff gemeld.");
    }

    public void reload() {
        cooldownMs = Math.max(1000L,
                plugin.getConfig().getLong("grim-integration.alert-cooldown-seconds", 5) * 1000L);
    }

    private void alertStaff(String playerName, String checkName, String details) {
        String message = "[ShieldGuard/Grim] " + playerName + " verdacht volgens " + checkName
                + (details.isBlank() ? "" : ": " + details);
        plugin.getLogger().warning(message);
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("shieldguard.alerts")) staff.sendMessage(Component.text(message));
        }
    }

    public void unregister() {
        if (eventBus != null && owner != null) eventBus.unregisterAllListeners(owner);
        lastAlertByPlayer.clear();
    }
}
