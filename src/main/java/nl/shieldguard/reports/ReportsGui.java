package nl.shieldguard.reports;

import nl.shieldguard.ShieldGuard;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Chest-GUI met open reports. Linksklik = teleporteren, rechtsklik = afsluiten. */
public final class ReportsGui implements Listener {

    private static final int SIZE = 54;
    private static final int MAX_REPORTS = 45;

    private final ShieldGuard plugin;
    private final ReportManager reports;

    public ReportsGui(ShieldGuard plugin, ReportManager reports) {
        this.plugin = plugin;
        this.reports = reports;
    }

    private static final class Holder implements InventoryHolder {
        final Map<Integer, Integer> slotToReport = new HashMap<>();
        private Inventory inventory;

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    public void open(Player staff) {
        Holder holder = new Holder();
        Inventory inv = Bukkit.createInventory(holder, SIZE, plugin.rawMsg("gui-title"));
        holder.inventory = inv;
        fill(inv, holder);
        staff.openInventory(inv);
    }

    private void fill(Inventory inv, Holder holder) {
        inv.clear();
        holder.slotToReport.clear();
        SimpleDateFormat fmt = new SimpleDateFormat("dd-MM HH:mm");
        List<Report> open = reports.open();
        for (int i = 0; i < open.size() && i < MAX_REPORTS; i++) {
            Report r = open.get(i);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(r.target));
            meta.displayName(Component.text("#" + r.id + " " + r.targetName, NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(line("Reden: ", r.reason));
            lore.add(line("Door: ", r.reporterName));
            lore.add(line("Tijd: ", fmt.format(new Date(r.time))));
            lore.add(line("Open reports op speler: ", String.valueOf(reports.openAgainst(r.target))));
            lore.add(Component.empty());
            lore.add(Component.text("Linksklik: teleporteren", NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Rechtsklik: afsluiten", NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            head.setItemMeta(meta);
            inv.setItem(i, head);
            holder.slotToReport.put(i, r.id);
        }
        if (open.size() > MAX_REPORTS) {
            ItemStack more = new ItemStack(Material.PAPER);
            var m = more.getItemMeta();
            m.displayName(Component.text("+" + (open.size() - MAX_REPORTS) + " oudere reports (/reports list)",
                    NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            more.setItemMeta(m);
            inv.setItem(SIZE - 1, more);
        }
    }

    private static Component line(String label, String value) {
        return Component.text(label, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text(value, NamedTextColor.WHITE));
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player staff)) return;
        if (e.getClickedInventory() != e.getInventory()) return;
        if (!staff.hasPermission("shieldguard.reports.manage")) return;

        Integer id = holder.slotToReport.get(e.getSlot());
        if (id == null) return;
        Report r = reports.get(id);
        if (r == null) return;

        if (e.getClick() == ClickType.LEFT || e.getClick() == ClickType.SHIFT_LEFT) {
            staff.closeInventory();
            teleport(staff, r);
        } else if (e.getClick() == ClickType.RIGHT || e.getClick() == ClickType.SHIFT_RIGHT) {
            if (reports.close(id, staff.getName(), "")) {
                staff.sendMessage(plugin.msg("report-closed", Placeholder.unparsed("id", String.valueOf(id))));
            }
            fill(e.getInventory(), holder);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    private void teleport(Player staff, Report r) {
        Player target = Bukkit.getPlayer(r.target);
        if (target != null) {
            staff.teleportAsync(target.getLocation());
            staff.sendMessage(plugin.msg("report-tp-done", Placeholder.unparsed("target", r.targetName)));
            return;
        }
        var world = Bukkit.getWorld(r.world);
        if (world == null) return;
        staff.sendMessage(plugin.msg("report-tp-offline", Placeholder.unparsed("target", r.targetName)));
        staff.teleportAsync(new org.bukkit.Location(world, r.x, r.y, r.z));
    }
}
