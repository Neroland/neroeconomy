package za.co.neroland.neroeconomy;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

public final class LegacyGameBootstrap implements BeforeAllCallback {
    @Override
    public void beforeAll(ExtensionContext context) {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }
}
