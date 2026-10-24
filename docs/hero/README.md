# Home page characters

The home page shows one of these at random; the arrows under the character step through the rest, round and round.

Each folder here is one character, recorded in game with Rainium's recorder (0.4.3 or later, 120 fps):

| Folder | Recording |
|---|---|
| `halo/` | Gold halo |
| `wings/` | Dark red wings (mirrored so the wings sit on the left) |
| `pod/` | Floating robot pod |
| `spear/` | Long red spear |
| `doll/` | Small doll sitting on the head |

## Adding one

1. Get the recording's frames somewhere (a folder of `frame_00001.png` …). About 9 seconds is enough; the animations repeat every 8.
2. Run `python3 docs/hero/tools/make_loop.py <that folder> <name>`. Add `--flip` to mirror it (keep wings/halos on the left so they overlap the white band). It uses every 4th frame of a 120 fps recording, so it plays in real time at 30 fps, finds where the motion repeats, and loops there. No frames are invented. Needs numpy, opencv-python, pillow and ffmpeg.
3. It prints a line like `{ id: "name", w: 748, h: 1326, label: "" }`. Add that to `CLIPS` in `docs/index.html`, fill in `label` (what the character looks like, for screen readers), and add `k: 0.75` like the others. Optional: `x` / `y` (where its centre sits, 0–1 across and down the area).

`make_clip.py` is the older tool that slowed 30 fps recordings down with made-up in-between frames; it's no longer used.
