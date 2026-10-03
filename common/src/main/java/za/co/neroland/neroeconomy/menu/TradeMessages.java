package za.co.neroland.neroeconomy.menu;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import za.co.neroland.neroeconomy.book.Fill;
import za.co.neroland.neroeconomy.book.Placed;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.economy.Fees;
import za.co.neroland.neroeconomy.market.PurchaseOutcome;

/**
 * What a player is told after a trade. Every success restates what was paid and what was received,
 * so nobody has to work a fee or a price adjustment out for themselves; every refusal gives the
 * reason. All wording is in the lang file.
 */
public final class TradeMessages {
    private TradeMessages() { }

    /**
     * The item's own name. Deliberately not a stack's hover name: a renamed stack carries text a
     * player typed, and a trade message never repeats that.
     */
    public static Component itemName(Identifier itemId) {
        if (itemId == null) return Component.empty();
        return BuiltInRegistries.ITEM.getOptional(itemId).map(item -> new ItemStack(item).getItemName())
                .orElseGet(() -> Component.literal(itemId.toString()));
    }

    /** The item an id names, by its own name; see {@link #itemName(Identifier)}. */
    public static Component itemName(String itemId) {
        Identifier id = itemId == null ? null : Identifier.tryParse(itemId);
        return id == null ? Component.literal(String.valueOf(itemId)) : itemName(id);
    }

    /**
     * Tells a seller how placing a sell order went: what sold at once, for how much and what of it
     * is theirs to collect; what is now listed and at what price; and the listing fee they paid.
     */
    public static void sellOrder(ServerPlayer seller, Placed placed, String itemId, long unitPrice) {
        if (!placed.ok()) {
            seller.sendSystemMessage(Component.translatable("message.neroeconomy.order.refused",
                    Component.translatable(placed.result().translationKey())));
            return;
        }
        Component item = itemName(itemId);
        if (placed.filled() > 0) seller.sendSystemMessage(Component.translatable("message.neroeconomy.order.sold_now",
                placed.filled(), item, placed.base(), placed.fees(), placed.sellerReceives()));
        if (placed.rested() > 0) seller.sendSystemMessage(Component.translatable("message.neroeconomy.order.sell_listed",
                placed.rested(), item, unitPrice, Fees.percentText(EconomyConfig.MARKET_FEE_PERCENT.get()),
                shortId(placed.orderId())));
        if (placed.listingFee() > 0L) seller.sendSystemMessage(Component.translatable(
                "message.neroeconomy.order.listing_fee", placed.listingFee()));
    }

    /**
     * Tells a buyer how placing a buy order went: what was bought at once and what it cost, what
     * came back because it was cheaper than their limit, what is still wanted and how much is held
     * in escrow for it, where the goods are, and the listing fee they paid.
     */
    public static void buyOrder(ServerPlayer buyer, Placed placed, String itemId, long unitPrice) {
        if (!placed.ok()) {
            buyer.sendSystemMessage(Component.translatable("message.neroeconomy.order.refused",
                    Component.translatable(placed.result().translationKey())));
            return;
        }
        Component item = itemName(itemId);
        if (placed.filled() > 0) {
            buyer.sendSystemMessage(Component.translatable("message.neroeconomy.order.bought_now", placed.filled(),
                    item, placed.buyerPaid()));
            long adjustment = 0L;
            boolean discountSkipped = false;
            for (Fill fill : placed.fills()) {
                adjustment += fill.adjustment();
                discountSkipped |= fill.discountSkipped();
            }
            if (adjustment > 0L)
                buyer.sendSystemMessage(Component.translatable("gui.neroeconomy.purchase.surcharge", adjustment));
            else if (adjustment < 0L)
                buyer.sendSystemMessage(Component.translatable("gui.neroeconomy.purchase.discount", -adjustment));
            if (discountSkipped)
                buyer.sendSystemMessage(Component.translatable("gui.neroeconomy.purchase.discount_skipped"));
            if (placed.refunded() > 0L) buyer.sendSystemMessage(Component.translatable(
                    "message.neroeconomy.order.refunded", placed.refunded()));
            if (placed.toVault()) buyer.sendSystemMessage(Component.translatable("gui.neroeconomy.purchase.to_vault"));
        }
        if (placed.rested() > 0) buyer.sendSystemMessage(Component.translatable("message.neroeconomy.order.buy_listed",
                placed.rested(), item, unitPrice, unitPrice * placed.rested(), shortId(placed.orderId())));
        if (placed.listingFee() > 0L) buyer.sendSystemMessage(Component.translatable(
                "message.neroeconomy.order.listing_fee", placed.listingFee()));
    }

    /** The first eight characters of an id: short enough to type, and the commands accept it. */
    public static String shortId(java.util.UUID id) {
        return id == null ? "" : id.toString().substring(0, 8);
    }

    /**
     * Tells a buyer how a purchase went.
     *
     * @param successKey takes the quantity, the item name and the credits paid
     * @param failedKey  takes the reason
     */
    public static void purchase(ServerPlayer buyer, PurchaseOutcome outcome, int quantity, Identifier itemId,
            String successKey, String failedKey) {
        if (!outcome.success()) {
            buyer.sendSystemMessage(Component.translatable(failedKey, Component.translatable(outcome.result().translationKey())));
            return;
        }
        buyer.sendSystemMessage(Component.translatable(successKey, quantity, itemName(itemId), outcome.paid()));
        long adjustment = outcome.adjustment();
        if (adjustment > 0L)
            buyer.sendSystemMessage(Component.translatable("gui.neroeconomy.purchase.surcharge", adjustment));
        else if (adjustment < 0L)
            buyer.sendSystemMessage(Component.translatable("gui.neroeconomy.purchase.discount", -adjustment));
        if (outcome.discountSkipped())
            buyer.sendSystemMessage(Component.translatable("gui.neroeconomy.purchase.discount_skipped"));
        // The goods did not go into the inventory, so say where they are.
        if (outcome.toVault())
            buyer.sendSystemMessage(Component.translatable("gui.neroeconomy.purchase.to_vault"));
    }
}
