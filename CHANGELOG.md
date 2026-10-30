# Changelog

Rainium is in beta (0.x) until 1.0.0.

## 0.8.8 (dev build)

- **Spin around:** a spinning layer now turns around a centre you choose: itself, the player, a body part, or another layer (Edit > Behaviour > Spin around). Pick another layer to circle it while that layer stays still.
- **Nocturne:** everything now turns around the moon at half a turn per second, while the moon itself stays still.

## 0.8.7 (dev build)

- **Sharp sample-model pictures:** the Nocturne pictures are now high-resolution images drawn as real textures instead of coarse pixel blocks.

## 0.8.6 (dev build)

- **Nocturne is one entry with two forms:** a Nocturne heading in Add > Presets with **Base** and **Ascended** underneath, each with a proper picture instead of the rough silhouette.
- **Nocturne is upright and placed properly:** moon and halo the right way up, 20% bigger, at eye level, beside your right arm and half a block behind you.
- **New: Spin.** Any layer can turn in place around its own upright axis (Edit > Behaviour > Spin, in degrees per second). On for Nocturne; set it to Off to keep it still.

## 0.8.5 (dev build)

- **New sample model: Nocturne** (a moon inside a glowing halo with ribbons), in two forms shown side by side in Add > Presets: the base form and the glowing Ascended form.

## 0.8.4 (dev build)

- **Eyed wings is always listed:** without the mod it is read from, the preset is greyed out with "Needs mod"; clicking it opens the mod's page. Keep the mod disabled: Rainium finds it in the `mods` folder either way. It ungreys as soon as the mod is found.

## 0.8.3 (dev build)

- **Fresh installs start empty:** the Gold halo is no longer added (switched off) on the first launch; it is only offered in the Add tab, like the other sample models. Existing setups are unchanged.

## 0.8.2 (dev build)

- **New sample model: Glow halo.** A soft glowing ring over the head with pink sparkles, in Add > Presets. Presets are now data-driven, so more sample models can be added the same way.

## 0.8.1 (dev build)

- **Tutorial:** the Mods and Configure highlights are found in more ways, so they keep working if Mod Menu's labels or layout change. If neither is found, the card still says what to click.

## 0.8.0

Everything from the 0.7.1 to 0.7.10 dev builds, plus:

- **First-run tutorial:** a short tour offered on the main menu the first time you start the game. It points you to Mods, then walks through the editor in seven steps (adding models, placing them, layers, particles with Hue and Tint, who sees it). Skip it from any step; replay it from **Settings > Replay tutorial**. It only shows once; the flag is saved in the rainium folder (`tutorial.state`).
- **What's new popup:** each main release shows one popup on the main menu the first time you start the game after updating.

From the dev builds:

- **Folders and zips:** drop a folder or a .zip of models into the rainium folder and it shows in Add > Your models. One click converts every model inside and assigns each its texture (from the OBJ's material file, a texture inside a glTF/GLB, or the closest file name), in a folder named after it.
- **Dropdowns** replace the ‹ › lists, and a small **i** beside each setting explains it (hover or click).
- **Editor look:** Rainium's own fonts (Archivo and Inter), the website's blues in the layer list, proper Undo, Redo and Showing icons, capture settings under the Rec button, and Privacy folded away at the end of Settings. No background animation or tab transition.
- **Faster editor:** translations, measured text, part names and Motion presets are remembered instead of recomputed every frame.
- Players are no longer given a fresh copy of the layer list every frame in the world.

## 0.7.10 (dev build)

- **Tutorial text:** emphasised words are now a lighter cyan instead of Minecraft's bold, which left small gaps in the letters and uneven spacing.

## 0.7.9 (dev build)

- **First-run tutorial.** The first time you start the game with Rainium, the main menu offers a short tour (skip it any time, from any step, with a confirmation). It starts on the main menu by pointing at **Mods**, follows you to Rainium, then walks through the editor in seven steps with a "Step n / 7" progress bar: finding Rainium, the editor's four areas, adding models (your own files, folders and zips, and the Gold halo), shaping and placing, managing layers, particles with Hue and Tint, and who sees it.
- It only shows once. The flag is saved in the rainium folder (`tutorial.state`), so updates and Undo never bring it back. **Settings > Replay tutorial** runs it again.
- **What's new popup:** every main release (x.y.0) shows one popup on the main menu the first time you start the game after updating.

## 0.7.8 (dev build)

- **Folders and zips are detected automatically.** Drop a folder or a .zip of models into the rainium folder and it appears in the Add tab's "Your models" list. One click unpacks it (zips go to `imports/`), converts every .obj, .gltf and .glb inside, matches each model to its texture, and adds them as layers in a folder named after it.
- **Textures are assigned for you:** an OBJ's own material file (`map_Kd`) is followed first, a texture embedded in a glTF/GLB is used as is, and otherwise the closest file name wins (`dragon.obj` with `dragon_diffuse.png`). Normal, specular, glow and similar maps are never picked as the colour texture. A model with several materials is split per material, each with its own texture. JPG and similar textures are converted to PNG.
- Models clearly not sized in blocks are fitted to two blocks across. Anything that couldn't be matched or read is listed in the game log.
- Zips are unpacked safely: only model and image files, nothing written outside the folder, with limits on size and file count.

## 0.7.7 (dev build)

- **Showing icon** is centred in its circle (the Undo and Redo icons use the same exact centring).
- **Caching, with no change in behaviour:** translations, shortened and measured label text, styled text, a model's part names (rechecked once a second), and the Motion presets are remembered instead of recomputed every frame. Players are no longer given a fresh copy of the layer list on every frame in the world.

## 0.7.6 (dev build)

Built from 0.7.3. This replaces 0.7.4 and 0.7.5, which had performance problems in the editor.

- **Dropdowns:** every setting you used to step through with ‹ › (style, model, texture, step size and the rest) is now a dropdown listing all the choices. The scroll wheel still steps through them.
- **Info buttons:** an "i" beside a setting's name shows its explanation when you hover it; click to keep it open. These replace the old hover tooltips on those rows.
- **Undo, Redo and Showing icons** are now proper icons (from Lucide), centred in their keys.
- **Removed** the background caustics and the tab-change animation; tabs switch instantly.
- No thumbnails were added.

## 0.7.3 (dev build)

- **Rainium's own fonts everywhere in the editor:** all text now uses the website's fonts (Archivo for titles and buttons, Inter for everything else, both under the SIL Open Font License). Resource-pack fonts and Minecraft's pixel symbols no longer mix in; arrows and markers are drawn as shapes or come from Inter.
- **Lighter layer list:** the rows are the site's blue, and the selected one is brighter, so its dark letters stay readable.
- **Capture settings moved to the Rec button:** the small arrow on Rec opens a card with Size, Frame rate, Spin and Open captures folder. They're no longer in Settings.
- **Privacy** is folded away at the end of Settings; click its heading to open it.
- **Livelier water:** the background caustics now shift and re-form as they drift, like the website's.
- **Tab transition:** switching between Edit, Add and Settings slides the new tab in behind a quick white wipe.

## 0.7.2 (dev build)

- **The website's font:** tabs, headings, buttons and layer names use Archivo Condensed Black Italic, the font on the Rainium site (bundled under the SIL Open Font License).
- **Water caustics:** faint light from the water's surface drifts across the editor's background.
- **Brighter outside a world:** opened from the mod list, the editor now shows Minecraft's menu panorama behind a lighter blue, instead of a dark empty screen.
- **Updater:** more reliable on networks where the check timed out. It uses an older, more compatible connection (HTTP/1.1), waits longer, tries once more before giving up, and says which step failed.

## 0.7.1 (dev build)

The first step of the editor's new look, after the Rainium website and Persona 3 Reload's menus. Every setting is still where it was.

- **Look:** the site's deep blue, cyan, red and white. The game behind the editor is blurred (it follows Minecraft's Menu Background Blur setting), and the open tab's name sits huge and faded in the background.
- **Selection:** the open tab and the selected layer get the site's triangle: a white triangle with a red twin that stabs in, with the letters turning red inside it.
- **Tabs** moved to the top bar next to the title; **Showing**, **Undo**, **Redo** and **Done** sit on the right as button hints.
- **Layer list:** slanted dark ribbons; the row under the mouse gets a cyan bar.
- **Panels and headings:** the settings panel is see-through blue glass with a white border and a cut corner. Section headings are white name tags.
- **Settings rows:** values sit in dark rounded pills, and the row under the mouse turns white with a red line, as in the game's settings. Switches show **On / Off**.
- **Buttons:** slanted, in bold italics; **Picture** and **Rec** too.

## 0.7.0

Everything from the 0.6.1 to 0.6.4 dev builds, plus:

- **Minecraft 26.2** is supported, alongside 26.3 and 1.21.11. Each release has a jar for each version, and the in-game updater picks the one for your game.

From the dev builds:

- **Tint**, next to Hue on the Edit tab: colours a whole layer towards any colour, at the strength you choose. Hue shifts the texture first, then Tint colours the result.
- **Flashback replays:** layers set to **Just me** show on you, not on the replay camera. Rainium works out which recorded player is you (your account name, the only player with a recorded hotbar, or the player nearest the camera at the start), or you can choose: **Settings > Visibility > Also me** takes entity UUIDs, and **Players here** has a **+ Me** button next to each recorded player.
- **Start angle** for particle layers: every particle can start upright, or at a random angle (the default).
- Text fields accept pasting with **Ctrl+V**.

## 0.6.4 (dev build)

- **Flashback replays:** Rainium now recognises Flashback's camera ("Replay Viewer"), so working out which recorded player is you works again when **Also me** is empty.
- **Also me** takes **entity UUIDs**, the ones Flashback shows; a pasted account UUID also matches. In a replay, **Players here** lists only the recorded players, not the camera.
- Text fields accept pasting with **Ctrl+V**.

## 0.6.3 (dev build)

- **Also me** in Settings > Visibility: player UUIDs that count as you, so they get your **Just me** layers, for example yourself in a Flashback replay. **Players here** lists the players around you (in a replay, the recorded players) with a **+ Me** button for each; UUIDs can also be typed in.
- When **Also me** is set and you're watching through a replay camera, the editor's preview shows the listed player.

## 0.6.2 (dev build)

- **Flashback replays:** layers set to **Just me** now show on the recorded player instead of the replay camera. Rainium finds you by your account name, or else as the only player whose hotbar was recorded, or else as the player nearest the camera when the replay starts. The editor's preview shows that player too.
- **Start angle** for particle layers: every particle can start upright, or at a random angle as before (the default).

## 0.6.1 (dev build)

- **Tint**, next to Hue on the Edit tab: colours a whole layer towards any colour. Click the colour strip to pick one and set the strength (Off keeps the original colours). Hue shifts the texture first, then Tint colours the result; a tint keeps its colour when you change the hue.

## 0.6.0

- **Minecraft 1.21.11** is supported alongside 26.3, with every feature, including pictures and recordings. Each release has a jar for each version (`rainium-<version>+26.3.jar` and `rainium-<version>+1.21.11.jar`), and the in-game updater picks the one for your game.

## 0.5.1 (dev build)

- **Minecraft 1.21.11** is supported alongside 26.3, with every feature, including pictures and recordings. Each release now has a jar for each version (`+26.3` and `+1.21.11`), and the in-game updater picks the one for your game.

## 0.5.0

Everything from the 0.4.1 to 0.4.6 dev builds:

- **Pictures and recordings** of the editor preview, with a transparent background. **Picture** saves a PNG; **● Rec** records numbered PNG frames that video editors import as one clip (or that ffmpeg can turn into a transparent WebM). Both go to `config/rainium/captures`, and each recording's folder name ends with its frame rate, like `_120fps`.
- **Smooth, true-speed recordings.** While recording, the game's clock follows the recording: every frame the game draws becomes the next frame of the clip, exactly one step after the last, whatever your frame rate. The player, emotes from mods like Emotecraft, sway and particles all play at their real speed with no choppiness. The game runs faster or slower than normal until you stop.
- **Settings > Capture**: size (the preview's size on screen, 2× or 4×), frame rate (120 by default, or 60 or 30), spin while recording (off, slow or fast), and a button to open the captures folder.
- The preview moves smoothly between game ticks, so walking and emotes no longer step 20 times a second.

## 0.4.6 (dev build)

- Recordings have no link to real time or your frame rate: every frame the game draws is the next frame of the clip, one exact step after the last, with no waiting to match real time and no jumping to catch up. The only pause is while saving catches up.

## 0.4.5 (dev build)

- Recordings play at true speed with no choppiness, including emotes from mods like Emotecraft. While recording, the game's own clock follows the recording, so everything that animates (the player, emotes, sway, particles) moves exactly one frame's worth between saved frames. If your computer can't keep up, the game runs in slow motion while recording instead of the clip speeding up.
- The preview moves smoothly between game ticks (walking and emotes no longer step 20 times a second).
- Saving uses more processor cores, and limits how much memory waiting frames can use.

## 0.4.4 (dev build)

- Recording folders are named with their frame rate (for example `rainium_2026-10-24_11-40-00_120fps`), so you know what rate to import the frames at.

## 0.4.3 (dev build)

- Recordings default to **120 fps** for the smoothest motion, with 60 and 30 still available in **Settings > Capture**. Higher frame rates take longer to record and use more disk space.

## 0.4.2 (dev build)

- Smoother recordings: a recording now runs on its own clock, so every frame is exactly one step (1/30 or 1/60 of a second) after the last, with no repeated or skipped frames. If your computer can't draw and save fast enough, the preview runs in slow motion while recording, but the saved clip still plays smoothly at full speed.

## 0.4.1 (dev build)

- **Pictures and recordings**: the preview has **Picture** and **● Rec** buttons. Pictures are PNGs with a transparent background; recordings are numbered transparent PNG frames that video editors import as one clip. Both are saved in `config/rainium/captures`.
- **Settings > Capture**: size (screen, 2× or 4×), frame rate (30 or 60 fps), spin while recording (off, slow or fast), and a button to open the captures folder.

## 0.4.0

Everything from the 0.3.1 to 0.3.8 dev builds:

- **Particle emitters**: a new kind of layer that gives off particles, using Minecraft's particle pictures or your own PNGs. Start from Sparkles, Embers, Hearts, Trail, Aura or an empty emitter on the Add tab, then adjust:
  - when they appear (always, while moving, while standing still, or more the faster you move) and how many;
  - where (point, ball, box or ring) and their size over time;
  - direction, speed, spread, gravity, drag, spin and fading;
  - whether they move with you or leave a trail.
- **glTF and GLB import** alongside OBJ, with the same live preview. A texture packed inside a `.glb` is used automatically.
- **Arm and leg attachment points** (left and right arm, left and right leg). Layers on them swing with your limbs, and **Mirrored copy** puts a copy on the other side.
- **Choose who sees each layer**: under **Shown on**, each layer and folder can appear on just you, everyone, other players, listed players or nobody, and on armor stands with the name tags you choose. **Default** follows the folder, then Settings > Visibility, which also gained **Listed players** and **Armor stands**. All of this only changes what you see.
- Exported zips leave out who layers are shown on, so shared sets use the receiver's own settings.
- The Add tab shows a small picture of each preset and model.
- Error messages can be copied (**Copy** on the error banner, **Copy error** in Settings and the import view).
- Player name lists can be separated by commas or spaces.
- Fixed particle layers failing on Windows when using a Minecraft particle picture.

## 0.3.8 (dev build)

- Exported zips no longer include who each layer is shown on (including any player or armor stand names). Adding a shared set uses your own Visibility settings.
- Code cleanup.

## 0.3.7 (dev build)

- **Shown on** for each layer and folder: choose which players (just you, everyone, other players, listed players or nobody) and which armor stands each one appears on. Left on Default, a layer follows its folder, then Settings > Visibility.
- Player name lists can be separated by commas or spaces.

## 0.3.6 (dev build)

- Fixed particle layers failing on Windows with "Illegal char <:>" when using a Minecraft particle picture.
- Error messages can be copied: **Copy** on the error banner, and **Copy error** in Settings and in the import view.

## 0.3.5 (dev build)

- **Particle emitters**: a new kind of layer that gives off particles, with Minecraft's particle pictures or your own PNGs. Start from Sparkles, Embers, Hearts, Trail, Aura or an empty emitter on the Add tab, then adjust when they appear, how many, where (point, ball, box or ring), size over time, fading, direction, speed, spread, gravity, drag, spin, and whether they move with you or leave a trail. Emitters use the same look and placement options as models.

## 0.3.4 (dev build)

- **Show on** has a new choice, **Listed players**: your layers appear on the players you name.
- **Armor stands**: your layers can also appear on armor stands with a chosen name tag (default "Rainium"), for screenshots and displays.
- Both only change what you see.

## 0.3.3 (dev build)

- The Add tab shows a small picture of each preset and model.

## 0.3.2 (dev build)

- The editor imports **glTF and GLB** models as well as OBJ, with the same live preview and options. A texture packed inside a `.glb` is saved next to the model and used automatically.

## 0.3.1 (dev build)

- New attachment points: **Left arm, Right arm, Left leg, Right leg**. Layers on them swing with your arms and legs. With **Mirrored copy** on, a copy goes on the other arm or leg.

## 0.3.0

- New **Include dev builds** switch in Settings > Updates: also installs dev builds (early versions like 0.3.1) automatically. Off by default.
- Fixed the wings' Aura showing on only one wing: it now has a mirrored copy, like the original.
- Gold halo: the rungs ignore world light so they glow in the dark, replacing the two extra glow layers.
- The preset changes apply when a preset is added. For an existing setup, use **Reset to preset** on the Aura and on the halo's Rungs (and remove the halo's old Glow / Rungs glow layers), or add the preset again.

## 0.2.1 (dev build)

- Fixed the wings' Aura showing on only one wing: it now has a mirrored copy, like the original.
- Gold halo: the rungs ignore world light so they glow in the dark, replacing the two extra glow layers.
- Both apply to newly added presets. For an existing setup, use **Reset to preset** on the Aura and on the halo's Rungs (and remove the halo's old Glow / Rungs glow layers), or add the preset again.

## 0.2.0

- First release on GitHub, and a check that automatic updates work: with 0.1.6 installed, the game should download this version and install it when you close it.
- Automatic updates only install main releases (x.y.0). Versions like 0.2.1 are dev builds for testing: published as pre-releases and never installed automatically.

## 0.1.6

- Cleaner editor with less text:
  - Layers in the list take one line each.
  - Duplicate, Copy style and Paste style share one row; Remove and Reset to preset moved to the bottom of the layer's settings.
  - Explanations that were always on screen are now tooltips (hover a button or setting).
- Motion is now a single **Feel** choice (Still, Calm, Normal, Lively, Bouncy). Turn on **Fine-tune** to see every motion setting.

## 0.1.5

- Small cleanup: removed leftover text and files that were no longer used.

## 0.1.4

- Automatic updates: once per launch Rainium checks its GitHub releases for a newer version built for the running Minecraft version, downloads and verifies it, and installs it when the game closes. Settings > Updates has the switch, the status and a "Check for updates now" button.
- Jars are now named `rainium-<version>+<minecraft>.jar`.

## 0.1.3

- The editor is recoloured to match the icon: night navy, cloud lavender and rain cyan.
- The Gold halo preset has a soft glow over the whole halo and a strong glow on its rungs (new installs, or Reset to preset).
- Fixed the wings' Aura not showing: glow textures with no transparency information are now shown by their brightness.
- With Mod Menu installed, Rainium's "Configure" button in the mod list opens the editor, and closing it goes back to the list.

## 0.1.2

- The bundled Gold halo is installed on first launch but starts switched off. Click its folder's switch in the layer list to show it.

## 0.1.1

- **Hue** replaces the tint swatches: it shifts the texture's colours around the colour wheel (−180° to +180°), with a stepper, typed values and a strip to click. Saved as `"hue"` in `layers.json`.
- All text the mod shows can be translated (`assets/rainium/lang`).
- The Gold halo now comes with the mod as an example preset.
- The Eyed wings preset is only listed when a compatible wing mod is installed.

## 0.1.0

First version under the Rainium name.

- In-game editor (K or `/rainium`) with a live player preview, undo/redo, tooltips, and typed values for every number.
- Layers render any JSON mesh with a texture, tint, render style, opacity, anchor, offset, rotation, scale, mirroring and sway physics.
- Folders: show or hide, resize, move and style every part of a model at once. Each folder has its own motion strength.
- OBJ import with live preview. A model with several parts becomes a folder.
- Presets: Gold halo and Eyed wings.
- Export a folder as a zip, and add shared sets from other installs.
- Hot reload of `layers.json`, meshes and textures. On errors, the last working setup stays on screen.
- Privacy: folder paths shown in the editor replace the computer's account name with the in-game name, a custom name or `~`. Logs always use `~`.
