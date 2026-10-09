package za.co.neroland.neroeconomy.house;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import za.co.neroland.neroeconomy.simulation.SimPipeline;
import za.co.neroland.neroeconomy.simulation.SimTesting;

/** The Houses' simulation handler: not housekeeping, and its words for players and operators exist. */
class HouseStepTest {
    private HouseWorld houses;

    @AfterEach void close() { if (houses != null) houses.world.close(); }

    @Test void theHandlerOnlyRunsWithTheAutonomousEconomyOn() {
        houses = HouseWorld.standard();
        SimPipeline pipeline = new SimPipeline();
        pipeline.register(new HouseStep(context -> houses.env()));
        long now = houses.world.now();
        long step = now / HouseWorld.STEP;
        assertFalse(new HouseStep(context -> null).housekeeping());
        pipeline.run(SimTesting.context(houses.world.store, "", step, HouseWorld.STEP, now, false), null);
        assertNull(houses.ask(HouseWorld.WHEAT));
        assertEquals(0, houses.section().size());
        pipeline.run(SimTesting.context(houses.world.store, "", step, HouseWorld.STEP, now, true), null);
        assertNotNull(houses.ask(HouseWorld.WHEAT));
        assertEquals(2, houses.section().size());
        // An env that answers null skips the step.
        new HouseStep(context -> null).run(SimTesting.context(houses.world.store, HouseStep.NAME, step + 1L,
                HouseWorld.STEP, now, true), step + 1L);
        assertTrue(houses.world.invariants());
    }

    @Test void everyHouseWordHasAnEnglishString() throws IOException {
        JsonObject lang;
        try (InputStream stream = HouseStepTest.class.getResourceAsStream("/assets/neroeconomy/lang/en_us.json")) {
            Assumptions.assumeTrue(stream != null, "en_us.json is not on this test classpath");
            lang = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        for (String key : List.of("command.neroeconomy.houses.header", "command.neroeconomy.houses.row",
                "command.neroeconomy.houses.top", "command.neroeconomy.houses.quiet", "command.neroeconomy.houses.retired",
                "command.neroeconomy.houses.off", "command.neroeconomy.reload_check.houses",
                "message.neroeconomy.order.daily_limit", "command.neroeconomy.transaction_type.house_funding",
                "command.neroeconomy.transaction_type.house_sweep"))
            assertTrue(lang.has(key), key);
        for (String id : List.of("general", "growers", "masons", "hunters", "smiths", "artisans", "engineers", "settlers",
                "starport"))
            assertTrue(lang.has("house.neroeconomy." + id), id);
    }
}
