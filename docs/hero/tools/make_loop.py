"""Turn an in-game Rainium recording into a looping home-page clip, using the recorded frames as they are.

    python3 make_loop.py <frames folder> <clip id> [--flip] [--step 4] [--fps 30] [--min 0.5]

Uses every --step'th recorded frame (120 fps recordings with --step 4 at --fps 30 play in real time), finds the
pair of frames where the motion matches best and loops between them (at least --min of the recording), and writes
docs/hero/<clip id>/: clip.mp4 (H.264) and clip.webm (VP9) with the picture in the top half and its transparency in
the bottom half, plus 00-31.webp stills for browsers without video. Prints the line to add to CLIPS in docs/index.html.
No frames are invented. Needs: numpy, opencv-python, pillow, ffmpeg.
"""
import argparse, glob, os, re, shutil, subprocess, tempfile
import cv2, numpy as np
from PIL import Image

ap = argparse.ArgumentParser()
ap.add_argument("src"); ap.add_argument("id")
ap.add_argument("--flip", action="store_true", help="mirror left-right")
ap.add_argument("--step", type=int, default=4, help="use every Nth recorded frame")
ap.add_argument("--fps", type=int, default=30)
ap.add_argument("--min", type=float, default=0.5, help="shortest loop, as a share of the recording")
ap.add_argument("--scale", type=float, default=0.5847, help="output pixels per recorded pixel")
a = ap.parse_args()

files = {int(re.search(r"(\d+)\.png$", f).group(1)): f for f in glob.glob(os.path.join(a.src, "frame_*.png"))}
first = min(files)
R = [n for n in sorted(files) if (n - first) % a.step == 0]
# stop at the first gap so the timing stays even
for k in range(1, len(R)):
    if R[k] - R[k - 1] != a.step: R = R[:k]; break

def raw(n):
    im = Image.open(files[n]).convert("RGBA")
    return im.transpose(Image.FLIP_LEFT_RIGHT) if a.flip else im

# pass 1: bounding box and small copies for matching
bb = None; small = []
for n in R:
    im = raw(n)
    b = im.getchannel("A").getbbox()
    bb = b if bb is None else (min(bb[0], b[0]), min(bb[1], b[1]), max(bb[2], b[2]), max(bb[3], b[3]))
    s = np.asarray(im.resize((im.width // 16, im.height // 16), Image.BILINEAR), np.float32) / 255
    s[..., :3] *= s[..., 3:4]
    small.append(s)
S = np.stack(small); N = len(S)

# pass 2: find the loop. A loop plays frames i..j-1 and then i again, so frame j (what would have come next)
# should look like frame i, and the motion into it should match too.
flat = S.reshape(N, -1)
flat = flat - flat.mean(0)
w, U = np.linalg.eigh(flat @ flat.T)                  # compress to the main directions of change
Z = U[:, ::-1][:, :64] * np.sqrt(np.maximum(w[::-1][:64], 0))
pose = np.sqrt(((Z[:, None, :] - Z[None, :, :]) ** 2).sum(2))
V = np.diff(Z, axis=0)
mot = np.sqrt(((V[:, None, :] - V[None, :, :]) ** 2).sum(2))
step_cost = np.median(np.diag(pose, 1))
best = None
minlen = max(2, int(N * a.min))
for i in range(0, N - minlen):
    for j in range(i + minlen, N):
        c = pose[i, j] + (0.5 * mot[i - 1, j - 1] if i > 0 else 0)
        c -= 0.02 * step_cost * (j - i) / N  # prefer longer loops when they're about as good
        if best is None or c < best[0]: best = (c, i, j)
_, i0, j0 = best
print(f"loop: recorded frames {R[i0]}..{R[j0 - 1]} ({j0 - i0} frames, {(j0 - i0) / a.fps:.1f} s); seam {pose[i0, j0]:.4f} vs normal step {step_cost:.4f}")

pad = 12; box = (bb[0] - pad, bb[1] - pad, bb[2] + pad, bb[3] + pad)
W = round((box[2] - box[0]) * a.scale); W += W % 2
H = round((box[3] - box[1]) * a.scale); H += H % 2

def stacked(n):
    f = np.asarray(raw(n).crop(box).resize((W, H), Image.LANCZOS))
    al = f[..., 3].astype(np.float32) / 255; rgb = f[..., :3].astype(np.float32)
    # spread edge colours into the transparent area so video compression doesn't leave dark fringes
    num = cv2.GaussianBlur(rgb * al[..., None], (0, 0), 5); den = cv2.GaussianBlur(al, (0, 0), 5)
    fill = num / np.maximum(den[..., None], 1e-4)
    rgb = np.where(al[..., None] > 0, rgb * al[..., None] + fill * (1 - al[..., None]), fill)
    return np.vstack([np.clip(rgb, 0, 255).astype(np.uint8), np.dstack([f[..., 3]] * 3)])

here = os.path.dirname(os.path.abspath(__file__))
out = os.path.join(os.path.dirname(here), a.id)
shutil.rmtree(out, ignore_errors=True); os.makedirs(out)
tmp = tempfile.mkdtemp()
loop = R[i0:j0]
for k, n in enumerate(loop): Image.fromarray(stacked(n), "RGB").save(f"{tmp}/{k:05d}.png", compress_level=1)
seq = f"{tmp}/%05d.png"; fps = str(a.fps)
subprocess.run(["ffmpeg", "-v", "error", "-y", "-framerate", fps, "-i", seq, "-c:v", "libx264", "-preset", "slow", "-crf", "24",
                "-profile:v", "high", "-level", "4.1", "-pix_fmt", "yuv420p", "-movflags", "+faststart", "-an", f"{out}/clip.mp4"], check=True)
subprocess.run(["ffmpeg", "-v", "error", "-y", "-framerate", fps, "-i", seq, "-c:v", "libvpx-vp9", "-crf", "36", "-b:v", "0",
                "-row-mt", "1", "-deadline", "good", "-cpu-used", "3", "-pix_fmt", "yuv420p", "-an", f"{out}/clip.webm"], check=True)
for k in range(32):
    raw(loop[round(k * (len(loop) - 1) / 32)]).crop(box).resize((W, H), Image.LANCZOS).save(f"{out}/{k:02d}.webp", quality=88, method=6)
shutil.rmtree(tmp)
print(f'{{ id: "{a.id}", w: {W}, h: {H}, label: "" }},')
