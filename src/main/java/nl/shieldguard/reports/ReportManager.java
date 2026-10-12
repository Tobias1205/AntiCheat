package nl.shieldguard.reports;

import nl.shieldguard.ShieldGuard;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ReportManager {

    private final ShieldGuard plugin;
    private final File file;
    private final Map<Integer, Report> reports = new LinkedHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private int nextId = 1;

    public ReportManager(ShieldGuard plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "reports.yml");
        load();
    }

    // ------------------------------------------------------------- opslag

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        nextId = yaml.getInt("next-id", 1);
        ConfigurationSection section = yaml.getConfigurationSection("reports");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(key);
            if (s == null) continue;
            try {
                Report r = new Report(
                        Integer.parseInt(key),
                        UUID.fromString(s.getString("reporter", "")),
                        s.getString("reporter-name", "?"),
                        UUID.fromString(s.getString("target", "")),
                        s.getString("target-name", "?"),
                        s.getString("reason", ""),
                        s.getLong("time"),
                        s.getString("world", ""),
                        s.getDouble("x"), s.getDouble("y"), s.getDouble("z"));
                r.closed = s.getBoolean("closed");
                r.handledBy = s.getString("handled-by", "");
                r.note = s.getString("note", "");
                reports.put(r.id, r);
                nextId = Math.max(nextId, r.id + 1);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Ongeldig report " + key + " overgeslagen.");
            }
        }
    }

    public synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("next-id", nextId);
        for (Report r : reports.values()) {
            String p = "reports." + r.id + ".";
            yaml.set(p + "reporter", r.reporter.toString());
            yaml.set(p + "reporter-name", r.reporterName);
            yaml.set(p + "target", r.target.toString());
            yaml.set(p + "target-name", r.targetName);
            yaml.set(p + "reason", r.reason);
            yaml.set(p + "time", r.time);
            yaml.set(p + "world", r.world);
            yaml.set(p + "x", r.x);
            yaml.set(p + "y", r.y);
            yaml.set(p + "z", r.z);
            yaml.set(p + "closed", r.closed);
            yaml.set(p + "handled-by", r.handledBy);
            yaml.set(p + "note", r.note);
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Kon reports.yml niet opslaan: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------ queries

    public synchronized Report get(int id) {
        return reports.get(id);
    }

    public synchronized List<Report> open() {
        List<Report> list = new ArrayList<>();
        for (Report r : reports.values()) if (!r.closed) list.add(r);
        list.sort(Comparator.comparingLong((Report r) -> r.time).reversed());
        return list;
    }

    public synchronized int openAgainst(UUID target) {
        int n = 0;
        for (Report r : reports.values()) if (!r.closed && r.target.equals(target)) n++;
        return n;
    }

    public synchronized boolean hasOpenFrom(UUID reporter, UUID target) {
        for (Report r : reports.values()) {
            if (!r.closed && r.reporter.equals(reporter) && r.target.equals(target)) return true;
        }
        return false;
    }

    /** Resterende cooldown in seconden, 0 als de speler mag reporten. */
    public long cooldownLeft(UUID reporter) {
        Long until = cooldowns.get(reporter);
        if (until == null) return 0;
        long left = (until - System.currentTimeMillis() + 999) / 1000;
        return Math.max(0, left);
    }

    // ------------------------------------------------------------- acties

    public Report create(Player reporter, UUID targetUuid, String targetName, String reason) {
        Location loc = reporter.getLocation();
        Report r;
        synchronized (this) {
            Player online = Bukkit.getPlayer(targetUuid);
            Location where = online != null ? online.getLocation() : loc;
            r = new Report(nextId++, reporter.getUniqueId(), reporter.getName(), targetUuid, targetName,
                    reason, System.currentTimeMillis(), where.getWorld().getName(),
                    where.getX(), where.getY(), where.getZ());
            reports.put(r.id, r);
        }
        long cooldown = plugin.getConfig().getLong("reports.cooldown-seconds", 60);
        cooldowns.put(reporter.getUniqueId(), System.currentTimeMillis() + cooldown * 1000);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::save);
        notifyStaff(r);
        sendWebhook(r);
        return r;
    }

    public synchronized boolean close(int id, String by, String note) {
        Report r = reports.get(id);
        if (r == null || r.closed) return false;
        r.closed = true;
        r.handledBy = by;
        r.note = note;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::save);
        return true;
    }

    private void notifyStaff(Report r) {
        Component line = plugin.msg("staff-new-report",
                        Placeholder.unparsed("id", String.valueOf(r.id)),
                        Placeholder.unparsed("reporter", r.reporterName),
                        Placeholder.unparsed("target", r.targetName),
                        Placeholder.unparsed("reason", r.reason))
                .clickEvent(ClickEvent.runCommand("/reports tp " + r.id))
                .hoverEvent(HoverEvent.showText(Component.text("Klik om naar " + r.targetName + " te teleporteren")));

        int count = openAgainst(r.target);
        int threshold = plugin.getConfig().getInt("reports.alert-threshold", 3);

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.hasPermission("shieldguard.reports.notify")) continue;
            p.sendMessage(line);
            if (count >= threshold) {
                p.sendMessage(plugin.msg("staff-alert",
                        Placeholder.unparsed("target", r.targetName),
                        Placeholder.unparsed("count", String.valueOf(count))));
            }
        }
    }

    private void sendWebhook(Report r) {
        String url = plugin.getConfig().getString("reports.discord-webhook-url", "");
        if (url == null || url.isBlank()) return;
        String text = "**Report #" + r.id + "** — " + r.reporterName + " meldt " + r.targetName
                + ": " + r.reason;
        String json = "{\"content\":\"" + escape(text) + "\"}";
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                c.setDoOutput(true);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                }
                c.getResponseCode();
                c.disconnect();
            } catch (Exception ex) {
                plugin.getLogger().warning("Discord webhook mislukt: " + ex.getMessage());
            }
        });
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> { }
                default -> sb.append(ch < 0x20 ? ' ' : ch);
            }
        }
        return sb.toString();
    }
}
