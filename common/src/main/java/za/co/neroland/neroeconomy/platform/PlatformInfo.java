package za.co.neroland.neroeconomy.platform;

import java.util.List;

/** Loader facts plus lifecycle/command registration owned by NeroEconomy's mod container. */
public interface PlatformInfo {
    void register(Object loaderBus);
    String getPlatformName();
    boolean isDevelopmentEnvironment();
    String getModVersion();
    List<String> getLoadedModIds();

    default boolean isModLoaded(String modId) {
        return getLoadedModIds().stream().anyMatch(entry -> entry.startsWith(modId + " "));
    }
}
