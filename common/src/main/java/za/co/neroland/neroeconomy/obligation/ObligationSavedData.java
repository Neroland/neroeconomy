package za.co.neroland.neroeconomy.obligation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import za.co.neroland.nerolandcore.progression.ProgressionGates;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.economy.EconomySection;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.economy.EscrowHolder;
import za.co.neroland.neroeconomy.economy.EscrowMath;
import za.co.neroland.neroeconomy.economy.LedgerResult;
import za.co.neroland.neroeconomy.economy.TransactionType;
import za.co.neroland.neroeconomy.market.CancelResult;
import za.co.neroland.neroeconomy.market.MarketLimits;
import za.co.neroland.neroeconomy.goods.GoodsSource;
import za.co.neroland.neroeconomy.market.MarketSavedData;
import za.co.neroland.neroeconomy.market.Settlement;
import za.co.neroland.neroeconomy.market.StoredStacks;
import za.co.neroland.neroeconomy.link.EconomyAlerts;
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
 */
public final class ObligationSavedData extends EconomySection implements EscrowHolder {
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

    private final Map<UUID, EconomyJob> jobs = new LinkedHashMap<>();
    private final Map<UUID, AuctionRecord> auctions = new LinkedHashMap<>();
    /** Bounty id to per-claimant kill tally; a bounty only pays once its quantity is proven. */
    private final Map<UUID, Map<UUID, Integer>> bountyProgress = new LinkedHashMap<>();
    /** Earliest economy-clock millisecond at which {@link #tick} can have anything to do. */
    private long nextEventAt;

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
        if (EconomyHealth.frozen()) return null;
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
        if (EconomyHealth.frozen()) return null;
        if (type == null || quantity < 1 || destination == null || payout < 1L || expiresAt <= now
                || JobTargets.isPlayer(target) || !targetUsable(type, target)) return null;
        if (!EconomyConfig.SYSTEM_JOBS_ENABLED.get()) {
            NeroEconomyCommon.LOGGER.debug("[NeroEconomy] System job refused: system jobs are disabled.");
            return null;
        }
        LedgerResult escrowed = NeroEconomyCommon.CURRENCY_PROVIDER.execute(Settlement.systemJobEscrow(payout));
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

    public synchronized boolean claim(UUID id, UUID player) {
        EconomyJob job = jobs.get(id);
        long now = now();
        if (EconomyHealth.frozen()) return false;
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
        // The goods have left a live inventory for a vault; get the store to disk promptly.
        NeroEconomyCommon.CURRENCY_PROVIDER.requestSave();
        return FulfillResult.SUCCESS;
    }

    /** Server-verified kill hook; entity ids are strings, so optional creature mods stay soft. */
    public synchronized int onKill(ServerPlayer killer, Entity victim) {
        if (killer == null || victim == null || EconomyHealth.frozen()) return 0;
        String entityId = BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()).toString();
        String playerTarget = victim instanceof ServerPlayer target ? JobTargets.player(target.getUUID()) : "";
        MarketSavedData market = MarketSavedData.get(killer.level().getServer());
        int completed = 0;
        boolean changed = false;
        long now = now();
        for (EconomyJob job : new ArrayList<>(jobs.values())) {
            if (job.type() != JobType.BOUNTY || terminal(job.state())
                    || job.issuer().equals(killer.getUUID()) || job.expiresAt() <= now) continue;
            if (!job.target().equals(entityId) && !job.target().equals(playerTarget)) continue;
            UUID claimant = job.claimantId();
            if (claimant != null && !claimant.equals(killer.getUUID())) continue;
            // A bounty settles at its full quantity, not on the first kill.
            int progress = bountyProgress.computeIfAbsent(job.id(), ignored -> new LinkedHashMap<>())
                    .merge(killer.getUUID(), 1, Integer::sum);
            changed = true;
            if (progress < job.quantity()) continue;
            if (!market.addCreditClaim(killer.getUUID(), job.payout(), TransactionType.BOUNTY_PAYOUT)) continue;
            bountyProgress.remove(job.id());
            jobs.put(job.id(), job.claimedBy(killer.getUUID(), job.claimedAt()).withState(JobState.FULFILLED));
            completed++;
        }
        if (changed) { invalidateSchedule(); setDirty(); }
        return completed;
    }

    public synchronized AuctionRecord createAuction(ServerPlayer seller, int quantity, long minimumBid,
            long endsAt, boolean requestedInterplanetary) {
        if (EconomyHealth.frozen()) return null;
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
        AuctionRecord auction = auctions.get(id);
        long now = now();
        if (EconomyHealth.frozen()) return false;
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
    private static boolean releasePayout(MarketSavedData market, EconomyJob job) {
        if (!SYSTEM_ACCOUNT.equals(job.issuer()))
            return market != null && market.addCreditClaim(job.issuer(), job.payout(), TransactionType.ESCROW_REFUND);
        return NeroEconomyCommon.CURRENCY_PROVIDER.execute(
                Settlement.escrowToReserve(job.payout(), TransactionType.ESCROW_REFUND)).ok();
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
            }
            auctions.put(auction.id(), new AuctionRecord(auction.id(), auction.seller(), auction.lot(),
                    auction.minimumBid(), auction.highestBid(), auction.highestBidder(), auction.endsAt(),
                    auction.interplanetary(), JobState.FULFILLED)); changed = true;
        }
        return changed;
    }

    /** Settled rows are dead weight once the audit window has passed; drop them with the ledger. */
    private boolean pruneTerminal(long now) {
        int days = EconomyConfig.TRANSACTION_RETENTION_DAYS.get();
        long cutoff = days <= 0 ? Long.MAX_VALUE : now - days * MILLIS_PER_DAY;
        boolean changed = jobs.entrySet().removeIf(entry -> terminal(entry.getValue().state())
                && entry.getValue().expiresAt() < cutoff);
        changed |= auctions.entrySet().removeIf(entry -> terminal(entry.getValue().state())
                && entry.getValue().endsAt() < cutoff);
        changed |= bountyProgress.keySet().removeIf(id -> !jobs.containsKey(id));
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
        // A due row that could not settle this pass is retried, but not once per tick.
        return next <= now ? now + RETRY_INTERVAL_MILLIS : next;
    }

    private void invalidateSchedule() { nextEventAt = 0L; }

    private static boolean terminal(JobState state) {
        return state == JobState.FULFILLED || state == JobState.EXPIRED;
    }

    /** GUI and command views only ever want live rows; settled ones are audit history. */
    public synchronized List<EconomyJob> jobs(JobType type) {
        return jobs.values().stream().filter(value -> type == null || value.type() == type)
                .filter(value -> !terminal(value.state()))
                .sorted(Comparator.comparingLong(EconomyJob::expiresAt)).toList();
    }

    /** Interplanetary lots stay hidden from a viewer who has not reached orbit; null shows all. */
    public synchronized List<AuctionRecord> auctions(ServerPlayer viewer) {
        boolean orbit = viewer == null || ProgressionGates.isOpen(viewer, ORBIT_GATE);
        return auctions.values().stream().filter(value -> value.state() == JobState.OPEN)
                .filter(value -> orbit || !value.interplanetary())
                .sorted(Comparator.comparingLong(AuctionRecord::endsAt)).toList();
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
        return total;
    }

    @Override public synchronized boolean isEmpty() {
        return jobs.isEmpty() && auctions.isEmpty() && bountyProgress.isEmpty();
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

    private UUID freshId() { UUID id; do id = UUID.randomUUID(); while (jobs.containsKey(id) || auctions.containsKey(id)); return id; }

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
                        .forGetter(ObligationSavedData::progressRows))
                .apply(instance, ObligationSavedData::fromRows));
    }
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
            List<ProgressRow> progress) {
        ObligationSavedData data = new ObligationSavedData();
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
        return data;
    }
}
