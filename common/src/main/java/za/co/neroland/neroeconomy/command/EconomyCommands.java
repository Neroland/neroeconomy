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
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import za.co.neroland.nerolandcore.data.PlayerDataErasure;
import za.co.neroland.nerolandcore.economy.CurrencyApi;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.config.EconomyConfig;
import za.co.neroland.neroeconomy.data.LedgerExportFiles;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.economy.PlayerLedgerExport;
import za.co.neroland.neroeconomy.economy.TransactionType;
import za.co.neroland.neroeconomy.telemetry.NeroEconomyTelemetry;
import za.co.neroland.neroeconomy.obligation.JobType;
import za.co.neroland.neroeconomy.obligation.ObligationSavedData;

/** Server-authoritative command tree. No player identity or transaction detail is info-logged. */
public final class EconomyCommands {
    private EconomyCommands() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(root("eco"));
        dispatcher.register(root("economy"));
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
                .then(auctionTree());
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
}
