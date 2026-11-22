package za.co.neroland.neroeconomy.data;

import java.util.UUID;

import com.google.gson.JsonElement;

import net.minecraft.server.MinecraftServer;

import za.co.neroland.neroeconomy.NeroEconomyCommon;

/**
 * NeroEconomy's part of Neroland Core's per-player data export ({@code /neroland data exportme}).
 *
 * <p>Backed by the same document as NeroEconomy's own export ({@link PlayerDataExport#build}), so the two
 * can never disagree. Built on request and handed to Core; never logged and never written to disk here
 * (the mod's own file export is a separate, operator-run command).</p>
 */
public final class CoreDataExport {
    private CoreDataExport() { }

    /** Register with Core. Call once at common init, next to the erasers. */
    public static void register() {
        za.co.neroland.nerolandcore.data.PlayerDataExport.register(NeroEconomyCommon.MOD_ID, CoreDataExport::export);
    }

    static JsonElement export(MinecraftServer server, UUID player) {
        return PlayerDataExport.build(server, player);
    }
}
