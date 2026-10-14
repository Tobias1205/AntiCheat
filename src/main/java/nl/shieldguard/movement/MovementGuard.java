package nl.shieldguard.movement;

import nl.shieldguard.ShieldGuard;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side movement checks: fly (stijgen/zweven/te langzaam vallen), speed en jesus.
 *
 * Werkt met strafpunten (VL): elke overtreding geeft VL, VL zakt vanzelf, vanaf {@code setback-vl}
 * wordt de speler teruggezet en vanaf {@code kick-vl} gekickt. Staff met shieldguard.alerts krijgt meldingen.
 * We vertrouwen NIET op de onGround-vlag van de client (die is te spoofen) maar kijken zelf naar de blokken.
 */
public final class MovementGuard implements Listener {

    /** Blokken waar normale beweging afwijkt (klimmen, stuiteren, glijden, stromen...). */
    private static final Set<Material> SPECIAL = EnumSet.of(
            Material.COBWEB, Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW, Material.HONEY_BLOCK,
            Material.SLIME_BLOCK, Material.SCAFFOLDING, Material.BUBBLE_COLUMN, Material.SOUL_SAND,
            Material.SOUL_SOIL, Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE, Material.FROSTED_ICE,
            Material.LADDER, Material.VINE, Material.TWISTING_VINES, Material.TWISTING_VINES_PLANT,
            Material.WEEPING_VINES, Material.WEEPING_VINES_PLANT, Material.CAVE_VINES,
            Material.CAVE_VINES_PLANT, Material.MOVING_PISTON, Material.PISTON_HEAD);

    private final ShieldGuard plugin;
    private final Map<UUID, Data> data = new ConcurrentHashMap<>();

    private boolean enabled, flyOn, jesusOn, speedOn;
    private Set<String> worlds = Set.of();
    private double setbackVl, kickVl, maxRise, glideFactor, groundLimit, airLimit, tolerance;
    private double maxSetbackDistance;
    private int hoverDurationMs, decaySeconds, checkpointIntervalMs;

    private static final class Data {
        Location lastGround;
        long exemptUntil;
        long specialUntil;
        long ignoreTeleportUntil;
        long lastMoveNanos;
        long lastFlagMs;
        long lastAlertMs;
        long lastDecayMs;
        long nonDescendingSinceNanos;
        long fallSampleNanos;
        long lastGroundUpdateMs;
        boolean leftGround;
        boolean canSetback;
        int airTicks, jesus, speedBuf, winAir;
        double groundY, fallSampleY, winDist;
        long winStart;
        final Map<String, Double> vl = new HashMap<>();
    }

    public MovementGuard(ShieldGuard plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        var c = plugin.getConfig();
        enabled = c.getBoolean("movement.enabled", true);
        worlds = new HashSet<>(c.getStringList("movement.worlds"));
        setbackVl = c.getDouble("movement.setback-vl", 3.0);
        kickVl = c.getDouble("movement.kick-vl", 30.0);
        maxSetbackDistance = Math.max(0.0, c.getDouble("movement.setback-max-distance", 4.0));
        checkpointIntervalMs = Math.max(50, c.getInt("movement.setback-checkpoint-interval-ms", 250));
        decaySeconds = Math.max(1, c.getInt("movement.vl-decay-seconds", 20));
        flyOn = c.getBoolean("movement.fly.enabled", true);
        maxRise = c.getDouble("movement.fly.max-rise", 1.45);
        hoverDurationMs = Math.max(500, c.getInt("movement.fly.hover-duration-ms", 1200));
        glideFactor = c.getDouble("movement.fly.glide-factor", 0.6);
        jesusOn = c.getBoolean("movement.jesus.enabled", true);
        speedOn = c.getBoolean("movement.speed.enabled", true);
        groundLimit = c.getDouble("movement.speed.ground-limit", 6.0);
        airLimit = c.getDouble("movement.speed.air-limit", 7.6);
        tolerance = c.getDouble("movement.speed.tolerance", 1.12);
    }

    // ----------------------------------------------------------- hulpjes

    private static long now() {
        return System.currentTimeMillis();
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private Data data(Player p) {
        return data.computeIfAbsent(p.getUniqueId(), k -> {
            Data d = new Data();
            d.lastGround = p.getLocation();
            d.exemptUntil = now() + 3000;
            d.lastDecayMs = now();
            return d;
        });
    }

    private void grace(Player p, long ms) {
        Data d = data(p);
        d.exemptUntil = Math.max(d.exemptUntil, now() + ms);
    }

    private static void resetWindow(Data d) {
        d.winStart = 0;
        d.winDist = 0;
        d.winAir = 0;
    }

    private static void reset(Data d, Location to) {
        d.airTicks = 0;
        d.groundY = to.getY();
        d.nonDescendingSinceNanos = 0;
        d.fallSampleNanos = 0;
        d.jesus = 0;
        d.speedBuf = 0;
        d.leftGround = false;
        d.canSetback = onGround(to);
        d.lastGroundUpdateMs = now();
        resetWindow(d);
    }

    /** Debug-regels voor /shieldguard vl <speler>. */
    public List<String> describe(UUID uuid) {
        List<String> out = new ArrayList<>();
        Data d = data.get(uuid);
        if (d == null || d.vl.isEmpty()) {
            out.add("Geen strafpunten.");
            return out;
        }
        for (Map.Entry<String, Double> en : d.vl.entrySet()) {
            out.add(en.getKey() + ": VL " + fmt(en.getValue()));
        }
        return out;
    }

    // --------------------------------------------------------- terrein

    /** Staat de speler (volgens de server) op iets stevigs? */
    private static boolean onGround(Location to) {
        World w = to.getWorld();
        int by = (int) Math.floor(to.getY() - 0.2);
        double[] offs = {-0.3, 0.0, 0.3};
        for (double ox : offs) {
            for (double oz : offs) {
                int bx = (int) Math.floor(to.getX() + ox);
                int bz = (int) Math.floor(to.getZ() + oz);
                if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return true;
                if (!w.getBlockAt(bx, by, bz).isPassable()) return true;
            }
        }
        return false;
    }

    /** Zit er iets speciaals (water, ladder, ijs, slijm...) rond de speler? */
    private static boolean nearSpecial(Location to) {
        World w = to.getWorld();
        int x0 = (int) Math.floor(to.getX() - 0.4), x1 = (int) Math.floor(to.getX() + 0.4);
        int z0 = (int) Math.floor(to.getZ() - 0.4), z1 = (int) Math.floor(to.getZ() + 0.4);
        int y0 = (int) Math.floor(to.getY() - 0.6), y1 = (int) Math.floor(to.getY() + 1.9);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) return true;
                for (int y = y0; y <= y1; y++) {
                    Block b = w.getBlockAt(x, y, z);
                    Material m = b.getType();
                    if (m.isAir()) continue;
                    if (SPECIAL.contains(m) || b.isLiquid() || Tag.CLIMBABLE.isTagged(m)) return true;
                }
            }
        }
        return false;
    }

    private boolean isExempt(Player p, Data d, long nowMs) {
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return true;
        if (p.getAllowFlight() || p.isFlying() || p.isGliding() || p.isRiptiding()
                || p.isInsideVehicle() || p.isDead()) {
            return true;
        }
        if (p.hasPotionEffect(PotionEffectType.LEVITATION) || p.hasPotionEffect(PotionEffectType.SLOW_FALLING)
                || p.hasPotionEffect(PotionEffectType.JUMP_BOOST)) {
            d.exemptUntil = Math.max(d.exemptUntil, nowMs + 2500);
            return true;
        }
        if (nowMs < d.exemptUntil) return true;
        return p.hasPermission("shieldguard.movement.bypass");
    }

    private static double speedFactor(Player p) {
        double f = p.getWalkSpeed() / 0.2;
        PotionEffect speed = p.getPotionEffect(PotionEffectType.SPEED);
        if (speed != null) f *= 1.0 + 0.2 * (speed.getAmplifier() + 1);
        return Math.max(f, 0.1);
    }

    // ------------------------------------------------------------- hoofd-event

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!enabled) return;
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null || !from.getWorld().equals(to.getWorld())) return;
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        if (dx * dx + dy * dy + dz * dz < 1.0E-8) return; // alleen gekeken, niet bewogen

        Player p = e.getPlayer();
        if (!worlds.isEmpty() && !worlds.contains(p.getWorld().getName())) return;

        Data d = data(p);
        long nowMs = now();
        long nowN = System.nanoTime();
        decay(d, nowMs);

        boolean fromGround = onGround(from);
        boolean ground = onGround(to);
        if (isExempt(p, d, nowMs)) {
            reset(d, to);
            if (ground) {
                d.lastGround = to.clone();
                d.lastGroundUpdateMs = nowMs;
            }
            d.lastMoveNanos = nowN;
            return;
        }

        if (nearSpecial(to)) d.specialUntil = nowMs + 2000;
        boolean special = nowMs < d.specialUntil;
        boolean landed = ground && d.leftGround;
        if (ground) {
            d.leftGround = false;
        } else if (fromGround) {
            // Keep track of legitimate jumps and running off an edge. Never setback while airborne.
            d.leftGround = true;
        }
        d.canSetback = ground && !special && !d.leftGround;
        if (d.canSetback && (landed || d.lastGround == null || d.lastGround.getWorld() != to.getWorld()
                || nowMs - d.lastGroundUpdateMs >= checkpointIntervalMs)) {
            d.lastGround = to.clone();
            d.lastGroundUpdateMs = nowMs;
        }
        double hDist = Math.hypot(dx, dz);

        // Lang stil/lag-piek: tempo-venster opnieuw beginnen
        if (d.lastMoveNanos != 0 && nowN - d.lastMoveNanos > 600_000_000L) resetWindow(d);
        d.lastMoveNanos = nowN;

        if (jesusOn && jesus(p, d, e, to, ground, dy, hDist)) return;

        if (ground || special) {
            d.airTicks = 0;
            d.groundY = to.getY();
            d.nonDescendingSinceNanos = 0;
            d.fallSampleNanos = 0;
            if (special) resetWindow(d);
        } else {
            d.airTicks++;
            if (flyOn && checkFly(p, d, e, to, dy, nowN)) return;
        }

        if (speedOn && !special) checkSpeed(p, d, e, ground, hDist, nowN);
    }

    // ----------------------------------------------------------------- checks

    private boolean jesus(Player p, Data d, PlayerMoveEvent e, Location to, boolean ground,
                          double dy, double hDist) {
        Material below = to.clone().subtract(0, 0.3, 0).getBlock().getType();
        boolean overLiquid = below == Material.WATER || below == Material.LAVA;
        if (!ground && overLiquid && to.getBlock().getType().isAir() && Math.abs(dy) < 0.02 && hDist > 0.05) {
            if (++d.jesus >= 8) {
                d.jesus = 0;
                return flag(p, d, e, "jesus", "loopt over vloeistof", 1.0);
            }
        } else {
            d.jesus = 0;
        }
        return false;
    }

    private boolean checkFly(Player p, Data d, PlayerMoveEvent e, Location to, double dy, long nowN) {
        double rise = to.getY() - d.groundY;
        if (rise > maxRise) {
            return flag(p, d, e, "fly", "stijgt " + fmt(rise) + " blokken zonder grond", 2.0);
        }
        if (dy >= -0.01) {
            if (dy > 0.01) d.fallSampleNanos = 0;
            if (d.nonDescendingSinceNanos == 0) d.nonDescendingSinceNanos = nowN;
            long hoverNanos = (long) hoverDurationMs * 1_000_000L;
            if (nowN - d.nonDescendingSinceNanos >= hoverNanos) {
                return flag(p, d, e, "fly", "blijft " + (hoverDurationMs / 1000.0)
                        + " seconden in de lucht zonder te dalen", 1.0);
            }
        } else {
            d.nonDescendingSinceNanos = 0;
            if (d.fallSampleNanos == 0) {
                d.fallSampleNanos = nowN;
                d.fallSampleY = to.getY();
            } else {
                long elapsedNanos = nowN - d.fallSampleNanos;
                if (glideFactor > 0 && elapsedNanos >= 250_000_000L) {
                    double elapsedTicks = elapsedNanos / 50_000_000.0;
                    // Expected vanilla fall displacement over the sample interval, independent of packet count.
                    double expectedDrop = 3.92 * (elapsedTicks - (1 - Math.pow(0.98, elapsedTicks)) / 0.02);
                    double actualDrop = d.fallSampleY - to.getY();
                    if (expectedDrop >= 0.20 && actualDrop < expectedDrop * glideFactor) {
                        return flag(p, d, e, "fly", "valt te langzaam over " + fmt(elapsedTicks)
                                + " ticks (" + fmt(actualDrop) + " van verwacht " + fmt(expectedDrop) + " blokken)", 1.0);
                    }
                    d.fallSampleNanos = nowN;
                    d.fallSampleY = to.getY();
                }
            }
        }
        return false;
    }

    private boolean checkSpeed(Player p, Data d, PlayerMoveEvent e, boolean ground, double hDist, long nowN) {
        if (d.winStart == 0) {
            d.winStart = nowN;
            d.winDist = 0;
            d.winAir = 0;
        }
        d.winDist += hDist;
        if (!ground) d.winAir++;
        double elapsed = (nowN - d.winStart) / 1.0E9;
        if (elapsed < 1.0) return false;

        double bps = d.winDist / elapsed;
        double limit = (d.winAir > 0 ? airLimit : groundLimit) * speedFactor(p) * tolerance;
        boolean flagged = false;
        if (bps > limit) {
            // Twee vensters achter elkaar nodig, zodat een lag-piek niet meteen telt
            if (++d.speedBuf >= 2) {
                double weight = 1.0 + Math.min(3.0, (bps - limit) / limit * 5.0);
                flagged = flag(p, d, e, "speed", fmt(bps) + " blokken/s (max " + fmt(limit) + ")", weight);
            }
        } else {
            d.speedBuf = Math.max(0, d.speedBuf - 1);
        }
        resetWindow(d);
        return flagged;
    }

    // ------------------------------------------------------ straffen en meldingen

    private void decay(Data d, long nowMs) {
        if (nowMs - d.lastDecayMs < decaySeconds * 1000L) return;
        d.lastDecayMs = nowMs;
        d.vl.replaceAll((k, v) -> v - 1.0);
        d.vl.values().removeIf(v -> v <= 0);
    }

    /** @return true als de speler is gekickt of teruggezet (dan geen verdere checks deze move). */
    private boolean flag(Player p, Data d, PlayerMoveEvent e, String check, String info, double weight) {
        long nowMs = now();
        d.lastFlagMs = nowMs;
        double vl = d.vl.merge(check, weight, Double::sum);

        if (nowMs - d.lastAlertMs > 1000) {
            d.lastAlertMs = nowMs;
            alert(p, check, info, vl);
        }

        if (vl >= kickVl) {
            d.vl.clear();
            Bukkit.getScheduler().runTask(plugin,
                    () -> p.kick(plugin.rawMsg("movement-kick", Placeholder.unparsed("check", check))));
            return true;
        }

        Location back = d.lastGround;
        Location current = e.getTo();
        double setbackDistanceSquared = maxSetbackDistance * maxSetbackDistance;
        if (vl >= setbackVl && d.canSetback && back != null && current != null
                && back.getWorld().equals(current.getWorld()) && onGround(back) && onGround(current)
                && back.distanceSquared(current) <= setbackDistanceSquared) {
            d.ignoreTeleportUntil = nowMs + 500;
            e.setTo(back.clone());
            reset(d, back);
            return true;
        }
        return false;
    }

    private void alert(Player p, String check, String info, double vl) {
        Component msg = plugin.rawMsg("movement-alert",
                Placeholder.unparsed("player", p.getName()),
                Placeholder.unparsed("check", check),
                Placeholder.unparsed("info", info),
                Placeholder.unparsed("vl", fmt(vl)));
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("shieldguard.alerts")) staff.sendMessage(msg);
        }
        plugin.getLogger().info(p.getName() + " " + check + " (" + info + ") VL " + fmt(vl));
    }

    // --------------------------------------------------------- uitzonderingen

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        data(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        data.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        Data d = data(p);
        Location to = e.getTo();
        if (to == null) return;
        reset(d, to);
        if (now() < d.ignoreTeleportUntil) return; // onze eigen setback
        d.lastGround = to.clone();
        d.exemptUntil = Math.max(d.exemptUntil, now() + 1500);
    }

    @EventHandler
    public void onVelocity(PlayerVelocityEvent e) {
        grace(e.getPlayer(), 1800);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) grace(p, 1000);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Data d = data(e.getPlayer());
        d.lastGround = e.getRespawnLocation().clone();
        reset(d, e.getRespawnLocation());
        grace(e.getPlayer(), 3000);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Data d = data(e.getPlayer());
        d.lastGround = e.getPlayer().getLocation();
        reset(d, e.getPlayer().getLocation());
        grace(e.getPlayer(), 3000);
    }

    @EventHandler
    public void onGlide(EntityToggleGlideEvent e) {
        if (e.getEntity() instanceof Player p) grace(p, 3000);
    }

    @EventHandler
    public void onRiptide(PlayerRiptideEvent e) {
        grace(e.getPlayer(), 4000);
    }

    @EventHandler
    public void onFlightToggle(PlayerToggleFlightEvent e) {
        grace(e.getPlayer(), 2500);
    }

    @EventHandler
    public void onGameMode(PlayerGameModeChangeEvent e) {
        grace(e.getPlayer(), 2500);
    }

    @EventHandler
    public void onVehicleExit(VehicleExitEvent e) {
        if (e.getExited() instanceof Player p) grace(p, 2000);
    }
}
