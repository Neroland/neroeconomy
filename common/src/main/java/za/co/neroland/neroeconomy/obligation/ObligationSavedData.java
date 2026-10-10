package za.co.neroland.neroeconomy.obligation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.SavedDataType;

import za.co.neroland.nerolandcore.progression.CoreGates;
import za.co.neroland.neroeconomy.api.PlayerAccountId;
import za.co.neroland.neroeconomy.api.SystemAccountId;
import za.co.neroland.nerolandcore.progression.ProgressionGates;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.economy.EconomySection;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.economy.EscrowHolder;
import za.co.neroland.neroeconomy.economy.EscrowMath;
import za.co.neroland.neroeconomy.economy.LedgerPlan;
import za.co.neroland.neroeconomy.economy.LedgerResult;
import za.co.neroland.neroeconomy.economy.TransactionType;
import za.co.neroland.neroeconomy.market.CancelResult;
import za.co.neroland.neroeconomy.market.MarketLimits;
import za.co.neroland.neroeconomy.goods.GoodsSource;
import za.co.neroland.neroeconomy.goods.Lot;
import za.co.neroland.neroeconomy.goods.LotHolder;
import za.co.neroland.neroeconomy.market.MarketSavedData;
import za.co.neroland.neroeconomy.market.Settlement;
import za.co.neroland.neroeconomy.market.StoredStacks;
import za.co.neroland.neroeconomy.link.EconomyAlerts;
import za.co.neroland.neroeconomy.trade.TradeEnv;
import za.co.neroland.neroeconomy.trade.Trading;
import za.co.neroland.neroeconomy.vault.VaultStacks;
import za.co.neroland.nerolandcore.link.LinkAlert;

/**
 * Durable jobs, bounty payout escrow, and timed auction escrow: the obligation section of the
 * economy store.
 *
 * <p><b>Credits.</b> A job's payout and an auction's standing bid are numbers that say what the
 * ledger's escrow account is holding for that row. Posting a job or placing a bid is a transfer
 * into the escrow account; settling one turns the number into a credit claim (the credits stay in
 * escrow until collected) or sends it back where it came from.
 *
 * <p><b>Merchant Houses.</b> A House's auctions ({@link HouseAuction}) are kept here beside the
 * players' ones, with their lot as plain goods taken from the House's stock; players bid on them
 * through {@link #bid}, and the pure rules ({@link #bidHouseAuction}, {@link #settleHouseAuctions})
 * run without a server. A House's jobs are ordinary server-funded jobs (issuer
 * {@link #SYSTEM_ACCOUNT}) that also record which House posted them ({@link #houseOf}); what a worker
 * delivers for one goes into that House's stock. This section reports the House auction lots as the
 * goods it holds; a player's auction lot is an item stack and is not a lot.
 *
 * <p><b>Bounds.</b> At most {@link #MAX_HOUSE_AUCTIONS} House auction rows and
 * {@link #MAX_SYSTEM_JOBS} unsettled server-funded jobs; settled rows go with the audit window.
 */
public final class ObligationSavedData extends EconomySection implements EscrowHolder, LotHolder {
    /** The id this section was stored under when it was a store of its own; read once, on migration. */
    public static final Identifier ID = Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "obligations");
    /** Resolved on first use: the rows hold item stacks, whose codec needs the game's registries. */
    public static final Codec<ObligationSavedData> CODEC = Codec.lazyInitialized(ObligationSavedData::codec);
    /** The pre-combined-store type. Only {@link EconomyStore} uses it, to migrate and then empty the old file. */
    public static final SavedDataType<ObligationSavedData> TYPE =
            new SavedDataType<>(ID, ObligationSavedData::new, CODEC, null);
    private static final Identifier ORBIT_GATE = CoreGates.REACHED_ORBIT;
    /** Issuer of server-funded jobs. It has no wallet: their payout is escrowed from the Reserve. */
    public static final UUID SYSTEM_ACCOUNT = new UUID(0L, 0L);
    private static final long MILLIS_PER_DAY = 86_400_000L;
    private static final long SCHEDULE_INTERVAL_MILLIS = 3_600_000L;
    private static final long RETRY_INTERVAL_MILLIS = 60_000L;
    /** What an erased player's bounty target becomes when the bounty cannot be closed at once. */
    private static final String ERASED_TARGET = JobTargets.PLAYER_PREFIX + "erased";
    /** The most House auction rows kept, running and settled. */
    public static final int MAX_HOUSE_AUCTIONS = 1_024;
    /** The most unsettled server-funded jobs at once. */
    public static final int MAX_SYSTEM_JOBS = 256;
    private static final long NO_DAY = Long.MIN_VALUE;

    private final Map<UUID, EconomyJob> jobs = new LinkedHashMap<>();
    private final Map<UUID, AuctionRecord> auctions = new LinkedHashMap<>();
    /** Bounty id to per-claimant kill tally; a bounty only pays once its quantity is proven. */
    private final Map<UUID, Map<UUID, Integer>> bountyProgress = new LinkedHashMap<>();
    /** Earliest economy-clock millisecond at which {@link #tick} can have anything to do. */
    private long nextEventAt;
    private final Map<UUID, HouseAuction> houseAuctions = new LinkedHashMap<>();
    /** Job id to the id of the Merchant House that posted it. */
    private final Map<UUID, String> houseJobs = new LinkedHashMap<>();
    /** The economy-day the two counts below are for. */
    private long dealDay = NO_DAY;
    private int npcAuctionsToday;
    private int bountiesToday;
    /**
     * Counts the rows the simulation makes for the Houses and the server. Their ids come from it, not
     * from a random source, so a step replayed after a pause makes the very same rows.
     */
    private long dealSequence;
    private static final long DEAL_ID_HIGH = 0x4E45524F4445414CL;

    public ObligationSavedData() { }

    /** The obligation section of the world's economy store. */
    public static ObligationSavedData get(MinecraftServer server) {
        return EconomyStore.get(server).obligations();
    }

    /**
     * Erasure entry point; tolerates an unknown player. Must run before the market store's, because
     * it books counterparty refunds there. The purge is pushed into Core's recovery backup in the
     * same request, because that backup is a second copy of the same rows.
     */
    public static void eraseFor(MinecraftServer server, UUID player) {
        if (server == null || player == null) return;
        get(server).forgetPlayer(player);
        EconomyStore.afterErasure(server, player);
    }

    private static long now() { return NeroEconomyCommon.CURRENCY_PROVIDER.now(); }

    /**
     * Whether a job of this type can ever be completed against {@code target}: a registered item, a
     * non-empty item tag, a registered entity type other than the player type, or a player id. The
     * command layer runs the same check first so it can say what is wrong; it is repeated here so
     * that no caller can escrow a payout behind a target nothing matches.
     */
    public static boolean targetUsable(JobType type, String target) {
        if (type == null || !JobTargets.wellFormed(target)) return false;
        try {
            if (type == JobType.BOUNTY) {
                if (JobTargets.isPlayer(target)) {
                    UUID.fromString(target.substring(JobTargets.PLAYER_PREFIX.length()));
                    return true;
                }
                Identifier entity = Identifier.tryParse(target);
                // The player entity type would be a bounty on every player, outside the player-bounty gate.
                return entity != null && BuiltInRegistries.ENTITY_TYPE.containsKey(entity)
                        && !JobTargets.PLAYER_ENTITY.equals(entity.toString());
            }
            if (JobTargets.isTag(target)) {
                Identifier tag = Identifier.tryParse(target.substring(JobTargets.TAG_PREFIX.length()));
                return tag != null && BuiltInRegistries.ITEM.get(TagKey.create(Registries.ITEM, tag))
                        .map(named -> named.size() > 0).orElse(false);
            }
            Identifier item = Identifier.tryParse(target);
            Item value = item == null ? null : BuiltInRegistries.ITEM.getOptional(item).orElse(null);
            return value != null && value != Items.AIR && !MarketSavedData.untradeable(item);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    public synchronized EconomyJob postJob(ServerPlayer issuer, JobType type, String target, int quantity,
            String destination, long payout, long expiresAt) {
        if (EconomyHealth.frozen() || marketClosed()) return null;
        if (issuer == null || type == null || target == null || target.isBlank() || quantity < 1
                || destination == null || payout < 1L || expiresAt <= now()) return null;
        if (!targetUsable(type, target)) return null;
        // Player-target bounties are a server policy; the command adds the permission-level gate.
        if (JobTargets.isPlayer(target) && !EconomyConfig.PLAYER_BOUNTIES_ENABLED.get()) return null;
        if (!MarketLimits.withinCap(openJobCount(issuer.getUUID()), 1, EconomyConfig.MAX_JOBS_PER_PLAYER.get()))
            return null;
        // The payout is promised to another player, so it cannot come out of an unvested starting balance.
        if (!NeroEconomyCommon.CURRENCY_PROVIDER.execute(
                Settlement.escrow(issuer.getUUID(), payout, TransactionType.MARKET_ESCROW)).ok()) return null;
        UUID id = freshId();
        EconomyJob job = new EconomyJob(id, type, issuer.getUUID(), "", target, quantity, destination,
                payout, expiresAt, JobState.OPEN);
        jobs.put(id, job); invalidateSchedule(); setDirty(); return job;
    }

    /**
     * Server-funded job with no player issuer, posted through {@code DeliveryJobApi.postSystem}.
     * The payout moves from the Reserve into escrow when the job is posted, so it is there for the
     * worker at settlement; an expired job sends it back to the Reserve. Fulfilled goods are
     * consumed rather than claimed (the system account collects from no terminal).
     *
     * <p>Posting is refused while {@code systemJobsEnabled} is off, when the payouts posted in the
     * current economy day would pass {@code systemJobDailyPayoutCap}, and when the Reserve does not
     * hold the payout. The budget counts what is posted, whether or not the job is ever fulfilled.
     * The settings are read at call time, so a config change applies at once.
     */
    public synchronized EconomyJob postSystemJob(JobType type, String target, int quantity,
            String destination, long payout, long expiresAt) {
        long now = now();
        if (EconomyHealth.frozen() || marketClosed()) return null;
        if (type == null || quantity < 1 || destination == null || payout < 1L || expiresAt <= now
                || JobTargets.isPlayer(target) || !targetUsable(type, target)) return null;
        if (!EconomyConfig.SYSTEM_JOBS_ENABLED.get()) {
            NeroEconomyCommon.LOGGER.debug("[NeroEconomy] System job refused: system jobs are disabled.");
            return null;
        }
        LedgerResult escrowed = execute(Settlement.systemJobEscrow(payout));
        if (!escrowed.ok()) {
            NeroEconomyCommon.LOGGER.debug("[NeroEconomy] System job refused: the Reserve or its daily "
                    + "job budget cannot cover the payout.");
            return null;
        }
        UUID id = freshId();
        EconomyJob job = new EconomyJob(id, type, SYSTEM_ACCOUNT, "", target, quantity, destination,
                payout, expiresAt, JobState.OPEN);
        jobs.put(id, job); invalidateSchedule(); setDirty(); return job;
    }

    public synchronized boolean claim(UUID id, UUID player) { return claim(id, player, now()); }

    /** {@link #claim(UUID, UUID)} at economy time {@code now}: the rule itself, without a server. */
    public synchronized boolean claim(UUID id, UUID player, long now) {
        EconomyJob job = jobs.get(id);
        // Closing the market stops new claims; giving a claim back (release) still works.
        if (EconomyHealth.frozen() || marketClosed()) return false;
        if (job == null || player == null || job.state() != JobState.OPEN || job.issuer().equals(player)
                || job.expiresAt() <= now) return false;
        // A claim is exclusive, so the player a bounty is on must not be able to take it out of play.
        if (job.target().equals(JobTargets.player(player))) return false;
        jobs.put(id, job.claimedBy(player, now)); invalidateSchedule(); setDirty(); return true;
    }

    /**
     * Hands a claimed job back: the claimant gives it up and it is open to anyone again. Nothing
     * moves — a claim holds no escrow of the claimant's. Idempotent: a job that is not theirs to
     * release is simply refused.
     */
    public synchronized boolean release(UUID id, UUID player) {
        EconomyJob job = jobs.get(id);
        if (job == null || player == null || job.state() != JobState.CLAIMED
                || !player.equals(job.claimantId())) return false;
        jobs.put(id, job.reopened()); invalidateSchedule(); setDirty(); return true;
    }

    /**
     * Settles a claimed contract or delivery from the claimant's inventory. Only unmodified stacks
     * count: a job names an item id or tag, and a renamed, enchanted, filled or worn stack is not
     * what the issuer escrowed a payout for. Every refusal happens before the inventory is touched,
     * and the result says why.
     */
    public synchronized FulfillResult fulfill(ServerPlayer player, UUID id) {
        if (EconomyHealth.frozen()) return FulfillResult.FROZEN;
        if (marketClosed()) return FulfillResult.MARKET_CLOSED;
        EconomyJob job = jobs.get(id);
        if (player == null || job == null || job.state() != JobState.CLAIMED
                || !player.getUUID().equals(job.claimantId()) || job.type() == JobType.BOUNTY)
            return FulfillResult.NOT_CLAIMED;
        // The goods come out of the worker's inventory, which a creative-mode one may never supply.
        if (!GoodsSource.allowed(player)) return FulfillResult.CREATIVE;
        if (job.type() == JobType.DELIVERY && !atDestination(player, job.destination()))
            return FulfillResult.NOT_AT_DESTINATION;
        EconomyStore store = EconomyStore.get(player.level().getServer());
        MarketSavedData market = store.market();
        // Refuse before the inventory is touched when the payout could not be booked at all.
        if (!market.canAcceptCreditClaim(player.getUUID(), job.payout())) return FulfillResult.PAYOUT_BLOCKED;
        List<ItemStack> slots = player.getInventory().getNonEquipmentItems();
        int usable = 0;
        int modified = 0;
        for (ItemStack stack : slots) {
            if (!matches(stack, job.target())) continue;
            if (MarketSavedData.pristine(stack)) usable += stack.getCount();
            else modified += stack.getCount();
        }
        if (usable < job.quantity())
            return usable + modified >= job.quantity() ? FulfillResult.MODIFIED_ITEMS : FulfillResult.MISSING_ITEMS;
        // Work out the delivery first, merged per item, so the issuer's vault is checked against the
        // real stacks before anything leaves the inventory.
        Map<Item, Integer> taken = new LinkedHashMap<>();
        int remaining = job.quantity();
        for (ItemStack stack : slots) {
            if (remaining == 0) break;
            if (!matches(stack, job.target()) || !MarketSavedData.pristine(stack)) continue;
            int take = Math.min(remaining, stack.getCount());
            taken.merge(stack.getItem(), take, Integer::sum);
            remaining -= take;
        }
        List<ItemStack> goods = new ArrayList<>();
        taken.forEach((item, count) -> {
            int perStack = Math.max(1, new ItemStack(item).getMaxStackSize());
            for (int left = count; left > 0; left -= perStack) goods.add(new ItemStack(item, Math.min(perStack, left)));
        });
        // A House's job: the goods go into that House's stock, settled by the same rule the tests run.
        if (houseJobs.containsKey(id)) {
            List<Lot> lots = new ArrayList<>();
            taken.forEach((item, count) -> lots.add(Lot.of(BuiltInRegistries.ITEM.getKey(item).toString(), count)));
            FulfillResult settled = fulfilHouseJob(id, player.getUUID(), lots);
            if (settled != FulfillResult.SUCCESS) return settled;
            remaining = job.quantity();
            for (ItemStack stack : slots) {
                if (remaining == 0) break;
                if (!matches(stack, job.target()) || !MarketSavedData.pristine(stack)) continue;
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
            }
            return FulfillResult.SUCCESS;
        }
        // System jobs consume the goods; a vault for the system account could never be emptied.
        boolean system = SYSTEM_ACCOUNT.equals(job.issuer());
        if (!system && !VaultStacks.canAccept(store, job.issuer(), goods)) return FulfillResult.ISSUER_CLAIMS_FULL;
        // The payout has been in the escrow account since the job was posted; it is now the worker's.
        if (!market.addCreditClaim(player.getUUID(), job.payout(), TransactionType.CONTRACT_PAYOUT))
            return FulfillResult.PAYOUT_BLOCKED;
        remaining = job.quantity();
        for (ItemStack stack : slots) {
            if (remaining == 0) break;
            if (!matches(stack, job.target()) || !MarketSavedData.pristine(stack)) continue;
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            remaining -= take;
        }
        if (!system) goods.forEach(item -> VaultStacks.deliver(store, job.issuer(), item));
        EconomyAlerts.raise(player.level().getServer(), job.issuer(), EconomyAlerts.Kind.CONTRACT_FULFILLED,
                LinkAlert.Severity.INFO);
        jobs.put(id, job.withState(JobState.FULFILLED)); invalidateSchedule(); setDirty();
        return FulfillResult.SUCCESS;
    }

    /** Server-verified kill hook; entity ids are strings, so optional creature mods stay soft. */
    public synchronized int onKill(ServerPlayer killer, Entity victim) {
        if (killer == null || victim == null || EconomyHealth.frozen()) return 0;
        String entityId = BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()).toString();
        String playerTarget = victim instanceof ServerPlayer target ? JobTargets.player(target.getUUID()) : "";
        return recordKill(MarketSavedData.get(killer.level().getServer()), killer.getUUID(), entityId, playerTarget,
                now());
    }

    /**
     * {@link #onKill} as a rule, without a server: {@code killer} killed a mob of {@code entityId}
     * (or the player whose bounty target is {@code playerTarget}; empty for a mob) at economy time
     * {@code now}. Every bounty on it that the killer may work counts the kill; one that reaches its
     * quantity pays its escrowed payout to the killer's credit claim.
     *
     * @return how many bounties the kill completed
     */
    public synchronized int recordKill(MarketSavedData market, UUID killer, String entityId, String playerTarget,
            long now) {
        // A kill while an operator has closed the market completes no bounty, as no job is fulfilled then.
        if (market == null || killer == null || entityId == null || EconomyHealth.frozen() || marketClosed()) return 0;
        String victimPlayer = playerTarget == null ? "" : playerTarget;
        int completed = 0;
        boolean changed = false;
        for (EconomyJob job : new ArrayList<>(jobs.values())) {
            if (job.type() != JobType.BOUNTY || terminal(job.state())
                    || job.issuer().equals(killer) || job.expiresAt() <= now) continue;
            if (!job.target().equals(entityId) && !job.target().equals(victimPlayer)) continue;
            UUID claimant = job.claimantId();
            if (claimant != null && !claimant.equals(killer)) continue;
            // A bounty settles at its full quantity, not on the first kill.
            int progress = bountyProgress.computeIfAbsent(job.id(), ignored -> new LinkedHashMap<>())
                    .merge(killer, 1, Integer::sum);
            changed = true;
            if (progress < job.quantity()) continue;
            if (!market.addCreditClaim(killer, job.payout(), TransactionType.BOUNTY_PAYOUT)) continue;
            bountyProgress.remove(job.id());
            jobs.put(job.id(), job.claimedBy(killer, job.claimedAt()).withState(JobState.FULFILLED));
            completed++;
        }
        if (changed) { invalidateSchedule(); setDirty(); }
        return completed;
    }

    public synchronized AuctionRecord createAuction(ServerPlayer seller, int quantity, long minimumBid,
            long endsAt, boolean requestedInterplanetary) {
        if (EconomyHealth.frozen() || marketClosed()) return null;
        if (seller == null || quantity < 1 || minimumBid < 1L || endsAt <= now()) return null;
        // The lot comes out of the seller's hand, which a creative-mode one may never supply.
        if (!GoodsSource.allowed(seller)) return null;
        ItemStack held = seller.getMainHandItem();
        if (held.isEmpty() || held.getCount() < quantity) return null;
        if (!MarketLimits.withinCap(openAuctionCount(seller.getUUID()), 1,
                EconomyConfig.MAX_AUCTIONS_PER_PLAYER.get())) return null;
        if (!MarketSavedData.tradeable(held.copyWithCount(quantity), seller.registryAccess())) return null;
        boolean orbit = requestedInterplanetary && ProgressionGates.isOpen(seller, ORBIT_GATE);
        UUID id = freshId();
        AuctionRecord auction = new AuctionRecord(id, seller.getUUID(), held.copyWithCount(quantity), minimumBid,
                0L, "", endsAt, orbit, JobState.OPEN);
        auctions.put(id, auction); held.shrink(quantity); invalidateSchedule(); setDirty(); return auction;
    }

    public synchronized boolean bid(ServerPlayer bidder, UUID id, long amount) {
        if (bidder != null && id != null && houseAuctions.containsKey(id)) return bidHouse(bidder, id, amount);
        AuctionRecord auction = auctions.get(id);
        long now = now();
        if (EconomyHealth.frozen() || marketClosed()) return false;
        if (bidder == null || auction == null || auction.state() != JobState.OPEN || auction.endsAt() <= now
                || auction.seller().equals(bidder.getUUID()) || amount <= auction.highestBid()
                || amount < ObligationRules.minimumNextBid(auction.minimumBid(), auction.highestBid(),
                        EconomyConfig.AUCTION_MIN_INCREMENT_PERCENT.get())) return false;
        // The orbit gate is recorded on the lot, so enforce it on the way in as well as on display.
        if (auction.interplanetary() && !ProgressionGates.isOpen(bidder, ORBIT_GATE)) return false;
        UUID previous = auction.bidderId();
        boolean displaces = previous != null && !previous.equals(bidder.getUUID());
        EconomyStore store = EconomyStore.get(bidder.level().getServer());
        MarketSavedData market = store.market();
        // Winning delivers the lot to the bidder's vault, so that needs room before any credit moves.
        if (!VaultStacks.canAccept(store, bidder.getUUID(), List.of(auction.lot()))) return false;
        // Never debit the new bidder unless the displaced bid can actually be booked back.
        if (displaces && !market.canAcceptCreditClaim(previous, auction.highestBid())) return false;
        long debit = previous != null && previous.equals(bidder.getUUID()) ? amount - auction.highestBid() : amount;
        // A bid is offered to another player, so it cannot come out of an unvested starting balance.
        if (!NeroEconomyCommon.CURRENCY_PROVIDER.execute(
                Settlement.escrow(bidder.getUUID(), debit, TransactionType.AUCTION_BID)).ok()) return false;
        if (displaces) {
            // The displaced bid stays in the escrow account; it is now owed back to its bidder.
            market.addCreditClaim(previous, auction.highestBid(), TransactionType.ESCROW_REFUND);
            EconomyAlerts.raise(bidder.level().getServer(), previous, EconomyAlerts.Kind.OUTBID,
                    LinkAlert.Severity.WARN);
        }
        long window = EconomyConfig.AUCTION_ANTI_SNIPE_SECONDS.get() * 1000L;
        long end = window > 0L && auction.endsAt() - now <= window ? now + window : auction.endsAt();
        auctions.put(id, new AuctionRecord(id, auction.seller(), auction.lot(), auction.minimumBid(), amount,
                bidder.getUUID().toString(), end, auction.interplanetary(), JobState.OPEN));
        invalidateSchedule(); setDirty(); return true;
    }

    /**
     * The lowest bid a running auction will accept next, so a refused bidder can be told the number.
     *
     * @return the amount, or -1 when there is no such running auction
     */
    public synchronized long minimumNextBid(UUID id) {
        HouseAuction house = id == null ? null : houseAuctions.get(id);
        if (house != null) return minimumNextHouseBid(house, now(), EconomyConfig.AUCTION_MIN_INCREMENT_PERCENT.get());
        AuctionRecord auction = id == null ? null : auctions.get(id);
        if (auction == null || auction.state() != JobState.OPEN || auction.endsAt() <= now()) return -1L;
        return ObligationRules.minimumNextBid(auction.minimumBid(), auction.highestBid(),
                EconomyConfig.AUCTION_MIN_INCREMENT_PERCENT.get());
    }

    /**
     * Runs every server tick, so it must cost nothing when nothing is due: a watermark over the
     * earliest expiry, auction end, and prune deadline short-circuits the whole pass.
     */
    public synchronized void tick(MinecraftServer server, long now) {
        if (now < nextEventAt || EconomyHealth.frozen()) return;
        MarketSavedData market = MarketSavedData.get(server);
        boolean changed = expireJobs(market, now);
        changed |= releaseStaleClaims(now);
        changed |= settleAuctions(server, market, now);
        // The Houses' lots settle by the same rule the simulation runs; it marks the section itself.
        EconomyStore store = owner();
        if (store != null) settleHouseAuctions(Trading.env(store), AuctionTerms.fromConfig(), now)
                .forEach(winner -> EconomyAlerts.raise(server, winner, EconomyAlerts.Kind.AUCTION_WON,
                        LinkAlert.Severity.INFO));
        changed |= pruneTerminal(now);
        nextEventAt = nextEventAt(now);
        if (changed) setDirty();
    }

    private boolean expireJobs(MarketSavedData market, long now) {
        boolean changed = false;
        for (EconomyJob job : new ArrayList<>(jobs.values())) {
            if (terminal(job.state()) || job.expiresAt() > now) continue;
            // The escrow has to land somewhere before the row can be closed off: a player's goes to
            // their credit claim, a system job's goes back to the Reserve it came from.
            if (!releasePayout(market, job)) continue;
            // A House's job is closed; the House may post another for the same goods.
            bountyProgress.remove(job.id());
            jobs.put(job.id(), job.withState(JobState.EXPIRED));
            changed = true;
        }
        return changed;
    }

    /**
     * Hands an unsettled job's escrowed payout back to whoever funded it. @return false when it
     * could not be booked, in which case the job must keep it
     */
    private boolean releasePayout(MarketSavedData market, EconomyJob job) {
        if (!SYSTEM_ACCOUNT.equals(job.issuer()))
            return market != null && market.addCreditClaim(job.issuer(), job.payout(), TransactionType.ESCROW_REFUND);
        return execute(Settlement.escrowToReserve(job.payout(), TransactionType.ESCROW_REFUND)).ok();
    }

    /**
     * Applies a plan to the ledger of the store this section belongs to: the same path as the
     * currency provider's in the game, and the store's own ledger in a test.
     */
    private LedgerResult execute(LedgerPlan plan) {
        EconomyStore store = owner();
        return store != null ? store.execute(plan) : NeroEconomyCommon.CURRENCY_PROVIDER.execute(plan);
    }

    /**
     * A claim locks a job to one player. One that has gone unfulfilled for the whole
     * {@code jobClaimTimeoutMinutes} is dropped so the job is open to everyone again. A claim saved
     * before claim times were recorded starts its timeout now.
     */
    private boolean releaseStaleClaims(long now) {
        int timeout = EconomyConfig.JOB_CLAIM_TIMEOUT_MINUTES.get();
        boolean changed = false;
        for (EconomyJob job : new ArrayList<>(jobs.values())) {
            if (job.state() != JobState.CLAIMED) continue;
            if (job.claimedAt() <= 0L) { jobs.put(job.id(), job.withClaimedAt(now)); changed = true; }
            else if (ObligationRules.claimTimedOut(job.claimedAt(), now, timeout)) {
                jobs.put(job.id(), job.reopened()); changed = true;
            }
        }
        return changed;
    }

    private boolean settleAuctions(MinecraftServer server, MarketSavedData market, long now) {
        boolean changed = false;
        for (AuctionRecord auction : new ArrayList<>(auctions.values())) {
            if (auction.state() != JobState.OPEN || auction.endsAt() > now) continue;
            UUID winner = auction.bidderId();
            if (winner == null) VaultStacks.deliver(EconomyStore.get(server), auction.seller(), auction.lot());
            else {
                // The winning bid is in the escrow account. The auction fee comes out of it and goes
                // to the Reserve; the rest becomes the seller's credit claim. Proceeds first: the lot
                // only changes hands once the seller can actually be paid.
                Settlement.Sale sale = Settlement.sale(auction.highestBid(), EconomyConfig.AUCTION_FEE_PERCENT.get());
                long proceeds = sale.proceeds();
                if (proceeds > 0L && !market.canAcceptCreditClaim(auction.seller(), proceeds)) continue;
                if (!NeroEconomyCommon.CURRENCY_PROVIDER.execute(
                        Settlement.feeFromEscrow(sale.fee(), TransactionType.FEE_AUCTION)).ok()) continue;
                if (proceeds > 0L) market.addCreditClaim(auction.seller(), proceeds, TransactionType.AUCTION_PAYOUT);
                VaultStacks.deliver(EconomyStore.get(server), winner, auction.lot());
                EconomyAlerts.raise(server, winner, EconomyAlerts.Kind.AUCTION_WON, LinkAlert.Severity.INFO);
                announceAuction(auction.seller(), winner, net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(auction.lot().getItem()).toString(), auction.lot().getCount(), auction.highestBid(), now);
            }
            auctions.put(auction.id(), new AuctionRecord(auction.id(), auction.seller(), auction.lot(),
                    auction.minimumBid(), auction.highestBid(), auction.highestBidder(), auction.endsAt(),
                    auction.interplanetary(), JobState.FULFILLED)); changed = true;
        }
        return changed;
    }

    /**
     * The cross-mod events of one settled auction: the seller's side (a player's auction only) and
     * the winner's side, never in one event. The unit price is the winning bid over the count,
     * rounded down.
     */
    private static void announceAuction(UUID seller, UUID winner, String item, long count, long bid, long now) {
        if (count <= 0L) return;
        za.co.neroland.neroeconomy.sibling.EconomyEventBus.sold(seller, item, count, bid / count,
                za.co.neroland.neroeconomy.api.EconomyEvents.Venue.AUCTION, now);
        za.co.neroland.neroeconomy.sibling.EconomyEventBus.bought(winner, item, count, bid / count,
                za.co.neroland.neroeconomy.api.EconomyEvents.Venue.AUCTION, now);
    }

    /** Settled rows are dead weight once the audit window has passed; drop them with the ledger. */
    private boolean pruneTerminal(long now) {
        int days = EconomyConfig.TRANSACTION_RETENTION_DAYS.get();
        long cutoff = days <= 0 ? Long.MAX_VALUE : now - days * MILLIS_PER_DAY;
        boolean changed = jobs.entrySet().removeIf(entry -> terminal(entry.getValue().state())
                && entry.getValue().expiresAt() < cutoff);
        changed |= auctions.entrySet().removeIf(entry -> terminal(entry.getValue().state())
                && entry.getValue().endsAt() < cutoff);
        changed |= houseAuctions.entrySet().removeIf(entry -> !entry.getValue().open()
                && entry.getValue().endsAt() < cutoff);
        changed |= bountyProgress.keySet().removeIf(id -> !jobs.containsKey(id));
        changed |= houseJobs.keySet().removeIf(id -> !jobs.containsKey(id));
        return changed;
    }

    private long nextEventAt(long now) {
        long next = now + SCHEDULE_INTERVAL_MILLIS;
        int timeout = EconomyConfig.JOB_CLAIM_TIMEOUT_MINUTES.get();
        for (EconomyJob job : jobs.values()) {
            if (terminal(job.state())) continue;
            if (job.expiresAt() < next) next = job.expiresAt();
            if (job.state() == JobState.CLAIMED)
                next = Math.min(next, ObligationRules.claimDeadline(job.claimedAt(), timeout));
        }
        for (AuctionRecord auction : auctions.values())
            if (auction.state() == JobState.OPEN && auction.endsAt() < next) next = auction.endsAt();
        for (HouseAuction auction : houseAuctions.values())
            if (auction.open() && auction.endsAt() < next) next = auction.endsAt();
        // A due row that could not settle this pass is retried, but not once per tick.
        return next <= now ? now + RETRY_INTERVAL_MILLIS : next;
    }

    private void invalidateSchedule() { nextEventAt = 0L; }

    private static boolean terminal(JobState state) {
        return state == JobState.FULFILLED || state == JobState.EXPIRED;
    }

    // --- Merchant Houses: auctions and jobs ----------------------------------------------------

    /** A player's bid on a House's lot, through the ordinary auction flow: the orbit gate here, the rule below. */
    private boolean bidHouse(ServerPlayer bidder, UUID id, long amount) {
        HouseAuction auction = houseAuctions.get(id);
        EconomyStore store = owner();
        if (auction == null || store == null) return false;
        boolean orbit = !auction.interplanetary() || ProgressionGates.isOpen(bidder, ORBIT_GATE);
        HouseBid placed = bidHouseAuction(Trading.env(store), AuctionTerms.fromConfig(), bidder.getUUID(), id, amount,
                now(), orbit);
        if (placed.displaced() != null)
            EconomyAlerts.raise(bidder.level().getServer(), placed.displaced(), EconomyAlerts.Kind.OUTBID,
                    LinkAlert.Severity.WARN);
        return placed.ok();
    }

    private static long minimumNextHouseBid(HouseAuction auction, long now, int incrementPercent) {
        if (!auction.open() || auction.endsAt() <= now) return -1L;
        return ObligationRules.minimumNextBid(auction.minimumBid(), auction.highestBid(), incrementPercent);
    }

    private void rollDealDay(long now) {
        long day = EconomySavedData.economyDay(now);
        if (dealDay != NO_DAY && day <= dealDay) return;
        dealDay = day;
        npcAuctionsToday = 0;
        bountiesToday = 0;
    }

    /** The auctions the Houses started on the economy-day of {@code now}. */
    public synchronized int npcAuctionsOn(long now) {
        return dealDay != NO_DAY && EconomySavedData.economyDay(now) <= dealDay ? npcAuctionsToday : 0;
    }

    /** The server-funded bounties posted on the economy-day of {@code now}. */
    public synchronized int bountiesOn(long now) {
        return dealDay != NO_DAY && EconomySavedData.economyDay(now) <= dealDay ? bountiesToday : 0;
    }

    /**
     * Starts an auction for a House. The caller has already taken {@code lot} out of the House's
     * stock and gives it back when this refuses: when the economy is frozen, the row bound is
     * reached, or {@code perDay} auctions were already started on this economy-day, server-wide.
     *
     * @return the auction, or null
     */
    public synchronized HouseAuction startHouseAuction(String house, Lot lot, long minimumBid, long endsAt,
            boolean interplanetary, long now, int perDay) {
        if (EconomyHealth.frozen() || marketClosed() || house == null || house.isBlank() || lot == null || !lot.pristine()
                || minimumBid < 1L || endsAt <= now || houseAuctions.size() >= MAX_HOUSE_AUCTIONS) return null;
        rollDealDay(now);
        if (npcAuctionsToday >= perDay) return null;
        HouseAuction auction = new HouseAuction(dealId(), house, lot, minimumBid, 0L, "", endsAt, interplanetary,
                JobState.OPEN);
        houseAuctions.put(auction.id(), auction);
        npcAuctionsToday++;
        invalidateSchedule(); setDirty();
        return auction;
    }

    /**
     * A bid on a House's auction. Every refusal happens before anything moves. The bid comes out of
     * the bidder's spendable balance into escrow (a bidder raising their own bid pays only the
     * difference); a displaced bid becomes its bidder's credit claim; a bid inside the anti-snipe
     * window extends the auction. The bidder's vault must have room for the lot.
     *
     * @param orbitOpen whether the bidder has reached orbit: an interplanetary lot needs it
     */
    public synchronized HouseBid bidHouseAuction(TradeEnv env, AuctionTerms terms, UUID bidder, UUID id, long amount,
            long now, boolean orbitOpen) {
        if (EconomyHealth.frozen()) return HouseBid.refused(HouseBid.Result.FROZEN);
        if (marketClosed()) return HouseBid.refused(HouseBid.Result.MARKET_CLOSED);
        HouseAuction auction = id == null ? null : houseAuctions.get(id);
        EconomyStore store = owner();
        if (auction == null || bidder == null || store == null || env == null || terms == null)
            return HouseBid.refused(HouseBid.Result.NOT_FOUND);
        if (!auction.open() || auction.endsAt() <= now) return HouseBid.refused(HouseBid.Result.ENDED);
        if (amount <= auction.highestBid()
                || amount < ObligationRules.minimumNextBid(auction.minimumBid(), auction.highestBid(), terms.minIncrementPercent()))
            return HouseBid.refused(HouseBid.Result.TOO_LOW);
        if (auction.interplanetary() && !orbitOpen) return HouseBid.refused(HouseBid.Result.ORBIT);
        if (!store.vault().canAccept(new PlayerAccountId(bidder), List.of(auction.lot()), env.settings().vaultTradeCap(),
                env.limit())) return HouseBid.refused(HouseBid.Result.VAULT_FULL);
        UUID previous = auction.bidderId();
        boolean same = bidder.equals(previous);
        boolean displaces = previous != null && !same;
        MarketSavedData market = store.market();
        if (displaces && !market.canAcceptCreditClaim(previous, auction.highestBid()))
            return HouseBid.refused(HouseBid.Result.REFUND_BLOCKED);
        long debit = same ? amount - auction.highestBid() : amount;
        // A bid is promised to the seller through escrow, so unvested starting credits cannot fund it.
        if (!store.execute(Settlement.escrow(bidder, debit, TransactionType.AUCTION_BID)).ok())
            return HouseBid.refused(HouseBid.Result.FUNDS);
        if (displaces) market.addCreditClaim(previous, auction.highestBid(), TransactionType.ESCROW_REFUND);
        long window = terms.antiSnipeMillis();
        long end = window > 0L && auction.endsAt() - now <= window ? now + window : auction.endsAt();
        houseAuctions.put(id, auction.withBid(amount, bidder, end));
        invalidateSchedule(); setDirty();
        return new HouseBid(HouseBid.Result.PLACED, displaces ? previous : null);
    }

    /**
     * Settles every House auction that has ended by {@code now}. With no bid the lot goes back to
     * the House's stock. Otherwise, in one ledger operation, the auction fee goes from escrow to the
     * Reserve and the rest of the winning bid to the House's account (and from there, above its
     * working capital, back to the Reserve with the next sweep); then the lot goes to the winner's
     * vault, which always books. A row whose ledger operation is refused stays open and is tried again.
     *
     * @return the winners, for whoever raises their alerts; nobody is named to anybody else
     */
    public synchronized List<UUID> settleHouseAuctions(TradeEnv env, AuctionTerms terms, long now) {
        EconomyStore store = owner();
        if (store == null || env == null || terms == null || EconomyHealth.frozen()) return List.of();
        List<UUID> winners = new ArrayList<>();
        boolean changed = false;
        for (HouseAuction auction : new ArrayList<>(houseAuctions.values())) {
            if (!auction.open() || auction.endsAt() > now) continue;
            UUID winner = auction.bidderId();
            if (winner == null) store.houses().returnStock(auction.house(), auction.lot());
            else {
                Settlement.Sale sale = Settlement.sale(auction.highestBid(), terms.feePercent());
                LedgerPlan plan = LedgerPlan.builder()
                        .move(SystemAccountId.ESCROW, SystemAccountId.RESERVE, sale.fee(), TransactionType.FEE_AUCTION)
                        .move(SystemAccountId.ESCROW, SystemAccountId.house(auction.house()), sale.proceeds(),
                                TransactionType.AUCTION_PAYOUT).build();
                if (!store.execute(plan).ok()) continue;
                store.vault().deliver(new PlayerAccountId(winner), List.of(auction.lot()), env.settings().vaultCap(),
                        env.settings().vaultHardBound(), env.limit());
                winners.add(winner);
                // The House is never named: the winner's side only.
                announceAuction(null, winner, auction.lot().item(), auction.lot().count(), auction.highestBid(), now);
            }
            houseAuctions.put(auction.id(), auction.settled());
            changed = true;
        }
        if (changed) { invalidateSchedule(); setDirty(); }
        return winners;
    }

    /**
     * Posts a job for a House: a contract or a delivery for goods it is short of, whose payout moves
     * from the Reserve into escrow under the system-job budget, as every server-funded job's does.
     * Refused (null, nothing moved) while system jobs are switched off, when the House already has
     * {@code perHouse} open jobs or one for the same goods, at the row bound, and when the Reserve
     * or the day's budget cannot cover the payout.
     */
    public synchronized EconomyJob postHouseJob(String house, JobType type, String target, int quantity,
            String destination, long payout, long expiresAt, long now, boolean enabled, int perHouse) {
        if (EconomyHealth.frozen() || marketClosed() || !enabled || house == null || house.isBlank() || type == null
                || type == JobType.BOUNTY || !JobTargets.wellFormed(target) || JobTargets.isPlayer(target)
                || quantity < 1 || destination == null || payout < 1L || expiresAt <= now) return null;
        int open = 0;
        for (Map.Entry<UUID, String> entry : houseJobs.entrySet()) {
            EconomyJob job = jobs.get(entry.getKey());
            if (job == null || terminal(job.state()) || !entry.getValue().equals(house)) continue;
            if (job.target().equals(target)) return null;
            open++;
        }
        if (open >= perHouse || openSystemJobs() >= MAX_SYSTEM_JOBS) return null;
        if (!execute(Settlement.systemJobEscrow(payout)).ok()) return null;
        EconomyJob job = new EconomyJob(dealId(), type, SYSTEM_ACCOUNT, "", target, quantity, destination, payout,
                expiresAt, JobState.OPEN);
        jobs.put(job.id(), job);
        houseJobs.put(job.id(), house);
        invalidateSchedule(); setDirty();
        return job;
    }

    /**
     * Posts a server-funded bounty on a hostile mob, funded like any system job. At most
     * {@code perDay} are posted per economy-day, server-wide, and at most that many are open at once,
     * because a mob farm completes any kill bounty.
     */
    public synchronized EconomyJob postSystemBounty(String entity, int quantity, long payout, long expiresAt, long now,
            boolean enabled, int perDay) {
        if (EconomyHealth.frozen() || marketClosed() || !enabled || !JobTargets.wellFormed(entity)
                || JobTargets.isPlayer(entity)
                || JobTargets.isTag(entity) || JobTargets.PLAYER_ENTITY.equals(entity) || quantity < 1 || payout < 1L
                || expiresAt <= now) return null;
        rollDealDay(now);
        if (bountiesToday >= perDay || openSystemJobs() >= MAX_SYSTEM_JOBS) return null;
        int open = 0;
        for (EconomyJob job : jobs.values())
            if (job.type() == JobType.BOUNTY && SYSTEM_ACCOUNT.equals(job.issuer()) && !terminal(job.state())) open++;
        if (open >= perDay) return null;
        if (!execute(Settlement.systemJobEscrow(payout)).ok()) return null;
        EconomyJob job = new EconomyJob(dealId(), JobType.BOUNTY, SYSTEM_ACCOUNT, "", entity, quantity, "", payout,
                expiresAt, JobState.OPEN);
        jobs.put(job.id(), job);
        bountiesToday++;
        invalidateSchedule(); setDirty();
        return job;
    }

    private int openSystemJobs() {
        int open = 0;
        for (EconomyJob job : jobs.values()) if (SYSTEM_ACCOUNT.equals(job.issuer()) && !terminal(job.state())) open++;
        return open;
    }

    /**
     * Settles a House's claimed job with goods the worker hands over: the payout, already in escrow,
     * becomes the worker's credit claim, and the goods go into the House's stock. {@code goods} must
     * be unmodified goods of the job's item, exactly its quantity; the game takes them out of the
     * worker's inventory only after this succeeded. Every refusal happens before anything moves.
     */
    public synchronized FulfillResult fulfilHouseJob(UUID id, UUID worker, List<Lot> goods) {
        if (EconomyHealth.frozen()) return FulfillResult.FROZEN;
        if (marketClosed()) return FulfillResult.MARKET_CLOSED;
        EconomyJob job = id == null ? null : jobs.get(id);
        String house = id == null ? null : houseJobs.get(id);
        EconomyStore store = owner();
        if (job == null || house == null || worker == null || store == null || job.state() != JobState.CLAIMED
                || !worker.equals(job.claimantId()) || job.type() == JobType.BOUNTY) return FulfillResult.NOT_CLAIMED;
        if (goods == null) return FulfillResult.MISSING_ITEMS;
        long count = 0L;
        for (Lot lot : goods) {
            if (lot == null || !lot.pristine() || !lot.item().equals(job.target())) return FulfillResult.MISSING_ITEMS;
            count += lot.count();
        }
        if (count != job.quantity()) return FulfillResult.MISSING_ITEMS;
        if (!store.market().addCreditClaim(worker, job.payout(), TransactionType.CONTRACT_PAYOUT))
            return FulfillResult.PAYOUT_BLOCKED;
        for (Lot lot : goods) store.houses().returnStock(house, lot);
        jobs.put(id, job.withState(JobState.FULFILLED));
        invalidateSchedule(); setDirty();
        return FulfillResult.SUCCESS;
    }

    /**
     * What falls due by {@code now} that needs no server: jobs past their expiry (escrow back to
     * whoever funded it), stale claims, House auctions that have ended, and settled rows past the
     * audit window. Players' own auctions hold item stacks and settle on the server tick. The
     * simulation runs this every step, so a pause or a shutdown settles what fell due meanwhile.
     *
     * @return the winners of House auctions settled now
     */
    public synchronized List<UUID> settleDue(TradeEnv env, AuctionTerms terms, long now) {
        EconomyStore store = owner();
        if (store == null || EconomyHealth.frozen()) return List.of();
        boolean changed = expireJobs(store.market(), now);
        changed |= releaseStaleClaims(now);
        List<UUID> winners = settleHouseAuctions(env, terms, now);
        changed |= pruneTerminal(now);
        if (changed) { invalidateSchedule(); setDirty(); }
        return winners;
    }

    /** One House auction, running or settled; null when there is none. */
    public synchronized HouseAuction houseAuction(UUID id) { return id == null ? null : houseAuctions.get(id); }

    /**
     * The Houses' running auctions, soonest to end first. Interplanetary lots only when
     * {@code orbitOpen}. What the Economy Hub's Auctions tab lists beside the players' lots.
     */
    public synchronized List<HouseAuction> houseAuctions(boolean orbitOpen) {
        return houseAuctions.values().stream().filter(HouseAuction::open)
                .filter(value -> orbitOpen || !value.interplanetary())
                .sorted(Comparator.comparingLong(HouseAuction::endsAt)).toList();
    }

    /** {@link #houseAuctions(boolean)} as {@code viewer} may see them; null (the console) sees all. */
    public synchronized List<HouseAuction> houseAuctions(ServerPlayer viewer) {
        return houseAuctions(viewer == null || ProgressionGates.isOpen(viewer, ORBIT_GATE));
    }

    /**
     * Every House auction row still kept, running and settled (settled ones until the audit window
     * passes), in the order they were started: what an operator's report or a "recent results" list reads.
     */
    public synchronized List<HouseAuction> houseAuctionHistory() { return List.copyOf(houseAuctions.values()); }

    /** Every server-funded job row still kept, settled or not, in the order they were posted. */
    public synchronized List<EconomyJob> systemJobHistory() {
        return jobs.values().stream().filter(value -> SYSTEM_ACCOUNT.equals(value.issuer())).toList();
    }

    /** The id of the Merchant House that posted a job; null for any other job. */
    public synchronized String houseOf(UUID job) { return job == null ? null : houseJobs.get(job); }

    /**
     * The unsettled server-funded jobs of one type (null: every type), soonest to expire first:
     * the Houses' contracts and deliveries, system bounties, and jobs posted through
     * {@code DeliveryJobApi.postSystem}. {@link #houseOf} says which House posted one.
     */
    public synchronized List<EconomyJob> systemJobs(JobType type) {
        return jobs.values().stream().filter(value -> SYSTEM_ACCOUNT.equals(value.issuer()) && !terminal(value.state()))
                .filter(value -> type == null || value.type() == type)
                .sorted(Comparator.comparingLong(EconomyJob::expiresAt)).toList();
    }

    /** One job by id, settled or not; null when there is none. */
    public synchronized EconomyJob job(UUID id) { return id == null ? null : jobs.get(id); }

    /** GUI and command views only ever want live rows; settled ones are audit history. */
    public synchronized List<EconomyJob> jobs(JobType type) {
        return jobs.values().stream().filter(value -> type == null || value.type() == type)
                .filter(value -> !terminal(value.state()))
                .sorted(Comparator.comparingLong(EconomyJob::expiresAt)).toList();
    }

    /** Interplanetary lots stay hidden from a viewer who has not reached orbit; null shows all. */
    public synchronized List<AuctionRecord> auctions(ServerPlayer viewer) {
        boolean orbit = viewer == null || ProgressionGates.isOpen(viewer, ORBIT_GATE);
        List<AuctionRecord> out = new ArrayList<>();
        auctions.values().stream().filter(value -> value.state() == JobState.OPEN)
                .filter(value -> orbit || !value.interplanetary()).forEach(out::add);
        // The Houses' lots, shown as auctions like any other. Their seller is a fixed id per House,
        // which names nobody; it is what a viewer's own "is this mine" checks compare against.
        EconomyStore store = owner();
        if (store != null) for (HouseAuction house : houseAuctions(orbit)) {
            ItemStack lot = store.goods().stackOf(house.lot());
            if (!lot.isEmpty()) out.add(new AuctionRecord(house.id(), houseSeller(house.house()), lot, house.minimumBid(),
                    house.highestBid(), house.highestBidder(), house.endsAt(), house.interplanetary(), house.state()));
        }
        out.sort(Comparator.comparingLong(AuctionRecord::endsAt));
        return out;
    }

    /** The id a House's auctions show as their seller: derived from the House's id, never a player's. */
    public static UUID houseSeller(String house) {
        return UUID.nameUUIDFromBytes(("neroeconomy:house/" + house).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** The caller's own unsettled jobs, for {@code market mine}; never anyone else's. */
    public synchronized List<EconomyJob> jobsIssuedBy(UUID player) {
        return jobs.values().stream().filter(value -> !terminal(value.state()) && value.issuer().equals(player))
                .sorted(Comparator.comparingLong(EconomyJob::expiresAt)).toList();
    }

    /** The caller's own running auctions, for {@code market mine}; never anyone else's. */
    public synchronized List<AuctionRecord> auctionsBy(UUID player) {
        return auctions.values().stream().filter(value -> value.state() == JobState.OPEN && value.seller().equals(player))
                .sorted(Comparator.comparingLong(AuctionRecord::endsAt)).toList();
    }

    private int openJobCount(UUID issuer) {
        return MarketLimits.clampCount(jobs.values().stream()
                .filter(value -> !terminal(value.state()) && value.issuer().equals(issuer)).count());
    }

    private int openAuctionCount(UUID seller) {
        return MarketLimits.clampCount(auctions.values().stream()
                .filter(value -> value.state() == JobState.OPEN && value.seller().equals(seller)).count());
    }

    /**
     * Cancels a job the caller issued while nobody has claimed it; the escrowed payout becomes their
     * credit claim, recorded as an escrow refund. A claimed job has a worker relying on it and cannot
     * be withdrawn. Idempotent: the row is removed, so a second call finds nothing.
     */
    public synchronized CancelResult cancelJob(MinecraftServer server, UUID id, UUID actor) {
        if (EconomyHealth.frozen()) return CancelResult.FROZEN;
        EconomyJob job = id == null ? null : jobs.get(id);
        if (server == null || job == null || actor == null || !job.issuer().equals(actor) || terminal(job.state()))
            return CancelResult.NOT_FOUND;
        if (job.state() != JobState.OPEN) return CancelResult.NOT_CANCELLABLE;
        if (!MarketSavedData.get(server).addCreditClaim(actor, job.payout(), TransactionType.ESCROW_REFUND))
            return CancelResult.REFUND_BLOCKED;
        jobs.remove(id); bountyProgress.remove(id); invalidateSchedule(); setDirty();
        return CancelResult.CANCELLED;
    }

    /**
     * Cancels an auction the caller started while it has no bid; the lot goes to their vault.
     * Once anyone has bid, the lot is committed. Idempotent: the row is removed.
     */
    public synchronized CancelResult cancelAuction(MinecraftServer server, UUID id, UUID actor) {
        if (EconomyHealth.frozen()) return CancelResult.FROZEN;
        AuctionRecord auction = id == null ? null : auctions.get(id);
        if (server == null || auction == null || actor == null || !auction.seller().equals(actor)
                || auction.state() != JobState.OPEN) return CancelResult.NOT_FOUND;
        if (auction.bidderId() != null || auction.highestBid() > 0L) return CancelResult.NOT_CANCELLABLE;
        EconomyStore store = EconomyStore.get(server);
        if (!VaultStacks.canAccept(store, actor, List.of(auction.lot()))) return CancelResult.CLAIMS_FULL;
        VaultStacks.deliver(store, actor, auction.lot());
        auctions.remove(id); invalidateSchedule(); setDirty();
        return CancelResult.CANCELLED;
    }

    /**
     * Erasure must not destroy the counterparty's escrow. A row that merely names the erased player
     * is settled back to a neutral state — an unclaimed job, or an auction with no standing bid —
     * and a displaced bid is refunded to its owner before the row it belonged to disappears.
     *
     * <p>A bounty <em>on</em> the erased player carries their id in its target, so it cannot stay as
     * posted either: it is closed and its escrow refunded to the issuer's claim.
     *
     * <p>Escrow that was the erased player's own — the payout of a job they posted, their standing
     * bid — moves to the Reserve as an erasure sweep, in a row that names no player.
     */
    public synchronized void forgetPlayer(UUID player) {
        if (player == null) return;
        EconomyStore store = owner();
        MarketSavedData market = store == null ? null : store.market();
        EconomySavedData ledger = ledger();
        String targetKey = JobTargets.player(player);
        long swept = 0L;
        boolean changed = false;
        for (EconomyJob job : new ArrayList<>(jobs.values())) {
            boolean issuer = job.issuer().equals(player);
            boolean claimant = player.equals(job.claimantId());
            boolean targeted = job.type() == JobType.BOUNTY && job.target().equals(targetKey);
            if (!issuer && !claimant && !targeted) continue;
            if (issuer || terminal(job.state())) {
                // An unsettled job of the erased issuer still holds its payout in escrow.
                if (issuer && !terminal(job.state())) swept = EscrowMath.total(swept, job.payout());
                jobs.remove(job.id());
                bountyProgress.remove(job.id());
            }
            else if (targeted) {
                boolean refunded = SYSTEM_ACCOUNT.equals(job.issuer())
                        ? ledger != null && ledger.apply(
                                Settlement.escrowToReserve(job.payout(), TransactionType.ESCROW_REFUND)).ok()
                        : market != null && market.addCreditClaim(job.issuer(), job.payout(), TransactionType.ESCROW_REFUND);
                // If the refund cannot be booked now the escrow must not be destroyed: the bounty keeps
                // it under a target that names nobody and matches nothing, and expiry refunds it.
                if (refunded) jobs.remove(job.id());
                else jobs.put(job.id(), new EconomyJob(job.id(), job.type(), job.issuer(),
                        claimant ? "" : job.claimant(), ERASED_TARGET, job.quantity(), job.destination(),
                        job.payout(), job.expiresAt(), claimant ? JobState.OPEN : job.state(),
                        claimant ? 0L : job.claimedAt()));
                bountyProgress.remove(job.id());
            }
            else jobs.put(job.id(), job.reopened());
            changed = true;
        }
        for (AuctionRecord auction : new ArrayList<>(auctions.values())) {
            UUID standing = auction.bidderId();
            boolean seller = auction.seller().equals(player);
            boolean bidder = player.equals(standing);
            if (!seller && !bidder) continue;
            boolean running = auction.state() == JobState.OPEN;
            // A running auction's standing bid is in escrow: the erased player's own goes to the
            // Reserve, anybody else's is owed back to them.
            if (running && bidder) swept = EscrowMath.total(swept, auction.highestBid());
            if (seller) {
                if (running && market != null && standing != null && !bidder && auction.highestBid() > 0L)
                    market.addCreditClaim(standing, auction.highestBid(), TransactionType.ESCROW_REFUND);
                auctions.remove(auction.id());
            } else auctions.put(auction.id(), new AuctionRecord(auction.id(), auction.seller(), auction.lot(),
                    auction.minimumBid(), 0L, "", auction.endsAt(), auction.interplanetary(), auction.state()));
            changed = true;
        }
        for (HouseAuction auction : new ArrayList<>(houseAuctions.values())) {
            if (!player.equals(auction.bidderId())) continue;
            // A running bid was the erased player's own escrow: it goes to the Reserve and the lot
            // stays up with no bid. A settled row keeps its numbers and loses the name.
            if (auction.open()) {
                swept = EscrowMath.total(swept, auction.highestBid());
                houseAuctions.put(auction.id(), auction.withoutBid());
            } else houseAuctions.put(auction.id(), auction.forgetBidder());
            changed = true;
        }
        for (var iterator = bountyProgress.entrySet().iterator(); iterator.hasNext();) {
            Map<UUID, Integer> kills = iterator.next().getValue();
            if (kills.remove(player) == null) continue;
            if (kills.isEmpty()) iterator.remove();
            changed = true;
        }
        if (ledger != null) ledger.sweepErasedEscrow(swept);
        if (changed) { invalidateSchedule(); setDirty(); }
    }

    /** The erasure hook as it was before the sections shared one store; the server is no longer needed. */
    public synchronized void forgetPlayer(MinecraftServer server, UUID player) { forgetPlayer(player); }

    /**
     * Every credit this section records as held in escrow: the payout of each job that has not
     * settled, whoever funded it, and the standing bid of each running auction.
     */
    public synchronized long escrowObligations() {
        long total = 0L;
        for (EconomyJob job : jobs.values())
            if (!terminal(job.state())) total = EscrowMath.total(total, job.payout());
        for (AuctionRecord auction : auctions.values())
            if (auction.state() == JobState.OPEN && auction.bidderId() != null)
                total = EscrowMath.total(total, auction.highestBid());
        for (HouseAuction auction : houseAuctions.values())
            if (auction.open() && auction.bidderId() != null) total = EscrowMath.total(total, auction.highestBid());
        return total;
    }

    /** The lots of the Houses' running auctions: taken from their stock, not yet sold or returned. */
    @Override public synchronized void forEachLot(Consumer<Lot> visitor) {
        for (HouseAuction auction : houseAuctions.values()) if (auction.open()) visitor.accept(auction.lot());
    }

    @Override public synchronized boolean isEmpty() {
        return jobs.isEmpty() && auctions.isEmpty() && bountyProgress.isEmpty() && houseAuctions.isEmpty()
                && houseJobs.isEmpty() && dealDay == NO_DAY && dealSequence == 0L;
    }

    /**
     * Data-subject export: every row that involves one player, with the subject's own role in it.
     * Nobody else is identified — the other side of a job or auction is left out, and a bounty the
     * subject posted on another player is reported with that player's id removed.
     */
    public synchronized JsonObject exportFor(UUID player) {
        JsonObject out = new JsonObject();
        String targetKey = JobTargets.player(player);
        JsonArray jobRows = new JsonArray();
        for (EconomyJob job : jobs.values()) {
            boolean targeted = job.type() == JobType.BOUNTY && job.target().equals(targetKey);
            String role = job.issuer().equals(player) ? "issuer" : player.equals(job.claimantId()) ? "claimant"
                    : targeted ? "bounty_target" : null;
            if (role == null) continue;
            JsonObject row = new JsonObject();
            row.addProperty("id", job.id().toString());
            row.addProperty("type", job.type().name());
            row.addProperty("role", role);
            // A bounty on somebody else is exported without their id, even to the player who posted it.
            row.addProperty("target", JobTargets.isPlayer(job.target()) && !targeted
                    ? JobTargets.PLAYER_HIDDEN : job.target());
            row.addProperty("quantity", job.quantity());
            row.addProperty("destination", job.destination());
            row.addProperty("payout", job.payout());
            row.addProperty("expires_at", job.expiresAt());
            row.addProperty("state", job.state().name());
            // When the claim was made is the claimant's own datum, so only their export carries it.
            if ("claimant".equals(role) && job.claimedAt() > 0L) row.addProperty("claimed_at", job.claimedAt());
            jobRows.add(row);
        }
        out.add("jobs", jobRows);
        JsonArray auctionRows = new JsonArray();
        for (AuctionRecord auction : auctions.values()) {
            String role = auction.seller().equals(player) ? "seller"
                    : player.equals(auction.bidderId()) ? "bidder" : null;
            if (role == null) continue;
            JsonObject row = new JsonObject();
            row.addProperty("id", auction.id().toString());
            row.addProperty("role", role);
            row.addProperty("item", BuiltInRegistries.ITEM.getKey(auction.lot().getItem()).toString());
            row.addProperty("count", auction.lot().getCount());
            row.addProperty("minimum_bid", auction.minimumBid());
            row.addProperty("highest_bid", auction.highestBid());
            row.addProperty("ends_at", auction.endsAt());
            row.addProperty("interplanetary", auction.interplanetary());
            row.addProperty("state", auction.state().name());
            auctionRows.add(row);
        }
        out.add("auctions", auctionRows);
        JsonArray houseRows = new JsonArray();
        for (HouseAuction auction : houseAuctions.values()) {
            if (!player.equals(auction.bidderId())) continue;
            JsonObject row = new JsonObject();
            row.addProperty("id", auction.id().toString());
            row.addProperty("role", "bidder");
            row.addProperty("house", auction.house());
            row.addProperty("item", auction.lot().item());
            row.addProperty("count", auction.lot().count());
            row.addProperty("minimum_bid", auction.minimumBid());
            row.addProperty("highest_bid", auction.highestBid());
            row.addProperty("ends_at", auction.endsAt());
            row.addProperty("interplanetary", auction.interplanetary());
            row.addProperty("state", auction.state().name());
            houseRows.add(row);
        }
        out.add("house_auctions", houseRows);
        JsonArray progressRows = new JsonArray();
        bountyProgress.forEach((job, kills) -> {
            Integer count = kills.get(player);
            if (count == null) return;
            JsonObject row = new JsonObject();
            row.addProperty("job", job.toString());
            row.addProperty("kills", count);
            progressRows.add(row);
        });
        out.add("bounty_progress", progressRows);
        return out;
    }

    /** The next id of a row the simulation makes: deterministic, and never one already in use. */
    private UUID dealId() {
        UUID id;
        do id = new UUID(DEAL_ID_HIGH, ++dealSequence);
        while (jobs.containsKey(id) || auctions.containsKey(id) || houseAuctions.containsKey(id));
        return id;
    }

    private UUID freshId() {
        UUID id;
        do id = UUID.randomUUID(); while (jobs.containsKey(id) || auctions.containsKey(id) || houseAuctions.containsKey(id));
        return id;
    }

    private static boolean atDestination(ServerPlayer player, String destination) {
        String[] parts = destination.split("\\|", 4);
        if (parts.length != 4 || !player.level().dimension().identifier().toString().equals(parts[0])) return false;
        try {
            BlockPos target = new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
            return player.blockPosition().distSqr(target) <= 16D;
        } catch (NumberFormatException ignored) { return false; }
    }

    /** Whether a stack is of the item, or in the item tag, a job asks for. Untradeable items never match. */
    private static boolean matches(ItemStack stack, String target) {
        if (stack.isEmpty() || stack.is(MarketSavedData.UNTRADEABLE)) return false;
        if (JobTargets.isTag(target)) {
            Identifier tag = Identifier.tryParse(target.substring(JobTargets.TAG_PREFIX.length()));
            return tag != null && stack.is(TagKey.create(Registries.ITEM, tag));
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(target);
    }

    private record JobRow(String id, String type, String issuer, String claimant, String target, int quantity,
            String destination, long payout, long expires, String state, long claimedAt) {
        static final Codec<JobRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("id").forGetter(JobRow::id), Codec.STRING.fieldOf("type").forGetter(JobRow::type),
                Codec.STRING.fieldOf("issuer").forGetter(JobRow::issuer), Codec.STRING.optionalFieldOf("claimant", "").forGetter(JobRow::claimant),
                Codec.STRING.fieldOf("target").forGetter(JobRow::target), Codec.INT.fieldOf("quantity").forGetter(JobRow::quantity),
                Codec.STRING.optionalFieldOf("destination", "").forGetter(JobRow::destination), Codec.LONG.fieldOf("payout").forGetter(JobRow::payout),
                Codec.LONG.fieldOf("expires").forGetter(JobRow::expires), Codec.STRING.fieldOf("state").forGetter(JobRow::state),
                // Added later, so optional: an old claimed job starts its claim timeout on the next pass.
                Codec.LONG.optionalFieldOf("claimed_at", 0L).forGetter(JobRow::claimedAt))
                .apply(instance, JobRow::new));
    }
    private record AuctionRow(String id, String seller, ItemStack lot, long minimum, long highest, String bidder,
            long ends, boolean interplanetary, String state) {
        static final Codec<AuctionRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("id").forGetter(AuctionRow::id), Codec.STRING.fieldOf("seller").forGetter(AuctionRow::seller),
                StoredStacks.CODEC.fieldOf("lot").forGetter(AuctionRow::lot), Codec.LONG.fieldOf("minimum").forGetter(AuctionRow::minimum),
                Codec.LONG.optionalFieldOf("highest", 0L).forGetter(AuctionRow::highest), Codec.STRING.optionalFieldOf("bidder", "").forGetter(AuctionRow::bidder),
                Codec.LONG.fieldOf("ends").forGetter(AuctionRow::ends), Codec.BOOL.optionalFieldOf("interplanetary", false).forGetter(AuctionRow::interplanetary),
                Codec.STRING.fieldOf("state").forGetter(AuctionRow::state)).apply(instance, AuctionRow::new));
    }
    private record ProgressRow(String job, String player, int kills) {
        static final Codec<ProgressRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("job").forGetter(ProgressRow::job),
                Codec.STRING.fieldOf("player").forGetter(ProgressRow::player),
                Codec.INT.fieldOf("kills").forGetter(ProgressRow::kills)).apply(instance, ProgressRow::new));
    }
    private static Codec<ObligationSavedData> codec() {
        return RecordCodecBuilder.create(instance -> instance.group(
                JobRow.CODEC.listOf().optionalFieldOf("jobs", List.of()).forGetter(ObligationSavedData::jobRows),
                AuctionRow.CODEC.listOf().optionalFieldOf("auctions", List.of()).forGetter(ObligationSavedData::auctionRows),
                // An older save also carries the day's system-job budget here. It is read past: that
                // budget is one of the Reserve's now and lives in the ledger.
                ProgressRow.CODEC.listOf().optionalFieldOf("bounty_progress", List.of())
                        .forGetter(ObligationSavedData::progressRows),
                // Added with the Merchant Houses' auctions and jobs, so optional: an older save has none.
                HouseAuctionRow.CODEC.listOf().optionalFieldOf("house_auctions", List.of())
                        .forGetter(ObligationSavedData::houseAuctionRows),
                HouseJobRow.CODEC.listOf().optionalFieldOf("house_jobs", List.of())
                        .forGetter(ObligationSavedData::houseJobRows),
                DealDayRow.CODEC.optionalFieldOf("deal_day", DealDayRow.NONE).forGetter(ObligationSavedData::dealDayRow))
                .apply(instance, ObligationSavedData::fromRows));
    }
    private record HouseAuctionRow(String id, String house, String item, int count, long minimum, long highest,
            String bidder, long ends, boolean interplanetary, String state) {
        static final Codec<HouseAuctionRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("id").forGetter(HouseAuctionRow::id),
                Codec.STRING.fieldOf("house").forGetter(HouseAuctionRow::house),
                Codec.STRING.fieldOf("item").forGetter(HouseAuctionRow::item),
                Codec.INT.fieldOf("count").forGetter(HouseAuctionRow::count),
                Codec.LONG.fieldOf("minimum").forGetter(HouseAuctionRow::minimum),
                Codec.LONG.optionalFieldOf("highest", 0L).forGetter(HouseAuctionRow::highest),
                Codec.STRING.optionalFieldOf("bidder", "").forGetter(HouseAuctionRow::bidder),
                Codec.LONG.fieldOf("ends").forGetter(HouseAuctionRow::ends),
                Codec.BOOL.optionalFieldOf("interplanetary", false).forGetter(HouseAuctionRow::interplanetary),
                Codec.STRING.fieldOf("state").forGetter(HouseAuctionRow::state)).apply(instance, HouseAuctionRow::new));
    }
    private record HouseJobRow(String job, String house) {
        static final Codec<HouseJobRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("job").forGetter(HouseJobRow::job),
                Codec.STRING.fieldOf("house").forGetter(HouseJobRow::house)).apply(instance, HouseJobRow::new));
    }
    private record DealDayRow(long day, int auctions, int bounties, long sequence) {
        static final DealDayRow NONE = new DealDayRow(NO_DAY, 0, 0, 0L);
        static final Codec<DealDayRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.LONG.optionalFieldOf("day", NO_DAY).forGetter(DealDayRow::day),
                Codec.INT.optionalFieldOf("auctions", 0).forGetter(DealDayRow::auctions),
                Codec.INT.optionalFieldOf("bounties", 0).forGetter(DealDayRow::bounties),
                Codec.LONG.optionalFieldOf("sequence", 0L).forGetter(DealDayRow::sequence)).apply(instance, DealDayRow::new));
    }
    private List<HouseAuctionRow> houseAuctionRows() {
        return houseAuctions.values().stream().map(value -> new HouseAuctionRow(value.id().toString(), value.house(),
                value.lot().item(), value.lot().count(), value.minimumBid(), value.highestBid(), value.highestBidder(),
                value.endsAt(), value.interplanetary(), value.state().name())).toList();
    }
    private List<HouseJobRow> houseJobRows() {
        List<HouseJobRow> rows = new ArrayList<>();
        houseJobs.forEach((job, house) -> rows.add(new HouseJobRow(job.toString(), house)));
        return rows;
    }
    private DealDayRow dealDayRow() { return new DealDayRow(dealDay, npcAuctionsToday, bountiesToday, dealSequence); }
    private List<JobRow> jobRows() { return jobs.values().stream().map(value -> new JobRow(value.id().toString(),
            value.type().name(), value.issuer().toString(), value.claimant(), value.target(), value.quantity(),
            value.destination(), value.payout(), value.expiresAt(), value.state().name(), value.claimedAt())).toList(); }
    private List<AuctionRow> auctionRows() { return auctions.values().stream().map(value -> new AuctionRow(
            value.id().toString(), value.seller().toString(), value.lot().copy(), value.minimumBid(), value.highestBid(),
            value.highestBidder(), value.endsAt(), value.interplanetary(), value.state().name())).toList(); }
    private List<ProgressRow> progressRows() {
        List<ProgressRow> rows = new ArrayList<>();
        bountyProgress.forEach((job, kills) -> kills.forEach((player, count) ->
                rows.add(new ProgressRow(job.toString(), player.toString(), count))));
        return rows;
    }
    private static ObligationSavedData fromRows(List<JobRow> jobs, List<AuctionRow> auctions,
            List<ProgressRow> progress, List<HouseAuctionRow> houseAuctions, List<HouseJobRow> houseJobs,
            DealDayRow dealDay) {
        ObligationSavedData data = new ObligationSavedData();
        for (HouseAuctionRow row : houseAuctions) try {
            if (data.houseAuctions.size() >= MAX_HOUSE_AUCTIONS || !Lot.valid(row.item(), row.count(), Lot.PRISTINE))
                continue;
            HouseAuction value = new HouseAuction(UUID.fromString(row.id()), row.house(), Lot.of(row.item(), row.count()),
                    row.minimum(), row.highest(), row.bidder(), row.ends(), row.interplanetary(), JobState.valueOf(row.state()));
            data.houseAuctions.put(value.id(), value);
        } catch (IllegalArgumentException ignored) { }
        data.dealDay = dealDay.day();
        data.npcAuctionsToday = Math.max(0, dealDay.auctions());
        data.bountiesToday = Math.max(0, dealDay.bounties());
        data.dealSequence = Math.max(0L, dealDay.sequence());
        for (JobRow row : jobs) try {
            EconomyJob value = new EconomyJob(UUID.fromString(row.id()), JobType.valueOf(row.type()),
                    UUID.fromString(row.issuer()), row.claimant(), row.target(), row.quantity(), row.destination(),
                    row.payout(), row.expires(), JobState.valueOf(row.state()), row.claimedAt());
            data.jobs.put(value.id(), value);
        } catch (IllegalArgumentException ignored) { }
        for (AuctionRow row : auctions) try {
            AuctionRecord value = new AuctionRecord(UUID.fromString(row.id()), UUID.fromString(row.seller()), row.lot(),
                    row.minimum(), row.highest(), row.bidder(), row.ends(), row.interplanetary(), JobState.valueOf(row.state()));
            data.auctions.put(value.id(), value);
        } catch (IllegalArgumentException ignored) { }
        for (ProgressRow row : progress) try {
            UUID job = UUID.fromString(row.job());
            if (row.kills() > 0 && data.jobs.containsKey(job)) data.bountyProgress
                    .computeIfAbsent(job, ignored -> new LinkedHashMap<>())
                    .put(UUID.fromString(row.player()), row.kills());
        } catch (IllegalArgumentException ignored) { }
        for (HouseJobRow row : houseJobs) try {
            UUID job = UUID.fromString(row.job());
            if (data.jobs.containsKey(job) && row.house() != null && !row.house().isBlank()) data.houseJobs.put(job, row.house());
        } catch (IllegalArgumentException ignored) { }
        return data;
    }
}
