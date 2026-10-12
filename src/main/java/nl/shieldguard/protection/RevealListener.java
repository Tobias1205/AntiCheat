package nl.shieldguard.protection;

import nl.shieldguard.ShieldGuard;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Wanneer een blok verdwijnt kan erts ernaast ineens "bloot" liggen.
 * Dan sturen we het echte blok naar nabije spelers.
 */
public final class RevealListener implements Listener {

    private static final BlockFace[] FACES = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private final ShieldGuard plugin;
    private final MaskManager masks;

    public RevealListener(ShieldGuard plugin, MaskManager masks) {
        this.plugin = plugin;
        this.masks = masks;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        reveal(List.of(e.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        reveal(new ArrayList<>(e.blockList()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        reveal(new ArrayList<>(e.blockList()));
    }

    private void reveal(Collection<Block> removed) {
        if (!masks.xrayEnabled() || removed.isEmpty()) return;
        Set<String> hidden = masks.hiddenNames();

        Set<Block> targets = new LinkedHashSet<>();
        for (Block b : removed) {
            for (BlockFace face : FACES) {
                Block n = b.getRelative(face);
                if (hidden.contains(MaskManager.normalize(n.getType().getKey().getKey()))) {
                    targets.add(n);
                }
            }
        }
        if (targets.isEmpty()) return;

        // Een tick later, zodat het wegvallen van het blok al verwerkt is
        Bukkit.getScheduler().runTask(plugin, () -> {
            double dist = plugin.getConfig().getDouble("xray-protection.reveal-distance", 96);
            double distSq = dist * dist;
            for (Block target : targets) {
                if (target.getType() == Material.AIR) continue;
                Location loc = target.getLocation();
                for (Player p : target.getWorld().getPlayers()) {
                    if (p.getLocation().distanceSquared(loc) <= distSq) {
                        p.sendBlockChange(loc, target.getBlockData());
                    }
                }
            }
        });
    }
}
