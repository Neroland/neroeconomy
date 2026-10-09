package za.co.neroland.neroeconomy.data;

import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.server.MinecraftServer;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.economy.PlayerLedgerExport;
import za.co.neroland.neroeconomy.market.MarketSavedData;
import za.co.neroland.neroeconomy.obligation.ObligationSavedData;
import za.co.neroland.neroeconomy.shop.ShopOwnershipState;
import za.co.neroland.neroeconomy.vault.VaultSection;

/**
 * The complete data-subject export: one JSON document covering every store that holds anything for
 * the subject. The ledger keys stay at the top level, where the export has always had them; the
 * market, obligation and shop stores each add one section, and the vault adds the subject's goods
 * as item id and count (never a stack's components, which can carry somebody else's data).
 *
 * <p>Nobody else is identified. The only account reference in the document is the subject's own
 * pseudonym; the other party of a ledger row is replaced by a fixed word, and the other stores
 * export the subject's own rows with the counterparty left out. Every timestamp is economy time
 * (see {@code EconomyClock}); {@code economy_time} and {@code wall_time} give both clocks' readings
 * when the export was taken, so the others can be read relative to them.
 */
public final class PlayerDataExport {
    private PlayerDataExport() { }

    /** Builds the whole document. Must run on the server thread, like every other store access. */
    public static JsonObject build(MinecraftServer server, UUID player) {
        if (server == null || player == null) throw new IllegalArgumentException("export subject");
        JsonObject json = ledger(EconomySavedData.get(server).export(player));
        long now = NeroEconomyCommon.CURRENCY_PROVIDER.now();
        json.addProperty("economy_time", now);
        json.addProperty("wall_time", NeroEconomyCommon.CURRENCY_PROVIDER.toWallMillis(now));
        json.add("market", MarketSavedData.get(server).exportFor(player));
        // The subject's own open orders on the order book.
        json.add("book", EconomyStore.get(server).book().exportFor(player));
        // Offers the subject sent or is being asked to answer, and whether they have muted offers.
        json.add("offers", EconomyStore.get(server).offers().exportFor(player));
        // The subject's auto-trade rules, their pause switch and what the rules filled today.
        json.add("rules", EconomyStore.get(server).rules().exportFor(player));
        // What the subject has put on the table in a direct trade that is open right now.
        json.add("trade_escrow", EconomyStore.get(server).tradeEscrow().exportFor(player));
        json.add("obligations", ObligationSavedData.get(server).exportFor(player));
        json.add("shops", ShopOwnershipState.get(server).exportFor(player));
        // Where the subject's Auto-Trader blocks stand; their rules are in the rules section above.
        json.add("auto_traders", EconomyStore.get(server).autoTraders().exportFor(player));
        json.add("vault", VaultSection.get(server).exportFor(player));
        // The subject's shares, index units and Savings Bonds, and their practice portfolio.
        json.add("exchange", EconomyStore.get(server).exchange().exportFor(player));
        // What the subject traded with the Merchant Houses today, and what of their trade with other
        // players counted toward reference prices today.
        json.add("merchant_houses", EconomyStore.get(server).houses().exportFor(player));
        return json;
    }

    /** The ledger section. Pure, so the layout is pinned by a unit test without a running game. */
    public static JsonObject ledger(PlayerLedgerExport export) {
        JsonObject json = new JsonObject();
        json.addProperty("player_uuid", export.player().toString());
        json.addProperty("account_reference", export.accountReference());
        json.addProperty("currency", export.currency());
        json.addProperty("balance", export.balance());
        json.addProperty("transaction_logging_opt_out", export.transactionLoggingOptOut());
        JsonObject chips = new JsonObject();
        chips.addProperty("economy_day", export.chipDay());
        chips.addProperty("count", export.chipCount());
        json.add("credit_chip_redemptions", chips);
        JsonObject vesting = new JsonObject();
        vesting.addProperty("locked_starting_balance", export.lockedStartingBalance());
        vesting.addProperty("vested", export.vested());
        json.add("starting_balance_vesting", vesting);
        JsonArray transactions = new JsonArray();
        export.transactions().forEach(row -> {
            JsonObject value = new JsonObject();
            value.addProperty("reason", row.type().name());
            value.addProperty("amount", row.amount());
            value.addProperty("currency", row.currency());
            value.addProperty("timestamp", row.timestamp());
            value.addProperty("source", row.sourceAccount());
            value.addProperty("destination", row.destinationAccount());
            transactions.add(value);
        });
        json.add("transactions", transactions);
        return json;
    }
}
