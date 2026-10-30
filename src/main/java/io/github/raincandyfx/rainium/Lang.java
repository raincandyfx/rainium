package io.github.raincandyfx.rainium;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Text shown by the mod comes from assets/rainium/lang/&lt;language&gt;.json, so it can be translated.
 * Keys are "rainium." + the given key; arguments fill %s placeholders in order.
 */
final class Lang {
    private Lang() {}

    static String t(String key, Object... args) {
        if (args.length > 0) return Component.translatable("rainium." + key, args).getString();
        Map<String, String> m = cache();
        String v = m.get(key);
        if (v == null) { v = Component.translatable("rainium." + key).getString(); m.put(key, v); }
        return v;
    }

    // Translations without arguments are looked up again and again while the editor draws; remember them
    // until the language object changes (a language switch or resource reload).
    private static final String MISSING = "\u0000";
    private static volatile Language cachedFor;
    private static volatile Map<String, String> plain = new ConcurrentHashMap<>(), optionals = new ConcurrentHashMap<>();

    private static Map<String, String> cache() {
        Language now = Language.getInstance();
        if (now != cachedFor) { optionals = new ConcurrentHashMap<>(); plain = new ConcurrentHashMap<>(); cachedFor = now; }
        return plain;
    }

    /** The translation, or null when the current language (and English) has no such key. */
    static String optional(String key) {
        cache();   // drops both caches if the language changed
        Map<String, String> m = optionals;
        String v = m.get(key);
        if (v == null) {
            String full = "rainium." + key;
            v = Language.getInstance().has(full) ? Component.translatable(full).getString() : MISSING;
            m.put(key, v);
        }
        return v == MISSING || MISSING.equals(v) ? null : v;
    }
}
