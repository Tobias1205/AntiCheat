package nl.shieldguard.reports;

import nl.shieldguard.ShieldGuard;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** /reports [list|view <id>|tp <id>|close <id> [notitie]|reload] — zonder argumenten opent de GUI. */
public final class ReportsCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB = List.of("gui", "list", "view", "tp", "close", "reload");

    private final ShieldGuard plugin;
    private final ReportManager reports;
    private final ReportsGui gui;

    public ReportsCommand(ShieldGuard plugin, ReportManager reports, ReportsGui gui) {
        this.plugin = plugin;
        this.reports = reports;
        this.gui = gui;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("shieldguard.reports.manage")) {
            sender.sendMessage(plugin.msg("no-permission"));
            return true;
        }

        String sub = args.length == 0 ? (sender instanceof Player ? "gui" : "list") : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "gui" -> {
                if (sender instanceof Player p) gui.open(p);
                else sender.sendMessage(plugin.msg("players-only"));
            }
            case "list" -> list(sender);
            case "view" -> {
                Report r = parse(sender, args);
                if (r != null) view(sender, r);
            }
            case "tp" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(plugin.msg("players-only"));
                    return true;
                }
                Report r = parse(sender, args);
                if (r != null) teleport(p, r);
            }
            case "close" -> {
                Report r = parse(sender, args);
                if (r == null) return true;
                String note = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "";
                if (reports.close(r.id, sender.getName(), note)) {
                    sender.sendMessage(plugin.msg("report-closed", Placeholder.unparsed("id", String.valueOf(r.id))));
                } else {
                    sender.sendMessage(plugin.msg("report-already-closed", Placeholder.unparsed("id", String.valueOf(r.id))));
                }
            }
            case "reload" -> {
                plugin.reload();
                sender.sendMessage(plugin.msg("reload-done"));
            }
            default -> sender.sendMessage(plugin.msg("report-usage"));
        }
        return true;
    }

    private Report parse(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.msg("report-not-found", Placeholder.unparsed("id", "?")));
            return null;
        }
        String raw = args[1].startsWith("#") ? args[1].substring(1) : args[1];
        try {
            int id = Integer.parseInt(raw);
            Report r = reports.get(id);
            if (r == null) {
                sender.sendMessage(plugin.msg("report-not-found", Placeholder.unparsed("id", raw)));
            }
            return r;
        } catch (NumberFormatException ex) {
            sender.sendMessage(plugin.msg("report-not-found", Placeholder.unparsed("id", raw)));
            return null;
        }
    }

    private void list(CommandSender sender) {
        List<Report> open = reports.open();
        if (open.isEmpty()) {
            sender.sendMessage(plugin.msg("reports-none"));
            return;
        }
        sender.sendMessage(plugin.rawMsg("reports-list-header",
                Placeholder.unparsed("count", String.valueOf(open.size()))));
        for (Report r : open) {
            sender.sendMessage(plugin.rawMsg("reports-list-entry",
                    Placeholder.unparsed("id", String.valueOf(r.id)),
                    Placeholder.unparsed("target", r.targetName),
                    Placeholder.unparsed("reporter", r.reporterName),
                    Placeholder.unparsed("reason", r.reason)));
        }
    }

    private void view(CommandSender sender, Report r) {
        String date = new SimpleDateFormat("dd-MM-yyyy HH:mm").format(new Date(r.time));
        sender.sendMessage(net.kyori.adventure.text.Component.text(
                "Report #" + r.id + (r.closed ? " (afgesloten door " + r.handledBy + ")" : " (open)")
                        + "\nSpeler: " + r.targetName
                        + "\nGemeld door: " + r.reporterName
                        + "\nReden: " + r.reason
                        + "\nTijd: " + date
                        + "\nPlek: " + r.world + " " + (int) r.x + " " + (int) r.y + " " + (int) r.z
                        + (r.note.isEmpty() ? "" : "\nNotitie: " + r.note)));
    }

    /** Teleporteert naar de gerapporteerde speler (online) of naar de plek van het report. */
    public void teleport(Player staff, Report r) {
        Player target = Bukkit.getPlayer(r.target);
        if (target != null) {
            staff.teleportAsync(target.getLocation());
            staff.sendMessage(plugin.msg("report-tp-done", Placeholder.unparsed("target", r.targetName)));
            return;
        }
        World world = Bukkit.getWorld(r.world);
        if (world == null) {
            staff.sendMessage(plugin.msg("report-not-found", Placeholder.unparsed("id", String.valueOf(r.id))));
            return;
        }
        staff.sendMessage(plugin.msg("report-tp-offline", Placeholder.unparsed("target", r.targetName)));
        staff.teleportAsync(new Location(world, r.x, r.y, r.z));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("shieldguard.reports.manage")) return out;
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String s : SUB) if (s.startsWith(prefix)) out.add(s);
        } else if (args.length == 2 && List.of("view", "tp", "close").contains(args[0].toLowerCase(Locale.ROOT))) {
            for (Report r : reports.open()) out.add(String.valueOf(r.id));
        }
        return out;
    }
}
