package io.github.raincandyfx.rainium;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

/**
 * Text shown by the mod comes from assets/rainium/lang/&lt;language&gt;.json, so it can be translated.
 * Keys are "rainium." + the given key; arguments fill %s placeholders in order.
 */
final class Lang {
    private Lang() {}

    static String t(String key, Object... args) {
        return Component.translatable("rainium." + key, args).getString();
    }

    /** The translation, or null when the current language (and English) has no such key. */
    static String optional(String key) {
        String full = "rainium." + key;
        return Language.getInstance().has(full) ? Component.translatable(full).getString() : null;
    }
}
