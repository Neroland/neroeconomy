package za.co.neroland.neroeconomy.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

import za.co.neroland.nerolandcore.data.PlayerDataErasure;
import za.co.neroland.nerolandcore.economy.CurrencyApi;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.block.PlayerShopBlockEntity;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.data.LedgerExportFiles;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.economy.PlayerLedgerExport;
import za.co.neroland.neroeconomy.economy.TransactionType;
import za.co.neroland.neroeconomy.market.ListingSource;
import za.co.neroland.neroeconomy.market.MarketSavedData;
import za.co.neroland.neroeconomy.registry.ModBlocks;
import za.co.neroland.neroeconomy.telemetry.NeroEconomyTelemetry;
import za.co.neroland.neroeconomy.obligation.JobType;
import za.co.neroland.neroeconomy.obligation.ObligationSavedData;

/** Server-authoritative command tree. No player identity or transaction detail is info-logged. */
public final class EconomyCommands {
    private EconomyCommands() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(root("eco"));
        dispatcher.register(root("economy"));
        // Third alias. The screenshot harness drives `neroeconomy gallery` / `neroeconomy gallery clear`
        // by name, and its own client root is deliberately `necgallery` so it cannot shadow this tree.
        dispatcher.register(root("neroeconomy"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> root(String name) {
        return Commands.literal(name)
                .then(Commands.literal("balance").executes(EconomyCommands::ownBalance)
                        .then(Commands.argument("player", StringArgumentType.word())
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(EconomyCommands::otherBalance)))
                .then(Commands.literal("pay").then(Commands.argument("player", StringArgumentType.word())
                        .then(Commands.argument("amount", LongArgumentType.longArg(1L))
                                .executes(EconomyCommands::pay))))
                .then(Commands.literal("history").executes(EconomyCommands::history))
                .then(Commands.literal("privacy").then(Commands.literal("logging")
                        .then(Commands.argument("optOut", BoolArgumentType.bool())
                                .executes(EconomyCommands::privacy))))
                .then(Commands.literal("admin")
                        .requires(source -> EconomyConfig.ADMIN_MUTATIONS_ENABLED.get()
                                && Commands.hasPermission(Commands.LEVEL_ADMINS).test(source))
                        .then(adminMutation("give", TransactionType.ADMIN_GRANT))
                        .then(adminMutation("take", TransactionType.ADMIN_TAKE))
                        .then(adminMutation("set", TransactionType.ADMIN_SET)))
                .then(Commands.literal("export").requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(EconomyCommands::export)))
                .then(Commands.literal("erase").requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(EconomyCommands::erase)))
                .then(jobTree("contract", JobType.CONTRACT))
                .then(deliveryTree())
                .then(bountyTree())
                .then(auctionTree())
                .then(galleryTree());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> adminMutation(String name, TransactionType type) {
        return Commands.literal(name).then(Commands.argument("player", StringArgumentType.word())
                .then(Commands.argument("amount", LongArgumentType.longArg(0L))
                        .executes(context -> mutate(context, type))));
    }

    private static int ownBalance(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return playerOnly(context);
        return sendBalance(context.getSource(), player.getUUID());
    }

    private static int otherBalance(CommandContext<CommandSourceStack> context) {
        UUID target = resolveAccount(context);
        return target == null ? 0 : sendBalance(context.getSource(), target);
    }

    private static int sendBalance(CommandSourceStack source, UUID target) {
        long balance = CurrencyApi.getBalance(target, NeroEconomyCommon.CREDITS);
        source.sendSuccess(() -> Component.translatable("command.neroeconomy.balance", balance), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int pay(CommandContext<CommandSourceStack> context) {
        ServerPlayer source = context.getSource().getPlayer();
        if (source == null) return playerOnly(context);
        UUID target = resolveAccount(context);
        if (target == null) return 0;
        long amount = LongArgumentType.getLong(context, "amount");
        if (source.getUUID().equals(target)) {
            context.getSource().sendFailure(Component.translatable("command.neroeconomy.self_payment"));
            return 0;
        }
        if (!CurrencyApi.transfer(source.getUUID(), target, NeroEconomyCommon.CREDITS, amount)) {
            context.getSource().sendFailure(Component.translatable("command.neroeconomy.payment_failed"));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.translatable("command.neroeconomy.payment_sent", amount), false);
        ServerPlayer online = context.getSource().getServer().getPlayerList().getPlayer(target);
        if (online != null) online.sendSystemMessage(Component.translatable("command.neroeconomy.payment_received", amount));
        return Command.SINGLE_SUCCESS;
    }

    private static int history(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return playerOnly(context);
        var rows = NeroEconomyCommon.CURRENCY_PROVIDER.activeData().history(player.getUUID(),
                EconomyConfig.HISTORY_LIMIT_PER_PLAYER.get());
        context.getSource().sendSuccess(() -> Component.translatable("command.neroeconomy.history.header", rows.size()), false);
        rows.forEach(row -> context.getSource().sendSuccess(() -> Component.translatable(
                "command.neroeconomy.history.row", row.type().name(), row.amount(), row.timestamp()), false));
        return Command.SINGLE_SUCCESS;
    }

    private static int privacy(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return playerOnly(context);
        boolean optOut = BoolArgumentType.getBool(context, "optOut");
        NeroEconomyCommon.CURRENCY_PROVIDER.activeData().setLoggingOptOut(player.getUUID(), optOut);
        context.getSource().sendSuccess(() -> Component.translatable(
                optOut ? "command.neroeconomy.logging.opted_out" : "command.neroeconomy.logging.opted_in"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int mutate(CommandContext<CommandSourceStack> context, TransactionType type) {
        UUID target = resolveAccount(context);
        if (target == null) return 0;
        long amount = LongArgumentType.getLong(context, "amount");
        EconomySavedData data = NeroEconomyCommon.CURRENCY_PROVIDER.activeData();
        long balance = data.getBalance(target, NeroEconomyCommon.CREDITS);
        boolean success = switch (type) {
            case ADMIN_GRANT -> Long.MAX_VALUE - balance >= amount
                    && NeroEconomyCommon.CURRENCY_PROVIDER.depositTyped(target, NeroEconomyCommon.CREDITS, amount, type);
            case ADMIN_TAKE -> NeroEconomyCommon.CURRENCY_PROVIDER.withdrawTyped(
                    target, NeroEconomyCommon.CREDITS, amount, type);
            case ADMIN_SET -> amount >= balance
                    ? NeroEconomyCommon.CURRENCY_PROVIDER.depositTyped(
                            target, NeroEconomyCommon.CREDITS, amount - balance, type)
                    : NeroEconomyCommon.CURRENCY_PROVIDER.withdrawTyped(
                            target, NeroEconomyCommon.CREDITS, balance - amount, type);
            default -> false;
        };
        if (!success) {
            context.getSource().sendFailure(Component.translatable("command.neroeconomy.admin_failed"));
            return 0;
        }
        // Neither the log nor the broadcast carries the amount, and the result stays with the caller.
        NeroEconomyCommon.LOGGER.debug("[NeroEconomy] Administrative mutation completed (reason={}).", type);
        context.getSource().sendSuccess(() -> Component.translatable("command.neroeconomy.admin_success"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int export(CommandContext<CommandSourceStack> context) {
        UUID target = resolveSubject(context);
        if (target == null) return 0;
        PlayerLedgerExport export = NeroEconomyCommon.CURRENCY_PROVIDER.activeData().export(target);
        JsonObject json = new JsonObject();
        json.addProperty("player_uuid", export.player().toString());
        json.addProperty("currency", export.currency());
        json.addProperty("balance", export.balance());
        json.addProperty("transaction_logging_opt_out", export.transactionLoggingOptOut());
        JsonArray transactions = new JsonArray();
        export.transactions().forEach(row -> {
            JsonObject value = new JsonObject();
            value.addProperty("reason", row.type().name());
            value.addProperty("amount", row.amount());
            value.addProperty("currency", row.currency());
            value.addProperty("timestamp", row.timestamp());
            value.addProperty("source", row.sourceAccount());
            value.addProperty("destination", row.destinationAccount());
            transactions.add(value);
        });
        json.add("transactions", transactions);
        Path root = LedgerExportFiles.directory(context.getSource().getServer());
        Path output = LedgerExportFiles.file(context.getSource().getServer(), target);
        try {
            Files.createDirectories(root);
            Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(json),
                    StandardCharsets.UTF_8);
        } catch (IOException failure) {
            NeroEconomyTelemetry.captureHandledException(failure, "data_subject_export");
            context.getSource().sendFailure(Component.translatable("command.neroeconomy.export_failed"));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.translatable("command.neroeconomy.export_success",
                output.getFileName().toString()), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int erase(CommandContext<CommandSourceStack> context) {
        UUID target = resolveSubject(context);
        if (target == null) return 0;
        PlayerDataErasure.erase(context.getSource().getServer(), target);
        context.getSource().sendSuccess(() -> Component.translatable("command.neroeconomy.erase_success"), true);
        return Command.SINGLE_SUCCESS;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> jobTree(String name, JobType type) {
        return Commands.literal(name)
                .then(Commands.literal("post").then(Commands.argument("target", StringArgumentType.word())
                        .then(Commands.argument("quantity", IntegerArgumentType.integer(1, 2304))
                                .then(Commands.argument("payout", LongArgumentType.longArg(1L))
                                        .executes(context -> postJob(context, type, ""))))))
                .then(Commands.literal("claim").then(Commands.argument("id", StringArgumentType.word())
                        .executes(EconomyCommands::claimJob)))
                .then(Commands.literal("fulfill").then(Commands.argument("id", StringArgumentType.word())
                        .executes(EconomyCommands::fulfillJob)))
                .then(Commands.literal("list").executes(context -> listJobs(context, type)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> deliveryTree() {
        return Commands.literal("delivery")
                .then(Commands.literal("post").then(Commands.argument("target", StringArgumentType.word())
                        .then(Commands.argument("quantity", IntegerArgumentType.integer(1, 2304))
                                .then(Commands.argument("dimension", StringArgumentType.word())
                                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                                .then(Commands.argument("payout", LongArgumentType.longArg(1L))
                                                                        .executes(EconomyCommands::postDelivery)))))))))
                .then(Commands.literal("claim").then(Commands.argument("id", StringArgumentType.word())
                        .executes(EconomyCommands::claimJob)))
                .then(Commands.literal("fulfill").then(Commands.argument("id", StringArgumentType.word())
                        .executes(EconomyCommands::fulfillJob)))
                .then(Commands.literal("list").executes(context -> listJobs(context, JobType.DELIVERY)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> bountyTree() {
        return Commands.literal("bounty")
                .then(Commands.literal("post").then(Commands.argument("target", StringArgumentType.word())
                        .then(Commands.argument("payout", LongArgumentType.longArg(1L))
                                .executes(context -> postJob(context, JobType.BOUNTY, "", 1))
                                .then(Commands.argument("quantity", IntegerArgumentType.integer(1, 2304))
                                        .executes(context -> postJob(context, JobType.BOUNTY, "",
                                                IntegerArgumentType.getInteger(context, "quantity")))))))
                .then(Commands.literal("claim").then(Commands.argument("id", StringArgumentType.word())
                        .executes(EconomyCommands::claimJob)))
                .then(Commands.literal("list").executes(context -> listJobs(context, JobType.BOUNTY)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> auctionTree() {
        return Commands.literal("auction")
                .then(Commands.literal("create").then(Commands.argument("quantity", IntegerArgumentType.integer(1, 64))
                        .then(Commands.argument("minimumBid", LongArgumentType.longArg(1L))
                                .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 10080))
                                        .executes(context -> createAuction(context, false))
                                        .then(Commands.argument("interplanetary", BoolArgumentType.bool())
                                                .executes(context -> createAuction(context,
                                                        BoolArgumentType.getBool(context, "interplanetary"))))))))
                .then(Commands.literal("bid").then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("amount", LongArgumentType.longArg(1L))
                                .executes(EconomyCommands::bid))))
                .then(Commands.literal("list").executes(EconomyCommands::listAuctions));
    }

    private static int postDelivery(CommandContext<CommandSourceStack> context) {
        String destination = StringArgumentType.getString(context, "dimension") + '|'
                + IntegerArgumentType.getInteger(context, "x") + '|'
                + IntegerArgumentType.getInteger(context, "y") + '|'
                + IntegerArgumentType.getInteger(context, "z");
        return postJob(context, JobType.DELIVERY, destination);
    }

    private static int postJob(CommandContext<CommandSourceStack> context, JobType type, String destination) {
        return postJob(context, type, destination, IntegerArgumentType.getInteger(context, "quantity"));
    }

    /** A bounty's quantity is the number of proven kills it takes to settle, and defaults to one. */
    private static int postJob(CommandContext<CommandSourceStack> context, JobType type, String destination,
            int quantity) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return playerOnly(context);
        String target = StringArgumentType.getString(context, "target");
        if (type == JobType.BOUNTY && target.startsWith("player:") && !EconomyConfig.PLAYER_BOUNTIES_ENABLED.get()) {
            context.getSource().sendFailure(Component.translatable("command.neroeconomy.bounty.players_disabled"));
            return 0;
        }
        long payout = LongArgumentType.getLong(context, "payout");
        long expires = System.currentTimeMillis() + EconomyConfig.DEFAULT_LISTING_DURATION_HOURS.get() * 3_600_000L;
        var job = ObligationSavedData.get(player.level().getServer()).postJob(player, type, target, quantity,
                destination, payout, expires);
        if (job == null) { context.getSource().sendFailure(Component.translatable("command.neroeconomy.job.post_failed")); return 0; }
        context.getSource().sendSuccess(() -> Component.translatable("command.neroeconomy.job.posted", job.id().toString()), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int claimJob(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return playerOnly(context);
        UUID id = parseId(context); if (id == null) return 0;
        boolean ok = ObligationSavedData.get(player.level().getServer()).claim(id, player.getUUID());
        if (!ok) { context.getSource().sendFailure(Component.translatable("command.neroeconomy.job.claim_failed")); return 0; }
        context.getSource().sendSuccess(() -> Component.translatable("command.neroeconomy.job.claimed"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int fulfillJob(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return playerOnly(context);
        UUID id = parseId(context); if (id == null) return 0;
        boolean ok = ObligationSavedData.get(player.level().getServer()).fulfill(player, id);
        if (!ok) { context.getSource().sendFailure(Component.translatable("command.neroeconomy.job.fulfill_failed")); return 0; }
        context.getSource().sendSuccess(() -> Component.translatable("command.neroeconomy.job.fulfilled"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int listJobs(CommandContext<CommandSourceStack> context, JobType type) {
        var jobs = ObligationSavedData.get(context.getSource().getServer()).jobs(type);
        context.getSource().sendSuccess(() -> Component.translatable("command.neroeconomy.job.list", jobs.size()), false);
        jobs.stream().limit(20).forEach(job -> context.getSource().sendSuccess(() -> Component.translatable(
                "command.neroeconomy.job.row", job.id().toString(), job.target(), job.quantity(), job.payout(),
                job.state().name()), false));
        return Command.SINGLE_SUCCESS;
    }

    private static int createAuction(CommandContext<CommandSourceStack> context, boolean interplanetary) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return playerOnly(context);
        long end = System.currentTimeMillis() + IntegerArgumentType.getInteger(context, "minutes") * 60_000L;
        var auction = ObligationSavedData.get(player.level().getServer()).createAuction(player,
                IntegerArgumentType.getInteger(context, "quantity"), LongArgumentType.getLong(context, "minimumBid"),
                end, interplanetary);
        if (auction == null) { context.getSource().sendFailure(Component.translatable("command.neroeconomy.auction.create_failed")); return 0; }
        context.getSource().sendSuccess(() -> Component.translatable("command.neroeconomy.auction.created",
                auction.id().toString(), auction.interplanetary()), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int bid(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) return playerOnly(context);
        UUID id = parseId(context); if (id == null) return 0;
        boolean ok = ObligationSavedData.get(player.level().getServer()).bid(player, id,
                LongArgumentType.getLong(context, "amount"));
        if (!ok) { context.getSource().sendFailure(Component.translatable("command.neroeconomy.auction.bid_failed")); return 0; }
        context.getSource().sendSuccess(() -> Component.translatable("command.neroeconomy.auction.bid_placed"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int listAuctions(CommandContext<CommandSourceStack> context) {
        // A console listing is unfiltered; a player only sees lots their progression allows.
        var auctions = ObligationSavedData.get(context.getSource().getServer())
                .auctions(context.getSource().getPlayer());
        context.getSource().sendSuccess(() -> Component.translatable("command.neroeconomy.auction.list", auctions.size()), false);
        auctions.stream().limit(20).forEach(auction -> context.getSource().sendSuccess(() -> Component.translatable(
                "command.neroeconomy.auction.row", auction.id().toString(), auction.highestBid(), auction.endsAt()), false));
        return Command.SINGLE_SUCCESS;
    }

    private static UUID parseId(CommandContext<CommandSourceStack> context) {
        try { return UUID.fromString(StringArgumentType.getString(context, "id")); }
        catch (IllegalArgumentException ignored) {
            context.getSource().sendFailure(Component.translatable("command.neroeconomy.invalid_id")); return null;
        }
    }

    private static int playerOnly(CommandContext<CommandSourceStack> context) {
        context.getSource().sendFailure(Component.translatable("command.neroeconomy.player_only"));
        return 0;
    }

    /**
     * Accepting any well-formed UUID would let {@code pay} and {@code balance} open an unlimited
     * number of funded accounts for ids nobody has ever played as, so an offline id has to be backed
     * by a player-data file in this world before it is treated as an account.
     */
    private static UUID resolveAccount(CommandContext<CommandSourceStack> context) {
        String value = StringArgumentType.getString(context, "player");
        MinecraftServer server = context.getSource().getServer();
        ServerPlayer online = server.getPlayerList().getPlayerByName(value);
        if (online != null) return online.getUUID();
        UUID id = null;
        try { id = UUID.fromString(value); }
        catch (IllegalArgumentException ignored) { }
        if (id != null && (server.getPlayerList().getPlayer(id) != null || hasPlayerData(server, id))) return id;
        context.getSource().sendFailure(Component.translatable("command.neroeconomy.player_not_found"));
        return null;
    }

    /**
     * Export and erasure are admin-only, never mint anything, and must still reach a data subject
     * whose world files are already gone, so they accept any well-formed id.
     */
    private static UUID resolveSubject(CommandContext<CommandSourceStack> context) {
        String value = StringArgumentType.getString(context, "player");
        ServerPlayer online = context.getSource().getServer().getPlayerList().getPlayerByName(value);
        if (online != null) return online.getUUID();
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException ignored) {
            context.getSource().sendFailure(Component.translatable("command.neroeconomy.player_not_found"));
            return null;
        }
    }

    private static boolean hasPlayerData(MinecraftServer server, UUID player) {
        try {
            return Files.isRegularFile(server.getWorldPath(LevelResource.PLAYER_DATA_DIR)
                    .resolve(player + ".dat"));
        } catch (RuntimeException ignored) { return false; }
    }

    // ================================================================================================
    // Gallery — a creative-only showcase scene for the listing screenshots.
    //
    // `/neroeconomy gallery` builds four demo clusters around the player, each on its own compass
    // bearing GALLERY_RADIUS blocks out with its own backdrop wall on the far side, so a camera near
    // the centre can shoot each one outward against a clean surface with nothing else in frame.
    // `/neroeconomy gallery clear` wipes the footprint so a rebuild cannot stack duplicates.
    //
    // COORDINATE CONTRACT with client/GalleryCaptureHarness (origin O = the player's block position,
    // oy = the pad level; display blocks sit one block above it). Tune the two files together:
    //
    //   shot              bearing  cluster centre        backdrop wall   pad
    //   bank_terminal     NORTH    (ox,    oy+1, oz-24)  z = oz-26       smooth quartz, 7x5
    //   player_shop       EAST     (ox+24, oy+1, oz)     x = ox+26       polished andesite, 5x9
    //   trading_terminal  SOUTH    (ox,    oy+2, oz+24)  z = oz+26       smooth stone 7x5 + deepslate dais
    //   market_board      WEST     (ox-24, oy+1, oz)     x = ox-26       polished andesite, 5x7
    //
    // The harness teleports to (0.5, 64, 0.5) in the flat `neroeconomy:capture` dimension before
    // building, so in a capture run O = (0, 64, 0) and every coordinate above is absolute.
    // ================================================================================================

    /** Distance from the gallery origin to each cluster centre. Mirrors {@code GalleryCaptureHarness.CLUSTER_RADIUS}. */
    private static final int GALLERY_RADIUS = 24;
    /** Blocks of slack beyond the outermost backdrop wall that {@code gallery clear} sweeps. */
    private static final int GALLERY_MARGIN = 8;
    /** Height above the pad level that {@code gallery clear} sweeps. */
    private static final int GALLERY_TOP = 12;
    /** Nothing here is ever a real trade, so the demo rows carry the ordinary listing lifetime. */
    private static long galleryExpiry() {
        return System.currentTimeMillis() + EconomyConfig.DEFAULT_LISTING_DURATION_HOURS.get() * 3_600_000L;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> galleryTree() {
        return Commands.literal("gallery")
                .requires(source -> source.getPlayer() != null)
                .executes(context -> buildGallery(context.getSource()))
                .then(Commands.literal("clear").executes(context -> clearGallery(context.getSource())));
    }

    /**
     * Build the four demo clusters around the player. Creative-only: it places blocks outright, mints
     * the demo stock the shops and market rows escrow, and funds the buy offers through the same
     * audited deposit the {@code admin give} subcommand uses, so nothing here can create value that the
     * ledger has not recorded.
     */
    private static int buildGallery(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Run this as a player."));
            return 0;
        }
        if (!player.getAbilities().instabuild) {
            source.sendFailure(Component.literal("The NeroEconomy gallery is creative-only."));
            return 0;
        }
        ServerLevel level = player.level();
        BlockPos origin = player.blockPosition();
        int ox = origin.getX();
        int oy = origin.getY(); // pad level; display blocks sit at oy + 1
        int oz = origin.getZ();
        int r = GALLERY_RADIUS;

        BlockState quartz = Blocks.SMOOTH_QUARTZ.defaultBlockState();
        BlockState quartzPillar = Blocks.QUARTZ_PILLAR.defaultBlockState();
        BlockState quartzCap = Blocks.CHISELED_QUARTZ_BLOCK.defaultBlockState();
        BlockState stone = Blocks.SMOOTH_STONE.defaultBlockState();
        BlockState andesite = Blocks.POLISHED_ANDESITE.defaultBlockState();
        BlockState deepslate = Blocks.POLISHED_DEEPSLATE.defaultBlockState();
        BlockState deepslateTrim = Blocks.CHISELED_DEEPSLATE.defaultBlockState();

        // ---- NORTH: bank kiosk -----------------------------------------------------------------
        // A quartz pad with a deepslate back wall and two capped pillars framing the Bank Terminal.
        int bz = oz - r;
        pad(level, ox - 3, bz - 2, ox + 3, bz + 2, oy, quartz);
        fill(level, ox - 3, oy + 1, bz - 2, ox + 3, oy + 4, bz - 2, deepslate);
        fill(level, ox - 2, oy + 1, bz, ox - 2, oy + 2, bz, quartzPillar);
        fill(level, ox + 2, oy + 1, bz, ox + 2, oy + 2, bz, quartzPillar);
        level.setBlockAndUpdate(new BlockPos(ox - 2, oy + 3, bz), quartzCap);
        level.setBlockAndUpdate(new BlockPos(ox + 2, oy + 3, bz), quartzCap);
        level.setBlockAndUpdate(new BlockPos(ox, oy + 1, bz),
                ModBlocks.BANK_TERMINAL.get().defaultBlockState());

        // ---- EAST: player shop row -------------------------------------------------------------
        // Three shops in a north-south line, ALL primed (owned, priced, stocked) so the shot shows a
        // live storefront rather than three empty blocks.
        int px = ox + r;
        pad(level, px - 2, oz - 4, px + 2, oz + 4, oy, andesite);
        fill(level, px + 2, oy + 1, oz - 4, px + 2, oy + 4, oz + 4, deepslate);
        primeShop(level, player, new BlockPos(px, oy + 1, oz - 2), new ItemStack(Items.DIAMOND, 12), 180L);
        primeShop(level, player, new BlockPos(px, oy + 1, oz), new ItemStack(Items.GOLD_INGOT, 32), 24L);
        primeShop(level, player, new BlockPos(px, oy + 1, oz + 2), new ItemStack(Items.IRON_INGOT, 48), 6L);
        level.setBlockAndUpdate(new BlockPos(px, oy + 1, oz - 4), deepslateTrim);
        level.setBlockAndUpdate(new BlockPos(px, oy + 1, oz + 4), deepslateTrim);

        // ---- SOUTH: trading terminal dais ------------------------------------------------------
        // A one-block deepslate dais lifts the Trading Terminal + Bank Terminal pair to oy+2.
        int tz = oz + r;
        pad(level, ox - 3, tz - 2, ox + 3, tz + 2, oy, stone);
        fill(level, ox - 2, oy + 1, tz - 1, ox + 2, oy + 1, tz + 1, deepslate);
        fill(level, ox - 3, oy + 1, tz + 2, ox + 3, oy + 5, tz + 2, deepslate);
        level.setBlockAndUpdate(new BlockPos(ox - 1, oy + 2, tz),
                ModBlocks.TRADING_TERMINAL.get().defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(ox + 1, oy + 2, tz),
                ModBlocks.BANK_TERMINAL.get().defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(ox, oy + 2, tz), deepslateTrim);

        // ---- WEST: market board wall -----------------------------------------------------------
        int mx = ox - r;
        pad(level, mx - 2, oz - 3, mx + 2, oz + 3, oy, andesite);
        fill(level, mx - 2, oy + 1, oz - 3, mx - 2, oy + 5, oz + 3, deepslate);
        BlockPos boardPos = new BlockPos(mx, oy + 1, oz - 1);
        level.setBlockAndUpdate(boardPos, ModBlocks.MARKET_BOARD.get().defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(mx, oy + 1, oz + 1),
                ModBlocks.MARKET_BOARD.get().defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(mx, oy + 1, oz), deepslateTrim);
        seedMarket(level, player, boardPos);

        source.sendSuccess(() -> Component.literal("Built the NeroEconomy gallery: a bank kiosk (north), "
                + "a row of three live player shops (east), a trading-terminal dais (south) and a "
                + "market-board wall seeded with sell listings and standing buy offers (west)."), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Wipe the gallery built at the player's feet so a rebuild (or the screenshot harness) cannot stack
     * duplicates. Clears the whole footprint to air from the pad layer ({@code origin.y}) up, leaving
     * the natural ground at {@code origin.y - 1} intact, and removes every non-player entity in the box.
     *
     * <p>Player shops are cancelled through the block entity's own teardown before their block goes, so
     * a rerun cannot leave the market store full of listings whose shop no longer exists; the escrowed
     * stock becomes an owner claim exactly as it would if the shop had been mined. The seeded
     * market-board rows are deliberately left to run out their ordinary expiry — the gallery does not
     * invent a privileged deletion path into the market store — and {@link MarketSavedData#expire} is
     * run here so anything already lapsed is swept in the same pass.
     */
    private static int clearGallery(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Run this as a player."));
            return 0;
        }
        if (!player.getAbilities().instabuild) {
            source.sendFailure(Component.literal("The NeroEconomy gallery is creative-only."));
            return 0;
        }
        ServerLevel level = player.level();
        BlockPos origin = player.blockPosition();
        int oy = origin.getY();
        int reach = GALLERY_RADIUS + GALLERY_MARGIN;
        int minX = origin.getX() - reach;
        int maxX = origin.getX() + reach;
        int minZ = origin.getZ() - reach;
        int maxZ = origin.getZ() + reach;
        int topY = oy + GALLERY_TOP;

        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int cleared = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = oy; y <= topY; y++) {
                    cursor.set(x, y, z);
                    BlockState state = level.getBlockState(cursor);
                    if (state.isAir()) continue;
                    if (state.hasBlockEntity()
                            && level.getBlockEntity(cursor) instanceof PlayerShopBlockEntity shop) {
                        shop.cancel(); // hand the escrow back before the block disappears
                    }
                    level.setBlock(cursor, air, 2); // flag 2 = notify clients, skip neighbour cascade
                    cleared++;
                }
            }
        }

        // Remove anything the scene (or a stray drop) left behind — everything but players.
        AABB box = new AABB(minX, oy - 1, minZ, maxX + 1, topY + 4, maxZ + 1);
        int removed = 0;
        for (Entity entity : level.getEntitiesOfClass(Entity.class, box, e -> !(e instanceof Player))) {
            entity.discard();
            removed++;
        }
        MarketSavedData.get(level.getServer()).expire(System.currentTimeMillis());

        int clearedBlocks = cleared;
        int removedEntities = removed;
        source.sendSuccess(() -> Component.literal("Cleared the NeroEconomy gallery: " + clearedBlocks
                + " blocks → air, " + removedEntities + " entities removed."), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Place a Player Shop and bring it to life: claim it for the builder, set a price, then push the
     * demo stock through the ordinary container path so it lands in the central escrow the same way a
     * player's own deposit would ({@code setItem} → {@code setChanged} → {@code replaceShopStock}).
     * Ownership has to be claimed first — an unowned shop rejects both the price and the stock.
     */
    private static void primeShop(ServerLevel level, ServerPlayer owner, BlockPos pos, ItemStack stock,
            long unitPrice) {
        level.setBlockAndUpdate(pos, ModBlocks.PLAYER_SHOP.get().defaultBlockState());
        if (!(level.getBlockEntity(pos) instanceof PlayerShopBlockEntity shop)) return;
        shop.claim(owner.getUUID());
        shop.setPrice(unitPrice);
        shop.setItem(PlayerShopBlockEntity.OFFER_SLOT, stock);
    }

    /**
     * Give the market boards something to show: two escrowed sell listings and two standing buy offers,
     * all owned by the builder. Both go through the store's ordinary public entry points, so the escrow
     * accounting is exactly the same as a player-posted row.
     */
    private static void seedMarket(ServerLevel level, ServerPlayer player, BlockPos boardPos) {
        String location = level.dimension().identifier() + "|" + boardPos.asLong();
        postDemoListing(level, player, new ItemStack(Items.DIAMOND, 16), 16, 210L, location);
        postDemoListing(level, player, new ItemStack(Items.COPPER_INGOT, 64), 64, 4L, location);
        postDemoBuyOffer(level, player, Items.REDSTONE, 64, 3L);
        postDemoBuyOffer(level, player, Items.EMERALD, 16, 90L);
    }

    /**
     * {@code postFromHand} escrows out of the seller's main hand, which is the only public entry point
     * into a sell listing. The builder's real held item is saved and restored around the call, so the
     * only stack the listing can consume is the demo one created here — the player's inventory is
     * never touched.
     */
    private static void postDemoListing(ServerLevel level, ServerPlayer player, ItemStack demo, int quantity,
            long unitPrice, String location) {
        ItemStack held = player.getMainHandItem().copy();
        player.setItemInHand(InteractionHand.MAIN_HAND, demo);
        MarketSavedData.get(level.getServer()).postFromHand(player, quantity, unitPrice, galleryExpiry(),
                ListingSource.MARKET_BOARD, location, UUID.randomUUID());
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
    }

    /**
     * A buy offer locks credits in escrow up front, so the offer is funded first through the same
     * audited deposit path {@code admin give} uses. Nothing is taken from the player's own balance.
     */
    private static void postDemoBuyOffer(ServerLevel level, ServerPlayer player, Item item, int quantity,
            long unitPrice) {
        long escrow;
        try { escrow = Math.multiplyExact((long) quantity, unitPrice); }
        catch (ArithmeticException overflow) { return; }
        if (!NeroEconomyCommon.CURRENCY_PROVIDER.depositTyped(player.getUUID(), NeroEconomyCommon.CREDITS,
                escrow, TransactionType.ADMIN_GRANT)) return;
        MarketSavedData.get(level.getServer()).postBuyOffer(player, BuiltInRegistries.ITEM.getKey(item),
                quantity, unitPrice, galleryExpiry(), UUID.randomUUID());
    }

    /** A flat pad of {@code state} at {@code y}, inclusive of both corners. */
    private static void pad(ServerLevel level, int x1, int z1, int x2, int z2, int y, BlockState state) {
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                level.setBlockAndUpdate(new BlockPos(x, y, z), state);
            }
        }
    }

    /** A solid box of {@code state}, inclusive of both corners. */
    private static void fill(ServerLevel level, int x1, int y1, int z1, int x2, int y2, int z2,
            BlockState state) {
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, y, z), state);
                }
            }
        }
    }
}
