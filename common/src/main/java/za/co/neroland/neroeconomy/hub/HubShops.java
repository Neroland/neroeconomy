package za.co.neroland.neroeconomy.hub;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.block.PlayerShopBlockEntity;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.market.ListingSource;
import za.co.neroland.neroeconomy.market.MarketListing;
import za.co.neroland.neroeconomy.market.MarketSavedData;
import za.co.neroland.neroeconomy.network.IntentGuard;
import za.co.neroland.neroeconomy.shop.ShopOwnershipState;
import za.co.neroland.neroeconomy.shop.ShopSweep;

/**
 * The Shop tab of one open hub on the server: the viewer's own Player Shops, read from the shop
 * owner index and the shop listings, a price change for a shop whose chunk is loaded, and the
 * shop's own window for one within reach. A shop's chunk is never loaded for this.
 */
final class HubShops {
    private static final long DAY_MILLIS = 86_400_000L;

    private final ServerPlayer player;

    HubShops(ServerPlayer player) {
        this.player = player;
    }

    private MinecraftServer server() { return player.level().getServer(); }

    private UUID self() { return player.getUUID(); }

    private void say(String key, Object... args) { player.sendSystemMessage(Component.translatable(key, args)); }

    /** The shop at a location key when its chunk is loaded, or null. */
    private PlayerShopBlockEntity loaded(String location) {
        ShopSweep.Location at = ShopSweep.parse(location);
        if (at == null) return null;
        Identifier dimension = Identifier.tryParse(at.dimension());
        if (dimension == null) return null;
        ServerLevel level = server().getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
        if (level == null) return null;
        BlockPos pos = BlockPos.of(at.packedPosition());
        if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) return null;
        return level.getBlockEntity(pos) instanceof PlayerShopBlockEntity shop ? shop : null;
    }

    private List<ShopPages.ShopLine> lines() {
        long now = NeroEconomyCommon.CURRENCY_PROVIDER.now();
        Map<String, MarketListing> listings = new HashMap<>();
        for (MarketListing listing : MarketSavedData.get(server()).listingsOf(self()))
            if (listing.source() == ListingSource.PLAYER_SHOP && !listing.demo()) listings.put(listing.location(), listing);
        List<ShopPages.ShopLine> out = new ArrayList<>();
        for (String location : ShopOwnershipState.get(server()).locationsOf(self())) {
            ShopSweep.Location at = ShopSweep.parse(location);
            if (at == null) continue;
            BlockPos pos = BlockPos.of(at.packedPosition());
            MarketListing listing = listings.get(location);
            PlayerShopBlockEntity shop = loaded(location);
            boolean inReach = shop != null && shop.canManage(self()) && IntentGuard.anchored(player, shop);
            long price = listing != null ? listing.baseUnitPrice() : shop != null ? shop.price() : 0L;
            long idle = listing == null || listing.lastActivityAt() <= 0L ? 0L
                    : Math.max(0L, now - listing.lastActivityAt()) / DAY_MILLIS;
            out.add(new ShopPages.ShopLine(location, listing == null ? "" : listing.itemId().toString(), at.dimension(),
                    pos.getX(), pos.getY(), pos.getZ(), listing == null ? 0L : listing.availableQuantity(), price, idle,
                    inReach, shop != null));
        }
        return out;
    }

    HubPages.Built build(int page) {
        return ShopPages.build(new ShopPages.ShopView(lines(), EconomyConfig.SHOP_LISTING_MAX_IDLE_DAYS.get()), page);
    }

    long signature() { return Objects.hash(lines()); }

    /** Sets a shop's price, while its chunk is loaded; it is the owner's interaction, as at the shop. */
    void price(HubPages.RowRef row, long price) {
        PlayerShopBlockEntity shop = loaded(row.item());
        if (shop == null) {
            say("gui.neroeconomy.hub.shop.not_loaded");
            return;
        }
        if (!shop.canManage(self())) {
            say("gui.neroeconomy.hub.shop.not_yours");
            return;
        }
        shop.setPrice(price);
        if (shop.listingId() != null) MarketSavedData.get(server()).touchShop(shop.listingId());
        say("gui.neroeconomy.hub.shop.priced", shop.price());
    }

    /** Opens the shop's own window, for a shop of the viewer's within reach. */
    void open(HubPages.RowRef row) {
        PlayerShopBlockEntity shop = loaded(row.item());
        if (shop == null || !shop.canManage(self()) || !IntentGuard.anchored(player, shop)) {
            say("gui.neroeconomy.hub.shop.not_in_reach");
            return;
        }
        player.openMenu(shop);
    }
}
