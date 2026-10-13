package nl.tobias.minecraftguard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

final class ReportMenu implements Listener {
    private static final String TARGET_TITLE = "§8Report a player";
    private static final String REASON_TITLE = "§8Choose a report reason";
    private static final String[] REASONS = {"Fly / movement", "X-ray / base scouting", "Combat / reach", "Other suspicious behavior"};
    private final MinecraftGuardPlugin plugin;
    private final Map<UUID, List<UUID>> targetMenus = new HashMap<>();
    private final Map<UUID, UUID> selectedTargets = new HashMap<>();

    ReportMenu(MinecraftGuardPlugin plugin) { this.plugin = plugin; }

    void open(Player reporter) {
        List<Player> online = Bukkit.getOnlinePlayers().stream().filter(p -> !p.equals(reporter)).limit(27).toList();
        Inventory inventory = Bukkit.createInventory(null, 27, Component.text(TARGET_TITLE));
        List<UUID> targets = new ArrayList<>();
        for (int i = 0; i < online.size(); i++) {
            Player target = online.get(i);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(target);
            meta.displayName(Component.text("§e" + target.getName()));
            meta.lore(List.of(Component.text("§7Click to choose a reason")));
            head.setItemMeta(meta);
            inventory.setItem(i, head);
            targets.add(target.getUniqueId());
        }
        targetMenus.put(reporter.getUniqueId(), targets);
        reporter.openInventory(inventory);
        if (online.isEmpty()) reporter.sendMessage("§cThere are no other players online to report.");
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = event.getView().title().toString();
        if (title.contains(TARGET_TITLE)) {
            event.setCancelled(true);
            List<UUID> targets = targetMenus.get(player.getUniqueId());
            int slot = event.getRawSlot();
            if (targets == null || slot < 0 || slot >= targets.size()) return;
            UUID targetId = targets.get(slot);
            Player target = Bukkit.getPlayer(targetId);
            if (target == null) { player.sendMessage("§cThat player is no longer online."); player.closeInventory(); return; }
            selectedTargets.put(player.getUniqueId(), targetId);
            Inventory reasons = Bukkit.createInventory(null, 9, Component.text(REASON_TITLE));
            Material[] icons = {Material.FEATHER, Material.DIAMOND_ORE, Material.IRON_SWORD, Material.PAPER};
            for (int i = 0; i < REASONS.length; i++) {
                ItemStack item = new ItemStack(icons[i]);
                var meta = item.getItemMeta();
                meta.displayName(Component.text("§e" + REASONS[i]));
                item.setItemMeta(meta);
                reasons.setItem(i + 2, item);
            }
            player.openInventory(reasons);
            return;
        }
        if (title.contains(REASON_TITLE)) {
            event.setCancelled(true);
            int choice = event.getRawSlot() - 2;
            if (choice < 0 || choice >= REASONS.length) return;
            UUID targetId = selectedTargets.remove(player.getUniqueId());
            Player target = targetId == null ? null : Bukkit.getPlayer(targetId);
            if (target == null) { player.closeInventory(); player.sendMessage("§cThat player is no longer online."); return; }
            String reason = REASONS[choice];
            plugin.getLogger().info("Report: " + player.getName() + " reported " + target.getName() + " for " + reason);
            player.closeInventory();
            player.sendMessage("§aReport submitted for §f" + target.getName() + "§a: §f" + reason);
            String permission = plugin.getConfig().getString("reports.notify-permission", "minecraftguard.alerts");
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission(permission)) staff.sendMessage("§c[Report] §f" + player.getName() + " reported " + target.getName() + " for " + reason);
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        targetMenus.remove(event.getPlayer().getUniqueId());
        if (event.getView().title().toString().contains(REASON_TITLE)) selectedTargets.remove(event.getPlayer().getUniqueId());
    }
}
