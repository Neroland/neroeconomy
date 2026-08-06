package za.co.neroland.neroeconomy.obligation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import za.co.neroland.nerolandcore.progression.ProgressionGates;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.data.SavedDataRecovery;
import za.co.neroland.neroeconomy.economy.TransactionType;
import za.co.neroland.neroeconomy.market.MarketSavedData;
import za.co.neroland.neroeconomy.link.EconomyAlerts;
import za.co.neroland.nerolandcore.link.LinkAlert;

/** Durable jobs, bounty payout escrow, and timed auction escrow. */
public final class ObligationSavedData extends SavedData {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "obligations");
    public static final SavedDataType<ObligationSavedData> TYPE =
            new SavedDataType<>(ID, ObligationSavedData::new, codec(), null);
    private static final Identifier ORBIT_GATE =
            Identifier.fromNamespaceAndPath("nerolandcore", "reached_orbit");
    /** Issuer of server-funded jobs; owns no wallet, so its "escrow" is minted at settlement. */
    public static final UUID SYSTEM_ACCOUNT = new UUID(0L, 0L);
    private static final long MILLIS_PER_DAY = 86_400_000L;
    private static final long SCHEDULE_INTERVAL_MILLIS = 3_600_000L;
    private static final long RETRY_INTERVAL_MILLIS = 60_000L;

    private final Map<UUID, EconomyJob> jobs = new LinkedHashMap<>();
    private final Map<UUID, AuctionRecord> auctions = new LinkedHashMap<>();
    /** Bounty id to per-claimant kill tally; a bounty only pays once its quantity is proven. */
    private final Map<UUID, Map<UUID, Integer>> bountyProgress = new LinkedHashMap<>();
    /** Earliest wall-clock millisecond at which {@link #tick} can have anything to do. */
    private long nextEventAt;

    public ObligationSavedData() { }
    public static ObligationSavedData get(MinecraftServer server) {
        return SavedDataRecovery.get(server.overworld(), TYPE, ObligationSavedData::new, ID.toString());
    }

    public synchronized EconomyJob postJob(ServerPlayer issuer, JobType type, String target, int quantity,
            String destination, long payout, long expiresAt) {
        if (issuer == null || type == null || target == null || target.isBlank() || quantity < 1
                || destination == null || payout < 1L || expiresAt <= System.currentTimeMillis()) return null;
        if (!NeroEconomyCommon.CURRENCY_PROVIDER.withdrawTyped(issuer.getUUID(), NeroEconomyCommon.CREDITS,
                payout, TransactionType.MARKET_ESCROW)) return null;
        UUID id = freshId();
        EconomyJob job = new EconomyJob(id, type, issuer.getUUID(), "", target, quantity, destination,
                payout, expiresAt, JobState.OPEN);
        jobs.put(id, job); invalidateSchedule(); setDirty(); return job;
    }

    /**
     * Server-funded job with no player issuer, posted through {@code DeliveryJobApi.postSystem}.
     * Nothing is escrowed: the payout is minted for the worker at settlement, so expiry refunds
     * nothing and fulfilled goods are consumed rather than claimed (the system account collects
     * from no terminal).
     */
    public synchronized EconomyJob postSystemJob(JobType type, String target, int quantity,
            String destination, long payout, long expiresAt) {
        if (type == null || target == null || target.isBlank() || quantity < 1
                || destination == null || payout < 1L || expiresAt <= System.currentTimeMillis()) return null;
        UUID id = freshId();
        EconomyJob job = new EconomyJob(id, type, SYSTEM_ACCOUNT, "", target, quantity, destination,
                payout, expiresAt, JobState.OPEN);
        jobs.put(id, job); invalidateSchedule(); setDirty(); return job;
    }

    public synchronized boolean claim(UUID id, UUID player) {
        EconomyJob job = jobs.get(id);
        if (job == null || player == null || job.state() != JobState.OPEN || job.issuer().equals(player)
                || job.expiresAt() <= System.currentTimeMillis()) return false;
        jobs.put(id, job.claimedBy(player)); setDirty(); return true;
    }

    public synchronized boolean fulfill(ServerPlayer player, UUID id) {
        EconomyJob job = jobs.get(id);
        if (player == null || job == null || job.state() != JobState.CLAIMED
                || !player.getUUID().equals(job.claimantId()) || job.type() == JobType.BOUNTY) return false;
        if (job.type() == JobType.DELIVERY && !atDestination(player, job.destination())) return false;
        MarketSavedData market = MarketSavedData.get(player.level().getServer());
        // Refuse before the inventory is touched when the payout could not be booked at all.
        if (!market.canAcceptCreditClaim(player.getUUID(), job.payout())) return false;
        List<ItemStack> removed = removeMatching(player, job.target(), job.quantity());
        if (removed.isEmpty()) return false;
        if (!market.addCreditClaim(player.getUUID(), job.payout())) {
            removed.forEach(item -> market.addItemClaim(player.getUUID(), item));
            return false;
        }
        // System jobs consume the goods; a claim for the system account could never be collected.
        if (!SYSTEM_ACCOUNT.equals(job.issuer())) removed.forEach(item -> market.addItemClaim(job.issuer(), item));
        EconomyAlerts.raise(player.level().getServer(), job.issuer(), "contract_fulfilled",
                "An economy job was fulfilled.", LinkAlert.Severity.INFO);
        jobs.put(id, job.withState(JobState.FULFILLED)); invalidateSchedule(); setDirty(); return true;
    }

    /** Server-verified kill hook; entity ids are strings, so optional creature mods stay soft. */
    public synchronized int onKill(ServerPlayer killer, Entity victim) {
        if (killer == null || victim == null) return 0;
        String entityId = BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()).toString();
        String playerTarget = victim instanceof ServerPlayer target ? "player:" + target.getUUID() : "";
        MarketSavedData market = MarketSavedData.get(killer.level().getServer());
        int completed = 0;
        boolean changed = false;
        for (EconomyJob job : new ArrayList<>(jobs.values())) {
            if (job.type() != JobType.BOUNTY || terminal(job.state())
                    || job.issuer().equals(killer.getUUID()) || job.expiresAt() <= System.currentTimeMillis()) continue;
            if (!job.target().equals(entityId) && !job.target().equals(playerTarget)) continue;
            UUID claimant = job.claimantId();
            if (claimant != null && !claimant.equals(killer.getUUID())) continue;
            // A bounty settles at its full quantity, not on the first kill.
            int progress = bountyProgress.computeIfAbsent(job.id(), ignored -> new LinkedHashMap<>())
                    .merge(killer.getUUID(), 1, Integer::sum);
            changed = true;
            if (progress < job.quantity()) continue;
            if (!market.addCreditClaim(killer.getUUID(), job.payout())) continue;
            bountyProgress.remove(job.id());
            jobs.put(job.id(), new EconomyJob(job.id(), job.type(), job.issuer(), killer.getUUID().toString(),
                    job.target(), job.quantity(), job.destination(), job.payout(), job.expiresAt(), JobState.FULFILLED));
            completed++;
        }
        if (changed) { invalidateSchedule(); setDirty(); }
        return completed;
    }

    /** Kills already credited toward one bounty by one claimant; exposed for tests and GUIs. */
    public synchronized int bountyProgress(UUID jobId, UUID claimant) {
        Map<UUID, Integer> kills = bountyProgress.get(jobId);
        return kills == null ? 0 : kills.getOrDefault(claimant, 0);
    }

    public synchronized AuctionRecord createAuction(ServerPlayer seller, int quantity, long minimumBid,
            long endsAt, boolean requestedInterplanetary) {
        if (seller == null || quantity < 1 || minimumBid < 1L || endsAt <= System.currentTimeMillis()) return null;
        ItemStack held = seller.getMainHandItem();
        if (held.isEmpty() || held.getCount() < quantity) return null;
        boolean orbit = requestedInterplanetary && ProgressionGates.isOpen(seller, ORBIT_GATE);
        UUID id = freshId();
        AuctionRecord auction = new AuctionRecord(id, seller.getUUID(), held.copyWithCount(quantity), minimumBid,
                0L, "", endsAt, orbit, JobState.OPEN);
        auctions.put(id, auction); held.shrink(quantity); invalidateSchedule(); setDirty(); return auction;
    }

    public synchronized boolean bid(ServerPlayer bidder, UUID id, long amount) {
        AuctionRecord auction = auctions.get(id);
        long now = System.currentTimeMillis();
        if (bidder == null || auction == null || auction.state() != JobState.OPEN || auction.endsAt() <= now
                || auction.seller().equals(bidder.getUUID()) || amount < auction.minimumBid()
                || amount <= auction.highestBid()) return false;
        // The orbit gate is recorded on the lot, so enforce it on the way in as well as on display.
        if (auction.interplanetary() && !ProgressionGates.isOpen(bidder, ORBIT_GATE)) return false;
        UUID previous = auction.bidderId();
        boolean displaces = previous != null && !previous.equals(bidder.getUUID());
        MarketSavedData market = MarketSavedData.get(bidder.level().getServer());
        // Never debit the new bidder unless the displaced bid can actually be booked back.
        if (displaces && !market.canAcceptCreditClaim(previous, auction.highestBid())) return false;
        long debit = previous != null && previous.equals(bidder.getUUID()) ? amount - auction.highestBid() : amount;
        if (!NeroEconomyCommon.CURRENCY_PROVIDER.withdrawTyped(bidder.getUUID(), NeroEconomyCommon.CREDITS,
                debit, TransactionType.AUCTION_BID)) return false;
        if (displaces) {
            market.addCreditClaim(previous, auction.highestBid());
            EconomyAlerts.raise(bidder.level().getServer(), previous, "outbid", "You were outbid.",
                    LinkAlert.Severity.WARN);
        }
        long window = EconomyConfig.AUCTION_ANTI_SNIPE_SECONDS.get() * 1000L;
        long end = window > 0L && auction.endsAt() - now <= window ? now + window : auction.endsAt();
        auctions.put(id, new AuctionRecord(id, auction.seller(), auction.lot(), auction.minimumBid(), amount,
                bidder.getUUID().toString(), end, auction.interplanetary(), JobState.OPEN));
        invalidateSchedule(); setDirty(); return true;
    }

    /**
     * Runs every server tick, so it must cost nothing when nothing is due: a watermark over the
     * earliest expiry, auction end, and prune deadline short-circuits the whole pass.
     */
    public synchronized void tick(MinecraftServer server, long now) {
        if (now < nextEventAt) return;
        MarketSavedData market = MarketSavedData.get(server);
        boolean changed = expireJobs(market, now);
        changed |= settleAuctions(server, market, now);
        changed |= pruneTerminal(now);
        nextEventAt = nextEventAt(now);
        if (changed) setDirty();
    }

    private boolean expireJobs(MarketSavedData market, long now) {
        boolean changed = false;
        for (EconomyJob job : new ArrayList<>(jobs.values())) {
            if (terminal(job.state()) || job.expiresAt() > now) continue;
            // The issuer's escrow has to land somewhere before the row can be closed off —
            // except for system jobs, which never escrowed anything to refund.
            if (!SYSTEM_ACCOUNT.equals(job.issuer()) && !market.addCreditClaim(job.issuer(), job.payout())) continue;
            bountyProgress.remove(job.id());
            jobs.put(job.id(), job.withState(JobState.EXPIRED));
            changed = true;
        }
        return changed;
    }

    private boolean settleAuctions(MinecraftServer server, MarketSavedData market, long now) {
        boolean changed = false;
        for (AuctionRecord auction : new ArrayList<>(auctions.values())) {
            if (auction.state() != JobState.OPEN || auction.endsAt() > now) continue;
            UUID winner = auction.bidderId();
            if (winner == null) market.addItemClaim(auction.seller(), auction.lot());
            else {
                // Proceeds first: the lot only changes hands once the seller can actually be paid.
                if (!market.addCreditClaim(auction.seller(), auction.highestBid())) continue;
                market.addItemClaim(winner, auction.lot());
                EconomyAlerts.raise(server, winner, "auction_won", "You won an auction.", LinkAlert.Severity.INFO);
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
        for (EconomyJob job : jobs.values())
            if (!terminal(job.state()) && job.expiresAt() < next) next = job.expiresAt();
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
    public synchronized List<AuctionRecord> auctions() { return auctions(null); }

    /** Interplanetary lots stay hidden from a viewer who has not reached orbit; null shows all. */
    public synchronized List<AuctionRecord> auctions(ServerPlayer viewer) {
        boolean orbit = viewer == null || ProgressionGates.isOpen(viewer, ORBIT_GATE);
        return auctions.values().stream().filter(value -> value.state() == JobState.OPEN)
                .filter(value -> orbit || !value.interplanetary())
                .sorted(Comparator.comparingLong(AuctionRecord::endsAt)).toList();
    }

    /**
     * Erasure must not destroy the counterparty's escrow. A row that merely names the erased player
     * is settled back to a neutral state — an unclaimed job, or an auction with no standing bid —
     * and a displaced bid is refunded to its owner before the row it belonged to disappears.
     */
    public synchronized void forgetPlayer(MinecraftServer server, UUID player) {
        if (player == null) return;
        MarketSavedData market = server == null ? null : MarketSavedData.get(server);
        boolean changed = false;
        for (EconomyJob job : new ArrayList<>(jobs.values())) {
            boolean issuer = job.issuer().equals(player);
            if (!issuer && !player.equals(job.claimantId())) continue;
            // Escrow on an erased issuer's job belonged to them; only the claim link is severed.
            if (issuer || terminal(job.state())) { jobs.remove(job.id()); bountyProgress.remove(job.id()); }
            else jobs.put(job.id(), new EconomyJob(job.id(), job.type(), job.issuer(), "", job.target(),
                    job.quantity(), job.destination(), job.payout(), job.expiresAt(), JobState.OPEN));
            changed = true;
        }
        for (AuctionRecord auction : new ArrayList<>(auctions.values())) {
            UUID standing = auction.bidderId();
            boolean seller = auction.seller().equals(player);
            if (!seller && !player.equals(standing)) continue;
            if (seller) {
                if (market != null && standing != null && !standing.equals(player) && auction.highestBid() > 0L)
                    market.addCreditClaim(standing, auction.highestBid());
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
        if (changed) { invalidateSchedule(); setDirty(); }
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

    private static List<ItemStack> removeMatching(ServerPlayer player, String target, int quantity) {
        int available = 0;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) if (matches(stack, target)) available += stack.getCount();
        if (available < quantity) return List.of();
        int remaining = quantity;
        List<ItemStack> removed = new ArrayList<>();
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (!matches(stack, target) || remaining == 0) continue;
            int take = Math.min(remaining, stack.getCount());
            removed.add(stack.copyWithCount(take)); stack.shrink(take); remaining -= take;
        }
        return removed;
    }
    private static boolean matches(ItemStack stack, String target) {
        if (stack.isEmpty()) return false;
        if (target.startsWith("#")) try {
            return stack.is(TagKey.create(Registries.ITEM, Identifier.parse(target.substring(1))));
        } catch (IllegalArgumentException ignored) { return false; }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(target);
    }

    private record JobRow(String id, String type, String issuer, String claimant, String target, int quantity,
            String destination, long payout, long expires, String state) {
        static final Codec<JobRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("id").forGetter(JobRow::id), Codec.STRING.fieldOf("type").forGetter(JobRow::type),
                Codec.STRING.fieldOf("issuer").forGetter(JobRow::issuer), Codec.STRING.optionalFieldOf("claimant", "").forGetter(JobRow::claimant),
                Codec.STRING.fieldOf("target").forGetter(JobRow::target), Codec.INT.fieldOf("quantity").forGetter(JobRow::quantity),
                Codec.STRING.optionalFieldOf("destination", "").forGetter(JobRow::destination), Codec.LONG.fieldOf("payout").forGetter(JobRow::payout),
                Codec.LONG.fieldOf("expires").forGetter(JobRow::expires), Codec.STRING.fieldOf("state").forGetter(JobRow::state))
                .apply(instance, JobRow::new));
    }
    private record AuctionRow(String id, String seller, ItemStack lot, long minimum, long highest, String bidder,
            long ends, boolean interplanetary, String state) {
        static final Codec<AuctionRow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("id").forGetter(AuctionRow::id), Codec.STRING.fieldOf("seller").forGetter(AuctionRow::seller),
                ItemStack.CODEC.fieldOf("lot").forGetter(AuctionRow::lot), Codec.LONG.fieldOf("minimum").forGetter(AuctionRow::minimum),
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
                ProgressRow.CODEC.listOf().optionalFieldOf("bounty_progress", List.of())
                        .forGetter(ObligationSavedData::progressRows))
                .apply(instance, ObligationSavedData::fromRows));
    }
    private List<JobRow> jobRows() { return jobs.values().stream().map(value -> new JobRow(value.id().toString(),
            value.type().name(), value.issuer().toString(), value.claimant(), value.target(), value.quantity(),
            value.destination(), value.payout(), value.expiresAt(), value.state().name())).toList(); }
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
                    row.payout(), row.expires(), JobState.valueOf(row.state())); data.jobs.put(value.id(), value);
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
