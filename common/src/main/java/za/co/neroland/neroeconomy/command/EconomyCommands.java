package za.co.neroland.neroeconomy.command;

import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

import za.co.neroland.nerolandcore.economy.CurrencyApi;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.admin.AdminOps;
import za.co.neroland.neroeconomy.api.ExchangeApi;
import za.co.neroland.neroeconomy.hub.HubOpener;
import za.co.neroland.neroeconomy.hub.HubTab;
import za.co.neroland.neroeconomy.commodity.CommodityData;
import za.co.neroland.neroeconomy.commodity.CommodityRegistry;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.economy.Fees;
import za.co.neroland.neroeconomy.economy.KnownAccounts;
import za.co.neroland.neroeconomy.economy.SupplyReport;
import za.co.neroland.neroeconomy.economy.TransactionType;
import za.co.neroland.neroeconomy.exchange.PriceText;
import za.co.neroland.neroeconomy.exchange.StockData;
import za.co.neroland.neroeconomy.goods.GoodsSource;
import za.co.neroland.neroeconomy.guard.GuardReport;
import za.co.neroland.neroeconomy.house.HouseData;
import za.co.neroland.neroeconomy.house.HouseDefinition;
import za.co.neroland.neroeconomy.house.HouseRegistry;
import za.co.neroland.neroeconomy.house.HouseSection;
import za.co.neroland.neroeconomy.market.CancelResult;
import za.co.neroland.neroeconomy.market.ListingSource;
import za.co.neroland.neroeconomy.market.MarketSavedData;
import za.co.neroland.neroeconomy.simulation.SimClock;
import za.co.neroland.neroeconomy.simulation.Simulation;
import za.co.neroland.neroeconomy.text.ShortIds;
import za.co.neroland.neroeconomy.text.TimeText;
import za.co.neroland.neroeconomy.telemetry.NeroEconomyTelemetry;
import za.co.neroland.neroeconomy.obligation.EconomyJob;
import za.co.neroland.neroeconomy.obligation.FulfillResult;
import za.co.neroland.neroeconomy.obligation.JobTargets;
import za.co.neroland.neroeconomy.obligation.JobType;
import za.co.neroland.neroeconomy.obligation.ObligationSavedData;

/** Server-authoritative command tree. No player identity or transaction detail is info-logged. */
public final class EconomyCommands {
    /** The most simulation steps one {@code /eco admin sim step} may force. */
    private static final int MAX_FORCED_STEPS = 100;
    /** The most lines of any one list {@code /eco admin reload-check} prints. */
    private static final int MAX_CHECK_LINES = 20;
    private static final long MILLIS_PER_MINUTE = 60_000L;

    private EconomyCommands() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(root("eco"));
        dispatcher.register(root("economy"));
        // Third alias. The screenshot harness drives `neroeconomy gallery` / `neroeconomy gallery clear`
        // by name, and its own client root is deliberately `necgallery` so it cannot shadow this tree.
        dispatcher.register(root("neroeconomy"));
        // Direct trade has a root of its own as well: the chat prompt's Accept and Decline run it.
        dispatcher.register(TradingCommands.trade());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> root(String name) {
        return Commands.literal(name)
                .then(Commands.literal("balance").executes(EconomyCommands::ownBalance)
                        .then(Commands.argument("player", StringArgumentType.word())
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(EconomyCommands::otherBalance)))
                .then(Commands.literal("pay").then(Commands.argument("player", StringArgumentType.word())
                        .then(Commands.argument("amount", LongArgumentType.longArg(1L))
                                .executes(EconomyCommands::pay))))
                .then(Commands.literal("history").executes(EconomyCommands::history))
                // The Economy Hub, as a shortcut for using the economy block the player stands at.
                .then(Commands.literal("hub").executes(context -> hub(context, null))
                        .then(Commands.argument("tab", StringArgumentType.word()).suggests(HUB_TABS)
                                .executes(context -> hub(context, StringArgumentType.getString(context, "tab")))))
                .then(Commands.literal("privacy").then(Commands.literal("logging")
                        .then(Commands.argument("optOut", BoolArgumentType.bool())
                                .executes(EconomyCommands::privacy))))
                // Level 3 for the whole branch. The reports are always available to it; anything that
                // changes the economy also needs adminMutationsEnabled, checked on every parse.
                .then(Commands.literal("admin").requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                        .then(adminMutation("give", TransactionType.ADMIN_GRANT))
                        .then(adminMutation("take", TransactionType.ADMIN_TAKE))
                        .then(adminMutation("set", TransactionType.ADMIN_SET))
                        .then(Commands.literal("supply").executes(EconomyCommands::supply))
                        .then(Commands.literal("stocks").executes(EconomyCommands::stocks))
                        .then(Commands.literal("houses").executes(EconomyCommands::houses))
                        .then(Commands.literal("reload-check").executes(EconomyCommands::reloadCheck))
                        .then(Commands.literal("health").executes(EconomyCommands::health)
                                .then(Commands.literal("accept")
                                        .requires(source -> EconomyConfig.ADMIN_MUTATIONS_ENABLED.get())
                                        .executes(EconomyCommands::acceptFreshStart)))
                        // The report is always available; running steps by hand changes the economy.
                        // The operator's market freeze: trading stops, reads, collecting and refunds go on.
                        .then(Commands.literal("market")
                                .requires(source -> EconomyConfig.ADMIN_MUTATIONS_ENABLED.get())
                                .then(Commands.literal("close").executes(context -> marketSwitch(context, true)))
                                .then(Commands.literal("open").executes(context -> marketSwitch(context, false))))
                        .then(Commands.literal("sim").executes(EconomyCommands::simStatus)
                                .then(Commands.literal("step")
                                        .requires(source -> EconomyConfig.ADMIN_MUTATIONS_ENABLED.get())
                                        .executes(context -> simStep(context, 1))
                                        .then(Commands.argument("steps", IntegerArgumentType.integer(1, MAX_FORCED_STEPS))
                                                .executes(context -> simStep(context,
                                                        IntegerArgumentType.getInteger(context, "steps")))))))
                .then(Commands.literal("export").requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(EconomyCommands::export)))
                .then(Commands.literal("erase").requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(EconomyCommands::erase)))
                .then(goodsJobTree("contract", JobType.CONTRACT))
                .then(goodsJobTree("delivery", JobType.DELIVERY))
                .then(bountyTree())
                .then(auctionTree())
                .then(marketTree())
                // The order book, offers and auto-trade rules: shortcuts to what the screens do.
                .then(TradingCommands.order())
                .then(TradingCommands.offer())
                .then(TradingCommands.rule())
                .then(TradingCommands.trade())
                // The exchange: shares, the index fund, Savings Bonds, and practice mode.
                .then(InvestCommands.invest())
                .then(galleryTree());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> adminMutation(String name, TransactionType type) {
        return Commands.literal(name).requires(source -> EconomyConfig.ADMIN_MUTATIONS_ENABLED.get())
                .then(Commands.argument("player", StringArgumentType.word())
                        .then(Commands.argument("amount", LongArgumentType.longArg(0L))
                                .executes(context -> mutate(context, type))));
    }

    /** Tells the caller the economy is frozen. @return true when it is, so the command must stop */
    private static boolean frozen(CommandContext<CommandSourceStack> context) {
        if (!EconomyHealth.frozen()) return false;
        context.getSource().sendFailure(Component.translatable(EconomyHealth.FROZEN_KEY));
        return true;
    }

    /**
     * Tells the caller an operator has closed the market. @return true when it is, so a command that
     * would trade, post, claim or fulfil must stop; cancelling, collecting and refunds carry on
     */
    private static boolean marketClosed(CommandContext<CommandSourceStack> context, ServerPlayer player) {
        if (!EconomyStore.get(player.level().getServer()).marketClosed()) return false;
        context.getSource().sendFailure(Component.translatable(EconomyHealth.MARKET_CLOSED_KEY));
        return true;
    }

    /**
     * Tells a player that a payment to another player would have to use starting credits that have
     * not vested yet. @return true when that is the case, so the command must stop
     */
    private static boolean locked(CommandContext<CommandSourceStack> context, ServerPlayer player, long amount) {
        if (!NeroEconomyCommon.CURRENCY_PROVIDER.lockBlocks(player.getUUID(), amount)) return false;
        context.getSource().sendFailure(Component.translatable("command.neroeconomy.funds_locked",
                NeroEconomyCommon.CURRENCY_PROVIDER.spendable(player.getUUID()),
                EconomyConfig.STARTING_BALANCE_VEST_MINUTES.get()));
        return true;
    }

    /** Where every credit is: aggregates only, never an account or a balance of anyone's. */
    private static int supply(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        SupplyReport report = EconomyStore.get(source.getServer()).supplyReport();
        source.sendSuccess(() -> Component.translatable("command.neroeconomy.supply.header"), false);
        source.sendSuccess(() -> Component.translatable("command.neroeconomy.supply.balances", report.circulation(),
                report.reserve(), report.escrow(), report.exchange(), report.houses()), false);
        source.sendSuccess(() -> Component.translatable("command.neroeconomy.supply.today", report.mintedToday(),
                report.burnedToday()), false);
        source.sendSuccess(() -> Component.translatable("command.neroeconomy.supply.all_time", report.mintedAllTime(),
                report.burnedAllTime()), false);
        report.mintedByReason().forEach((reason, amount) -> source.sendSuccess(() -> Component.translatable(
                "command.neroeconomy.supply.minted_reason", transactionType(reason), amount), false));
        report.burnedByReason().forEach((reason, amount) -> source.sendSuccess(() -> Component.translatable(
                "command.neroeconomy.supply.burned_reason", transactionType(reason), amount), false));
        report.budgetUsedToday().forEach((kind, used) -> source.sendSuccess(() -> Component.translatable(
                "command.neroeconomy.supply.budget",
                Component.translatable("command.neroeconomy.budget." + kind.name().toLowerCase(Locale.ROOT)), used,
                EconomySavedData.dailyLimit(kind)), false));
        source.sendSuccess(() -> Component.translatable("command.neroeconomy.supply.obligations",
                report.obligations()), false);
        long[] held = EconomyStore.get(source.getServer()).exchange().desk().heldParts();
        source.sendSuccess(() -> Component.translatable("command.neroeconomy.supply.exchange_held",
                report.exchangeHeld(), held[0], held[1], held[2]), false);
        source.sendSuccess(() -> checkLine("command.neroeconomy.supply.check_supply", report.supplyBalanced()), false);
        source.sendSuccess(() -> checkLine("command.neroeconomy.supply.check_escrow", report.escrowBalanced()), false);
        source.sendSuccess(() -> checkLine("command.neroeconomy.supply.check_exchange", report.exchangeBalanced()), false);
        return Command.SINGLE_SUCCESS;
    }

    /** Every listed company's price, change today and halt flag, then the index and the bond rates. Names no account. */
    private static int stocks(CommandContext<CommandSourceStack> context) {
        if (frozen(context)) return 0;
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        if (!ExchangeApi.enabled() || !EconomyConfig.AUTONOMOUS_ECONOMY.get())
            source.sendSuccess(() -> Component.translatable("command.neroeconomy.stocks.off"), false);
        java.util.List<ExchangeApi.StockQuote> quotes = ExchangeApi.stocks(server);
        if (quotes.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("command.neroeconomy.stocks.none"), false);
        } else {
            source.sendSuccess(() -> Component.translatable("command.neroeconomy.stocks.header"), false);
            for (ExchangeApi.StockQuote quote : quotes) source.sendSuccess(() -> stockLine(quote), false);
        }
        ExchangeApi.index(server).ifPresent(index -> source.sendSuccess(() -> Component.translatable(
                "command.neroeconomy.stocks.index", Component.translatable(index.nameKey()),
                PriceText.credits(index.priceCents()), PriceText.percent(index.dayChangeBasisPoints()),
                index.members()), false));
        for (ExchangeApi.BondQuote bond : ExchangeApi.bonds()) source.sendSuccess(() -> Component.translatable(
                "command.neroeconomy.stocks.bond", Component.translatable(bond.nameKey()),
                PriceText.percent(bond.rateBasisPoints())), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Every Merchant House: its working capital as it stands, what it paid for goods and received
     * for goods today, and its busiest commodities today. Aggregates only; never an account.
     */
    private static int houses(CommandContext<CommandSourceStack> context) {
        if (frozen(context)) return 0;
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        HouseRegistry registry = HouseData.forServer(server);
        if (!EconomyConfig.AUTONOMOUS_ECONOMY.get())
            source.sendSuccess(() -> Component.translatable("command.neroeconomy.houses.off"), false);
        source.sendSuccess(() -> Component.translatable("command.neroeconomy.houses.header"), false);
        for (HouseSection.HouseReport report : EconomyStore.get(server).houses().report(registry)) {
            HouseDefinition house = registry.get(report.id());
            Component name = house != null ? Component.translatable(house.nameKey())
                    : Component.translatable("command.neroeconomy.houses.retired", report.id());
            long cap = house != null ? house.workingCapital() : 0L;
            source.sendSuccess(() -> Component.translatable("command.neroeconomy.houses.row", name, report.capital(), cap,
                    report.balance(), report.escrowed(), report.paidToday(), report.receivedToday()), false);
            if (report.top().isEmpty())
                source.sendSuccess(() -> Component.translatable("command.neroeconomy.houses.quiet"), false);
            for (HouseSection.Volume volume : report.top())
                source.sendSuccess(() -> Component.translatable("command.neroeconomy.houses.top", volume.key(),
                        volume.units(), volume.value()), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    /**
     * What the data packs came to: stock files and commodity entries that were skipped, items two
     * commodities both claimed, and the arbitrage guard run afresh against the server's live
     * recipes. Data-pack ids, fixed reason words and prices only; nothing about any account.
     */
    private static int reloadCheck(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        source.sendSuccess(() -> Component.translatable("command.neroeconomy.reload_check.header"), false);

        int listed = StockData.forServer(server).stocks().size();
        java.util.List<StockData.Problem> stockProblems = StockData.problems(server);
        source.sendSuccess(() -> Component.translatable("command.neroeconomy.reload_check.stocks", listed,
                stockProblems.size()), false);
        checkLines(source, stockProblems, problem -> Component.translatable(
                "command.neroeconomy.reload_check.file_problem", problem.id(), problem.word()));

        HouseRegistry houses = HouseData.forServer(server);
        source.sendSuccess(() -> Component.translatable("command.neroeconomy.reload_check.houses",
                houses.houses().size(), houses.problems().size()), false);
        checkLines(source, houses.problems(), problem -> Component.translatable(
                "command.neroeconomy.reload_check.file_problem", problem.file(), problem.word()));

        // The guard runs again now, against the recipes as they are, and its verdict is applied.
        CommodityData.Check check = CommodityData.check(server);
        CommodityRegistry registry = CommodityData.forServer(server);
        if (!registry.available()) {
            source.sendFailure(Component.translatable("command.neroeconomy.reload_check.unavailable"));
            return 0;
        }
        CommodityRegistry.LoadReport load = registry.report();
        source.sendSuccess(() -> Component.translatable("command.neroeconomy.reload_check.commodities",
                registry.commodities().size(), load.files(), load.gatedFiles(), load.absent(), load.disabled()), false);
        source.sendSuccess(() -> Component.translatable("command.neroeconomy.reload_check.commodity_problems",
                load.problems().size()), false);
        checkLines(source, load.problems(), problem -> problem.entry() < 0
                ? Component.translatable("command.neroeconomy.reload_check.file_problem", problem.file(), problem.word())
                : Component.translatable("command.neroeconomy.reload_check.entry_problem", problem.file(),
                        problem.entry(), problem.word()));
        source.sendSuccess(() -> Component.translatable("command.neroeconomy.reload_check.clashes",
                load.clashes().size()), false);
        checkLines(source, load.clashes(), clash -> Component.translatable("command.neroeconomy.reload_check.clash",
                clash.item(), clash.kept(), clash.keptFile(), clash.dropped(), clash.droppedFile()));

        if (!check.guardProblems().isEmpty()) {
            source.sendSuccess(() -> Component.translatable("command.neroeconomy.reload_check.guard_problems",
                    check.guardProblems().size()), false);
            checkLines(source, check.guardProblems(), problem -> Component.translatable(
                    "command.neroeconomy.reload_check.file_problem", problem.file(), problem.word()));
        }
        GuardReport report = check.report();
        if (!check.recipesReadable()) {
            source.sendFailure(Component.translatable("command.neroeconomy.reload_check.no_recipes"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("command.neroeconomy.reload_check.guard", report.recipesRead(),
                report.recipesSkipped(), report.chainsChecked(), report.depth()), false);
        java.util.List<GuardReport.Failure> failures = report.failures().stream()
                .filter(failure -> failure.kind() != GuardReport.Kind.CLASH).toList();
        checkLines(source, failures, failure -> switch (failure.kind()) {
            case SPREAD -> Component.translatable("command.neroeconomy.reload_check.failure.spread",
                    failure.commodity(), failure.cost(), failure.revenue());
            case REDEEM -> Component.translatable("command.neroeconomy.reload_check.failure.redeem",
                    failure.commodity(), failure.recipe(), failure.cost(), failure.revenue(), failure.steps());
            default -> Component.translatable("command.neroeconomy.reload_check.failure.chain",
                    failure.commodity(), failure.recipe(), failure.cost(), failure.revenue(), failure.steps());
        });
        if (failures.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("command.neroeconomy.reload_check.passed"), false);
            return Command.SINGLE_SUCCESS;
        }
        source.sendFailure(Component.translatable("command.neroeconomy.reload_check.failed", failures.size(),
                report.buyOff().size(), report.sellOff().size()));
        return 0;
    }

    /** Sends up to {@link #MAX_CHECK_LINES} of {@code rows}, one line each, then how many were left out. */
    private static <T> void checkLines(CommandSourceStack source, java.util.List<T> rows,
            java.util.function.Function<T, Component> line) {
        int shown = Math.min(rows.size(), MAX_CHECK_LINES);
        for (int index = 0; index < shown; index++) {
            T row = rows.get(index);
            source.sendSuccess(() -> line.apply(row), false);
        }
        int more = rows.size() - shown;
        if (more > 0) source.sendSuccess(() -> Component.translatable("command.neroeconomy.reload_check.more", more), false);
    }

    private static Component stockLine(ExchangeApi.StockQuote quote) {
        Component name = Component.translatable(quote.nameKey());
        if (quote.delisted())
            return Component.translatable("command.neroeconomy.stocks.row_delisted", name,
                    PriceText.credits(quote.priceCents()));
        return Component.translatable(quote.halted() ? "command.neroeconomy.stocks.row_halted"
                : "command.neroeconomy.stocks.row", name, Component.translatable("sector.neroeconomy." + quote.sector()),
                Component.translatable(quote.type().translationKey()), PriceText.credits(quote.priceCents()),
                PriceText.percent(quote.dayChangeBasisPoints()));
    }

    private static Component transactionType(TransactionType type) {
        return Component.translatable("command.neroeconomy.transaction_type." + type.name().toLowerCase(Locale.ROOT));
    }

    private static Component checkLine(String key, boolean passed) {
        return Component.translatable(key, Component.translatable(passed
                ? "command.neroeconomy.check.passed" : "command.neroeconomy.check.failed"));
    }

    /** Whether the economy is frozen, what the store holds, and whether the three invariants hold. */
    private static int health(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        EconomyStore store = EconomyStore.get(source.getServer());
        boolean frozen = EconomyHealth.frozen();
        source.sendSuccess(() -> Component.translatable(frozen ? "command.neroeconomy.health.frozen"
                : "command.neroeconomy.health.healthy"), false);
        source.sendSuccess(() -> Component.translatable("command.neroeconomy.health.schema", store.schema(),
                EconomyStore.SCHEMA), false);
        java.util.List<String> sections = store.sectionsPresent();
        source.sendSuccess(() -> sections.isEmpty() ? Component.translatable("command.neroeconomy.health.no_sections")
                : Component.translatable("command.neroeconomy.health.sections", String.join(", ", sections)), false);
        SupplyReport report = store.supplyReport();
        source.sendSuccess(() -> checkLine("command.neroeconomy.supply.check_supply", report.supplyBalanced()), false);
        source.sendSuccess(() -> checkLine("command.neroeconomy.supply.check_escrow", report.escrowBalanced()), false);
        source.sendSuccess(() -> checkLine("command.neroeconomy.supply.check_exchange", report.exchangeBalanced()), false);
        if (frozen) source.sendSuccess(() -> Component.translatable("command.neroeconomy.health.how_to_accept"), false);
        return Command.SINGLE_SUCCESS;
    }

    /** Gives up on the unreadable store and starts the economy afresh. Needs {@code adminMutationsEnabled}. */
    private static int acceptFreshStart(CommandContext<CommandSourceStack> context) {
        if (!AdminOps.acceptFreshStart(context.getSource().getServer()))
            return fail(context, "command.neroeconomy.health.not_frozen");
        // The broadcast to other operators carries no player data: it only says what was done.
        context.getSource().sendSuccess(() -> Component.translatable("command.neroeconomy.health.accepted"), true);
        return Command.SINGLE_SUCCESS;
    }

    /** Where the simulation has got to. Counts and settings only; nothing about any account. */
    private static int simStatus(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Simulation.Status status = Simulation.status(source.getServer());
        source.sendSuccess(() -> Component.translatable("command.neroeconomy.sim.header"), false);
        source.sendSuccess(() -> status.fast() ? Component.translatable("command.neroeconomy.sim.step_fast")
                : Component.translatable("command.neroeconomy.sim.step_minutes",
                        status.stepMillis() / MILLIS_PER_MINUTE), false);
        source.sendSuccess(() -> status.lastStep() == SimClock.NEVER
                ? Component.translatable("command.neroeconomy.sim.not_started")
                : Component.translatable("command.neroeconomy.sim.last_step", status.lastStep()), false);
        source.sendSuccess(() -> Component.translatable("command.neroeconomy.sim.behind", status.behind()), false);
        source.sendSuccess(() -> Component.translatable(status.catchingUp()
                ? "command.neroeconomy.sim.catching_up" : "command.neroeconomy.sim.up_to_date"), false);
        source.sendSuccess(() -> Component.translatable(status.autonomous()
                ? "command.neroeconomy.sim.autonomous_on" : "command.neroeconomy.sim.autonomous_off"), false);
        source.sendSuccess(() -> Component.translatable("command.neroeconomy.sim.handlers",
                String.join(", ", Simulation.PIPELINE.names())), false);
        if (EconomyHealth.frozen())
            source.sendSuccess(() -> Component.translatable(EconomyHealth.FROZEN_KEY), false);
        return Command.SINGLE_SUCCESS;
    }

    /** Closes or opens the market. Needs {@code adminMutationsEnabled}. */
    private static int marketSwitch(CommandContext<CommandSourceStack> context, boolean closed) {
        boolean changed = AdminOps.setMarketClosed(context.getSource().getServer(), closed);
        String key = !changed ? (closed ? "command.neroeconomy.market_switch.already_closed"
                : "command.neroeconomy.market_switch.already_open")
                : closed ? "command.neroeconomy.market_switch.closed" : "command.neroeconomy.market_switch.opened";
        // The broadcast to other operators says what was done and nothing else.
        context.getSource().sendSuccess(() -> Component.translatable(key), changed);
        return changed ? Command.SINGLE_SUCCESS : 0;
    }

    /** Runs simulation steps now, for testing. Needs {@code adminMutationsEnabled}. */
    private static int simStep(CommandContext<CommandSourceStack> context, int steps) {
        if (frozen(context)) return 0;
        int ran = AdminOps.simStep(context.getSource().getServer(), steps);
        // The broadcast to other operators says what was done and nothing else.
        context.getSource().sendSuccess(() -> Component.translatable("command.neroeconomy.sim.stepped", ran), true);
        return ran;
    }

    private static int ownBalance(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return playerOnly(context);
        return sendBalance(context.getSource(), player.getUUID());
    }

    private static int otherBalance(CommandContext<CommandSourceStack> context) {
        UUID target = resolveAccount(context);
        return target == null ? 0 : sendBalance(context.getSource(), target);
    }

    private static int sendBalance(CommandSourceStack source, UUID target) {
        long balance = CurrencyApi.getBalance(target, NeroEconomyCommon.CREDITS);
        source.sendSuccess(() -> Component.translatable("command.neroeconomy.balance", balance), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int pay(CommandContext<CommandSourceStack> context) {
        ServerPlayer source = context.getSource().getPlayer();
        if (source == null) return playerOnly(context);
        UUID target = resolveAccount(context);
        if (target == null) return 0;
        long amount = LongArgumentType.getLong(context, "amount");
        if (source.getUUID().equals(target)) {
            context.getSource().sendFailure(Component.translatable("command.neroeconomy.self_payment"));
            return 0;
        }
        if (frozen(context) || locked(context, source, amount)) return 0;
        if (!CurrencyApi.transfer(source.getUUID(), target, NeroEconomyCommon.CREDITS, amount)) {
            context.getSource().sendFailure(Component.translatable("command.neroeconomy.payment_failed"));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.translatable("command.neroeconomy.payment_sent", amount), false);
        ServerPlayer online = context.getSource().getServer().getPlayerList().getPlayer(target);
        if (online != null) online.sendSystemMessage(Component.translatable("command.neroeconomy.payment_received", amount));
        return Command.SINGLE_SUCCESS;
    }

    /** The tabs the caller sees, as {@code /eco hub} accepts them. */
    private static final SuggestionProvider<CommandSourceStack> HUB_TABS = (context, builder) -> {
        ServerPlayer player = context.getSource().getPlayer();
        boolean admin = player != null && Commands.LEVEL_ADMINS.check(player.permissions());
        return SharedSuggestionProvider.suggest(HubTab.visibleTabs(admin).stream().map(HubTab::id), builder);
    };

    /**
     * Opens the Economy Hub at the economy block the player stands at, on the tab named or the
     * block's own. There is no remote hub: without a block in reach the command says so.
     */
    private static int hub(CommandContext<CommandSourceStack> context, String tabName) {
        ServerPlayer player = TradingCommands.trader(context, false);
        if (player == null) return 0;
        HubTab tab = null;
        if (tabName != null) {
            tab = HubTab.parse(tabName);
            if (tab == null || !tab.visible(Commands.LEVEL_ADMINS.check(player.permissions())))
                return TradingCommands.fail(context, "command.neroeconomy.hub.unknown_tab", tabName);
        }
        BlockEntity anchor = HubOpener.nearestAnchor(player);
        if (anchor == null) return TradingCommands.fail(context, "command.neroeconomy.hub.no_terminal");
        HubOpener.open(player, anchor, tab);
        return Command.SINGLE_SUCCESS;
    }

    private static int history(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return playerOnly(context);
        var rows = NeroEconomyCommon.CURRENCY_PROVIDER.activeData().history(player.getUUID(),
                EconomyConfig.HISTORY_LIMIT_PER_PLAYER.get());
        context.getSource().sendSuccess(() -> Component.translatable("command.neroeconomy.history.header", rows.size()), false);
        // The reason is shown through its lang key and the time as a date, never as an enum name
        // or a raw millisecond count. Stored timestamps are economy time, so they are converted first.
        rows.forEach(row -> context.getSource().sendSuccess(() -> Component.translatable(
                "command.neroeconomy.history.row", transactionType(row.type()), row.amount(),
                TimeText.utcMinutes(NeroEconomyCommon.CURRENCY_PROVIDER.toWallMillis(row.timestamp()))), false));
        return Command.SINGLE_SUCCESS;
    }

    /** Time left until an economy-time deadline, as a translated duration rather than a timestamp. */
    private static Component remaining(long economyDeadline) {
        TimeText.Remaining left = TimeText.remaining(economyDeadline - NeroEconomyCommon.CURRENCY_PROVIDER.now());
        return Component.translatable(left.key(), left.first(), left.second());
    }

    private static int privacy(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return playerOnly(context);
        boolean optOut = BoolArgumentType.getBool(context, "optOut");
        NeroEconomyCommon.CURRENCY_PROVIDER.activeData().setLoggingOptOut(player.getUUID(), optOut);
        context.getSource().sendSuccess(() -> Component.translatable(
                optOut ? "command.neroeconomy.logging.opted_out" : "command.neroeconomy.logging.opted_in"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int mutate(CommandContext<CommandSourceStack> context, TransactionType type) {
        if (frozen(context)) return 0;
        UUID target = resolveAccount(context);
        if (target == null) return 0;
        long amount = LongArgumentType.getLong(context, "amount");
        // The same call as the hub's Admin tab: one ledger row under the reason, and the action counted.
        if (!AdminOps.credits(context.getSource().getServer(), target, amount, type)) {
            context.getSource().sendFailure(Component.translatable("command.neroeconomy.admin_failed"));
            return 0;
        }
        // Neither the log nor the broadcast carries the amount, and the result stays with the caller.
        context.getSource().sendSuccess(() -> Component.translatable("command.neroeconomy.admin_success"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int export(CommandContext<CommandSourceStack> context) {
        UUID target = resolveSubject(context);
        if (target == null) return 0;
        // Every store, not just the ledger, and not cut at historyLimitPerPlayer.
        Path output = AdminOps.export(context.getSource().getServer(), target);
        if (output == null) {
            context.getSource().sendFailure(Component.translatable("command.neroeconomy.export_failed"));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.translatable("command.neroeconomy.export_success",
                output.getFileName().toString()), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int erase(CommandContext<CommandSourceStack> context) {
        UUID target = resolveSubject(context);
        if (target == null) return 0;
        AdminOps.erase(context.getSource().getServer(), target);
        context.getSource().sendSuccess(() -> Component.translatable("command.neroeconomy.erase_success"), true);
        return Command.SINGLE_SUCCESS;
    }

    // Job targets are typed ids, not free words: a namespaced id contains ':' and a tag starts with
    // '#', neither of which an unquoted word argument can carry. The target kind is a literal
    // (item / tag / entity / player), so nothing needs quoting, and each id is checked against the
    // live registries before any payout is escrowed.
    private static final SuggestionProvider<CommandSourceStack> ITEM_IDS = (context, builder) ->
            SharedSuggestionProvider.suggestResource(BuiltInRegistries.ITEM.keySet(), builder);
    private static final SuggestionProvider<CommandSourceStack> ITEM_TAGS = (context, builder) ->
            SharedSuggestionProvider.suggestResource(
                    BuiltInRegistries.ITEM.getTags().map(tag -> tag.key().location()), builder);
    private static final SuggestionProvider<CommandSourceStack> ENTITY_IDS = (context, builder) ->
            SharedSuggestionProvider.suggestResource(BuiltInRegistries.ENTITY_TYPE.keySet(), builder);

    /** {@code contract} and {@code delivery}: jobs that ask for goods, by item id or by item tag. */
    private static LiteralArgumentBuilder<CommandSourceStack> goodsJobTree(String name, JobType type) {
        return Commands.literal(name)
                .then(Commands.literal("post")
                        .then(Commands.literal("item").then(Commands.argument("target", IdentifierArgument.id())
                                .suggests(ITEM_IDS).then(goodsTail(type, false))))
                        .then(Commands.literal("tag").then(Commands.argument("target", IdentifierArgument.id())
                                .suggests(ITEM_TAGS).then(goodsTail(type, true)))))
                .then(Commands.literal("claim").then(Commands.argument("id", StringArgumentType.word())
                        .executes(EconomyCommands::claimJob)))
                .then(Commands.literal("fulfill").then(Commands.argument("id", StringArgumentType.word())
                        .executes(EconomyCommands::fulfillJob)))
                .then(Commands.literal("release").then(Commands.argument("id", StringArgumentType.word())
                        .executes(EconomyCommands::releaseJob)))
                .then(Commands.literal("list").executes(context -> listJobs(context, type)));
    }

    /** Everything after the target: {@code <quantity> <payout>}, with the destination in between for a delivery. */
    private static RequiredArgumentBuilder<CommandSourceStack, Integer> goodsTail(JobType type, boolean tag) {
        RequiredArgumentBuilder<CommandSourceStack, Integer> quantity =
                Commands.argument("quantity", IntegerArgumentType.integer(1, 2304));
        if (type != JobType.DELIVERY) return quantity.then(Commands.argument("payout", LongArgumentType.longArg(1L))
                .executes(context -> postGoods(context, type, tag)));
        return quantity.then(Commands.argument("dimension", DimensionArgument.dimension())
                .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .then(Commands.argument("payout", LongArgumentType.longArg(1L))
                                                .executes(context -> postGoods(context, type, tag)))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> bountyTree() {
        return Commands.literal("bounty")
                .then(Commands.literal("post")
                        .then(Commands.literal("entity").then(Commands.argument("target", IdentifierArgument.id())
                                .suggests(ENTITY_IDS).then(bountyTail(false))))
                        // A bounty on a named player needs permission level 2 as well as the server
                        // policy switch; the switch is checked when the command runs, so turning it on
                        // or off takes effect at once.
                        .then(Commands.literal("player").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .then(Commands.argument("target", EntityArgument.player()).then(bountyTail(true)))))
                .then(Commands.literal("claim").then(Commands.argument("id", StringArgumentType.word())
                        .executes(EconomyCommands::claimJob)))
                .then(Commands.literal("release").then(Commands.argument("id", StringArgumentType.word())
                        .executes(EconomyCommands::releaseJob)))
                .then(Commands.literal("list").executes(context -> listJobs(context, JobType.BOUNTY)));
    }

    /** {@code <payout> [quantity]}: a bounty's quantity is the number of proven kills it takes to settle, default one. */
    private static RequiredArgumentBuilder<CommandSourceStack, Long> bountyTail(boolean onPlayer) {
        return Commands.argument("payout", LongArgumentType.longArg(1L))
                .executes(context -> postBounty(context, onPlayer, 1))
                .then(Commands.argument("quantity", IntegerArgumentType.integer(1, 2304))
                        .executes(context -> postBounty(context, onPlayer,
                                IntegerArgumentType.getInteger(context, "quantity"))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> auctionTree() {
        return Commands.literal("auction")
                .then(Commands.literal("create").then(Commands.argument("quantity", IntegerArgumentType.integer(1, 64))
                        .then(Commands.argument("minimumBid", LongArgumentType.longArg(1L))
                                .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 10080))
                                        .executes(context -> createAuction(context, false))
                                        .then(Commands.argument("interplanetary", BoolArgumentType.bool())
                                                .executes(context -> createAuction(context,
                                                        BoolArgumentType.getBool(context, "interplanetary"))))))))
                .then(Commands.literal("bid").then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("amount", LongArgumentType.longArg(1L))
                                .executes(EconomyCommands::bid))))
                .then(Commands.literal("list").executes(EconomyCommands::listAuctions));
    }

    /** A player's view of, and way out of, their own open market rows, jobs and auctions. */
    private static LiteralArgumentBuilder<CommandSourceStack> marketTree() {
        return Commands.literal("market")
                .then(Commands.literal("mine").executes(EconomyCommands::listMine))
                .then(Commands.literal("cancel").then(Commands.argument("id", StringArgumentType.word())
                        .suggests(OWN_ROWS).executes(EconomyCommands::cancelMine)));
    }

    /** The ids of everything of the caller's that {@code market cancel} could act on. */
    private static java.util.List<UUID> ownRows(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        UUID self = player.getUUID();
        java.util.List<UUID> ids = new java.util.ArrayList<>();
        MarketSavedData market = MarketSavedData.get(server);
        ObligationSavedData obligations = ObligationSavedData.get(server);
        market.listingsOf(self).forEach(row -> ids.add(row.listingId()));
        market.buyOffersOf(self).forEach(row -> ids.add(row.offerId()));
        obligations.jobsIssuedBy(self).forEach(row -> ids.add(row.id()));
        obligations.auctionsBy(self).forEach(row -> ids.add(row.id()));
        return ids;
    }

    /** Tab-completion of the caller's own rows as short ids; nobody else's row is ever offered. */
    private static final SuggestionProvider<CommandSourceStack> OWN_ROWS = (context, builder) -> {
        ServerPlayer player = context.getSource().getPlayer();
        return player == null ? builder.buildFuture()
                : SharedSuggestionProvider.suggest(ShortIds.all(ownRows(player)), builder);
    };

    private static int postGoods(CommandContext<CommandSourceStack> context, JobType type, boolean tag)
            throws CommandSyntaxException {
        Identifier id = IdentifierArgument.getId(context, "target");
        String target = tag ? JobTargets.TAG_PREFIX + id : id.toString();
        if (!JobTargets.wellFormed(target)) return fail(context, "command.neroeconomy.job.target_too_long",
                JobTargets.MAX_LENGTH);
        if (!ObligationSavedData.targetUsable(type, target)) return fail(context,
                tag ? "command.neroeconomy.job.unknown_tag" : "command.neroeconomy.job.unknown_item", target);
        String destination = "";
        if (type == JobType.DELIVERY) {
            // Resolved against the running server, so a dimension that does not exist is refused here.
            ServerLevel level = DimensionArgument.getDimension(context, "dimension");
            destination = level.dimension().identifier().toString() + '|'
                    + IntegerArgumentType.getInteger(context, "x") + '|'
                    + IntegerArgumentType.getInteger(context, "y") + '|'
                    + IntegerArgumentType.getInteger(context, "z");
        }
        return postJob(context, type, target, IntegerArgumentType.getInteger(context, "quantity"), destination);
    }

    private static int postBounty(CommandContext<CommandSourceStack> context, boolean onPlayer, int quantity)
            throws CommandSyntaxException {
        if (!onPlayer) {
            Identifier id = IdentifierArgument.getId(context, "target");
            // The player entity type would be a bounty on every player, outside the player-bounty gate.
            if (JobTargets.PLAYER_ENTITY.equals(id.toString()))
                return fail(context, "command.neroeconomy.bounty.use_player");
            if (!ObligationSavedData.targetUsable(JobType.BOUNTY, id.toString()))
                return fail(context, "command.neroeconomy.job.unknown_entity", id.toString());
            return postJob(context, JobType.BOUNTY, id.toString(), quantity, "");
        }
        if (!EconomyConfig.PLAYER_BOUNTIES_ENABLED.get())
            return fail(context, "command.neroeconomy.bounty.players_disabled");
        ServerPlayer issuer = context.getSource().getPlayer();
        if (issuer == null) return playerOnly(context);
        ServerPlayer target = EntityArgument.getPlayer(context, "target");
        if (target.getUUID().equals(issuer.getUUID())) return fail(context, "command.neroeconomy.bounty.self");
        return postJob(context, JobType.BOUNTY, JobTargets.player(target.getUUID()), quantity, "");
    }

    private static int postJob(CommandContext<CommandSourceStack> context, JobType type, String target, int quantity,
            String destination) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return playerOnly(context);
        if (frozen(context) || marketClosed(context, player)) return 0;
        ObligationSavedData store = ObligationSavedData.get(player.level().getServer());
        int cap = EconomyConfig.MAX_JOBS_PER_PLAYER.get();
        if (store.jobsIssuedBy(player.getUUID()).size() >= cap)
            return fail(context, "command.neroeconomy.job.limit_reached", cap);
        long payout = LongArgumentType.getLong(context, "payout");
        // A payout is promised to another player, so unvested starting credits cannot fund it.
        if (locked(context, player, payout)) return 0;
        long expires = NeroEconomyCommon.CURRENCY_PROVIDER.now()
                + EconomyConfig.DEFAULT_LISTING_DURATION_HOURS.get() * 3_600_000L;
        var job = store.postJob(player, type, target, quantity, destination, payout, expires);
        if (job == null) return fail(context, "command.neroeconomy.job.post_failed");
        context.getSource().sendSuccess(() -> Component.translatable("command.neroeconomy.job.posted",
                job.id().toString(), job.payout()), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int claimJob(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return playerOnly(context);
        UUID id = parseId(context); if (id == null) return 0;
        if (frozen(context) || marketClosed(context, player)) return 0;
        boolean ok = ObligationSavedData.get(player.level().getServer()).claim(id, player.getUUID());
        if (!ok) return fail(context, "command.neroeconomy.job.claim_failed");
        context.getSource().sendSuccess(() -> Component.translatable("command.neroeconomy.job.claimed"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int releaseJob(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return playerOnly(context);
        UUID id = parseId(context); if (id == null) return 0;
        if (frozen(context)) return 0;
        boolean ok = ObligationSavedData.get(player.level().getServer()).release(id, player.getUUID());
        if (!ok) return fail(context, "command.neroeconomy.job.release_failed");
        context.getSource().sendSuccess(() -> Component.translatable("command.neroeconomy.job.released"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int fulfillJob(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return playerOnly(context);
        UUID id = parseId(context); if (id == null) return 0;
        FulfillResult result = ObligationSavedData.get(player.level().getServer()).fulfill(player, id);
        // The result carries its own wording, so a refusal says what is actually missing.
        if (result != FulfillResult.SUCCESS) return fail(context, result.translationKey());
        context.getSource().sendSuccess(() -> Component.translatable(result.translationKey()), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int listJobs(CommandContext<CommandSourceStack> context, JobType type) {
        var jobs = ObligationSavedData.get(context.getSource().getServer()).jobs(type);
        ServerPlayer viewer = context.getSource().getPlayer();
        UUID viewerId = viewer == null ? null : viewer.getUUID();
        context.getSource().sendSuccess(() -> Component.translatable("command.neroeconomy.job.list", jobs.size()), false);
        jobs.stream().limit(20).forEach(job -> context.getSource().sendSuccess(() -> Component.translatable(
                "command.neroeconomy.job.row", job.id().toString(), targetLabel(job, viewerId), job.quantity(),
                job.payout(), Component.translatable(job.state().translationKey()), remaining(job.expiresAt())),
                false));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * What a viewer is shown as a job's target. Item, tag and entity ids are public. A player-target
     * bounty never prints the id it stores: the viewer gets a fixed phrase, chosen by whether they
     * posted it, are its target, or are anyone else (the console included).
     */
    private static Object targetLabel(EconomyJob job, UUID viewer) {
        String shown = JobTargets.visibleTo(job.target(), job.issuer(), viewer);
        String key = JobTargets.playerLabelKey(shown);
        // An id is an argument as it stands; only the player forms have wording of their own.
        return key == null ? shown : Component.translatable(key);
    }

    private static int listMine(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return playerOnly(context);
        MinecraftServer server = player.level().getServer();
        UUID self = player.getUUID();
        MarketSavedData market = MarketSavedData.get(server);
        ObligationSavedData obligations = ObligationSavedData.get(server);
        // Each list is filtered to the caller in the store, so nobody else's row can reach this output.
        var listings = market.listingsOf(self);
        var offers = market.buyOffersOf(self);
        var jobs = obligations.jobsIssuedBy(self);
        var auctions = obligations.auctionsBy(self);
        CommandSourceStack source = context.getSource();
        source.sendSuccess(() -> Component.translatable("command.neroeconomy.market.mine.header",
                listings.size() + offers.size() + jobs.size() + auctions.size()), false);
        listings.stream().limit(20).forEach(listing -> source.sendSuccess(() -> Component.translatable(
                "command.neroeconomy.market.mine.listing", listing.listingId().toString(),
                listing.itemId().toString(), listing.availableQuantity(), listing.baseUnitPrice(),
                Component.translatable(listing.source() == ListingSource.PLAYER_SHOP
                        ? "command.neroeconomy.market.mine.source.shop"
                        : "command.neroeconomy.market.mine.source.board")), false));
        offers.stream().limit(20).forEach(offer -> source.sendSuccess(() -> Component.translatable(
                "command.neroeconomy.market.mine.buy_offer", offer.offerId().toString(), offer.itemId().toString(),
                offer.remaining(), offer.unitPrice(), offer.escrow()), false));
        jobs.stream().limit(20).forEach(job -> source.sendSuccess(() -> Component.translatable(
                "command.neroeconomy.market.mine.job", job.id().toString(),
                Component.translatable("command.neroeconomy.job_type." + job.type().name().toLowerCase(Locale.ROOT)),
                targetLabel(job, self), job.quantity(), job.payout(),
                Component.translatable(job.state().translationKey()), remaining(job.expiresAt())),
                false));
        auctions.stream().limit(20).forEach(auction -> source.sendSuccess(() -> Component.translatable(
                "command.neroeconomy.market.mine.auction", auction.id().toString(),
                BuiltInRegistries.ITEM.getKey(auction.lot().getItem()).toString(), auction.lot().getCount(),
                auction.highestBid(), remaining(auction.endsAt())), false));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Cancels whichever of the caller's own rows has this id: a board listing (stock to their item
     * claim), a buy offer or an unclaimed job (escrow to their credit claim), or an auction with no
     * bid (lot to their vault). Each store only acts on a row the caller owns and reports
     * anything else as not found, so the id of somebody else's row gets the same answer as no id.
     */
    private static int cancelMine(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return playerOnly(context);
        // A whole id, or the first characters of one of the caller's own rows.
        ShortIds.Match match = ShortIds.resolve(StringArgumentType.getString(context, "id"), ownRows(player));
        if (!match.found()) return fail(context, match.ambiguous() ? "command.neroeconomy.trade.id_ambiguous"
                : CancelResult.NOT_FOUND.translationKey());
        UUID id = match.id();
        if (frozen(context)) return 0;
        MinecraftServer server = player.level().getServer();
        UUID self = player.getUUID();
        MarketSavedData market = MarketSavedData.get(server);
        ObligationSavedData obligations = ObligationSavedData.get(server);
        CancelResult result = market.cancelBoardListing(id, self);
        if (result == CancelResult.NOT_FOUND) result = market.cancelBuyOffer(id, self);
        if (result == CancelResult.NOT_FOUND) result = obligations.cancelJob(server, id, self);
        if (result == CancelResult.NOT_FOUND) result = obligations.cancelAuction(server, id, self);
        if (result != CancelResult.CANCELLED) return fail(context, result.translationKey());
        context.getSource().sendSuccess(() -> Component.translatable(CancelResult.CANCELLED.translationKey()), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int createAuction(CommandContext<CommandSourceStack> context, boolean interplanetary) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return playerOnly(context);
        if (frozen(context) || marketClosed(context, player)) return 0;
        // The lot comes out of the hand, which a creative-mode or spectator one may never supply.
        if (GoodsSource.refused(player)) return 0;
        long end = NeroEconomyCommon.CURRENCY_PROVIDER.now()
                + IntegerArgumentType.getInteger(context, "minutes") * 60_000L;
        ObligationSavedData store = ObligationSavedData.get(player.level().getServer());
        int cap = EconomyConfig.MAX_AUCTIONS_PER_PLAYER.get();
        if (store.auctionsBy(player.getUUID()).size() >= cap)
            return fail(context, "command.neroeconomy.auction.limit_reached", cap);
        var auction = store.createAuction(player,
                IntegerArgumentType.getInteger(context, "quantity"), LongArgumentType.getLong(context, "minimumBid"),
                end, interplanetary);
        if (auction == null) return fail(context, "command.neroeconomy.auction.create_failed");
        context.getSource().sendSuccess(() -> Component.translatable("command.neroeconomy.auction.created",
                auction.id().toString(), auction.interplanetary(),
                Fees.percentText(EconomyConfig.AUCTION_FEE_PERCENT.get())), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int bid(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return playerOnly(context);
        UUID id = parseId(context); if (id == null) return 0;
        if (frozen(context) || marketClosed(context, player)) return 0;
        ObligationSavedData store = ObligationSavedData.get(player.level().getServer());
        long amount = LongArgumentType.getLong(context, "amount");
        long minimum = store.minimumNextBid(id);
        // The usual refusal is a bid under the minimum increment, so that one names the number to beat.
        if (minimum > 0L && amount < minimum) return fail(context, "command.neroeconomy.auction.bid_too_low", minimum);
        // A bid is offered to another player, so unvested starting credits cannot fund it.
        if (locked(context, player, amount)) return 0;
        if (!store.bid(player, id, amount)) return fail(context, "command.neroeconomy.auction.bid_failed");
        context.getSource().sendSuccess(() -> Component.translatable("command.neroeconomy.auction.bid_placed", amount), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int listAuctions(CommandContext<CommandSourceStack> context) {
        // A console listing is unfiltered; a player only sees lots their progression allows.
        var auctions = ObligationSavedData.get(context.getSource().getServer())
                .auctions(context.getSource().getPlayer());
        context.getSource().sendSuccess(() -> Component.translatable("command.neroeconomy.auction.list", auctions.size()), false);
        auctions.stream().limit(20).forEach(auction -> context.getSource().sendSuccess(() -> Component.translatable(
                "command.neroeconomy.auction.row", auction.id().toString(), auction.highestBid(),
                remaining(auction.endsAt())), false));
        return Command.SINGLE_SUCCESS;
    }

    private static UUID parseId(CommandContext<CommandSourceStack> context) {
        try { return UUID.fromString(StringArgumentType.getString(context, "id")); }
        catch (IllegalArgumentException ignored) {
            context.getSource().sendFailure(Component.translatable("command.neroeconomy.invalid_id")); return null;
        }
    }

    private static int playerOnly(CommandContext<CommandSourceStack> context) {
        return fail(context, "command.neroeconomy.player_only");
    }

    /** Sends a translated failure to the caller only and reports the command as failed. */
    private static int fail(CommandContext<CommandSourceStack> context, String key, Object... args) {
        context.getSource().sendFailure(Component.translatable(key, args));
        return 0;
    }

    /**
     * An online player, or an offline id this world already knows; see {@link KnownAccounts}, which
     * the Bank Terminal resolves its recipient through as well.
     */
    private static UUID resolveAccount(CommandContext<CommandSourceStack> context) {
        UUID id = KnownAccounts.resolve(context.getSource().getServer(),
                StringArgumentType.getString(context, "player"));
        if (id == null) context.getSource().sendFailure(Component.translatable("command.neroeconomy.player_not_found"));
        return id;
    }

    /**
     * Export and erasure are admin-only, never mint anything, and must still reach a data subject
     * whose world files are already gone, so they accept any well-formed id.
     */
    private static UUID resolveSubject(CommandContext<CommandSourceStack> context) {
        String value = StringArgumentType.getString(context, "player");
        ServerPlayer online = context.getSource().getServer().getPlayerList().getPlayerByName(value);
        if (online != null) return online.getUUID();
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException ignored) {
            context.getSource().sendFailure(Component.translatable("command.neroeconomy.player_not_found"));
            return null;
        }
    }

    // ================================================================================================
    // Gallery — an admin-only showcase scene for the listing screenshots.
    //
    // `/neroeconomy gallery` builds one cluster per system around the player (GalleryBuilder, laid
    // out by gallery/GalleryLayout — the coordinate contract with client/GalleryCaptureHarness), each
    // with a label stand. `/neroeconomy gallery clear` removes exactly what the gallery placed.
    //
    // Both need permission level 3, a creative-mode player and `adminMutationsEnabled`, and both are
    // counted through AdminOps like every other operator action. Nothing the gallery seeds is real:
    // its board rows, sample tickers and Auto-Trader loop live in the store's gallery sandbox, which
    // is never part of the market, the book, link snapshots, the Reserve, the supply report or an
    // invariant; its Player Shops are flagged demo before any stock goes in.
    // ================================================================================================

    private static LiteralArgumentBuilder<CommandSourceStack> galleryTree() {
        return Commands.literal("gallery")
                // Checked again every time the command is parsed, so turning the switch off or
                // de-opping a player takes effect at once.
                .requires(source -> EconomyConfig.ADMIN_MUTATIONS_ENABLED.get()
                        && Commands.hasPermission(Commands.LEVEL_ADMINS).test(source))
                .executes(context -> gallerySafely(context.getSource(), false))
                .then(Commands.literal("clear").executes(context -> gallerySafely(context.getSource(), true)));
    }

    /**
     * Runs a gallery command, turning an unexpected failure into a plain message. The failure is
     * reported without the player, the position or anything else about who ran it.
     */
    private static int gallerySafely(CommandSourceStack source, boolean clear) {
        try {
            return clear ? clearGallery(source) : buildGallery(source);
        } catch (RuntimeException failure) {
            NeroEconomyTelemetry.captureHandledException(failure, clear ? "gallery_clear" : "gallery_build");
            NeroEconomyCommon.LOGGER.error("[NeroEconomy] gallery command failed", failure);
            source.sendFailure(Component.translatable("command.neroeconomy.gallery.failed"));
            return 0;
        }
    }

    /** The creative-mode player running a gallery command, or null after telling the caller why not. */
    private static ServerPlayer galleryOperator(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("command.neroeconomy.player_only"));
            return null;
        }
        if (!player.isCreative()) {
            source.sendFailure(Component.translatable("command.neroeconomy.gallery.creative_only"));
            return null;
        }
        return player;
    }

    private static int buildGallery(CommandSourceStack source) {
        ServerPlayer player = galleryOperator(source);
        if (player == null) return 0;
        switch (GalleryBuilder.build(player)) {
            case FROZEN -> {
                source.sendFailure(Component.translatable(EconomyHealth.FROZEN_KEY));
                return 0;
            }
            case ALREADY_STANDING -> {
                source.sendFailure(Component.translatable("command.neroeconomy.gallery.already_built"));
                return 0;
            }
            case BUILT -> AdminOps.galleryBuilt(source.getServer());
        }
        source.sendSuccess(() -> Component.translatable("command.neroeconomy.gallery.built"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int clearGallery(CommandSourceStack source) {
        ServerPlayer player = galleryOperator(source);
        if (player == null) return 0;
        GalleryBuilder.Cleared cleared = GalleryBuilder.clear(player);
        AdminOps.galleryCleared(source.getServer());
        source.sendSuccess(() -> Component.translatable("command.neroeconomy.gallery.cleared", cleared.blocks(),
                cleared.entities(), cleared.rows()), false);
        return Command.SINGLE_SUCCESS;
    }
}
