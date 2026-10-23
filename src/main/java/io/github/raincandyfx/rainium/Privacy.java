package io.github.raincandyfx.rainium;

import java.util.Locale;

/**
 * Keeps the computer account name out of anything the mod shows or logs. Paths inside the
 * user's home folder are displayed with the home folder's name replaced by the in-game name,
 * a custom name chosen in Settings, or hidden entirely ("~").
 */
final class Privacy {
    enum Mode { INGAME, CUSTOM, HIDDEN }

    static volatile Mode mode = Mode.INGAME;
    static volatile String customName = "";
    /** Last known in-game name (updated while the editor is open). */
    static volatile String ingameName;

    private Privacy() {}

    /** The name shown in place of the account name, or null when hidden. */
    static String displayName() {
        return switch (mode) {
            case HIDDEN -> null;
            case CUSTOM -> customName == null || customName.isBlank() ? "player" : customName.trim();
            case INGAME -> ingameName == null || ingameName.isBlank() ? "player" : ingameName;
        };
    }

    /** For on-screen text: uses the chosen display name. */
    static String redact(String s) { return redact(s, mode == Mode.HIDDEN ? null : displayName()); }

    /** For log files: always hides the home folder. */
    static String redactForLog(String s) { return redact(s, null); }

    static String redact(String s, String name) {
        if (s == null || s.isEmpty()) return s;
        String home = System.getProperty("user.home", "");
        String user = System.getProperty("user.name", "");
        String out = s;
        if (home.length() > 1) {
            String trimmed = home.endsWith("/") || home.endsWith("\\") ? home.substring(0, home.length() - 1) : home;
            int cut = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
            String parent = cut > 0 ? trimmed.substring(0, cut + 1) : "";
            String replacement = name == null ? "~" : parent + name;
            for (String variant : new String[]{trimmed, trimmed.replace('\\', '/'), trimmed.replace('/', '\\')}) {
                out = replaceIgnoreCase(out, variant, replacement);
            }
        }
        // any other path segment that is exactly the account name
        if (user.length() > 0) {
            String rep = name == null ? "~" : name;
            for (char sep : new char[]{'/', '\\'}) {
                out = replaceIgnoreCase(out, sep + user + sep, sep + rep + sep);
                if (out.toLowerCase(Locale.ROOT).endsWith((sep + user).toLowerCase(Locale.ROOT))) {
                    out = out.substring(0, out.length() - user.length()) + rep;
                }
            }
        }
        return out;
    }

    private static String replaceIgnoreCase(String s, String find, String rep) {
        if (find.isEmpty()) return s;
        String ls = s.toLowerCase(Locale.ROOT), lf = find.toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder();
        int from = 0, i;
        while ((i = ls.indexOf(lf, from)) >= 0) {
            b.append(s, from, i).append(rep);
            from = i + find.length();
        }
        return b.append(s.substring(from)).toString();
    }
}
