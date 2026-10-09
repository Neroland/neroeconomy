package za.co.neroland.neroeconomy.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import za.co.neroland.neroeconomy.hub.HubAction;
import za.co.neroland.neroeconomy.hub.HubPage;
import za.co.neroland.neroeconomy.hub.JobForm;
import za.co.neroland.neroeconomy.hub.JobsPages;

/**
 * The Jobs tab: contracts, deliveries and bounties anyone may take (a Merchant House's badged
 * with its name), the player's own jobs, and the "Post" form. Nobody who posted or took a job is
 * shown; time left is the server's, counted down here. Posting restates the escrow before the
 * confirm: the payout leaves the balance now and comes back if the job is withdrawn or expires.
 *
 * <pre>
 *   20   Contracts (8, 56) | Deliveries (66, 58) | Bounties (126, 52) | Mine (180, 32) | Post (214, 34)
 *   36   how many jobs the player has open, of how many
 *   LISTS  50: six rows of 20px | 174: page buttons | the row's action (150, 174, 98x14) |
 *          Release (150, 192, 98x14) | 194: the confirm lines (8, 138 wide)
 *   POST   50: kind (8, 50, 110x14) | 68: what (8, 68, 150) + From hand / Next creature (162, 68, 86) |
 *          86: how many (8, 86, 50), payout (62, 86, 80) | 104: Deliver here / elsewhere (8, 104, 100),
 *          x y z | 122: dimension (8, 122, 150) | 140: the check | 152: the escrow | 194: Post (8, 194, 80)
 * </pre>
 */
final class HubJobsView extends HubTabView {
    private JobForm.Kind kind = JobForm.Kind.CONTRACT;
    private boolean elsewhere;
    private int dimensionIndex;
    private int entityIndex = -1;
    private EditBox target;
    private EditBox quantity;
    private EditBox payout;
    private EditBox x;
    private EditBox y;
    private EditBox z;
    private Button primary;
    private Button release;
    private Button post;

    HubJobsView(EconomyHubScreen screen, HubIntents intents, HubPage page) {
        super(screen, intents, page);
        this.listX = 8;
        this.listY = 50;
        this.listW = 240;
        this.rowH = 20;
        layout();
    }

    private HubAction.JobsView view() { return HubAction.JobsView.byOrdinal(header(JobsPages.VIEW)); }

    private void layout() { this.visibleRows = view() == HubAction.JobsView.POST ? 0 : JobsPages.JOB_ROWS; }

    @Override
    void accept(HubPage next) {
        HubAction.JobsView was = view();
        boolean bounties = header(JobsPages.PLAYER_BOUNTIES) == 1L;
        super.accept(next);
        if (was != view() || bounties != (header(JobsPages.PLAYER_BOUNTIES) == 1L)) {
            this.selected = null;
            disarm();
            layout();
            this.screen.rebuild();
        }
    }

    private void tab(HubAction.JobsView target, String key, int left, int w) {
        Button button = this.screen.button(Component.translatable(key), key + ".tooltip", left, 20, w, 14,
                () -> this.intents.send(HubAction.JOBS_VIEW, null, target.ordinal(), 0L, ""));
        button.active = view() != target;
    }

    @Override
    void init() {
        layout();
        tab(HubAction.JobsView.CONTRACTS, "gui.neroeconomy.hub.jobs.contracts", 8, 56);
        tab(HubAction.JobsView.DELIVERIES, "gui.neroeconomy.hub.jobs.deliveries", 66, 58);
        tab(HubAction.JobsView.BOUNTIES, "gui.neroeconomy.hub.jobs.bounties", 126, 52);
        tab(HubAction.JobsView.MINE, "gui.neroeconomy.hub.jobs.mine", 180, 32);
        tab(HubAction.JobsView.POST, "gui.neroeconomy.hub.jobs.post", 214, 34);
        this.primary = null;
        this.release = null;
        this.post = null;
        if (view() != HubAction.JobsView.POST) {
            pageButtons(8, 174);
            this.primary = this.screen.button(Component.empty(), "gui.neroeconomy.hub.jobs.action.tooltip", 150, 174, 98,
                    14, this::act);
            this.release = this.screen.button(Component.translatable("gui.neroeconomy.hub.jobs.release"),
                    "gui.neroeconomy.hub.jobs.release.tooltip", 150, 192, 98, 14, () -> {
                        HubPage.Row row = selectedRow();
                        if (row != null) this.intents.send(HubAction.JOB_RELEASE, row.id(), 0L, 0L, "");
                    });
            return;
        }
        if (this.kind == JobForm.Kind.PLAYER_BOUNTY && header(JobsPages.PLAYER_BOUNTIES) != 1L) this.kind = JobForm.Kind.BOUNTY;
        this.screen.button(Component.translatable("gui.neroeconomy.hub.jobs.kind." + this.kind.name().toLowerCase(Locale.ROOT)),
                "gui.neroeconomy.hub.jobs.kind.tooltip", 8, 50, 110, 14, () -> {
                    JobForm.Kind[] all = JobForm.Kind.values();
                    JobForm.Kind next = all[(this.kind.ordinal() + 1) % all.length];
                    if (next == JobForm.Kind.PLAYER_BOUNTY && header(JobsPages.PLAYER_BOUNTIES) != 1L) next = all[0];
                    this.kind = next;
                    if (this.target != null) this.target.setValue("");
                    disarm();
                    this.screen.rebuild();
                });
        this.target = this.screen.box(this.target, targetKey(), 8, 68, 150, JobForm.MAX_TARGET);
        if (this.kind == JobForm.Kind.CONTRACT || this.kind == JobForm.Kind.DELIVERY) {
            this.screen.button(Component.translatable("gui.neroeconomy.hub.jobs.from_hand"),
                    "gui.neroeconomy.hub.jobs.from_hand.tooltip", 162, 68, 86, 14, this::fromHand);
        } else if (this.kind == JobForm.Kind.BOUNTY) {
            this.screen.button(Component.translatable("gui.neroeconomy.hub.jobs.next_creature"),
                    "gui.neroeconomy.hub.jobs.next_creature.tooltip", 162, 68, 86, 14, this::nextCreature);
        }
        this.quantity = this.screen.box(this.quantity, "gui.neroeconomy.hub.jobs.quantity", 8, 86, 50, 4);
        this.payout = this.screen.box(this.payout, "gui.neroeconomy.hub.jobs.payout", 62, 86, 80, 13);
        if (this.kind == JobForm.Kind.DELIVERY) {
            this.screen.button(Component.translatable(this.elsewhere ? "gui.neroeconomy.hub.jobs.elsewhere"
                    : "gui.neroeconomy.hub.jobs.here"), "gui.neroeconomy.hub.jobs.where.tooltip", 8, 104, 100, 14, () -> {
                        this.elsewhere = !this.elsewhere;
                        disarm();
                        this.screen.rebuild();
                    });
            if (this.elsewhere) {
                this.x = this.screen.box(this.x, "gui.neroeconomy.hub.jobs.x", 112, 104, 44, 9);
                this.y = this.screen.box(this.y, "gui.neroeconomy.hub.jobs.y", 160, 104, 34, 5);
                this.z = this.screen.box(this.z, "gui.neroeconomy.hub.jobs.z", 198, 104, 50, 9);
                this.screen.button(Component.literal(dimension()), "gui.neroeconomy.hub.jobs.dimension.tooltip", 8, 122,
                        150, 14, () -> {
                            this.dimensionIndex++;
                            disarm();
                            this.screen.rebuild();
                        });
            }
        }
        this.post = this.screen.button(Component.empty(), "gui.neroeconomy.hub.jobs.post_button.tooltip", 8, 194, 80, 14,
                this::post);
    }

    private String targetKey() {
        return switch (this.kind) {
            case CONTRACT, DELIVERY -> "gui.neroeconomy.hub.jobs.target_item";
            case BOUNTY -> "gui.neroeconomy.hub.jobs.target_entity";
            default -> "gui.neroeconomy.hub.jobs.target_player";
        };
    }

    // --- the post form -----------------------------------------------------------------------

    /** The dimensions this client knows of, as the dimension button cycles through them. */
    private static List<String> dimensions() {
        List<String> out = new ArrayList<>();
        var connection = Minecraft.getInstance().getConnection();
        if (connection != null) for (var key : connection.levels()) out.add(key.identifier().toString());
        out.sort(String::compareTo);
        if (out.isEmpty()) out.add("minecraft:overworld");
        return out;
    }

    private String dimension() {
        List<String> all = dimensions();
        return all.get(Math.floorMod(this.dimensionIndex, all.size()));
    }

    private void fromHand() {
        if (this.target == null) return;
        ItemStack held = held();
        if (!held.isEmpty()) this.target.setValue(BuiltInRegistries.ITEM.getKey(held.getItem()).toString());
    }

    /** Steps through the creatures a bounty can be on: every monster and animal type there is. */
    private void nextCreature() {
        List<String> ids = new ArrayList<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
            // The player type is never a bounty target; player bounties are their own kind.
            if (id.equals("minecraft:player") || type.getCategory() == MobCategory.MISC) continue;
            ids.add(id);
        }
        ids.sort(String::compareTo);
        if (ids.isEmpty() || this.target == null) return;
        this.entityIndex = Math.floorMod(this.entityIndex + 1, ids.size());
        this.target.setValue(ids.get(this.entityIndex));
    }

    private String destination() {
        if (this.kind != JobForm.Kind.DELIVERY) return "";
        if (!this.elsewhere) return JobForm.HERE;
        return dimension() + "|" + value(this.x) + "|" + value(this.y) + "|" + value(this.z);
    }

    private JobForm form() {
        char letter = switch (this.kind) {
            case CONTRACT -> 'C';
            case DELIVERY -> 'D';
            case BOUNTY -> 'B';
            default -> 'P';
        };
        return JobForm.parse(letter + ";" + value(this.target) + ";" + value(this.quantity) + ";" + destination());
    }

    private void post() {
        JobForm form = form();
        long amount = number(this.payout);
        if (form == null || amount <= 0L) return;
        press("post|" + form.encode() + "|" + amount, () -> this.intents.send(HubAction.JOB_POST, null, amount, 0L,
                form.encode()));
    }

    // --- acting on a row ---------------------------------------------------------------------

    private enum Act { NONE, CLAIM, HAND_IN, WITHDRAW }

    private static Act act(HubPage.Row row) {
        if (row == null) return Act.NONE;
        boolean open = "command.neroeconomy.job_state.open".equals(row.arg(2));
        if (row.flag(JobsPages.FLAG_MINE)) return open ? Act.WITHDRAW : Act.NONE;
        if (row.flag(JobsPages.FLAG_CLAIMED_BY_ME))
            return "command.neroeconomy.job_type.bounty".equals(row.title()) ? Act.NONE : Act.HAND_IN;
        return open ? Act.CLAIM : Act.NONE;
    }

    private void act() {
        HubPage.Row row = selectedRow();
        switch (act(row)) {
            case CLAIM -> this.intents.send(HubAction.JOB_CLAIM, row.id(), 0L, 0L, "");
            case HAND_IN -> press("hand|" + row.id(), () -> this.intents.send(HubAction.JOB_FULFIL, row.id(), 0L, 0L, ""));
            case WITHDRAW -> press("withdraw|" + row.id(), () -> this.intents.send(HubAction.JOB_CANCEL, row.id(), 0L, 0L, ""));
            default -> { }
        }
    }

    @Override
    void tick() {
        HubPage.Row row = selectedRow();
        if (this.primary != null) {
            Act act = act(row);
            if (!this.armedFor.isEmpty() && (row == null || !this.armedFor.endsWith(String.valueOf(row.id())))) disarm();
            this.primary.active = act != Act.NONE;
            String key = switch (act) {
                case CLAIM -> "gui.neroeconomy.hub.jobs.claim";
                case HAND_IN -> "gui.neroeconomy.hub.jobs.hand_in";
                case WITHDRAW -> "gui.neroeconomy.hub.jobs.withdraw";
                default -> "gui.neroeconomy.hub.jobs.no_action";
            };
            label(this.primary, key, row != null && (armed("hand|" + row.id()) || armed("withdraw|" + row.id())));
            this.release.active = row != null && row.flag(JobsPages.FLAG_CLAIMED_BY_ME);
        }
        if (this.post != null) {
            JobForm form = form();
            long amount = number(this.payout);
            String what = form == null || amount <= 0L ? "" : "post|" + form.encode() + "|" + amount;
            if (!this.armedFor.isEmpty() && !armed(what)) disarm();
            this.post.active = !what.isEmpty();
            label(this.post, "gui.neroeconomy.hub.jobs.post_button", !what.isEmpty() && armed(what));
        }
    }

    // --- drawing -----------------------------------------------------------------------------

    private Component target(HubPage.Row row) { return itemOrKey(row.arg(0), row.flag(JobsPages.FLAG_ITEM)); }

    @Override
    void draw(GuiGraphicsExtractor g) {
        EconomyHubScreen s = this.screen;
        s.text(g, Component.translatable("gui.neroeconomy.hub.jobs.open", header(JobsPages.OPEN_MINE),
                header(JobsPages.MAX_JOBS)), 8, 37, 240, TexturedContainerScreen.SUBTLE);
        if (view() == HubAction.JobsView.POST) {
            drawPost(g);
            return;
        }
        drawRows(g, "gui.neroeconomy.hub.jobs.empty", (row, top) -> {
            s.icon(g, row.icon(), 10, top + 2);
            boolean picked = row.id().equals(this.selected);
            Component payout = Component.translatable("gui.neroeconomy.hub.credits", row.num(1));
            int payoutWidth = s.width(payout);
            s.text(g, Component.translatable("gui.neroeconomy.hub.jobs.row", Component.translatable(row.title()),
                    row.num(0), target(row)), 30, top + 2, 212 - payoutWidth,
                    picked ? TexturedContainerScreen.SELECTED : TexturedContainerScreen.TITLE);
            s.textRight(g, payout, 246, top + 2, TexturedContainerScreen.CREDIT);
            Component who = row.flag(JobsPages.FLAG_HOUSE) ? Component.translatable(row.arg(1))
                    : Component.translatable(row.flag(JobsPages.FLAG_MINE) ? "gui.neroeconomy.hub.jobs.yours"
                            : "gui.neroeconomy.hub.jobs.player");
            Component second = row.arg(3).isEmpty()
                    ? Component.translatable("gui.neroeconomy.hub.jobs.row2", who, Component.translatable(row.arg(2)),
                            timeLeft(row.num(2)))
                    : Component.translatable("gui.neroeconomy.hub.jobs.row2_delivery", who,
                            Component.translatable(row.arg(2)), timeLeft(row.num(2)), row.num(3), row.num(4), row.num(5),
                            Component.literal(row.arg(3)));
            s.text(g, second, 30, top + 11, 216, row.flag(JobsPages.FLAG_HOUSE) ? EconomyHubScreen.WARNING
                    : TexturedContainerScreen.SUBTLE);
        });
        drawPageText(g, 54, 177);
        HubPage.Row row = selectedRow();
        Component line;
        int colour = EconomyHubScreen.WARNING;
        if (row != null && armed("hand|" + row.id()))
            line = Component.translatable("gui.neroeconomy.hub.jobs.confirm_hand_in", row.num(0), target(row), row.num(1));
        else if (row != null && armed("withdraw|" + row.id()))
            line = Component.translatable("gui.neroeconomy.hub.jobs.confirm_withdraw", row.num(1));
        else {
            colour = TexturedContainerScreen.SUBTLE;
            line = Component.translatable(row == null ? "gui.neroeconomy.hub.jobs.pick" : switch (act(row)) {
                case CLAIM -> "gui.neroeconomy.hub.jobs.claim_hint";
                case HAND_IN -> "gui.neroeconomy.hub.jobs.hand_in_hint";
                case WITHDRAW -> "gui.neroeconomy.hub.jobs.withdraw_hint";
                default -> "gui.neroeconomy.hub.jobs.nothing_hint";
            });
        }
        s.wrapped(g, line, 8, 194, 138, colour);
    }

    private void drawPost(GuiGraphicsExtractor g) {
        EconomyHubScreen s = this.screen;
        if (this.kind == JobForm.Kind.DELIVERY && !this.elsewhere)
            s.text(g, Component.translatable("gui.neroeconomy.hub.jobs.here_at", header(JobsPages.HERE_X),
                    header(JobsPages.HERE_Y), header(JobsPages.HERE_Z),
                    Component.literal(this.page.key(JobsPages.HERE_DIMENSION))), 112, 107, 136,
                    TexturedContainerScreen.SUBTLE);
        String typed = value(this.target);
        Component check;
        if (typed.isEmpty()) check = Component.translatable("gui.neroeconomy.hub.jobs.target_hint");
        else if (this.kind == JobForm.Kind.CONTRACT || this.kind == JobForm.Kind.DELIVERY) {
            Identifier id = typed.startsWith("#") ? null : Identifier.tryParse(typed);
            var item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            check = typed.startsWith("#") ? Component.translatable("gui.neroeconomy.hub.jobs.target_tag", typed)
                    : item == null || item == Items.AIR ? Component.translatable("gui.neroeconomy.hub.jobs.target_unknown")
                    : Component.translatable("gui.neroeconomy.hub.jobs.target_ok", s.itemName(typed));
        } else if (this.kind == JobForm.Kind.BOUNTY) {
            Identifier id = Identifier.tryParse(typed);
            var type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
            check = type == null ? Component.translatable("gui.neroeconomy.hub.jobs.target_unknown")
                    : Component.translatable("gui.neroeconomy.hub.jobs.target_ok", type.getDescription());
        } else {
            check = Component.translatable("gui.neroeconomy.hub.jobs.target_player_hint");
        }
        s.text(g, check, 8, 140, 240, TexturedContainerScreen.SUBTLE);
        JobForm form = form();
        long amount = number(this.payout);
        if (form == null || amount <= 0L) {
            s.wrapped(g, Component.translatable("gui.neroeconomy.hub.jobs.form_hint", header(JobsPages.HOURS)), 8, 152,
                    240, TexturedContainerScreen.SUBTLE);
            return;
        }
        s.wrapped(g, Component.translatable("gui.neroeconomy.hub.jobs.escrow", amount, header(JobsPages.HOURS)), 8, 152,
                240, TexturedContainerScreen.TITLE);
        String what = "post|" + form.encode() + "|" + amount;
        if (armed(what)) s.wrapped(g, Component.translatable("gui.neroeconomy.hub.jobs.confirm_post", amount,
                form.quantity()), 92, 194, 156, EconomyHubScreen.WARNING);
    }

    @Override
    Component tooltipAt(int x, int y) {
        HubPage.Row row = rowAt(x, y);
        if (row == null) return null;
        return Component.translatable(row.flag(JobsPages.FLAG_HOUSE) ? "gui.neroeconomy.hub.jobs.house.tooltip"
                : "gui.neroeconomy.hub.jobs.row.tooltip");
    }
}
