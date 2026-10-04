package za.co.neroland.neroeconomy.command;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.AccountId;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.book.BookSection;
import za.co.neroland.neroeconomy.book.BookStacks;
import za.co.neroland.neroeconomy.book.BuyOrder;
import za.co.neroland.neroeconomy.book.Placed;
import za.co.neroland.neroeconomy.book.SellOrder;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.direct.DirectTrades;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.economy.KnownAccounts;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.market.CancelResult;
import za.co.neroland.neroeconomy.menu.TradeMessages;
import za.co.neroland.neroeconomy.network.IntentGuard;
import za.co.neroland.neroeconomy.network.RateLimiter;
import za.co.neroland.neroeconomy.offers.Offer;
import za.co.neroland.neroeconomy.offers.OfferOutcome;
import za.co.neroland.neroeconomy.offers.OfferStacks;
import za.co.neroland.neroeconomy.rules.RuleOutcome;
import za.co.neroland.neroeconomy.rules.RuleSide;
import za.co.neroland.neroeconomy.rules.RuleStacks;
import za.co.neroland.neroeconomy.rules.RulesSection;
import za.co.neroland.neroeconomy.rules.TradeRule;
import za.co.neroland.neroeconomy.text.ShortIds;
import za.co.neroland.neroeconomy.text.TimeText;

/**
 * The trading commands under the economy roots: {@code order}, {@code offer}, {@code rule} and
 * {@code trade} (which is also a root of its own, {@code /trade}). They are shortcuts to the same
 * entry points the screens and the companion app use ({@link BookStacks}, {@link OfferStacks},
 * {@link RuleStacks}, {@link DirectTrades}); nothing is decided here.
 *
 * <p>Every command is for a player and is guarded the way a payload is: alive, not a spectator,
 * within the shared packet budget, and — when it moves value — refused while the economy is
 * frozen. Each value-moving command carries a fresh request id through the shared request ledger.
 * Ids are shown as a short prefix and accepted as one; tab-completion offers the caller's own rows
 * and nobody else's. Nothing a command prints names another player, and nothing is logged.
 */
final class TradingCommands {
    private static final int MAX_QUANTITY = BookSection.MAX_ORDER_QUANTITY;
    private static final int ROWS = 20;

    private TradingCommands() { }

    // --- who may run them --------------------------------------------------------------------

    /**
     * The player running a trading command, or null after telling the caller why not: not a
     * player, a frozen economy, dead or spectating, or over the packet budget.
     */
    static ServerPlayer trader(CommandContext<CommandSourceStack> context, boolean movesValue) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return refuse(context, "command.neroeconomy.player_only");
        if (movesValue && EconomyHealth.frozen()) return refuse(context, EconomyHealth.FROZEN_KEY);
        if (!player.isAlive() || player.isSpectator()) return refuse(context, "command.neroeconomy.trade.not_now");
        if (!IntentGuard.withinBudget(player, movesValue ? RateLimiter.Budget.VALUE : RateLimiter.Budget.QUERY))
            return refuse(context, "command.neroeconomy.trade.too_fast");
        return player;
    }

    private static ServerPlayer refuse(CommandContext<CommandSourceStack> context, String key) {
        context.getSource().sendFailure(Component.translatable(key));
        return null;
    }

    static int fail(CommandContext<CommandSourceStack> context, String key, Object... args) {
        context.getSource().sendFailure(Component.translatable(key, args));
        return 0;
    }

    private static void say(CommandContext<CommandSourceStack> context, String key, Object... args) {
        context.getSource().sendSuccess(() -> Component.translatable(key, args), false);
    }

    private static AccountId account(ServerPlayer player) { return new PlayerAccountId(player.getUUID()); }

    private static EconomyStore store(ServerPlayer player) { return EconomyStore.get(player.level().getServer()); }

    /** An argument that was not part of the command as typed is simply absent. */
    private static <T> T optional(CommandContext<CommandSourceStack> context, String name, Class<T> type) {
        try { return context.getArgument(name, type); }
        catch (IllegalArgumentException absent) { return null; }
    }

    private static Component remaining(long economyDeadline) {
        TimeText.Remaining left = TimeText.remaining(economyDeadline - NeroEconomyCommon.CURRENCY_PROVIDER.now());
        return Component.translatable(left.key(), left.first(), left.second());
    }

    // --- ids ---------------------------------------------------------------------------------

    private static List<UUID> orderIds(ServerPlayer player) {
        BookSection book = store(player).book();
        List<UUID> ids = new ArrayList<>();
        for (SellOrder order : book.sellsOf(account(player))) ids.add(order.id());
        for (BuyOrder order : book.buysOf(account(player))) ids.add(order.id());
        return ids;
    }

    private static List<UUID> offerIds(ServerPlayer player, boolean incoming, boolean outgoing) {
        List<UUID> ids = new ArrayList<>();
        if (incoming) for (Offer offer : store(player).offers().receivedBy(account(player))) ids.add(offer.id());
        if (outgoing) for (Offer offer : store(player).offers().sentBy(account(player))) ids.add(offer.id());
        return ids;
    }

    private static List<UUID> ruleIds(ServerPlayer player) {
        List<UUID> ids = new ArrayList<>();
        for (TradeRule rule : store(player).rules().rulesOf(account(player))) ids.add(rule.id());
        return ids;
    }

    /** Tab-completion of the caller's own rows, as the short ids the lists show. */
    private static SuggestionProvider<CommandSourceStack> own(Function<ServerPlayer, List<UUID>> rows) {
        return (context, builder) -> {
            ServerPlayer player = context.getSource().getPlayer();
            return player == null ? builder.buildFuture()
                    : SharedSuggestionProvider.suggest(ShortIds.all(rows.apply(player)), builder);
        };
    }

    /**
     * The id the caller typed, resolved against their own rows; null after telling them when it
     * matches nothing of theirs or more than one row.
     */
    private static UUID id(CommandContext<CommandSourceStack> context, List<UUID> own) {
        ShortIds.Match match = ShortIds.resolve(StringArgumentType.getString(context, "id"), own);
        if (match.found()) return match.id();
        fail(context, match.ambiguous() ? "command.neroeconomy.trade.id_ambiguous" : "command.neroeconomy.trade.id_unknown");
        return null;
    }

    private static final SuggestionProvider<CommandSourceStack> ITEM_IDS = (context, builder) ->
            SharedSuggestionProvider.suggestResource(BuiltInRegistries.ITEM.keySet(), builder);

    /** The unmodified items in the caller's own vault. */
    private static final SuggestionProvider<CommandSourceStack> VAULT_ITEMS = (context, builder) -> {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return builder.buildFuture();
        Set<Identifier> items = new LinkedHashSet<>();
        for (Lot lot : store(player).vault().stacks(player.getUUID())) {
            Identifier id = lot.pristine() ? Identifier.tryParse(lot.item()) : null;
            if (id != null) items.add(id);
        }
        return SharedSuggestionProvider.suggestResource(items, builder);
    };

    private static final SuggestionProvider<CommandSourceStack> ITEM_TAGS = (context, builder) ->
            SharedSuggestionProvider.suggestResource(
                    BuiltInRegistries.ITEM.getTags().map(tag -> tag.key().location()), builder);

    private static final SuggestionProvider<CommandSourceStack> PLAYER_NAMES = (context, builder) ->
            SharedSuggestionProvider.suggest(context.getSource().getOnlinePlayerNames(), builder);

    // --- order -------------------------------------------------------------------------------

    static LiteralArgumentBuilder<CommandSourceStack> order() {
        return Commands.literal("order")
                .then(Commands.literal("sell")
                        .then(Commands.literal("vault").then(Commands.argument("item", IdentifierArgument.id())
                                .suggests(VAULT_ITEMS).then(Commands.argument("unitPrice", LongArgumentType.longArg(1L))
                                        .then(Commands.argument("quantity", IntegerArgumentType.integer(1, MAX_QUANTITY))
                                                .executes(TradingCommands::sellFromVault)))))
                        .then(Commands.argument("unitPrice", LongArgumentType.longArg(1L))
                                .executes(context -> sellFromHand(context, 0))
                                .then(Commands.argument("quantity", IntegerArgumentType.integer(1, 64))
                                        .executes(context -> sellFromHand(context,
                                                IntegerArgumentType.getInteger(context, "quantity"))))))
                .then(Commands.literal("buy").then(Commands.argument("item", IdentifierArgument.id()).suggests(ITEM_IDS)
                        .then(Commands.argument("quantity", IntegerArgumentType.integer(1, MAX_QUANTITY))
                                .then(Commands.argument("unitPrice", LongArgumentType.longArg(1L))
                                        .executes(TradingCommands::buyOrder)))))
                .then(Commands.literal("cancel").then(Commands.argument("id", StringArgumentType.word())
                        .suggests(own(TradingCommands::orderIds)).executes(TradingCommands::cancelOrder)))
                .then(Commands.literal("mine").executes(TradingCommands::myOrders));
    }

    /** {@code order sell <unitPrice> [quantity]}: the stack in the main hand; all of it when no quantity is given. */
    private static int sellFromHand(CommandContext<CommandSourceStack> context, int quantity) {
        ServerPlayer player = trader(context, true);
        if (player == null) return 0;
        ItemStack held = player.getMainHandItem();
        int count = quantity > 0 ? quantity : held.getCount();
        if (held.isEmpty() || count < 1) return fail(context, "message.neroeconomy.order.refused",
                Component.translatable("message.neroeconomy.order.not_holding"));
        long price = LongArgumentType.getLong(context, "unitPrice");
        BookStacks.HandSale sale = BookStacks.sellFromHand(player, count, price, UUID.randomUUID());
        TradeMessages.sellOrder(player, sale.placed(), sale.offered().isEmpty() ? ""
                : BuiltInRegistries.ITEM.getKey(sale.offered().getItem()).toString(), price);
        return sale.placed().ok() ? Command.SINGLE_SUCCESS : 0;
    }

    private static int sellFromVault(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = trader(context, true);
        if (player == null) return 0;
        String item = IdentifierArgument.getId(context, "item").toString();
        long price = LongArgumentType.getLong(context, "unitPrice");
        Placed placed = BookStacks.sellFromVault(player.level().getServer(), player.getUUID(), item, Lot.PRISTINE,
                IntegerArgumentType.getInteger(context, "quantity"), price, UUID.randomUUID());
        TradeMessages.sellOrder(player, placed, item, price);
        return placed.ok() ? Command.SINGLE_SUCCESS : 0;
    }

    private static int buyOrder(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = trader(context, true);
        if (player == null) return 0;
        String item = IdentifierArgument.getId(context, "item").toString();
        long price = LongArgumentType.getLong(context, "unitPrice");
        Placed placed = BookStacks.buyOrder(player.level().getServer(), player.getUUID(), player, item,
                IntegerArgumentType.getInteger(context, "quantity"), price, UUID.randomUUID());
        TradeMessages.buyOrder(player, placed, item, price);
        return placed.ok() ? Command.SINGLE_SUCCESS : 0;
    }

    private static int cancelOrder(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = trader(context, true);
        if (player == null) return 0;
        UUID id = id(context, orderIds(player));
        if (id == null) return 0;
        CancelResult result = BookStacks.cancel(player.level().getServer(), player.getUUID(), id);
        if (result == CancelResult.NOT_CANCELLABLE) return fail(context, "command.neroeconomy.order.rule_owned");
        if (result != CancelResult.CANCELLED) return fail(context, result.translationKey());
        say(context, CancelResult.CANCELLED.translationKey());
        return Command.SINGLE_SUCCESS;
    }

    private static int myOrders(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = trader(context, false);
        if (player == null) return 0;
        BookSection book = store(player).book();
        List<SellOrder> sells = book.sellsOf(account(player));
        List<BuyOrder> buys = book.buysOf(account(player));
        List<UUID> ids = orderIds(player);
        if (ids.isEmpty()) {
            say(context, "command.neroeconomy.order.mine.empty");
            return Command.SINGLE_SUCCESS;
        }
        say(context, "command.neroeconomy.order.mine.header", sells.size(), buys.size());
        sells.stream().limit(ROWS).forEach(order -> say(context, "command.neroeconomy.order.mine.sell",
                ShortIds.of(order.id(), ids), order.lot().count(), goodsName(order.lot()), order.unitPrice(),
                source(order.source().name()), remaining(order.expiresAt())));
        buys.stream().limit(ROWS).forEach(order -> say(context, "command.neroeconomy.order.mine.buy",
                ShortIds.of(order.id(), ids), order.remaining(), TradeMessages.itemName(order.item()), order.unitPrice(),
                order.escrow(), source(order.source().name()), remaining(order.expiresAt())));
        return Command.SINGLE_SUCCESS;
    }

    private static Component source(String name) {
        return Component.translatable("command.neroeconomy.order.source." + name.toLowerCase(Locale.ROOT));
    }

    /** The item's own name, with a note when the goods are a modified stack. Never a name a player typed. */
    private static Component goodsName(Lot lot) {
        Component name = TradeMessages.itemName(lot.item());
        return lot.pristine() ? name : Component.translatable("command.neroeconomy.trade.modified", name);
    }

    // --- offer -------------------------------------------------------------------------------

    static LiteralArgumentBuilder<CommandSourceStack> offer() {
        return Commands.literal("offer")
                .then(Commands.literal("send").then(Commands.argument("player", StringArgumentType.word())
                        .suggests(PLAYER_NAMES)
                        .then(Commands.literal("hand").then(withCreditsAndAsk(
                                Commands.argument("handCount", IntegerArgumentType.integer(1, 64)))))
                        .then(Commands.literal("vault").then(Commands.argument("giveItem", IdentifierArgument.id())
                                .suggests(VAULT_ITEMS).then(withCreditsAndAsk(
                                        Commands.argument("giveCount", IntegerArgumentType.integer(1, MAX_QUANTITY))))))
                        .then(Commands.literal("credits").then(withAsk(
                                Commands.argument("giveCredits", LongArgumentType.longArg(1L)))))))
                .then(Commands.literal("list").executes(TradingCommands::listOffers))
                .then(Commands.literal("accept").then(Commands.argument("id", StringArgumentType.word())
                        .suggests(own(player -> offerIds(player, true, false))).executes(TradingCommands::acceptOffer)))
                .then(Commands.literal("decline").then(Commands.argument("id", StringArgumentType.word())
                        .suggests(own(player -> offerIds(player, true, false))).executes(TradingCommands::declineOffer)))
                .then(Commands.literal("cancel").then(Commands.argument("id", StringArgumentType.word())
                        .suggests(own(player -> offerIds(player, false, true))).executes(TradingCommands::cancelOffer)))
                .then(Commands.literal("mute").then(Commands.argument("muted", BoolArgumentType.bool())
                        .executes(TradingCommands::muteOffers)));
    }

    /** What may follow what is given: nothing, or {@code ask item <item> <count> [credits <n>]}, or {@code ask credits <n>}. */
    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T withAsk(T node) {
        node.executes(TradingCommands::sendOffer);
        node.then(Commands.literal("ask")
                .then(Commands.literal("item").then(Commands.argument("askItem", IdentifierArgument.id())
                        .suggests(ITEM_IDS).then(Commands.argument("askCount", IntegerArgumentType.integer(1, MAX_QUANTITY))
                                .executes(TradingCommands::sendOffer)
                                .then(Commands.literal("credits").then(Commands.argument("askCredits",
                                        LongArgumentType.longArg(1L)).executes(TradingCommands::sendOffer))))))
                .then(Commands.literal("credits").then(Commands.argument("askCredits", LongArgumentType.longArg(1L))
                        .executes(TradingCommands::sendOffer))));
        return node;
    }

    /** Items given may be followed by {@code credits <n>} given as well, and then by what is asked. */
    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T withCreditsAndAsk(T node) {
        withAsk(node);
        node.then(Commands.literal("credits").then(withAsk(Commands.argument("giveCredits", LongArgumentType.longArg(1L)))));
        return node;
    }

    private static int sendOffer(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = trader(context, true);
        if (player == null) return 0;
        MinecraftServer server = player.level().getServer();
        // A recipient is an online player or an id this world already knows; an offer opens no account.
        UUID recipient = KnownAccounts.resolve(server, StringArgumentType.getString(context, "player"));
        if (recipient == null) return fail(context, "command.neroeconomy.player_not_found");
        Integer handCount = optional(context, "handCount", Integer.class);
        Identifier giveItem = optional(context, "giveItem", Identifier.class);
        Integer giveCount = optional(context, "giveCount", Integer.class);
        Long giveCredits = optional(context, "giveCredits", Long.class);
        Identifier askItem = optional(context, "askItem", Identifier.class);
        Integer askCount = optional(context, "askCount", Integer.class);
        Long askCredits = optional(context, "askCredits", Long.class);
        List<Lot> vaultLots = giveItem == null || giveCount == null ? List.of() : List.of(Lot.of(giveItem.toString(), giveCount));
        OfferStacks.Terms terms = new OfferStacks.Terms(handCount == null ? 0 : handCount, vaultLots,
                giveCredits == null ? 0L : giveCredits, askItem == null ? null : askItem.toString(),
                askCount == null ? 0 : askCount, askCredits == null ? 0L : askCredits);
        OfferOutcome sent = OfferStacks.send(server, player.getUUID(), player, recipient, terms, UUID.randomUUID());
        if (!sent.ok()) return fail(context, "command.neroeconomy.offer.refused", Component.translatable(sent.result().translationKey()));
        Offer offer = sent.offer();
        // What left the sender, restated: the items and the credits now held for the offer.
        say(context, "command.neroeconomy.offer.sent", TradeMessages.shortId(offer.id()), given(offer),
                remaining(offer.expiresAt()));
        if (offer.asksItems() || offer.askCredits() > 0L) say(context, "command.neroeconomy.offer.sent_ask", asked(offer));
        ServerPlayer online = server.getPlayerList().getPlayer(recipient);
        // The recipient is told an offer is waiting, not who sent it.
        if (online != null) online.sendSystemMessage(Component.translatable("command.neroeconomy.offer.received",
                TradeMessages.shortId(offer.id())));
        return Command.SINGLE_SUCCESS;
    }

    /** What an offer gives: its items by their own names and its credits. */
    private static Component given(Offer offer) {
        MutableComponent out = Component.empty();
        boolean first = true;
        for (Lot lot : offer.lots()) {
            if (!first) out.append(Component.literal(", "));
            out.append(Component.translatable("command.neroeconomy.trade.items", lot.count(), goodsName(lot)));
            first = false;
        }
        if (offer.credits() > 0L) {
            if (!first) out.append(Component.literal(", "));
            out.append(Component.translatable("command.neroeconomy.trade.credits", offer.credits()));
            first = false;
        }
        return first ? Component.translatable("command.neroeconomy.trade.nothing") : out;
    }

    /** What an offer asks in return. */
    private static Component asked(Offer offer) {
        MutableComponent out = Component.empty();
        boolean first = true;
        if (offer.asksItems()) {
            out.append(Component.translatable("command.neroeconomy.trade.items", offer.askCount(),
                    TradeMessages.itemName(offer.askItem())));
            first = false;
        }
        if (offer.askCredits() > 0L) {
            if (!first) out.append(Component.literal(", "));
            out.append(Component.translatable("command.neroeconomy.trade.credits", offer.askCredits()));
            first = false;
        }
        return first ? Component.translatable("command.neroeconomy.trade.nothing") : out;
    }

    private static int listOffers(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = trader(context, false);
        if (player == null) return 0;
        EconomyStore store = store(player);
        List<Offer> incoming = store.offers().receivedBy(account(player));
        List<Offer> outgoing = store.offers().sentBy(account(player));
        List<UUID> ids = offerIds(player, true, true);
        if (store.offers().muted(account(player))) say(context, "command.neroeconomy.offer.list.muted");
        if (ids.isEmpty()) {
            say(context, "command.neroeconomy.offer.list.empty");
            return Command.SINGLE_SUCCESS;
        }
        say(context, "command.neroeconomy.offer.list.header", incoming.size(), outgoing.size());
        // Neither list names the other party: an offer shows what it holds and what it asks.
        incoming.stream().limit(ROWS).forEach(offer -> say(context, "command.neroeconomy.offer.list.incoming",
                ShortIds.of(offer.id(), ids), given(offer), asked(offer), remaining(offer.expiresAt())));
        outgoing.stream().limit(ROWS).forEach(offer -> say(context, "command.neroeconomy.offer.list.outgoing",
                ShortIds.of(offer.id(), ids), given(offer), asked(offer), remaining(offer.expiresAt())));
        return Command.SINGLE_SUCCESS;
    }

    private static int acceptOffer(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = trader(context, true);
        if (player == null) return 0;
        UUID id = id(context, offerIds(player, true, false));
        if (id == null) return 0;
        MinecraftServer server = player.level().getServer();
        OfferOutcome accepted = OfferStacks.accept(server, player.getUUID(), id, UUID.randomUUID());
        if (!accepted.ok()) return fail(context, "command.neroeconomy.offer.refused",
                Component.translatable(accepted.result().translationKey()));
        Offer offer = accepted.offer();
        // What was received and what was given, with the trade tax taken on the way.
        say(context, "command.neroeconomy.offer.accepted", offer.itemCount(), accepted.recipientGot(), asked(offer),
                accepted.tax());
        ServerPlayer sender = online(server, offer.sender());
        if (sender != null) sender.sendSystemMessage(Component.translatable("command.neroeconomy.offer.accepted_sender",
                accepted.senderGets()));
        return Command.SINGLE_SUCCESS;
    }

    private static int declineOffer(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = trader(context, true);
        if (player == null) return 0;
        UUID id = id(context, offerIds(player, true, false));
        if (id == null) return 0;
        MinecraftServer server = player.level().getServer();
        OfferOutcome declined = OfferStacks.decline(server, player.getUUID(), id);
        if (!declined.ok()) return fail(context, "command.neroeconomy.offer.refused",
                Component.translatable(declined.result().translationKey()));
        say(context, "command.neroeconomy.offer.declined");
        ServerPlayer sender = online(server, declined.offer().sender());
        if (sender != null) sender.sendSystemMessage(Component.translatable("command.neroeconomy.offer.declined_sender"));
        return Command.SINGLE_SUCCESS;
    }

    private static int cancelOffer(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = trader(context, true);
        if (player == null) return 0;
        UUID id = id(context, offerIds(player, false, true));
        if (id == null) return 0;
        OfferOutcome cancelled = OfferStacks.cancel(player.level().getServer(), player.getUUID(), id);
        if (!cancelled.ok()) return fail(context, "command.neroeconomy.offer.refused",
                Component.translatable(cancelled.result().translationKey()));
        Offer offer = cancelled.offer();
        say(context, cancelled.refundedToClaim() ? "command.neroeconomy.offer.cancelled_claim"
                : "command.neroeconomy.offer.cancelled", offer.itemCount(), offer.credits());
        return Command.SINGLE_SUCCESS;
    }

    private static int muteOffers(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = trader(context, false);
        if (player == null) return 0;
        boolean muted = BoolArgumentType.getBool(context, "muted");
        if (EconomyHealth.frozen()) return fail(context, EconomyHealth.FROZEN_KEY);
        OfferStacks.mute(player.level().getServer(), player.getUUID(), muted);
        say(context, muted ? "command.neroeconomy.offer.muted" : "command.neroeconomy.offer.unmuted");
        return Command.SINGLE_SUCCESS;
    }

    private static ServerPlayer online(MinecraftServer server, AccountId account) {
        return account instanceof PlayerAccountId player ? server.getPlayerList().getPlayer(player.playerId()) : null;
    }

    // --- trade -------------------------------------------------------------------------------

    /** The players who have asked the caller for a trade; nobody else's requests are offered. */
    private static final SuggestionProvider<CommandSourceStack> TRADE_ASKERS = (context, builder) ->
            SharedSuggestionProvider.suggest(DirectTrades.askerNames(context.getSource().getPlayer()), builder);

    /**
     * {@code trade <player>}, {@code trade accept|decline [player]} and {@code trade mute <true|false>}.
     * A literal wins over the player argument, so a player called "accept" is asked with a
     * sneak-use instead.
     */
    static LiteralArgumentBuilder<CommandSourceStack> trade() {
        return Commands.literal("trade")
                .then(Commands.literal("accept").executes(context -> answerTrade(context, true))
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(TRADE_ASKERS)
                                .executes(context -> answerTrade(context, true))))
                .then(Commands.literal("decline").executes(context -> answerTrade(context, false))
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(TRADE_ASKERS)
                                .executes(context -> answerTrade(context, false))))
                // The same flag as "offer mute": one switch for offers and trade requests.
                .then(Commands.literal("mute").then(Commands.argument("muted", BoolArgumentType.bool())
                        .executes(TradingCommands::muteOffers)))
                .then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYER_NAMES)
                        .executes(TradingCommands::requestTrade));
    }

    private static int requestTrade(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = trader(context, true);
        if (player == null) return 0;
        ServerPlayer target = player.level().getServer().getPlayerList()
                .getPlayerByName(StringArgumentType.getString(context, "player"));
        if (target == null) return fail(context, "command.neroeconomy.trade.request.offline");
        // Everything else is decided, and said, by the same code the sneak-use gesture reaches.
        DirectTrades.request(player, target, false);
        return Command.SINGLE_SUCCESS;
    }

    private static int answerTrade(CommandContext<CommandSourceStack> context, boolean accept) {
        ServerPlayer player = trader(context, accept);
        if (player == null) return 0;
        String name = optional(context, "player", String.class);
        ServerPlayer from = name == null ? null : player.level().getServer().getPlayerList().getPlayerByName(name);
        // A name that is nobody in the game cannot have a request waiting.
        if (name != null && from == null) return fail(context, "command.neroeconomy.trade.request.none");
        if (accept) DirectTrades.accept(player, from);
        else DirectTrades.decline(player, from);
        return Command.SINGLE_SUCCESS;
    }

    // --- rule --------------------------------------------------------------------------------

    static LiteralArgumentBuilder<CommandSourceStack> rule() {
        return Commands.literal("rule")
                .then(Commands.literal("add")
                        .then(Commands.literal("buy")
                                .then(Commands.literal("item").then(ruleTail(RuleSide.BUY, false)))
                                .then(Commands.literal("tag").then(ruleTail(RuleSide.BUY, true))))
                        .then(Commands.literal("sell")
                                .then(Commands.literal("item").then(ruleTail(RuleSide.SELL, false)))
                                .then(Commands.literal("tag").then(ruleTail(RuleSide.SELL, true)))))
                .then(Commands.literal("list").executes(TradingCommands::listRules))
                .then(Commands.literal("pause").then(Commands.argument("id", StringArgumentType.word())
                        .suggests(own(TradingCommands::ruleIds)).executes(context -> pauseRule(context, true))))
                .then(Commands.literal("resume").then(Commands.argument("id", StringArgumentType.word())
                        .suggests(own(TradingCommands::ruleIds)).executes(context -> pauseRule(context, false))))
                .then(Commands.literal("delete").then(Commands.argument("id", StringArgumentType.word())
                        .suggests(own(TradingCommands::ruleIds)).executes(TradingCommands::deleteRule)))
                .then(Commands.literal("pause-all").then(Commands.argument("paused", BoolArgumentType.bool())
                        .executes(TradingCommands::pauseAllRules)));
    }

    /** {@code <id> <limitPrice> <perDay> <total>}: the same for every kind of rule. */
    private static ArgumentBuilder<CommandSourceStack, ?> ruleTail(RuleSide side, boolean tag) {
        return Commands.argument("target", IdentifierArgument.id()).suggests(tag ? ITEM_TAGS : ITEM_IDS)
                .then(Commands.argument("limitPrice", LongArgumentType.longArg(1L))
                        .then(Commands.argument("perDay", IntegerArgumentType.integer(1, MAX_QUANTITY))
                                .then(Commands.argument("total", LongArgumentType.longArg(1L))
                                        .executes(context -> addRule(context, side, tag)))));
    }

    private static int addRule(CommandContext<CommandSourceStack> context, RuleSide side, boolean tag)
            throws CommandSyntaxException {
        ServerPlayer player = trader(context, true);
        if (player == null) return 0;
        String target = IdentifierArgument.getId(context, "target").toString();
        RuleOutcome added = RuleStacks.add(player.level().getServer(), player.getUUID(), side, tag, target,
                LongArgumentType.getLong(context, "limitPrice"), IntegerArgumentType.getInteger(context, "perDay"),
                LongArgumentType.getLong(context, "total"), RulesSection.VAULT, UUID.randomUUID());
        if (!added.ok()) return fail(context, "command.neroeconomy.rule.refused", Component.translatable(added.result().translationKey()));
        TradeRule rule = added.rule();
        // A buy rule restates the budget that just moved into escrow; a sell rule moved nothing yet.
        say(context, side == RuleSide.BUY ? "command.neroeconomy.rule.added_buy" : "command.neroeconomy.rule.added_sell",
                TradeMessages.shortId(rule.id()), rule.perDay(), target(rule), rule.limitPrice(), rule.total());
        return Command.SINGLE_SUCCESS;
    }

    /** What a rule trades: an item by its own name, or a tag as {@code #namespace:path}. */
    private static Component target(TradeRule rule) {
        return rule.tag() ? Component.literal("#" + rule.target()) : TradeMessages.itemName(rule.target());
    }

    private static int listRules(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = trader(context, false);
        if (player == null) return 0;
        RulesSection rules = store(player).rules();
        List<TradeRule> own = rules.rulesOf(account(player));
        List<UUID> ids = ruleIds(player);
        if (rules.pausedAll(account(player))) say(context, "command.neroeconomy.rule.list.paused_all");
        if (own.isEmpty()) {
            say(context, "command.neroeconomy.rule.list.empty");
            return Command.SINGLE_SUCCESS;
        }
        say(context, "command.neroeconomy.rule.list.header", own.size(), EconomyConfig.MAX_RULES_PER_PLAYER.get(),
                rules.valueToday(account(player), NeroEconomyCommon.CURRENCY_PROVIDER.now()),
                EconomyConfig.RULE_DAILY_VALUE_CAP.get());
        own.stream().limit(ROWS).forEach(rule -> say(context, rule.buys() ? "command.neroeconomy.rule.list.buy"
                : "command.neroeconomy.rule.list.sell", ShortIds.of(rule.id(), ids), target(rule), rule.limitPrice(),
                rule.filledToday(), rule.perDay(), rule.done(), rule.total(),
                Component.translatable(rule.status().translationKey())));
        return Command.SINGLE_SUCCESS;
    }

    private static int pauseRule(CommandContext<CommandSourceStack> context, boolean paused) {
        ServerPlayer player = trader(context, true);
        if (player == null) return 0;
        UUID id = id(context, ruleIds(player));
        if (id == null) return 0;
        RuleOutcome changed = RuleStacks.setPaused(player.level().getServer(), player.getUUID(), id, paused);
        if (!changed.ok()) return fail(context, "command.neroeconomy.rule.refused", Component.translatable(changed.result().translationKey()));
        say(context, paused ? "command.neroeconomy.rule.paused" : "command.neroeconomy.rule.resumed");
        return Command.SINGLE_SUCCESS;
    }

    private static int deleteRule(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = trader(context, true);
        if (player == null) return 0;
        UUID id = id(context, ruleIds(player));
        if (id == null) return 0;
        RuleOutcome deleted = RuleStacks.delete(player.level().getServer(), player.getUUID(), id);
        if (!deleted.ok()) return fail(context, "command.neroeconomy.rule.refused", Component.translatable(deleted.result().translationKey()));
        say(context, deleted.refundedToClaim() ? "command.neroeconomy.rule.deleted_claim" : "command.neroeconomy.rule.deleted",
                deleted.refunded());
        return Command.SINGLE_SUCCESS;
    }

    private static int pauseAllRules(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = trader(context, true);
        if (player == null) return 0;
        boolean paused = BoolArgumentType.getBool(context, "paused");
        int rules = RuleStacks.setPausedAll(player.level().getServer(), player.getUUID(), paused);
        say(context, paused ? "command.neroeconomy.rule.paused_all" : "command.neroeconomy.rule.resumed_all", rules);
        return Command.SINGLE_SUCCESS;
    }
}
