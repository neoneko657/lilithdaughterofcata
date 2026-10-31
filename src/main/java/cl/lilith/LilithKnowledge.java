package cl.lilith;

import java.util.ArrayList;
import java.util.List;

/**
 * Lo que Lilith sabe de Minecraft (Java 1.21). Cada tema tiene claves para reconocerlo
 * y varias lineas: la primera es la respuesta corta y las siguientes son "mas detalle".
 * Claves: minusculas, sin tildes; un * al final de una palabra significa "empieza con".
 */
public final class LilithKnowledge {

    public static final class Entry {
        public final String id;
        public final String[] keys;
        public final String[] lines;
        public final String mob; // tipo de criatura (para contar encuentros), o null

        Entry(String id, String mob, String keys, String[] lines) {
            this.id = id;
            this.mob = mob;
            String[] k = keys.split("\\|");
            for (int i = 0; i < k.length; i++) k[i] = LilithText.normKey(k[i]);
            this.keys = k;
            this.lines = lines;
        }
    }

    public record Hit(Entry entry, int score) {}

    /** Puntaje minimo para dar por reconocido un tema (una palabra de 3+ letras ya basta). */
    public static final int MIN_SCORE = 7;

    private final List<Entry> all = new ArrayList<>();

    public LilithKnowledge() {
        build();
    }

    public int size() {
        return all.size();
    }

    public List<Entry> entries() {
        return all;
    }

    public Entry byId(String id) {
        for (Entry e : all) if (e.id.equals(id)) return e;
        return null;
    }

    /** El tema que mejor calza con el mensaje, o null. */
    public Hit best(List<String> toks) {
        Hit best = null;
        for (Entry e : all) {
            int s = 0;
            for (String k : e.keys) s = Math.max(s, LilithText.matchScore(toks, k));
            if (s >= MIN_SCORE && (best == null || s > best.score())) best = new Hit(e, s);
        }
        return best;
    }

    private void e(String id, String keys, String... lines) {
        all.add(new Entry(id, null, keys, lines));
    }

    private void mob(String id, String type, String keys, String... lines) {
        all.add(new Entry(id, type, keys, lines));
    }

    private void build() {
        // ------------------------------------------------------------ basicos
        e("empezar", "primeros pasos|primer dia|empezar|empiezo|principiante|sobrevivir|supervivencia|novato|noob",
                "Primero madera: golpea un árbol, haz tablones y una mesa de crafteo. Luego pico de madera, piedra, herramientas de piedra y un horno.",
                "Antes de que anochezca ten un refugio, comida y antorchas. De noche salen monstruos en la oscuridad, y una cama te salta la noche.",
                "Consejo clásico: nunca caves recto hacia abajo, y no entres a una cueva sin comida, un arma y bloques.");
        e("mesa", "mesa de crafteo|mesa de trabajo|crafting table|crafteo|craftear|crafting",
                "La mesa de crafteo se hace con 4 tablones de cualquier madera. Te da la cuadrícula de 3x3 para casi todo lo demás.");
        e("horno", "horno*|fundir|funde|fundicion|ahumador|smelter",
                "El horno se hace con 8 bloques de piedra (cobblestone, deepslate o blackstone). Combustible abajo, lo que quieras fundir arriba.",
                "El alto horno funde menas al doble de velocidad y el ahumador cocina la comida más rápido.",
                "Un cubo de lava como combustible sirve para 100 objetos. El carbón y los troncos también sirven.");
        e("antorcha", "antorcha*|iluminar|iluminacion",
                "Antorcha: 1 carbón (o carbón vegetal) sobre 1 palo y salen 4. Donde hay luz no aparecen monstruos, así que ilumina tu base y tus minas.");
        e("cama", "cama|camas|dormir|dormirse|punto de reaparicion|spawn point",
                "Cama: 3 lanas del mismo color + 3 tablones. Al dormir te saltas la noche y fijas tu punto de reaparición.",
                "Solo se puede dormir de noche o en tormenta. En el Nether y el End las camas explotan, no las uses ahí.");
        e("cofre", "cofre*|chest|cofre de ender|ender chest|shulker box|caja de shulker|almacenamiento|almacenar",
                "Cofre: 8 tablones. Dos juntos forman uno doble. El cofre de ender (8 obsidiana + 1 ojo de ender) comparte su contenido entre todos los tuyos.",
                "La shulker box (2 caparazones de shulker + 1 cofre) conserva lo que lleva dentro aunque la rompas.");
        e("herramientas", "pico|picos|herramientas|herramienta|tier|pala|azada",
                "Orden de materiales: madera, piedra, hierro, diamante y netherite. El oro es rapidísimo pero se rompe enseguida.",
                "Con pico de hierro ya sacas diamantes, pero la obsidiana pide pico de diamante o netherite.",
                "Encantamientos clave del pico: Eficiencia, Inquebrantable y Reparación (Mending).");
        e("armadura", "armadura*|casco|peto|pechera|botas|grebas|pantalones|cota de malla",
                "Armaduras de peor a mejor: cuero, oro, cota de malla, hierro, diamante y netherite. La de oro protege poco y dura nada.",
                "El netherite además da resistencia al empuje y no se quema en lava. Encántala con Protección IV, Inquebrantable y Mending.",
                "Las plantillas de diseño (trims) cambian su aspecto en la mesa de herrería, sin afectar la protección.");
        e("netherite", "netherite|netherita|ancient debris|restos antiguos|escombros antiguos|mejorar a netherite",
                "El netherite sale de los escombros antiguos (ancient debris) del Nether, con pico de diamante o netherite. Se busca sobre todo cerca de Y 15.",
                "Funde 4 escombros en 4 fragmentos; 4 fragmentos + 4 lingotes de oro = 1 lingote de netherite.",
                "Para mejorar una pieza de diamante: mesa de herrería + plantilla de mejora + lingote de netherite + la pieza.");
        e("diamantes", "diamante|diamantes|diamond*",
                "Diamantes: baja a unos Y -58 o -59, cerca de la bedrock, y haz minería en rama o explora cuevas con luz.",
                "Hace falta pico de hierro o mejor. Fortuna da más diamantes por mena. Ojo con los lagos de lava a esa profundidad.");
        e("hierro", "hierro|iron",
                "El hierro sale sobre todo cerca de Y 16, y también mucho en montañas altas. Pide pico de piedra.",
                "Funde la mena en bruto en el horno. Los golems de hierro también sueltan lingotes.");
        e("oro", "oro|gold|lingote de oro",
                "El oro abunda cerca de Y -16, y en las badlands sale incluso en la superficie. Pide pico de hierro.",
                "En el Nether hay mucho oro (suelta pepitas). Con lingotes de oro puedes comerciar con piglins.");
        e("carbon", "carbon|carbon vegetal|coal",
                "El carbón sale en casi cualquier cueva y mucho cerca de la superficie. Sirve de combustible y para antorchas; basta pico de madera.");
        e("redstone", "redstone|redstoneo|circuito*|repetidor*|comparador*|observador*|polvo de redstone",
                "La mena de redstone sale muy abajo, cerca de Y -59. El polvo transmite señal hasta 15 bloques.",
                "El repetidor refuerza y retrasa la señal, el comparador mide contenedores y el observador detecta cambios en un bloque.",
                "Con eso haces puertas ocultas, granjas automáticas y más. Un pistón: 3 tablones, 4 piedra, 1 hierro y 1 redstone.");
        e("lapiz", "lapis|lapislazuli|lapis lazuli",
                "El lapislázuli sale mejor cerca de Y 0, y sirve para encantar y teñir. Pide pico de piedra.");
        e("esmeralda", "esmeralda*|emerald*",
                "Las esmeraldas solo salen en biomas de montaña (prados, picos, laderas nevadas...), y se comercian con aldeanos. Pide pico de hierro.");
        e("cobre", "cobre|copper|pararrayos|catalejo",
                "El cobre sale en cuevas, sobre todo cerca de Y 48. Se oxida con el tiempo y un panal lo mantiene como nuevo.",
                "Con él haces pararrayos (desvían los rayos) y catalejos.");
        e("obsidiana", "obsidiana|obsidian|obsidiana llorona",
                "La obsidiana se forma al echar agua sobre lava quieta. Solo se rompe con pico de diamante o netherite.",
                "Para un portal al Nether bastan 10 (marco de 4x5 sin esquinas); con esquinas son 14.");
        e("portal", "portal del nether|portal al nether|portal nether|hacer portal|hacer un portal|crear portal|ir al nether|entrar al nether|llegar al nether",
                "Portal al Nether: marco de obsidiana de 4 de ancho x 5 de alto (mínimo 10 bloques, las esquinas no hacen falta) y préndelo con un yesquero.",
                "Un bloque en el Nether equivale a 8 en el Overworld, ideal para viajar lejos. Anota las coordenadas de tu portal.",
                "Lleva comida, bloques, pico, armadura y arma: el Nether es peligroso. Y ponte algo de oro para que los piglins no se enojen.");
        e("nether", "nether|infierno",
                "El Nether es una dimensión de lava y fuego con fortalezas, bastiones, ghasts y piglins. Da cuarzo, netherite y varas de blaze.",
                "El agua se evapora ahí y las camas explotan. Cuidado con la lava y las caídas.");
        e("end", "end|ir al end|entrar al end|llegar al end|portal del end",
                "Para ir al End: ojos de ender (perla + polvo de blaze) para encontrar la fortaleza, y activar el portal poniendo ojos en el marco.",
                "El End es otra dimensión con el Ender Dragon, endermans y las ciudades del End. Lleva bloques, arco y comida.");
        e("stronghold", "fortaleza del end|stronghold|ojo de ender|ojos de ender|eye of ender|ender eye",
                "Ojo de ender: perla de ender + polvo de blaze. Lánzalo y vuela hacia la fortaleza (stronghold); repite hasta caer cerca y cava.",
                "El portal tiene 12 marcos y algunos ya traen ojo puesto; completa los que falten con ojos de ender.");
        e("dragon", "dragon*|ender dragon|matar al dragon|derrotar al dragon",
                "Ender Dragon: rompe primero los cristales de las torres (se curan con ellos), con arco o subiendo con bloques.",
                "Cuando se posa en el portal central, pégale con espada o arco. Cuidado con su aliento y con caer al vacío.",
                "Al morir da muchísima experiencia y suelta el huevo de dragón.");
        e("fortaleza_nether", "fortaleza del nether|nether fortress|vara de blaze|varas de blaze|blaze rod|esqueleto wither|wither skeleton|craneo*",
                "La fortaleza del Nether (ladrillos oscuros) tiene spawners de blaze (varas) y esqueletos wither (carbón y cráneos).",
                "Las varas de blaze sirven para el alambique y el polvo de blaze (ojos de ender). Lleva resistencia al fuego.");
        e("bastion", "bastion*|bastion remnant|piglin brute|piglin bruto|piglins brutos",
                "Los bastiones guardan mucho botín (oro, plantillas de netherite), pero los piglins brutos atacan siempre, aunque lleves oro.");
        e("perla", "perla*|ender pearl",
                "La perla de ender se lanza para teletransportarte; te cuesta unos 2,5 corazones de daño. Las sueltan los endermans (más con Saqueo).");

        // ------------------------------------------------------------ criaturas
        mob("creeper", "CREEPER", "creeper|creepers|creper|crepper|crepers",
                "Los creepers explotan si te acercas. Golpéalos y retrocede, o mátalos de lejos. Los gatos los espantan.",
                "Si les cae un rayo se cargan y la explosión es mucho mayor. Si los mata un esqueleto sueltan un disco de música.");
        mob("zombie", "ZOMBIE", "zombi*|zombie*",
                "Los zombis son lentos pero aguantan. Se queman con el sol (salvo con casco). En dificultad difícil rompen puertas de madera.",
                "Un zombi aldeano se cura con poción de debilidad + manzana dorada, y los aldeanos curados te dan mejores precios.");
        mob("skeleton", "SKELETON", "esqueleto*|skeleton*",
                "Los esqueletos disparan de lejos. Acércate en zigzag o ponte detrás de un bloque, y pégales cuerpo a cuerpo.",
                "También se queman de día. Sueltan huesos y flechas.");
        mob("spider", "SPIDER", "arana*|spider*",
                "Las arañas trepan paredes y de día suelen ser pacíficas. De noche saltan sobre ti, así que pelea con espalda cubierta.",
                "Sueltan hilo y ojo de araña. La araña de cueva (en minas abandonadas) envenena.");
        mob("enderman", "ENDERMAN", "enderman*|endermen",
                "A los enderman no se les mira a los ojos: se enojan y se teletransportan. Una calabaza tallada en la cabeza lo evita.",
                "Les hace daño el agua y la lluvia. Sueltan perlas de ender, necesarias para llegar al End.");
        mob("witch", "WITCH", "bruja*|witch*",
                "Las brujas lanzan pociones. Cuidado con el veneno y la lentitud; ataca rápido o acércate por sorpresa.",
                "Viven en cabañas de pantano y a veces salen en invasiones. Sueltan pociones, palos y redstone.");
        mob("phantom", "PHANTOM", "fantasma*|phantom*|membrana*",
                "Los fantasmas aparecen si pasas unos 3 días sin dormir. Duerme para reiniciar el contador, y pelea con la espalda contra una pared.",
                "Sueltan membranas, que reparan la elytra. Los gatos los espantan.");
        mob("blaze", "BLAZE", "blaze*|blazes",
                "Los blaze viven en fortalezas del Nether y disparan bolas de fuego. Escóndete tras un bloque o lleva resistencia al fuego.",
                "Sueltan varas de blaze. Las bolas de nieve les hacen daño.");
        mob("piglin", "PIGLIN", "piglin*",
                "Los piglin se enojan si no llevas nada de oro puesto. Con un lingote de oro puedes intercambiar por objetos.",
                "Los piglin brutos (bastiones) te atacan siempre. Ponte aunque sea una pieza de armadura de oro.");
        mob("warden", "WARDEN", "warden*|ciudad antigua|ciudades antiguas|deep dark|sculk*",
                "Al warden no se le pelea: se huye, en silencio. Aparece en las Ciudades Antiguas si activas sculk shriekers.",
                "Es ciego y te siente por el ruido. Camina agachado, usa lana para apagar vibraciones y no te quedes a pelear.");
        mob("ghast", "GHAST", "ghast*",
                "Los ghast lanzan bolas de fuego, pero se las puedes devolver de un golpe. Sueltan lágrimas de ghast (pociones de regeneración).");
        mob("wither", "WITHER", "wither|jefe wither|invocar wither|invocar al wither",
                "El Wither se invoca con 4 bloques de arena de almas en T y 3 cráneos de esqueleto wither. Pelea en un lugar abierto y con buena armadura.",
                "Al morir suelta una estrella del Nether, que sirve para hacer un faro.");
        mob("guardian", "GUARDIAN", "guardian*|monumento oceanico|prismarina|esponja*",
                "El monumento oceánico tiene guardianes y guardianes ancianos (que dan fatiga de minado). Lleva respiración acuática y leche.",
                "Da esponjas y prismarina.");
        mob("villager", "VILLAGER", "aldeano*|villager*|comerciar|comercio|intercambiar con aldeanos",
                "Los aldeanos comercian por esmeraldas; su profesión depende del bloque de trabajo (atril, mesa de fletcher...). ",
                "Para Mending comercia con un bibliotecario. Curar a un zombi aldeano baja tus precios.");
        mob("golem", "IRON_GOLEM", "golem*",
                "Golem de hierro: 4 bloques de hierro en T + una calabaza. Protege aldeas y suelta lingotes de hierro.");
        mob("raid", "PILLAGER", "invasion*|raid*|saqueador*|pillager*|mal presagio|bad omen|capitan de saqueadores",
                "Si matas a un capitán de saqueadores (con estandarte) te da Mal Presagio. Entra con eso a una aldea y empieza una invasión.",
                "Si la ganas obtienes Héroe de la Aldea, que baja los precios.");
        mob("slime", "SLIME", "slime*",
                "Los slimes salen en pantanos de noche y en ciertos chunks bajo Y 40. Se dividen al morir. Sueltan bolas de slime para pistones pegajosos.");
        mob("hoglin", "HOGLIN", "hoglin*|zoglin*",
                "Los hoglin son cerdos del Nether que embisten fuerte. Sueltan carne y cuero. En el Overworld se convierten en zoglin.");
        mob("drowned", "DROWNED", "ahogado*|drowned|tridente*|trident*",
                "Los ahogados salen en el agua y a veces llevan tridente. El tridente tiene Canalización (rayos en tormenta) y Impulso (te lanza en agua).");
        mob("evoker", "EVOKER", "evocador*|evoker*|vex|vexes|mansion del bosque|mansiones del bosque|woodland mansion",
                "El evocador invoca vexes y colmillos. Suelta el tótem de la inmortalidad. Se encuentra en mansiones del bosque oscuro y en invasiones.");
        mob("shulker", "SHULKER", "shulker*|ciudad del end|end city",
                "Las ciudades del End tienen shulkers (te hacen levitar) y barcos con elytra. Sus caparazones sirven para hacer shulker boxes.");

        // ------------------------------------------------------------ combate / pvp
        e("pvp", "pvp|combate|pelear|peleas|duelo*|1v1|consejos de pvp",
                "PvP: espera a que cargue el golpe, pega en crítico cayendo, muévete de lado y lleva escudo, comida y un tótem.",
                "Sprint hit y W-tap dan más empuje. El hacha le rompe el escudo al rival. Las pociones y manzanas doradas te salvan.",
                "Combos fuertes: maza con wind charge desde arriba, o crystal pvp con obsidiana y cristales.");
        e("espada", "espada*|sword*|afilado|sharpness",
                "Mejor espada: netherite o diamante con Afilado V, Aspecto ígneo, Saqueo, Inquebrantable y Mending. En PvP mucha gente usa Empuje.");
        e("espada_hacha", "hacha|hachas|axe|axes|espada o hacha|hacha o espada|hacha vs espada|espada vs hacha",
                "La espada es más rápida y barre a varios; el hacha pega más fuerte por golpe pero es lenta, y rompe el escudo del rival unos segundos.");
        e("maza", "maza*|mace|wind charge|carga de viento|densidad|brecha|rafaga de viento",
                "La maza pega más cuanto más caes antes del golpe. Con una wind charge te impulsas hacia arriba y caes encima.",
                "Se craftea con un núcleo pesado (de las bóvedas ominosas de las cámaras de prueba) y una vara de breeze.",
                "Brecha reduce el efecto de la armadura y Densidad suma daño por bloque caído.");
        e("totem", "totem*|inmortalidad",
                "El tótem de la inmortalidad te salva de la muerte con regeneración y absorción. Lo sueltan los evocadores; llévalo en la mano secundaria.");
        e("escudo", "escudo*|shield*",
                "Escudo: 1 lingote de hierro + 6 tablones. Bloqueas con clic derecho; el hacha lo inhabilita unos segundos.");
        e("arco", "arco*|ballesta*|flecha*|crossbow|infinidad",
                "Arco: 3 palos + 3 hilos. Tensa al máximo para más daño y apunta algo por encima del objetivo. La ballesta se carga antes de disparar.",
                "Infinidad te da flechas ilimitadas con una en el inventario (no con las de poción).");
        e("elytra", "elytra|elitro*|planear|cohete*|fuegos artificiales",
                "La elytra está en los barcos de las ciudades del End. Se repara con membranas de fantasma, y usas cohetes (papel + pólvora) para impulsarte.");
        e("cristal", "cristal del end|end crystal|crystal pvp|crystal|cristales del end",
                "El cristal del End explota fuerte al golpearlo. Se pone sobre obsidiana o bedrock. En PvP se pone junto al rival y se rompe de golpe.",
                "Se craftea con 7 cristales, 1 ojo de ender y 1 lágrima de ghast. Protección contra explosiones y el tótem ayudan.");
        e("nexo", "nexo*|ancla de reaparicion|respawn anchor|anchor*",
                "El nexo (ancla de reaparición): 6 obsidiana llorona + 3 glowstone. Se carga con glowstone y solo funciona en el Nether; fuera explota.");
        e("manzana_dorada", "manzana dorada|manzana de oro|golden apple|manzana encantada|notch apple",
                "La manzana dorada da regeneración y absorción: 8 lingotes de oro + 1 manzana. La encantada ya no se craftea, solo sale en botines.");
        e("telarana", "telarana*|cobweb*",
                "La telaraña ralentiza mucho. Se corta rápido con espada o tijeras. En PvP se usa para atrapar al rival.");

        // ------------------------------------------------------------ pociones, encantamientos
        e("pociones", "pocion*|alambique|destilar|brewing|elixir*",
                "Alambique: vara de blaze + 3 piedras. Botella de agua + verruga del Nether = poción base; luego añades el ingrediente del efecto.",
                "Velocidad: azúcar. Fuerza: polvo de blaze. Resistencia al fuego: crema de magma. Curación: sandía brillante. Regeneración: lágrima de ghast.",
                "Visión nocturna: zanahoria dorada. Caída lenta: membrana. Respirar bajo el agua: pez globo. Redstone alarga, glowstone potencia y pólvora la hace arrojadiza.");
        e("encantar", "encantamiento*|encantar|encantada|mesa de encantamientos|enchant*",
                "Mesa de encantamientos: 4 obsidiana + 2 diamantes + 1 libro. Con 15 estanterías alrededor llegas al nivel 30.",
                "Cuesta lapislázuli y niveles. Para combinar encantamientos usa un yunque.");
        e("mending", "mending|reparacion|remendar|reparar equipo",
                "Mending repara tu equipo con la experiencia que recoges. No sale en la mesa de encantar: se consigue con libros (aldeanos, pesca, botines).");
        e("fortuna", "fortuna|fortune|toque de seda|silk touch|eficiencia|inquebrantable|unbreaking",
                "Fortuna aumenta lo que suelta una mena. Toque de seda recoge el bloque tal cual (no se combinan). Eficiencia mina más rápido; Inquebrantable alarga la vida.");
        e("yunque", "yunque*|anvil|piedra de afilar|grindstone",
                "Yunque: 3 bloques de hierro + 4 lingotes. Combina y renombra, pero si cuesta más de 39 niveles sale 'Demasiado caro'.",
                "La piedra de afilar quita encantamientos y devuelve algo de experiencia.");
        e("xp", "experiencia|xp|niveles|nivel de xp",
                "La experiencia sale de matar mobs, minar menas, fundir y criar animales. Sirve para encantar, usar el yunque y con Mending.");

        // ------------------------------------------------------------ comida, granja, animales
        e("comida", "comida|comer|hambre|saturacion|alimento*",
                "Buena comida: filete o chuleta cocinados, pan y zanahoria dorada (la que más sacia). Las manzanas doradas curan.",
                "Zanahoria dorada: 8 pepitas de oro + 1 zanahoria. Evita carne podrida, ojo de araña, papa venenosa y pez globo.");
        e("granja", "cultivo*|cultivar|granja*|plantar|sembrar|trigo|semilla*|zanahoria*|patata*|papas|papa venenosa|remolacha*",
                "Siembra en tierra arada (con azada) con agua a 4 bloques o menos. El polvo de hueso acelera el crecimiento.",
                "La caña de azúcar y el bambú necesitan agua o arena cerca. Para criar: trigo a vacas y ovejas, zanahorias a cerdos, semillas a gallinas.");
        e("domar", "domar|domesticar|lobo*|perro*|gato*|caballo*|criar|reproducir",
                "Lobos: huesos. Gatos y ocelotes: pescado crudo. Caballos: súbete varias veces hasta que se calmen y ponles silla.",
                "Los gatos espantan a creepers y fantasmas, así que son muy útiles en la base.");
        e("pesca", "pescar|pesca|cana de pescar",
                "Caña de pescar: 3 palos + 2 hilos. Pesca en agua abierta con cielo despejado. Suerte del mar mejora el botín (libros, sillas, tesoros).");
        e("abeja", "abeja*|panal*|miel",
                "Las abejas polinizan flores y hacen panal en colmenas. Usa humo (una fogata debajo) para sacar el panal sin que ataquen.");

        // ------------------------------------------------------------ supervivencia / mecanicas
        e("caida", "caida*|dano por caida|mlg|water clutch|cubo de agua",
                "La caída es gratis hasta 3 bloques. Un cubo de agua justo antes de tocar el suelo anula el daño. También amortiguan el heno, la miel y las telarañas.");
        e("lava", "lava*|magma*",
                "Lava: nunca caves hacia abajo a ciegas. El agua la apaga: fuente de lava = obsidiana, lava corriendo = piedra. Un cubo de agua te salva.");
        e("ahogarse", "ahogar*|respirar|aire bajo el agua|respiracion acuatica",
                "Tienes unos 15 segundos de aire bajo el agua. El casco con Respiración, la poción y el conduit te dan tiempo.");
        e("coordenadas", "coordenadas|f3|posicion|brujula|recovery compass|brujula de recuperacion",
                "Con F3 ves tus coordenadas. Anota tu base y portales. La brújula de recuperación (8 fragmentos de eco + brújula) apunta a donde moriste.");
        e("mapa", "mapa|mapas|cartografo*|locate",
                "Mapa: 8 papeles + 1 brújula. Los cartógrafos venden mapas del tesoro, de monumentos y de mansiones.");
        e("dia", "cuanto dura el dia|duracion del dia|duracion de un dia|ciclo del dia|ciclo dia noche",
                "Un día de Minecraft dura 20 minutos reales: unos 10 de día, 7 de noche y el resto amanecer y atardecer.");
        e("clima", "tormenta*|rayo*|trueno*|lluvia*",
                "En tormenta caen rayos (pueden cargar creepers e incendiar). Un pararrayos de cobre los desvía. Con tormenta se puede dormir.");
        e("dificultad", "dificultad*|pacifico|hardcore|modo dificil",
                "Dificultades: pacífico (sin monstruos hostiles), fácil, normal y difícil (zombis rompen puertas y el hambre puede matar).",
                "En Hardcore tienes una sola vida: si mueres, ya no puedes jugar ese mundo.");
        e("bloque_duro", "bloque mas duro|bloque mas resistente|bedrock|roca madre",
                "La bedrock no se rompe en supervivencia. De lo que sí se rompe, la obsidiana es de lo más resistente a explosiones.");
        e("tnt", "tnt|dinamita|explosivo*|pistola de chispa",
                "TNT: 5 pólvora + 4 arena. Se enciende con yesquero o redstone. Cuidado: explota y rompe casi todo menos agua y obsidiana.");
        e("yesquero", "yesquero|encendedor|flint and steel|pedernal",
                "Yesquero: 1 lingote de hierro + 1 pedernal (sale al picar grava). Prende fuego y activa portales del Nether.");
        e("cubo", "cubo*|bucket",
                "Cubo: 3 lingotes de hierro en V. Recoge agua, lava y leche, y es clave para sobrevivir caídas.");
        e("pistones", "pegajoso|sticky|piston*|tolva*|hopper|dropper|dispenser",
                "Pistón: 3 tablones + 4 piedra + 1 hierro + 1 redstone; con bola de slime es pegajoso. La tolva (hopper): 5 hierro + 1 cofre, mueve objetos entre contenedores.");
        e("faro", "faro*|beacon",
                "Faro: estrella del Nether + 5 cristal + 3 obsidiana sobre una pirámide de bloques de hierro, oro, esmeralda, diamante o netherite. Da efectos a los de alrededor.");

        // ------------------------------------------------------------ estructuras y biomas
        e("estructuras", "estructura*|aldea*|piramide*|templo*|naufragio*|tesoro enterrado|iglu|mina abandonada|mineshaft|ruinas",
                "Hay aldeas, pirámides del desierto, templos de jungla, naufragios, minas abandonadas, mansiones, ruinas oceánicas y más. Un mapa de cartógrafo ayuda a ubicarlas.",
                "Las pirámides del desierto tienen una trampa de TNT bajo el cofre del centro, ¡cuidado con la placa de presión!");
        e("camara", "camara*|trial chamber|trial*|llaves de prueba|boveda*|vault*|breeze",
                "Las cámaras de prueba (1.21) son mazmorras con spawners 'trial', llaves y bóvedas con botín. Ahí está el breeze, que lanza ráfagas de viento.");
        e("biomas", "bioma*|cerezal|cerezo*|pantano|jungla|desierto|badlands|manglar|prado|taiga|sabana",
                "Hay muchos biomas: bosque, jungla, desierto, badlands, pantano, manglar, cerezal, taiga, sabana, océanos y montañas. Cada uno tiene su fauna y estructuras.",
                "El cerezal tiene madera rosa y pétalos; la jungla, templos y bambú; el desierto, pirámides; las badlands, oro en superficie.");
        e("cuevas", "cueva*|lush|frondosa*|dripstone|espeleotema*|geoda*|amatista*",
                "Cuevas frondosas: ajolotes y bayas brillantes. Cuevas de espeleotemas: dripstone. Las geodas dan amatistas. Siempre entra con luz y comida.");
        e("mushroom", "hongo*|mooshroom|mushroom*",
                "En las islas de hongos no aparecen monstruos hostiles. Las vacas hongo dan sopa de hongos.");

        // ------------------------------------------------------------ servidor
        e("lag", "lag|lagueo|laguea|tps|rendimiento",
                "Si el servidor va con lag por mí, mi dueño puede bajar pathfinding-max-nodes en config.yml o apagar alguna habilidad de combate.");
        e("tpa", "tpa|teletransporte|teletransportar|tpaccept",
                "Si me mandas un /tpa, el dueño decide: yo solo acepto los de mi dueño, y si es un duelo, peleamos hasta que uno muera o digan 'gg'.");
        e("chunk", "chunk*",
                "Un chunk es una columna de 16x16 bloques. El servidor solo carga los cercanos a los jugadores, por eso las granjas lejanas se detienen.");
        e("version", "version de minecraft|que version|1 21|actualizacion*",
                "Sé de Minecraft Java 1.21: cámaras de prueba, maza, wind charges, breeze y las novedades de los últimos updates.");
    }
}
