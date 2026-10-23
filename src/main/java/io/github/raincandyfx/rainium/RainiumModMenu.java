package io.github.raincandyfx.rainium;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Adds a "Configure" button for Rainium in Mod Menu's mod list. Only loaded when Mod Menu is installed. */
public final class RainiumModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<RainiumScreen> getModConfigScreenFactory() {
        return RainiumScreen::new;
    }
}
