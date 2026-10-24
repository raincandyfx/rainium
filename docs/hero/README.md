# Home page characters

The home page shows one of these at random; the arrows under the character step through the rest, round and round.

Each folder is one character, recorded in game with Rainium's recorder (0.4.2 or later):

| Folder | Recording |
|---|---|
| `sway/` | Gold halo, swaying (frames 5–132) |
| `ronova/` | Dark red wings, idle (mirrored so the wings sit on the left) |

## Adding one

1. Unzip the recording somewhere (a folder of `frame_00001.png` …).
2. Run `python3 docs/hero/tools/make_clip.py <that folder> <name>`. Add `--flip` to mirror it (keep wings/halos on the left so they overlap the white band), and `--first N` / `--last N` to use only part of the recording. It needs numpy, opencv-python, pillow and ffmpeg, and takes a while.
3. It prints a line like `{ id: "name", w: 704, h: 1322 }`. Add that to `CLIPS` in `docs/index.html`, with a `label` describing the character for screen readers. Optional: `k` (size on screen, 1 = as recorded), `x` / `y` (where its centre sits, 0–1 across and down the area).

The clips play the recording 6× slower than it was captured, forward and then backward (so the end never jumps back to the start).
