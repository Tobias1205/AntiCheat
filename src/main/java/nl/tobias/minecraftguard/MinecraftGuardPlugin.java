package nl.tobias.minecraftguard;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class MinecraftGuardPlugin extends JavaPlugin {
    private ReportMenu reportMenu;
    private MovementMonitor movementMonitor;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reportMenu = new ReportMenu(this);
        movementMonitor = new MovementMonitor(this);
        Bukkit.getPluginManager().registerEvents(reportMenu, this);
        Bukkit.getPluginManager().registerEvents(movementMonitor, this);
        getCommand("report").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof org.bukkit.entity.Player player)) {
                sender.sendMessage("This command can only be used in game.");
                return true;
            }
            reportMenu.open(player);
            return true;
        });
        getCommand("guard").setExecutor((sender, command, label, args) -> {
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                sender.sendMessage("§aMinecraftGuard configuration reloaded.");
            } else if (args.length == 1 && args[0].equalsIgnoreCase("alerts")) {
                sender.sendMessage("§eMovement alerts are advisory. Review each case before taking action.");
            } else {
                sender.sendMessage("§eUsage: /guard alerts|reload");
            }
            return true;
        });
        getLogger().info("MinecraftGuard enabled. Movement detections are advisory only.");
    }
}
