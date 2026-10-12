package nl.shieldguard.protection;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import nl.shieldguard.ShieldGuard;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.ChestBoat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.entity.minecart.SpawnerMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Alle erts en storage is voor de client standaard stone/deepslate. Deze klasse toont het echte blok pas
 * als de speler er dichtbij is EN er een vrije zichtlijn naartoe bestaat. Hetzelfde geldt voor
 * minecarts met kisten/hoppers/spawners en chest-boats (die worden per speler verborgen).
 */
public final class Revealer implements Listener {

    private static final BlockFace[] FACES = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private final ShieldGuard plugin;
    private final MaskManager masks;
    private BukkitTask task;
    private int runCounter;

    public Revealer(ShieldGuard plugin, MaskManager masks) {
        this.plugin = plugin;
        this.masks = masks;
    }

    public void start() {
        int interval = Math.max(1, plugin.getConfig().getInt("reveal-interval-ticks", 5));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, interval);
    }

    public void stop() {
        if (task != null) task.cancel();
    }

    // ------------------------------------------------------- entities verbergen

    private static boolean isStorageEntity(Entity e) {
        return e instanceof StorageMinecart || e instanceof HopperMinecart
                || e instanceof SpawnerMinecart || e instanceof ChestBoat;
    }

    private static java.util.Collection<Entity> storageEntities(World w) {
        return w.getEntitiesByClasses(StorageMinecart.class, HopperMinecart.class,
                SpawnerMinecart.class, ChestBoat.class);
    }

    /** Nieuwe/geladen storage-entities meteen verbergen, nog voordat ze naar spelers gestuurd worden. */
    @EventHandler
    public void onEntityAdd(EntityAddToWorldEvent e) {
        if (!masks.hideEntities() || !isStorageEntity(e.getEntity())) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            MaskManager.State st = masks.state(p.getUniqueId());
            if (masks.active(st)) p.hideEntity(plugin, e.getEntity());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        if (!masks.hideEntities()) return;
        Player p = e.getPlayer();
        MaskManager.State st = masks.state(p.getUniqueId());
        if (!masks.active(st)) return;
        for (World w : Bukkit.getWorlds()) {
            for (Entity entity : storageEntities(w)) p.hideEntity(plugin, entity);
        }
    }

    // ----------------------------------------------------------------- tick

    private void tick() {
        runCounter++;
        boolean periodic = runCounter % 8 == 0;
        Map<World, java.util.Collection<Entity>> entityCache = new HashMap<>();

        for (Player p : Bukkit.getOnlinePlayers()) {
            MaskManager.State st = masks.state(p.getUniqueId());
            if (!masks.active(st)) continue;
            World w = p.getWorld();
            Location eye = p.getEyeLocation();

            if (masks.hideEntities()) {
                var entities = entityCache.computeIfAbsent(w, Revealer::storageEntities);
                updateEntities(p, st, eye, entities, periodic);
            }

            if (masks.blocksHidden(st)) {
                long cur = MaskManager.pos(p.getLocation().getBlockX(), p.getLocation().getBlockY(),
                        p.getLocation().getBlockZ());
                if (cur != st.lastBlock || periodic) {
                    st.lastBlock = cur;
                    scanBlocks(p, st, w, eye);
                    prune(st);
                }
            }
        }
    }

    // --------------------------------------------------------- entity logic

    private void updateEntities(Player p, MaskManager.State st, Location eye,
                                java.util.Collection<Entity> entities, boolean cleanup) {
        double r = masks.storageRadius();
        double r2 = r * r;
        Set<UUID> alive = cleanup ? new HashSet<>() : null;

        for (Entity e : entities) {
            if (alive != null) alive.add(e.getUniqueId());
            boolean should = false;
            if (r > 0) {
                Vector center = e.getBoundingBox().getCenter();
                if (center.distanceSquared(eye.toVector()) <= r2) {
                    should = rayTo(eye.getWorld(), eye, null, center.getX(), center.getY(), center.getZ());
                }
            }
            boolean shown = st.shownEntities.contains(e.getUniqueId());
            if (should && !shown) {
                p.showEntity(plugin, e);
                st.shownEntities.add(e.getUniqueId());
            } else if (!should) {
                if (shown) {
                    st.shownEntities.remove(e.getUniqueId());
                    p.hideEntity(plugin, e);
                } else if (p.canSee(e)) {
                    p.hideEntity(plugin, e);
                }
            }
        }
        if (alive != null) st.shownEntities.retainAll(alive);
    }

    // ---------------------------------------------------------- block logic

    private void scanBlocks(Player p, MaskManager.State st, World w, Location eye) {
        int bx = eye.getBlockX();
        int by = eye.getBlockY();
        int bz = eye.getBlockZ();

        // Erts: kubus rond de speler
        int r = masks.oreRadius();
        Set<Material> ores = masks.oreMaterials();
        if (r > 0 && !ores.isEmpty()) {
            int r2 = r * r;
            int minY = Math.max(w.getMinHeight(), by - r);
            int maxY = Math.min(w.getMaxHeight() - 1, by + r);
            for (int x = bx - r; x <= bx + r; x++) {
                for (int z = bz - r; z <= bz + r; z++) {
                    if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                    for (int y = minY; y <= maxY; y++) {
                        int dx = x - bx, dy = y - by, dz = z - bz;
                        if (dx * dx + dy * dy + dz * dz > r2) continue;
                        Block b = w.getBlockAt(x, y, z);
                        if (!ores.contains(b.getType())) continue;
                        tryReveal(p, st, w, eye, b);
                    }
                }
            }
        }

        // Storage: via de tile entities van de chunks eromheen
        int sr = masks.storageRadius();
        Set<Material> storage = masks.storageMaterials();
        if (sr > 0 && !storage.isEmpty()) {
            long sr2 = (long) sr * sr;
            int cr = (sr + 15) >> 4;
            for (int cx = (bx >> 4) - cr; cx <= (bx >> 4) + cr; cx++) {
                for (int cz = (bz >> 4) - cr; cz <= (bz >> 4) + cr; cz++) {
                    if (!w.isChunkLoaded(cx, cz)) continue;
                    Chunk chunk = w.getChunkAt(cx, cz);
                    for (BlockState bs : chunk.getTileEntities(false)) {
                        if (!storage.contains(bs.getType())) continue;
                        long dx = bs.getX() - bx, dy = bs.getY() - by, dz = bs.getZ() - bz;
                        if (dx * dx + dy * dy + dz * dz > sr2) continue;
                        tryReveal(p, st, w, eye, bs.getBlock());
                    }
                }
            }
        }
    }

    private void tryReveal(Player p, MaskManager.State st, World w, Location eye, Block b) {
        long key = MaskManager.pos(b.getX(), b.getY(), b.getZ());
        if (st.revealed.containsKey(key)) return;
        if (!hasOpenNeighbor(b)) return;      // goedkope voorfilter: ingesloten = nooit zichtbaar
        if (!visible(w, eye, b)) return;
        st.revealed.put(key, Boolean.TRUE);   // eerst markeren, anders maskeert de packet-listener het weer
        p.sendBlockChange(b.getLocation(), b.getBlockData());
    }

    private static boolean hasOpenNeighbor(Block b) {
        for (BlockFace f : FACES) {
            if (!b.getRelative(f).getType().isOccluding()) return true;
        }
        return false;
    }

    /** Zichtlijn naar het midden van het blok, of naar de drie vlakken die naar de speler gericht zijn. */
    private boolean visible(World w, Location eye, Block b) {
        double cx = b.getX() + 0.5, cy = b.getY() + 0.5, cz = b.getZ() + 0.5;
        if (rayTo(w, eye, b, cx, cy, cz)) return true;
        double sx = Math.signum(eye.getX() - cx);
        double sy = Math.signum(eye.getY() - cy);
        double sz = Math.signum(eye.getZ() - cz);
        if (sx != 0 && rayTo(w, eye, b, cx + sx * 0.49, cy, cz)) return true;
        if (sy != 0 && rayTo(w, eye, b, cx, cy + sy * 0.49, cz)) return true;
        return sz != 0 && rayTo(w, eye, b, cx, cy, cz + sz * 0.49);
    }

    /**
     * target != null: waar als de eerste geraakte blok het target is.
     * target == null (voor entities): waar als er geen blok tussen eye en punt zit.
     */
    private static boolean rayTo(World w, Location eye, Block target, double x, double y, double z) {
        Vector dir = new Vector(x - eye.getX(), y - eye.getY(), z - eye.getZ());
        double len = dir.length();
        if (len < 0.01) return true;
        dir.multiply(1.0 / len);
        if (target == null) {
            RayTraceResult hit = w.rayTraceBlocks(eye, dir, len, FluidCollisionMode.NEVER, true);
            return hit == null || hit.getHitBlock() == null;
        }
        RayTraceResult hit = w.rayTraceBlocks(eye, dir, len + 0.75, FluidCollisionMode.NEVER, true);
        return hit != null && target.equals(hit.getHitBlock());
    }

    /** Onthulde posities ver van de speler weer vergeten (zodat een herlaadde chunk ze weer verbergt). */
    private void prune(MaskManager.State st) {
        int keep = Math.max(masks.oreRadius(), masks.storageRadius()) + 12;
        long keep2 = (long) keep * keep;
        Iterator<Long> it = st.revealed.keySet().iterator();
        while (it.hasNext()) {
            long k = it.next();
            long dx = MaskManager.posX(k) - st.x;
            long dy = MaskManager.posY(k) - st.y;
            long dz = MaskManager.posZ(k) - st.z;
            if (dx * dx + dy * dy + dz * dz > keep2) it.remove();
        }
    }
}
