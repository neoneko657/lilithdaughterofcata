package cl.lilith;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Lo que Lilith aprende: personas, cosas (criaturas que ha visto) y sus muertes. */
public class LilithMemory {

    public static class Rec {
        public String name;
        public long first, last;
        public int visits, talks, affinity;
        public int wins, losses; // duelos de Lilith contra esta persona
        public String nick;      // como quiere que le diga
        public List<String> likes = new ArrayList<>();
        public List<String> notes = new ArrayList<>();
    }

    public static class Data {
        public Map<String, Rec> players = new HashMap<>();
        public Map<String, Integer> encounters = new HashMap<>();
        public int deaths;
        public int pops;
        public int crystalBlasts;
        public int anchorBlasts;
        public Map<String, Integer> kills = new HashMap<>();
        public Map<String, Integer> killedBy = new HashMap<>();
        public List<String> deathSpots = new ArrayList<>();
        public Map<String, String> taught = new HashMap<>(); // cosas que le ensenio el dueño: tema -> respuesta
    }

    private final LilithPlugin plugin;
    private final File file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    public Data data = new Data();

    public LilithMemory(LilithPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "memoria.json");
        load();
    }

    private void load() {
        if (!file.exists()) return;
        try {
            Data d = gson.fromJson(Files.readString(file.toPath(), StandardCharsets.UTF_8), Data.class);
            if (d != null) data = d;
            if (data.players == null) data.players = new HashMap<>();
            if (data.encounters == null) data.encounters = new HashMap<>();
            if (data.kills == null) data.kills = new HashMap<>();
            if (data.killedBy == null) data.killedBy = new HashMap<>();
            if (data.deathSpots == null) data.deathSpots = new ArrayList<>();
            if (data.taught == null) data.taught = new HashMap<>();
        } catch (Exception e) {
            plugin.getLogger().warning("No pude leer memoria.json: " + e.getMessage());
        }
    }

    /** Serializa en el hilo principal y escribe el archivo en otro hilo (no causa lag). */
    public void save(boolean async) {
        final String json = gson.toJson(data);
        Runnable write = () -> {
            try {
                file.getParentFile().mkdirs();
                Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);
            } catch (IOException e) {
                plugin.getLogger().warning("No pude guardar memoria.json: " + e.getMessage());
            }
        };
        if (async) Bukkit.getScheduler().runTaskAsynchronously(plugin, write);
        else write.run();
    }

    public Rec get(Player p) {
        Rec r = data.players.computeIfAbsent(p.getUniqueId().toString(), k -> {
            Rec n = new Rec();
            n.first = System.currentTimeMillis();
            return n;
        });
        r.name = p.getName();
        r.last = System.currentTimeMillis();
        return r;
    }

    public void encounter(String type) {
        data.encounters.merge(type, 1, Integer::sum);
    }

    public void kill(String type) {
        data.kills.merge(type, 1, Integer::sum);
    }

    public int killedBy(String type) {
        return data.killedBy.getOrDefault(type, 0);
    }

    public int totalKills() {
        int n = 0;
        for (int v : data.kills.values()) n += v;
        return n;
    }

    /** Lo que mas la ha matado, o null. */
    public String topKiller() {
        String best = null;
        int max = 0;
        for (Map.Entry<String, Integer> e : data.killedBy.entrySet()) {
            if (e.getValue() > max) {
                max = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    public void died(String by, String spot) {
        data.deaths++;
        data.killedBy.merge(by, 1, Integer::sum);
        addLimited(data.deathSpots, spot, 5);
    }

    public int encounters(String type) {
        return data.encounters.getOrDefault(type, 0);
    }

    public static void addLimited(List<String> list, String value, int max) {
        if (value.isBlank() || list.contains(value)) return;
        list.add(value);
        while (list.size() > max) list.remove(0);
    }
}
