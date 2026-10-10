package za.co.neroland.neroeconomy.api;

import java.util.Set;

import net.minecraft.server.MinecraftServer;

import za.co.neroland.neroeconomy.NeroEconomyCommon;
import za.co.neroland.neroeconomy.economy.EconomyHealth;
import za.co.neroland.neroeconomy.sibling.CallerRegistry;

/**
 * The entry point of the cross-mod API. Another mod compiles against this jar ({@code compileOnly})
 * and reaches it from one bridge class behind an {@code isModLoaded("neroeconomy")} check:
 *
 * <pre>{@code
 * static final EconomyCaller CALLER = SiblingEconomy.claim("mymod", Set.of("quest_reward"));
 * EconomyResult result = CALLER.pay(server, player, 50, "quest_reward", "quest:42:stage:3");
 * }</pre>
 */
public final class SiblingEconomy {
    /** Raised by one on every add-only change to this package; 1 is the first cross-mod API. */
    public static final int API_VERSION = 1;

    private SiblingEconomy() { }

    /**
     * Claims the handle for {@code modId} with its closed set of reason codes. Call it from common
     * init. A mod id is {@code [a-z0-9_.-]{1,64}} and must be a loaded mod; {@code neroeconomy} and
     * {@code unknown} cannot be claimed. A reason is {@code [a-z0-9_]{1,32}}; at most 32.
     *
     * @throws IllegalArgumentException for a malformed id or reason
     * @throws IllegalStateException when the id is already claimed, is not a loaded mod, or a server
     *         has already started
     */
    public static EconomyCaller claim(String modId, Set<String> reasons) {
        return new EconomyCaller(modId, CallerRegistry.GAME.claim(modId, reasons));
    }

    /** Whether {@code server} is the running server and the economy is not frozen. */
    public static boolean available(MinecraftServer server) {
        return server != null && server == NeroEconomyCommon.CURRENCY_PROVIDER.serverOrNull() && !EconomyHealth.frozen();
    }
}
