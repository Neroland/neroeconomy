#!/usr/bin/env python3
"""Normalise the gallery screenshots so every PNG is gallery-ready: capped at a max width
(default 1920 px — the CurseForge gallery target) and under a size cap (default 4 MiB), re-encoded
in place.

The capture harness (`/necgallery capture`) writes screenshots at the game window's native
resolution, so on a large/hi-DPI display they come out 2.5k+ wide and 5-8 MB each — too big for the
4 MB gallery limit and heavy in git. Run this after a capture, before committing:

    python tools/compress_screenshots.py

It keeps PNG (the Sync-Modrinth-gallery workflow globs ``*.png``) and only ever shrinks: any image
already within the caps is left effectively untouched. If a 1920 px PNG somehow still exceeds the
size cap it is progressively downscaled until it fits.

Options:
    --dir DIR        screenshots folder (default: the NeoForge 26.2 client capture folder)
    --max-width PX   max width in pixels (default: 1920)
    --max-mb MB      max file size in MiB (default: 4.0)
    --dry-run        report what would change without writing
"""
import argparse
import glob
import os
import subprocess
import sys

try:
    from PIL import Image, ImageFile
except ModuleNotFoundError:
    # Self-heal: Pillow is required for this tool (unlike gen_textures, which degrades without it).
    print("Pillow not found - installing it (pip install Pillow)...", flush=True)
    subprocess.run([sys.executable, "-m", "pip", "install", "--quiet", "Pillow"], check=False)
    from PIL import Image, ImageFile  # retry; if still missing this raises with a clear trace

# Tolerate a partially-written file rather than aborting the whole batch.
ImageFile.LOAD_TRUNCATED_IMAGES = True

DEFAULT_DIR = os.path.join("neoforge", "versions", "26.2", "runs", "client", "screenshots", "neroeconomy")


def fit(path, max_width, max_bytes, dry_run):
    """Re-encode one PNG so it is <= max_width wide and <= max_bytes; return (before, after, dims)."""
    before = os.path.getsize(path)
    with Image.open(path) as src:
        im = src.convert("RGB")
    w, h = im.size
    width = min(w, max_width)
    # Already small enough and within the width cap → leave it alone.
    if w <= max_width and before <= max_bytes:
        return before, before, (w, h)
    while True:
        out = im if width == w else im.resize((width, round(h * width / w)), Image.LANCZOS)
        if dry_run:
            return before, before, out.size  # don't write; size unknown but report dims
        out.save(path, "PNG", optimize=True, compress_level=9)
        after = os.path.getsize(path)
        if after <= max_bytes or width <= 640:
            return before, after, out.size
        width = int(width * 0.85)  # still too big → shrink and retry


def main():
    ap = argparse.ArgumentParser(description="Cap gallery screenshots to a max width + size.")
    ap.add_argument("--dir", default=DEFAULT_DIR)
    ap.add_argument("--max-width", type=int, default=1920)
    ap.add_argument("--max-mb", type=float, default=4.0)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    cap = int(args.max_mb * 1024 * 1024)
    files = sorted(glob.glob(os.path.join(args.dir, "*.png")))
    if not files:
        print(f"No PNGs found in {args.dir}", file=sys.stderr)
        return 1

    worst = 0
    for f in files:
        before, after, dims = fit(f, args.max_width, cap, args.dry_run)
        worst = max(worst, after)
        flag = "" if after <= cap else "  STILL OVER CAP!"
        print(f"{os.path.basename(f):24s} {before/1e6:5.1f}MB -> {after/1e6:5.2f}MB  {dims[0]}x{dims[1]}{flag}")
    print(f"Largest output: {worst/1e6:.2f} MB (cap {args.max_mb} MB){' [dry run]' if args.dry_run else ''}")
    return 0 if worst <= cap or args.dry_run else 2


if __name__ == "__main__":
    sys.exit(main())
