package cl.lilith;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.util.Vector;

import java.util.Random;
import java.util.function.Predicate;

/**
 * Cerebro de combate de Lilith. Cada tick elige UNA accion, en este orden:
 * combo mace + wind charge, ender pearl, pociones arrojadizas, trampas (telarana / lava),
 * arco o ballesta de lejos, y cuerpo a cuerpo con criticos, sprint hit, strafe y W-tap.
 */
public class LilithCombat {

    private static final Predicate<ItemStack> PEARL = LilithItems.of(Material.ENDER_PEARL);
    private static final Predicate<ItemStack> MACE = LilithItems.of(Material.MACE);
    private static final Predicate<ItemStack> WIND = LilithItems.of(Material.WIND_CHARGE);
    private static final Predicate<ItemStack> BOW = LilithItems.of(Material.BOW);
    private static final Predicate<ItemStack> XBOW = LilithItems.of(Material.CROSSBOW);
    private static final Predicate<ItemStack> ARROW =
            LilithItems.of(Material.ARROW, Material.TIPPED_ARROW, Material.SPECTRAL_ARROW);
    private static final Predicate<ItemStack> WEB = LilithItems.of(Material.COBWEB);
    private static final Predicate<ItemStack> LAVA = LilithItems.of(Material.LAVA_BUCKET);
    private static final Predicate<ItemStack> BUCKET = LilithItems.of(Material.BUCKET);
    private static final Predicate<ItemStack> SWORD = LilithItems.endsWith("_SWORD");
    private static final Predicate<ItemStack> AXE = LilithItems.endsWith("_AXE");
    private static final Predicate<ItemStack> HARM_SPLASH =
            it -> it.getType() == Material.SPLASH_POTION && LilithItems.harmful(it);

    private enum Smash { NONE, JUMP, AIR }

    private final LilithPlugin plugin;
    private final LilithNav nav;
    private final LilithCrystal crystal;
    private final Random rnd = new Random();

    private long lastPearl = -1000, lastSmash = -1000, lastWeb = -1000, lastLava = -1000,
            lastHarm = -1000, lastEquip = -1000, lastEscape = -1000;
    private long strafeUntil, wtapUntil = -1, critStart = -1, drawStart = -1, loadStart = -1, backoffUntil = -1;
    private int strafeDir = 1;
    private Smash smash = Smash.NONE;
    private long smashT;
    private Block lavaBlock;
    private long lavaT;

    public LilithCombat(LilithPlugin plugin, LilithNav nav) {
        this.plugin = plugin;
        this.nav = nav;
        this.crystal = new LilithCrystal(plugin, nav);
    }

    public LilithCrystal crystal() {
        return crystal;
    }

    private boolean cfg(String key) {
        return plugin.getConfig().getBoolean(key, true);
    }

    // ----------------------------------------------------------------- helpers

    private void face(ServerPlayer p, Vec3 v) {
        p.lookAt(EntityAnchorArgument.Anchor.EYES, v);
        p.setYHeadRot(p.getYRot());
    }

    private void attack(ServerPlayer p, net.minecraft.world.entity.Entity h) {
        p.attack(h);
        p.swing(InteractionHand.MAIN_HAND);
    }

    /** Punto al que apuntar para que un proyectil (velocidad v, gravedad g por tick) llegue al objetivo. */
    private Vec3 aim(ServerPlayer p, Entity target, double v, double g) {
        Vec3 from = p.getEyePosition();
        Vector vel = target.getVelocity();
        double bodyY = target instanceof LivingEntity le ? le.getHeight() * 0.6 : 0.5;
        double tx = target.getLocation().getX(), ty = target.getLocation().getY() + bodyY, tz = target.getLocation().getZ();
        double hd = Math.hypot(tx - from.x, tz - from.z);
        double ticks = hd / v;
        tx += vel.getX() * ticks * 0.8;
        tz += vel.getZ() * ticks * 0.8;
        hd = Math.hypot(tx - from.x, tz - from.z);
        ticks = hd / v;
        return new Vec3(tx, ty + 0.5 * g * ticks * ticks, tz);
    }

    private void strafeSide(ServerPlayer p, long t) {
        if (t >= strafeUntil) {
            strafeDir = rnd.nextBoolean() ? 1 : -1;
            strafeUntil = t + 10 + rnd.nextInt(25);
        }
        p.xxa = strafeDir * 0.9f;
        p.zza = 0f;
        p.setSprinting(false);
    }

    /** Retrocede mirando al rival (mientras come, bebe o tras poner lava). */
    public void evade(ServerPlayer p, net.minecraft.world.entity.Entity h, long t) {
        face(p, h.getEyePosition());
        if (t >= strafeUntil) {
            strafeDir = rnd.nextBoolean() ? 1 : -1;
            strafeUntil = t + 10 + rnd.nextInt(25);
        }
        p.xxa = strafeDir * 0.8f;
        p.zza = -1.0f;
        p.setSprinting(false);
        p.setJumping(false);
    }

    // ------------------------------------------------------------------- fight

    public void fight(ServerPlayer p, Player bp, Entity target, long t, boolean handsBusy) {
        net.minecraft.world.entity.Entity h = ((CraftEntity) target).getHandle();
        double d = p.distanceTo(h);
        if (handsBusy || t < backoffUntil) {
            evade(p, h, t);
            return;
        }
        boolean isPlayer = target instanceof Player;
        boolean los = bp.hasLineOfSight(target);

        // crystal pvp (dtap + obsidiana + cristales / nexos) contra jugadores, si tiene el equipo
        if (isPlayer && crystal.active(bp, target) && crystal.fight(p, bp, (Player) target, t)) return;

        if (cfg("combat-mace") && smash(p, bp, target, h, d, t)) return;
        if (cfg("combat-pearls") && pearl(p, bp, target, d, los, t)) return;
        if (isPlayer && harmPotion(p, bp, h, d, los, t)) return;
        if (isPlayer && traps(p, bp, (Player) target, d, los, t)) return;
        if (cfg("combat-ranged") && ranged(p, bp, target, d, los, t)) return;
        melee(p, bp, target, h, d, t);
    }

    // ------------------------------------------------- mace + wind charge combo

    private boolean smash(ServerPlayer p, Player bp, Entity target, net.minecraft.world.entity.Entity h,
                          double d, long t) {
        PlayerInventory inv = bp.getInventory();
        switch (smash) {
            case NONE -> {
                boolean worth = target instanceof Player || (target instanceof LivingEntity le && le.getHealth() > 10);
                if (!worth || d < 3.5 || d > 12 || !p.onGround() || t - lastSmash < 80 || bp.getHealth() <= 8) return false;
                if (!LilithItems.has(bp, MACE) || !LilithItems.has(bp, WIND)) return false;
                smash = Smash.JUMP;
                smashT = t;
                face(p, h.getEyePosition());
                p.setJumping(true);
                return true;
            }
            case JUMP -> {
                if (t - smashT > 25 || bp.getHealth() <= 6) {
                    smash = Smash.NONE;
                    p.setJumping(false);
                    return false;
                }
                if (p.onGround() && t - smashT <= 3) {
                    p.setJumping(true);
                    return true;
                }
                p.setJumping(false);
                // en lo mas alto del salto: mira hacia abajo y lanza la wind charge a sus pies
                if (!p.onGround() && (p.getDeltaMovement().y <= 0.12 || t - smashT > 12)) {
                    if (!LilithItems.hold(bp, WIND)) {
                        smash = Smash.NONE;
                        return false;
                    }
                    p.setXRot(90f);
                    LilithItems.use(p);
                    smash = Smash.AIR;
                    smashT = t;
                    lastSmash = t;
                }
                return true;
            }
            default -> { // AIR
                LilithItems.hold(bp, MACE);
                face(p, h.getEyePosition());
                p.zza = 1.0f;
                p.xxa = 0f;
                p.setSprinting(false);
                p.setJumping(false);
                boolean landed = p.onGround() && t - smashT > 6;
                if (landed || t - smashT > 100 || !target.isValid()) {
                    smash = Smash.NONE;
                    return landed;
                }
                if (p.getDeltaMovement().y < 0 && d <= 3.4 && p.getAttackStrengthScale(0.5f) >= 0.9f) {
                    attack(p, h);
                }
                return true;
            }
        }
    }

    // ------------------------------------------------------------- ender pearl

    private boolean pearl(ServerPlayer p, Player bp, Entity target, double d, boolean los, long t) {
        if (t - lastPearl < 100 || d < 14 || d > 45 || !los) return false;
        if (!LilithItems.hold(bp, PEARL)) return false;
        face(p, aim(p, target, 1.5, 0.03));
        LilithItems.use(p);
        lastPearl = t;
        return true;
    }

    /** Lanza una perla lejos del enemigo para escapar. Devuelve true si la lanzo. */
    public boolean escapePearl(ServerPlayer p, Player bp, net.minecraft.world.entity.Entity h, long t) {
        if (!cfg("combat-pearls") || t - lastEscape < 100 || t - lastPearl < 40) return false;
        if (p.distanceTo(h) > 8 || !LilithItems.has(bp, PEARL)) return false;
        Vec3 away = p.position().subtract(h.position());
        if (away.lengthSqr() < 1.0E-4) away = new Vec3(1, 0, 0);
        away = away.normalize().scale(12);
        LilithItems.hold(bp, PEARL);
        face(p, new Vec3(p.getX() + away.x, p.getEyeY(), p.getZ() + away.z));
        p.setXRot(-28f);
        LilithItems.use(p);
        lastEscape = t;
        lastPearl = t;
        return true;
    }

    // --------------------------------------------------------- potions + traps

    private boolean harmPotion(ServerPlayer p, Player bp, net.minecraft.world.entity.Entity h,
                               double d, boolean los, long t) {
        if (t - lastHarm < 60 || d < 2.5 || d > 5.0 || !los) return false;
        if (!LilithItems.hold(bp, HARM_SPLASH)) return false;
        double theta = 0.5 * Math.asin(Math.min(1.0, 0.2 * d)); // v = 0.5, g = 0.05
        face(p, new Vec3(h.getX(), p.getEyeY(), h.getZ()));
        p.setXRot((float) -Math.toDegrees(theta));
        LilithItems.use(p);
        lastHarm = t;
        return true;
    }

    private boolean traps(ServerPlayer p, Player bp, Player target, double d, boolean los, long t) {
        if (!los || !target.isOnGround()) return false;
        Block feet = target.getLocation().getBlock();
        Block below = feet.getRelative(BlockFace.DOWN);
        if (!feet.getType().isAir() || !below.getType().isSolid()) return false;

        // telarana en sus pies
        if (cfg("combat-cobweb") && t - lastWeb > 160 && d >= 2.5 && d <= 4.8 && LilithItems.has(bp, WEB)) {
            LilithItems.hold(bp, WEB);
            face(p, new Vec3(below.getX() + 0.5, below.getY() + 1.0, below.getZ() + 0.5));
            LilithItems.placeOnTop(p, below);
            lastWeb = t;
            return true;
        }
        // lava en sus pies (solo overworld; luego retrocede para no quemarse)
        if (cfg("combat-lava") && t - lastLava > 400 && d >= 3.4 && d <= 4.2
                && bp.getWorld().getEnvironment() == World.Environment.NORMAL
                && LilithItems.has(bp, LAVA)) {
            LilithItems.hold(bp, LAVA);
            face(p, new Vec3(below.getX() + 0.5, below.getY() + 1.0, below.getZ() + 0.5));
            LilithItems.use(p);
            lavaBlock = feet;
            lavaT = t;
            lastLava = t;
            backoffUntil = t + 40;
            return true;
        }
        return false;
    }

    /** Recoge la lava que dejo puesta cuando ya no hay pelea. Devuelve true si esta ocupada en eso. */
    public boolean tidy(ServerPlayer p, Player bp, long t) {
        if (lavaBlock == null) return false;
        if (t - lavaT > 1200 || lavaBlock.getType() != Material.LAVA) {
            lavaBlock = null;
            return false;
        }
        if (t - lavaT < 100) return false;
        Vec3 c = new Vec3(lavaBlock.getX() + 0.5, lavaBlock.getY() + 0.5, lavaBlock.getZ() + 0.5);
        if (p.getEyePosition().distanceTo(c) > 4.0) {
            nav.moveTo(p, c, 3.2, false, false);
            return true;
        }
        if (!LilithItems.hold(bp, BUCKET)) {
            lavaBlock = null;
            return false;
        }
        nav.reset();
        p.zza = 0;
        p.xxa = 0;
        face(p, c);
        LilithItems.use(p);
        return true;
    }

    // ------------------------------------------------------------ arco / ballesta

    private void cancelDraw(ServerPlayer p) {
        if (drawStart >= 0 || loadStart >= 0) {
            if (p.isUsingItem()) p.stopUsingItem();
            drawStart = -1;
            loadStart = -1;
        }
    }

    private boolean ranged(ServerPlayer p, Player bp, Entity target, double d, boolean los, long t) {
        if (d < 7 || d > 40 || !los || !LilithItems.has(bp, ARROW)) {
            cancelDraw(p);
            return false;
        }
        boolean xbow = LilithItems.has(bp, XBOW);
        boolean bow = LilithItems.has(bp, BOW);
        if (!xbow && !bow) return false;
        PlayerInventory inv = bp.getInventory();

        if (xbow) {
            LilithItems.hold(bp, XBOW);
            ItemStack cur = inv.getItemInMainHand();
            boolean charged = cur.getItemMeta() instanceof CrossbowMeta cm && cm.hasChargedProjectiles();
            face(p, aim(p, target, 3.15, 0.05));
            if (charged) {
                LilithItems.use(p);
                loadStart = -1;
            } else if (!p.isUsingItem()) {
                p.startUsingItem(InteractionHand.MAIN_HAND);
                loadStart = t;
            } else if (loadStart >= 0 && t - loadStart >= 26) {
                p.releaseUsingItem();
                loadStart = -1;
            }
        } else {
            LilithItems.hold(bp, BOW);
            face(p, aim(p, target, 3.0, 0.05));
            if (!p.isUsingItem()) {
                p.startUsingItem(InteractionHand.MAIN_HAND);
                drawStart = t;
            } else if (drawStart >= 0 && t - drawStart >= 20) {
                p.releaseUsingItem();
                drawStart = -1;
            }
        }
        // se mueve de lado mientras apunta (pero el strafe no cambia hacia donde mira)
        if (t >= strafeUntil) {
            strafeDir = rnd.nextBoolean() ? 1 : -1;
            strafeUntil = t + 10 + rnd.nextInt(25);
        }
        p.xxa = strafeDir * 0.8f;
        p.zza = 0f;
        p.setSprinting(false);
        return true;
    }

    // -------------------------------------------------------------- cuerpo a cuerpo

    private void weapon(Player bp, Entity target, long t) {
        if (t - lastEquip < 10) return;
        boolean shielded = target instanceof HumanEntity he
                && he.isBlocking() && he.getCooldown(Material.SHIELD) <= 0;
        String cur = bp.getInventory().getItemInMainHand().getType().name();
        boolean axeHeld = cur.endsWith("_AXE");
        boolean swordHeld = cur.endsWith("_SWORD");
        if (shielded) { // rival con escudo arriba: hacha
            if (!axeHeld && LilithItems.hold(bp, AXE)) lastEquip = t;
        } else if (!swordHeld) {
            if (LilithItems.hold(bp, SWORD)) {
                lastEquip = t;
            } else if (!axeHeld) {
                if (LilithItems.hold(bp, AXE) || (!cur.equals("MACE") && LilithItems.hold(bp, MACE))) lastEquip = t;
            }
        }
    }

    private void melee(ServerPlayer p, Player bp, Entity target, net.minecraft.world.entity.Entity h,
                       double d, long t) {
        cancelDraw(p);
        weapon(bp, target, t);
        boolean creeper = target.getType() == EntityType.CREEPER;
        boolean sword = bp.getInventory().getItemInMainHand().getType().name().endsWith("_SWORD");
        boolean ground = p.onGround();
        float str = p.getAttackStrengthScale(0.5f);
        boolean falling = !ground && !p.isInWater() && p.getDeltaMovement().y < -0.02 && bp.getFallDistance() > 0.0f;

        if (d > 3.0) { // acercarse corriendo (sprint)
            if (t < wtapUntil) {
                p.zza = 0f;
                p.setSprinting(false);
            } else {
                nav.moveTo(p, h.position(), 2.4, true, true);
            }
            if (!ground) p.setJumping(false);
            return;
        }

        nav.reset();
        face(p, h.getEyePosition());
        if (t >= strafeUntil) {
            strafeDir = rnd.nextBoolean() ? 1 : -1;
            strafeUntil = t + 10 + rnd.nextInt(25);
        }
        p.xxa = creeper ? 0f : strafeDir * 0.9f;
        p.zza = d < 1.8 ? -0.6f : (str < 0.6f ? 0.2f : 0.9f);
        if (t < wtapUntil) { // W-tap: suelta la tecla un instante para reiniciar el sprint
            p.zza = 0f;
            p.setSprinting(false);
        } else {
            p.setSprinting(p.zza > 0.5f && !p.isUsingItem());
        }
        if (creeper && str < 0.95f) { // golpea y retrocede
            p.zza = -1.0f;
            p.setSprinting(false);
        }
        if (!ground) p.setJumping(false);

        if (str >= 0.95f) {
            if (falling) { // golpe critico (en vanilla el critico no se hace sprintando)
                p.setSprinting(false);
                attack(p, h);
                critStart = -1;
                wtapUntil = t + 3;
            } else if (ground && critStart < 0) {
                boolean wantCrit = sword && !creeper && rnd.nextInt(100) < 75
                        && (target instanceof Player || (target instanceof LivingEntity le && le.getHealth() >= 10));
                if (wantCrit) {
                    p.setJumping(true);
                    critStart = t;
                } else { // sprint hit
                    attack(p, h);
                    wtapUntil = t + 3;
                }
            } else if (ground && t - critStart > 4) { // el salto no salio bien: pega igual
                attack(p, h);
                critStart = -1;
                wtapUntil = t + 3;
            }
        }
    }
}
