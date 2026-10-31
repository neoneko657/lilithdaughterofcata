package cl.lilith;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.Arrays;
import java.util.Locale;

public class LilithCommand implements CommandExecutor {

    private final LilithPlugin plugin;

    public LilithCommand(LilithPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        boolean console = s instanceof org.bukkit.command.ConsoleCommandSender;
        if (plugin.getConfig().getBoolean("owner-only-command", true) && !console && !plugin.isOwner(s)) {
            s.sendMessage("Lilith solo le hace caso a su dueño.");
            return true;
        }
        if (a.length == 0) {
            s.sendMessage("Uso: /lilith <spawn|despawn|say|cmd|skin|duel|crystal|follow|stay|free|inv|status|reload>");
            return true;
        }
        String rest = String.join(" ", Arrays.copyOfRange(a, 1, a.length));
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "spawn" -> s.sendMessage(plugin.spawnLilith() ? "Lilith aparecio." : "Lilith ya esta conectada.");
            case "despawn" -> s.sendMessage(plugin.despawnLilith() ? "Lilith se desconecto." : "Lilith no esta conectada.");
            case "say" -> {
                if (rest.isBlank()) s.sendMessage("Uso: /lilith say <mensaje>");
                else plugin.brain().say(rest);
            }
            case "cmd" -> {
                if (rest.isBlank()) s.sendMessage("Uso: /lilith cmd <comando>");
                else plugin.brain().command(rest);
            }
            case "follow" -> {
                if (rest.isBlank()) s.sendMessage("Uso: /lilith follow <jugador>");
                else {
                    plugin.brain().follow(rest.trim());
                    s.sendMessage("Lilith sigue a " + rest.trim() + ".");
                }
            }
            case "inv" -> {
                if (s instanceof org.bukkit.entity.Player pl) plugin.invGui().open(pl);
                else s.sendMessage("Este comando es solo para jugadores.");
            }
            case "skin" -> {
                if (!rest.matches("\\w{1,16}")) s.sendMessage("Uso: /lilith skin <nombre>");
                else {
                    plugin.brain().skin(rest);
                    s.sendMessage("Lilith intenta ponerse la skin de " + rest + ".");
                }
            }
            case "duel" -> s.sendMessage(plugin.brain().duel(rest.trim())
                    ? "Lilith va a pelear con " + rest.trim() + "." : "Ese jugador no esta conectado.");
            case "crystal" -> {
                boolean on = !rest.trim().equalsIgnoreCase("off");
                plugin.brain().crystal(on);
                s.sendMessage("Crystal pvp: " + (on ? "activado" : "desactivado"));
            }
            case "stay" -> plugin.brain().stay();
            case "free" -> plugin.brain().free();
            case "reload" -> {
                plugin.reloadConfig();
                s.sendMessage("Configuracion de Lilith recargada.");
            }
            case "status" -> s.sendMessage("Lilith: " + plugin.brain().status());
            default -> s.sendMessage("Subcomando desconocido.");
        }
        return true;
    }
}
