package cl.lilith;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LilithPlugin extends JavaPlugin implements Listener {

    private LilithMemory memory;
    private LilithBrain brain;
    private LilithInvGui invGui;
    private final ArrayDeque<String> recentChat = new ArrayDeque<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        memory = new LilithMemory(this);
        brain = new LilithBrain(this);

        invGui = new LilithInvGui(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(invGui, this);
        var cmd = getCommand("lilith");
        if (cmd != null) cmd.setExecutor(new LilithCommand(this));

        if (getConfig().getBoolean("spawn-on-start", true)) {
            Bukkit.getScheduler().runTaskLater(this, this::spawnLilith, 40L);
        }
        Bukkit.getScheduler().runTaskTimer(this, () -> brain.tick(), 40L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, () -> memory.save(true), 6000L, 6000L);
    }

    @Override
    public void onDisable() {
        despawnLilith();
        memory.save(false);
    }

    public LilithMemory memory() { return memory; }
    public LilithBrain brain() { return brain; }
    public LilithInvGui invGui() { return invGui; }
    public ServerPlayer handle() { return LilithBody.handle(); }

    public boolean spawnLilith() {
        if (handle() != null) return false;
        World w = Bukkit.getWorlds().get(0);
        LilithBody.spawn(w.getSpawnLocation());
        return true;
    }

    public boolean despawnLilith() {
        Player bp = Bukkit.getPlayer(LilithBody.ID);
        if (bp == null) return false;
        bp.kick();
        return true;
    }

    /** La unica persona a la que Lilith le hace caso (config: owner). */
    public boolean isOwner(org.bukkit.command.CommandSender s) {
        return s instanceof Player p
                && p.getName().equalsIgnoreCase(getConfig().getString("owner", "homuradied"));
    }

    private static boolean isLilith(Player p) {
        return p.getUniqueId().equals(LilithBody.ID);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!isLilith(e.getPlayer())) brain.greet(e.getPlayer());
    }

    /** Nombres con los que se le puede hablar (config: chat-names). */
    public List<String> chatNames() {
        List<String> n = getConfig().getStringList("chat-names");
        return n.isEmpty() ? List.of("lilith") : n;
    }

    /** Ultimas lineas del chat (solo en memoria), para dar contexto cuando le hablan. */
    public List<String> recentChat() {
        synchronized (recentChat) {
            return new ArrayList<>(recentChat);
        }
    }

    private void rememberChat(String who, String text) {
        String line = who + ": " + (text.length() > 120 ? text.substring(0, 120) : text);
        synchronized (recentChat) {
            recentChat.addLast(line);
            while (recentChat.size() > 8) recentChat.pollFirst();
        }
    }

    /**
     * Lilith escucha el chat pero SOLO responde si alguien dice su nombre (o si hay una charla abierta con esa
     * persona, chat-followup-seconds). Las ordenes siguen siendo solo del owner; conversar puede cualquiera.
     */
    @EventHandler
    public void onChat(AsyncChatEvent e) {
        Player sender = e.getPlayer();
        if (isLilith(sender)) return;
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        if (getConfig().getBoolean("chat-listen", true)) rememberChat(sender.getName(), text);
        if (!LilithText.mentions(text, chatNames()) && !brain.inFollowup(sender)) return;
        if (!isOwner(sender) && !getConfig().getBoolean("chat-with-everyone", true)) return;
        Bukkit.getScheduler().runTask(this, () -> brain.hear(sender, text));
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent e) {
        if (e.getEntity() instanceof Player p && isLilith(p)) {
            Bukkit.getScheduler().runTask(this, () -> brain.onHurt(e.getDamager()));
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        if (!isLilith(p)) {
            brain.onPlayerDeath(p);
            return;
        }
        String by = "desconocido";
        Player killer = p.getKiller();
        if (killer != null) {
            by = "PLAYER";
            memory.get(killer).losses++;
        } else if (p.getLastDamageCause() instanceof EntityDamageByEntityEvent ede) {
            Entity d = ede.getDamager();
            if (d instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof Entity s) d = s;
            by = d.getType().name();
        } else if (p.getLastDamageCause() != null) {
            by = p.getLastDamageCause().getCause().name();
        }
        org.bukkit.Location l = p.getLocation();
        memory.died(by, l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ()
                + " (por " + by + ")");
        brain.onDeath(killer);
        // Como un jugador real: espera unos segundos y reaparece.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player bp = Bukkit.getPlayer(LilithBody.ID);
            if (bp != null && bp.isDead()) bp.spigot().respawn();
        }, 60L);
    }

    /** Cuenta lo que Lilith mata (para su memoria). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent e) {
        Player k = e.getEntity().getKiller();
        if (k != null && isLilith(k) && !(e.getEntity() instanceof Player)) {
            memory.kill(e.getEntity().getType().name());
        }
    }

    /** Un totem se gasto: vuelve a ponerse otro enseguida. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (isLilith(p)) {
            Bukkit.getScheduler().runTask(this, () -> brain.onTotemPop());
        } else {
            Bukkit.getScheduler().runTask(this, () -> brain.onEnemyPop(p));
        }
    }

    /** /tpa Lilith (o call, tpask, tpahere...): lo acepta, y si esta activado, pelean. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (isLilith(e.getPlayer()) || !isOwner(e.getPlayer())) return;
        String msg = e.getMessage();
        if (msg.length() < 2) return;
        String[] parts = msg.substring(1).trim().split("\\s+");
        if (parts.length < 2) return;
        String label = parts[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        if (!getConfig().getStringList("tpa-commands").contains(label)) return;
        if (!parts[1].equalsIgnoreCase(LilithBody.NAME)) return;
        brain.acceptTpa(e.getPlayer());
    }
}
