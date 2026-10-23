RAINIUM - render any JSON mesh on players
=========================================

IN-GAME EDITOR: press K (rebindable under Controls > Rainium), run /rainium, or click
  Configure next to Rainium in Mod Menu.
  Left: your layers. Click one to edit it, click its square to show/hide it, Up/Down to reorder.
  Folders group the parts of one model (presets and multi-part OBJ imports create them):
                the square on a folder shows/hides every part in it, the arrow collapses it,
                and clicking its name opens the folder view (parts, resize/move all parts
                together, Ungroup, Remove folder). A layer's "Folder" picker moves it in/out.
  Edit tab:     part, model, texture, style, hue, opacity, placement, mirroring and sway,
                shown live on your player. Duplicate / Remove sit under the layer's name.
  Add tab:      presets, mesh files in this folder, and .obj files to import (with a live
                preview while you adjust axes, units and size).
  Settings tab: who sees the layers, motion/sway tuning, folder shortcuts, automatic updates
                (checked once per launch from the mod's GitHub releases), and privacy:
                folder paths shown in the editor replace your computer's account name with
                your in-game name (default), a custom name, or ~. Log files always use ~.
  Click a layer's or folder's name to rename it; click any number (size, position, angle,
  opacity, motion...) to type an exact value. Enter saves, Esc cancels. Undo/Redo are in
  the top bar (also Ctrl+Z / Ctrl+Y). Hover a setting's name for a short explanation.
  Layer extras: Copy style / Paste style (look and placement), Reset to preset.
  Folder extras: Motion (how strongly that folder sways), Paste style to all parts,
                 Export as zip (exports/<name>.zip, add it elsewhere from Add > Shared sets).
  Changes save to layers.json immediately (the first save keeps your hand-written file as
  layers.json.bak; // comments aren't preserved). /rainium reload reloads from disk.

PRESETS (Add tab):
  Gold halo   an example model that comes with the mod. It's installed on first launch but
              switched off: click its folder's square in the layer list to show it.
  Eyed wings  only listed when a compatible wing mod is in your mods folder (installed or
              .disabled); its wing files are copied from there.
  On first launch the editor starts with the presets that are available. Preset files are
  copied into presets/<name>/, where you can edit them like any other model.

Everything lives in this folder (.minecraft/config/rainium). Edit layers.json, a mesh
file or a texture and the change shows up in game within a second. If something is wrong,
the previous working setup stays on screen and the error is written to the log
(logs/latest.log, search for "rainium").

COORDINATES (model space, the default "coords": "minecraft")
  Units are blocks: 1.0 = 16 model pixels = the width of 2 player heads.
  Origin = the neck (top centre of the body).
  +X = the player's LEFT, +Y = DOWN, +Z = BEHIND the player.
  The body spans x -0.25..0.25, y 0..0.75, z -0.125..0.125. Feet are at y = 1.5.
  Meshes made in Blender/glTF (Y up, character facing +Z) can use "coords": "y_up".

MESH FILE FORMATS (all JSON; pick whichever is easiest)
  1) Interleaved, 8 floats per vertex (x y z  u v  nx ny nz) + quad indices:
       { "wing": { "v": [ ... ], "q": [0,1,2,3, ...] },  "glow": { ... } }
     Other layouts:  "layout": "pos uv normal" (default), any order, plus "color" (4)
     and "padN" to skip N floats. Normals/UVs may be left out of the layout.
  2) Separate arrays (flat or nested):
       { "groups": { "main": {
           "positions": [[x,y,z], ...],
           "uvs":       [[u,v], ...],        optional (UV 0,0 = top-left of the texture)
           "normals":   [[x,y,z], ...],      optional (computed if missing)
           "triangles": [0,1,2, ...]         or "quads": [...]
       } } }
     Index options: "q"/"quads", "t"/"tris"/"triangles", "indices" + "mode": "triangles"|"quads",
     or "faces": [[0,1,2], [3,4,5,6], [7,8,9,10,11]]  (polygons, fan-triangulated).
     No indices at all = consecutive vertices form triangles.
     "flip_v": true flips texture V (for UVs authored with 0,0 at the bottom-left).
  3) A single group without a name: { "positions": [...], "triangles": [...] }

  OBJ files can be imported from the in-game editor (Add tab). For glTF / GLB, or to
  convert outside the game, use mesh_convert.py (copied into this folder):
      python mesh_convert.py model.glb wings.json --scale 0.06

LAYER OPTIONS (each entry of "layers")
  mesh        file name in this folder (required)
  groups      ["a","b"] or "*" (default: all groups in the file)
  texture     PNG in this folder, or a resource id like "minecraft:textures/block/stone.png"
              (default: plain white)
  render      cutout_no_cull (default) | cutout | translucent | translucent_cull
              | emissive (additive glow, ignores light) | translucent_emissive
  hue         shift the texture's colours around the colour wheel, in degrees (-180..180)
  color       tint: "#RRGGBB", "#AARRGGBB", [r,g,b] or [r,g,b,a] (0-1 or 0-255);
              the editor's Opacity is this colour's alpha
  fullbright  true = ignore world lighting
  anchor      body (default, follows crouch lean) | head | root
  offset      [x,y,z] in blocks, applied after the anchor
  rotation    [x,y,z] degrees, applied X then Y then Z
  scale       number or [x,y,z]
  coords      minecraft (default) | y_up
  mirror      true = also draw a copy mirrored left/right
  physics     true (default) = trail behind movement/turning with idle sway
  hide_when_flying  true = hide while gliding with an elytra
  order       submit order (0 opaque, 1 translucent, 2 glow by default)
  enabled     false = skip this layer
  name        label shown in the editor
  folder      folder this layer belongs to (top-level "folders": {"Name": {"enabled": false}}
              switches a whole folder off)
