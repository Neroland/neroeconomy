package za.co.neroland.neroeconomy.hub;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.neroeconomy.admin.AdminOps;
import za.co.neroland.neroeconomy.admin.AdminSection;
import za.co.neroland.neroeconomy.api.ExchangeApi;
import za.co.neroland.neroeconomy.commodity.CommodityData;
import za.co.neroland.neroeconomy.commodity.CommodityDefinition;
import za.co.neroland.neroeconomy.commodity.CommodityRegistry;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.economy.BudgetKind;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.economy.KnownAccounts;
import za.co.neroland.neroeconomy.economy.SupplyReport;
import za.co.neroland.neroeconomy.economy.TransactionType;
import za.co.neroland.neroeconomy.exchange.StockState;
import za.co.neroland.neroeconomy.house.HouseData;
import za.co.neroland.neroeconomy.house.HouseDefinition;
import za.co.neroland.neroeconomy.house.HouseRegistry;
import za.co.neroland.neroeconomy.house.HouseSection;
import za.co.neroland.neroeconomy.simulation.Simulation;

/**
 * The Admin tab of one operator's open hub: which view they are on, the last reload check, and the
 * account they looked up. Every call checks again that the player is a level-3 operator, and every
 * call that changes the economy that {@code adminMutationsEnabled} is on, whatever the router let
 * through. Everything here ends in {@link AdminOps}, the same calls {@code /eco admin} makes. The
 * typed account stays in this object and on the one confirm row of the player data view; it is
 * never logged, and erasing the account forgets it here too.
 */
final class HubAdmin {
    private final ServerPlayer player;
    private HubAction.AdminView view = HubAction.AdminView.SUPPLY;
    private AdminPages.Check check = AdminPages.Check.NONE;
    private String typed = "";
    private UUID target;
    private boolean known;
    private UUID rowId;
    private String result = "";
    private long amount;
    /** Rises with every change the operator made here, so the page is sent again. */
    private int revision;

    HubAdmin(ServerPlayer player) {
        this.player = player;
    }

    private MinecraftServer server() { return player.level().getServer(); }

    private boolean admin() { return Commands.LEVEL_ADMINS.check(player.permissions()); }

    private static boolean mutations() { return EconomyConfig.ADMIN_MUTATIONS_ENABLED.get(); }

    private void say(String key, Object... args) { player.sendSystemMessage(Component.translatable(key, args)); }

    /** The operator may act: level 3, and for a change to the economy, mutations on. Tells them otherwise. */
    private boolean allowed(boolean mutation) {
        if (!admin()) return false;
        if (mutation && !mutations()) {
            say("gui.neroeconomy.hub.admin.mutations_off");
            return false;
        }
        return true;
    }

    private void result(String key, long number) {
        result = key;
        amount = number;
        revision++;
    }

    // --- the page ----------------------------------------------------------------------------

    private AdminPages.State state() {
        return new AdminPages.State(mutations(), EconomyStore.get(server()).marketClosed(), EconomyHealth.frozen());
    }

    HubPages.Built build(int page) {
        // A player who lost level 3 with the hub open sees nothing of it.
        if (!admin()) return HubPages.comingSoon(HubTab.ADMIN);
        AdminPages.State state = state();
        return switch (view) {
            case SUPPLY -> AdminPages.supply(supply(), state, page);
            case SIM -> AdminPages.sim(sim(), state);
            case COMMODITIES -> AdminPages.commodities(commodities(), state, page);
            case STOCKS -> AdminPages.stocks(stocks(), state, page);
            case HOUSES -> AdminPages.houses(houses(), state, page);
            case CHECK -> AdminPages.check(check, state, page);
            case HEALTH -> AdminPages.health(health(), state);
            case PLAYER -> AdminPages.player(new AdminPages.Player(typed, rowId, target, known, result, amount), state);
        };
    }

    /** What the page depends on: the view, what was done here, and the time in five-second steps. */
    long signature() {
        return Objects.hash(view, revision, EconomyHealth.frozen(), EconomyStore.get(server()).marketClosed(),
                mutations(), server().getTickCount() / 100);
    }

    private AdminPages.Supply supply() {
        EconomyStore store = EconomyStore.get(server());
        SupplyReport report = store.supplyReport();
        List<AdminPages.Budget> budgets = new ArrayList<>();
        for (BudgetKind kind : BudgetKind.values()) {
            String name = kind.name().toLowerCase(Locale.ROOT);
            if (!AdminPages.BUDGETS.contains(name)) continue;
            budgets.add(new AdminPages.Budget(name, report.budgetUsedToday().getOrDefault(kind, 0L),
                    EconomySavedData.dailyLimit(kind)));
        }
        List<AdminPages.Reason> reasons = new ArrayList<>();
        for (TransactionType type : TransactionType.values()) {
            long minted = report.mintedByReason().getOrDefault(type, 0L);
            long burned = report.burnedByReason().getOrDefault(type, 0L);
            if (minted != 0L || burned != 0L)
                reasons.add(new AdminPages.Reason("command.neroeconomy.transaction_type."
                        + type.name().toLowerCase(Locale.ROOT), minted, burned));
        }
        return new AdminPages.Supply(report.circulation(), report.reserve(), report.escrow(), report.exchange(),
                report.houses(), report.obligations(), report.exchangeHeld(), report.mintedToday(), report.burnedToday(),
                report.mintedAllTime(), report.burnedAllTime(), report.supplyBalanced(), report.escrowBalanced(),
                report.exchangeBalanced(), budgets, reasons);
    }

    private AdminPages.Sim sim() {
        Simulation.Status status = Simulation.status(server());
        List<AdminPages.ActionCount> counts = new ArrayList<>();
        for (Map.Entry<AdminSection.Action, Long> entry : EconomyStore.get(server()).admin().counts().entrySet())
            counts.add(new AdminPages.ActionCount(entry.getKey().key(), entry.getValue()));
        return new AdminPages.Sim(status.stepMillis(), status.lastStep(), status.behind(), status.fast(),
                status.autonomous(), status.catchingUp(), counts);
    }

    private List<AdminPages.Commodity> commodities() {
        CommodityRegistry registry = CommodityData.forServer(server());
        HouseSection houses = EconomyStore.get(server()).houses();
        java.util.Set<String> drifted = houses.driftBuyOff();
        List<AdminPages.Commodity> out = new ArrayList<>();
        for (CommodityDefinition commodity : registry.commodities()) {
            String key = commodity.key();
            List<String> items = registry.itemsOf(key);
            String icon = !commodity.tag() ? commodity.target() : items.isEmpty() ? "minecraft:name_tag" : items.get(0);
            Optional<HouseSection.Quote> quote = houses.quote(key);
            long reference = quote.map(HouseSection.Quote::referenceMillis).orElse(commodity.referencePrice() * 1000L);
            out.add(new AdminPages.Commodity(key, icon, reference, quote.map(HouseSection.Quote::ask).orElse(0L),
                    quote.map(HouseSection.Quote::bid).orElse(0L), quote.map(HouseSection.Quote::soldToday).orElse(0L),
                    quote.map(HouseSection.Quote::boughtToday).orElse(0L),
                    registry.buyOff().contains(key) || drifted.contains(key), registry.sellOff().contains(key),
                    quote.map(HouseSection.Quote::sells).orElse(commodity.npcSells()),
                    quote.map(HouseSection.Quote::buys).orElse(commodity.npcBuys())));
        }
        return out;
    }

    private List<AdminPages.Stock> stocks() {
        EconomyStore store = EconomyStore.get(server());
        List<AdminPages.Stock> out = new ArrayList<>();
        for (ExchangeApi.StockQuote quote : ExchangeApi.stocks(server())) {
            StockState state = store.exchange().state(quote.id());
            out.add(new AdminPages.Stock(quote.nameKey(), quote.priceCents(), quote.dayChangeBasisPoints(),
                    quote.halted(), quote.delisted(), state == null ? 0L : state.splits()));
        }
        return out;
    }

    private List<AdminPages.House> houses() {
        HouseRegistry registry = HouseData.forServer(server());
        List<AdminPages.House> out = new ArrayList<>();
        for (HouseSection.HouseReport report : EconomyStore.get(server()).houses().report(registry)) {
            HouseDefinition house = registry.get(report.id());
            out.add(new AdminPages.House(house != null ? house.nameKey() : report.id(), house == null, report.capital(),
                    house != null ? house.workingCapital() : 0L, report.paidToday(), report.receivedToday()));
        }
        return out;
    }

    private AdminPages.Health health() {
        EconomyStore store = EconomyStore.get(server());
        SupplyReport report = store.supplyReport();
        return new AdminPages.Health(store.schema(), EconomyStore.SCHEMA, report.supplyBalanced(),
                report.escrowBalanced(), report.exchangeBalanced(), store.sectionsPresent());
    }

    // --- the server calls --------------------------------------------------------------------

    void view(HubAction.AdminView next) {
        if (!allowed(false)) return;
        view = next;
        revision++;
    }

    void simStep(int steps) {
        if (!allowed(true)) return;
        int ran = AdminOps.simStep(server(), steps);
        say(ran > 0 ? "command.neroeconomy.sim.stepped" : EconomyHealth.FROZEN_KEY, ran);
        revision++;
    }

    void marketFreeze(boolean closed) {
        if (!allowed(true)) return;
        boolean changed = AdminOps.setMarketClosed(server(), closed);
        say(!changed ? (closed ? "command.neroeconomy.market_switch.already_closed"
                : "command.neroeconomy.market_switch.already_open")
                : closed ? "command.neroeconomy.market_switch.closed" : "command.neroeconomy.market_switch.opened");
        revision++;
    }

    void reloadCheck() {
        if (!allowed(false)) return;
        check = AdminOps.reloadCheck(server());
        view = HubAction.AdminView.CHECK;
        revision++;
    }

    void acceptFresh() {
        if (!allowed(true)) return;
        say(AdminOps.acceptFreshStart(server()) ? "command.neroeconomy.health.accepted"
                : "command.neroeconomy.health.not_frozen");
        revision++;
    }

    /** Looks the account up. A known account can have its credits changed; any id can be exported or erased. */
    void target(String text) {
        if (!allowed(false)) return;
        String value = text == null ? "" : text.trim();
        UUID found = KnownAccounts.resolve(server(), value);
        known = found != null;
        target = found != null ? found : AdminOps.subject(server(), value);
        typed = target == null ? "" : value;
        rowId = target == null ? null : UUID.randomUUID();
        result(target == null ? "gui.neroeconomy.hub.admin.player.not_found"
                : known ? "gui.neroeconomy.hub.admin.player.found" : "gui.neroeconomy.hub.admin.player.found_id", 0L);
    }

    private UUID targetOf(HubPages.RowRef row) {
        return row != null && row.id() != null && row.id().equals(target) ? target : null;
    }

    void export(HubPages.RowRef row) {
        UUID subject = targetOf(row);
        if (!allowed(false) || subject == null) return;
        result(AdminOps.export(server(), subject) != null ? "gui.neroeconomy.hub.admin.player.exported"
                : "command.neroeconomy.export_failed", 0L);
    }

    void erase(HubPages.RowRef row) {
        UUID subject = targetOf(row);
        if (!allowed(false) || subject == null) return;
        AdminOps.erase(server(), subject);
        // Forgotten here as well: nothing about the erased account stays in this hub.
        typed = "";
        target = null;
        known = false;
        rowId = null;
        result("gui.neroeconomy.hub.admin.player.erased", 0L);
    }

    void credits(HubPages.RowRef row, long value, TransactionType type) {
        UUID account = targetOf(row);
        if (!allowed(true) || account == null || !known) return;
        boolean done = AdminOps.credits(server(), account, value, type);
        String key = !done ? "command.neroeconomy.admin_failed" : switch (type) {
            case ADMIN_GRANT -> "gui.neroeconomy.hub.admin.player.granted";
            case ADMIN_TAKE -> "gui.neroeconomy.hub.admin.player.taken";
            default -> "gui.neroeconomy.hub.admin.player.set";
        };
        result(key, value);
    }
}
