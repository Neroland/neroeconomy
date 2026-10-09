package za.co.neroland.neroeconomy.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import za.co.neroland.neroeconomy.hub.HubAction;
import za.co.neroland.neroeconomy.hub.HubPage;
import za.co.neroland.neroeconomy.hub.HubPages;

/**
 * The Vault tab: how full the vault is, what is in it, and the two ways out — the vault's own
 * window (only at a Bank Terminal) and "Collect to inventory".
 *
 * <pre>
 *   20   stacks used / cap               31   over-the-limit warning, or what the vault is for
 *   42   eight rows of 18px: icon (10), name (30), count right-aligned at 246
 *   190  Open vault (8, 190, 84x14) | Collect (96, 190, 96x14) | previous / next (204, 190)
 *   208  page text
 * </pre>
 */
final class HubVaultView extends HubTabView {
    private Button open;
    private Button collect;

    HubVaultView(EconomyHubScreen screen, HubIntents intents, HubPage page) {
        super(screen, intents, page);
        this.listX = 8;
        this.listY = 42;
        this.listW = 240;
        this.rowH = 18;
        this.visibleRows = HubPages.VAULT_ROWS;
    }

    @Override
    void init() {
        this.open = this.screen.button(Component.translatable("gui.neroeconomy.hub.vault.open"),
                "gui.neroeconomy.hub.vault.open.tooltip", 8, 190, 84, 14,
                () -> this.intents.send(HubAction.VAULT_OPEN, null, 0L, 0L, ""));
        this.collect = this.screen.button(Component.translatable("gui.neroeconomy.hub.vault.collect"),
                "gui.neroeconomy.hub.vault.collect.tooltip", 96, 190, 96, 14,
                () -> this.intents.send(HubAction.VAULT_COLLECT, null, 0L, 0L, ""));
        pageButtons(204, 190);
    }

    @Override
    void tick() {
        if (this.open != null) this.open.active = this.page.number(HubPages.AT_BANK, 0L) == 1L;
        if (this.collect != null) this.collect.active = this.page.number(HubPages.USED, 0L) > 0L;
    }

    private boolean over() { return this.page.number(HubPages.USED, 0L) > this.page.number(HubPages.CAP, 0L); }

    @Override
    void draw(GuiGraphicsExtractor g) {
        EconomyHubScreen s = this.screen;
        s.text(g, Component.translatable("gui.neroeconomy.hub.vault.used", this.page.number(HubPages.USED, 0L),
                this.page.number(HubPages.CAP, 0L)), 8, 20, 240, over() ? EconomyHubScreen.BAD
                : TexturedContainerScreen.CREDIT);
        s.text(g, Component.translatable(over() ? "gui.neroeconomy.hub.vault.over_cap"
                : this.page.number(HubPages.AT_BANK, 0L) == 1L ? "gui.neroeconomy.hub.vault.hint"
                : "gui.neroeconomy.hub.vault.hint_elsewhere"), 8, 31, 240,
                over() ? EconomyHubScreen.BAD : TexturedContainerScreen.SUBTLE);
        if (this.page.rows().isEmpty())
            s.wrapped(g, Component.translatable("gui.neroeconomy.hub.vault.empty"), 10, 46, 236,
                    TexturedContainerScreen.SUBTLE);
        for (int index = 0; index < this.page.rows().size() && index < this.visibleRows; index++) {
            HubPage.Row row = this.page.rows().get(index);
            int y = this.listY + index * this.rowH;
            s.icon(g, row.icon(), 10, y + 1);
            Component name = row.flag(HubPage.FLAG_C)
                    ? Component.translatable("gui.neroeconomy.hub.modified", s.itemName(row.icon()))
                    : s.itemName(row.icon());
            s.text(g, name, 30, y + 5, 170, TexturedContainerScreen.TITLE);
            s.textRight(g, Component.translatable("gui.neroeconomy.hub.vault.count", row.num(0)), 246, y + 5,
                    TexturedContainerScreen.SUBTLE);
        }
        drawPageText(g, 204, 208);
    }

    @Override
    Component tooltipAt(int x, int y) {
        if (y >= 19 && y < 40 && x >= 8 && x < 248) return Component.translatable("gui.neroeconomy.hub.vault.used.tooltip");
        HubPage.Row row = rowAt(x, y);
        if (row != null && row.flag(HubPage.FLAG_C)) return Component.translatable("gui.neroeconomy.hub.modified.tooltip");
        return null;
    }

    /** Vault lines are read here and handled in the vault's own window. */
    @Override
    boolean click(int x, int y) { return false; }
}
