package za.co.neroland.neroeconomy.hub;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Jobs tab's pages: contracts, deliveries and bounties anyone may take, the viewer's own posted
 * and claimed jobs, and the "Post" form. A job shows what it wants and what it pays; a Merchant
 * House's job carries the House's name as its badge. Nobody who posted or took a job is ever on a
 * row, and a bounty on a player shows only a fixed phrase ("a player", "you", "the player you
 * named"), never the stored target. Pure Java.
 */
public final class JobsPages {
    public static final int JOB_ROWS = 6;

    // --- header names ------------------------------------------------------------------------
    public static final String VIEW = "view";
    public static final String OPEN_MINE = "open_mine";
    public static final String MAX_JOBS = "max_jobs";
    public static final String HOURS = "hours";
    public static final String PLAYER_BOUNTIES = "player_bounties";
    public static final String HERE_X = "here_x";
    public static final String HERE_Y = "here_y";
    public static final String HERE_Z = "here_z";
    /** Header key: the dimension the viewer stands in. */
    public static final String HERE_DIMENSION = "here_dim";

    /** Row flags. */
    public static final int FLAG_HOUSE = HubPage.FLAG_A;
    public static final int FLAG_MINE = HubPage.FLAG_B;
    public static final int FLAG_CLAIMED_BY_ME = HubPage.FLAG_C;
    public static final int FLAG_ITEM = HubPage.FLAG_D;

    private JobsPages() { }

    /**
     * One job as the viewer may see it.
     *
     * @param typeKey   lang key of its kind (contract, delivery, bounty)
     * @param icon      an item standing for what it wants
     * @param target    what it wants: an item id ({@code item} set), a {@code #tag}, or a lang key
     *                  (an entity's name, or a fixed phrase for a player bounty)
     * @param houseKey  the posting Merchant House's name key; empty for a player's job
     * @param stateKey  lang key of its state
     * @param dimension a delivery's destination dimension id; empty otherwise
     */
    public record JobLine(UUID id, String typeKey, String icon, String target, boolean item, String houseKey,
            String stateKey, long quantity, long payout, long leftMillis, boolean mine, boolean claimedByMe,
            String dimension, long x, long y, long z) { }

    /**
     * @param openMine       how many unsettled jobs the viewer has posted
     * @param pvpBounties    whether this viewer may post a bounty on a player
     * @param hereDimension  where the viewer stands, for "deliver here"
     */
    public record JobsView(HubAction.JobsView view, List<JobLine> jobs, int openMine, int maxJobs, int hours,
            boolean pvpBounties, String hereDimension, long hereX, long hereY, long hereZ) {
        public JobsView {
            jobs = jobs == null ? List.of() : List.copyOf(jobs);
            hereDimension = hereDimension == null ? "" : hereDimension;
        }
    }

    public static HubPages.Built build(JobsView view, long requestedPage) {
        List<HubPage.Row> rows = new ArrayList<>();
        Map<UUID, HubPages.RowRef> refs = new LinkedHashMap<>();
        int pages = 1;
        int page = 0;
        if (view.view() != HubAction.JobsView.POST) {
            List<JobLine> lines = view.jobs();
            pages = HubPage.pages(lines.size(), JOB_ROWS);
            page = HubPage.clampPage(requestedPage, pages);
            int from = page * JOB_ROWS;
            for (JobLine line : lines.subList(Math.min(lines.size(), from), Math.min(lines.size(), from + JOB_ROWS))) {
                int flags = (line.houseKey().isEmpty() ? 0 : FLAG_HOUSE) | (line.mine() ? FLAG_MINE : 0)
                        | (line.claimedByMe() ? FLAG_CLAIMED_BY_ME : 0) | (line.item() ? FLAG_ITEM : 0);
                rows.add(new HubPage.Row(line.id(), line.icon(), line.typeKey(),
                        List.of(line.target(), line.houseKey(), line.stateKey(), line.dimension()),
                        new long[] { line.quantity(), line.payout(), line.leftMillis(), line.x(), line.y(), line.z() },
                        flags));
                refs.put(line.id(), new HubPages.RowRef(HubPages.RowRef.Kind.JOB, line.id(), "", 0));
            }
        }
        Map<String, Long> numbers = new LinkedHashMap<>();
        Map<String, String> keys = new LinkedHashMap<>();
        numbers.put(VIEW, (long) view.view().ordinal());
        numbers.put(OPEN_MINE, (long) view.openMine());
        numbers.put(MAX_JOBS, (long) view.maxJobs());
        numbers.put(HOURS, (long) view.hours());
        numbers.put(PLAYER_BOUNTIES, view.pvpBounties() ? 1L : 0L);
        numbers.put(HERE_X, view.hereX());
        numbers.put(HERE_Y, view.hereY());
        numbers.put(HERE_Z, view.hereZ());
        if (!view.hereDimension().isEmpty()) keys.put(HERE_DIMENSION, view.hereDimension());
        return new HubPages.Built(new HubPage(0, 0, HubTab.JOBS, page, pages, rows, numbers, keys), refs);
    }
}
