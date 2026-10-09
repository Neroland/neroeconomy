package za.co.neroland.neroeconomy.economy;

import net.minecraft.world.level.saveddata.SavedData;

/**
 * One part of the combined economy store ({@link EconomyStore}). A section keeps its own state,
 * codec, bounds, eraser and export, but it is saved as part of the one store: marking a section
 * dirty marks the store dirty, so a save can never persist half an operation.
 *
 * <p>The ledger, market, obligation and shop-owner sections were stores of their own before the
 * combined store existed, which is why a section is still a {@link SavedData}: the old types are
 * read once, on migration. A section that has an owner never marks itself dirty, so the old file
 * it was read from is not rewritten behind the combined store's back.
 */
public abstract class EconomySection extends SavedData {
    private EconomyStore owner;

    /**
     * Makes this section part of {@code store}. Called by the store only. Reading a section can
     * mark it dirty; that flag is cleared here, so the file it was read from is not rewritten.
     */
    final void attach(EconomyStore store) {
        owner = store;
        super.setDirty(false);
    }

    /** The store this section belongs to; null for a section that stands alone (a test, an old file). */
    protected final EconomyStore owner() { return owner; }

    /**
     * Whether an operator has closed the market (see {@code AdminSection}): trading refuses while
     * reads, collecting and refunds carry on. False for a section that stands alone.
     */
    protected final boolean marketClosed() {
        EconomyStore store = owner;
        return store != null && store.marketClosed();
    }

    /** The ledger of the store this section belongs to; null for a section that stands alone. */
    protected final EconomySavedData ledger() {
        EconomyStore store = owner;
        return store == null ? null : store.ledger();
    }

    @Override public void setDirty(boolean dirty) {
        EconomyStore store = owner;
        // Clearing the flag is always this section's own business: the game does it after a save.
        if (store == null || !dirty) super.setDirty(dirty);
        else store.setDirty();
    }

    /**
     * Marks the file this section was read from, back when it was a store of its own, to be
     * rewritten from the section as it stands now. Only used to carry an erasure into that file
     * while it still exists.
     */
    final void rewriteStandaloneFile() { super.setDirty(true); }

    /** Whether the section holds nothing at all, so it can be left out of the saved store. */
    public abstract boolean isEmpty();
}
