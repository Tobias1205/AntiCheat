package nl.shieldguard.protection;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import nl.shieldguard.ShieldGuard;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Houdt per speler bij waar hij is en welke "diepte-grens" hij nodig heeft,
 * stuurt chunks opnieuw als die grens verschuift, en bewaart de instellingen.
 */
public final class MaskManager implements Listener {

    public static final int NO_MASK = Integer.MIN_VALUE;

    /** Status per speler. Wordt vanuit de main thread geschreven en vanuit de netty-thread gelezen. */
    public static final class State {
        public volatile String world = "";
        public volatile int minY = -64;
        public volatile int x = 0;
        public volatile int y = 64;
        public volatile int z = 0;
        public volatile boolean enabled;
        public volatile boolean bypass;
        /** chunkKey -> cutoff waarmee die chunk is verstuurd (NO_MASK = niet gemaskeerd) */
        public final Map<Long, Integer> sent = new ConcurrentHashMap<>();
        /** Blokposities (zie MaskManager#pos) die deze speler echt mag zien: erts/storage dat dichtbij is en in zicht. */
        public final Map<Long, Boolean> revealed = new ConcurrentHashMap<>();
        // Alleen main thread:
        public final Set<UUID> shownEntities = new HashSet<>();
        public long lastBlock = Long.MIN_VALUE;
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
    private volatile boolean storageEnabled;
    private volatile boolean hideExposedOres;
    private volatile boolean hideEntities;
    private volatile int depth;
    private volatile int surfaceMargin;
    private volatile int radius;
    private volatile int perRun;
    private volatile int remaskGap;
    private volatile int interval;
    private volatile int oreRadius;
    private volatile int storageRadius;
    private volatile Set<String> oreNames = Set.of();
    private volatile Set<String> storageNames = Set.of();
    private volatile Set<Material> oreMaterials = Set.of();
    private volatile Set<Material> storageMaterials = Set.of();
    private volatile String bypassPermission;

    public MaskManager(ShieldGuard plugin) {
        this.plugin = plugin;
        reloadSettings();
    }

    public void reloadSettings() {
        var cfg = plugin.getConfig();
        Set<String> w = new HashSet<>(cfg.getStringList("worlds"));
        worlds = w;

        freecamEnabled = cfg.getBoolean("freecam-protection.enabled", true);
        depth = Math.max(16, cfg.getInt("freecam-protection.depth-below-player", 32));
        surfaceMargin = Math.max(0, cfg.getInt("freecam-protection.surface-margin", 12));
        radius = Math.max(1, cfg.getInt("freecam-protection.refresh-radius-chunks", 8));
        perRun = Math.max(1, cfg.getInt("freecam-protection.chunks-per-run", 6));
        remaskGap = Math.max(16, cfg.getInt("freecam-protection.remask-gap-blocks", 32));
        interval = Math.max(1, cfg.getInt("freecam-protection.refresh-interval-ticks", 10));

        xrayEnabled = cfg.getBoolean("xray-protection.enabled", true);
        hideExposedOres = cfg.getBoolean("xray-protection.hide-exposed-ores", true);
        oreRadius = Math.max(0, cfg.getInt("xray-protection.reveal-radius", 6));

        storageEnabled = cfg.getBoolean("storage-protection.enabled", true);
        hideEntities = storageEnabled && cfg.getBoolean("storage-protection.hide-entities", true);
        storageRadius = Math.max(0, cfg.getInt("storage-protection.reveal-radius", 10));

        List<String> ores = xrayEnabled ? cfg.getStringList("xray-protection.hidden-blocks") : List.of();
        List<String> storage = storageEnabled ? cfg.getStringList("storage-protection.hidden-blocks") : List.of();
        oreNames = names(ores);
        storageNames = names(storage);
        oreMaterials = materials(ores);
        storageMaterials = materials(storage);

        bypassPermission = cfg.getString("bypass-permission", "shieldguard.bypass");
        idCache.clear();
        // Speler-status opnieuw bepalen
        for (Player p : Bukkit.getOnlinePlayers()) {
            init(p, p.getLocation());
        }
    }

    private static Set<String> names(List<String> list) {
        Set<String> out = new HashSet<>();
        for (String s : list) out.add(normalize(s));
        return out;
    }

    private Set<Material> materials(List<String> list) {
        Set<Material> out = EnumSet.noneOf(Material.class);
        for (String s : list) {
            Material m = Material.matchMaterial(normalize(s));
            if (m != null) {
                out.add(m);
            } else {
                plugin.getLogger().warning("Onbekend blok in config: " + s);
            }
        }
        return out;
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

    /** Zelfde codering als vanilla BlockPos#asLong. */
    public static long pos(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (long) (y & 0xFFF);
    }

    public static int posX(long p) {
        return (int) (p >> 38);
    }

    public static int posY(long p) {
        return (int) (p << 52 >> 52);
    }

    public static int posZ(long p) {
        return (int) (p << 26 >> 38);
    }

    public static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    public State state(UUID uuid) {
        return uuid == null ? null : states.get(uuid);
    }

    public Ids ids(ClientVersion version) {
        return idCache.computeIfAbsent(version, v -> new Ids(v, oreNames, storageNames));
    }

    public boolean hideExposedOres() {
        return hideExposedOres;
    }

    public boolean hideEntities() {
        return hideEntities;
    }

    public int surfaceMargin() {
        return surfaceMargin;
    }

    public int oreRadius() {
        return oreRadius;
    }

    public int storageRadius() {
        return storageRadius;
    }

    public Set<Material> oreMaterials() {
        return oreMaterials;
    }

    public Set<Material> storageMaterials() {
        return storageMaterials;
    }

    /** Is er voor deze speler iets te verbergen (erts en/of storage)? */
    public boolean blocksHidden(State st) {
        return st.enabled && !st.bypass && (!oreMaterials.isEmpty() || !storageMaterials.isEmpty());
    }

    public boolean active(State st) {
        return st != null && st.enabled && !st.bypass;
    }

    /** Grens (wereld-Y) waaronder het diepte-masker geldt, of NO_MASK. */
    public int cutoff(State st) {
        if (!freecamEnabled || !st.enabled || st.bypass) return NO_MASK;
        int c = Math.floorDiv(st.y - depth, 16) * 16;
        if (c <= st.minY) return NO_MASK;
        return c;
    }

    // ------------------------------------------------------------ diagnose

    public final AtomicLong chunksSeen = new AtomicLong();
    public final AtomicLong chunksModified = new AtomicLong();
    public final AtomicLong blocksFilled = new AtomicLong();
    public final AtomicLong oresHidden = new AtomicLong();
    public final AtomicLong storageHidden = new AtomicLong();
    public final AtomicLong errors = new AtomicLong();
    private volatile String lastError = "-";

    /** Fouten in de packet-code zichtbaar maken (PacketEvents slikt ze anders stil in). */
    public void reportError(String where, Throwable t) {
        long n = errors.incrementAndGet();
        lastError = where + ": " + t;
        if (n <= 5) {
            plugin.getLogger().log(Level.SEVERE,
                    "Fout in " + where + " - de bescherming werkt hierdoor NIET voor dit packet", t);
        }
    }

    public void logStartup() {
        StringBuilder existing = new StringBuilder();
        for (World w : Bukkit.getWorlds()) {
            if (existing.length() > 0) existing.append(", ");
            existing.append(w.getName());
        }
        plugin.getLogger().info("Beschermde werelden (config): " + worlds + " | bestaande werelden: " + existing);
        for (String name : worlds) {
            if (Bukkit.getWorld(name) == null) {
                plugin.getLogger().warning("Wereld '" + name + "' uit config.yml bestaat niet!");
            }
        }
        plugin.getLogger().info("Verborgen: " + oreMaterials.size() + " soorten erts, "
                + storageMaterials.size() + " soorten storage. Freecam-masker aan: " + freecamEnabled);
        plugin.getLogger().info("Let op: spelers met permission '" + bypassPermission
                + "' (standaard alle OPs) zien alles ongemaskeerd.");
    }

    /** Regels voor /shieldguard status. */
    public List<String> describe(Player p) {
        List<String> out = new ArrayList<>();
        State st = states.get(p.getUniqueId());
        boolean bypass = p.hasPermission(bypassPermission);
        out.add("Jouw wereld: " + p.getWorld().getName() + " | beschermd volgens config: "
                + worlds.contains(p.getWorld().getName()));
        out.add("Bypass-permission (" + bypassPermission + "): " + bypass
                + (bypass ? "  <-- DAAROM ZIE JIJ ALLES. Haal de permission weg of deop jezelf om te testen." : ""));
        if (st == null) {
            out.add("Geen speler-status gevonden (relog).");
        } else {
            int c = cutoff(st);
            out.add("Jouw Y: " + st.y + " | actief voor jou: " + (st.enabled && !st.bypass)
                    + " | freecam-grens: " + (c == NO_MASK ? "geen" : "Y<" + c)
                    + " | onthulde blokken: " + st.revealed.size() + " | chunks onthouden: " + st.sent.size());
        }
        out.add("Instellingen: freecam=" + freecamEnabled + " (diepte " + depth + ", marge " + surfaceMargin
                + "), erts=" + oreMaterials.size() + " soorten (radius " + oreRadius
                + "), storage=" + storageMaterials.size() + " soorten (radius " + storageRadius + ")");
        out.add("Packets sinds start: chunks gezien=" + chunksSeen.get() + ", aangepast=" + chunksModified.get()
                + ", blokken gevuld=" + blocksFilled.get() + ", erts verborgen=" + oresHidden.get()
                + ", storage verborgen=" + storageHidden.get());
        out.add("Fouten in packet-code: " + errors.get() + " | laatste: " + lastError);
        return out;
    }

    // ---------------------------------------------------------------- events

    private void init(Player p, Location loc) {
        State st = states.computeIfAbsent(p.getUniqueId(), u -> new State());
        World w = loc.getWorld();
        boolean worldChanged = !w.getName().equals(st.world);
        st.world = w.getName();
        st.minY = w.getMinHeight();
        st.x = loc.getBlockX();
        st.y = loc.getBlockY();
        st.z = loc.getBlockZ();
        st.enabled = worlds.contains(w.getName());
        st.bypass = p.hasPermission(bypassPermission);
        if (worldChanged) {
            st.sent.clear();
            st.revealed.clear();
            st.queue.clear();
            st.queued.clear();
            st.lastScanSignature = Long.MIN_VALUE;
            st.lastBlock = Long.MIN_VALUE;
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
        st.x = to.getBlockX();
        st.y = to.getBlockY();
        st.z = to.getBlockZ();
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
