# Changelog

Rainium is in beta: versions stay at 0.1.x until 1.0.0.

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
