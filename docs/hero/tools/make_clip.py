"""Turn an in-game Rainium recording (a folder of transparent frame_00001.png ... frames) into a home-page clip.

    python3 make_clip.py <frames folder> <clip id> [--flip] [--first N] [--last N] [--slow 6]

Writes docs/hero/<clip id>/: clip.mp4 (H.264) and clip.webm (VP9), each with the picture in the top half and its
transparency in the bottom half, plus 00-31.webp stills for browsers without video. The clip plays the frames
--slow times slower than recorded (in-between frames are made with optical flow), forward and then backward,
easing to a stop at each end. Prints the size to add to CLIPS in docs/index.html.
Needs: numpy, opencv-python, pillow, ffmpeg.
"""
import argparse, glob, os, re, shutil, subprocess, tempfile
import cv2, numpy as np
from PIL import Image

ap = argparse.ArgumentParser()
ap.add_argument("src"); ap.add_argument("id")
ap.add_argument("--flip", action="store_true", help="mirror left-right")
ap.add_argument("--first", type=int); ap.add_argument("--last", type=int)
ap.add_argument("--slow", type=float, default=6)
ap.add_argument("--work", help="folder to keep the in-between frames in, so a stopped run can pick up where it left off")
ap.add_argument("--scale", type=float, default=0.5847, help="output pixels per recorded pixel (same as the first clip)")
a = ap.parse_args()

files = {int(re.search(r"(\d+)\.png$", f).group(1)): f for f in glob.glob(os.path.join(a.src, "frame_*.png"))}
R = [n for n in sorted(files) if (a.first is None or n >= a.first) and (a.last is None or n <= a.last)]
def raw(n):
    im = Image.open(files[n]).convert("RGBA")
    return im.transpose(Image.FLIP_LEFT_RIGHT) if a.flip else im
bb = None
for n in R:
    b = raw(n).getchannel("A").getbbox()
    bb = b if bb is None else (min(bb[0], b[0]), min(bb[1], b[1]), max(bb[2], b[2]), max(bb[3], b[3]))
pad = 12; box = (bb[0] - pad, bb[1] - pad, bb[2] + pad, bb[3] + pad)
W = round((box[2] - box[0]) * a.scale); W += W % 2
H = round((box[3] - box[1]) * a.scale); H += H % 2

cache = {}
def load(k):  # k = index into R
    if k not in cache:
        if len(cache) > 3: cache.pop(min(cache))
        x = np.asarray(raw(R[k]).crop(box).resize((W, H), Image.LANCZOS)).astype(np.float32) / 255
        x[..., :3] *= x[..., 3:4]
        cache[k] = x
    return cache[k]
def gray(x): return np.clip(x[..., :3].mean(2) * 200 + x[..., 3] * 55, 0, 255).astype(np.uint8)
yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
def warp(img, flow, s): return cv2.remap(img, xx + flow[..., 0] * s, yy + flow[..., 1] * s, cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=0)

# forward timeline: 1/slow recorded frame per output frame, easing to a stop over 1 s at each end
L = len(R) - 1
vel = []; p = 0
while p < L: vel.append(1 / a.slow); p += 1 / a.slow
n = len(vel); E = 30
for k in range(min(E, n // 2)):
    e = 0.5 - 0.5 * np.cos(np.pi * (k + 0.5) / E); vel[k] *= e; vel[n - 1 - k] *= e
pos = np.concatenate([[0], np.cumsum(vel)]); pos *= L / pos[-1]

flows = {}
def frame(p):
    i = min(int(p), L - 1); w = float(p - i)
    if w < 1e-6: m = load(i)
    elif w > 1 - 1e-6: m = load(i + 1)
    else:
        if i not in flows:
            flows.clear()
            g0, g1 = gray(load(i)), gray(load(i + 1))
            flows[i] = (cv2.calcOpticalFlowFarneback(g0, g1, None, 0.5, 4, 21, 5, 7, 1.5, 0),
                        cv2.calcOpticalFlowFarneback(g1, g0, None, 0.5, 4, 21, 5, 7, 1.5, 0))
        f01, f10 = flows[i]
        m = warp(load(i), f10, w) * (1 - w) + warp(load(i + 1), f01, 1 - w) * w
    al = m[..., 3]
    rgb = np.where(al[..., None] > 1e-4, m[..., :3] / np.maximum(al[..., None], 1e-4), 0)
    f = (np.clip(np.dstack([rgb, al]), 0, 1) * 255).astype(np.uint8)
    # spread edge colours into the transparent area so video compression doesn't leave dark fringes
    al = f[..., 3].astype(np.float32) / 255; rgb = f[..., :3].astype(np.float32)
    num = cv2.GaussianBlur(rgb * al[..., None], (0, 0), 5); den = cv2.GaussianBlur(al, (0, 0), 5)
    fill = num / np.maximum(den[..., None], 1e-4)
    rgb = np.where(al[..., None] > 0, rgb * al[..., None] + fill * (1 - al[..., None]), fill)
    return np.vstack([np.clip(rgb, 0, 255).astype(np.uint8), np.dstack([f[..., 3]] * 3)])

here = os.path.dirname(os.path.abspath(__file__))
out = os.path.join(os.path.dirname(here), a.id); os.makedirs(out, exist_ok=True)
tmp = a.work or tempfile.mkdtemp()
shutil.rmtree(f"{tmp}/seq", ignore_errors=True); os.makedirs(f"{tmp}/u", exist_ok=True); os.makedirs(f"{tmp}/seq")
for k, p in enumerate(pos):
    if not os.path.exists(f"{tmp}/u/{k:05d}.png"):
        Image.fromarray(frame(p), "RGB").save(f"{tmp}/u/{k:05d}.tmp.png", compress_level=1)
        os.replace(f"{tmp}/u/{k:05d}.tmp.png", f"{tmp}/u/{k:05d}.png")
U = len(pos); order = list(range(U)) + list(range(U - 2, 0, -1))
for j, k in enumerate(order): os.symlink(f"{tmp}/u/{k:05d}.png", f"{tmp}/seq/{j:05d}.png")
seq = f"{tmp}/seq/%05d.png"
subprocess.run(["ffmpeg", "-v", "error", "-y", "-framerate", "30", "-i", seq, "-c:v", "libx264", "-preset", "slow", "-crf", "26",
                "-profile:v", "high", "-level", "4.0", "-pix_fmt", "yuv420p", "-movflags", "+faststart", "-an", f"{out}/clip.mp4"], check=True)
subprocess.run(["ffmpeg", "-v", "error", "-y", "-framerate", "30", "-i", seq, "-c:v", "libvpx-vp9", "-crf", "38", "-b:v", "0",
                "-row-mt", "1", "-deadline", "good", "-cpu-used", "3", "-pix_fmt", "yuv420p", "-an", f"{out}/clip.webm"], check=True)
# stills: 32 evenly spaced recorded frames
for k in range(32):
    im = raw(R[round(k * L / 31)]).crop(box).resize((W, H), Image.LANCZOS)
    im.save(f"{out}/{k:02d}.webp", quality=88, method=6)
if not a.work: shutil.rmtree(tmp)
print(f'{{ id: "{a.id}", w: {W}, h: {H} }}  ({len(order) / 30:.1f} s cycle)')
