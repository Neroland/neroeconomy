package za.co.neroland.neroeconomy.command;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.ExchangeApi;
import za.co.neroland.neroeconomy.exchange.BondOutcome;
import za.co.neroland.neroeconomy.exchange.BondTerm;
import za.co.neroland.neroeconomy.exchange.DividendRun;
import za.co.neroland.neroeconomy.exchange.ExchangeDesk;
import za.co.neroland.neroeconomy.exchange.ExchangeViews;
import za.co.neroland.neroeconomy.exchange.Investing;
import za.co.neroland.neroeconomy.exchange.PriceText;
import za.co.neroland.neroeconomy.exchange.Quotes;
import za.co.neroland.neroeconomy.exchange.TradeQuote;
import za.co.neroland.neroeconomy.exchange.TradeResult;
import za.co.neroland.neroeconomy.exchange.TradingTerms;
import za.co.neroland.neroeconomy.text.ShortIds;
import za.co.neroland.neroeconomy.text.TimeText;

/**
 * The exchange commands under the economy roots: {@code invest list}, {@code buy}, {@code sell},
 * {@code portfolio}, {@code bond buy}, {@code bond redeem}, and the same again under
 * {@code invest practice} for play credits, with {@code practice reset}. They are shortcuts to the
 * entry points the screens and the companion app use ({@link Investing}); nothing is decided here.
 *
 * <p>Every command is for a player and is guarded the way the other trading commands are: alive,
 * not a spectator, within the shared packet budget, and — when it moves real credits — refused
 * while the economy is frozen. Each one that changes anything carries a fresh request id. Every
 * purchase and sale answers with what was spent and what was received. Nothing a command prints
 * names another player, and nothing is logged.
 */
final class InvestCommands {
    private static final String ACCEPT = "accept-reduced";

    private InvestCommands() { }

    static LiteralArgumentBuilder<CommandSourceStack> invest() {
        return trading(Commands.literal("invest").then(Commands.literal("list").executes(InvestCommands::list)), false)
                .then(trading(Commands.literal("practice"), true)
                        .then(Commands.literal("reset").executes(InvestCommands::reset)));
    }

    /** The commands that exist twice: once for real credits, once under {@code practice}. */
    private static LiteralArgumentBuilder<CommandSourceStack> trading(LiteralArgumentBuilder<CommandSourceStack> root,
            boolean practice) {
        return root
                .then(Commands.literal("buy").then(Commands.argument("stock", IdentifierArgument.id()).suggests(STOCKS)
                        .then(Commands.argument("credits", LongArgumentType.longArg(1L))
                                .executes(context -> buy(context, practice)))))
                .then(Commands.literal("sell").then(Commands.argument("stock", IdentifierArgument.id())
                        .suggests(held(practice))
                        .then(Commands.literal("all").executes(context -> sell(context, practice, true, false))
                                .then(Commands.literal(ACCEPT).executes(context -> sell(context, practice, true, true))))
                        .then(Commands.argument("units", LongArgumentType.longArg(1L))
                                .executes(context -> sell(context, practice, false, false))
                                .then(Commands.literal(ACCEPT)
                                        .executes(context -> sell(context, practice, false, true))))))
                .then(Commands.literal("portfolio").executes(context -> portfolio(context, practice)))
                .then(Commands.literal("bond")
                        .then(Commands.literal("buy").then(Commands.argument("term", StringArgumentType.word())
                                .suggests(TERMS).then(Commands.argument("credits", LongArgumentType.longArg(1L))
                                        .executes(context -> bondBuy(context, practice)))))
                        .then(Commands.literal("redeem").then(Commands.argument("id", StringArgumentType.word())
                                .suggests(bonds(practice)).executes(context -> bondRedeem(context, practice)))));
    }

    // --- tab-completion ----------------------------------------------------------------------

    /** Every listed company's id, and {@code index} for the index fund. */
    private static final SuggestionProvider<CommandSourceStack> STOCKS = (context, builder) -> {
        List<String> ids = new ArrayList<>();
        for (ExchangeViews.Product product : Investing.products(context.getSource().getServer()))
            if (product.tradeable() || product.statusKey().equals(TradeResult.HALTED.translationKey()))
                ids.add(product.index() ? "index" : product.id());
        return SharedSuggestionProvider.suggest(ids, builder);
    };

    /** What the caller holds, real or practice: nobody else's. */
    private static SuggestionProvider<CommandSourceStack> held(boolean practice) {
        return (context, builder) -> {
            ServerPlayer player = context.getSource().getPlayer();
            if (player == null) return builder.buildFuture();
            List<String> ids = new ArrayList<>();
            for (ExchangeViews.HoldingView holding : view(player, practice).holdings())
                ids.add(holding.stockId().equals(ExchangeApi.INDEX_ID) ? "index" : holding.stockId());
            return SharedSuggestionProvider.suggest(ids, builder);
        };
    }

    private static final SuggestionProvider<CommandSourceStack> TERMS = (context, builder) -> {
        List<String> keys = new ArrayList<>();
        for (BondTerm term : BondTerm.values()) keys.add(term.key());
        return SharedSuggestionProvider.suggest(keys, builder);
    };

    /** The caller's own bonds, as the short ids the portfolio shows. */
    private static SuggestionProvider<CommandSourceStack> bonds(boolean practice) {
        return (context, builder) -> {
            ServerPlayer player = context.getSource().getPlayer();
            return player == null ? builder.buildFuture()
                    : SharedSuggestionProvider.suggest(ShortIds.all(bondIds(player, practice)), builder);
        };
    }

    private static List<UUID> bondIds(ServerPlayer player, boolean practice) {
        List<UUID> ids = new ArrayList<>();
        for (ExchangeViews.BondView bond : view(player, practice).bonds()) ids.add(bond.id());
        return ids;
    }

    // --- helpers -----------------------------------------------------------------------------

    private static MinecraftServer server(ServerPlayer player) { return player.level().getServer(); }

    private static ExchangeViews.PortfolioView view(ServerPlayer player, boolean practice) {
        return practice ? Investing.practicePortfolio(server(player), player.getUUID())
                : Investing.portfolio(server(player), player.getUUID());
    }

    /** A practice line says so, every time: nobody should take play credits for real ones. */
    private static void say(CommandContext<CommandSourceStack> context, boolean practice, String key, Object... args) {
        MutableComponent line = Component.translatable(key, args);
        context.getSource().sendSuccess(() -> practice
                ? Component.translatable("command.neroeconomy.invest.practice.line", line) : line, false);
    }

    private static int refuse(CommandContext<CommandSourceStack> context, TradeResult result, long payable, long gain) {
        return TradingCommands.fail(context, result.translationKey(), payable, gain);
    }

    private static int refuse(CommandContext<CommandSourceStack> context, TradeResult result) {
        return refuse(context, result, 0L, 0L);
    }

    /** The stock the caller typed, or null after telling them it names nothing. */
    private static String stock(CommandContext<CommandSourceStack> context, ServerPlayer player)
            throws CommandSyntaxException {
        String id = Investing.resolve(server(player), IdentifierArgument.getId(context, "stock").toString());
        if (id == null) refuse(context, TradeResult.UNKNOWN_STOCK);
        return id;
    }

    private static Component name(ExchangeViews.PortfolioView view, String stockId, MinecraftServer server) {
        for (ExchangeViews.HoldingView holding : view.holdings())
            if (holding.stockId().equals(stockId) && !holding.nameKey().isEmpty())
                return Component.translatable(holding.nameKey());
        return name(server, stockId);
    }

    private static Component name(MinecraftServer server, String stockId) {
        for (ExchangeViews.Product product : Investing.products(server))
            if (product.id().equals(stockId) && !product.nameKey().isEmpty()) return Component.translatable(product.nameKey());
        return Component.literal(stockId);
    }

    private static Component duration(long millis) {
        TimeText.Remaining left = TimeText.remaining(millis);
        return Component.translatable(left.key(), left.first(), left.second());
    }

    private static String signed(long value) { return value > 0L ? "+" + value : Long.toString(value); }

    // --- list --------------------------------------------------------------------------------

    private static int list(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = TradingCommands.trader(context, false);
        if (player == null) return 0;
        MinecraftServer server = server(player);
        if (!ExchangeApi.enabled()) say(context, false, TradeResult.EXCHANGE_OFF.translationKey());
        say(context, false, "command.neroeconomy.invest.list.header");
        for (ExchangeViews.Product product : Investing.products(server)) {
            Component name = Component.translatable(product.nameKey());
            String id = product.index() ? "index" : product.id();
            if (product.statusKey().equals(TradeResult.DELISTED.translationKey())) {
                say(context, false, "command.neroeconomy.invest.list.delisted", name);
                continue;
            }
            say(context, false, product.tradeable() ? "command.neroeconomy.invest.list.row"
                    : "command.neroeconomy.invest.list.row_halted", name, id, PriceText.credits(product.askCents()),
                    PriceText.credits(product.bidCents()), PriceText.percent(product.dayChangeBasisPoints()),
                    Component.translatable(product.riskKey()));
        }
        for (ExchangeViews.BondOffer bond : Investing.bondOffers())
            say(context, false, "command.neroeconomy.invest.list.bond", Component.translatable(bond.nameKey()), bond.id(),
                    PriceText.percent(bond.rateBasisPoints()), bond.days(), Component.translatable(bond.riskKey()));
        dividendNote(context, Investing.portfolio(server, player.getUUID()).dividends());
        return Command.SINGLE_SUCCESS;
    }

    private static void dividendNote(CommandContext<CommandSourceStack> context, DividendRun dividends) {
        if (dividends.happened() && dividends.scaled())
            say(context, false, "command.neroeconomy.invest.dividends_scaled", dividends.percent());
    }

    // --- buy and sell ------------------------------------------------------------------------

    private static int buy(CommandContext<CommandSourceStack> context, boolean practice) throws CommandSyntaxException {
        ServerPlayer player = TradingCommands.trader(context, !practice);
        if (player == null) return 0;
        String stockId = stock(context, player);
        if (stockId == null) return 0;
        MinecraftServer server = server(player);
        long credits = LongArgumentType.getLong(context, "credits");
        TradeQuote done = practice
                ? Investing.practiceBuyForCredits(server, player.getUUID(), stockId, credits, UUID.randomUUID())
                : Investing.buyForCredits(server, player.getUUID(), stockId, credits, UUID.randomUUID());
        if (!done.ok()) return refuse(context, done.result());
        say(context, practice, "command.neroeconomy.invest.bought", done.units(), name(server, stockId),
                PriceText.credits(done.unitPriceCents()), done.total(), done.fee(), done.net());
        long hold = TradingTerms.fromConfig().holdMillis();
        if (hold > 0L) say(context, practice, "command.neroeconomy.invest.hold", duration(hold));
        return Command.SINGLE_SUCCESS;
    }

    private static int sell(CommandContext<CommandSourceStack> context, boolean practice, boolean all,
            boolean acceptReduced) throws CommandSyntaxException {
        ServerPlayer player = TradingCommands.trader(context, !practice);
        if (player == null) return 0;
        String stockId = stock(context, player);
        if (stockId == null) return 0;
        MinecraftServer server = server(player);
        UUID id = player.getUUID();
        long units = all ? Quotes.ALL : LongArgumentType.getLong(context, "units");
        Component name = name(view(player, practice), stockId, server);
        TradeQuote done;
        if (practice) {
            done = Investing.practiceSell(server, id, stockId, units, UUID.randomUUID());
        } else {
            // "accept-reduced" accepts what the exchange can pay of the gain at this moment: the
            // amount is read and the sale made in the same tick, so the two cannot differ.
            long accepted = ExchangeDesk.FULL_GAIN_ONLY;
            if (acceptReduced) {
                TradeQuote quote = Investing.quoteSell(server, id, stockId, units);
                if (quote.result() == TradeResult.GAIN_REDUCED) accepted = quote.gainPayable();
            }
            done = Investing.sell(server, id, stockId, units, accepted, UUID.randomUUID());
        }
        if (!done.ok()) {
            refuse(context, done.result(), done.gainPayable(), done.gain());
            if (done.result() == TradeResult.HOLDING_PERIOD && done.earliestSellAt() > 0L)
                say(context, practice, "command.neroeconomy.invest.sellable", done.sellableUnits(),
                        duration(done.earliestSellAt() - NeroEconomyCommon.CURRENCY_PROVIDER.now()));
            return 0;
        }
        if (done.loss() > 0L) say(context, practice, "command.neroeconomy.invest.sold_loss", done.units(), name,
                PriceText.credits(done.unitPriceCents()), done.total(), done.loss(), done.fee(), done.net());
        else say(context, practice, "command.neroeconomy.invest.sold_gain", done.units(), name,
                PriceText.credits(done.unitPriceCents()), done.total(), done.costBasis(), done.gainPayable(), done.fee(),
                done.net());
        if (done.gainForfeited() > 0L) say(context, practice, "command.neroeconomy.invest.sold_reduced",
                done.gainPayable(), done.gain(), done.gainForfeited());
        return Command.SINGLE_SUCCESS;
    }

    // --- portfolio ---------------------------------------------------------------------------

    private static int portfolio(CommandContext<CommandSourceStack> context, boolean practice) {
        ServerPlayer player = TradingCommands.trader(context, false);
        if (player == null) return 0;
        ExchangeViews.PortfolioView view = view(player, practice);
        if (practice) say(context, true, "command.neroeconomy.invest.practice.balance", view.playBalance());
        if (view.isEmpty()) {
            say(context, practice, "command.neroeconomy.invest.portfolio.empty");
            return Command.SINGLE_SUCCESS;
        }
        say(context, practice, "command.neroeconomy.invest.portfolio.header", view.cost(), view.value(),
                signed(view.difference()));
        long now = NeroEconomyCommon.CURRENCY_PROVIDER.now();
        for (ExchangeViews.HoldingView holding : view.holdings()) {
            Component name = holding.nameKey().isEmpty() ? Component.literal(holding.stockId())
                    : Component.translatable(holding.nameKey());
            say(context, practice, "command.neroeconomy.invest.portfolio.holding", name, holding.units(), holding.cost(),
                    holding.value(), signed(holding.difference()), holding.sellableUnits());
            if (holding.earliestSellAt() > 0L) say(context, practice, "command.neroeconomy.invest.portfolio.waiting",
                    duration(holding.earliestSellAt() - now));
            if (!holding.statusKey().isEmpty()) say(context, practice, holding.statusKey(), 0L, 0L);
        }
        List<UUID> ids = new ArrayList<>();
        for (ExchangeViews.BondView bond : view.bonds()) ids.add(bond.id());
        for (ExchangeViews.BondView bond : view.bonds()) {
            Component name = Component.translatable(bond.nameKey());
            if (bond.matured()) say(context, practice, "command.neroeconomy.invest.portfolio.bond_matured",
                    ShortIds.of(bond.id(), ids), name, bond.principal(), bond.interest());
            else say(context, practice, "command.neroeconomy.invest.portfolio.bond", ShortIds.of(bond.id(), ids), name,
                    bond.principal(), bond.interest(), duration(bond.maturesAt() - now));
        }
        say(context, practice, "command.neroeconomy.invest.portfolio.limit", view.invested(), view.portfolioLimit());
        if (view.lastDividend() > 0L)
            say(context, practice, "command.neroeconomy.invest.portfolio.dividend", view.lastDividend());
        if (!practice) dividendNote(context, view.dividends());
        return Command.SINGLE_SUCCESS;
    }

    // --- bonds -------------------------------------------------------------------------------

    private static int bondBuy(CommandContext<CommandSourceStack> context, boolean practice) {
        ServerPlayer player = TradingCommands.trader(context, !practice);
        if (player == null) return 0;
        BondTerm term = BondTerm.parse(StringArgumentType.getString(context, "term"));
        if (term == null) return TradingCommands.fail(context, "command.neroeconomy.invest.bond.unknown_term");
        long credits = LongArgumentType.getLong(context, "credits");
        MinecraftServer server = server(player);
        BondOutcome done = practice ? Investing.practiceBuyBond(server, player.getUUID(), term, credits, UUID.randomUUID())
                : Investing.buyBond(server, player.getUUID(), term, credits, UUID.randomUUID());
        if (!done.ok()) return refuse(context, done.result());
        say(context, practice, "command.neroeconomy.invest.bond.bought", Component.translatable(term.translationKey()),
                done.bond().principal(), done.bond().interest(), duration(term.millis()),
                ShortIds.of(done.bond().id(), bondIds(player, practice)));
        return Command.SINGLE_SUCCESS;
    }

    private static int bondRedeem(CommandContext<CommandSourceStack> context, boolean practice) {
        ServerPlayer player = TradingCommands.trader(context, !practice);
        if (player == null) return 0;
        ShortIds.Match match = ShortIds.resolve(StringArgumentType.getString(context, "id"), bondIds(player, practice));
        if (!match.found()) return TradingCommands.fail(context, match.ambiguous()
                ? "command.neroeconomy.trade.id_ambiguous" : TradeResult.BOND_NOT_FOUND.translationKey());
        MinecraftServer server = server(player);
        BondOutcome done = practice ? Investing.practiceRedeemBond(server, player.getUUID(), match.id(), UUID.randomUUID())
                : Investing.redeemBond(server, player.getUUID(), match.id(), UUID.randomUUID());
        if (!done.ok()) return refuse(context, done.result());
        if (done.interest() > 0L || done.paid() > done.bond().principal())
            say(context, practice, "command.neroeconomy.invest.bond.paid_out", done.paid(), done.interest());
        else say(context, practice, "command.neroeconomy.invest.bond.redeemed_early", done.paid(),
                done.bond().interest());
        return Command.SINGLE_SUCCESS;
    }

    // --- practice reset ----------------------------------------------------------------------

    private static int reset(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = TradingCommands.trader(context, false);
        if (player == null) return 0;
        TradeResult result = Investing.practiceReset(server(player), player.getUUID(), UUID.randomUUID());
        if (!result.ok()) return refuse(context, result);
        say(context, true, "command.neroeconomy.invest.practice.reset",
                Investing.practicePortfolio(server(player), player.getUUID()).playBalance());
        return Command.SINGLE_SUCCESS;
    }
}
