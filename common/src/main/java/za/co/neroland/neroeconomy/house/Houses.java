package za.co.neroland.neroeconomy.house;

import java.util.UUID;

import net.minecraft.server.MinecraftServer;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.neroeconomy.commodity.CommodityData;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.obligation.AuctionTerms;
import za.co.neroland.neroeconomy.simulation.SimPipeline;
import za.co.neroland.neroeconomy.trade.SystemDelivery;
import za.co.neroland.neroeconomy.trade.Trading;

/**
 * The running game's side of the Merchant Houses: the {@link HouseEnv} built from the data packs
 * and the configuration, the simulation handler, the server tick's re-quote of traded commodities,
 * where the book delivers the Houses' goods, and the eraser.
 */
public final class Houses {
    /** The most commodities the server tick re-quotes at once; the rest wait for the next tick. */
    public static final int REQUOTES_PER_TICK = 8;

    private Houses() { }

    /** What the Houses work with on {@code server}'s economy now. */
    public static HouseEnv env(MinecraftServer server, EconomyStore store) {
        return new HouseEnv(CommodityData.forServer(server), HouseData.forServer(server), Trading.env(store),
                HouseSettings.fromConfig(), quotes -> CommodityData.buyOffAt(server, quotes));
    }

    /**
     * What the Houses' auctions and jobs work with on {@code server}'s economy now. A delivery job
     * delivers to the world's spawn point, the one place every player can find.
     */
    public static HouseDealsEnv dealsEnv(MinecraftServer server, EconomyStore store) {
        String destination = "";
        try {
            var spawn = server.getRespawnData().globalPos();
            destination = spawn.dimension().identifier() + "|" + spawn.pos().getX() + "|" + spawn.pos().getY() + "|"
                    + spawn.pos().getZ();
        } catch (RuntimeException unknown) {
            // No spawn point to deliver to: the Houses post contracts only.
        }
        return new HouseDealsEnv(env(server, store), AuctionTerms.fromConfig(), HouseDealSettings.fromConfig(),
                BountyData.forServer(server), destination);
    }

    /**
     * Registers the handlers: the Houses after the exchange's, so a step's prices settle before
     * they quote; then their auctions and jobs, which see that step's stock and prices.
     */
    public static void register(SimPipeline pipeline) {
        pipeline.register(new HouseStep(context -> context.server() == null ? null
                : env(context.server(), context.store())));
        HouseDealsStep.register(pipeline, context -> context.server() == null ? null
                : dealsEnv(context.server(), context.store()));
    }

    /**
     * Where the book delivers goods it owes a House: that House's stock, in the server's store.
     * Never refuses and never throws.
     */
    public static final SystemDelivery DELIVERY = new SystemDelivery() {
        @Override public boolean handles(SystemAccountId account) { return account != null && account.isHouse(); }

        @Override public void deliver(SystemAccountId account, Lot lot) {
            MinecraftServer server = NeroEconomyCommon.CURRENCY_PROVIDER.serverOrNull();
            if (server == null) return;
            try {
                EconomyStore.get(server).houses().deliver(account, lot);
            } catch (RuntimeException failure) {
                NeroEconomyCommon.LOGGER.warn("[NeroEconomy] Goods owed to a Merchant House could not be booked ({}).",
                        failure.getClass().getName());
            }
        }
    };

    /**
     * The server tick: commodities traded since they were last quoted are quoted again, a few per
     * tick, so a House's price walks with each chunk that fills. Only with the autonomous economy on.
     */
    public static void tick(MinecraftServer server) {
        if (server == null || EconomyHealth.frozen() || !EconomyConfig.AUTONOMOUS_ECONOMY.get()) return;
        EconomyStore store = EconomyStore.get(server);
        HouseSection houses = store.houses();
        if (!houses.hasDirty()) return;
        houses.requoteDirty(env(server, store), REQUOTES_PER_TICK);
    }

    /** Erasure: what the Houses recorded about the player today. Tolerates an unknown player. */
    public static void eraseFor(MinecraftServer server, UUID player) {
        if (server == null || player == null) return;
        EconomyStore.get(server).houses().forgetPlayer(player);
        EconomyStore.afterErasure(server, player);
    }
}
