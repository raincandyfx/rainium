# Changelog

Rainium is in beta (0.x) until 1.0.0.

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
