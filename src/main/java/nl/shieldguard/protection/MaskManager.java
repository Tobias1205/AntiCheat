package nl.shieldguard.protection;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import nl.shieldguard.ShieldGuard;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Houdt per speler bij waar hij is en welke "diepte-grens" hij nodig heeft,
 * en stuurt chunks opnieuw als die grens verschuift.
 */
public final class MaskManager implements Listener {

    public static final int NO_MASK = Integer.MIN_VALUE;

    /** Status per speler. Wordt vanuit de main thread geschreven en vanuit de netty-thread gelezen. */
    public static final class State {
        public volatile String world = "";
        public volatile int minY = -64;
        public volatile int y = 64;
        public volatile boolean enabled;
        public volatile boolean bypass;
        /** chunkKey -> cutoff waarmee die chunk is verstuurd (NO_MASK = niet gemaskeerd) */
        public final Map<Long, Integer> sent = new ConcurrentHashMap<>();
        // Alleen main thread:
        final ArrayDeque<Long> queue = new ArrayDeque<>();
        final Set<Long> queued = new HashSet<>();
        long lastScanSignature = Long.MIN_VALUE;
    }

    private final ShieldGuard plugin;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final Map<ClientVersion, Ids> idCache = new ConcurrentHashMap<>();

    private BukkitTask task;

    // Instellingen (volatile: worden bij reload vervangen)
    private volatile Set<String> worlds = Set.of();
    private volatile boolean freecamEnabled;
    private volatile boolean xrayEnabled;
    private volatile int depth;
    private volatile int radius;
    private volatile int perRun;
    private volatile int remaskGap;
    private volatile int interval;
    private volatile Set<String> hiddenNames = Set.of();
    private volatile String bypassPermission;

    public MaskManager(ShieldGuard plugin) {
        this.plugin = plugin;
        reloadSettings();
    }

    public void reloadSettings() {
        var cfg = plugin.getConfig();
        Set<String> w = new HashSet<>();
        for (String s : cfg.getStringList("worlds")) w.add(s);
        worlds = w;
        freecamEnabled = cfg.getBoolean("freecam-protection.enabled", true);
        xrayEnabled = cfg.getBoolean("xray-protection.enabled", true);
        depth = Math.max(16, cfg.getInt("freecam-protection.depth-below-player", 32));
        radius = Math.max(1, cfg.getInt("freecam-protection.refresh-radius-chunks", 8));
        perRun = Math.max(1, cfg.getInt("freecam-protection.chunks-per-run", 6));
        remaskGap = Math.max(16, cfg.getInt("freecam-protection.remask-gap-blocks", 32));
        interval = Math.max(1, cfg.getInt("freecam-protection.refresh-interval-ticks", 10));
        Set<String> hidden = new HashSet<>();
        for (String s : cfg.getStringList("xray-protection.hidden-blocks")) {
            hidden.add(normalize(s));
        }
        hiddenNames = hidden;
        bypassPermission = cfg.getString("bypass-permission", "shieldguard.bypass");
        idCache.clear();
        // Speler-status opnieuw bepalen
        for (Player p : Bukkit.getOnlinePlayers()) {
            init(p, p.getLocation());
        }
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, interval);
    }

    public void stop() {
        if (task != null) task.cancel();
        states.clear();
    }

    public static String normalize(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("minecraft:") ? n.substring(10) : n;
    }

    public State state(UUID uuid) {
        return uuid == null ? null : states.get(uuid);
    }

    public boolean xrayEnabled() {
        return xrayEnabled;
    }

    public Set<String> hiddenNames() {
        return hiddenNames;
    }

    public Ids ids(ClientVersion version) {
        return idCache.computeIfAbsent(version, v -> new Ids(v, hiddenNames));
    }

    /** Grens (wereld-Y) waaronder alles gemaskeerd wordt, of NO_MASK. */
    public int cutoff(State st) {
        if (!freecamEnabled || !st.enabled || st.bypass) return NO_MASK;
        int c = Math.floorDiv(st.y - depth, 16) * 16;
        if (c <= st.minY) return NO_MASK;
        return c;
    }

    public boolean oresHidden(State st) {
        return xrayEnabled && st.enabled && !st.bypass;
    }

    public static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    // ---------------------------------------------------------------- events

    private void init(Player p, Location loc) {
        State st = states.computeIfAbsent(p.getUniqueId(), u -> new State());
        World w = loc.getWorld();
        boolean worldChanged = !w.getName().equals(st.world);
        st.world = w.getName();
        st.minY = w.getMinHeight();
        st.y = loc.getBlockY();
        st.enabled = worlds.contains(w.getName());
        st.bypass = p.hasPermission(bypassPermission);
        if (worldChanged) {
            st.sent.clear();
            st.queue.clear();
            st.queued.clear();
            st.lastScanSignature = Long.MIN_VALUE;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        init(e.getPlayer(), e.getPlayer().getLocation());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        states.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location to = e.getTo();
        State st = states.get(e.getPlayer().getUniqueId());
        if (st == null) {
            init(e.getPlayer(), to);
            return;
        }
        if (!to.getWorld().getName().equals(st.world)) {
            init(e.getPlayer(), to); // ook bij teleports (PlayerTeleportEvent is een PlayerMoveEvent)
            return;
        }
        st.y = to.getBlockY();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        init(e.getPlayer(), e.getRespawnLocation());
    }

    // ------------------------------------------------------- refresh-routine

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            State st = states.get(p.getUniqueId());
            if (st == null || !st.enabled || st.bypass) continue;
            int needed = cutoff(st);
            Location loc = p.getLocation();
            int pcx = loc.getBlockX() >> 4;
            int pcz = loc.getBlockZ() >> 4;

            long signature = ((long) needed << 40) ^ ((long) pcx << 20) ^ (pcz & 0xFFFFFL);
            if (st.queue.isEmpty() && signature != st.lastScanSignature) {
                st.lastScanSignature = signature;
                scan(st, needed, pcx, pcz);
            }

            World world = p.getWorld();
            int done = 0;
            while (done < perRun && !st.queue.isEmpty()) {
                long key = st.queue.poll();
                st.queued.remove(key);
                int cx = (int) (key >> 32);
                int cz = (int) key;
                if (world.isChunkLoaded(cx, cz)) {
                    //noinspection deprecation
                    world.refreshChunk(cx, cz);
                    done++;
                }
            }
        }
    }

    private void scan(State st, int needed, int pcx, int pcz) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                long key = key(pcx + dx, pcz + dz);
                Integer sent = st.sent.get(key);
                if (sent == null) continue;
                boolean unmaskNeeded = sent > needed;
                boolean remaskNeeded = needed != NO_MASK && ((long) needed - (long) sent) >= remaskGap;
                if ((unmaskNeeded || remaskNeeded) && st.queued.add(key)) {
                    st.queue.add(key);
                }
            }
        }
    }
}
