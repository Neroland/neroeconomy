package za.co.neroland.neroeconomy.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.hub.HubPages.RowRef;
import za.co.neroland.neroeconomy.hub.HubRouter.Result;

/**
 * The Admin tab's intents: every one is refused below level 3 whatever the client shows, every one
 * that changes the economy is refused while {@code adminMutationsEnabled} is off, and each good
 * one reaches exactly its own server call.
 */
class HubRouterAdminTest {
    private static final UUID TARGET = new UUID(9L, 1L);
    private static final UUID LINE = new UUID(9L, 2L);
    private static final Map<UUID, RowRef> ROWS = Map.of(
            TARGET, new RowRef(RowRef.Kind.ADMIN_TARGET, new UUID(7L, 7L), "", 1),
            LINE, new RowRef(RowRef.Kind.ADMIN_LINE, null, "", 0));

    private static HubRouter.Context operator(boolean mutations) {
        return new HubRouter.Context(HubTab.ADMIN, ROWS, false, true, true, mutations);
    }

    private static HubRouter.Intent intent(HubAction action, UUID target, long a, String text) {
        return new HubRouter.Intent(UUID.randomUUID(), HubTab.ADMIN, action, target, a, 0L, text);
    }

    /** A well-formed intent for each admin action. */
    private static HubRouter.Intent good(HubAction action) {
        return switch (action) {
            case ADMIN_VIEW -> intent(action, null, HubAction.AdminView.PLAYER.ordinal(), "");
            case ADMIN_SIM_STEP -> intent(action, null, 5L, "");
            case ADMIN_MARKET_FREEZE -> intent(action, null, 1L, "");
            case ADMIN_TARGET -> intent(action, null, 0L, "Alex");
            case ADMIN_EXPORT, ADMIN_ERASE -> intent(action, TARGET, 0L, "");
            case ADMIN_GRANT, ADMIN_TAKE, ADMIN_SET -> intent(action, TARGET, 250L, "");
            default -> intent(action, null, 0L, "");
        };
    }

    private static List<HubAction> adminActions() {
        return java.util.Arrays.stream(HubAction.values()).filter(HubAction::adminOnly).toList();
    }

    @Test void everyAdminActionIsRefusedBelowLevelThree() {
        assertEquals(11, adminActions().size());
        for (HubAction action : adminActions()) {
            for (boolean mutations : new boolean[] { true, false }) {
                // Even a client that claims to be on the Admin tab, with the server thinking so too.
                HubRouter.Context player = new HubRouter.Context(HubTab.ADMIN, ROWS, false, true, false, mutations);
                HubRouterTest.Recorder recorder = new HubRouterTest.Recorder();
                assertEquals(Result.NOT_ADMIN, HubRouter.route(good(action), player, recorder), action.name());
                assertTrue(recorder.calls.isEmpty(), action.name());
                assertTrue(recorder.seen.isEmpty(), action + " spent a request id");
            }
        }
    }

    @Test void everyAdminChangeIsRefusedWhileMutationsAreDisabled() {
        List<HubAction> changes = List.of(HubAction.ADMIN_SIM_STEP, HubAction.ADMIN_MARKET_FREEZE,
                HubAction.ADMIN_ACCEPT_FRESH, HubAction.ADMIN_GRANT, HubAction.ADMIN_TAKE, HubAction.ADMIN_SET);
        for (HubAction action : adminActions()) {
            assertEquals(changes.contains(action), action.adminMutation(), action.name());
            HubRouterTest.Recorder recorder = new HubRouterTest.Recorder();
            Result result = HubRouter.route(good(action), operator(false), recorder);
            if (action.adminMutation()) {
                assertEquals(Result.MUTATIONS_OFF, result, action.name());
                assertTrue(recorder.calls.isEmpty(), action.name());
            } else {
                // Reads, the check, and the data subject's rights do not depend on the switch.
                assertEquals(Result.OK, result, action.name());
            }
        }
        // The old five-part context allows no change at all.
        assertEquals(Result.MUTATIONS_OFF, HubRouter.route(good(HubAction.ADMIN_GRANT),
                new HubRouter.Context(HubTab.ADMIN, ROWS, false, true, true), new HubRouterTest.Recorder()));
    }

    @Test void eachAdminActionReachesItsOwnCall() {
        Map<HubAction, String> expected = new java.util.EnumMap<>(HubAction.class);
        expected.put(HubAction.ADMIN_VIEW, "adminView PLAYER");
        expected.put(HubAction.ADMIN_SIM_STEP, "adminSimStep 5");
        expected.put(HubAction.ADMIN_MARKET_FREEZE, "adminMarketFreeze true");
        expected.put(HubAction.ADMIN_RELOAD_CHECK, "adminReloadCheck");
        expected.put(HubAction.ADMIN_ACCEPT_FRESH, "adminAcceptFresh");
        expected.put(HubAction.ADMIN_TARGET, "adminTarget Alex");
        expected.put(HubAction.ADMIN_EXPORT, "adminExport ADMIN_TARGET");
        expected.put(HubAction.ADMIN_ERASE, "adminErase ADMIN_TARGET");
        expected.put(HubAction.ADMIN_GRANT, "adminGrant 250");
        expected.put(HubAction.ADMIN_TAKE, "adminTake 250");
        expected.put(HubAction.ADMIN_SET, "adminSet 250");
        assertEquals(adminActions().size(), expected.size());
        for (HubAction action : adminActions()) {
            HubRouterTest.Recorder recorder = new HubRouterTest.Recorder();
            assertEquals(Result.OK, HubRouter.route(good(action), operator(true), recorder), action.name());
            assertEquals(List.of(expected.get(action)), recorder.calls);
        }
    }

    @Test void badNumbersTextAndRowsAreRefused() {
        HubRouter.Context context = operator(true);
        assertEquals(Result.BAD_NUMBER, HubRouter.route(intent(HubAction.ADMIN_SIM_STEP, null, 0L, ""), context,
                new HubRouterTest.Recorder()));
        assertEquals(Result.BAD_NUMBER, HubRouter.route(intent(HubAction.ADMIN_SIM_STEP, null,
                HubRouter.MAX_ADMIN_STEPS + 1L, ""), context, new HubRouterTest.Recorder()));
        assertEquals(Result.BAD_NUMBER, HubRouter.route(intent(HubAction.ADMIN_VIEW, null, 99L, ""), context,
                new HubRouterTest.Recorder()));
        assertEquals(Result.BAD_NUMBER, HubRouter.route(intent(HubAction.ADMIN_MARKET_FREEZE, null, 2L, ""), context,
                new HubRouterTest.Recorder()));
        assertEquals(Result.BAD_NUMBER, HubRouter.route(intent(HubAction.ADMIN_GRANT, TARGET, 0L, ""), context,
                new HubRouterTest.Recorder()));
        assertEquals(Result.BAD_NUMBER, HubRouter.route(intent(HubAction.ADMIN_SET, TARGET, -1L, ""), context,
                new HubRouterTest.Recorder()));
        assertEquals(Result.OK, HubRouter.route(intent(HubAction.ADMIN_SET, TARGET, 0L, ""), context,
                new HubRouterTest.Recorder()));
        assertEquals(Result.BAD_TEXT, HubRouter.route(intent(HubAction.ADMIN_TARGET, null, 0L, ""), context,
                new HubRouterTest.Recorder()));
        assertEquals(Result.BAD_TEXT, HubRouter.route(intent(HubAction.ADMIN_TARGET, null, 0L, "two words"), context,
                new HubRouterTest.Recorder()));
        // The actions on an account name the looked-up row, never a table line or nothing.
        assertEquals(Result.NO_ROW, HubRouter.route(intent(HubAction.ADMIN_ERASE, LINE, 0L, ""), context,
                new HubRouterTest.Recorder()));
        assertEquals(Result.NO_ROW, HubRouter.route(intent(HubAction.ADMIN_GRANT, null, 5L, ""), context,
                new HubRouterTest.Recorder()));
        assertEquals(Result.NO_ROW, HubRouter.route(intent(HubAction.ADMIN_EXPORT, new UUID(1L, 1L), 0L, ""), context,
                new HubRouterTest.Recorder()));
    }

    @Test void aChangeSpendsItsRequestIdOnceAndTheFrozenEconomyStopsCreditChangesOnly() {
        HubRouterTest.Recorder recorder = new HubRouterTest.Recorder();
        HubRouter.Intent grant = good(HubAction.ADMIN_GRANT);
        assertEquals(Result.OK, HubRouter.route(grant, operator(true), recorder));
        assertEquals(Result.STALE_REQUEST, HubRouter.route(grant, operator(true), recorder));
        assertEquals(1, recorder.calls.size());
        HubRouter.Context frozen = new HubRouter.Context(HubTab.ADMIN, ROWS, true, true, true, true);
        assertEquals(Result.FROZEN, HubRouter.route(good(HubAction.ADMIN_GRANT), frozen, new HubRouterTest.Recorder()));
        assertEquals(Result.FROZEN, HubRouter.route(good(HubAction.ADMIN_SIM_STEP), frozen, new HubRouterTest.Recorder()));
        // Accepting a fresh start is exactly what a frozen economy needs; export and erasure still work.
        assertEquals(Result.OK, HubRouter.route(good(HubAction.ADMIN_ACCEPT_FRESH), frozen, new HubRouterTest.Recorder()));
        assertEquals(Result.OK, HubRouter.route(good(HubAction.ADMIN_ERASE), frozen, new HubRouterTest.Recorder()));
        assertEquals(Result.OK, HubRouter.route(good(HubAction.ADMIN_MARKET_FREEZE), frozen, new HubRouterTest.Recorder()));
    }

    @Test void anAdminActionFromAnotherTabIsRefused() {
        HubRouter.Context wallet = new HubRouter.Context(HubTab.WALLET, ROWS, false, true, true, true);
        assertEquals(Result.WRONG_TAB, HubRouter.route(good(HubAction.ADMIN_VIEW), wallet, new HubRouterTest.Recorder()));
        assertFalse(HubAction.WALLET_PAY.adminOnly());
        assertTrue(HubTab.ADMIN.adminOnly() && !HubTab.ADMIN.visible(false) && HubTab.ADMIN.built());
    }
}
