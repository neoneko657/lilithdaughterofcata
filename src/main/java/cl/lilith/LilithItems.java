package cl.lilith;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Ayudas de inventario y de "click derecho" para Lilith. */
public final class LilithItems {

    private LilithItems() {}

    // ------------------------------------------------------------ predicados

    public static Predicate<ItemStack> of(Material... ms) {
        return it -> {
            if (it == null) return false;
            for (Material m : ms) if (it.getType() == m) return true;
            return false;
        };
    }

    public static Predicate<ItemStack> endsWith(String suffix) {
        return it -> it != null && it.getType().name().endsWith(suffix);
    }

    // ------------------------------------------------------------ busqueda

    /** Primer slot (hotbar primero, luego inventario) que cumple el filtro, o -1. */
    public static int find(PlayerInventory inv, Predicate<ItemStack> f) {
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && !it.getType().isAir() && f.test(it)) return i;
        }
        return -1;
    }

    public static boolean has(Player bp, Predicate<ItemStack> f) {
        return find(bp.getInventory(), f) >= 0;
    }

    /** Cantidad total de items que cumplen el filtro en hotbar + inventario (sin la mano secundaria). */
    public static int count(PlayerInventory inv, Predicate<ItemStack> f) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && !it.getType().isAir() && f.test(it)) n += it.getAmount();
        }
        return n;
    }

    // ------------------------------------------------------------ mano

    /** Deja en la mano principal un item que cumpla el filtro (lo sube al hotbar si hace falta). */
    public static boolean hold(Player bp, Predicate<ItemStack> f) {
        PlayerInventory inv = bp.getInventory();
        ItemStack cur = inv.getItemInMainHand();
        if (!cur.getType().isAir() && f.test(cur)) return true;
        int i = find(inv, f);
        if (i < 0) return false;
        if (i < 9) {
            inv.setHeldItemSlot(i);
            return true;
        }
        int dest = spareSlot(inv);
        ItemStack a = inv.getItem(i);
        ItemStack b = inv.getItem(dest);
        inv.setItem(dest, a);
        inv.setItem(i, b);
        inv.setHeldItemSlot(dest);
        return true;
    }

    private static int spareSlot(PlayerInventory inv) {
        for (int i = 0; i < 9; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) return i;
        }
        for (int i = 8; i >= 0; i--) {
            if (!important(inv.getItem(i).getType())) return i;
        }
        return 8;
    }

    private static boolean important(Material m) {
        String n = m.name();
        return n.endsWith("_SWORD") || n.endsWith("_AXE") || n.endsWith("_PICKAXE")
                || m == Material.MACE || m == Material.BOW || m == Material.CROSSBOW
                || m == Material.TOTEM_OF_UNDYING || m == Material.GOLDEN_APPLE
                || m == Material.ENCHANTED_GOLDEN_APPLE || m == Material.ENDER_PEARL
                || m == Material.WIND_CHARGE || m == Material.WATER_BUCKET
                || m == Material.LAVA_BUCKET || m == Material.COBWEB
                || m == Material.FIREWORK_ROCKET || m == Material.SHIELD
                || m == Material.END_CRYSTAL || m == Material.OBSIDIAN
                || m == Material.RESPAWN_ANCHOR || m == Material.GLOWSTONE;
    }

    // ------------------------------------------------------------ "click derecho"

    /** Click derecho con lo que tenga en la mano principal (lanza perlas, pociones, wind charge, cohetes, cubos...). */
    public static void use(ServerPlayer p) {
        InteractionHand hand = InteractionHand.MAIN_HAND;
        p.gameMode.useItem(p, p.level(), p.getItemInHand(hand), hand);
    }

    /** Click derecho sobre la cara de ARRIBA de un bloque (para colocar el bloque de la mano encima). */
    public static boolean placeOnTop(ServerPlayer p, Block against) {
        BlockPos pos = new BlockPos(against.getX(), against.getY(), against.getZ());
        Vec3 loc = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        BlockHitResult hit = new BlockHitResult(loc, Direction.UP, pos, false);
        InteractionHand hand = InteractionHand.MAIN_HAND;
        return p.gameMode.useItemOn(p, p.level(), p.getItemInHand(hand), hand, hit).consumesAction();
    }

    // ------------------------------------------------------------ pociones

    public static List<PotionEffectType> effects(ItemStack it) {
        List<PotionEffectType> out = new ArrayList<>();
        if (it == null || !(it.getItemMeta() instanceof PotionMeta pm)) return out;
        PotionType base = pm.getBasePotionType();
        if (base != null) {
            for (PotionEffect e : base.getPotionEffects()) out.add(e.getType());
        }
        for (PotionEffect e : pm.getCustomEffects()) out.add(e.getType());
        return out;
    }

    public static boolean heals(ItemStack it) {
        return effects(it).contains(PotionEffectType.INSTANT_HEALTH);
    }

    public static boolean harmful(ItemStack it) {
        List<PotionEffectType> e = effects(it);
        return e.contains(PotionEffectType.INSTANT_DAMAGE) || e.contains(PotionEffectType.POISON)
                || e.contains(PotionEffectType.SLOWNESS) || e.contains(PotionEffectType.WEAKNESS);
    }
}
