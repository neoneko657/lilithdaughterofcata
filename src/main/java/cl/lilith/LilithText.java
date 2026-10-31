package cl.lilith;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** Utilidades de texto de Lilith. No dependen de Bukkit, asi se pueden probar solas. */
public final class LilithText {

    private LilithText() {}

    /** Minusculas, sin tildes, sin signos, y "holaaaa" -> "hola". */
    public static String norm(String s) {
        if (s == null) return "";
        String t = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        t = t.toLowerCase(Locale.ROOT);
        t = t.replaceAll("(\\p{L})\\1{2,}", "$1");
        return t.replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    public static List<String> toks(String normed) {
        List<String> out = new ArrayList<>();
        if (normed == null || normed.isEmpty()) return out;
        for (String w : normed.split(" ")) if (!w.isEmpty()) out.add(w);
        return out;
    }

    /** Normaliza una clave del tipo "portal del nether" o "zombi*" (el * al final de una palabra = prefijo). */
    public static String normKey(String key) {
        StringBuilder sb = new StringBuilder();
        for (String w : key.trim().split("\\s+")) {
            boolean star = w.endsWith("*");
            String base = norm(star ? w.substring(0, w.length() - 1) : w).replace(" ", "");
            if (base.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(base).append(star ? "*" : "");
        }
        return sb.toString();
    }

    /** true si a y b difieren como mucho en 1 letra (cambio, falta o sobra). */
    static boolean lev1(String a, String b) {
        int la = a.length(), lb = b.length();
        if (Math.abs(la - lb) > 1) return false;
        if (la == lb) { // dos letras seguidas intercambiadas ("liliht")
            for (int k = 0; k + 1 < la; k++) {
                if (a.charAt(k) != b.charAt(k)) {
                    if (a.charAt(k) == b.charAt(k + 1) && a.charAt(k + 1) == b.charAt(k)
                            && a.substring(k + 2).equals(b.substring(k + 2))) return true;
                    break;
                }
            }
        }
        int i = 0, j = 0, diff = 0;
        while (i < la && j < lb) {
            if (a.charAt(i) == b.charAt(j)) {
                i++;
                j++;
                continue;
            }
            if (++diff > 1) return false;
            if (la == lb) {
                i++;
                j++;
            } else if (la > lb) i++;
            else j++;
        }
        diff += (la - i) + (lb - j);
        return diff <= 1;
    }

    /**
     * Cuanto calza una clave (ya normalizada) con los tokens del mensaje. 0 = no calza.
     * Mas palabras y mas letras = mas puntaje. Tolera 1 letra de error en palabras largas.
     */
    public static int matchScore(List<String> toks, String key) {
        String[] k = key.split(" ");
        int n = toks.size(), m = k.length, best = 0;
        for (int i = 0; i + m <= n; i++) {
            int score = 0;
            boolean ok = true;
            for (int j = 0; j < m && ok; j++) {
                String w = toks.get(i + j), kw = k[j];
                if (kw.endsWith("*")) {
                    String stem = kw.substring(0, kw.length() - 1);
                    if (w.startsWith(stem)) score += 4 + stem.length();
                    else ok = false;
                } else if (w.equals(kw)) {
                    score += 4 + kw.length();
                } else if (kw.length() >= 6 && w.length() >= 5 && lev1(w, kw)) {
                    score += 2 + kw.length();
                } else {
                    ok = false;
                }
            }
            if (ok) best = Math.max(best, score);
        }
        return best;
    }

    /** Alguna de las claves "a|b|c" (ya normalizadas) aparece en el mensaje. */
    public static boolean has(List<String> toks, String keys) {
        for (String k : keys.split("\\|")) {
            if (!k.isEmpty() && matchScore(toks, k) > 0) return true;
        }
        return false;
    }

    /** El mensaje nombra a Lilith (o a un apodo de la lista), tolerando 1 letra de error. */
    public static boolean mentions(String text, Collection<String> names) {
        for (String t : toks(norm(text))) {
            for (String nm : names) {
                String n = norm(nm);
                if (n.isEmpty()) continue;
                if (t.equals(n) || (n.length() >= 5 && t.length() >= 4 && lev1(t, n))) return true;
            }
        }
        return false;
    }

    /** Quita de la lista los tokens que son su nombre. */
    public static List<String> without(List<String> toks, Collection<String> names) {
        List<String> out = new ArrayList<>();
        outer:
        for (String t : toks) {
            for (String nm : names) {
                String n = norm(nm);
                if (!n.isEmpty() && (t.equals(n) || (n.length() >= 5 && t.length() >= 4 && lev1(t, n)))) continue outer;
            }
            out.add(t);
        }
        return out;
    }

    /**
     * Deja un texto seguro para decirlo en el chat: sin saltos de linea, sin formato markdown,
     * sin "/" al inicio (el chat de un jugador ejecuta comandos con "/") y sin caracteres raros.
     */
    public static String clean(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        t = t.replaceAll("[\\p{Cntrl}\u00a7]", "");
        t = t.replaceAll("[*`#>~|]", "");
        t = t.replaceAll("\\s{2,}", " ").trim();
        t = t.replaceFirst("^(?i)lilith\\s*[:\\-]\\s*", "");
        t = t.replaceFirst("^[/\\\\]+", ""); // un "/" al inicio ejecutaria un comando
        return t.trim();
    }

    /** Parte un texto en mensajes de maximo `max` caracteres, cortando en espacios. */
    public static List<String> split(String s, int max) {
        List<String> out = new ArrayList<>();
        String t = s.trim();
        while (t.length() > max) {
            int cut = t.lastIndexOf(' ', max);
            int dot = Math.max(t.lastIndexOf(". ", max), Math.max(t.lastIndexOf("? ", max), t.lastIndexOf("! ", max)));
            if (dot > max / 2) cut = dot + 1;
            if (cut < max / 3) cut = max;
            out.add(t.substring(0, cut).trim());
            t = t.substring(cut).trim();
        }
        if (!t.isEmpty()) out.add(t);
        return out;
    }
}
