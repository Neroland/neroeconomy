#!/usr/bin/env python3
"""Generate NeroEconomy's deterministic placeholder PNG assets using only the Python stdlib."""

from pathlib import Path
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1] / "common" / "src" / "main" / "resources" / "assets" / "neroeconomy" / "textures"
INK = (10, 18, 28, 255)
HULL = (35, 55, 70, 255)
ACCENT = (56, 230, 161, 255)
LIGHT = (180, 255, 224, 255)
WARN = (255, 190, 72, 255)


def png(path: Path, width: int, height: int, pixel):
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        for x in range(width):
            raw.extend(pixel(x, y))
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    payload = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    payload += chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b"")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(payload)


def block(name, variant):
    def pixel(x, y):
        if x in (0, 15) or y in (0, 15):
            return INK
        if (x + y + variant) % 7 == 0:
            return LIGHT
        if 4 <= x <= 11 and 5 <= y <= 10:
            return ACCENT if (x + variant) % 3 else WARN
        return HULL if (x + y) % 2 else (29, 46, 60, 255)
    png(ROOT / "block" / f"{name}.png", 16, 16, pixel)


def chip():
    def pixel(x, y):
        if x in (0, 15) or y in (0, 15):
            return (0, 0, 0, 0)
        if x in (2, 13) or y in (2, 13):
            return WARN
        if 5 <= x <= 10 and 5 <= y <= 10:
            return LIGHT if (x + y) % 2 else ACCENT
        return HULL
    png(ROOT / "item" / "credit_chip.png", 16, 16, pixel)


def panel(name):
    def pixel(x, y):
        if x < 3 or y < 3 or x >= 253 or y >= 189:
            return INK
        if y < 22:
            return HULL
        if x % 32 == 0 or y % 32 == 0:
            return (29, 46, 60, 255)
        return (13, 22, 32, 255)
    png(ROOT / "gui" / f"{name}.png", 256, 192, pixel)


for index, asset in enumerate(("bank_terminal", "player_shop", "trading_terminal", "market_board")):
    block(asset, index)
chip()
for asset in ("bank_terminal", "player_shop", "trading_terminal", "market_board"):
    panel(asset)
print(f"Generated NeroEconomy textures under {ROOT}")
