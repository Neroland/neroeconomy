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

import za.co.neroland.nerolandcore.data.SavedDataRecovery;
import za.co.neroland.nerolandcore.progression.ProgressionGates;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.economy.TransactionType;
import za.co.neroland.neroeconomy.market.MarketSavedData;
import za.co.neroland.neroeconomy.link.EconomyAlerts;
import za.co.neroland.nerolandcore.link.LinkAlert;

/** Durable jobs, bounty payout escrow, and timed auction escrow. */
public final class ObligationSavedData extends SavedData {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(NeroEconomyCommon.MOD_ID, "obligations");
    public static final SavedDataType<ObligationSavedData> TYPE =
            new SavedDataType<>(ID, ObligationSavedData::new, codec(), null);
    private final Map<UUID, EconomyJob> jobs = new LinkedHashMap<>();
    private final Map<UUID, AuctionRecord> auctions = new LinkedHashMap<>();

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
        jobs.put(id, job); setDirty(); return job;
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
        List<ItemStack> removed = removeMatching(player, job.target(), job.quantity());
        if (removed.isEmpty()) return false;
        MarketSavedData market = MarketSavedData.get(player.level().getServer());
        removed.forEach(item -> market.addItemClaim(job.issuer(), item));
        market.addCreditClaim(player.getUUID(), job.payout());
        EconomyAlerts.raise(player.level().getServer(), job.issuer(), "contract_fulfilled",
                "An economy job was fulfilled.", LinkAlert.Severity.INFO);
        jobs.put(id, job.withState(JobState.FULFILLED)); setDirty(); return true;
    }

    /** Server-verified kill hook; entity ids are strings, so optional creature mods stay soft. */
    public synchronized int onKill(ServerPlayer killer, Entity victim) {
        if (killer == null || victim == null) return 0;
        String entityId = BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()).toString();
        String playerTarget = victim instanceof ServerPlayer target ? "player:" + target.getUUID() : "";
        int completed = 0;
        for (EconomyJob job : new ArrayList<>(jobs.values())) {
            if (job.type() != JobType.BOUNTY || job.state() == JobState.FULFILLED || job.state() == JobState.EXPIRED
                    || job.issuer().equals(killer.getUUID()) || job.expiresAt() <= System.currentTimeMillis()) continue;
            if (!job.target().equals(entityId) && !job.target().equals(playerTarget)) continue;
            UUID claimant = job.claimantId();
            if (claimant != null && !claimant.equals(killer.getUUID())) continue;
            MarketSavedData.get(killer.level().getServer()).addCreditClaim(killer.getUUID(), job.payout());
            jobs.put(job.id(), new EconomyJob(job.id(), job.type(), job.issuer(), killer.getUUID().toString(),
                    job.target(), job.quantity(), job.destination(), job.payout(), job.expiresAt(), JobState.FULFILLED));
            completed++;
        }
        if (completed > 0) setDirty();
        return completed;
    }

    public synchronized AuctionRecord createAuction(ServerPlayer seller, int quantity, long minimumBid,
            long endsAt, boolean requestedInterplanetary) {
        if (seller == null || quantity < 1 || minimumBid < 1L || endsAt <= System.currentTimeMillis()) return null;
        ItemStack held = seller.getMainHandItem();
        if (held.isEmpty() || held.getCount() < quantity) return null;
        boolean orbit = requestedInterplanetary && ProgressionGates.isOpen(seller,
                Identifier.fromNamespaceAndPath("nerolandcore", "reached_orbit"));
        UUID id = freshId();
        AuctionRecord auction = new AuctionRecord(id, seller.getUUID(), held.copyWithCount(quantity), minimumBid,
                0L, "", endsAt, orbit, JobState.OPEN);
        auctions.put(id, auction); held.shrink(quantity); setDirty(); return auction;
    }

    public synchronized boolean bid(ServerPlayer bidder, UUID id, long amount) {
        AuctionRecord auction = auctions.get(id);
        long now = System.currentTimeMillis();
        if (bidder == null || auction == null || auction.state() != JobState.OPEN || auction.endsAt() <= now
                || auction.seller().equals(bidder.getUUID()) || amount < auction.minimumBid()
                || amount <= auction.highestBid()) return false;
        UUID previous = auction.bidderId();
        long debit = previous != null && previous.equals(bidder.getUUID()) ? amount - auction.highestBid() : amount;
        if (!NeroEconomyCommon.CURRENCY_PROVIDER.withdrawTyped(bidder.getUUID(), NeroEconomyCommon.CREDITS,
                debit, TransactionType.AUCTION_BID)) return false;
        if (previous != null && !previous.equals(bidder.getUUID()))
            MarketSavedData.get(bidder.level().getServer()).addCreditClaim(previous, auction.highestBid());
        if (previous != null && !previous.equals(bidder.getUUID())) EconomyAlerts.raise(
                bidder.level().getServer(), previous, "outbid", "You were outbid.", LinkAlert.Severity.WARN);
        long window = EconomyConfig.AUCTION_ANTI_SNIPE_SECONDS.get() * 1000L;
        long end = window > 0L && auction.endsAt() - now <= window ? now + window : auction.endsAt();
        auctions.put(id, new AuctionRecord(id, auction.seller(), auction.lot(), auction.minimumBid(), amount,
                bidder.getUUID().toString(), end, auction.interplanetary(), JobState.OPEN));
        setDirty(); return true;
    }

    public synchronized void tick(MinecraftServer server, long now) {
        MarketSavedData market = MarketSavedData.get(server);
        boolean changed = false;
        for (var entry : new ArrayList<>(jobs.entrySet())) {
            EconomyJob job = entry.getValue();
            if ((job.state() == JobState.OPEN || job.state() == JobState.CLAIMED) && job.expiresAt() <= now) {
                market.addCreditClaim(job.issuer(), job.payout());
                jobs.put(job.id(), job.withState(JobState.EXPIRED)); changed = true;
            }
        }
        for (var entry : new ArrayList<>(auctions.entrySet())) {
            AuctionRecord auction = entry.getValue();
            if (auction.state() != JobState.OPEN || auction.endsAt() > now) continue;
            UUID winner = auction.bidderId();
            if (winner == null) market.addItemClaim(auction.seller(), auction.lot());
            else {
                market.addItemClaim(winner, auction.lot());
                market.addCreditClaim(auction.seller(), auction.highestBid());
                EconomyAlerts.raise(server, winner, "auction_won", "You won an auction.", LinkAlert.Severity.INFO);
            }
            auctions.put(auction.id(), new AuctionRecord(auction.id(), auction.seller(), auction.lot(),
                    auction.minimumBid(), auction.highestBid(), auction.highestBidder(), auction.endsAt(),
                    auction.interplanetary(), JobState.FULFILLED)); changed = true;
        }
        if (changed) setDirty();
    }

    public synchronized List<EconomyJob> jobs(JobType type) {
        return jobs.values().stream().filter(value -> type == null || value.type() == type)
                .sorted(Comparator.comparingLong(EconomyJob::expiresAt)).toList();
    }
    public synchronized List<AuctionRecord> auctions() {
        return auctions.values().stream().filter(value -> value.state() == JobState.OPEN)
                .sorted(Comparator.comparingLong(AuctionRecord::endsAt)).toList();
    }

    public synchronized void forgetPlayer(UUID player) {
        if (player == null) return;
        boolean changed = jobs.entrySet().removeIf(entry -> entry.getValue().issuer().equals(player)
                || player.equals(entry.getValue().claimantId()));
        changed |= auctions.entrySet().removeIf(entry -> entry.getValue().seller().equals(player)
                || player.equals(entry.getValue().bidderId()));
        if (changed) setDirty();
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
    private static Codec<ObligationSavedData> codec() {
        return RecordCodecBuilder.create(instance -> instance.group(
                JobRow.CODEC.listOf().optionalFieldOf("jobs", List.of()).forGetter(ObligationSavedData::jobRows),
                AuctionRow.CODEC.listOf().optionalFieldOf("auctions", List.of()).forGetter(ObligationSavedData::auctionRows))
                .apply(instance, ObligationSavedData::fromRows));
    }
    private List<JobRow> jobRows() { return jobs.values().stream().map(value -> new JobRow(value.id().toString(),
            value.type().name(), value.issuer().toString(), value.claimant(), value.target(), value.quantity(),
            value.destination(), value.payout(), value.expiresAt(), value.state().name())).toList(); }
    private List<AuctionRow> auctionRows() { return auctions.values().stream().map(value -> new AuctionRow(
            value.id().toString(), value.seller().toString(), value.lot().copy(), value.minimumBid(), value.highestBid(),
            value.highestBidder(), value.endsAt(), value.interplanetary(), value.state().name())).toList(); }
    private static ObligationSavedData fromRows(List<JobRow> jobs, List<AuctionRow> auctions) {
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
        return data;
    }
}
