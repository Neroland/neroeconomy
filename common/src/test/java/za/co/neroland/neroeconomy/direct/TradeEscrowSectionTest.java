package za.co.neroland.neroeconomy.direct;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonArray;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.economy.StoreTesting;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.trade.TradeWorld;

/**
 * The trade-escrow section against a real store: what a side puts in is recorded and reported to
 * the prototype table, a save and a load keep it, and a load returns it to its owner's vault — so
 * a server that stops in the middle of a trade loses nothing and duplicates nothing.
 */
class TradeEscrowSectionTest {
    private static final String DIAMOND = TradeWorld.DIAMOND;
    private static final String IRON = TradeWorld.IRON;
    private final TradeWorld world = new TradeWorld();

    @AfterEach void done() {
        assertTrue(world.invariants());
        world.close();
    }

    private TradeEscrowSection escrow() { return world.store.tradeEscrow(); }

    @Test void whatASidePutsInIsHeldUnderItsOwnName() {
        UUID alice = world.player(1, 0L);
        UUID bob = world.player(2, 0L);
        AccountId account = TradeWorld.account(alice);
        assertTrue(escrow().isEmpty());
        assertTrue(escrow().held(account).isEmpty());

        assertTrue(escrow().replace(account, List.of(Lot.of(DIAMOND, 64), Lot.of(DIAMOND, 6), new Lot(IRON, 1, 3))));

        assertEquals(List.of(Lot.of(DIAMOND, 64), Lot.of(DIAMOND, 6), new Lot(IRON, 1, 3)), escrow().held(account));
        assertEquals(70L, escrow().total(account, DIAMOND, Lot.PRISTINE));
        assertEquals(1L, escrow().total(account, IRON, 3));
        assertEquals(0L, escrow().total(TradeWorld.account(bob), DIAMOND, Lot.PRISTINE));
        assertEquals(Set.of(account), escrow().owners());
        // The store counts it: this is what keeps a modified stack's prototype alive.
        assertEquals(70L, world.inStore(DIAMOND, Lot.PRISTINE));
        assertEquals(1L, world.inStore(IRON, 3));
        // A stack taken back out of a slot.
        assertTrue(escrow().replace(account, List.of(Lot.of(DIAMOND, 6))));
        assertEquals(6L, world.inStore(DIAMOND, Lot.PRISTINE));
        // Emptying every slot leaves no row.
        assertTrue(escrow().replace(account, List.of()));
        assertTrue(escrow().isEmpty());
    }

    @Test void aSideHoldsAtMostNineStacksAndOnlyAPlayerHoldsAny() {
        AccountId account = TradeWorld.account(world.player(1, 0L));
        List<Lot> nine = new ArrayList<>();
        for (int slot = 0; slot < TradeEscrowSection.MAX_STACKS; slot++) nine.add(Lot.of(DIAMOND, slot + 1));
        assertTrue(escrow().replace(account, nine));
        List<Lot> ten = new ArrayList<>(nine);
        ten.add(Lot.of(IRON, 1));
        assertFalse(escrow().replace(account, ten));
        assertEquals(nine, escrow().held(account));
        assertFalse(escrow().replace(SystemAccountId.RESERVE, List.of(Lot.of(IRON, 1))));
        assertFalse(escrow().replace(account, null));
        assertFalse(escrow().replace(null, List.of()));
        assertEquals(nine, escrow().held(account));
    }

    @Test void releaseHandsEverythingOverExactlyOnce() {
        AccountId account = TradeWorld.account(world.player(1, 0L));
        assertTrue(escrow().replace(account, List.of(Lot.of(DIAMOND, 5), Lot.of(IRON, 2))));
        assertEquals(List.of(Lot.of(DIAMOND, 5), Lot.of(IRON, 2)), escrow().release(account));
        assertTrue(escrow().release(account).isEmpty());
        assertTrue(escrow().release(null).isEmpty());
        assertTrue(escrow().isEmpty());
        assertEquals(0L, world.inStore(DIAMOND, Lot.PRISTINE));
    }

    @Test void aServerThatStopsMidTradeReturnsEverythingToItsOwnersVaultsOnLoad() {
        UUID alice = world.player(1, 100L);
        UUID bob = world.player(2, 100L);
        world.stock(alice, Lot.of(DIAMOND, 10));
        assertTrue(escrow().replace(TradeWorld.account(alice), List.of(Lot.of(DIAMOND, 64), new Lot(IRON, 1, 3))));
        assertTrue(escrow().replace(TradeWorld.account(bob), List.of(Lot.of(IRON, 12))));
        assertTrue(StoreTesting.encode(world.store).getAsJsonObject().has("trade_escrow"));

        // The save that happened within a second of the goods going in, then a crash and a restart.
        world.reload();

        // What was read back is exactly what was put in: nothing lost by the save.
        assertEquals(List.of(Lot.of(DIAMOND, 64), new Lot(IRON, 1, 3)), escrow().held(TradeWorld.account(alice)));
        assertEquals(List.of(Lot.of(IRON, 12)), escrow().held(TradeWorld.account(bob)));
        assertEquals(74L, world.inStore(DIAMOND, Lot.PRISTINE));
        // The load's recovery: each side's goods go to its own vault, and the escrow is empty.
        assertEquals(3, escrow().recover(world.env));
        assertTrue(escrow().isEmpty());
        assertEquals(74L, world.held(alice, DIAMOND));
        assertEquals(1L, world.held(alice, IRON, 3));
        assertEquals(12L, world.held(bob, IRON));
        assertEquals(74L, world.inStore(DIAMOND, Lot.PRISTINE));
        assertEquals(12L, world.inStore(IRON, Lot.PRISTINE));
        // A second recovery, and a second restart, find nothing: nothing comes back twice.
        assertEquals(0, escrow().recover(world.env));
        world.reload();
        assertEquals(0, world.store.recoverTradeEscrow());
        assertTrue(escrow().isEmpty());
        assertFalse(StoreTesting.encode(world.store).getAsJsonObject().has("trade_escrow"));
        assertEquals(74L, world.held(alice, DIAMOND));
        // No credits were involved at any point.
        assertEquals(100L, world.balance(alice));
        assertEquals(100L, world.balance(bob));
    }

    @Test void recoveryBooksEvenIntoAVaultThatIsFull() {
        UUID alice = world.player(1, 0L);
        world.env = world.env.with(TradeWorld.settings().withVault(1, 1, 4));
        world.stock(alice, Lot.of(TradeWorld.GOLD, 1));
        assertTrue(escrow().replace(TradeWorld.account(alice), List.of(Lot.of(DIAMOND, 9), Lot.of(IRON, 9))));

        assertEquals(List.of(Lot.of(DIAMOND, 9), Lot.of(IRON, 9)), escrow().returnToVault(world.env, TradeWorld.account(alice)));

        assertEquals(3, world.store.vault().stackCount(alice));
        assertEquals(9L, world.held(alice, DIAMOND));
        assertTrue(escrow().returnToVault(world.env, TradeWorld.account(alice)).isEmpty());
    }

    @Test void aStoreSavedBeforeDirectTradeExistedLoadsWithAnEmptyEscrow() {
        // An empty section is left out of the saved store, which is the form an older store has.
        assertFalse(StoreTesting.encode(world.store).getAsJsonObject().has("trade_escrow"));
        world.reload();
        assertTrue(escrow().isEmpty());
        assertEquals(0, world.store.recoverTradeEscrow());
        assertFalse(world.store.sectionsPresent().contains("trade_escrow"));
        assertTrue(escrow().replace(TradeWorld.account(world.player(1, 0L)), List.of(Lot.of(DIAMOND, 1))));
        assertTrue(world.store.sectionsPresent().contains("trade_escrow"));
    }

    @Test void erasureDestroysWhatThePlayerHadInEscrowAndExportListsItemAndCountOnly() {
        UUID alice = world.player(1, 0L);
        UUID bob = world.player(2, 0L);
        assertTrue(escrow().replace(TradeWorld.account(alice), List.of(Lot.of(DIAMOND, 5), new Lot(IRON, 1, 3))));
        assertTrue(escrow().replace(TradeWorld.account(bob), List.of(Lot.of(IRON, 2))));

        JsonArray exported = escrow().exportFor(alice);
        assertEquals(2, exported.size());
        assertEquals(DIAMOND, exported.get(0).getAsJsonObject().get("item").getAsString());
        assertEquals(5, exported.get(0).getAsJsonObject().get("count").getAsInt());
        // Never a payload: a stored stack can carry a name that is somebody's data.
        assertEquals(Set.of("item", "count"), exported.get(1).getAsJsonObject().keySet());
        assertFalse(exported.toString().contains(alice.toString()));

        assertEquals(List.of(Lot.of(DIAMOND, 5), new Lot(IRON, 1, 3)), escrow().forgetPlayer(alice));
        assertTrue(escrow().held(TradeWorld.account(alice)).isEmpty());
        assertEquals(0, escrow().exportFor(alice).size());
        // The other side's goods are theirs and stay.
        assertEquals(List.of(Lot.of(IRON, 2)), escrow().held(TradeWorld.account(bob)));
        assertTrue(escrow().forgetPlayer(alice).isEmpty());
        assertTrue(escrow().forgetPlayer(null).isEmpty());
        assertFalse(StoreTesting.encode(world.store).toString().contains(alice.toString() + "\",\"stacks"));
    }
}
