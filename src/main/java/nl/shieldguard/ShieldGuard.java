package nl.shieldguard;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import nl.shieldguard.movement.ActionGuard;
import nl.shieldguard.movement.CombatGuard;
import nl.shieldguard.protection.MaskManager;
import nl.shieldguard.protection.ChunkPacketListener;
import nl.shieldguard.protection.Revealer;
import nl.shieldguard.integration.GrimIntegration;
import nl.shieldguard.reports.ReportManager;
import nl.shieldguard.reports.ReportsCommand;
import nl.shieldguard.reports.ReportCommand;
import nl.shieldguard.reports.ReportsGui;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class ShieldGuard extends JavaPlugin {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private MaskManager maskManager;
    private Revealer revealer;
    private CombatGuard combatGuard;
    private ActionGuard actionGuard;
    private GrimIntegration grimIntegration;
    private ReportManager reportManager;
    private ReportsGui reportsGui;
    private PacketListenerCommon packetListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        maskManager = new MaskManager(this);
        getServer().getPluginManager().registerEvents(maskManager, this);
        maskManager.start();

        revealer = new Revealer(this, maskManager);
        getServer().getPluginManager().registerEvents(revealer, this);
        revealer.start();

        // GrimAC is the authoritative movement checker. Do not run ShieldGuard's older
        // PlayerMoveEvent setbacks alongside it; they can correct legitimate jumps.

        combatGuard = new CombatGuard(this);
        getServer().getPluginManager().registerEvents(combatGuard, this);

        actionGuard = new ActionGuard(this);
        getServer().getPluginManager().registerEvents(actionGuard, this);

        grimIntegration = new GrimIntegration(this);
        grimIntegration.register();

        packetListener = new ChunkPacketListener(maskManager).asAbstract(PacketListenerPriority.HIGH);
        PacketEvents.getAPI().getEventManager().registerListener(packetListener);

        reportManager = new ReportManager(this);
        reportsGui = new ReportsGui(this, reportManager);
        getServer().getPluginManager().registerEvents(reportsGui, this);

        PluginCommand admin = getCommand("shieldguard");
        if (admin != null) {
            AdminCommand ac = new AdminCommand(this, maskManager);
            admin.setExecutor(ac);
            admin.setTabCompleter(ac);
        }
        maskManager.logStartup();

        PluginCommand report = getCommand("report");
        if (report != null) {
            ReportCommand rc = new ReportCommand(this, reportManager);
            report.setExecutor(rc);
            report.setTabCompleter(rc);
        }
        PluginCommand reports = getCommand("reports");
        if (reports != null) {
            ReportsCommand rc = new ReportsCommand(this, reportManager, reportsGui);
            reports.setExecutor(rc);
            reports.setTabCompleter(rc);
        }

        getLogger().info("ShieldGuard actief.");
    }

    @Override
    public void onDisable() {
        if (packetListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
        }
        if (grimIntegration != null) {
            grimIntegration.unregister();
        }
        if (revealer != null) {
            revealer.stop();
        }
        if (maskManager != null) {
            maskManager.stop();
        }
        if (reportManager != null) {
            reportManager.save();
        }
    }

    public void reload() {
        reloadConfig();
        maskManager.reloadSettings();
        combatGuard.reload();
        actionGuard.reload();
        grimIntegration.reload();
    }

    /** Bericht uit config.yml als Component (MiniMessage), inclusief prefix. */
    public Component msg(String key, TagResolver... resolvers) {
        String prefix = getConfig().getString("messages.prefix", "");
        String raw = getConfig().getString("messages." + key, "<red>Ontbrekend bericht: " + key + "</red>");
        return MM.deserialize(prefix + raw, resolvers);
    }

    /** Bericht zonder prefix. */
    public Component rawMsg(String key, TagResolver... resolvers) {
        String raw = getConfig().getString("messages." + key, key);
        return MM.deserialize(raw, resolvers);
    }
}
