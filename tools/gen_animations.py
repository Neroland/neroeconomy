#!/usr/bin/env python3
"""Turn the block front sprites into animated pulse strips (stdlib only).

Each <id>_front.png becomes a vertical frame strip whose accent pixels (the gold vault trim, the
teal market line, the amber pins, the shop lamp) breathe in brightness -- HSV value only, so the
colour never washes out. Frame 0 is the untouched original, which keeps the script idempotent: the
first 16x16 frame is re-read as the base on every run. A sibling <id>_front.png.mcmeta carries the
frametime so the atlas animates it with no model or Java change.

Run after tools/gen_textures.py:  python tools/gen_animations.py
"""

from pathlib import Path
import colorsys
import json
import math
import struct
import zlib

BLOCK = Path(__file__).resolve().parents[1] / "common" / "src" / "main" / "resources" / "assets" \
    / "neroeconomy" / "textures" / "block"

# name, frames, frametime (ticks), amplitude, hue window (or None for every accent)
PULSE = [
    ("bank_terminal_front", 8, 3, 0.28, (0.06, 0.18)),
    ("trading_terminal_front", 8, 3, 0.28, (0.32, 0.58)),
    ("market_board_front", 8, 3, 0.24, (0.00, 0.18)),
    ("player_shop_front", 8, 3, 0.20, (0.02, 0.18)),
    ("auto_trader_front", 8, 3, 0.26, (0.06, 0.48)),
    ("exchange_terminal_front", 8, 3, 0.26, (0.06, 0.18)),
    ("merchant_stall_front", 8, 3, 0.20, (0.06, 0.18)),
]


# --------------------------------------------------------------- PNG in/out

def read_png(path):
    """Decode an 8-bit RGB/RGBA PNG into (width, height, rows of RGBA tuples)."""
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"{path.name} is not a PNG")
    pos = 8
    header = None
    idat = bytearray()
    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        kind = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            header = struct.unpack(">IIBBBBB", body)
        elif kind == b"IDAT":
            idat.extend(body)
        elif kind == b"IEND":
            break
    if header is None:
        raise ValueError(f"{path.name} has no IHDR")
    width, height, depth, colour, compression, filt, interlace = header
    if depth != 8 or colour not in (2, 6) or interlace != 0 or compression != 0 or filt != 0:
        raise ValueError(f"{path.name}: only 8-bit non-interlaced RGB/RGBA is supported")
    channels = 4 if colour == 6 else 3
    raw = zlib.decompress(bytes(idat))
    stride = width * channels
    out = []
    previous = bytearray(stride)
    pos = 0
    for _ in range(height):
        ftype = raw[pos]
        pos += 1
        line = bytearray(raw[pos:pos + stride])
        pos += stride
        for i in range(stride):
            a = line[i - channels] if i >= channels else 0
            b = previous[i]
            c = previous[i - channels] if i >= channels else 0
            if ftype == 1:
                line[i] = (line[i] + a) & 0xFF
            elif ftype == 2:
                line[i] = (line[i] + b) & 0xFF
            elif ftype == 3:
                line[i] = (line[i] + ((a + b) >> 1)) & 0xFF
            elif ftype == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
            elif ftype != 0:
                raise ValueError(f"{path.name}: unknown filter {ftype}")
        row = []
        for x in range(width):
            base = x * channels
            if channels == 4:
                row.append((line[base], line[base + 1], line[base + 2], line[base + 3]))
            else:
                row.append((line[base], line[base + 1], line[base + 2], 255))
        out.append(row)
        previous = line
    return width, height, out


def write_png(path, rows):
    height = len(rows)
    width = len(rows[0])
    raw = bytearray()
    for row in rows:
        raw.append(0)
        for pixel in row:
            raw.extend(pixel)
    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)
    payload = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    payload += chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b"")
    path.write_bytes(payload)


def write_mcmeta(path, frametime):
    # CRLF, like every other tracked text file in the working tree, so a regeneration changes nothing.
    with open(str(path) + ".mcmeta", "w", encoding="utf-8", newline="\r\n") as fh:
        json.dump({"animation": {"frametime": frametime}}, fh, indent=2)
        fh.write("\n")


# --------------------------------------------------------------- pulse

def accent_mask(rows, hue):
    """Saturated, brighter-than-median pixels inside the hue window."""
    values = [max(p[:3]) / 255 for row in rows for p in row if p[3]]
    median = sorted(values)[len(values) // 2] if values else 0.5
    mask = []
    for y, row in enumerate(rows):
        for x, (r, g, b, a) in enumerate(row):
            if not a:
                continue
            h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
            if s > 0.32 and v > max(0.42, median * 1.05) and (hue is None or hue[0] <= h <= hue[1]):
                mask.append((x, y))
    return mask


def lit(pixel, factor):
    r, g, b, a = pixel
    h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
    v = min(1.0, v * factor)
    s = min(1.0, s * (1.0 + 0.12 * (factor - 1)))
    r, g, b = (int(c * 255) for c in colorsys.hsv_to_rgb(h, s, v))
    return (r, g, b, a)


def pulse_strip(base, frames, amplitude, hue):
    mask = accent_mask(base, hue)
    strip = []
    for i in range(frames):
        factor = 1.0 + amplitude * math.sin(2 * math.pi * i / frames)
        frame = [list(row) for row in base]
        if abs(factor - 1.0) > 1e-9:  # frame 0 stays byte-identical -> re-running is idempotent
            for (x, y) in mask:
                frame[y][x] = lit(frame[y][x], factor)
        strip.extend(frame)
    return strip, len(mask)


def main():
    made = []
    for name, frames, frametime, amplitude, hue in PULSE:
        path = BLOCK / f"{name}.png"
        if not path.exists():
            print("  miss", name)
            continue
        width, _height, rows = read_png(path)
        base = rows[:width]  # frame 0 only -- re-running never compounds the pulse
        strip, lit_pixels = pulse_strip(base, frames, amplitude, hue)
        write_png(path, strip)
        write_mcmeta(path, frametime)
        made.append(f"{name} (pulse {frames}f @{frametime}, {lit_pixels} accent px)")
    print(f"animated {len(made)} textures:")
    for entry in made:
        print("  ", entry)


if __name__ == "__main__":
    main()
