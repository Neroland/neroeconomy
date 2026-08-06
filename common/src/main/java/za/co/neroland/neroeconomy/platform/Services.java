package za.co.neroland.neroeconomy.platform;

import java.util.ServiceLoader;

import za.co.neroland.neroeconomy.NeroEconomyCommon;

/** Eagerly resolved loader seams; common init touches both before gameplay starts. */
public final class Services {
    public static final PlatformInfo PLATFORM = load(PlatformInfo.class);
    public static final NetworkPlatform NETWORK = load(NetworkPlatform.class);

    private Services() {
    }

    private static <T> T load(Class<T> type) {
        T service = ServiceLoader.load(type).findFirst().orElseThrow(() ->
                new IllegalStateException("Missing NeroEconomy service " + type.getName()));
        NeroEconomyCommon.LOGGER.debug("Loaded {} -> {}", type.getSimpleName(), service.getClass().getName());
        return service;
    }
}
