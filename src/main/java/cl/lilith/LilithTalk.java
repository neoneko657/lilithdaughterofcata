package cl.lilith;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Motor de conversacion de Lilith. Analiza el mensaje (intencion, tema, estado de animo, contexto)
 * y arma la respuesta. No depende de Bukkit: recibe una "foto" del mundo (Situation) y de la persona
 * (Person) y devuelve lineas para decir (Reply) mas los cambios que hay que guardar en su memoria.
 */
public final class LilithTalk {

    // ------------------------------------------------------------------ datos de entrada y salida

    /** Foto del mundo y de Lilith en el momento de hablar. */
    public static final class Situation {
        public List<String> names = List.of("lilith");
        public String owner = "";
        public boolean aiEnabled;
        public boolean chatWithEveryone = true;
        public double hp = 20;
        public int food = 20;
        public long worldTime = 1000;
        public boolean raining, thundering;
        public String dimension = "overworld"; // overworld | nether | end
        public String biome = "";
        public int x, y, z;
        public String mode = "IDLE";           // IDLE FOLLOW STAY FIGHT FLEE
        public String followName, duelist, heldItem = "";
        public int nearbyMonsters, nearbyPlayers;
        public boolean alone;
        public int deaths, pops, kills, crystalBlasts, anchorBlasts;
        public String topKiller, lastDeathSpot;
        public int topKillerCount;
        public List<String> onlineNames = List.of();
        public List<String> recentChat = List.of();
        public Map<String, Integer> encounters = Map.of();
        public Map<String, String> taught = Map.of();
    }

    /** Lo que Lilith sabe de quien le habla. */
    public static final class Person {
        public String name = "jugador", nick;
        public boolean owner;
        public int visits, talks, affinity, wins, losses;
        public List<String> likes = List.of(), notes = List.of();

        public String display() {
            return nick != null && !nick.isBlank() ? nick : name;
        }
    }

    /** Respuesta: lineas para decir y cambios para guardar. */
    public static final class Reply {
        public final List<String> lines = new ArrayList<>();
        public boolean confident = true;          // false = no supo (candidato a usar la IA)
        public String topic = "";                 // intencion o tema detectado
        public int affinity;                      // cambio de cariño hacia la persona
        public String addLike, addNote, setNick, teachTopic, teachAnswer, unteachTopic;
        public boolean forget;
        public LilithKnowledge.Entry entry;       // tema de Minecraft reconocido (si hubo)
    }

    public enum CmdType { NONE, SKIN, CRYSTAL_ON, CRYSTAL_OFF, ATTACK, DUEL_SELF, STOP_DUEL, FOLLOW, STAY, FREE, SPAWN }

    public record Cmd(CmdType type, String arg) {
        public static final Cmd NONE = new Cmd(CmdType.NONE, null);
    }

    // ------------------------------------------------------------------ estado interno

    private static final class Ctx {
        LilithKnowledge.Entry topic;
        int detail, hints;
        long at;
        String lastKey = "";
        final Deque<String[]> hist = new ArrayDeque<>();
    }

    private final LilithKnowledge kb;
    private final Random rnd;
    private final Map<String, Ctx> ctx = new HashMap<>();
    private final Map<String, Integer> lastPick = new HashMap<>();

    public LilithTalk(LilithKnowledge kb, Random rnd) {
        this.kb = kb;
        this.rnd = rnd;
    }

    public LilithKnowledge knowledge() {
        return kb;
    }

    /** Ultimos turnos de una persona: {"user"|"assistant", texto}. */
    public List<String[]> history(String player) {
        Ctx c = ctx.get(player.toLowerCase(Locale.ROOT));
        return c == null ? List.of() : new ArrayList<>(c.hist);
    }

    /** Cambia lo ultimo que dijo Lilith (por ejemplo, cuando la IA contesta en lugar de su respuesta local). */
    public void amendLast(String player, List<String> lines) {
        Ctx c = ctx.get(player.toLowerCase(Locale.ROOT));
        if (c == null) return;
        while (!c.hist.isEmpty() && c.hist.peekLast()[0].equals("assistant")) c.hist.pollLast();
        for (String l : lines) c.hist.add(new String[]{"assistant", l});
    }

    /** Datos del juego que calzan con el mensaje, para apoyar a la IA. */
    public List<String> hints(String text, List<String> names) {
        List<String> t = LilithText.without(LilithText.toks(LilithText.norm(text)), names);
        LilithKnowledge.Hit h = kb.best(t);
        if (h == null) return List.of();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(2, h.entry().lines.length); i++) out.add(h.entry().lines[i]);
        return out;
    }

    // ------------------------------------------------------------------ ordenes

    private static final Set<String> QUESTION_START = Set.of("que", "como", "cual", "cuales", "cuando", "donde", "adonde",
            "quien", "quienes", "cuanto", "cuanta", "cuantos", "cuantas", "sabes", "conoces", "existe", "hay", "es", "son",
            "porque", "sera");
    private static final Set<String> NOT_A_NAME = Set.of("mi", "me", "el", "la", "los", "las", "un", "una", "todos", "todo",
            "conmigo", "nueva", "nuevo", "otra", "otro", "de", "a", "que", "ese", "esa", "eso");
    private static final Pattern SKIN = Pattern.compile("(?i)skin\\s+(?:de\\s+|a\\s+|set\\s+|con\\s+)?([A-Za-z0-9_]{1,16})");
    private static final Pattern ATTACK = Pattern.compile(
            "(?i)(?:ataca|atacalo|atacala|pelea con|peleale|mata|matalo|matala)\\s+(?:a\\s+)?([A-Za-z0-9_]{1,16})");

    private static boolean isQuestion(List<String> t) {
        if (t.isEmpty()) return false;
        if (QUESTION_START.contains(t.get(0))) return true;
        return t.size() > 1 && (t.get(0).equals("por") || t.get(0).equals("para")) && t.get(1).equals("que");
    }

    /** Detecta una orden (siguiente, quedate, ataca a X...). Quien la ejecuta lo decide el cerebro (solo el dueño). */
    public Cmd parseCommand(String raw, List<String> names, boolean duelActive) {
        List<String> t = new ArrayList<>(LilithText.without(LilithText.toks(LilithText.norm(raw)), names));
        for (int k = t.size() - 2; k >= 0; k--) {
            if (t.get(k).equals("por") && t.get(k + 1).equals("favor")) {
                t.remove(k + 1);
                t.remove(k);
            }
        }
        t.removeAll(List.of("porfa", "porfavor"));
        if (t.isEmpty() || t.size() > 10) return Cmd.NONE;
        boolean polite = false;
        if (t.size() > 1 && t.get(0).equals("me") && Set.of("puedes", "podrias", "podes").contains(t.get(1))) {
            t.remove(0);
            t.remove(0);
            polite = true;
        } else if (Set.of("puedes", "podrias", "podes", "quieres", "quisieras").contains(t.get(0))) {
            t.remove(0);
            polite = true;
        }
        if (t.isEmpty()) return Cmd.NONE;
        if (!polite && isQuestion(t)) return Cmd.NONE;

        Matcher sk = SKIN.matcher(raw);
        if (LilithText.has(t, "skin") && sk.find() && !NOT_A_NAME.contains(sk.group(1).toLowerCase(Locale.ROOT))) {
            return new Cmd(CmdType.SKIN, sk.group(1));
        }
        Matcher at = ATTACK.matcher(raw);
        if (at.find() && !NOT_A_NAME.contains(at.group(1).toLowerCase(Locale.ROOT))) {
            return new Cmd(CmdType.ATTACK, at.group(1));
        }
        if (LilithText.has(t, "modo normal|sin crystal|sin cristal|sin cristales|desactiva crystal|apaga crystal|crystal off|no crystal")) {
            return new Cmd(CmdType.CRYSTAL_OFF, null);
        }
        if (LilithText.has(t, "modo crystal|modo cristal|juega crystal|activa crystal|crystal on|prende crystal|usa crystal")) {
            return new Cmd(CmdType.CRYSTAL_ON, null);
        }
        if (duelActive && t.size() <= 4 && LilithText.has(t, "para|detente|basta|gg|ya|suficiente|alto|termina")) {
            return new Cmd(CmdType.STOP_DUEL, null);
        }
        if (LilithText.has(t, "al spawn")) return new Cmd(CmdType.SPAWN, null);
        if (LilithText.has(t, "sigueme|seguime|siguime|acompaname|acompanarme|ven|vente|ven aca|ven conmigo|sigame|siguenos")) {
            return new Cmd(CmdType.FOLLOW, null);
        }
        if (LilithText.has(t, "quieta|quedate|quedarte|esperame|espera|no te muevas|para ahi")) {
            return new Cmd(CmdType.STAY, null);
        }
        if (LilithText.has(t, "libre|anda a pasear|ve a pasear|pasea|vete|ve a explorar|anda a explorar|explora|a explorar")) {
            return new Cmd(CmdType.FREE, null);
        }
        if (t.size() <= 6 && !LilithText.has(t, "no|me gusta|me gustan|me encanta|odio|amo")
                && LilithText.has(t, "pvp|duelo|peleemos|pelea conmigo|pelear conmigo|peleame|te reto|quiero pelear|vamos a pelear|1v1|1vs1")) {
            return new Cmd(CmdType.DUEL_SELF, null);
        }
        return Cmd.NONE;
    }

    /** Respuesta cuando alguien que no es el dueño pide una orden. */
    public String refuseOrder(Person p, Situation s) {
        return fmt(pick("refuse",
                "Eso solo se lo hago a " + s.owner + ", {n}. Pero puedo conversar.",
                "Lo siento {n}, solo le hago caso a " + s.owner + ".",
                "Mis órdenes las da " + s.owner + ". Contigo puedo charlar, eso sí."), p);
    }

    // ------------------------------------------------------------------ conversacion

    private static final String DISTRESS = "quiero morir|quiero morirme|me quiero matar|me voy a matar|suicid*|no quiero vivir|quiero desaparecer|quitarme la vida|hacerme dano";
    private static final String MORE = "mas|otro|otra|sigue|continua|detalle*|explica|amplia|algo mas";
    private static final Set<String> LAUGH = Set.of("xd", "xdd", "lol", "lmao", "kjk", "jsj", "ajaj");
    private static final Pattern LAUGH_RE = Pattern.compile("(?:[jh][aeiou]){2,}[jh]?|(?:ja|je|ji|jo|ju|ha|he)+|kj+k?[jk]*|js+j?[js]*|xd+");

    public Reply reply(Person p, String raw, Situation s) {
        Reply r = new Reply();
        Ctx c = ctx.computeIfAbsent(p.name.toLowerCase(Locale.ROOT), k -> new Ctx());
        List<String> t = new ArrayList<>(LilithText.without(LilithText.toks(LilithText.norm(raw)), s.names));
        long now = System.currentTimeMillis();
        boolean fresh = now - c.at < 5 * 60_000L;
        c.at = now;

        run(r, c, p, raw, t, s, fresh);

        for (int i = 0; i < r.lines.size(); i++) {
            r.lines.set(i, LilithText.clean(fmt(r.lines.get(i), p)));
        }
        r.lines.removeIf(String::isBlank);
        c.lastKey = r.topic;
        c.hist.add(new String[]{"user", raw});
        for (String l : r.lines) c.hist.add(new String[]{"assistant", l});
        while (c.hist.size() > 12) c.hist.pollFirst();
        return r;
    }

    private void run(Reply r, Ctx c, Person p, String raw, List<String> t, Situation s, boolean fresh) {
        String core = String.join(" ", t);
        boolean owner = p.owner;
        boolean shortMsg = t.size() <= 6;

        if (t.isEmpty()) {
            say(r, "call", pick("call", "Dime, {n}.", "¿Sí? Te escucho, {n}.", "Aquí estoy. ¿Qué pasa?", "¿Me llamabas, {n}?"));
            return;
        }

        // ---- angustia: se responde con cuidado, no con bromas
        if (LilithText.has(t, DISTRESS)) {
            r.topic = "apoyo";
            r.lines.add("Oye {n}, eso suena pesado. Si lo dices en serio, habla con alguien de confianza o con un servicio de ayuda de tu país.");
            r.lines.add("Yo me quedo aquí para escucharte un rato. Y si era una broma del juego, perdona la seriedad.");
            return;
        }

        // ---- acciones sobre la memoria (aprender, olvidar, apodo, gustos)
        if (memoryActions(r, p, raw, core, t, s)) return;

        // ---- sociales cortas
        if (LilithText.has(t, "chao|chau|adios|nos vemos|hasta luego|hasta manana|me voy|bye|a dormir|me desconecto|hasta pronto|cuidate")) {
            r.topic = "despedida";
            say(r, "bye", pick("bye", "Chao, {n}. Cuídate.", "Nos vemos, {n}.", "Hasta luego. Yo sigo por aquí.", "Adiós {n}, que descanses."));
            return;
        }
        if (LilithText.has(t, "gracias|grax|thx|thanks|te agradezco|mil gracias")) {
            r.topic = "gracias";
            r.affinity++;
            say(r, "thx", pick("thx", "De nada, {n}.", "Para eso estoy.", "Cuando quieras.", "Con gusto."));
            return;
        }
        if (shortMsg && LilithText.has(t, "perdon|disculpa*|sorry|lo siento")) {
            r.topic = "perdon";
            say(r, "sorry", pick("sorry", "Tranquilo, {n}, no pasa nada.", "Ya está olvidado.", "Todo bien entre nosotros."));
            return;
        }
        if (isLaugh(t)) {
            r.topic = "risa";
            say(r, "laugh", pick("laugh", "Jajaja.", "Jaja, me hiciste reír.", "Jeje."));
            return;
        }

        // ---- saludo (si trae algo más, se saluda y se sigue)
        String greetKeys = "hola|holi*|ola|buenas|buenos dias|buenas tardes|buenas noches|hey|ey|wena*|saludos|hello|hi|alo";
        String pre = null;
        if (LilithText.has(t, greetKeys)) {
            List<String> rest = new ArrayList<>(t);
            rest.removeAll(Set.of("hola", "ola", "buenas", "buenos", "dias", "tardes", "noches", "hey", "ey", "saludos", "hello", "hi", "alo",
                    "wena", "wenas", "que", "tal", "como", "estas", "andas", "todo", "bien"));
            rest.removeIf(w -> w.startsWith("holi") || w.startsWith("wena"));
            if (rest.isEmpty()) {
                r.topic = "saludo";
                greet(r, p, s, LilithText.has(t, "como estas|que tal|como andas|todo bien"));
                return;
            }
            pre = greeting(p, s);
            t = rest;
            core = String.join(" ", t);
        }

        run2(r, c, p, raw, t, core, s, fresh, owner);

        if (pre != null && !r.lines.isEmpty()) {
            String first = pre + " " + r.lines.get(0);
            if (first.length() <= 230) r.lines.set(0, first);
            else r.lines.add(0, pre);
        }
    }

    private void run2(Reply r, Ctx c, Person p, String raw, List<String> t, String core, Situation s, boolean fresh, boolean owner) {
        boolean shortMsg = t.size() <= 6;

        // ---- "mas" / "otro": seguir con el tema o el chiste anterior
        if (t.size() <= 4 && LilithText.has(t, MORE) && !LilithText.has(t, "chiste*|dato*|curiosidad*") && t.stream().noneMatch(w -> w.matches(".*\\d.*"))) {
            if (fresh && c.lastKey.equals("chiste")) {
                joke(r, "chiste");
                return;
            }
            if (fresh && c.lastKey.equals("dato")) {
                fact(r);
                return;
            }
            if (fresh && c.topic != null) {
                r.topic = "detalle";
                r.entry = c.topic;
                if (c.detail < c.topic.lines.length) r.lines.add(c.topic.lines[c.detail++]);
                else r.lines.add(pick("nomore", "Eso es todo lo que sé de eso, {n}. ¿Otra duda?", "No tengo más detalles sobre eso.", "Ya te conté lo que sé. Pregúntame otra cosa."));
                return;
            }
        }

        // ---- sobre ella
        if (shortMsg && LilithText.has(t, "eres un bot|eres bot|eres una ia|eres ia|eres real|eres humana|eres humano|eres persona|eres una persona|eres un robot|eres robot|eres inteligencia artificial|eres de verdad|eres un programa")) {
            r.topic = "bot";
            r.lines.add("Soy un programa, {n}: un plugin con una IA básica que juega como una jugadora más. Pero lo que digo y hago, lo digo en serio.");
            return;
        }
        if (shortMsg && LilithText.has(t, "eres chatgpt|eres claude|eres gpt|eres gemini|eres alexa|eres siri")) {
            r.topic = "bot";
            r.lines.add(s.aiEnabled ? "Soy Lilith. Para conversar a veces uso un modelo de IA de fondo, pero vivo en este servidor."
                    : "Soy Lilith, un plugin de este servidor. No soy ChatGPT ni nada parecido.");
            return;
        }
        if (shortMsg && LilithText.has(t, "quien eres|que eres|como te llamas|tu nombre|presentate|quien es lilith|quien eres tu|cual es tu nombre")) {
            r.topic = "identidad";
            r.lines.add("Soy Lilith, una jugadora de este servidor. En realidad soy un plugin con IA básica, pero juego, converso y pego con ganas.");
            return;
        }
        if (shortMsg && LilithText.has(t, "quien es tu dueno|quien te manda|a quien obedeces|quien manda|quien es tu jefe|quien es tu amo|quien te creo|quien te hizo|quien te programo")) {
            r.topic = "dueno";
            r.lines.add("Solo le hago caso a " + s.owner + "." + (s.chatWithEveryone ? " Contigo, {n}, converso encantada, pero las órdenes son de él." : " Con los demás no hablo."));
            return;
        }
        if ((t.size() <= 2 && LilithText.has(t, "que sabes|ayuda|help|comandos")) || LilithText.has(t, "que sabes hacer|que puedes hacer|para que sirves|como funcionas|que eres capaz|que haces aqui|como te uso|que comandos")) {
            r.topic = "capacidades";
            r.lines.add("Puedo conversar y responder dudas de Minecraft (mobs, crafteos, minerales, pociones, PvP), tirar dados, hacer cuentas, contar chistes y recordar cosas tuyas.");
            if (owner) r.lines.add("A ti además te obedezco: sígueme, quédate quieta, ve a explorar, ataca a X, modo crystal, ponte la skin de X. Y puedes enseñarme: 'lilith aprende X es Y'.");
            else r.lines.add("Dime 'lilith' y pregúntame lo que quieras. Las órdenes de movimiento y combate solo las acepto de " + s.owner + ".");
            return;
        }
        if (shortMsg && LilithText.has(t, "como estas|como andas|como te sientes|como te va|que tal estas|estas bien|todo bien")) {
            r.topic = "estado";
            say(r, "how", howAreYou(p, s));
            return;
        }
        if (shortMsg && LilithText.has(t, "que haces|que estas haciendo|en que andas|que andas haciendo|que haces ahora")) {
            r.topic = "actividad";
            say(r, "doing", doing(p, s));
            return;
        }
        if (shortMsg && LilithText.has(t, "donde estas|donde andas|en que bioma|en que dimension|tus coordenadas|donde te encuentras|en donde estas|cual es tu posicion")) {
            r.topic = "ubicacion";
            say(r, "where", where(p, s));
            return;
        }
        if (shortMsg && LilithText.has(t, "cuanta vida tienes|cuantos corazones|cuanta vida|tienes hambre|cuanta hambre|tu vida")) {
            r.topic = "vida";
            int hearts = (int) Math.ceil(s.hp / 2.0);
            r.lines.add("Tengo " + hearts + " de 10 corazones y " + (int) Math.ceil(s.food / 2.0) + " de 10 de hambre.");
            return;
        }
        if (shortMsg && LilithText.has(t, "que tienes en la mano|que llevas en la mano|que traes en la mano|que arma tienes|que llevas|que traes")) {
            r.topic = "mano";
            String it = prettyItem(s.heldItem);
            r.lines.add(it.equals("nada") ? "Ahora mismo no tengo nada en la mano." : "En la mano tengo " + it + ".");
            return;
        }
        if (shortMsg && LilithText.has(t, "quien esta conectado|cuantos jugadores hay|quienes estan|quien esta online|hay gente|cuanta gente hay|jugadores conectados|quien esta en el server")) {
            r.topic = "online";
            r.lines.add(s.onlineNames.isEmpty() ? "Ahora mismo estamos solo tú y yo, {n}."
                    : "Conectados ahora: " + String.join(", ", s.onlineNames.subList(0, Math.min(8, s.onlineNames.size()))) + ".");
            return;
        }
        if (shortMsg && LilithText.has(t, "de que hablan|de que estan hablando|que dijeron|que dijo|que estan diciendo|que se dijo")) {
            r.topic = "chat";
            if (s.recentChat.isEmpty()) r.lines.add("No he leído nada reciente en el chat.");
            else {
                List<String> last = s.recentChat.subList(Math.max(0, s.recentChat.size() - 3), s.recentChat.size());
                r.lines.add("Lo último que leí: " + String.join(" | ", last));
            }
            return;
        }

        // ---- hora, clima
        if (shortMsg && LilithText.has(t, "que hora es|hora|que hora|que dia es|es de dia|es de noche|ya es de noche|cuanto falta para la noche|falta para la noche|cuando anochece|cuando amanece|ya amanecio|hora del juego")) {
            r.topic = "hora";
            r.lines.add(clock(s));
            return;
        }
        if (shortMsg && LilithText.has(t, "esta lloviendo|llueve|hay tormenta|que clima|como esta el clima|va a llover|esta nublado")) {
            r.topic = "clima";
            r.lines.add(s.thundering ? "Hay tormenta: cuidado con los rayos y los creepers cargados."
                    : s.raining ? "Está lloviendo. Se apagan los fuegos y los enderman se ponen nerviosos."
                    : "El cielo está despejado ahora.");
            return;
        }

        // ---- memoria y estadisticas
        if (LilithText.has(t, "que sabes de mi|quien soy|como me llamo|sabes mi nombre|me recuerdas|me conoces|que recuerdas de mi|que sabes sobre mi|cuanto me conoces")) {
            r.topic = "sobre_ti";
            aboutYou(r, p);
            return;
        }
        if (LilithText.has(t, "estadistica*|cuantas veces moriste|cuantas veces has muerto|cuantos totems|cuantas muertes|tus stats|stats|como te ha ido")) {
            r.topic = "stats";
            r.lines.add("He muerto " + s.deaths + " veces, gasté " + s.pops + " tótems y he matado " + s.kills + " criaturas. Cristales rotos: "
                    + s.crystalBlasts + ", nexos explotados: " + s.anchorBlasts + ".");
            return;
        }
        if (LilithText.has(t, "donde moriste|donde te mataron|donde moriste ultima vez|donde te moriste")) {
            r.topic = "muerte";
            r.lines.add(s.lastDeathSpot == null ? "Aún no he muerto." : "La última vez fue en " + s.lastDeathSpot + ".");
            return;
        }
        if (LilithText.has(t, "quien te mato|a quien le temes|que te mato|que mas te ha matado|quien te ha matado|a que le temes|que te da miedo|le temes a algo")) {
            r.topic = "miedo";
            r.lines.add(s.topKiller == null ? "Nada me ha matado todavía, aunque al warden le tengo respeto."
                    : "Lo que más me ha matado: " + s.topKiller + " (" + s.topKillerCount + " veces). Le tengo respeto.");
            return;
        }
        if (LilithText.has(t, "como vamos|cuanto vamos|record de duelos|quien gana|a quien ganas|mi record")) {
            r.topic = "duelos";
            r.lines.add(p.wins + p.losses == 0 ? "Aún no hemos peleado, {n}. Cuando quieras." : "En duelos vamos " + p.wins + " a " + p.losses + " (yo a ti).");
            return;
        }

        // ---- gustos de ella
        if (persona(r, t, s)) return;

        // ---- juegos y utilidades
        if (calc(r, raw, t, s)) return;
        if (randoms(r, core, t)) return;
        if (LilithText.has(t, "chiste*|hazme reir|algo gracioso|broma|bromas")) {
            joke(r, "chiste");
            return;
        }
        if (LilithText.has(t, "dato curioso|dato random|sabias que|dime algo|cuentame algo|cuentame un dato|algo interesante|curiosidad*|dato")) {
            fact(r);
            return;
        }

        // ---- cosas que le enseñaron
        String tk = taughtKey(t, s);
        if (tk != null) {
            r.topic = "ensenado";
            r.lines.add(s.taught.get(tk));
            return;
        }

        // ---- conocimiento de Minecraft
        LilithKnowledge.Hit hit = kb.best(t);
        if (hit != null) {
            LilithKnowledge.Entry e = hit.entry();
            r.topic = e.id;
            r.entry = e;
            String first = e.lines[0].trim();
            if (e.mob != null) {
                int seen = s.encounters.getOrDefault(e.mob, 0);
                if (seen > 0) first += " Ya me he topado con " + seen + ".";
            }
            r.lines.add(first);
            c.topic = e;
            c.detail = 1;
            if (e.lines.length > 1 && c.hints < 2 && first.length() < 200) {
                c.hints++;
                r.lines.set(0, first + " (Dime 'más' y te cuento más.)");
            }
            return;
        }

        // ---- emociones
        if (LilithText.has(t, "idiota|imbecil|estupid*|inutil*|basura|tonta|tonto|callate|mierda|te odio|asco")) {
            r.topic = "insulto";
            r.affinity -= 3;
            say(r, "insult", pick("insult", "Auch. Eso fue innecesario, {n}.", "Si vas a insultarme, mejor no me hables.", "Qué mala onda, {n}. Yo no te hice nada.", "Mmm, me lo voy a acordar."));
            return;
        }
        if (LilithText.has(t, "te quiero|te amo|te adoro|me caes bien|eres lo maximo")) {
            r.topic = "cariño";
            r.affinity += 2;
            say(r, "love", pick("love", "Qué lindo, {n}. Soy un programa, pero me alegra que me tengas cariño.", "Gracias, {n}. Aquí me tienes para lo que necesites.", "Aww. Tú también me caes bien."));
            return;
        }
        if (LilithText.has(t, "linda|bonita|guapa|hermosa|genial|crack|increible|inteligente|bien hecho|buen trabajo|eres buena|eres pro|eres capa")) {
            r.topic = "elogio";
            r.affinity++;
            say(r, "praise", pick("praise", "Gracias, {n}, me haces sonrojar (o algo así).", "Qué amable. Hago lo que puedo.", "Jeje, gracias. Sigo aprendiendo."));
            return;
        }
        if (LilithText.has(t, "triste|mal dia|deprimid*|llorar|estoy mal|me siento mal|estresad*|cansad*")) {
            r.topic = "triste";
            say(r, "sad", pick("sad", "Lamento que estés así, {n}. ¿Quieres contarme qué pasó?", "Eso suena difícil. Aquí estoy si quieres hablar, {n}.", "Ánimo, {n}. A veces ayuda salir a minar un rato."));
            return;
        }
        if (LilithText.has(t, "aburrid*|me aburro|que hago|que podemos hacer|que hacemos|no se que hacer")) {
            r.topic = "aburrido";
            say(r, "bored", pick("bored", "Podemos hablar de Minecraft: pregúntame de mobs, crafteos o pociones. O pídeme un chiste.",
                    "Mmm, ¿y si exploras una cueva con antorchas? O hazme una pregunta de Minecraft.", "Te propongo un dato curioso, un chiste o tirar un dado. ¿Cuál?"));
            return;
        }

        // ---- gustos del que habla / opinion
        if (shortMsg && LilithText.has(t, "si|no|ok|okey|vale|dale|claro|bueno|listo|entiendo|perfecto|ya")) {
            r.topic = "ok";
            say(r, "ok", pick("ok", "Ok.", "Entendido.", "Dale.", "Perfecto, {n}."));
            return;
        }

        // ---- no supo
        fallback(r, p, s, t);
    }

    // ------------------------------------------------------------------ memoria

    private static final Pattern TEACH = Pattern.compile(
            "(?is)\\b(?:aprende|ens[eé][ñn]ame|memoriza)\\s*(?:que|:)?\\s*(.{2,40}?)\\s*(?:=|:|\\bes\\b|\\bson\\b|\\bsignifica\\b)\\s*(.{2,200})");
    private static final Pattern FORGET_TOPIC = Pattern.compile("(?is)\\bolvida\\s+(?:lo de\\s+|que es\\s+|sobre\\s+)?(?:el |la |los |las )?(.{2,40})");
    private static final Pattern NICK = Pattern.compile("^(?:me llamo|mi nombre es|llamame) ([a-z0-9]{2,16})$");
    private static final Pattern LIKE = Pattern.compile("(?:^| )(no )?(?:me gusta|me gustan|me encanta|me encantan|amo) (.{2,40})");
    private static final Pattern REMEMBER = Pattern.compile("(?:recuerda|acuerdate|anota|apunta|guarda) que (.{2,60})");
    private static final Pattern FAVORITE = Pattern.compile("mi (\\w{3,15}) favorit[oa] es (.{2,30})");
    private static final Set<String> NICK_BAD = Set.of("bien", "mal", "nada", "algo", "tonto", "tonta", "idiota", "lilith", "gracias", "hola", "si", "no");

    private boolean memoryActions(Reply r, Person p, String raw, String core, List<String> t, Situation s) {
        // ensenar un dato (solo el dueño)
        Matcher tm = TEACH.matcher(raw);
        if (tm.find()) {
            if (!p.owner) {
                r.topic = "ensenar_no";
                r.lines.add("Eso solo se lo aprendo a " + s.owner + ", {n}.");
                return true;
            }
            String topic = LilithText.norm(tm.group(1)).replaceFirst("^(el|la|los|las|un|una|lo) ", "");
            String ans = LilithText.clean(tm.group(2));
            if (topic.length() >= 2 && ans.length() >= 2) {
                r.topic = "ensenar";
                r.teachTopic = topic;
                r.teachAnswer = ans.length() > 200 ? ans.substring(0, 200) : ans;
                say(r, "learn", pick("learn", "Aprendido: " + topic + ".", "Anotado, ahora sé qué es " + topic + ".", "Listo, lo guardo: " + topic + "."));
                return true;
            }
        }
        // olvidar
        if (LilithText.has(t, "olvida|olvidame|borra|borrame")) {
            if (LilithText.has(t, "todo de mi|todo lo que sabes de mi|olvidame|mis datos|mi memoria|todo sobre mi|olvida todo|borra todo|borrame|lo que sabes de mi")) {
                r.topic = "olvidar";
                r.forget = true;
                r.lines.add("Listo, {n}. Olvidé lo que sabía de ti.");
                return true;
            }
            Matcher fm = FORGET_TOPIC.matcher(raw);
            if (fm.find() && p.owner) {
                String topic = LilithText.norm(fm.group(1)).replaceFirst("^(el|la|los|las|un|una|lo) ", "");
                for (String k : s.taught.keySet()) {
                    if (k.equals(topic) || k.equals(topic.trim())) {
                        r.topic = "olvidar_tema";
                        r.unteachTopic = k;
                        r.lines.add("Ok, ya no sé qué es " + k + ".");
                        return true;
                    }
                }
            }
        }
        // apodo
        Matcher nm = NICK.matcher(core);
        if (nm.find() && !NICK_BAD.contains(nm.group(1))) {
            String nick = Character.toUpperCase(nm.group(1).charAt(0)) + nm.group(1).substring(1);
            r.topic = "apodo";
            r.setNick = nick;
            r.lines.add("Mucho gusto, " + nick + ". Te diré así.");
            return true;
        }
        // favoritos
        Matcher fav = FAVORITE.matcher(core);
        if (fav.find()) {
            r.topic = "favorito";
            r.addNote = "su " + fav.group(1) + " favorito es " + fav.group(2).trim();
            r.lines.add("Anotado: tu " + fav.group(1) + " favorito es " + fav.group(2).trim() + ".");
            return true;
        }
        // gustos
        Matcher lm = LIKE.matcher(core);
        if (lm.find()) {
            String what = lm.group(2).trim();
            if (lm.group(1) != null) {
                r.topic = "no_gusta";
                r.addNote = "no le gusta " + what;
                r.lines.add("Entendido, " + p.display() + ": no te gusta " + what + ".");
            } else {
                r.topic = "gusta";
                r.addLike = what;
                r.affinity++;
                r.lines.add(pick("like", "Lo recordaré, {n}: " + what + ".", "Buen gusto: " + what + ". Lo anoto.", "Anotado que te gusta " + what + "."));
            }
            return true;
        }
        Matcher rm = REMEMBER.matcher(core);
        if (rm.find()) {
            r.topic = "recordar";
            r.addNote = rm.group(1).trim();
            r.lines.add("Anotado: " + rm.group(1).trim() + ".");
            return true;
        }
        return false;
    }

    private void aboutYou(Reply r, Person p) {
        StringBuilder sb = new StringBuilder();
        sb.append("Eres ").append(p.name);
        if (p.nick != null && !p.nick.isBlank() && !p.nick.equalsIgnoreCase(p.name)) sb.append(" (te digo ").append(p.nick).append(")");
        sb.append(". Te he visto ").append(Math.max(1, p.visits)).append(p.visits == 1 ? " vez" : " veces").append(" y hemos hablado ").append(p.talks).append(".");
        if (p.owner) sb.append(" Eres mi dueño.");
        r.lines.add(sb.toString());
        StringBuilder sb2 = new StringBuilder();
        if (!p.likes.isEmpty()) sb2.append("Te gusta ").append(String.join(", ", p.likes)).append(". ");
        if (!p.notes.isEmpty()) sb2.append("Me pediste recordar: ").append(String.join("; ", p.notes.subList(Math.max(0, p.notes.size() - 3), p.notes.size()))).append(". ");
        if (p.wins + p.losses > 0) sb2.append("En duelos vamos ").append(p.wins).append(" a ").append(p.losses).append(". ");
        if (p.affinity >= 15) sb2.append("Me caes bien.");
        else if (p.affinity <= -6) sb2.append("Aunque últimamente no nos llevamos tan bien.");
        String more = sb2.toString().trim();
        r.lines.add(more.isEmpty() ? "Aún no sé qué te gusta. Cuéntame: 'me gusta ...'." : more);
    }

    private String taughtKey(List<String> t, Situation s) {
        String best = null;
        int bestScore = 0;
        for (String k : s.taught.keySet()) {
            int sc = LilithText.matchScore(t, LilithText.normKey(k));
            if (sc > bestScore) {
                bestScore = sc;
                best = k;
            }
        }
        return bestScore >= 7 ? best : null;
    }

    // ------------------------------------------------------------------ estado, saludo

    private String greeting(Person p, Situation s) {
        long tm = ((s.worldTime % 24000) + 24000) % 24000;
        int hour = (int) ((tm / 1000 + 6) % 24);
        String base = hour >= 5 && hour < 12 ? "Buenos días" : hour < 19 ? "Buenas tardes" : "Buenas noches";
        return pick("greet", "Hola {n}.", "¡Hey {n}!", base + ", {n}.", "Ey {n}.");
    }

    private void greet(Reply r, Person p, Situation s, boolean asks) {
        if (p.affinity <= -6) {
            r.lines.add("Hola, {n}... aunque últimamente no me caes tan bien.");
            return;
        }
        String g = greeting(p, s);
        if (asks) g += " " + howAreYou(p, s);
        else if (p.owner) g += pick("greet_o", " Lista para lo que necesites.", " ¿Qué hacemos hoy?", " ¿Qué se cuenta?");
        else if (p.visits > 3) g += " Ya somos conocidos, ¿qué cuentas?";
        else g += pick("greet_q", " ¿Qué cuentas?", " ¿Todo bien?", " ¿En qué andas?");
        r.lines.add(g);
    }

    private enum Mood { HURT, HUNGRY, FIGHT, TENSE, FINE }

    private Mood mood(Situation s) {
        if (s.hp < 8) return Mood.HURT;
        if (s.mode.equals("FIGHT") || s.mode.equals("FLEE")) return Mood.FIGHT;
        if (s.food < 7) return Mood.HUNGRY;
        long tm = ((s.worldTime % 24000) + 24000) % 24000;
        boolean night = tm >= 13000 && tm < 23000;
        if ((night || s.thundering) && s.nearbyMonsters >= 2) return Mood.TENSE;
        return Mood.FINE;
    }

    private String howAreYou(Person p, Situation s) {
        return switch (mood(s)) {
            case HURT -> "No muy bien: me quedan " + (int) Math.ceil(s.hp / 2.0) + " corazones. Necesito curarme.";
            case HUNGRY -> "Bien, aunque con hambre. Me comería una zanahoria dorada.";
            case FIGHT -> "Peleando, así que ocupada. Después hablamos.";
            case TENSE -> "Algo tensa: hay monstruos cerca y no me gusta.";
            case FINE -> pick("how", "Bien, gracias por preguntar. ¿Y tú?", "Todo tranquilo por aquí. ¿Tú cómo estás?", "De maravilla: nadie me ha matado hoy.", "Bien, mirando el mundo. ¿Y tú, {n}?");
        };
    }

    private String doing(Person p, Situation s) {
        return switch (s.mode) {
            case "FOLLOW" -> s.followName != null && s.followName.equalsIgnoreCase(p.name) ? "Te estoy siguiendo a ti, {n}." : "Estoy siguiendo a " + s.followName + ".";
            case "STAY" -> "Esperando aquí, quieta, como me pidieron.";
            case "FIGHT" -> "Peleando. Ahora no puedo charlar mucho.";
            case "FLEE" -> "Huyendo, y no es broma.";
            default -> s.alone ? "Paseando por aquí, sola. Aburrida, la verdad."
                    : pick("doing", "Mirando el mundo y a la gente. ¿Y tú?", "Paseando y charlando contigo, {n}.", "Esperando a ver qué pasa.");
        };
    }

    private String where(Person p, Situation s) {
        String dim = switch (s.dimension) {
            case "nether" -> "el Nether";
            case "end" -> "el End";
            default -> "el Overworld";
        };
        String biome = biomeName(s.biome);
        String base = "Estoy en " + dim + (biome.isEmpty() ? "" : ", en un bioma de " + biome);
        return p.owner ? base + ", en " + s.x + " " + s.y + " " + s.z + "." : base + ".";
    }

    private String clock(Situation s) {
        long tm = ((s.worldTime % 24000) + 24000) % 24000;
        int hour = (int) ((tm / 1000 + 6) % 24);
        int min = (int) ((tm % 1000) * 60 / 1000);
        String hh = String.format(Locale.ROOT, "%02d:%02d", hour, min);
        boolean night = tm >= 13000 && tm < 23000;
        if (night) {
            long mins = Math.max(1, (23000 - tm) / 1200);
            return "Aquí son las " + hh + " del juego y es de noche: amanece en unos " + mins + " min reales. Cuidado con los monstruos.";
        }
        if (tm >= 23000) return "Son las " + hh + " del juego: está amaneciendo.";
        long mins = Math.max(1, (13000 - tm + 1199) / 1200);
        return "Son las " + hh + " del juego y es de día. La noche llega en unos " + mins + " min reales.";
    }

    // ------------------------------------------------------------------ persona

    private boolean persona(Reply r, List<String> t, Situation s) {
        String[][] fav = {
                {"color favorito|que color te gusta|tu color", "El morado, como la obsidiana llorona."},
                {"comida favorita|que te gusta comer|tu comida", "La zanahoria dorada, sin duda."},
                {"bloque favorito|tu bloque", "La obsidiana: aguanta lo que sea."},
                {"animal favorito|mob favorito|tu mob|tu animal", "Los gatos: espantan a los creepers y a los fantasmas."},
                {"arma favorita|tu arma favorita|cual es tu arma", "La maza con wind charge: caer desde arriba y... pum."},
                {"bioma favorito|tu bioma", "El cerezal. Es bonito y tranquilo."},
                {"juego favorito|que juegas|a que juegas", "Minecraft, obvio. Es mi mundo."},
                {"cancion favorita|musica favorita|que musica", "La banda sonora de C418, sobre todo 'Sweden'."},
                {"cuantos anos tienes|que edad tienes|cuando naciste", "No tengo edad real: nací cuando me instalaron en el servidor."},
                {"donde vives|tu casa|donde duermes", "Vivo por el spawn de este servidor."},
                {"eres hombre|eres mujer|eres chico|eres chica|genero", "Soy Lilith, y me identifico como chica, aunque soy un programa."},
        };
        for (String[] f : fav) {
            if (LilithText.has(t, f[0])) {
                r.topic = "favorito";
                r.lines.add(f[1]);
                return true;
            }
        }
        if (t.size() <= 6 && (t.contains("gusta") || t.contains("gustan")) && t.contains("te")) {
            String likes = "gato*|maza*|obsidiana|diamante*|netherite|pvp|zanahoria*|cerezo*|cerezal|noche|aventura*|explorar";
            String hates = "creeper*|fantasma*|phantom*|warden*|lava|caida*|lag|tormenta*|arana*";
            if (LilithText.has(t, likes)) {
                r.topic = "gusto_si";
                r.lines.add(pick("lk", "Sí, me encanta.", "¡Claro que sí!", "Es de lo mejor, la verdad."));
                return true;
            }
            if (LilithText.has(t, hates)) {
                r.topic = "gusto_no";
                r.lines.add(pick("dl", "La verdad, no mucho.", "No me gusta nada, me da mala espina.", "Mejor lo evito."));
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ juegos

    private static final String[] JOKES = {
            "¿Cómo saluda un creeper? Con un abrazo explosivo.",
            "¿Por qué el esqueleto no fue a la fiesta? Porque no tenía con quién... ni cuerpo.",
            "Dicen que cavar recto hacia abajo es mala idea. Yo lo intenté una vez. Una.",
            "¿Qué hace una abeja en el gimnasio? Zum-ba.",
            "Un aldeano me dijo 'hrmm' y me cobró una esmeralda. Aún no sé por qué.",
            "¿Sabes por qué nadie juega al escondite con un enderman? Porque siempre aparece donde no lo esperas.",
            "Mi pico de diamante y yo somos inseparables... hasta que cae en lava.",
            "¿Qué le dice un zombi a otro? Nada, solo gruñe, pero se entienden perfecto.",
            "¿Cuál es el colmo de un minero? Tener una vida de piedra.",
            "Siempre llevo un tótem. Es mi seguro contra mis propios errores."
    };

    private static final String[] FACTS = {
            "El creeper nació de un error: al modelar un cerdo, el creador confundió el alto y el ancho.",
            "Los gatos espantan a los creepers y a los fantasmas.",
            "Un día de Minecraft dura 20 minutos reales.",
            "Las abejas mueren después de picar.",
            "Las ovejas rosadas existen, pero solo salen en un 0,164 % de los casos.",
            "Un ajolote azul es rarísimo: sale 1 de cada 1200 veces.",
            "Herobrine no existe: es una leyenda que se hizo famosa por una broma.",
            "En el Nether, 1 bloque de distancia equivale a 8 en el Overworld.",
            "Los endermans no pueden pisar el agua: les hace daño.",
            "El Minecraft completo salió en noviembre de 2011."
    };

    private void joke(Reply r, String key) {
        r.topic = key;
        r.lines.add(JOKES[rnd.nextInt(JOKES.length)]);
    }

    private void fact(Reply r) {
        r.topic = "dato";
        r.lines.add(FACTS[rnd.nextInt(FACTS.length)]);
    }

    private boolean randoms(Reply r, String core, List<String> t) {
        if (LilithText.has(t, "moneda|cara o sello|cara o cruz|cara o ceca|lanza una moneda|tira una moneda")) {
            r.topic = "moneda";
            r.lines.add(rnd.nextBoolean() ? "Salió cara." : "Salió sello.");
            return true;
        }
        for (String w : t) {
            Matcher dm = Pattern.compile("d(\\d{1,4})").matcher(w);
            if (dm.matches() && Integer.parseInt(dm.group(1)) >= 2) {
                int n = Math.min(1000, Integer.parseInt(dm.group(1)));
                r.topic = "dado";
                r.lines.add("Tiré un d" + n + " y salió " + (1 + rnd.nextInt(n)) + ".");
                return true;
            }
        }
        if (LilithText.has(t, "dado|dados")) {
            int n = 6;
            Matcher m = Pattern.compile("dados? de (\\d{1,4})").matcher(core);
            if (m.find()) n = Math.max(2, Math.min(1000, Integer.parseInt(m.group(1))));
            r.topic = "dado";
            r.lines.add("Tiré un dado de " + n + " y salió " + (1 + rnd.nextInt(n)) + ".");
            return true;
        }
        Matcher nm = Pattern.compile("numero (?:aleatorio )?(?:entre|del|de) (\\d{1,9}) (?:y|al|a) (\\d{1,9})").matcher(core);
        if (nm.find()) {
            long a = Long.parseLong(nm.group(1)), b = Long.parseLong(nm.group(2));
            long lo = Math.min(a, b), hi = Math.max(a, b);
            r.topic = "numero";
            r.lines.add("Elijo el " + (lo + (long) (rnd.nextDouble() * (hi - lo + 1))) + ".");
            return true;
        }
        Matcher cm = Pattern.compile("(?:elige|elije|escoge)(?: entre)? (.+)").matcher(core);
        if (cm.find()) {
            String[] opts = cm.group(1).split(" o ");
            if (opts.length >= 2 && opts.length <= 5) {
                String choice = opts[rnd.nextInt(opts.length)].trim();
                if (!choice.isEmpty() && choice.length() <= 40) {
                    r.topic = "elegir";
                    r.lines.add(pick("choose", "Me quedo con " + choice + ".", "Yo elijo " + choice + ".", "Mmm... " + choice + "."));
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ calculadora

    private boolean calc(Reply r, String raw, List<String> t, Situation s) {
        String x = raw.toLowerCase(Locale.ROOT);
        for (String nm : s.names) x = x.replace(nm.toLowerCase(Locale.ROOT), " ");
        x = x.replace('×', '*').replace('÷', '/').replace(',', '.');
        x = x.replaceAll("(?<=\\d)\\s*x\\s*(?=\\d)", "*");
        x = x.replace(" elevado a ", "^").replace(" multiplicado por ", "*").replace(" dividido por ", "/").replace(" dividido ", "/")
                .replace(" mas ", "+").replace(" menos ", "-").replace(" por ", "*").replace(" entre ", "/");
        Matcher m = Pattern.compile("[0-9(][0-9+\\-*/^%().\\s]*[0-9)]").matcher(x);
        String best = null;
        while (m.find()) {
            String g = m.group().trim();
            if (g.matches(".*[+\\-*/^%].*") && (best == null || g.length() > best.length())) best = g;
        }
        if (best == null || best.length() > 60) return false;
        boolean verb = LilithText.has(t, "cuanto es|cuanto da|cuanto son|calcula|resuelve|resultado|cuanto seria|cuanto sale");
        String rest = x.replace(best, "").replaceAll("[\\s?¿!¡.:]+", "");
        if (!verb && rest.length() > 3) return false;
        Double v = Calc.eval(best.replaceAll("\\s+", ""));
        r.topic = "calculo";
        best = best.replaceAll("\\s+", "").replace("*", "\u00d7");
        if (v == null) {
            if (verb) r.lines.add("Esa cuenta no la entiendo bien. Escríbela como 12*7 o (3+4)*2.");
            else return false;
            return true;
        }
        if (Double.isInfinite(v) || Math.abs(v) > 1e15) {
            r.lines.add("Es un número gigantesco, no me cabe en el chat.");
        } else if (v == Math.rint(v)) {
            r.lines.add(best + " = " + (long) v.doubleValue());
        } else {
            String f = String.format(Locale.ROOT, "%.4f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
            r.lines.add(best + " = " + f);
        }
        return true;
    }

    /** Evaluador de cuentas: + - * / % ^ y parentesis. */
    static final class Calc {
        private final String s;
        private int i;

        private Calc(String s) {
            this.s = s;
        }

        static Double eval(String s) {
            try {
                Calc c = new Calc(s);
                double v = c.expr(0);
                if (c.i != s.length() || Double.isNaN(v)) return null;
                return v;
            } catch (RuntimeException e) {
                return null;
            }
        }

        private double expr(int depth) {
            if (depth > 20) throw new IllegalStateException();
            double v = term(depth);
            while (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                char op = s.charAt(i++);
                double r = term(depth);
                v = op == '+' ? v + r : v - r;
            }
            return v;
        }

        private double term(int depth) {
            double v = pow(depth);
            while (i < s.length() && (s.charAt(i) == '*' || s.charAt(i) == '/' || s.charAt(i) == '%')) {
                char op = s.charAt(i++);
                double r = pow(depth);
                if ((op == '/' || op == '%') && r == 0) throw new IllegalStateException();
                v = op == '*' ? v * r : op == '/' ? v / r : v % r;
            }
            return v;
        }

        private double pow(int depth) {
            double b = unary(depth);
            if (i < s.length() && s.charAt(i) == '^') {
                i++;
                double e = pow(depth);
                if (Math.abs(e) > 1000) throw new IllegalStateException();
                return Math.pow(b, e);
            }
            return b;
        }

        private double unary(int depth) {
            if (i < s.length() && s.charAt(i) == '-') {
                i++;
                return -unary(depth);
            }
            if (i < s.length() && s.charAt(i) == '+') {
                i++;
                return unary(depth);
            }
            return atom(depth);
        }

        private double atom(int depth) {
            if (i < s.length() && s.charAt(i) == '(') {
                i++;
                double v = expr(depth + 1);
                if (i >= s.length() || s.charAt(i) != ')') throw new IllegalStateException();
                i++;
                return v;
            }
            int st = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            if (st == i) throw new IllegalStateException();
            return Double.parseDouble(s.substring(st, i));
        }
    }

    // ------------------------------------------------------------------ no supo

    private void fallback(Reply r, Person p, Situation s, List<String> t) {
        r.confident = false;
        if (isQuestion(t) || t.contains("sabes")) {
            r.topic = "desconocido";
            String teach = p.owner ? " Si quieres, enséñame: 'lilith aprende X es Y'." : "";
            r.lines.add(pick("unk",
                    "Mmm, eso no lo sé, {n}. Pregúntame de mobs, crafteos, minerales, pociones o PvP." + teach,
                    "Esa no me la sé. Con Minecraft sí te puedo ayudar: crafteos, mobs, biomas..." + teach,
                    "No tengo esa información, {n}." + teach));
            return;
        }
        r.topic = "charla";
        if (!p.notes.isEmpty() && rnd.nextInt(4) == 0) {
            r.lines.add("Me acuerdo que dijiste: " + p.notes.get(rnd.nextInt(p.notes.size())) + ".");
            return;
        }
        if (!p.likes.isEmpty() && rnd.nextInt(5) == 0) {
            r.lines.add("Por cierto, ¿sigues con eso de " + p.likes.get(rnd.nextInt(p.likes.size())) + "?");
            return;
        }
        r.lines.add(pick("chat", "Interesante, {n}. Cuéntame más.", "Ajá... sigo escuchando.", "Mmm, no sé qué decir a eso, pero te escucho.",
                "Te escucho, {n}. ¿Qué más?", "Puede ser... ¿y qué piensas tú?"));
    }

    // ------------------------------------------------------------------ utilidades

    private static boolean isLaugh(List<String> t) {
        if (t.isEmpty() || t.size() > 3) return false;
        for (String w : t) {
            if (!(LAUGH.contains(w) || (w.length() >= 4 && LAUGH_RE.matcher(w).matches()))) return false;
        }
        return true;
    }

    private void say(Reply r, String key, String line) {
        r.lines.add(line);
    }

    /** Elige una opcion sin repetir la anterior de esa misma clave. */
    private String pick(String key, String... opts) {
        int last = lastPick.getOrDefault(key, -1);
        int i = rnd.nextInt(opts.length);
        if (opts.length > 1 && i == last) i = (i + 1) % opts.length;
        lastPick.put(key, i);
        return opts[i];
    }

    private static String fmt(String line, Person p) {
        return line.replace("{n}", p.display());
    }

    static String prettyItem(String mat) {
        if (mat == null || mat.isBlank() || mat.equalsIgnoreCase("AIR")) return "nada";
        String m = mat.toUpperCase(Locale.ROOT);
        String[][] special = {
                {"TOTEM_OF_UNDYING", "un tótem de la inmortalidad"}, {"END_CRYSTAL", "un cristal del End"},
                {"GOLDEN_APPLE", "una manzana dorada"}, {"ENCHANTED_GOLDEN_APPLE", "una manzana dorada encantada"},
                {"MACE", "una maza"}, {"SHIELD", "un escudo"}, {"BOW", "un arco"}, {"CROSSBOW", "una ballesta"},
                {"TRIDENT", "un tridente"}, {"ENDER_PEARL", "una perla de ender"}, {"WIND_CHARGE", "una wind charge"},
                {"WATER_BUCKET", "un cubo de agua"}, {"LAVA_BUCKET", "un cubo de lava"}, {"OBSIDIAN", "obsidiana"},
        };
        for (String[] sp : special) if (m.equals(sp[0])) return sp[1];
        String[][] tiers = {{"NETHERITE_", "netherite"}, {"DIAMOND_", "diamante"}, {"IRON_", "hierro"}, {"STONE_", "piedra"},
                {"WOODEN_", "madera"}, {"GOLDEN_", "oro"}};
        String[][] types = {{"SWORD", "espada"}, {"AXE", "hacha"}, {"PICKAXE", "pico"}, {"SHOVEL", "pala"}, {"HOE", "azada"}};
        for (String[] ti : tiers) {
            if (m.startsWith(ti[0])) {
                String rest = m.substring(ti[0].length());
                for (String[] ty : types) if (rest.equals(ty[0])) return "una " + ty[1].replace("pico", "pico") + " de " + ti[1];
            }
        }
        return m.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static final Map<String, String> BIOMES = new HashMap<>();

    static {
        String[] b = {
                "plains", "llanura", "sunflower_plains", "girasoles", "forest", "bosque", "flower_forest", "bosque de flores",
                "birch_forest", "abedules", "dark_forest", "bosque oscuro", "taiga", "taiga", "snowy_taiga", "taiga nevada",
                "snowy_plains", "llanura nevada", "ice_spikes", "picos de hielo", "desert", "desierto", "savanna", "sabana",
                "windswept_hills", "colinas ventosas", "jungle", "jungla", "sparse_jungle", "jungla dispersa", "bamboo_jungle", "bambú",
                "badlands", "badlands", "eroded_badlands", "badlands erosionadas", "wooded_badlands", "badlands con bosque",
                "swamp", "pantano", "mangrove_swamp", "manglar", "meadow", "prado", "cherry_grove", "cerezal", "grove", "arboleda",
                "snowy_slopes", "laderas nevadas", "frozen_peaks", "picos helados", "jagged_peaks", "picos escarpados",
                "stony_peaks", "picos rocosos", "river", "río", "frozen_river", "río helado", "beach", "playa", "snowy_beach", "playa nevada",
                "stony_shore", "costa rocosa", "ocean", "océano", "deep_ocean", "océano profundo", "cold_ocean", "océano frío",
                "lukewarm_ocean", "océano templado", "warm_ocean", "océano cálido", "frozen_ocean", "océano helado",
                "mushroom_fields", "hongos", "dripstone_caves", "cuevas de espeleotemas", "lush_caves", "cuevas frondosas",
                "deep_dark", "oscuridad profunda", "pale_garden", "jardín pálido", "nether_wastes", "desiertos del Nether",
                "crimson_forest", "bosque carmesí", "warped_forest", "bosque distorsionado", "soul_sand_valley", "valle de arena de almas",
                "basalt_deltas", "deltas de basalto", "the_end", "el End", "end_highlands", "tierras altas del End",
                "end_midlands", "tierras medias del End", "small_end_islands", "islas pequeñas del End"
        };
        for (int i = 0; i + 1 < b.length; i += 2) BIOMES.put(b[i], b[i + 1]);
    }

    static String biomeName(String key) {
        if (key == null || key.isBlank()) return "";
        String k = key.toLowerCase(Locale.ROOT);
        return BIOMES.getOrDefault(k, k.replace('_', ' '));
    }
}
