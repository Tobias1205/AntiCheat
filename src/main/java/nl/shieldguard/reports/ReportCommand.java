package nl.shieldguard.reports;

import nl.shieldguard.ShieldGuard;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** /report <speler> <reden> */
public final class ReportCommand implements CommandExecutor, TabCompleter {

    private final ShieldGuard plugin;
    private final ReportManager reports;

    public ReportCommand(ShieldGuard plugin, ReportManager reports) {
        this.plugin = plugin;
        this.reports = reports;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.msg("players-only"));
            return true;
        }
        if (!player.hasPermission("shieldguard.report")) {
            player.sendMessage(plugin.msg("no-permission"));
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(plugin.msg("report-usage"));
            return true;
        }

        String targetName = args[0];
        OfflinePlayer target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(targetName);
            if (off != null && off.hasPlayedBefore()) target = off;
        }
        if (target == null) {
            player.sendMessage(plugin.msg("report-unknown-player"));
            return true;
        }
        String shownName = target.getName() != null ? target.getName() : targetName;

        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(plugin.msg("report-self"));
            return true;
        }

        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        int min = plugin.getConfig().getInt("reports.min-reason-length", 4);
        if (reason.length() < min) {
            player.sendMessage(plugin.msg("report-reason-short"));
            return true;
        }
        if (reason.length() > 200) reason = reason.substring(0, 200);

        long left = reports.cooldownLeft(player.getUniqueId());
        if (left > 0) {
            player.sendMessage(plugin.msg("report-cooldown",
                    Placeholder.unparsed("seconds", String.valueOf(left))));
            return true;
        }
        if (reports.hasOpenFrom(player.getUniqueId(), target.getUniqueId())) {
            player.sendMessage(plugin.msg("report-duplicate", Placeholder.unparsed("target", shownName)));
            return true;
        }

        Report r = reports.create(player, target.getUniqueId(), shownName, reason);
        player.sendMessage(plugin.msg("report-sent",
                Placeholder.unparsed("target", shownName),
                Placeholder.unparsed("id", String.valueOf(r.id))));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(p.getName());
            }
        } else if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            for (String s : plugin.getConfig().getStringList("reports.suggested-reasons")) {
                if (s.toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(s);
            }
        }
        return out;
    }
}
