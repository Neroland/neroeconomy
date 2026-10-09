package za.co.neroland.neroeconomy.gallery;

import java.util.ArrayList;
import java.util.List;

/**
 * The gallery's coordinate contract, in one pure place: where each cluster stands and where the
 * capture harness puts the camera for its shot. The server-side builder lays clusters out with
 * {@link Cluster#x} and {@link Cluster#z}, and {@code client/GalleryCaptureHarness} shoots
 * {@link #shots}, so the two can no longer drift apart.
 *
 * <p>Origin O = the operator's block position; {@code oy} is the pad level and display blocks
 * stand one above it. Every cluster sits on a compass {@link Bearing}, {@code radius} blocks out,
 * with a backdrop wall on its far side. The four original clusters form the inner ring at
 * {@link #INNER_RADIUS}; the clusters added since stand further out on the NORTH and WEST bearings
 * only, every {@link #TIER_STEP} blocks, each hidden from every other shot by the walls nearer
 * the origin. The EAST and SOUTH bearings are left empty beyond the inner ring because the
 * {@code player_shop} camera looks south-east down its row of shops.
 *
 * <pre>
 *   shot              bearing  radius  pad (lateral x depth)   wall
 *   bank_terminal     NORTH    24      7 x 5 smooth quartz     z = oz-26   (original)
 *   player_shop       EAST     24      9 x 5 andesite          x = ox+26   (original)
 *   trading_terminal  SOUTH    24      7 x 5 smooth stone      z = oz+26   (original)
 *   market_board      WEST     24      7 x 5 andesite          x = ox-26   (original)
 *   exchange_floor    NORTH    44      7 x 5                   depth +2
 *   auto_trader       NORTH    64      7 x 5                   depth +2
 *   jobs_board        NORTH    84      7 x 5                   depth +2
 *   bank_vault        NORTH    104     7 x 5                   depth +2
 *   direct_trade      WEST     44      7 x 5                   depth +2
 *   merchant_stall    WEST     64      7 x 5                   depth +2
 *   auction_podium    WEST     84      7 x 5                   depth +2
 * </pre>
 *
 * <p>The capture harness builds at (0, 64, 0) in the flat {@code neroeconomy:capture} dimension,
 * so in a capture run every coordinate is absolute.
 */
public final class GalleryLayout {
    /** Distance from the origin to each of the four original clusters. */
    public static final int INNER_RADIUS = 24;
    /** Distance between the tiers of the clusters added beyond the inner ring. */
    public static final int TIER_STEP = 20;
    /** Half the width of an added cluster's pad and wall, across its bearing. */
    public static final int HALF_WIDTH = 3;
    /** Half the depth of an added cluster's pad, along its bearing; the wall stands at +this. */
    public static final int HALF_DEPTH = 2;
    /** Height of an added cluster's backdrop wall, above the pad. */
    public static final int WALL_HEIGHT = 5;

    private GalleryLayout() { }

    /** A compass bearing out from the origin. "Lateral" is to the right of a viewer looking outward. */
    public enum Bearing {
        NORTH(0, -1), EAST(1, 0), SOUTH(0, 1), WEST(-1, 0);

        public final int dx;
        public final int dz;

        Bearing(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }

        public int lateralX() { return -dz; }
        public int lateralZ() { return dx; }
    }

    /** One cluster: its shot name, bearing, distance out, and how high its display block stands. */
    public enum Cluster {
        BANK_TERMINAL("bank_terminal", Bearing.NORTH, INNER_RADIUS, 0),
        PLAYER_SHOP("player_shop", Bearing.EAST, INNER_RADIUS, 0),
        TRADING_TERMINAL("trading_terminal", Bearing.SOUTH, INNER_RADIUS, 1),
        MARKET_BOARD("market_board", Bearing.WEST, INNER_RADIUS, 0),
        EXCHANGE_FLOOR("exchange_floor", Bearing.NORTH, INNER_RADIUS + TIER_STEP, 0),
        AUTO_TRADER("auto_trader", Bearing.NORTH, INNER_RADIUS + 2 * TIER_STEP, 0),
        JOBS_BOARD("jobs_board", Bearing.NORTH, INNER_RADIUS + 3 * TIER_STEP, 0),
        BANK_VAULT("bank_vault", Bearing.NORTH, INNER_RADIUS + 4 * TIER_STEP, 0),
        DIRECT_TRADE("direct_trade", Bearing.WEST, INNER_RADIUS + TIER_STEP, 0),
        MERCHANT_STALL("merchant_stall", Bearing.WEST, INNER_RADIUS + 2 * TIER_STEP, 0),
        AUCTION_PODIUM("auction_podium", Bearing.WEST, INNER_RADIUS + 3 * TIER_STEP, 1);

        public final String shot;
        public final Bearing bearing;
        public final int radius;
        /** Blocks the display block stands above {@code oy + 1} (a dais or a podium). */
        public final int lift;

        Cluster(String shot, Bearing bearing, int radius, int lift) {
            this.shot = shot;
            this.bearing = bearing;
            this.radius = radius;
            this.lift = lift;
        }

        /** One of the four clusters the gallery had before the sandbox; their shots never move. */
        public boolean original() { return radius == INNER_RADIUS; }

        /** Block x of the point {@code depth} blocks outward and {@code lateral} to the right of the centre. */
        public int x(int ox, int depth, int lateral) {
            return ox + bearing.dx * (radius + depth) + bearing.lateralX() * lateral;
        }

        /** Block z of the point {@code depth} blocks outward and {@code lateral} to the right of the centre. */
        public int z(int oz, int depth, int lateral) {
            return oz + bearing.dz * (radius + depth) + bearing.lateralZ() * lateral;
        }
    }

    /** One framed shot: the camera position and the point it looks at. */
    public record Pose(String shot, double cameraX, double cameraY, double cameraZ, double targetX,
            double targetY, double targetZ) { }

    /** Every cluster the gallery builds beyond the original four, in build and shot order. */
    public static List<Cluster> added() {
        List<Cluster> out = new ArrayList<>();
        for (Cluster cluster : Cluster.values()) if (!cluster.original()) out.add(cluster);
        return out;
    }

    /** The furthest any gallery block stands from the origin along a bearing, wall included. */
    public static int reach() {
        int reach = INNER_RADIUS + HALF_DEPTH;
        for (Cluster cluster : Cluster.values()) reach = Math.max(reach, cluster.radius + HALF_DEPTH);
        return reach;
    }

    /**
     * Every shot, the four original ones first with their poses exactly as they always were, then
     * one per added cluster: from 4.5 blocks in front and 2.5 to the right of the display block,
     * at near-eye height, aimed just behind the display block's centre so the wall fills the frame.
     */
    public static List<Pose> shots(int ox, int oy, int oz) {
        int r = INNER_RADIUS;
        List<Pose> shots = new ArrayList<>();
        shots.add(new Pose("bank_terminal", ox + 2.5, oy + 2.6, oz - r + 4.5, ox, oy + 1.6, oz - r - 0.5));
        shots.add(new Pose("player_shop", ox + r - 3, oy + 2.4, oz - 5.5, ox + r, oy + 1.6, oz + 0.5));
        shots.add(new Pose("trading_terminal", ox - 2.5, oy + 3.8, oz + r - 4.5, ox, oy + 2.6, oz + r + 0.5));
        shots.add(new Pose("market_board", ox - r + 4.5, oy + 2.6, oz + 3, ox - r - 0.5, oy + 1.7, oz - 0.5));
        for (Cluster cluster : added()) {
            Bearing b = cluster.bearing;
            double centreX = cluster.x(ox, 0, 0) + 0.5;
            double centreZ = cluster.z(oz, 0, 0) + 0.5;
            shots.add(new Pose(cluster.shot,
                    centreX - 4.5 * b.dx + 2.5 * b.lateralX(), oy + 2.6 + cluster.lift,
                    centreZ - 4.5 * b.dz + 2.5 * b.lateralZ(),
                    centreX + 0.5 * b.dx, oy + 1.6 + cluster.lift, centreZ + 0.5 * b.dz));
        }
        return shots;
    }
}
