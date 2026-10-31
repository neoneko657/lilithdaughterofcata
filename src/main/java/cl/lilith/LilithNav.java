package cl.lilith;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Navegacion de Lilith: A* sobre bloques con saltos, caidas cortas y picado de bloques.
 * - Si el camino directo esta libre, camina recto (sin gastar CPU).
 * - Si no, busca una ruta (maximo N nodos, como mucho una busqueda cada 10 ticks).
 * - Si la ruta pasa por bloques, los pica con la herramienta adecuada (si se puede).
 * - Si un bloque no se puede romper (spawn protegido, otro plugin lo cancela, etc.),
 *   lo recuerda como bloqueado y busca otra ruta.
 */
public class LilithNav {

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] NONE = new int[0][];
    private static final long BLOCK_MS = 300_000L;
    private static final long AVOID_MS = 20_000L;
    private static final String[] UNTOUCHABLE = {
            "CHEST", "BARREL", "FURNACE", "SMOKER", "SPAWNER", "BEACON", "PORTAL", "COMMAND_BLOCK",
            "LECTERN", "JUKEBOX", "HOPPER", "DISPENSER", "DROPPER", "ANVIL", "ENCHANTING", "BREWING",
            "DOOR", "BED", "SHULKER", "FENCE", "_WALL"
    };

    private static class Node implements Comparable<Node> {
        final int x, y, z;
        double g, f;
        Node parent;
        int[][] dig = NONE;

        Node(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        long key() {
            return LilithNav.key(x, y, z);
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(f, o.f);
        }
    }

    static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((y + 64) & 0xFFF);
    }

    private final LilithPlugin plugin;
    private final Map<Long, Long> blocked = new HashMap<>();

    private long now;
    private List<Node> path;
    private int idx;
    private Vec3 pathGoal;
    private long lastPlan = -1000, giveUpUntil, directOffUntil, lastCheck;
    private int stuckCount;
    private Vec3 lastPos;

    private Block digBlock;
    private int digTicks, digDone;

    private boolean protOn;
    private int protR, protX, protZ;

    public LilithNav(LilithPlugin plugin) {
        this.plugin = plugin;
    }

    public void tick() {
        now++;
    }

    public void reset() {
        path = null;
        idx = 0;
        pathGoal = null;
        digBlock = null;
        stuckCount = 0;
        lastPos = null;
    }

    private void invalidate() {
        path = null;
        idx = 0;
        lastPlan = now - 10;
    }

    // ------------------------------------------------------------------ API

    /** Avanza hacia dest. Devuelve true si ya esta a "reach" bloques. */
    public boolean moveTo(ServerPlayer p, Vec3 dest, double reach, boolean sprint, boolean allowDig) {
        Player bp = p.getBukkitEntity();
        Vec3 pos = p.position();
        double dx = dest.x - pos.x, dz = dest.z - pos.z, dy = dest.y - pos.y;
        double horiz = Math.sqrt(dx * dx + dz * dz);

        if (horiz <= reach && Math.abs(dy) <= 2.0) {
            reset();
            halt(p);
            return true;
        }
        if (digBlock != null) {
            tickDig(p, bp);
            return false;
        }

        if (now >= directOffUntil && horiz < 5 && Math.abs(dy) <= 1.2 && clearLine(bp, dest)) {
            walk(p, dest, sprint, false);
            checkStuck(pos, false);
            return false;
        }

        boolean need = path == null || idx >= path.size() || pathGoal == null || pathGoal.distanceTo(dest) > 2.5;
        if (need && now - lastPlan >= 10 && now >= giveUpUntil) plan(p, bp, dest, allowDig);

        if (path == null || idx >= path.size()) {
            walk(p, dest, sprint, false); // plan B: ir recto saltando lo que se pueda
            checkStuck(pos, false);
            return false;
        }
        followPath(p, bp, pos, sprint, allowDig);
        return false;
    }

    public boolean isDigging() {
        return digBlock != null;
    }

    /** True mientras no encuentra ninguna ruta (por ejemplo, region protegida): conviene hacer otra cosa. */
    public boolean gaveUp() {
        return now < giveUpUntil;
    }

    // ------------------------------------------------------------- following

    private boolean digEnabled(boolean allowDig) {
        return allowDig && plugin.getConfig().getBoolean("pathfinding-dig", true);
    }

    private void followPath(ServerPlayer p, Player bp, Vec3 pos, boolean sprint, boolean allowDig) {
        Node n = path.get(idx);
        double cx = n.x + 0.5, cz = n.z + 0.5;
        double hd = Math.hypot(cx - pos.x, cz - pos.z);
        if (hd < 0.35 && Math.abs(pos.y - n.y) < 1.0) {
            idx++;
            stuckCount = 0;
            lastCheck = now;
            lastPos = pos;
            return;
        }
        World w = bp.getWorld();
        for (int[] c : n.dig) {
            Block b = w.getBlockAt(c[0], c[1], c[2]);
            if (b.isPassable() || b.getType().isAir()) continue;
            if (openable(b)) { // puertas y portones de madera: se abren, no se pican
                if (openDoor(p, bp, b)) continue;
                invalidate();
                return;
            }
            if (!digEnabled(allowDig) || !startDig(p, bp, b)) invalidate();
            return;
        }
        boolean up = n.y > pos.y + 0.3;
        walk(p, new Vec3(cx, n.y, cz), sprint && n.dig.length == 0, up && hd < 1.7);
        checkStuck(pos, true);
    }

    private void checkStuck(Vec3 pos, boolean inPath) {
        if (now - lastCheck < 20) return;
        lastCheck = now;
        if (lastPos != null && pos.distanceTo(lastPos) < 0.25 && digBlock == null) {
            stuckCount++;
            if (inPath && path != null && idx < path.size()) {
                Node n = path.get(idx);
                blocked.put(key(n.x, n.y, n.z), System.currentTimeMillis() + AVOID_MS);
            }
            invalidate();
            directOffUntil = now + 60;
            if (stuckCount >= 4) {
                stuckCount = 0;
                giveUpUntil = now + 100;
            }
        }
        lastPos = pos;
    }

    // ----------------------------------------------------------------- doors

    private static boolean openable(Block b) {
        Material m = b.getType();
        if (m == Material.IRON_DOOR) return false; // necesita redstone
        return Tag.DOORS.isTagged(m) || Tag.FENCE_GATES.isTagged(m);
    }

    private Block otherHalf(Block b) {
        BlockData bd = b.getBlockData();
        if (bd instanceof Bisected bi) {
            Block o = bi.getHalf() == Bisected.Half.BOTTOM ? b.getRelative(BlockFace.UP) : b.getRelative(BlockFace.DOWN);
            if (o.getType() == b.getType()) return o;
        }
        return null;
    }

    private void blockDoor(Block b) {
        markBlocked(b);
        Block o = otherHalf(b);
        if (o != null) markBlocked(o);
    }

    /** Abre una puerta como un jugador (respeta spawn protegido y plugins de regiones). false = no se pudo. */
    private boolean openDoor(ServerPlayer p, Player bp, Block b) {
        if (spawnProtected(b.getX(), b.getZ())) {
            blockDoor(b);
            return false;
        }
        Vec3 c = new Vec3(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
        if (p.getEyePosition().distanceToSqr(c) > 20.25) return false;

        PlayerInteractEvent ev = new PlayerInteractEvent(bp, Action.RIGHT_CLICK_BLOCK,
                bp.getInventory().getItemInMainHand(), b, BlockFace.UP);
        Bukkit.getPluginManager().callEvent(ev);
        if (ev.useInteractedBlock() == Event.Result.DENY) { // region, claim, etc.
            blockDoor(b);
            return false;
        }
        face(p, c);
        p.swing(InteractionHand.MAIN_HAND);
        setOpen(b);
        Block o = otherHalf(b);
        if (o != null) setOpen(o);
        b.getWorld().playSound(b.getLocation(), Sound.BLOCK_WOODEN_DOOR_OPEN, 1f, 1f);
        return b.isPassable();
    }

    private void setOpen(Block b) {
        BlockData bd = b.getBlockData();
        if (bd instanceof Openable op && !op.isOpen()) {
            op.setOpen(true);
            b.setBlockData(op);
        }
    }

    // --------------------------------------------------------------- digging

    private boolean spawnProtected(int x, int z) {
        return protOn && Math.max(Math.abs(x - protX), Math.abs(z - protZ)) <= protR;
    }

    private void markBlocked(Block b) {
        blocked.put(key(b.getX(), b.getY(), b.getZ()), System.currentTimeMillis() + BLOCK_MS);
    }

    private boolean startDig(ServerPlayer p, Player bp, Block b) {
        if (spawnProtected(b.getX(), b.getZ())) {
            markBlocked(b);
            return false;
        }
        for (BlockFace f : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
                BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            if (b.getRelative(f).getType() == Material.LAVA) {
                markBlocked(b); // romperlo la dejaria pasar lava
                return false;
            }
        }
        pickTool(bp, b);
        float speed = b.getBreakSpeed(bp);
        if (speed <= 0f) {
            markBlocked(b);
            return false;
        }
        int ticks = (int) Math.ceil(1.0 / speed);
        if (ticks > 400) {
            markBlocked(b);
            return false;
        }
        digBlock = b;
        digTicks = Math.max(1, ticks);
        digDone = 0;
        return true;
    }

    private void pickTool(Player bp, Block b) {
        PlayerInventory inv = bp.getInventory();
        int bestSlot = inv.getHeldItemSlot();
        float bestS = -1f;
        for (int k = 0; k < 9; k++) {
            int i = (bestSlot + k) % 9; // empieza por la mano actual para no cambiar sin motivo
            ItemStack it = inv.getItem(i);
            float s = b.getDestroySpeed(it == null ? new ItemStack(Material.AIR) : it, true);
            if (s > bestS + 0.01f) {
                bestS = s;
                if (k > 0 || bestS >= 0) bestSlot = i;
            }
        }
        inv.setHeldItemSlot(bestSlot);
    }

    private void tickDig(ServerPlayer p, Player bp) {
        Block b = digBlock;
        if (b == null) return;
        if (b.isPassable() || b.getType().isAir()) {
            digBlock = null;
            return;
        }
        Vec3 c = new Vec3(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
        if (p.getEyePosition().distanceToSqr(c) > 20.25) { // alcance de 4.5 bloques
            digBlock = null;
            invalidate();
            return;
        }
        halt(p);
        face(p, c);
        if (digDone % 4 == 0) p.swing(InteractionHand.MAIN_HAND);
        digDone++;
        if (digDone >= digTicks) {
            boolean ok = bp.breakBlock(b);
            digBlock = null;
            if (!ok || !(b.isPassable() || b.getType().isAir())) {
                markBlocked(b); // protegido por otro plugin, claim, etc.
                invalidate();
            }
        }
    }

    // -------------------------------------------------------------- steering

    private void face(ServerPlayer p, Vec3 v) {
        p.lookAt(EntityAnchorArgument.Anchor.EYES, v);
        p.setYHeadRot(p.getYRot());
    }

    private void walk(ServerPlayer p, Vec3 dest, boolean sprint, boolean jumpHint) {
        face(p, new Vec3(dest.x, p.getEyeY(), dest.z));
        p.zza = 1.0f;
        p.setSprinting(sprint && !p.isUsingItem());
        p.setJumping(jumpHint || p.isInWater() || (p.onGround() && p.horizontalCollision));
    }

    private void halt(ServerPlayer p) {
        p.zza = 0;
        p.xxa = 0;
        p.setJumping(false);
        p.setSprinting(false);
    }

    private boolean clearLine(Player bp, Vec3 dest) {
        World w = bp.getWorld();
        double[][] rays = {{0.3, 0.3}, {1.62, 1.5}}; // rodillas y ojos
        for (double[] r : rays) {
            Location from = bp.getLocation().add(0, r[0], 0);
            Vector dir = new Vector(dest.x - from.getX(), dest.y + r[1] - from.getY(), dest.z - from.getZ());
            double len = dir.length();
            if (len < 0.01) continue;
            if (w.rayTraceBlocks(from, dir.normalize(), len, FluidCollisionMode.NEVER, true) != null) return false;
        }
        return true;
    }

    // -------------------------------------------------------------- planning

    private void plan(ServerPlayer p, Player bp, Vec3 dest, boolean allowDig) {
        lastPlan = now;
        World w = bp.getWorld();
        protR = Bukkit.getServer().getSpawnRadius();
        // Igual que vanilla: solo protege el spawn del overworld, si hay ops y ella no lo es.
        protOn = protR > 0 && !bp.isOp() && !Bukkit.getOperators().isEmpty()
                && w.getEnvironment() == World.Environment.NORMAL;
        Location sp = w.getSpawnLocation();
        protX = sp.getBlockX();
        protZ = sp.getBlockZ();

        int max = Math.max(200, plugin.getConfig().getInt("pathfinding-max-nodes", 1500));
        Vec3 pos = p.position();
        Search s = new Search(w,
                (int) Math.floor(pos.x), (int) Math.floor(pos.y + 0.01), (int) Math.floor(pos.z),
                (int) Math.floor(dest.x), (int) Math.floor(dest.y + 0.01), (int) Math.floor(dest.z),
                digEnabled(allowDig), max);
        List<Node> r = s.run();
        path = r;
        idx = 0;
        pathGoal = dest;
        if (r == null) giveUpUntil = now + 40;
    }

    private static boolean hazard(Material m) {
        switch (m) {
            case LAVA: case FIRE: case SOUL_FIRE: case MAGMA_BLOCK: case CACTUS: case SWEET_BERRY_BUSH:
            case WITHER_ROSE: case CAMPFIRE: case SOUL_CAMPFIRE: case COBWEB: case POWDER_SNOW:
                return true;
            default:
                return false;
        }
    }

    private static boolean fenceLike(Material m) {
        String n = m.name();
        return n.contains("FENCE") || n.endsWith("_WALL");
    }

    private static boolean undiggable(Material m) {
        float hd = m.getHardness();
        if (hd < 0 || hd >= 20) return true;
        String n = m.name();
        for (String s : UNTOUCHABLE) if (n.contains(s)) return true;
        return false;
    }

    /** 0 = libre, 1 = agua, 2 = bloque que se puede picar, 3 = no se puede, 4 = puerta que se puede abrir. */
    private int cell(World w, int x, int y, int z) {
        if (y < w.getMinHeight() || y >= w.getMaxHeight()) return 3;
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return 3;
        long k = key(x, y, z);
        Long exp = blocked.get(k);
        if (exp != null) {
            if (exp > System.currentTimeMillis()) return 3;
            blocked.remove(k);
        }
        Block b = w.getBlockAt(x, y, z);
        Material m = b.getType();
        if (m.isAir()) return 0;
        if (hazard(m)) return 3;
        if (m == Material.WATER) return 1;
        if (b.isPassable()) return 0;
        if (openable(b)) return 4;
        if (undiggable(m)) return 3;
        if (spawnProtected(x, z)) return 3;
        return 2;
    }

    private boolean solid(World w, int x, int y, int z) {
        if (y < w.getMinHeight() || y >= w.getMaxHeight() || !w.isChunkLoaded(x >> 4, z >> 4)) return false;
        Material m = w.getBlockAt(x, y, z).getType();
        return m.isSolid() && !hazard(m) && !fenceLike(m) && !Tag.DOORS.isTagged(m);
    }

    private class Search {
        final World w;
        final int sx, sy, sz, gx, gy, gz, max;
        final boolean dig;
        final PriorityQueue<Node> open = new PriorityQueue<>();
        final Map<Long, Node> best = new HashMap<>();
        final Set<Long> closed = new HashSet<>();

        Search(World w, int sx, int sy, int sz, int gx, int gy, int gz, boolean dig, int max) {
            this.w = w;
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.gx = gx;
            this.gy = gy;
            this.gz = gz;
            this.dig = dig;
            this.max = max;
        }

        double h(int x, int y, int z) {
            return Math.abs(x - gx) + Math.abs(z - gz) + Math.abs(y - gy);
        }

        boolean goal(Node n) {
            return Math.abs(n.x - gx) + Math.abs(n.z - gz) <= 1 && Math.abs(n.y - gy) <= 1;
        }

        List<Node> run() {
            Node start = new Node(sx, sy, sz);
            start.f = h(sx, sy, sz);
            open.add(start);
            best.put(start.key(), start);
            Node bestNode = start;
            double bestH = start.f;
            int expanded = 0;
            while (!open.isEmpty() && expanded < max) {
                Node n = open.poll();
                if (!closed.add(n.key())) continue;
                expanded++;
                if (goal(n)) return build(n);
                double hh = h(n.x, n.y, n.z);
                if (hh < bestH) {
                    bestH = hh;
                    bestNode = n;
                }
                expand(n);
            }
            // Sin ruta completa: avanza lo mas cerca posible si de verdad se acerca.
            if (bestNode != start && bestH < start.f - 1.5) return build(bestNode);
            return null;
        }

        List<Node> build(Node end) {
            List<Node> out = new ArrayList<>();
            for (Node n = end; n != null && n.parent != null; n = n.parent) out.add(n);
            java.util.Collections.reverse(out);
            return out;
        }

        double clear(int x, int y, int z, List<int[]> d) {
            switch (cell(w, x, y, z)) {
                case 0:
                    return 0;
                case 1:
                    return 1.5;
                case 4:
                    d.add(new int[]{x, y, z});
                    return 2.5;
                case 2:
                    if (!dig) return -1;
                    d.add(new int[]{x, y, z});
                    return 2.0 + w.getBlockAt(x, y, z).getType().getHardness() * 3.0;
                default:
                    return -1;
            }
        }

        boolean solid(int x, int y, int z) {
            return LilithNav.this.solid(w, x, y, z);
        }

        void add(Node n, int x, int y, int z, double cost, List<int[]> d) {
            if (Math.abs(x - sx) > 48 || Math.abs(z - sz) > 48) return;
            double g = n.g + cost;
            long k = key(x, y, z);
            Node ex = best.get(k);
            if (ex != null && ex.g <= g) return;
            Node nn = new Node(x, y, z);
            nn.g = g;
            nn.f = g + h(x, y, z);
            nn.parent = n;
            nn.dig = d.isEmpty() ? NONE : d.toArray(new int[0][]);
            best.put(k, nn);
            open.add(nn);
        }

        void expand(Node n) {
            for (int[] dd : DIRS) {
                int nx = n.x + dd[0], nz = n.z + dd[1];

                // caminar al mismo nivel (picando lo que estorbe)
                List<int[]> d = new ArrayList<>(2);
                double a = clear(nx, n.y, nz, d);
                double b = a < 0 ? -1 : clear(nx, n.y + 1, nz, d);
                if (a >= 0 && b >= 0) {
                    if (solid(nx, n.y - 1, nz) || cell(w, nx, n.y, nz) == 1) {
                        add(n, nx, n.y, nz, 1 + a + b, d);
                    } else {
                        // no hay suelo: caida de hasta 3 bloques
                        List<int[]> fd = new ArrayList<>(d);
                        double extra = 0;
                        for (int k = 1; k <= 3; k++) {
                            int cy = n.y - k;
                            int before = fd.size();
                            double ck = clear(nx, cy, nz, fd);
                            if (ck < 0) break;
                            extra += ck;
                            if (solid(nx, cy - 1, nz) || cell(w, nx, cy, nz) == 1) {
                                add(n, nx, cy, nz, 1 + 0.5 * k + a + b + extra, fd);
                                break;
                            }
                            if (fd.size() > before) break;
                        }
                    }
                }

                // subir un bloque saltando (o picando escalera hacia arriba)
                if (solid(nx, n.y, nz)) {
                    List<int[]> u = new ArrayList<>(3);
                    double h0 = clear(n.x, n.y + 2, n.z, u);
                    double h1 = h0 < 0 ? -1 : clear(nx, n.y + 1, nz, u);
                    double h2 = h1 < 0 ? -1 : clear(nx, n.y + 2, nz, u);
                    if (h0 >= 0 && h1 >= 0 && h2 >= 0) add(n, nx, n.y + 1, nz, 1.8 + h0 + h1 + h2, u);
                }
            }
        }
    }
}
