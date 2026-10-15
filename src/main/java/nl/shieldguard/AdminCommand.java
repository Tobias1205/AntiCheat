package nl.shieldguard;

import nl.shieldguard.protection.MaskManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

/** /shieldguard status | reload | vl <speler> */
public final class AdminCommand implements CommandExecutor, TabCompleter {

    private final ShieldGuard plugin;
    private final MaskManager masks;
    public AdminCommand(ShieldGuard plugin, MaskManager masks) {
        this.plugin = plugin;
        this.masks = masks;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("shieldguard.admin")) {
            sender.sendMessage(plugin.msg("no-permission"));
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();
        if (sub.equals("reload")) {
            plugin.reload();
            sender.sendMessage(plugin.msg("reload-done"));
            return true;
        }
        if (sub.equals("vl")) {
            Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : null;
            if (target == null) {
                sender.sendMessage(Component.text("Gebruik: /shieldguard vl <speler> (speler moet online zijn)", NamedTextColor.RED));
                return true;
            }
            sender.sendMessage(Component.text("Bewegingschecks lopen via GrimAC. Bekijk GrimAC-alerts en /grim history voor "
                    + target.getName() + ".", NamedTextColor.GRAY));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.msg("players-only"));
            return true;
        }
        player.sendMessage(Component.text("ShieldGuard status", NamedTextColor.GOLD));
        for (String line : masks.describe(player)) {
            player.sendMessage(Component.text(line, NamedTextColor.GRAY));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 ? List.of("status", "reload", "vl") : List.of();
    }
}
