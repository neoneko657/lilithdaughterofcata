package cl.lilith;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * GUI de 54 slots para editar el inventario de Lilith.
 *
 * Fila 1:   0 casco, 1 peto, 2 pantalones, 3 botas, 5 mano secundaria
 * Filas 2-4 (9-35): inventario principal
 * Fila 5    (36-44): hotbar
 * El resto son paneles grises de relleno (no se pueden tocar).
 */
public class LilithInvGui implements Listener {

    private static final int OFFHAND = 5;

    private static class Holder implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final LilithPlugin plugin;

    public LilithInvGui(LilithPlugin plugin) {
        this.plugin = plugin;
    }

    private static boolean isFiller(int slot) {
        return slot == 4 || (slot >= 6 && slot <= 8) || slot >= 45;
    }

    public void open(Player admin) {
        Player lilith = Bukkit.getPlayer(LilithBody.ID);
        if (lilith == null) {
            admin.sendMessage("Lilith no esta conectada.");
            return;
        }
        Holder holder = new Holder();
        Inventory inv = Bukkit.createInventory(holder, 54, Component.text("Inventario de Lilith"));
        holder.inv = inv;

        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        meta.displayName(Component.text(" "));
        filler.setItemMeta(meta);
        for (int i = 0; i < 54; i++) {
            if (isFiller(i)) inv.setItem(i, filler);
        }

        PlayerInventory pi = lilith.getInventory();
        inv.setItem(0, pi.getHelmet());
        inv.setItem(1, pi.getChestplate());
        inv.setItem(2, pi.getLeggings());
        inv.setItem(3, pi.getBoots());
        inv.setItem(OFFHAND, pi.getItemInOffHand());
        for (int i = 9; i < 36; i++) inv.setItem(i, pi.getItem(i));
        for (int i = 0; i < 9; i++) inv.setItem(36 + i, pi.getItem(i));

        admin.openInventory(inv);
    }

    private void sync(Inventory inv) {
        Player lilith = Bukkit.getPlayer(LilithBody.ID);
        if (lilith == null) return;
        PlayerInventory pi = lilith.getInventory();
        pi.setHelmet(inv.getItem(0));
        pi.setChestplate(inv.getItem(1));
        pi.setLeggings(inv.getItem(2));
        pi.setBoots(inv.getItem(3));
        ItemStack off = inv.getItem(OFFHAND);
        pi.setItemInOffHand(off == null ? new ItemStack(Material.AIR) : off);
        for (int i = 9; i < 36; i++) pi.setItem(i, inv.getItem(i));
        for (int i = 0; i < 9; i++) pi.setItem(i, inv.getItem(36 + i));
        lilith.updateInventory();
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder)) return;
        if (Bukkit.getPlayer(LilithBody.ID) == null) {
            e.setCancelled(true);
            e.getWhoClicked().closeInventory();
            return;
        }
        ClickType c = e.getClick();
        if (c.isShiftClick() || c == ClickType.DOUBLE_CLICK) {
            e.setCancelled(true);
            return;
        }
        if (e.getClickedInventory() == top && isFiller(e.getSlot())) {
            e.setCancelled(true);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> sync(top));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder)) return;
        for (int raw : e.getRawSlots()) {
            if (raw < 54 && isFiller(raw)) {
                e.setCancelled(true);
                return;
            }
        }
        Bukkit.getScheduler().runTask(plugin, () -> sync(top));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (top.getHolder() instanceof Holder) sync(top);
    }
}
