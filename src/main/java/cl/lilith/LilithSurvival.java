package cl.lilith;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.function.Predicate;

/**
 * Todo lo que la mantiene viva: totems (2 manos si hace falta), manzanas doradas, pociones,
 * huida con elytra y water drop al caer.
 * tick() devuelve FREE (puede pelear normal), HANDS (tiene las manos ocupadas: comiendo, bebiendo)
 * o ALL (esta haciendo una maniobra: planeando, cayendo; el cerebro no debe mover el cuerpo).
 */
public class LilithSurvival {

    public static final int FREE = 0, HANDS = 1, ALL = 2;

    private static final Predicate<ItemStack> TOTEM = LilithItems.of(Material.TOTEM_OF_UNDYING);
    private static final Predicate<ItemStack> ROCKET = LilithItems.of(Material.FIREWORK_ROCKET);
    private static final Predicate<ItemStack> ELYTRA = LilithItems.of(Material.ELYTRA);
    private static final Predicate<ItemStack> GAPPLE = LilithItems.of(Material.GOLDEN_APPLE);
    private static final Predicate<ItemStack> EGAPPLE = LilithItems.of(Material.ENCHANTED_GOLDEN_APPLE);
    private static final Predicate<ItemStack> WATER = LilithItems.of(Material.WATER_BUCKET);
    private static final Predicate<ItemStack> BUCKET = LilithItems.of(Material.BUCKET);
    private static final Predicate<ItemStack> HEAL_DRINK =
            it -> it.getType() == Material.POTION && LilithItems.heals(it);
    private static final Predicate<ItemStack> HEAL_SPLASH =
            it -> it.getType() == Material.SPLASH_POTION && LilithItems.heals(it);

    private final LilithPlugin plugin;
    private final double[] hist = new double[20];
    private int histI;
    private long lastHurt = -1000, lastUse = -1000, lastBuff = -1000;
    private boolean dual;
    private boolean fightToDeath;

    private enum Glide { NONE, PREP, FLY }

    private Glide glide = Glide.NONE;
    private long glideT, lastRocket = -1000;
    private boolean swapped;

    private long mlgT = -1000;
    private boolean mlgPlaced;
    private int pickTries;

    public LilithSurvival(LilithPlugin plugin) {
        this.plugin = plugin;
        java.util.Arrays.fill(hist, 20.0);
    }

    public boolean dual() {
        return dual;
    }

    /** Crystal pvp: no huye, no se pone totems en las dos manos, no vuela con elytra ni se cura a medias. */
    public boolean fightToDeath() {
        return fightToDeath;
    }

    public void setFightToDeath(boolean v) {
        fightToDeath = v;
    }

    public void noteHurt(long t) {
        lastHurt = t;
    }

    /** Tiene algo con que curarse o un totem: entonces no hace falta huir todavia. */
    public boolean canHeal(Player bp) {
        PlayerInventory inv = bp.getInventory();
        return LilithItems.has(bp, GAPPLE) || LilithItems.has(bp, EGAPPLE)
                || LilithItems.has(bp, HEAL_DRINK) || LilithItems.has(bp, HEAL_SPLASH)
                || TOTEM.test(inv.getItemInOffHand()) || LilithItems.has(bp, TOTEM);
    }

    private boolean cfg(String key) {
        return plugin.getConfig().getBoolean(key, true);
    }

    private static double distance(Player bp, Entity target) {
        if (target == null || target.getWorld() != bp.getWorld()) return 999;
        return target.getLocation().distance(bp.getLocation());
    }

    private void face(ServerPlayer p, Vec3 v) {
        p.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, v);
        p.setYHeadRot(p.getYRot());
    }

    // ------------------------------------------------------------------ tick

    public int tick(ServerPlayer p, Player bp, long t, Entity target) {
        double hp = bp.getHealth();
        hist[histI] = hp;
        histI = (histI + 1) % hist.length;
        double max = 0;
        for (double h : hist) max = Math.max(max, h);
        double loss = max - hp; // dano en el ultimo segundo

        double dist = distance(bp, target);
        boolean threat = dist < 30 || t - lastHurt < 100;
        boolean danger = hp <= 8 || loss >= 8;

        if (fightToDeath) {
            dual = false;
            if (p.isUsingItem()) p.stopUsingItem();
        }
        if (cfg("combat-totems")) totems(bp, hp, danger && threat && !fightToDeath, threat);
        if (waterDrop(p, bp, t)) return ALL;
        if (fightToDeath) return FREE; // pelea con lo que tenga hasta morir
        if (elytra(p, bp, t, target, hp, dist)) return ALL;
        return heal(p, bp, t, target, hp, dist);
    }

    /** Llamado cuando un totem se acaba de gastar. */
    public void onTotemPop(Player bp) {
        totems(bp, bp.getHealth(), !fightToDeath, true);
    }

    // ---------------------------------------------------------------- totems

    private void totems(Player bp, double hp, boolean panic, boolean threat) {
        PlayerInventory inv = bp.getInventory();
        boolean off = TOTEM.test(inv.getItemInOffHand());
        if (!off) {
            int i = LilithItems.find(inv, TOTEM);
            if (i >= 0) {
                ItemStack a = inv.getItem(i);
                ItemStack old = inv.getItemInOffHand();
                inv.setItemInOffHand(a);
                inv.setItem(i, old.getType().isAir() ? null : old);
                off = true;
            }
        }
        // totems que quedan fuera de la mano secundaria (incluye el que lleve en la mano principal)
        int spare = LilithItems.count(inv, TOTEM);
        boolean keep = dual && hp < 12 && threat;
        if ((panic || keep) && off && spare >= 1) {
            if (!TOTEM.test(inv.getItemInMainHand())) LilithItems.hold(bp, TOTEM);
            dual = true;
        } else if (dual) {
            dual = false; // el cerebro vuelve a equipar el arma
        }
    }

    // ------------------------------------------------------------- water drop

    private boolean waterDrop(ServerPlayer p, Player bp, long t) {
        if (!cfg("combat-waterdrop")) return false;
        boolean falling = !p.onGround() && !bp.isGliding() && !p.isInWater()
                && bp.getFallDistance() > 5.5f && p.getDeltaMovement().y < -0.3;
        if (falling && LilithItems.has(bp, WATER)) {
            RayTraceResult r = bp.getWorld().rayTraceBlocks(bp.getEyeLocation(), new Vector(0, -1, 0), 6,
                    FluidCollisionMode.ALWAYS, true);
            if (r == null || r.getHitBlock() == null) return false; // vacio: nada que hacer
            Block hit = r.getHitBlock();
            LilithItems.hold(bp, WATER);
            p.setXRot(90f);
            double dy = bp.getEyeLocation().getY() - r.getHitPosition().getY();
            if (dy <= 4.0 && hit.getType() != Material.WATER) {
                LilithItems.use(p);
                mlgT = t;
                mlgPlaced = true;
                pickTries = 0;
            }
            return true;
        }
        if (mlgPlaced) {
            if (t - mlgT > 200 || pickTries > 6) {
                mlgPlaced = false;
                return false;
            }
            if (p.onGround() || p.isInWater()) {
                Block w = findWater(bp);
                if (w == null) {
                    mlgPlaced = false;
                    return false;
                }
                if (LilithItems.hold(bp, BUCKET)) {
                    face(p, new Vec3(w.getX() + 0.5, w.getY() + 0.5, w.getZ() + 0.5));
                    LilithItems.use(p);
                    pickTries++;
                    return true;
                }
                mlgPlaced = false;
            }
        }
        return false;
    }

    private Block findWater(Player bp) {
        World w = bp.getWorld();
        Block base = bp.getLocation().getBlock();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    Block b = base.getRelative(dx, dy, dz);
                    if (b.getType() == Material.WATER && b.getBlockData() instanceof Levelled lv && lv.getLevel() == 0) {
                        return b;
                    }
                }
            }
        }
        return null;
    }

    // ----------------------------------------------------------------- elytra

    private boolean hasElytra(PlayerInventory inv) {
        ItemStack c = inv.getChestplate();
        return (c != null && c.getType() == Material.ELYTRA) || LilithItems.find(inv, ELYTRA) >= 0;
    }

    private void swapChest(PlayerInventory inv, boolean toElytra) {
        if (toElytra) {
            ItemStack c = inv.getChestplate();
            if (c != null && c.getType() == Material.ELYTRA) return;
            int i = LilithItems.find(inv, ELYTRA);
            if (i < 0) return;
            ItemStack e = inv.getItem(i);
            inv.setChestplate(e);
            inv.setItem(i, c);
            swapped = true;
        } else if (swapped) {
            int i = LilithItems.find(inv, LilithItems.endsWith("_CHESTPLATE"));
            if (i >= 0) {
                ItemStack c = inv.getItem(i);
                ItemStack e = inv.getChestplate();
                inv.setChestplate(c);
                inv.setItem(i, e);
            }
            swapped = false;
        }
    }

    private boolean elytra(ServerPlayer p, Player bp, long t, Entity target, double hp, double dist) {
        PlayerInventory inv = bp.getInventory();
        if (glide == Glide.NONE) {
            if (!cfg("combat-elytra") || hp > 8 || dist > 16) return false;
            if (target == null && t - lastHurt > 60) return false;
            if (p.isInWater() || !hasElytra(inv) || !LilithItems.has(bp, ROCKET)) return false;
            swapChest(inv, true);
            glide = Glide.PREP;
            glideT = t;
        }

        Vec3 away = null;
        if (target != null && target.getWorld() == bp.getWorld()) {
            net.minecraft.world.entity.Entity h = ((org.bukkit.craftbukkit.entity.CraftEntity) target).getHandle();
            away = p.position().subtract(h.position());
        }

        if (glide == Glide.PREP) {
            if (away != null) lookAway(p, away);
            if (t - glideT > 50) { // no pudo despegar
                finishGlide(p, bp);
                return false;
            }
            if (p.onGround()) {
                p.zza = 1.0f;
                p.setJumping(true);
            } else {
                p.setJumping(false);
                if (t - glideT > 2 && p.getDeltaMovement().y < 0.15) {
                    bp.setGliding(true);
                    glide = Glide.FLY;
                    glideT = t;
                    lastRocket = -1000;
                }
            }
            return true;
        }

        // FLY
        if (!bp.isGliding()) {
            if (p.onGround() || t - glideT > 10) {
                finishGlide(p, bp);
                return false;
            }
            bp.setGliding(true);
        }
        if (away != null) lookAway(p, away);
        p.setXRot(t - glideT < 70 ? -40f : -12f);
        if (t - lastRocket >= 30 && dist < 90 && LilithItems.hold(bp, ROCKET)) {
            LilithItems.use(p);
            lastRocket = t;
        }
        if (p.onGround() && t - glideT > 15) {
            finishGlide(p, bp);
            return false;
        }
        return true;
    }

    private void lookAway(ServerPlayer p, Vec3 away) {
        float yaw = (float) Math.toDegrees(Math.atan2(-away.x, away.z));
        p.setYRot(yaw);
        p.setYHeadRot(yaw);
    }

    private void finishGlide(ServerPlayer p, Player bp) {
        glide = Glide.NONE;
        p.setJumping(false);
        if (bp.isGliding() && p.onGround()) bp.setGliding(false);
        swapChest(bp.getInventory(), false);
    }

    // ------------------------------------------------------------- curarse

    private static boolean has(PlayerInventory inv, Predicate<ItemStack> f) {
        return LilithItems.find(inv, f) >= 0;
    }

    private int heal(ServerPlayer p, Player bp, long t, Entity target, double hp, double dist) {
        if (p.isUsingItem()) return HANDS;
        if (glide != Glide.NONE || t - lastUse < 8) return FREE;
        PlayerInventory inv = bp.getInventory();
        boolean escapeFirst = hasElytra(inv) && LilithItems.has(bp, ROCKET) && dist < 10 && hp <= 8;
        if (escapeFirst) return FREE;

        // 1) pocion arrojadiza de curacion a sus pies (efecto instantaneo)
        if (hp <= 9 && has(inv, HEAL_SPLASH) && LilithItems.hold(bp, HEAL_SPLASH)) {
            p.setXRot(90f);
            LilithItems.use(p);
            lastUse = t;
            return HANDS;
        }
        // 2) pocion de curacion para beber
        if (hp <= 10 && has(inv, HEAL_DRINK) && LilithItems.hold(bp, HEAL_DRINK)) {
            p.startUsingItem(InteractionHand.MAIN_HAND);
            lastUse = t;
            return HANDS;
        }
        // 3) manzanas doradas (la encantada si esta muy grave)
        if (hp <= 10) {
            Predicate<ItemStack> first = hp <= 7 ? EGAPPLE : GAPPLE;
            Predicate<ItemStack> second = hp <= 7 ? GAPPLE : EGAPPLE;
            if ((has(inv, first) && LilithItems.hold(bp, first)) || (has(inv, second) && LilithItems.hold(bp, second))) {
                p.startUsingItem(InteractionHand.MAIN_HAND);
                lastUse = t;
                return HANDS;
            }
        }
        // 4) pociones de ayuda antes/durante la pelea
        if (cfg("combat-potions") && t - lastBuff > 40 && (target == null || dist > 5)) {
            Predicate<ItemStack> buff = it -> it.getType() == Material.POTION && wantedBuff(bp, it);
            if (has(inv, buff) && LilithItems.hold(bp, buff)) {
                p.startUsingItem(InteractionHand.MAIN_HAND);
                lastBuff = t;
                lastUse = t;
                return HANDS;
            }
        }
        return FREE;
    }

    private boolean wantedBuff(Player bp, ItemStack it) {
        List<PotionEffectType> e = LilithItems.effects(it);
        if (e.contains(PotionEffectType.INSTANT_HEALTH) || e.contains(PotionEffectType.INSTANT_DAMAGE)) return false;
        if (e.contains(PotionEffectType.SPEED) && !bp.hasPotionEffect(PotionEffectType.SPEED)) return true;
        if (e.contains(PotionEffectType.STRENGTH) && !bp.hasPotionEffect(PotionEffectType.STRENGTH)) return true;
        if (e.contains(PotionEffectType.RESISTANCE) && !bp.hasPotionEffect(PotionEffectType.RESISTANCE)) return true;
        if (e.contains(PotionEffectType.REGENERATION) && !bp.hasPotionEffect(PotionEffectType.REGENERATION)
                && bp.getHealth() <= 16) return true;
        return e.contains(PotionEffectType.FIRE_RESISTANCE) && !bp.hasPotionEffect(PotionEffectType.FIRE_RESISTANCE)
                && (bp.getFireTicks() > 0 || bp.getWorld().getEnvironment() == World.Environment.NETHER);
    }
}
