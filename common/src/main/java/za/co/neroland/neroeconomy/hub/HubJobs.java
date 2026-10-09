package za.co.neroland.neroeconomy.hub;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.house.HouseData;
import za.co.neroland.neroeconomy.house.HouseDefinition;
import za.co.neroland.neroeconomy.market.CancelResult;
import za.co.neroland.neroeconomy.obligation.EconomyJob;
import za.co.neroland.neroeconomy.obligation.FulfillResult;
import za.co.neroland.neroeconomy.obligation.JobTargets;
import za.co.neroland.neroeconomy.obligation.JobType;
import za.co.neroland.neroeconomy.obligation.ObligationSavedData;

/**
 * The Jobs tab of one open hub on the server: the lists of contracts, deliveries and bounties, the
 * viewer's own jobs, and posting, claiming, releasing, handing in and withdrawing, each through
 * {@link ObligationSavedData}, which checks everything again. A bounty on a player is never shown
 * by its stored target; who posted or took a job is never shown at all.
 */
final class HubJobs {
    /** Shown as the badge of a server-funded job that no Merchant House posted. */
    static final String SERVER_BADGE = "gui.neroeconomy.hub.jobs.server_badge";

    private final ServerPlayer player;
    private HubAction.JobsView view = HubAction.JobsView.CONTRACTS;

    HubJobs(ServerPlayer player) {
        this.player = player;
    }

    private MinecraftServer server() { return player.level().getServer(); }

    private UUID self() { return player.getUUID(); }

    private ObligationSavedData store() { return ObligationSavedData.get(server()); }

    private void say(String key, Object... args) { player.sendSystemMessage(Component.translatable(key, args)); }

    /** Tells the player an operator has closed the market. @return true when it is, so the call must stop */
    private boolean marketClosed() {
        if (!EconomyStore.get(server()).marketClosed()) return false;
        say(EconomyHealth.MARKET_CLOSED_KEY);
        return true;
    }

    private boolean playerBountiesAllowed() {
        return EconomyConfig.PLAYER_BOUNTIES_ENABLED.get() && Commands.LEVEL_GAMEMASTERS.check(player.permissions());
    }

    // --- reading -----------------------------------------------------------------------------

    private List<EconomyJob> listed() {
        ObligationSavedData store = store();
        return switch (view) {
            case CONTRACTS -> store.jobs(JobType.CONTRACT);
            case DELIVERIES -> store.jobs(JobType.DELIVERY);
            case BOUNTIES -> store.jobs(JobType.BOUNTY);
            case MINE -> {
                List<EconomyJob> mine = new ArrayList<>(store.jobsIssuedBy(self()));
                for (EconomyJob job : store.jobs(null))
                    if (self().equals(job.claimantId()) && !mine.contains(job)) mine.add(job);
                yield mine;
            }
            default -> List.of();
        };
    }

    HubPages.Built build(int page) {
        long now = NeroEconomyCommon.CURRENCY_PROVIDER.now();
        List<JobsPages.JobLine> lines = new ArrayList<>();
        for (EconomyJob job : listed()) lines.add(line(job, now));
        var at = player.blockPosition();
        return JobsPages.build(new JobsPages.JobsView(view, lines, store().jobsIssuedBy(self()).size(),
                EconomyConfig.MAX_JOBS_PER_PLAYER.get(), EconomyConfig.DEFAULT_LISTING_DURATION_HOURS.get(),
                playerBountiesAllowed(), player.level().dimension().identifier().toString(), at.getX(), at.getY(),
                at.getZ()), page);
    }

    private JobsPages.JobLine line(EconomyJob job, long now) {
        String shown = JobTargets.visibleTo(job.target(), job.issuer(), self());
        String label = JobTargets.playerLabelKey(shown);
        String target;
        String icon;
        boolean item = false;
        if (label != null) {
            target = label;
            icon = "minecraft:player_head";
        } else if (job.type() == JobType.BOUNTY) {
            Identifier id = Identifier.tryParse(shown);
            target = id == null ? shown : BuiltInRegistries.ENTITY_TYPE.getOptional(id)
                    .map(type -> type.getDescriptionId()).orElse(shown);
            icon = id == null ? "minecraft:iron_sword" : spawnEgg(id);
        } else if (JobTargets.isTag(shown)) {
            target = shown;
            icon = tagIcon(shown.substring(JobTargets.TAG_PREFIX.length()));
        } else {
            target = shown;
            icon = shown;
            item = true;
        }
        String houseKey = "";
        if (ObligationSavedData.SYSTEM_ACCOUNT.equals(job.issuer())) {
            String house = store().houseOf(job.id());
            HouseDefinition definition = house == null ? null : HouseData.forServer(server()).get(house);
            houseKey = definition == null ? SERVER_BADGE : definition.nameKey();
        }
        JobForm.Place place = JobForm.destination(job.destination());
        return new JobsPages.JobLine(job.id(), "command.neroeconomy.job_type." + job.type().name().toLowerCase(Locale.ROOT),
                icon, target, item, houseKey, job.state().translationKey(), job.quantity(), job.payout(),
                Math.max(0L, job.expiresAt() - now), job.issuer().equals(self()), self().equals(job.claimantId()),
                place == null ? "" : place.dimension(), place == null ? 0L : place.x(), place == null ? 0L : place.y(),
                place == null ? 0L : place.z());
    }

    private static String spawnEgg(Identifier entity) {
        Identifier egg = Identifier.fromNamespaceAndPath(entity.getNamespace(), entity.getPath() + "_spawn_egg");
        return BuiltInRegistries.ITEM.containsKey(egg) ? egg.toString() : "minecraft:iron_sword";
    }

    private static String tagIcon(String tag) {
        Identifier id = Identifier.tryParse(tag);
        if (id == null) return "minecraft:name_tag";
        return BuiltInRegistries.ITEM.get(TagKey.create(Registries.ITEM, id))
                .flatMap(named -> named.stream().findFirst())
                .flatMap(holder -> holder.unwrapKey())
                .map(key -> key.identifier().toString()).orElse("minecraft:name_tag");
    }

    long signature() {
        return Objects.hash(view, store().jobs(null), player.blockPosition());
    }

    HubAction.JobsView view() { return view; }

    void view(HubAction.JobsView next) { view = next; }

    // --- the calls ---------------------------------------------------------------------------

    void claim(HubPages.RowRef row) {
        if (marketClosed()) return;
        say(store().claim(row.id(), self()) ? "command.neroeconomy.job.claimed" : "command.neroeconomy.job.claim_failed");
    }

    void release(HubPages.RowRef row) {
        say(store().release(row.id(), self()) ? "command.neroeconomy.job.released"
                : "command.neroeconomy.job.release_failed");
    }

    void fulfil(HubPages.RowRef row) {
        FulfillResult result = store().fulfill(player, row.id());
        say(result.translationKey());
    }

    void cancel(HubPages.RowRef row) {
        EconomyJob job = store().job(row.id());
        if (job == null || !job.issuer().equals(self())) {
            say(CancelResult.NOT_FOUND.translationKey());
            return;
        }
        CancelResult result = store().cancelJob(server(), row.id(), self());
        say(result.translationKey());
    }

    /**
     * Posts a job. Every refusal the command gives is given here first, in the same words, so
     * the player is told what is wrong before any credit moves; the store checks all of it again
     * and escrows the payout.
     */
    void post(JobForm form, long payout) {
        if (marketClosed()) return;
        ObligationSavedData store = store();
        int cap = EconomyConfig.MAX_JOBS_PER_PLAYER.get();
        if (store.jobsIssuedBy(self()).size() >= cap) {
            say("command.neroeconomy.job.limit_reached", cap);
            return;
        }
        JobType type;
        String target = form.target();
        String destination = "";
        switch (form.kind()) {
            case CONTRACT, DELIVERY -> {
                type = form.kind() == JobForm.Kind.CONTRACT ? JobType.CONTRACT : JobType.DELIVERY;
                if (!ObligationSavedData.targetUsable(type, target)) {
                    say(JobTargets.isTag(target) ? "command.neroeconomy.job.unknown_tag"
                            : "command.neroeconomy.job.unknown_item", target);
                    return;
                }
                if (type == JobType.DELIVERY) {
                    destination = destination(form);
                    if (destination == null) {
                        say("gui.neroeconomy.hub.jobs.unknown_dimension");
                        return;
                    }
                }
            }
            case BOUNTY -> {
                type = JobType.BOUNTY;
                if (JobTargets.PLAYER_ENTITY.equals(target)) {
                    say("gui.neroeconomy.hub.jobs.use_player");
                    return;
                }
                if (!ObligationSavedData.targetUsable(type, target)) {
                    say("command.neroeconomy.job.unknown_entity", target);
                    return;
                }
            }
            default -> {
                type = JobType.BOUNTY;
                if (!playerBountiesAllowed()) {
                    say("command.neroeconomy.bounty.players_disabled");
                    return;
                }
                ServerPlayer named = server().getPlayerList().getPlayerByName(target);
                if (named == null) {
                    say("command.neroeconomy.player_not_found");
                    return;
                }
                if (named.getUUID().equals(self())) {
                    say("command.neroeconomy.bounty.self");
                    return;
                }
                target = JobTargets.player(named.getUUID());
            }
        }
        // A payout is promised to another player, so unvested starting credits cannot fund it.
        if (NeroEconomyCommon.CURRENCY_PROVIDER.lockBlocks(self(), payout)) {
            say("command.neroeconomy.funds_locked", NeroEconomyCommon.CURRENCY_PROVIDER.spendable(self()),
                    EconomyConfig.STARTING_BALANCE_VEST_MINUTES.get());
            return;
        }
        long expires = NeroEconomyCommon.CURRENCY_PROVIDER.now()
                + EconomyConfig.DEFAULT_LISTING_DURATION_HOURS.get() * 3_600_000L;
        EconomyJob job = store.postJob(player, type, target, form.quantity(), destination, payout, expires);
        if (job == null) {
            say("command.neroeconomy.job.post_failed");
            return;
        }
        say("gui.neroeconomy.hub.jobs.posted", job.payout());
        view = HubAction.JobsView.MINE;
    }

    /** Where a delivery goes, as a job stores it; null for a dimension this server does not have. */
    private String destination(JobForm form) {
        if (form.here()) {
            var at = player.blockPosition();
            return player.level().dimension().identifier() + "|" + at.getX() + '|' + at.getY() + '|' + at.getZ();
        }
        JobForm.Place place = JobForm.destination(form.destination());
        if (place == null) return null;
        Identifier id = Identifier.tryParse(place.dimension());
        if (id == null) return null;
        ServerLevel level = server().getLevel(ResourceKey.create(Registries.DIMENSION, id));
        if (level == null) return null;
        return level.dimension().identifier() + "|" + place.x() + '|' + place.y() + '|' + place.z();
    }
}
