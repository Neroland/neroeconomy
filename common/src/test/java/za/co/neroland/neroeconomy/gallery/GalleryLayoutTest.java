package za.co.neroland.neroeconomy.gallery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** The coordinate contract between the gallery builder and the capture harness. */
class GalleryLayoutTest {
    @Test void theFourOriginalShotsKeepTheirNamesOrderAndPoses() {
        List<GalleryLayout.Pose> shots = GalleryLayout.shots(0, 64, 0);
        assertEquals(new GalleryLayout.Pose("bank_terminal", 2.5, 66.6, -19.5, 0, 65.6, -24.5), shots.get(0));
        assertEquals(new GalleryLayout.Pose("player_shop", 21, 66.4, -5.5, 24, 65.6, 0.5), shots.get(1));
        assertEquals(new GalleryLayout.Pose("trading_terminal", -2.5, 67.8, 19.5, 0, 66.6, 24.5), shots.get(2));
        assertEquals(new GalleryLayout.Pose("market_board", -19.5, 66.6, 3, -24.5, 65.7, -0.5), shots.get(3));
    }

    @Test void everyClusterHasExactlyOneShotWithAUniqueName() {
        List<GalleryLayout.Pose> shots = GalleryLayout.shots(10, 70, -30);
        assertEquals(GalleryLayout.Cluster.values().length, shots.size());
        Set<String> names = new HashSet<>();
        for (GalleryLayout.Pose pose : shots) assertTrue(names.add(pose.shot()), pose.shot());
        for (GalleryLayout.Cluster cluster : GalleryLayout.Cluster.values()) assertTrue(names.contains(cluster.shot));
        assertEquals(7, GalleryLayout.added().size());
    }

    @Test void addedClustersStandOnTheFreeBearingsAndNeverOverlap() {
        Set<Long> taken = new HashSet<>();
        for (GalleryLayout.Cluster cluster : GalleryLayout.Cluster.values()) {
            if (!cluster.original()) {
                assertTrue(cluster.bearing == GalleryLayout.Bearing.NORTH || cluster.bearing == GalleryLayout.Bearing.WEST,
                        cluster.shot);
                assertEquals(0, (cluster.radius - GalleryLayout.INNER_RADIUS) % GalleryLayout.TIER_STEP);
            }
            // The footprint, padded by one block, of every cluster: the widest original is 9 across.
            int half = cluster.original() ? 4 : GalleryLayout.HALF_WIDTH;
            for (int depth = -GalleryLayout.HALF_DEPTH - 1; depth <= GalleryLayout.HALF_DEPTH + 1; depth++)
                for (int lateral = -half - 1; lateral <= half + 1; lateral++) {
                    long key = ((long) cluster.x(0, depth, lateral) << 32) | (cluster.z(0, depth, lateral) & 0xffffffffL);
                    assertTrue(taken.add(key), cluster.shot + " overlaps another cluster");
                }
        }
        assertEquals(GalleryLayout.INNER_RADIUS + 4 * GalleryLayout.TIER_STEP + GalleryLayout.HALF_DEPTH,
                GalleryLayout.reach());
    }

    @Test void eachAddedShotLooksOutwardAtItsOwnCluster() {
        List<GalleryLayout.Pose> shots = GalleryLayout.shots(0, 64, 0);
        for (GalleryLayout.Cluster cluster : GalleryLayout.added()) {
            GalleryLayout.Pose pose = shots.stream().filter(shot -> shot.shot().equals(cluster.shot)).findFirst()
                    .orElseThrow();
            GalleryLayout.Bearing b = cluster.bearing;
            // Looking outward along the bearing, at the display block, from in front of it.
            double outward = (pose.targetX() - pose.cameraX()) * b.dx + (pose.targetZ() - pose.cameraZ()) * b.dz;
            assertEquals(5.0, outward, 1e-9, cluster.shot);
            double centreX = cluster.x(0, 0, 0) + 0.5;
            double centreZ = cluster.z(0, 0, 0) + 0.5;
            assertEquals(centreX + 0.5 * b.dx, pose.targetX(), 1e-9);
            assertEquals(centreZ + 0.5 * b.dz, pose.targetZ(), 1e-9);
            assertFalse(pose.cameraY() <= 64 + 1, cluster.shot + " camera is under the floor");
        }
    }

    @Test void lateralIsToTheRightOfAViewerLookingOutward() {
        // Looking north, right is east; looking west, right is north.
        assertEquals(1, GalleryLayout.Bearing.NORTH.lateralX());
        assertEquals(0, GalleryLayout.Bearing.NORTH.lateralZ());
        assertEquals(0, GalleryLayout.Bearing.WEST.lateralX());
        assertEquals(-1, GalleryLayout.Bearing.WEST.lateralZ());
    }
}
