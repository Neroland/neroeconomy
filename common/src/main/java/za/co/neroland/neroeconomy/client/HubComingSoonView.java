package za.co.neroland.neroeconomy.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import za.co.neroland.neroeconomy.hub.HubPage;
import za.co.neroland.neroeconomy.hub.HubTab;

/**
 * A tab a later part of this release builds. It says so plainly and says what works meanwhile, so
 * the empty page still tells the player what to do next.
 */
final class HubComingSoonView extends HubTabView {
    private final HubTab tab;

    HubComingSoonView(EconomyHubScreen screen, HubIntents intents, HubPage page, HubTab tab) {
        super(screen, intents, page);
        this.tab = tab;
    }

    @Override
    void init() { }

    @Override
    void draw(GuiGraphicsExtractor g) {
        this.screen.icon(g, this.tab.icon(), 8, 22);
        this.screen.text(g, Component.translatable("gui.neroeconomy.hub.coming_soon"), 30, 26, 218,
                TexturedContainerScreen.TITLE);
        this.screen.wrapped(g, Component.translatable("gui.neroeconomy.hub.coming_soon." + this.tab.id()), 8, 46, 240,
                TexturedContainerScreen.SUBTLE);
    }

    @Override
    boolean click(int x, int y) { return false; }
}
