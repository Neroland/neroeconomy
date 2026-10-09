package za.co.neroland.neroeconomy.admin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import za.co.neroland.nerolandcore.data.PlayerDataErasure;
import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.commodity.CommodityData;
import za.co.neroland.neroeconomy.commodity.CommodityRegistry;
import za.co.neroland.neroeconomy.data.LedgerExportFiles;
import za.co.neroland.neroeconomy.data.PlayerDataExport;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.EconomySavedData;
import za.co.neroland.neroeconomy.economy.EconomyStore;
import za.co.neroland.neroeconomy.economy.TransactionType;
import za.co.neroland.neroeconomy.exchange.StockData;
import za.co.neroland.neroeconomy.guard.GuardReport;
import za.co.neroland.neroeconomy.house.HouseData;
import za.co.neroland.neroeconomy.house.HouseRegistry;
import za.co.neroland.neroeconomy.hub.AdminPages;
import za.co.neroland.neroeconomy.simulation.Simulation;
import za.co.neroland.neroeconomy.telemetry.NeroEconomyTelemetry;

/**
 * What an operator can do to the economy, once: {@code /eco admin} and the hub's Admin tab both
 * end here, so the two can never differ. Each call is counted in the store's {@link AdminSection}
 * (counts only: never whose account), and every credit change is a ledger row under its own reason
 * ({@code ADMIN_GRANT}, {@code ADMIN_TAKE}, {@code ADMIN_SET}) with no free text. The callers check
 * the permission level and {@code adminMutationsEnabled}; nothing here logs a player or an amount.
 */
public final class AdminOps {
    /** The most lines of the reload check kept for the Admin tab. */
    public static final int MAX_CHECK_LINES = 20;

    private AdminOps() { }

    private static void count(MinecraftServer server, AdminSection.Action action) {
        EconomyStore.get(server).admin().record(action);
    }

    /** Counts a gallery build. The gallery moves no value; this keeps it on the audited operator path. */
    public static void galleryBuilt(MinecraftServer server) {
        if (server != null) count(server, AdminSection.Action.GALLERY_BUILD);
    }

    /** Counts a gallery clear. */
    public static void galleryCleared(MinecraftServer server) {
        if (server != null) count(server, AdminSection.Action.GALLERY_CLEAR);
    }

    /**
     * Grants, takes or sets credits on one account. Refused while the economy is frozen, and when
     * a grant would overflow or a take is more than the balance.
     *
     * @return whether the ledger accepted it
     */
    public static boolean credits(MinecraftServer server, UUID target, long amount, TransactionType type) {
        if (server == null || target == null || amount < 0L || EconomyHealth.frozen()) return false;
        EconomySavedData data = NeroEconomyCommon.CURRENCY_PROVIDER.activeData();
        long balance = data.getBalance(target, NeroEconomyCommon.CREDITS);
        boolean success = switch (type) {
            case ADMIN_GRANT -> amount >= 1L && Long.MAX_VALUE - balance >= amount
                    && NeroEconomyCommon.CURRENCY_PROVIDER.depositTyped(target, NeroEconomyCommon.CREDITS, amount, type);
            case ADMIN_TAKE -> amount >= 1L && NeroEconomyCommon.CURRENCY_PROVIDER.withdrawTyped(
                    target, NeroEconomyCommon.CREDITS, amount, type);
            case ADMIN_SET -> amount == balance || (amount > balance
                    ? NeroEconomyCommon.CURRENCY_PROVIDER.depositTyped(
                            target, NeroEconomyCommon.CREDITS, amount - balance, type)
                    : NeroEconomyCommon.CURRENCY_PROVIDER.withdrawTyped(
                            target, NeroEconomyCommon.CREDITS, balance - amount, type));
            default -> false;
        };
        if (!success) return false;
        count(server, switch (type) {
            case ADMIN_GRANT -> AdminSection.Action.GRANT;
            case ADMIN_TAKE -> AdminSection.Action.TAKE;
            default -> AdminSection.Action.SET;
        });
        // Neither the account nor the amount is logged; the reason is a fixed word.
        NeroEconomyCommon.LOGGER.debug("[NeroEconomy] Administrative mutation completed (reason={}).", type);
        return true;
    }

    /** Runs simulation steps now. @return how many ran; 0 while frozen */
    public static int simStep(MinecraftServer server, int steps) {
        if (server == null || steps < 1) return 0;
        int ran = Simulation.stepNow(server, steps);
        if (ran > 0) count(server, AdminSection.Action.SIM_STEP);
        return ran;
    }

    /** Closes or opens the market. @return whether anything changed */
    public static boolean setMarketClosed(MinecraftServer server, boolean closed) {
        if (server == null || !EconomyStore.get(server).admin().setMarketFrozen(closed)) return false;
        count(server, closed ? AdminSection.Action.MARKET_CLOSE : AdminSection.Action.MARKET_OPEN);
        // Says what was done and nothing about who did it.
        NeroEconomyCommon.LOGGER.info(closed ? "[NeroEconomy] An operator closed the market."
                : "[NeroEconomy] An operator opened the market.");
        return true;
    }

    /** Gives up on the unreadable store and starts afresh. @return false when the economy is not frozen */
    public static boolean acceptFreshStart(MinecraftServer server) {
        if (server == null || !EconomyStore.acceptFreshStart(server)) return false;
        NeroEconomyCommon.CURRENCY_PROVIDER.refresh(server);
        count(server, AdminSection.Action.FRESH_START);
        return true;
    }

    /**
     * Writes the export file of one data subject: every store, not cut at historyLimitPerPlayer.
     *
     * @return the file written; null when it could not be
     */
    public static Path export(MinecraftServer server, UUID target) {
        if (server == null || target == null) return null;
        Path output;
        try {
            JsonObject json = PlayerDataExport.build(server, target);
            output = LedgerExportFiles.write(server, target, new GsonBuilder().setPrettyPrinting().create().toJson(json));
        } catch (IOException | RuntimeException failure) {
            // The real message holds the path, and the file name is the subject's id: class name only.
            NeroEconomyTelemetry.captureHandledException(LedgerExportFiles.reportable(failure), "data_subject_export");
            return null;
        }
        count(server, AdminSection.Action.EXPORT);
        return output;
    }

    /** Erases everything the economy (and every other mod on Core's erasure) keeps about one data subject. */
    public static void erase(MinecraftServer server, UUID target) {
        if (server == null || target == null) return;
        PlayerDataErasure.erase(server, target);
        count(server, AdminSection.Action.ERASE);
    }

    /**
     * Export and erasure are admin-only, never mint anything, and must still reach a data subject
     * whose world files are already gone: an online player's name, or any well-formed UUID.
     */
    public static UUID subject(MinecraftServer server, String typed) {
        if (server == null || typed == null || typed.isBlank()) return null;
        ServerPlayer online = server.getPlayerList().getPlayerByName(typed.trim());
        if (online != null) return online.getUUID();
        try {
            return UUID.fromString(typed.trim());
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }

    /**
     * Runs the data-pack check again, as {@code /eco admin reload-check} does, and applies the
     * guard's verdict. Data-pack ids, fixed reason words and prices only; nothing about any account.
     */
    public static AdminPages.Check reloadCheck(MinecraftServer server) {
        Map<String, Long> counts = new LinkedHashMap<>();
        List<AdminPages.CheckLine> lines = new ArrayList<>();
        long[] more = {0L};
        int listed = StockData.forServer(server).stocks().size();
        List<StockData.Problem> stockProblems = StockData.problems(server);
        counts.put("stocks", (long) listed);
        counts.put("stock_problems", (long) stockProblems.size());
        for (StockData.Problem problem : stockProblems)
            add(lines, more, new AdminPages.CheckLine(AdminPages.CHECK_FILE, List.of(problem.id(), problem.word()), null));
        HouseRegistry houses = HouseData.forServer(server);
        counts.put("houses", (long) houses.houses().size());
        counts.put("house_problems", (long) houses.problems().size());
        for (HouseRegistry.Problem problem : houses.problems())
            add(lines, more, new AdminPages.CheckLine(AdminPages.CHECK_FILE, List.of(problem.file(), problem.word()), null));

        CommodityData.Check check = CommodityData.check(server);
        CommodityRegistry registry = CommodityData.forServer(server);
        if (!registry.available())
            return new AdminPages.Check(true, false, true, counts, lines, 0L, more[0]);
        CommodityRegistry.LoadReport load = registry.report();
        counts.put("commodities", (long) registry.commodities().size());
        counts.put("files", (long) load.files());
        counts.put("gated", (long) load.gatedFiles());
        counts.put("absent", (long) load.absent());
        counts.put("disabled", (long) load.disabled());
        counts.put("commodity_problems", (long) load.problems().size());
        counts.put("clashes", (long) load.clashes().size());
        counts.put("guard_problems", (long) check.guardProblems().size());
        for (CommodityRegistry.Problem problem : load.problems())
            add(lines, more, problem.entry() < 0
                    ? new AdminPages.CheckLine(AdminPages.CHECK_FILE, List.of(problem.file(), problem.word()), null)
                    : new AdminPages.CheckLine(AdminPages.CHECK_ENTRY, List.of(problem.file(), problem.word()),
                            new long[] { problem.entry() }));
        for (CommodityRegistry.Clash clash : load.clashes())
            add(lines, more, new AdminPages.CheckLine(AdminPages.CHECK_CLASH,
                    List.of(clash.item(), clash.kept(), clash.dropped()), null));
        for (var problem : check.guardProblems())
            add(lines, more, new AdminPages.CheckLine(AdminPages.CHECK_FILE, List.of(problem.file(), problem.word()), null));
        GuardReport report = check.report();
        if (!check.recipesReadable())
            return new AdminPages.Check(true, true, false, counts, lines, 0L, more[0]);
        counts.put("recipes_read", (long) report.recipesRead());
        counts.put("recipes_skipped", (long) report.recipesSkipped());
        counts.put("chains", (long) report.chainsChecked());
        counts.put("depth", (long) report.depth());
        counts.put("buy_off", (long) report.buyOff().size());
        counts.put("sell_off", (long) report.sellOff().size());
        long failures = 0L;
        for (GuardReport.Failure failure : report.failures()) {
            if (failure.kind() == GuardReport.Kind.CLASH) continue;
            failures++;
            add(lines, more, switch (failure.kind()) {
                case SPREAD -> new AdminPages.CheckLine(AdminPages.CHECK_SPREAD,
                        List.of(failure.commodity(), failure.cost(), failure.revenue()), null);
                case REDEEM -> new AdminPages.CheckLine(AdminPages.CHECK_REDEEM,
                        List.of(failure.commodity(), failure.recipe(), failure.cost(), failure.revenue()),
                        new long[] { failure.steps() });
                default -> new AdminPages.CheckLine(AdminPages.CHECK_CHAIN,
                        List.of(failure.commodity(), failure.recipe(), failure.cost(), failure.revenue()),
                        new long[] { failure.steps() });
            });
        }
        return new AdminPages.Check(true, true, true, counts, lines, failures, more[0]);
    }

    private static void add(List<AdminPages.CheckLine> lines, long[] more, AdminPages.CheckLine line) {
        if (lines.size() < MAX_CHECK_LINES) lines.add(line);
        else more[0]++;
    }
}
