package cl.lilith;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cerebro liviano de Lilith: reglas de prioridad, sin modelos pesados.
 * Piensa cada 10 ticks (0.5 s) y solo mueve su cuerpo el resto del tiempo.
 */
public class LilithBrain {

    enum Mode { IDLE, FOLLOW, STAY, FIGHT, FLEE }

    private final LilithPlugin plugin;
    private final Random rnd = new Random();
    private final LilithNav nav;
    private final LilithCombat combat;
    private final LilithSurvival surv;
    private final LilithKnowledge kb = new LilithKnowledge();
    private final LilithTalk talk = new LilithTalk(kb, rnd);
    private final LilithAI ai = new LilithAI();
    private final Map<UUID, Long> chatCool = new ConcurrentHashMap<>();
    private final Map<UUID, Long> followUntil = new ConcurrentHashMap<>();
    private final ArrayDeque<Long> aiCalls = new ArrayDeque<>();
    private Mode lastMode = Mode.IDLE;
    private Entity navTarget;
    private long lastEquip;

    private Mode mode = Mode.IDLE;
    private Mode base = Mode.IDLE;
    private String followName;
    private Entity target;
    private Player lookTarget;
    private Vec3 wander;
    private long t, nextWander, lastSpoke;
    private String duelist;
    private long aloneSince, lastSpawnCmd = -100000;
    private boolean alone;

    public LilithBrain(LilithPlugin plugin) {
        this.plugin = plugin;
        this.nav = new LilithNav(plugin);
        this.combat = new LilithCombat(plugin, nav);
        this.surv = new LilithSurvival(plugin);
    }

    private ServerPlayer p() {
        return plugin.handle();
    }

    private Player bp() {
        ServerPlayer p = p();
        return p == null ? null : p.getBukkitEntity();
    }

    // ---------------------------------------------------------------- tick

    public void tick() {
        ServerPlayer p = p();
        if (p == null) return;
        p.doTick(); // un jugador real lo recibe de su conexion; la de Lilith es falsa
        if (!p.isAlive()) {
            stop(p);
            return;
        }
        t++;
        nav.tick();
        Entity threat = (mode == Mode.FIGHT || mode == Mode.FLEE) ? target : null;
        // crystal pvp: sin huir, sin curarse a medias, sin elytra: pelea con lo que tenga hasta morir
        surv.setFightToDeath(mode == Mode.FIGHT && target != null
                && plugin.getConfig().getBoolean("crystal-fight-to-death", true)
                && combat.crystal().active(p.getBukkitEntity(), target));
        int busy = surv.tick(p, p.getBukkitEntity(), t, threat);
        if (t % 10 == 0) think(p);
        if (busy < LilithSurvival.ALL) act(p, busy);
    }

    private void think(ServerPlayer p) {
        Player bp = p.getBukkitEntity();
        double hp = bp.getHealth();

        if (bp.getFoodLevel() < 14 && !p.isUsingItem() && mode != Mode.FIGHT) eat(p, bp);

        if (base == Mode.FOLLOW && (followName == null || Bukkit.getPlayerExact(followName) == null)) {
            base = Mode.IDLE;
            if (mode == Mode.FOLLOW) mode = Mode.IDLE;
        }

        if (mode == Mode.FIGHT) {
            if (invalid(bp, target)) {
                target = null;
                mode = base;
            } else if (hp < fleeHp(bp) && !surv.canHeal(bp) && !surv.fightToDeath()) {
                mode = Mode.FLEE;
            }
        } else if (mode == Mode.FLEE) {
            if (hp >= fleeHp(bp) + 5 || invalid(bp, target)) {
                target = null;
                mode = base;
            }
        } else if (hp >= 10 && duelTarget(bp)) {
            equipBest(bp);
        } else if (hp >= 10) {
            double reach = base == Mode.STAY ? 6 : 12;
            Entity best = null;
            double bestD = Double.MAX_VALUE;
            for (Entity e : bp.getNearbyEntities(reach, 6, reach)) {
                if (e instanceof Monster && bp.hasLineOfSight(e) && !feared(e, hp)) {
                    double d = e.getLocation().distanceSquared(bp.getLocation());
                    if (d < bestD) {
                        bestD = d;
                        best = e;
                    }
                }
            }
            if (best != null) {
                target = best;
                mode = Mode.FIGHT;
                plugin.memory().encounter(best.getType().name());
                equipBest(bp);
            }
        }

        updateAlone(p, bp);

        if (mode == Mode.IDLE && t >= nextWander) {
            nextWander = t + 100 + rnd.nextInt(200);
            if (rnd.nextInt(10) < 7) pickWander(p, bp);
            else wander = null;
        }

        lookTarget = null;
        double bestL = 36;
        for (Entity e : bp.getNearbyEntities(6, 3, 6)) {
            if (e instanceof Player pl && !pl.getUniqueId().equals(LilithBody.ID)
                    && pl.getGameMode() != GameMode.SPECTATOR) {
                double d = pl.getLocation().distanceSquared(bp.getLocation());
                if (d < bestL) {
                    bestL = d;
                    lookTarget = pl;
                }
            }
        }
    }

    private boolean invalid(Player bp, Entity e) {
        return e == null || !e.isValid() || e.isDead()
                || e.getWorld() != bp.getWorld()
                || e.getLocation().distanceSquared(bp.getLocation()) > (isDuelist(e) ? 3600 : 400);
    }

    private static String key(Entity e) {
        return e instanceof Player ? "PLAYER" : e.getType().name();
    }

    /** Le teme a lo que ya la mato 2+ veces, mientras no tenga la vida alta. */
    private boolean feared(Entity e, double hp) {
        return plugin.memory().killedBy(key(e)) >= 2 && hp < 16;
    }

    private static int tier(String n) {
        if (n.startsWith("NETHERITE")) return 4;
        if (n.startsWith("DIAMOND")) return 3;
        if (n.startsWith("IRON") || n.startsWith("CHAINMAIL")) return 2;
        if (n.startsWith("GOLDEN") || n.startsWith("LEATHER") || n.startsWith("TURTLE")) return 1;
        return 0;
    }

    /** Puntaje simple de equipo: armadura + arma. */
    private static int gear(Player pl) {
        int g = 0;
        for (ItemStack a : pl.getInventory().getArmorContents()) {
            if (a != null) g += tier(a.getType().name());
        }
        String w = pl.getInventory().getItemInMainHand().getType().name();
        if (w.equals("MACE")) g += 4;
        else if (w.endsWith("_SWORD") || w.endsWith("_AXE")) g += tier(w);
        return g;
    }

    /** Con que vida se rinde y huye: sube si el rival la mato antes, esta mejor equipado, o son muchos. */
    private double fleeHp(Player bp) {
        double th = 7;
        if (target != null) {
            th += Math.min(4, plugin.memory().killedBy(key(target)));
            if (target instanceof Player pl && gear(pl) >= gear(bp) + 4) th += 3;
        }
        int hostile = 0;
        for (Entity e : bp.getNearbyEntities(10, 5, 10)) if (e instanceof Monster) hostile++;
        if (hostile >= 4) th += 3;
        return Math.min(th, 14);
    }

    private boolean isDuelist(Entity e) {
        return duelist != null && e instanceof Player pl && pl.getName().equalsIgnoreCase(duelist);
    }

    /** Si hay un duelo pendiente y el rival esta cerca, lo ataca. */
    private boolean duelTarget(Player bp) {
        if (duelist == null) return false;
        Player dp = Bukkit.getPlayerExact(duelist);
        if (dp == null || dp.isDead() || dp.getGameMode() != GameMode.SURVIVAL) {
            duelist = null;
            return false;
        }
        if (dp.getWorld() != bp.getWorld() || dp.getLocation().distanceSquared(bp.getLocation()) > 3600) return false;
        target = dp;
        mode = Mode.FIGHT;
        return true;
    }

    /** Sola = ningun jugador a 64 bloques durante un rato. Si ademas esta lejos, vuelve a /spawn. */
    private void updateAlone(ServerPlayer p, Player bp) {
        boolean company = false;
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.getUniqueId().equals(LilithBody.ID) || o.getGameMode() == GameMode.SPECTATOR) continue;
            if (o.getWorld() == bp.getWorld() && o.getLocation().distanceSquared(bp.getLocation()) < 4096) {
                company = true;
                break;
            }
        }
        if (company || mode != Mode.IDLE || base != Mode.IDLE) aloneSince = t;
        int need = Math.max(5, plugin.getConfig().getInt("return-to-spawn-after-seconds", 30)) * 20;
        alone = t - aloneSince > need;
        if (!alone || p.isUsingItem() || t - lastSpawnCmd < 1200) return;
        if (!plugin.getConfig().getBoolean("return-to-spawn", true)) return;
        double min = plugin.getConfig().getInt("return-to-spawn-min-distance", 48);
        World w = bp.getWorld();
        boolean otherWorld = w != Bukkit.getWorlds().get(0);
        if (otherWorld || bp.getLocation().distance(w.getSpawnLocation()) > min) {
            command(plugin.getConfig().getString("spawn-command", "spawn"));
            lastSpawnCmd = t;
            aloneSince = t;
        }
    }

    /** Elige a donde pasear: alrededor del spawn si esta sola cerca de el; si no, cerca de donde esta. */
    private void pickWander(ServerPlayer p, Player bp) {
        World w = bp.getWorld();
        Location spawn = w.getSpawnLocation();
        Location here = bp.getLocation();
        boolean aroundSpawn = alone && here.distance(spawn) < 48;
        Location c = aroundSpawn ? spawn : here;
        int r = aroundSpawn ? 14 : 8;
        int x = c.getBlockX() + rnd.nextInt(2 * r + 1) - r;
        int z = c.getBlockZ() + rnd.nextInt(2 * r + 1) - r;
        double y = p.getY();
        if (w.getEnvironment() == World.Environment.NORMAL && w.isChunkLoaded(x >> 4, z >> 4)) {
            y = w.getHighestBlockYAt(x, z) + 1;
        }
        wander = new Vec3(x + 0.5, y, z + 0.5);
    }

    private void act(ServerPlayer p, int busy) {
        if (mode != lastMode) {
            nav.reset();
            lastMode = mode;
        }
        switch (mode) {
            case FIGHT -> fight(p, busy);
            case FLEE -> flee(p);
            case FOLLOW -> follow(p);
            case STAY -> {
                stop(p);
                lookAtPlayer(p);
            }
            default -> idle(p);
        }
    }

    private void fight(ServerPlayer p, int busy) {
        if (target == null) return;
        if (target != navTarget) {
            nav.reset();
            navTarget = target;
        }
        Player bp = p.getBukkitEntity();
        if (surv.dual()) { // totems en las dos manos: no ataca, solo se aleja
            flee(p);
            return;
        }
        if (!surv.dual() && bp.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING) {
            equipBest(bp);
        }
        combat.fight(p, bp, target, t, busy == LilithSurvival.HANDS);
    }

    private void flee(ServerPlayer p) {
        if (target == null) return;
        net.minecraft.world.entity.Entity h = ((CraftEntity) target).getHandle();
        if (p.getBukkitEntity().getHealth() <= 8 && combat.escapePearl(p, p.getBukkitEntity(), h, t)) return;
        Vec3 away = p.position().subtract(h.position());
        if (away.lengthSqr() < 1.0E-4) away = new Vec3(1, 0, 0);
        moveToward(p, p.position().add(away.normalize().scale(8)), true);
    }

    private void follow(ServerPlayer p) {
        Player fp = followName == null ? null : Bukkit.getPlayerExact(followName);
        if (fp == null || fp.getWorld() != p.getBukkitEntity().getWorld()) {
            stop(p);
            return;
        }
        Vec3 pos = ((CraftPlayer) fp).getHandle().position();
        double d = p.position().distanceTo(pos);
        if (d > 3.5 || Math.abs(pos.y - p.getY()) > 2.5) {
            nav.moveTo(p, pos, 3.0, d > 9, true);
        } else {
            nav.reset();
            stop(p);
            face(p, ((CraftPlayer) fp).getHandle().getEyePosition());
        }
    }

    private void idle(ServerPlayer p) {
        if (combat.tidy(p, p.getBukkitEntity(), t)) return;
        if (wander != null) {
            if (nav.gaveUp()) { // no hay ruta (region protegida, encerrada...): cambia de plan
                wander = null;
                nextWander = t + 20;
                return;
            }
            if (nav.moveTo(p, wander, 1.5, false, false)) wander = null;
        } else {
            stop(p);
            lookAtPlayer(p);
        }
    }

    private void lookAtPlayer(ServerPlayer p) {
        if (lookTarget != null && lookTarget.isOnline()) {
            face(p, ((CraftPlayer) lookTarget).getHandle().getEyePosition());
        }
    }

    // ------------------------------------------------------------ movement

    private void face(ServerPlayer p, Vec3 v) {
        p.lookAt(EntityAnchorArgument.Anchor.EYES, v);
        p.setYHeadRot(p.getYRot());
    }

    private void moveToward(ServerPlayer p, Vec3 dest, boolean sprint) {
        face(p, new Vec3(dest.x, p.getEyeY(), dest.z));
        p.zza = 1.0f;
        p.setSprinting(sprint && !p.isUsingItem());
        boolean jump = p.isInWater()
                || (p.onGround() && (p.horizontalCollision || dest.y - p.getY() > 0.6));
        p.setJumping(jump);
    }

    private void stop(ServerPlayer p) {
        p.zza = 0;
        p.xxa = 0;
        p.setJumping(false);
        p.setSprinting(false);
    }

    // ------------------------------------------------------------ actions

    private void equipBest(Player bp) {
        PlayerInventory inv = bp.getInventory();
        int axe = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null) continue;
            String n = it.getType().name();
            if (n.endsWith("_SWORD")) {
                inv.setHeldItemSlot(i);
                return;
            }
            if (n.endsWith("_AXE") && axe < 0) axe = i;
        }
        if (axe >= 0) inv.setHeldItemSlot(axe);
    }

    private void eat(ServerPlayer p, Player bp) {
        PlayerInventory inv = bp.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() == Material.AIR || !it.getType().isEdible()) continue;
            if (it.getType() == Material.ROTTEN_FLESH || it.getType() == Material.SPIDER_EYE
                    || it.getType() == Material.POISONOUS_POTATO || it.getType() == Material.PUFFERFISH) continue;
            int slot = i;
            if (i > 8) {
                ItemStack swap = inv.getItem(8);
                inv.setItem(8, it);
                inv.setItem(i, swap);
                slot = 8;
            }
            inv.setHeldItemSlot(slot);
            p.startUsingItem(InteractionHand.MAIN_HAND);
            return;
        }
    }

    // ----------------------------------------------------- talk & commands

    /** Dice algo en el chat. Se limpia: sin "/" inicial (ejecutaria un comando) y maximo 250 caracteres. */
    public void say(String msg) {
        Player bp = bp();
        if (bp == null) return;
        String m = LilithText.clean(msg);
        if (m.isEmpty()) return;
        if (m.length() > 250) m = m.substring(0, 250);
        lastSpoke = t;
        bp.chat(m);
    }

    /** Dice varias lineas con una pausa entre ellas, como si fuera escribiendo. */
    private void sayLines(List<String> lines) {
        long delay = plugin.getConfig().getLong("chat-reply-delay-ticks", 15);
        for (String l : lines) {
            delay += Math.min(50, 10 + l.length() / 3);
            final String line = l;
            Bukkit.getScheduler().runTaskLater(plugin, () -> say(line), delay);
        }
    }

    /** Ejecuta un comando como si lo escribiera ella, con SUS permisos (los de un jugador normal). */
    public void command(String cmd) {
        Player bp = bp();
        if (bp == null) return;
        Bukkit.dispatchCommand(bp, cmd.startsWith("/") ? cmd.substring(1) : cmd);
    }

    public void follow(String name) {
        followName = name;
        base = Mode.FOLLOW;
        if (mode != Mode.FIGHT && mode != Mode.FLEE) mode = Mode.FOLLOW;
    }

    /** Se cambia la skin usando el comando de tu plugin de skins (config: skin-command). */
    public void skin(String name) {
        if (name == null || !name.matches("\\w{1,16}")) return;
        String fmt = plugin.getConfig().getString("skin-command", "skin set %s");
        command(String.format(fmt, name));
    }

    /** Va a pelear con ese jugador (si esta conectado). */
    public boolean duel(String name) {
        Player dp = Bukkit.getPlayerExact(name);
        if (dp == null || dp.getUniqueId().equals(LilithBody.ID)) return false;
        duelist = dp.getName();
        return true;
    }

    /** Un jugador gasto un totem: si es su rival, mete cristales de inmediato. */
    public void onEnemyPop(Player who) {
        if (target == who) combat.crystal().notePop(t);
    }

    public void crystal(boolean on) {
        combat.crystal().setEnabled(on);
    }

    public void stay() {
        base = Mode.STAY;
        if (mode != Mode.FIGHT && mode != Mode.FLEE) mode = Mode.STAY;
    }

    public void free() {
        base = Mode.IDLE;
        followName = null;
        if (mode != Mode.FIGHT && mode != Mode.FLEE) mode = Mode.IDLE;
    }

    public String status() {
        Player bp = bp();
        if (bp == null) return "desconectada";
        return "modo=" + mode + " vida=" + (int) bp.getHealth() + " hambre=" + bp.getFoodLevel()
                + (followName != null ? " siguiendo=" + followName : "")
                + (duelist != null ? " duelo=" + duelist : "") + (alone ? " sola" : "")
                + (surv.dual() ? " totems2manos" : "")
                + (surv.fightToDeath() ? " crystal-hasta-morir" : "");
    }

    public void greet(Player pl) {
        LilithMemory.Rec r = plugin.memory().get(pl);
        r.visits++;
        if (!plugin.getConfig().getBoolean("greet-on-join", true)) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!pl.isOnline()) return;
            if (r.visits > 1) say("Bienvenido de vuelta, " + pl.getName() + ".");
            else say("Hola " + pl.getName() + ", soy Lilith. Mucho gusto.");
        }, 50L);
    }

    public void onHurt(Entity damager) {
        ServerPlayer p = p();
        Player bp = bp();
        if (p == null || bp == null) return;
        surv.noteHurt(t);
        Entity real = damager;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Entity s) real = s;

        if (real instanceof Player pl) {
            plugin.memory().get(pl).affinity -= 2;
            boolean defend = plugin.getConfig().getBoolean("defend-against-players", true)
                    && bp.getHealth() >= 7 && pl.getGameMode() == GameMode.SURVIVAL;
            if (defend) {
                say("Oye " + pl.getName() + ", eso duele. Me defiendo.");
                target = pl;
                mode = Mode.FIGHT;
                equipBest(bp);
            } else {
                say("Auch, " + pl.getName() + ".");
            }
        } else if (mode != Mode.FIGHT && mode != Mode.FLEE && bp.getHealth() >= 7) {
            target = real;
            mode = Mode.FIGHT;
            plugin.memory().encounter(real.getType().name());
            equipBest(bp);
        }
    }

    /** Se escribio "lilith" en el chat (o sigue una charla abierta): analiza el mensaje y responde. */
    public void hear(Player sp, String text) {
        Player self = bp();
        if (self == null || self.isDead()) return;
        boolean owner = plugin.isOwner(sp);
        if (!owner && !plugin.getConfig().getBoolean("chat-with-everyone", true)) return;

        long now = System.currentTimeMillis();
        long gap = owner ? 700 : plugin.getConfig().getLong("chat-cooldown-ms", 3000);
        Long last = chatCool.get(sp.getUniqueId());
        if (last != null && now - last < gap) return;
        chatCool.put(sp.getUniqueId(), now);

        LilithMemory.Rec r = plugin.memory().get(sp);
        r.talks++;
        r.affinity = Math.max(-50, Math.min(50, r.affinity + 1));
        List<String> names = plugin.chatNames();
        LilithTalk.Situation sit = situation(self, names);
        LilithTalk.Person per = person(sp, r, owner);

        LilithTalk.Cmd cmd = talk.parseCommand(text, names, duelist != null);
        if (cmd.type() != LilithTalk.CmdType.NONE) {
            if (owner) runCommand(cmd, sp);
            else sayLines(List.of(talk.refuseOrder(per, sit)));
        } else {
            List<String[]> prior = talk.history(per.name);
            LilithTalk.Reply rep = talk.reply(per, text, sit);
            applyEffects(r, rep);
            if (!rep.confident && aiReady()) askAi(per, sit, text, rep, prior, names);
            else sayLines(rep.lines);
        }
        int follow = plugin.getConfig().getInt("chat-followup-seconds", 0);
        if (follow > 0) followUntil.put(sp.getUniqueId(), now + follow * 1000L);
        plugin.memory().save(true);
    }

    /** true si esta persona hablo con ella hace poco y la charla sigue abierta (chat-followup-seconds). */
    public boolean inFollowup(Player pl) {
        Long until = followUntil.get(pl.getUniqueId());
        return until != null && System.currentTimeMillis() < until;
    }

    private void runCommand(LilithTalk.Cmd cmd, Player sp) {
        String n = sp.getName();
        String a = cmd.arg();
        switch (cmd.type()) {
            case SKIN -> {
                skin(a);
                sayLines(List.of("Me pongo la skin de " + a + "."));
            }
            case CRYSTAL_OFF -> {
                combat.crystal().setEnabled(false);
                sayLines(List.of("Ok, sin crystal pvp."));
            }
            case CRYSTAL_ON -> {
                combat.crystal().setEnabled(true);
                sayLines(List.of("Crystal pvp activado. Si me quedo sin totems, peleo igual."));
            }
            case ATTACK -> sayLines(List.of(duel(a) ? "Voy por " + a + "." : "No veo a " + a + " conectado."));
            case DUEL_SELF -> {
                duelist = n;
                sayLines(List.of("Acepto el duelo, " + n + ". Mándame un /tpa y peleamos."));
            }
            case STOP_DUEL -> {
                duelist = null;
                if (mode == Mode.FIGHT && target instanceof Player) {
                    target = null;
                    mode = base;
                }
                sayLines(List.of("gg, " + n + "."));
            }
            case FOLLOW -> {
                follow(n);
                sayLines(List.of("Voy contigo, " + n + "."));
            }
            case STAY -> {
                stay();
                sayLines(List.of("Aquí me quedo."));
            }
            case FREE -> {
                free();
                sayLines(List.of("Voy a explorar un rato."));
            }
            case SPAWN -> {
                command(plugin.getConfig().getString("spawn-command", "spawn"));
                lastSpawnCmd = t;
                sayLines(List.of("Voy al spawn."));
            }
            default -> { }
        }
    }

    private LilithTalk.Person person(Player sp, LilithMemory.Rec r, boolean owner) {
        LilithTalk.Person p = new LilithTalk.Person();
        p.name = sp.getName();
        p.nick = r.nick;
        p.owner = owner;
        p.visits = r.visits;
        p.talks = r.talks;
        p.affinity = r.affinity;
        p.wins = r.wins;
        p.losses = r.losses;
        p.likes = r.likes;
        p.notes = r.notes;
        return p;
    }

    private void applyEffects(LilithMemory.Rec r, LilithTalk.Reply rep) {
        r.affinity = Math.max(-50, Math.min(50, r.affinity + rep.affinity));
        if (rep.addLike != null) LilithMemory.addLimited(r.likes, rep.addLike, 10);
        if (rep.addNote != null) LilithMemory.addLimited(r.notes, rep.addNote, 10);
        if (rep.setNick != null) r.nick = rep.setNick;
        if (rep.forget) {
            r.likes.clear();
            r.notes.clear();
            r.nick = null;
        }
        var taught = plugin.memory().data.taught;
        if (rep.teachTopic != null && (taught.size() < 200 || taught.containsKey(rep.teachTopic))) {
            taught.put(rep.teachTopic, rep.teachAnswer);
        }
        if (rep.unteachTopic != null) taught.remove(rep.unteachTopic);
    }

    /** Foto del mundo y de Lilith para el motor de conversacion. */
    private LilithTalk.Situation situation(Player self, List<String> names) {
        LilithTalk.Situation s = new LilithTalk.Situation();
        s.names = names;
        s.owner = plugin.getConfig().getString("owner", "homuradied");
        s.aiEnabled = plugin.getConfig().getBoolean("ai.enabled", false);
        s.chatWithEveryone = plugin.getConfig().getBoolean("chat-with-everyone", true);
        s.hp = self.getHealth();
        s.food = self.getFoodLevel();
        World w = self.getWorld();
        s.worldTime = w.getTime();
        s.raining = w.hasStorm();
        s.thundering = w.isThundering();
        s.dimension = switch (w.getEnvironment()) {
            case NETHER -> "nether";
            case THE_END -> "end";
            default -> "overworld";
        };
        Location l = self.getLocation();
        s.x = l.getBlockX();
        s.y = l.getBlockY();
        s.z = l.getBlockZ();
        try {
            s.biome = l.getBlock().getBiome().getKey().getKey();
        } catch (Throwable ignored) {
            s.biome = "";
        }
        s.mode = mode.name();
        s.followName = followName;
        s.duelist = duelist;
        s.heldItem = self.getInventory().getItemInMainHand().getType().name();
        int mon = 0, pl = 0;
        for (Entity e : self.getNearbyEntities(12, 6, 12)) {
            if (e instanceof Monster) mon++;
            else if (e instanceof Player o && !o.getUniqueId().equals(LilithBody.ID)) pl++;
        }
        s.nearbyMonsters = mon;
        s.nearbyPlayers = pl;
        s.alone = alone;
        LilithMemory.Data d = plugin.memory().data;
        s.deaths = d.deaths;
        s.pops = d.pops;
        s.kills = plugin.memory().totalKills();
        s.crystalBlasts = d.crystalBlasts;
        s.anchorBlasts = d.anchorBlasts;
        s.topKiller = plugin.memory().topKiller();
        s.topKillerCount = s.topKiller == null ? 0 : plugin.memory().killedBy(s.topKiller);
        s.lastDeathSpot = d.deathSpots.isEmpty() ? null : d.deathSpots.get(d.deathSpots.size() - 1);
        List<String> online = new java.util.ArrayList<>();
        for (Player o : Bukkit.getOnlinePlayers()) if (!o.getUniqueId().equals(LilithBody.ID)) online.add(o.getName());
        s.onlineNames = online;
        s.recentChat = plugin.recentChat();
        s.encounters = d.encounters;
        s.taught = d.taught;
        return s;
    }

    // ---------------------------------------------------------------- IA opcional

    private boolean aiReady() {
        var cfg = plugin.getConfig();
        if (!cfg.getBoolean("ai.enabled", false)) return false;
        String key = cfg.getString("ai.api-key", "");
        if (key == null || key.isBlank()) return false;
        long now = System.currentTimeMillis();
        while (!aiCalls.isEmpty() && now - aiCalls.peekFirst() > 3_600_000L) aiCalls.pollFirst();
        return aiCalls.size() < cfg.getInt("ai.max-calls-per-hour", 120);
    }

    private void askAi(LilithTalk.Person per, LilithTalk.Situation sit, String text, LilithTalk.Reply fallback,
                       List<String[]> prior, List<String> names) {
        var cfg = plugin.getConfig();
        aiCalls.addLast(System.currentTimeMillis());
        String state = "vida " + (int) Math.ceil(sit.hp / 2.0) + "/10, hambre " + (int) Math.ceil(sit.food / 2.0) + "/10, modo " + sit.mode
                + ", " + sit.dimension + (sit.biome.isBlank() ? "" : ", bioma " + sit.biome) + ", hora del juego " + sit.worldTime
                + (sit.thundering ? ", tormenta" : sit.raining ? ", lluvia" : "") + ", monstruos cerca " + sit.nearbyMonsters
                + ", muertes " + sit.deaths;
        StringBuilder facts = new StringBuilder(per.owner ? "es tu dueño" : "no es tu dueño");
        if (per.nick != null) facts.append("; prefiere que le digas ").append(per.nick);
        if (!per.likes.isEmpty()) facts.append("; le gusta ").append(String.join(", ", per.likes));
        if (!per.notes.isEmpty()) facts.append("; notas: ").append(String.join("; ", per.notes));
        List<String> recent = cfg.getBoolean("ai.include-recent-chat", false) ? sit.recentChat : List.of();
        String system = LilithAI.systemPrompt(sit.owner, state, per.name, facts.toString(), talk.hints(text, names), recent);
        LilithAI.Settings st = new LilithAI.Settings(cfg.getString("ai.provider", "anthropic"), cfg.getString("ai.url", ""),
                cfg.getString("ai.api-key", ""), cfg.getString("ai.model", "claude-haiku-5-5"),
                cfg.getInt("ai.max-tokens", 150), cfg.getInt("ai.timeout-seconds", 12));
        ai.ask(st, system, prior, text).whenComplete((out, err) -> {
            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    String clean = out == null ? "" : LilithText.clean(out);
                    if (err != null || clean.isBlank()) {
                        if (err != null) plugin.getLogger().warning("IA no disponible: " + err.getMessage());
                        sayLines(fallback.lines);
                        return;
                    }
                    List<String> lines = LilithText.split(clean, 230);
                    if (lines.size() > 2) lines = new java.util.ArrayList<>(lines.subList(0, 2));
                    talk.amendLast(per.name, lines);
                    sayLines(lines);
                });
            } catch (RuntimeException ignored) {
                // el plugin se apago mientras esperaba la respuesta
            }
        });
    }

    // ------------------------------------------------------------ tpa y duelos

    /** Alguien le mando /tpa (o similar): lo acepta y, si esta activado, pelean. */
    public void acceptTpa(Player pl) {
        if (!plugin.isOwner(pl)) return; // solo acepta los tpa de su dueño
        if (!plugin.getConfig().getBoolean("accept-tpa", true)) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (bp() == null || !pl.isOnline()) return;
            command(plugin.getConfig().getString("tpa-accept-command", "tpaccept"));
            if (plugin.getConfig().getBoolean("pvp-after-tpa", true) && pl.getGameMode() == GameMode.SURVIVAL) {
                say("Dale, " + pl.getName() + ". Peleemos.");
                duelist = pl.getName();
            } else {
                say("Te acepte el tpa, " + pl.getName() + ".");
            }
        }, 30L + rnd.nextInt(30));
    }

    /** Lilith murio: si era un duelo, se termina (la derrota ya se anoto en el plugin). */
    public void onDeath(Player killer) {
        if (killer != null && duelist != null && killer.getName().equalsIgnoreCase(duelist)) {
            LilithMemory.Rec r = plugin.memory().get(killer);
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                    say("gg, " + killer.getName() + ". Vamos " + r.wins + " a " + r.losses + "."), 100L);
        }
        duelist = null;
        target = null;
        mode = base;
    }

    public void endDuel(String msg) {
        duelist = null;
        if (mode == Mode.FIGHT && target instanceof Player) {
            target = null;
            mode = base;
        }
        if (msg != null) say(msg);
    }

    public void onPlayerDeath(Player dead) {
        if (duelist != null && dead.getName().equalsIgnoreCase(duelist)) {
            LilithMemory.Rec r = plugin.memory().get(dead);
            r.wins++;
            endDuel("gg, " + dead.getName() + ". Vamos " + r.wins + " a " + r.losses + ".");
        }
    }

    /** Se acaba de gastar un totem: pone otro en la mano secundaria ahora mismo. */
    public void onTotemPop() {
        Player bp = bp();
        if (bp == null) return;
        plugin.memory().data.pops++;
        surv.onTotemPop(bp);
    }
}
