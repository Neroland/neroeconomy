#!/usr/bin/env python3
"""Generate NeroEconomy's deterministic PNG assets using only the Python stdlib.

Block art follows the house recipe: a seeded noise base in the block's own metal palette, a 1px
bevel (light top/left, dark bottom/right), corner rivets at (2,2)/(13,2)/(2,13)/(13,13) and a
per-block accent. Every block ships three sprites -- <id>.png (sides + particle), <id>_top.png and
<id>_front.png -- so the multi-element models can give each face its own read.

Run `python tools/gen_textures.py` for everything, or narrow it: `blocks`, `item`, `gui`.
After regenerating the block art, re-run `python tools/gen_animations.py` to rebuild the animated
front strips (this script writes plain 16x16 stills).
"""

from pathlib import Path
import math
import random
import struct
import sys
import zlib

ROOT = Path(__file__).resolve().parents[1] / "common" / "src" / "main" / "resources" / "assets" / "neroeconomy" / "textures"
INK = (10, 18, 28, 255)
HULL = (35, 55, 70, 255)
ACCENT = (56, 230, 161, 255)
LIGHT = (180, 255, 224, 255)
WARN = (255, 190, 72, 255)

S = 16


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


# --------------------------------------------------------------- canvas helpers

def canvas(fill=INK):
    return [[fill for _ in range(S)] for _ in range(S)]


def write_block(name, cv):
    png(ROOT / "block" / f"{name}.png", S, S, lambda x, y: cv[y][x])


def noise(cv, palette, rng):
    for y in range(S):
        for x in range(S):
            cv[y][x] = rng.choice(palette)


def bevel(cv, light, dark):
    """1px highlight on top/left, shadow on bottom/right."""
    for i in range(S):
        cv[0][i] = light
        cv[i][0] = light
        cv[S - 1][i] = dark
        cv[i][S - 1] = dark


def rivets(cv, colour):
    for (x, y) in ((2, 2), (13, 2), (2, 13), (13, 13)):
        cv[y][x] = colour


def rect(cv, x0, y0, x1, y1, colour):
    """Inclusive filled rectangle, clipped to the sprite."""
    for y in range(max(0, y0), min(S - 1, y1) + 1):
        for x in range(max(0, x0), min(S - 1, x1) + 1):
            cv[y][x] = colour


def disc(cv, cx, cy, inner, outer, colour):
    """Fill the annulus inner < d <= outer around a sub-pixel centre."""
    for y in range(S):
        for x in range(S):
            d = math.hypot(x - cx, y - cy)
            if inner < d <= outer:
                cv[y][x] = colour


def mix(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3)) + (255,)


# --------------------------------------------------------------- palettes

STEEL_L = (108, 120, 136, 255)
STEEL_D = (16, 22, 30, 255)

# Bank terminal -- vault steel with gold/amber trim.
VAULT = [(60, 60, 68, 255), (50, 51, 59, 255), (43, 45, 53, 255), (66, 67, 76, 255)]
VAULT_D = (34, 36, 43, 255)
VAULT_L = (86, 88, 98, 255)
GOLD = (226, 178, 66, 255)
GOLD_HI = (255, 214, 122, 255)
GOLD_D = (122, 88, 28, 255)

# Trading terminal -- cold hull steel with the economy teal.
TERM = [(34, 48, 58, 255), (28, 41, 51, 255), (40, 55, 65, 255), (24, 35, 45, 255)]
TERM_L = (96, 122, 134, 255)
TERM_D = (12, 20, 28, 255)
TEAL_D = (18, 86, 68, 255)
SCREEN = (7, 12, 18, 255)
GRID = (22, 48, 44, 255)

# Market board -- steel frame, cork interior, paper listings, amber pins.
FRAME = [(46, 50, 58, 255), (39, 43, 51, 255), (53, 57, 66, 255), (34, 38, 46, 255)]
FRAME_L = (96, 102, 114, 255)
FRAME_D = (18, 20, 26, 255)
CORK = [(86, 66, 48, 255), (74, 56, 40, 255), (96, 74, 54, 255)]
PAPER = (226, 214, 184, 255)
PAPER_D = (198, 184, 152, 255)
TEXT = (92, 80, 62, 255)
PIN_A = (255, 190, 72, 255)
PIN_R = (206, 72, 54, 255)

# Player shop -- warm timber stall, brass fittings, awning stripes.
WOOD = [(96, 64, 40, 255), (84, 56, 34, 255), (72, 48, 30, 255), (106, 72, 46, 255)]
WOOD_L = (146, 102, 64, 255)
WOOD_D = (34, 22, 14, 255)
BRASS = (196, 150, 80, 255)
CREAM = (240, 222, 190, 255)
AWN = (214, 110, 44, 255)
LAMP = (255, 204, 120, 255)


def stripes(cv, y0, y1, a, b, width=2):
    for y in range(y0, y1 + 1):
        for x in range(S):
            cv[y][x] = a if (x // width) % 2 == 0 else b


# --------------------------------------------------------------- bank terminal

def gen_bank_side():
    rng = random.Random(1101)
    cv = canvas()
    noise(cv, VAULT, rng)
    bevel(cv, VAULT_L, STEEL_D)
    for x in (4, 10):
        rect(cv, x, 2, x, 13, VAULT_D)
        rect(cv, x + 1, 2, x + 1, 13, VAULT_L)
    rect(cv, 1, 7, 14, 7, GOLD_D)
    rect(cv, 1, 8, 14, 8, VAULT_D)
    rivets(cv, GOLD)
    write_block("bank_terminal", cv)


def gen_bank_top():
    rng = random.Random(1102)
    cv = canvas()
    noise(cv, VAULT, rng)
    bevel(cv, VAULT_L, STEEL_D)
    rect(cv, 3, 3, 12, 12, VAULT_D)
    rect(cv, 4, 4, 11, 11, VAULT[0])
    rect(cv, 4, 6, 11, 6, GOLD_D)
    rect(cv, 5, 7, 10, 8, INK)
    rect(cv, 5, 9, 10, 9, GOLD)
    rivets(cv, GOLD)
    write_block("bank_terminal_top", cv)


def gen_bank_front():
    rng = random.Random(1103)
    cv = canvas()
    noise(cv, VAULT, rng)
    bevel(cv, VAULT_L, STEEL_D)
    rect(cv, 2, 2, 13, 13, VAULT_D)
    disc(cv, 7.5, 7.5, 0.0, 5.6, VAULT[0])
    disc(cv, 7.5, 7.5, 4.6, 5.6, GOLD_D)
    disc(cv, 7.5, 7.5, 3.9, 4.7, GOLD)
    for x in range(3, 13):
        cv[7][x] = GOLD_D if cv[7][x] != VAULT_D else cv[7][x]
    for y in range(3, 13):
        cv[y][7] = GOLD_D if cv[y][7] != VAULT_D else cv[y][7]
    disc(cv, 7.5, 7.5, 0.0, 2.4, GOLD)
    disc(cv, 7.5, 7.5, 0.0, 1.2, GOLD_HI)
    # Credit glyph: two bars struck through the hub.
    rect(cv, 6, 6, 9, 6, VAULT_D)
    rect(cv, 6, 9, 9, 9, VAULT_D)
    # Bolt heads around the door.
    for (bx, by) in ((3, 3), (12, 3), (3, 12), (12, 12)):
        cv[by][bx] = GOLD_HI
    rivets(cv, GOLD)
    write_block("bank_terminal_front", cv)


# --------------------------------------------------------------- trading terminal

def gen_terminal_side():
    rng = random.Random(1201)
    cv = canvas()
    noise(cv, TERM, rng)
    bevel(cv, TERM_L, TERM_D)
    for y in (4, 6, 8):
        rect(cv, 4, y, 12, y, TERM_D)
    rect(cv, 2, 4, 2, 11, TEAL_D)
    for y in (5, 8, 11):
        cv[y][2] = ACCENT
    rect(cv, 5, 11, 11, 12, TERM_D)
    rivets(cv, TERM_L)
    write_block("trading_terminal", cv)


def gen_terminal_top():
    rng = random.Random(1202)
    cv = canvas()
    noise(cv, TERM, rng)
    bevel(cv, TERM_L, TERM_D)
    disc(cv, 7.5, 7.5, 3.1, 4.3, TERM_D)
    disc(cv, 7.5, 7.5, 2.2, 3.3, TEAL_D)
    rect(cv, 6, 6, 9, 9, TERM_D)
    rect(cv, 7, 7, 8, 8, ACCENT)
    for (vx, vy) in ((4, 12), (11, 12), (4, 3), (11, 3)):
        cv[vy][vx] = TERM_D
    rivets(cv, TERM_L)
    write_block("trading_terminal_top", cv)


def gen_terminal_front():
    rng = random.Random(1203)
    cv = canvas()
    noise(cv, TERM, rng)
    bevel(cv, TERM_L, TERM_D)
    rect(cv, 1, 2, 14, 11, TERM_D)
    rect(cv, 2, 3, 13, 10, SCREEN)
    for x in range(2, 14):
        if x % 3 == 2:
            rect(cv, x, 3, x, 10, GRID)
    rect(cv, 2, 10, 13, 10, GRID)
    # Rising market line with a filled body underneath.
    line = {3: 9, 4: 8, 5: 9, 6: 7, 7: 8, 8: 6, 9: 5, 10: 6, 11: 4, 12: 4}
    fill = mix(SCREEN, TEAL_D, 0.7)
    for x, y in line.items():
        for fy in range(y + 1, min(y + 3, 11)):
            cv[fy][x] = fill
        cv[y][x] = ACCENT
    cv[3][12] = LIGHT
    # Ticker dashes along the bottom of the bezel.
    for x in range(3, 13, 3):
        cv[11][x] = ACCENT
    rivets(cv, TERM_L)
    write_block("trading_terminal_front", cv)


# --------------------------------------------------------------- market board

def gen_board_side():
    rng = random.Random(1301)
    cv = canvas()
    noise(cv, FRAME, rng)
    bevel(cv, FRAME_L, FRAME_D)
    rect(cv, 7, 1, 7, 14, FRAME_D)
    rect(cv, 8, 1, 8, 14, FRAME_L)
    for y in (4, 11):
        rect(cv, 2, y, 13, y, FRAME_D)
    rivets(cv, PIN_A)
    write_block("market_board", cv)


def gen_board_top():
    rng = random.Random(1302)
    cv = canvas()
    noise(cv, FRAME, rng)
    bevel(cv, FRAME_L, FRAME_D)
    rect(cv, 0, 0, 15, 2, mix(FRAME[0], PAPER, 0.35))
    rect(cv, 0, 3, 15, 3, PIN_A)
    rect(cv, 0, 4, 15, 4, FRAME_D)
    for x in range(2, 14, 3):
        cv[1][x] = PAPER_D
    rect(cv, 2, 9, 13, 9, FRAME_D)
    rivets(cv, PIN_A)
    write_block("market_board_top", cv)


def gen_board_front():
    rng = random.Random(1303)
    cv = canvas()
    noise(cv, CORK, rng)
    bevel(cv, FRAME_L, FRAME_D)
    rect(cv, 0, 12, 15, 15, FRAME[1])
    rect(cv, 0, 12, 15, 12, FRAME_D)
    # Header rail with amber index ticks.
    rect(cv, 1, 1, 14, 1, FRAME[3])
    for x in range(2, 14, 3):
        cv[1][x] = PIN_A
    # Four pinned listing cards.
    for (cx, cy, pin) in ((2, 3, PIN_A), (9, 3, PIN_R), (2, 8, PIN_R), (9, 8, PIN_A)):
        rect(cv, cx, cy, cx + 5, cy + 3, PAPER)
        rect(cv, cx, cy + 3, cx + 5, cy + 3, PAPER_D)
        rect(cv, cx + 1, cy + 1, cx + 4, cy + 1, TEXT)
        rect(cv, cx + 1, cy + 2, cx + 3, cy + 2, TEXT)
        cv[cy][cx + 2] = pin
    rivets(cv, PIN_A)
    write_block("market_board_front", cv)


# --------------------------------------------------------------- player shop

def gen_shop_side():
    rng = random.Random(1401)
    cv = canvas()
    noise(cv, WOOD, rng)
    bevel(cv, WOOD_L, WOOD_D)
    for y in (5, 10):
        rect(cv, 1, y, 14, y, WOOD_D)
        rect(cv, 1, y + 1, 14, y + 1, WOOD_L)
    rect(cv, 1, 2, 14, 2, BRASS)
    rivets(cv, BRASS)
    write_block("player_shop", cv)


def gen_shop_top():
    rng = random.Random(1402)
    cv = canvas()
    noise(cv, WOOD, rng)
    bevel(cv, WOOD_L, WOOD_D)
    stripes(cv, 0, 3, AWN, CREAM)
    rect(cv, 0, 4, 15, 4, WOOD_D)
    for y in (8, 12):
        rect(cv, 1, y, 14, y, WOOD_D)
    rect(cv, 1, 9, 14, 9, WOOD_L)
    rivets(cv, BRASS)
    write_block("player_shop_top", cv)


def gen_shop_front():
    rng = random.Random(1403)
    cv = canvas()
    noise(cv, WOOD, rng)
    bevel(cv, WOOD_L, WOOD_D)
    # Awning valance across the top two rows, with its shadow line.
    stripes(cv, 0, 1, AWN, CREAM)
    rect(cv, 0, 2, 15, 2, WOOD_D)
    # Brass counter lip.
    rect(cv, 1, 5, 14, 5, BRASS)
    rect(cv, 1, 6, 14, 6, WOOD_D)
    # Recessed goods panel with a lit serving hatch.
    rect(cv, 1, 7, 14, 14, mix(WOOD[2], WOOD_D, 0.45))
    rect(cv, 4, 8, 11, 12, INK)
    rect(cv, 5, 9, 10, 11, LAMP)
    for (gx, gy) in ((5, 10), (7, 10), (9, 10)):
        cv[gy][gx] = AWN
    cv[9][6] = CREAM
    cv[9][9] = CREAM
    # Crate stacks either side of the hatch.
    for (bx, by) in ((2, 9), (2, 12), (13, 9), (13, 12)):
        cv[by][bx] = BRASS
    rivets(cv, BRASS)
    write_block("player_shop_front", cv)


BLOCK_GENERATORS = (
    gen_bank_side, gen_bank_top, gen_bank_front,
    gen_terminal_side, gen_terminal_top, gen_terminal_front,
    gen_board_side, gen_board_top, gen_board_front,
    gen_shop_side, gen_shop_top, gen_shop_front,
)


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


def main():
    targets = [a for a in sys.argv[1:] if not a.startswith("-")] or ["blocks", "item"]
    if "blocks" in targets:
        for generator in BLOCK_GENERATORS:
            generator()
        print(f"Generated {len(BLOCK_GENERATORS)} NeroEconomy block sprites")
    if "item" in targets:
        chip()
    if "gui" in targets:
        # GUI panel sheets are owned by tools/gen_gui.py (layout-matched 256x256 sheets);
        # generating them here would clobber those. Run gen_gui.py instead.
        print("GUI sheets are generated by tools/gen_gui.py; skipping.")
    print(f"Generated NeroEconomy textures under {ROOT}")


if __name__ == "__main__":
    main()
