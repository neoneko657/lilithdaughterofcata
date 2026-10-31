package cl.lilith;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.RespawnAnchor;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;

/**
 * Crystal pvp basico de Lilith contra jugadores.
 *
 * Ciclo (dtap):
 *   1. Se acerca (perla si esta lejos, sprint si esta cerca).
 *   2. Golpea con la espada kb1 (si no, kb2, si no, cualquiera) o sprint hit sin espada hasta levantarlo.
 *   3. Apenas lo levanta pone obsidiana al lado del rival, el cristal encima y lo rompe. Si el rival vuelve
 *      a tocar el suelo, corta los cristales y repite el dtap (golpear hasta levantarlo otra vez).
 *   4. A veces, en vez de cristales: pone un nexo (respawn anchor), lo carga con 1 glowstone y lo explota
 *      con un totem en la mano (solo fuera del Nether, alli los nexos no explotan).
 *
 * Si el rival acaba de gastar un totem ("pop"), se salta el golpe y va directo a los cristales.
 * Mientras esta activo, el cerebro NO la deja huir ni curarse a medias: pelea hasta morir.
 */
public class LilithCrystal {

    private enum Phase { NONE, OBSIDIAN, CRYSTAL, BLAST, ANCHOR_PLACE, ANCHOR_CHARGE, ANCHOR_BLAST }

    private static final class Spot {
        Block ground;      // bloque al que se le hace click para colocar la base (null si la base ya existe)
        Block base;        // donde va la obsidiana (o el nexo)
        Block top;         // donde va el cristal
        boolean needPlace;
        double score;
    }

    private static final Predicate<ItemStack> CRYSTAL = LilithItems.of(Material.END_CRYSTAL);
    private static final Predicate<ItemStack> OBSIDIAN = LilithItems.of(Material.OBSIDIAN);
    private static final Predicate<ItemStack> ANCHOR = LilithItems.of(Material.RESPAWN_ANCHOR);
    private static final Predicate<ItemStack> GLOW = LilithItems.of(Material.GLOWSTONE);
    private static final Predicate<ItemStack> TOTEM = LilithItems.of(Material.TOTEM_OF_UNDYING);
    private static final Predicate<ItemStack> PEARL = LilithItems.of(Material.ENDER_PEARL);
    private static final Predicate<ItemStack> SWORD = LilithItems.endsWith("_SWORD");

    private final LilithPlugin plugin;
    private final LilithNav nav;
    private final Random rnd = new Random();
    private final Map<String, Long> bad = new HashMap<>();

    private boolean enabled = true;
    private Phase phase = Phase.NONE;
    private Spot spot;
    private long phaseT, lastPearl = -1000, lastBlast = -1000, lastAnchor = -1000, hitWait = -1, popBoostUntil = -1;
    private int cycles;
    private long lastHit = -1000;

    public LilithCrystal(LilithPlugin plugin, LilithNav nav) {
        this.plugin = plugin;
        this.nav = nav;
    }

    public void setEnabled(boolean on) {
        enabled = on;
        if (!on) reset();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** El rival acaba de gastar un totem: aprovecha y mete cristales ya. */
    public void notePop(long t) {
        popBoostUntil = t + 60;
    }

    // ------------------------------------------------------------------ helpers

    private boolean crystalKit(Player bp) {
        return LilithItems.has(bp, CRYSTAL) && LilithItems.has(bp, OBSIDIAN);
    }

    private boolean anchorKit(Player bp) {
        if (!plugin.getConfig().getBoolean("crystal-anchors", true)) return false;
        if (bp.getWorld().getEnvironment() == World.Environment.NETHER) return false; // alli no explotan
        PlayerInventory inv = bp.getInventory();
        return LilithItems.has(bp, ANCHOR) && LilithItems.has(bp, GLOW)
                && (TOTEM.test(inv.getItemInOffHand()) || LilithItems.has(bp, TOTEM));
    }

    /** True si esta peleando contra un jugador y tiene con que hacer crystal pvp. */
    public boolean active(Player bp, Entity target) {
        if (!enabled || !(target instanceof Player)) return false;
        if (!plugin.getConfig().getBoolean("combat-crystal", true)) return false;
        return crystalKit(bp) || anchorKit(bp);
    }

    private void face(ServerPlayer p, Vec3 v) {
        p.lookAt(EntityAnchorArgument.Anchor.EYES, v);
        p.setYHeadRot(p.getYRot());
    }

    private void attack(ServerPlayer p, net.minecraft.world.entity.Entity h) {
        p.attack(h);
        p.swing(InteractionHand.MAIN_HAND);
    }

    private static Vec3 topCenter(Block b) {
        return new Vec3(b.getX() + 0.5, b.getY() + 1.0, b.getZ() + 0.5);
    }

    private static String key(Block b) {
        return b.getX() + "," + b.getY() + "," + b.getZ();
    }

    private void markBad(long t) {
        if (spot != null) bad.put(key(spot.base), t + 300);
        if (bad.size() > 200) bad.clear();
    }

    private void reset() {
        phase = Phase.NONE;
        spot = null;
        hitWait = -1;
    }

    /** Apunta la perla con adelanto sobre el rival (v = 1.5, g = 0.03). */
    private Vec3 aimPearl(ServerPlayer p, Player target) {
        Vec3 from = p.getEyePosition();
        Vector vel = target.getVelocity();
        Location tl = target.getLocation();
        double v = 1.5, g = 0.03;
        double tx = tl.getX(), ty = tl.getY() + 0.9, tz = tl.getZ();
        double hd = Math.hypot(tx - from.x, tz - from.z);
        double ticks = hd / v;
        tx += vel.getX() * ticks * 0.8;
        tz += vel.getZ() * ticks * 0.8;
        hd = Math.hypot(tx - from.x, tz - from.z);
        ticks = hd / v;
        return new Vec3(tx, ty + 0.5 * g * ticks * ticks, tz);
    }

    /** Menor nivel de knockback (>0) entre sus espadas: asi kb1 va antes que kb2. 0 si ninguna tiene. */
    private int preferredKnockback(Player bp) {
        PlayerInventory inv = bp.getInventory();
        int best = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || !SWORD.test(it)) continue;
            int kb = it.getEnchantmentLevel(Enchantment.KNOCKBACK);
            if (kb > 0 && (best == 0 || kb < best)) best = kb;
        }
        return best;
    }

    /** Espada kb1 primero (si no, kb2); si no hay con knockback, cualquier espada; si no, sprint hit con lo que tenga. */
    private void selectWeapon(Player bp) {
        final int kb = preferredKnockback(bp);
        if (kb > 0) {
            LilithItems.hold(bp, it -> SWORD.test(it) && it.getEnchantmentLevel(Enchantment.KNOCKBACK) == kb);
            return;
        }
        LilithItems.hold(bp, SWORD);
    }

    /** El rival esta en el aire (a mas de ~medio bloque sobre el suelo): ya lo levantaron. */
    private boolean lifted(Player target) {
        Location l = target.getLocation();
        int gy = groundY(l);
        if (gy == Integer.MIN_VALUE) return true;
        return l.getY() - (gy + 1.0) > 0.35;
    }

    /** El rival ya toco el suelo otra vez (y no esta subiendo). */
    private boolean landed(Player target) {
        return !lifted(target) && target.getVelocity().getY() < 0.1;
    }

    // ------------------------------------------------------------------ fight

    /** Un tick de crystal pvp. Devuelve false si no pudo hacer nada (entonces pelea el combate normal). */
    public boolean fight(ServerPlayer p, Player bp, Player target, long t) {
        net.minecraft.world.entity.Entity h = ((CraftEntity) target).getHandle();
        double d = p.distanceTo(h);

        if (phase != Phase.NONE) {
            boolean stale = t - phaseT > 40 || !target.isValid() || target.isDead() || spot == null;
            if (!stale) {
                Location c = new Location(target.getWorld(), spot.top.getX() + 0.5, spot.top.getY() + 0.5,
                        spot.top.getZ() + 0.5);
                stale = c.getWorld() != target.getWorld() || c.distance(target.getLocation()) > 7;
            }
            if (stale) reset();
        }

        // el rival toco el suelo: se corta la cadena de cristales y se repite el dtap
        // (un cristal ya puesto igual se rompe, eso lo hace BLAST)
        if ((phase == Phase.OBSIDIAN || phase == Phase.CRYSTAL) && landed(target)) reset();

        return switch (phase) {
            case OBSIDIAN -> obsidianStep(p, bp, target, t);
            case CRYSTAL -> crystalStep(p, bp, target, t);
            case BLAST -> blastStep(p, bp, target, t);
            case ANCHOR_PLACE -> anchorPlaceStep(p, bp, target, t);
            case ANCHOR_CHARGE -> anchorChargeStep(p, bp, target, t);
            case ANCHOR_BLAST -> anchorBlastStep(p, bp, target, t);
            default -> start(p, bp, target, h, d, t);
        };
    }

    private boolean start(ServerPlayer p, Player bp, Player target, net.minecraft.world.entity.Entity h,
                          double d, long t) {
        boolean lifted = lifted(target);

        // 3) apenas lo levanto (o el rival gasto un totem): obsidiana + cristal / nexo al instante
        if (lifted && (t - lastHit < 40 || t < popBoostUntil) && plan(p, bp, target, t)) {
            hitWait = -1;
            return true;
        }

        // 1) acercarse: perla si esta lejos, caminando (sprint) si no
        if (d > 3.0) {
            hitWait = -1;
            boolean los = bp.hasLineOfSight(target);
            if (d >= 10 && d <= 45 && los && t - lastPearl > 80 && plugin.getConfig().getBoolean("combat-pearls", true)
                    && LilithItems.has(bp, PEARL) && LilithItems.hold(bp, PEARL)) {
                face(p, aimPearl(p, target));
                LilithItems.use(p);
                lastPearl = t;
                return true;
            }
            selectWeapon(bp);
            nav.moveTo(p, h.position(), 2.4, true, true);
            return true;
        }

        // 2) a rango: dtap. Golpea (kb1 o sprint hit) una y otra vez hasta levantarlo
        nav.reset();
        face(p, h.getEyePosition());
        p.xxa = 0f;
        p.zza = d < 1.4 ? 0f : 1.0f;
        p.setSprinting(true);

        if (t < popBoostUntil && plan(p, bp, target, t)) return true; // el rival gasto un totem: cristales ya

        selectWeapon(bp);
        if (hitWait < 0) hitWait = t;
        if (p.getAttackStrengthScale(0.5f) >= 0.9f) {
            attack(p, h);
            lastHit = t;
            return true;
        }
        if (!lifted && t - hitWait > 45) { // no logra levantarlo (escudo, lag...): pasa a los explosivos igual
            hitWait = t;
            plan(p, bp, target, t);
        }
        return true;
    }

    /** Elige entre cristales y nexo y arranca la secuencia en este mismo tick. */
    private boolean plan(ServerPlayer p, Player bp, Player target, long t) {
        boolean crystals = crystalKit(bp);
        boolean anchors = anchorKit(bp) && t - lastAnchor > 80;
        boolean useAnchor = anchors && (!crystals || rnd.nextInt(100) < 35);
        if (useAnchor) {
            Spot s = pickSpot(p, target, false, t);
            if (s != null) {
                spot = s;
                phase = Phase.ANCHOR_PLACE;
                phaseT = t;
                return anchorPlaceStep(p, bp, target, t);
            }
        }
        if (crystals) {
            Spot s = pickSpot(p, target, true, t);
            if (s != null) {
                spot = s;
                phase = Phase.OBSIDIAN;
                phaseT = t;
                cycles = 0;
                return obsidianStep(p, bp, target, t);
            }
        }
        return false;
    }

    // ------------------------------------------------------------ eleccion del lugar

    private int groundY(Location l) {
        Block b = l.getBlock();
        for (int i = 0; i <= 6; i++) {
            Block g = b.getRelative(0, -i, 0);
            if (g.getType().isSolid()) return g.getY();
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Busca un bloque a ~1.5 bloques al lado del rival (nunca debajo) donde poner obsidiana nueva (o el
     * nexo). Si ya hay obsidiana justo a esa distancia la reutiliza; si no, pone una nueva.
     */
    private Spot pickSpot(ServerPlayer p, Player target, boolean crystal, long t) {
        World w = target.getWorld();
        Location tl = target.getLocation();
        int gy = groundY(tl);
        if (gy == Integer.MIN_VALUE) return null;
        int cx = tl.getBlockX(), cz = tl.getBlockZ();
        Vec3 eye = p.getEyePosition();
        Spot best = null;

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                Block ground = w.getBlockAt(cx + dx, gy, cz + dz);
                Block above = ground.getRelative(BlockFace.UP);
                Block above2 = above.getRelative(BlockFace.UP);
                Material gm = ground.getType();
                Spot s = new Spot();

                if (crystal && (gm == Material.OBSIDIAN || gm == Material.BEDROCK) && above.getType().isAir()) {
                    s.base = ground;
                    s.top = above;
                    s.needPlace = false;
                } else if (crystal && above.getType() == Material.OBSIDIAN && above2.getType().isAir()) {
                    s.base = above;
                    s.top = above2;
                    s.needPlace = false;
                } else if (gm.isSolid() && above.getType().isAir() && (!crystal || above2.getType().isAir())) {
                    s.ground = ground;
                    s.base = above;
                    s.top = above2;
                    s.needPlace = true;
                } else {
                    continue;
                }

                if (bad.getOrDefault(key(s.base), 0L) > t) continue;
                Block click = s.needPlace ? s.ground : s.base;
                if (eye.distanceTo(topCenter(click)) > 4.3) continue; // fuera de alcance para poner
                if (s.needPlace && !w.getNearbyEntities(BoundingBox.of(s.base)).isEmpty()) continue;
                if (crystal) { // el cristal necesita 1x2 libre de entidades encima de la base
                    BoundingBox cb = new BoundingBox(s.top.getX(), s.top.getY(), s.top.getZ(),
                            s.top.getX() + 1, s.top.getY() + 2, s.top.getZ() + 1);
                    if (!w.getNearbyEntities(cb).isEmpty()) continue;
                }

                Vector tc = new Vector(s.top.getX() + 0.5, s.top.getY() + 0.5, s.top.getZ() + 0.5);
                double dEye = eye.distanceTo(new Vec3(tc.getX(), tc.getY(), tc.getZ()));
                if (dEye < 1.3) continue; // demasiado pegado a ella
                // la obsidiana va a ~1.5 bloques del rival, a los lados: nunca debajo ni pegada a el,
                // porque donde esta parado no se puede poner el cristal
                double hd = Math.hypot(tc.getX() - tl.getX(), tc.getZ() - tl.getZ());
                if (hd < 1.0 || hd > 2.2) continue;
                s.score = 10 - Math.abs(hd - 1.5) * 6.0 + Math.min(dEye, 3.5) * 0.4
                        + (s.needPlace ? 0 : 0.5) - (dEye > 3.2 ? 3 : 0);
                if (best == null || s.score > best.score) best = s;
            }
        }
        return best;
    }

    // ------------------------------------------------------------ obsidiana y cristal

    private boolean obsidianStep(ServerPlayer p, Player bp, Player target, long t) {
        if (spot == null) {
            reset();
            return false;
        }
        if (spot.needPlace && spot.base.getType() != Material.OBSIDIAN) {
            if (!LilithItems.hold(bp, OBSIDIAN)) {
                reset();
                return false;
            }
            face(p, topCenter(spot.ground));
            LilithItems.placeOnTop(p, spot.ground);
            if (spot.base.getType() != Material.OBSIDIAN) { // otro plugin lo cancelo, o algo estorba
                if (t - phaseT > 8) {
                    markBad(t);
                    reset();
                }
                return true;
            }
        }
        phase = Phase.CRYSTAL;
        return crystalStep(p, bp, target, t);
    }

    private boolean crystalStep(ServerPlayer p, Player bp, Player target, long t) {
        if (spot == null) {
            reset();
            return false;
        }
        Material bm = spot.base.getType();
        if (bm != Material.OBSIDIAN && bm != Material.BEDROCK) {
            if (spot.needPlace) {
                phase = Phase.OBSIDIAN;
                return obsidianStep(p, bp, target, t);
            }
            reset();
            return false;
        }
        if (t - lastBlast < 5) { // deja pasar la invulnerabilidad del rival antes del siguiente cristal
            face(p, ((CraftEntity) target).getHandle().getEyePosition());
            return true;
        }
        if (!LilithItems.hold(bp, CRYSTAL)) {
            reset();
            return false;
        }
        face(p, topCenter(spot.base));
        if (LilithItems.placeOnTop(p, spot.base)) {
            phase = Phase.BLAST;
            phaseT = t;
            return true;
        }
        if (t - phaseT > 6) { // el rival se metio en el hueco o algo lo impide: buscar otro lugar
            markBad(t);
            reset();
        }
        return true;
    }

    private EnderCrystal nearestCrystal(World w, Block top) {
        Location c = top.getLocation().add(0.5, 0.5, 0.5);
        EnderCrystal best = null;
        double bd = 2.5;
        for (Entity e : w.getNearbyEntities(c, 1.5, 1.5, 1.5)) {
            if (e instanceof EnderCrystal ec) {
                double d = e.getLocation().distance(c);
                if (d < bd) {
                    bd = d;
                    best = ec;
                }
            }
        }
        return best;
    }

    private boolean blastStep(ServerPlayer p, Player bp, Player target, long t) {
        if (spot == null) {
            reset();
            return false;
        }
        EnderCrystal c = nearestCrystal(bp.getWorld(), spot.top);
        if (c == null) { // ya exploto o no aparecio: vuelve a poner otro
            if (t - phaseT > 3) {
                phase = Phase.CRYSTAL;
                phaseT = t;
            }
            return true;
        }
        net.minecraft.world.entity.Entity ch = ((CraftEntity) c).getHandle();
        if (p.distanceTo(ch) > 3.3) { // fuera de alcance: da un paso hacia el
            face(p, ch.position().add(0, 1.0, 0));
            p.zza = 1.0f;
            p.setSprinting(false);
            if (t - phaseT > 10) {
                markBad(t);
                reset();
            }
            return true;
        }
        face(p, ch.position().add(0, 1.0, 0));
        attack(p, ch);
        lastBlast = t;
        cycles++;
        plugin.memory().data.crystalBlasts++;
        phase = Phase.CRYSTAL;
        phaseT = t;
        if (cycles >= 6) { // vuelve a golpear al rival para levantarlo de nuevo
            cycles = 0;
            reset();
        }
        return true;
    }

    // ------------------------------------------------------------------ nexos

    private boolean anchorPlaceStep(ServerPlayer p, Player bp, Player target, long t) {
        if (spot == null) {
            reset();
            return false;
        }
        if (spot.base.getType() != Material.RESPAWN_ANCHOR) {
            if (!LilithItems.hold(bp, ANCHOR)) {
                reset();
                return false;
            }
            face(p, topCenter(spot.ground));
            LilithItems.placeOnTop(p, spot.ground);
            if (spot.base.getType() != Material.RESPAWN_ANCHOR) {
                if (t - phaseT > 8) {
                    markBad(t);
                    reset();
                }
                return true;
            }
        }
        phase = Phase.ANCHOR_CHARGE;
        return anchorChargeStep(p, bp, target, t);
    }

    private int charges(Block b) {
        return b.getBlockData() instanceof RespawnAnchor ra ? ra.getCharges() : 0;
    }

    private boolean anchorChargeStep(ServerPlayer p, Player bp, Player target, long t) {
        if (spot == null || spot.base.getType() != Material.RESPAWN_ANCHOR) {
            reset();
            return false;
        }
        if (charges(spot.base) < 1) { // 1 glowstone alcanza para que explote
            if (!LilithItems.hold(bp, GLOW)) {
                reset();
                return false;
            }
            face(p, topCenter(spot.base));
            LilithItems.placeOnTop(p, spot.base);
            if (charges(spot.base) < 1) {
                if (t - phaseT > 8) {
                    markBad(t);
                    reset();
                }
                return true;
            }
        }
        phase = Phase.ANCHOR_BLAST;
        return anchorBlastStep(p, bp, target, t);
    }

    private boolean anchorBlastStep(ServerPlayer p, Player bp, Player target, long t) {
        if (spot == null) {
            reset();
            return false;
        }
        if (spot.base.getType() != Material.RESPAWN_ANCHOR) { // ya exploto
            finishAnchor(t);
            return true;
        }
        // click con un totem en la mano (si tiene uno de repuesto; el de la mano secundaria la protege)
        if (!LilithItems.hold(bp, TOTEM)) LilithItems.hold(bp, SWORD);
        face(p, topCenter(spot.base));
        LilithItems.placeOnTop(p, spot.base);
        if (spot.base.getType() != Material.RESPAWN_ANCHOR) {
            finishAnchor(t);
        } else if (t - phaseT > 8) {
            markBad(t);
            reset();
        }
        return true;
    }

    private void finishAnchor(long t) {
        plugin.memory().data.anchorBlasts++;
        lastAnchor = t;
        reset();
    }
}
