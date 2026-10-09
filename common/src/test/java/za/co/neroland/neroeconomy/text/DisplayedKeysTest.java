package za.co.neroland.neroeconomy.text;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.economy.BudgetKind;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.economy.TransactionType;
import za.co.neroland.neroeconomy.market.PostResult;
import za.co.neroland.neroeconomy.menu.BankTransferRules;
import za.co.neroland.neroeconomy.obligation.JobState;
import za.co.neroland.neroeconomy.obligation.JobTargets;
import za.co.neroland.neroeconomy.obligation.JobType;

/** Nothing a player is shown falls back to an enum name or a raw token: every one has wording. */
class DisplayedKeysTest {
    @Test void everyDisplayedEnumAndTokenHasAnEnglishString() throws IOException {
        JsonObject lang;
        try (InputStream stream = DisplayedKeysTest.class.getResourceAsStream("/assets/neroeconomy/lang/en_us.json")) {
            // The lang file reaches the test classpath through the loader node's resources.
            assumeTrue(stream != null, "en_us.json is not on this test classpath");
            lang = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        List<String> keys = new ArrayList<>();
        for (JobState value : JobState.values()) keys.add(value.translationKey());
        for (JobType value : JobType.values())
            keys.add("command.neroeconomy.job_type." + value.name().toLowerCase(Locale.ROOT));
        for (TransactionType value : TransactionType.values())
            keys.add("command.neroeconomy.transaction_type." + value.name().toLowerCase(Locale.ROOT));
        for (PostResult value : PostResult.values()) keys.add(value.translationKey());
        for (BankTransferRules.Check value : BankTransferRules.Check.values())
            if (value != BankTransferRules.Check.OK) keys.add(value.translationKey());
        keys.add(JobTargets.playerLabelKey(JobTargets.PLAYER_HIDDEN));
        keys.add(JobTargets.playerLabelKey(JobTargets.PLAYER_YOU));
        keys.add(JobTargets.playerLabelKey(JobTargets.PLAYER_YOURS));
        // The Reserve budgets an operator is shown by name, and the one message a frozen economy gives.
        for (BudgetKind value : BudgetKind.values())
            if (value != BudgetKind.UNBUDGETED)
                keys.add("command.neroeconomy.budget." + value.name().toLowerCase(Locale.ROOT));
        keys.add(EconomyHealth.FROZEN_KEY);
        keys.addAll(List.of("command.neroeconomy.funds_locked", "command.neroeconomy.check.passed",
                "command.neroeconomy.check.failed", "command.neroeconomy.health.frozen",
                "command.neroeconomy.health.healthy", "command.neroeconomy.health.schema",
                "command.neroeconomy.health.sections", "command.neroeconomy.health.no_sections",
                "command.neroeconomy.health.how_to_accept", "command.neroeconomy.health.not_frozen",
                "command.neroeconomy.health.accepted", "command.neroeconomy.supply.header",
                "command.neroeconomy.supply.balances", "command.neroeconomy.supply.today",
                "command.neroeconomy.supply.all_time", "command.neroeconomy.supply.minted_reason",
                "command.neroeconomy.supply.burned_reason", "command.neroeconomy.supply.budget",
                "command.neroeconomy.supply.obligations", "command.neroeconomy.supply.check_supply",
                "command.neroeconomy.supply.check_escrow", "gui.neroeconomy.purchase.surcharge",
                "gui.neroeconomy.purchase.discount", "gui.neroeconomy.purchase.discount_skipped",
                "item.neroeconomy.credit_chip.reserve_empty", "item.neroeconomy.credit_chip.refused"));
        // The vault screen and the simulation report.
        keys.addAll(List.of("container.neroeconomy.vault", "gui.neroeconomy.vault.count",
                "gui.neroeconomy.vault.count.tooltip", "gui.neroeconomy.vault.empty", "gui.neroeconomy.vault.hidden",
                "gui.neroeconomy.vault.hidden.tooltip", "gui.neroeconomy.vault.hint",
                "gui.neroeconomy.vault.locked.tooltip", "gui.neroeconomy.vault.over_cap",
                "gui.neroeconomy.vault.over_cap.tooltip", "gui.neroeconomy.vault.status.tooltip",
                "gui.neroeconomy.purchase.to_vault", "command.neroeconomy.sim.header",
                "command.neroeconomy.sim.step_minutes", "command.neroeconomy.sim.step_fast",
                "command.neroeconomy.sim.last_step", "command.neroeconomy.sim.not_started",
                "command.neroeconomy.sim.behind", "command.neroeconomy.sim.catching_up",
                "command.neroeconomy.sim.up_to_date", "command.neroeconomy.sim.autonomous_on",
                "command.neroeconomy.sim.autonomous_off", "command.neroeconomy.sim.handlers",
                "command.neroeconomy.sim.stepped"));
        keys.add(TimeText.DAYS_HOURS);
        keys.add(TimeText.HOURS_MINUTES);
        keys.add(TimeText.MINUTES_SECONDS);
        List<String> missing = keys.stream().filter(key -> !lang.has(key)).toList();
        assertTrue(missing.isEmpty(), "missing lang keys: " + missing);
    }
}
