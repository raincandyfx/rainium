# Home page characters

The home page shows one of these at random; the arrows under the character step through the rest, round and round.

Each folder here is one character, recorded in game with Rainium's recorder. All are 30 fps recordings played as recorded:

| Folder | Recording |
|---|---|
| `halo/` | Gold halo |
| `wings/` | Dark red wings (mirrored so the wings sit on the left) |
| `pod/` | Floating robot pod |
| `spear/` | Long red spear |
| `doll/` | Small doll sitting on the head |

## Adding one

1. Get the recording's frames somewhere (a folder of `frame_00001.png` …). Record a bit more than one full cycle of the animation (some take over 10 seconds) so there's a clean place to loop.
2. Run `python3 docs/hero/tools/make_loop.py <that folder> <name> --step 1 --scale 1.0` for a 30 fps recording (for a 120 fps one leave both out: it then uses every 4th frame). Add `--flip` to mirror it (keep wings/halos on the left so they overlap the white band). It finds where the motion repeats and loops there; no frames are invented. Check the `seam` it prints: it should be close to the normal step, or the recording didn't cover a full cycle. Needs numpy, opencv-python, pillow and ffmpeg.
3. It prints a line like `{ id: "name", w: 748, h: 1326, label: "" }`. Add that to `CLIPS` in `docs/index.html`, fill in `label` (what the character looks like, for screen readers), add `k` to size it like the others (about 900 ÷ h), and `t:` the loop length in seconds it printed. Optional: `x` / `y` (where its centre sits, 0–1 across and down the area).

`make_clip.py` is the older tool that slowed 30 fps recordings down with made-up in-between frames; it's no longer used.

## The pod clip plays forwards, then backwards

The pod recording never repeated, so `pod/clip.mp4`, `clip.webm` and the stills were built from its frames played forwards and then backwards (no frames are invented). `t` for it in `CLIPS` is the length of the whole there-and-back run.
