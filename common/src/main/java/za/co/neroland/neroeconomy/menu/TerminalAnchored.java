package za.co.neroland.neroeconomy.menu;

import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * A menu that belongs to an economy block — the Economy Hub, opened at a Bank Terminal, Trading
 * Terminal, Market Board, Exchange Terminal or Merchant Stall, and the vault, opened at a Bank
 * Terminal — and is only valid while its player stays at it. A player with such a menu open is "at
 * a terminal", which is what lets two players trade directly from their own bases.
 */
public interface TerminalAnchored {
    /** The terminal's block entity; null on the client, which has none. */
    BlockEntity terminalAnchor();
}
