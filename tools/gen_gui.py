#!/usr/bin/env python3
"""Generate NeroEconomy's GUI panel sheets using only the Python stdlib.

One 256x256 sheet per screen; the top-left ``imageWidth x imageHeight`` rectangle is the panel that
``TexturedContainerScreen`` blits, the rest of the sheet stays transparent. Every recess and slot
socket below mirrors the coordinates hard-coded in the matching screen class, so the two must be
edited together.

Run directly (``python3 tools/gen_gui.py``) or through ``./gradlew genAssets``.
"""

from pathlib import Path
import random
import struct
import zlib

GUI_DIR = (Path(__file__).resolve().parents[1] / "common" / "src" / "main" / "resources"
           / "assets" / "neroeconomy" / "textures" / "gui")
SHEET = 256

CLEAR = (0, 0, 0, 0)
INK = (5, 8, 13, 255)
HULL = [(13, 17, 25, 255), (15, 20, 29, 255), (11, 15, 22, 255)]
PANEL = (8, 11, 17, 255)
PANEL_EDGE = (30, 40, 56, 255)
SOCKET = (20, 26, 36, 255)
SOCKET_HI = (44, 56, 74, 255)
DIVIDER = (18, 24, 34, 255)
RAIL = (11, 17, 25, 255)

# Economy accent family: the credits green, varied per screen.
ACCENTS = {
    "bank_terminal": (56, 230, 161, 255),
    "player_shop": (96, 232, 140, 255),
    "trading_terminal": (48, 214, 190, 255),
    "market_board": (150, 236, 120, 255),
    "vault": (72, 220, 150, 255),
    "direct_trade": (84, 226, 172, 255),
    "auto_trader": (120, 228, 150, 255),
}


def write_png(path: Path, buf):
    """PNG writer copied from tools/gen_textures.py, fed a row-major RGBA buffer."""
    raw = bytearray()
    for row in buf:
        raw.append(0)
        for pixel in row:
            raw.extend(pixel)

    def chunk(kind, data):
        return (struct.pack(">I", len(data)) + kind + data
                + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF))

    payload = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", SHEET, SHEET, 8, 6, 0, 0, 0))
    payload += chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b"")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(payload)
    return len(payload)


def new_sheet():
    return [[CLEAR] * SHEET for _ in range(SHEET)]


def put(buf, x, y, colour):
    if 0 <= x < SHEET and 0 <= y < SHEET:
        buf[y][x] = colour


def rect(buf, x0, y0, x1, y1, colour):
    for y in range(y0, y1):
        for x in range(x0, x1):
            put(buf, x, y, colour)


def darken(colour, factor):
    return (int(colour[0] * factor), int(colour[1] * factor), int(colour[2] * factor), 255)


def hull(buf, name, width, height, accent):
    """Noise hull, 1px accent frame and the INK inset line — the house panel recipe."""
    rng = random.Random(zlib.crc32(name.encode()) & 0xFFFF)
    accent_d = darken(accent, 0.45)
    for y in range(height):
        for x in range(width):
            buf[y][x] = rng.choice(HULL)
    for x in range(width):
        put(buf, x, 0, accent)
        put(buf, x, height - 1, accent_d)
    for y in range(height):
        put(buf, 0, y, accent)
        put(buf, width - 1, y, accent_d)
    for y in range(1, height - 1):
        put(buf, 1, y, INK)
        put(buf, width - 2, y, INK)
    for x in range(1, width - 1):
        put(buf, x, 1, INK)
        put(buf, x, height - 2, INK)


def recess(buf, x0, y0, x1, y1):
    """A sunken readout/list zone: dark fill, INK top/left, lit bottom/right."""
    rect(buf, x0, y0, x1, y1, PANEL)
    for x in range(x0, x1):
        put(buf, x, y0, INK)
        put(buf, x, y1 - 1, PANEL_EDGE)
    for y in range(y0, y1):
        put(buf, x0, y, INK)
        put(buf, x1 - 1, y, PANEL_EDGE)


def socket(buf, sx, sy):
    """An 18x18 vanilla-style slot socket for a slot at (sx, sy): the item is drawn at (sx, sy)."""
    rect(buf, sx - 1, sy - 1, sx + 17, sy + 17, SOCKET)
    for x in range(sx - 1, sx + 17):
        put(buf, x, sy - 1, INK)
        put(buf, x, sy + 16, SOCKET_HI)
    for y in range(sy - 1, sy + 17):
        put(buf, sx - 1, y, INK)
        put(buf, sx + 16, y, SOCKET_HI)


def header_rule(buf, width, y, accent):
    rect(buf, 6, y, width - 6, y + 1, darken(accent, 0.55))


def player_inventory(buf, top, hotbar):
    for row in range(3):
        for col in range(9):
            socket(buf, 8 + col * 18, top + row * 18)
    for col in range(9):
        socket(buf, 8 + col * 18, hotbar)


def gen_bank_terminal():
    """176x134 — balance well, two form wells, one action row. Mirrors BankTerminalScreen."""
    name = "bank_terminal"
    width, height = 176, 134
    accent = ACCENTS[name]
    buf = new_sheet()
    hull(buf, name, width, height, accent)
    header_rule(buf, width, 14, accent)
    recess(buf, 7, 16, 169, 32)     # balance readout
    recess(buf, 8, 46, 168, 68)     # recipient field well
    recess(buf, 8, 80, 168, 102)    # amount field well
    recess(buf, 7, 103, 169, 129)   # action row well
    return name, buf


def gen_player_shop():
    """176x214 — buyer well with the offer socket, owner well, player inventory."""
    name = "player_shop"
    width, height = 176, 214
    accent = ACCENTS[name]
    buf = new_sheet()
    hull(buf, name, width, height, accent)
    header_rule(buf, width, 14, accent)
    recess(buf, 7, 16, 169, 76)     # buyer region
    rect(buf, 9, 29, 167, 30, DIVIDER)  # caption rule inside the buyer region
    recess(buf, 7, 78, 169, 114)    # owner controls region
    rect(buf, 9, 89, 167, 90, DIVIDER)
    socket(buf, 12, 32)             # single offer slot
    player_inventory(buf, 132, 190)
    return name, buf


def gen_vault():
    """176x236 — six rows of nine vault sockets, a status well, player inventory. Mirrors VaultScreen."""
    name = "vault"
    width, height = 176, 236
    accent = ACCENTS[name]
    buf = new_sheet()
    hull(buf, name, width, height, accent)
    header_rule(buf, width, 14, accent)
    for row in range(6):
        for col in range(9):
            socket(buf, 8 + col * 18, 18 + row * 18)
    recess(buf, 7, 128, 169, 142)   # status line: stacks waiting off screen, or the empty-vault hint
    player_inventory(buf, 153, 211)
    return name, buf


def gen_direct_trade():
    """176x224 — two three-by-three panes with a status column between them, a credits row, a
    summary well, one action row and the player inventory. Mirrors DirectTradeScreen."""
    name = "direct_trade"
    width, height = 176, 224
    accent = ACCENTS[name]
    buf = new_sheet()
    hull(buf, name, width, height, accent)
    header_rule(buf, width, 14, accent)
    for row in range(3):
        for col in range(3):
            socket(buf, 8 + col * 18, 27 + row * 18)     # you give
            socket(buf, 116 + col * 18, 27 + row * 18)   # they give
    recess(buf, 63, 26, 113, 81)    # confirmation lamps and the countdown
    rect(buf, 65, 51, 111, 52, DIVIDER)
    recess(buf, 7, 82, 169, 99)     # credits row: field, Set, the other side's credits
    recess(buf, 7, 100, 169, 122)   # summary: what you give and what you get
    recess(buf, 7, 122, 169, 140)   # Confirm / Cancel
    player_inventory(buf, 143, 201)
    return name, buf


def gen_auto_trader():
    """256x234 — input and output panes with a progress well under them, the rule editor on the
    right, a two-line confirm well and the player inventory in the middle. Mirrors AutoTraderScreen."""
    name = "auto_trader"
    width, height = 256, 234
    accent = ACCENTS[name]
    buf = new_sheet()
    hull(buf, name, width, height, accent)
    header_rule(buf, width, 14, accent)
    for row in range(3):
        for col in range(3):
            socket(buf, 8 + col * 18, 27 + row * 18)    # inputs
            socket(buf, 70 + col * 18, 27 + row * 18)   # outputs
    recess(buf, 7, 84, 128, 126)     # the rule's status, today, in total, the daily value cap
    recess(buf, 131, 15, 251, 126)   # rule editor: toggles, id, three amounts, three buttons
    rect(buf, 133, 60, 249, 61, DIVIDER)
    rect(buf, 133, 108, 249, 109, DIVIDER)
    recess(buf, 7, 128, 249, 150)    # confirm lines
    for row in range(3):
        for col in range(9):
            socket(buf, 47 + col * 18, 153 + row * 18)
    for col in range(9):
        socket(buf, 47 + col * 18, 211)
    return name, buf


def gen_terminal(name):
    """256x226 — search row, status well, eight-row listing frame, two control rows, posting row."""
    width, height = 256, 226
    accent = ACCENTS[name]
    buf = new_sheet()
    hull(buf, name, width, height, accent)
    header_rule(buf, width, 16, accent)
    recess(buf, 7, 18, 249, 40)     # search field + Search button
    recess(buf, 7, 42, 249, 58)     # balance / counts status well
    recess(buf, 7, 62, 249, 156)    # listing frame: eight rows of 11px from y 65
    for row in range(1, 8):
        rect(buf, 9, 64 + row * 11, 247, 65 + row * 11, DIVIDER)
    rect(buf, 240, 65, 244, 153, RAIL)  # scroll rail channel
    recess(buf, 7, 158, 249, 202)   # navigation + action button rows
    recess(buf, 7, 202, 249, 224)   # posting row (board only; a hint line otherwise)
    return name, buf


def main():
    written = []
    for build in (gen_bank_terminal, gen_player_shop, gen_vault, gen_direct_trade, gen_auto_trader):
        name, buf = build()
        written.append((name, write_png(GUI_DIR / f"{name}.png", buf)))
    for board in ("trading_terminal", "market_board"):
        name, buf = gen_terminal(board)
        written.append((name, write_png(GUI_DIR / f"{name}.png", buf)))
    for name, size in written:
        print(f"wrote {name}.png ({size} bytes)")
    print(f"Generated NeroEconomy GUI sheets under {GUI_DIR}")


if __name__ == "__main__":
    main()
