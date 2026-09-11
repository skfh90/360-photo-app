package com.n30dyn4m1c.photosphere.camera;

import com.n30dyn4m1c.photosphere.sensor.OrientationData;
import com.n30dyn4m1c.photosphere.sensor.OrientationTracker;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SphereTargetPlanTest {

    private static final float TOLERANCE = 1e-3f;

    @Test
    public void everyRingLiesInsideThePlusOrMinusSixtyDegreeBand() {
        SphereTargetPlan plan = SphereTargetPlan.create();

        Assert.assertTrue(plan.size() > 0);
        List<SphereTarget> targets = plan.getTargets();
        for (int i = 0; i < targets.size(); i++) {
            SphereTarget target = targets.get(i);
            Assert.assertTrue(
                    "elevation " + target.getElevationDegrees() + " outside the band",
                    Math.abs(target.getElevationDegrees()) <= 60f + TOLERANCE);
            Assert.assertTrue(
                    "yaw " + target.getYawDegrees() + " outside [-180, 180)",
                    target.getYawDegrees() >= -180f && target.getYawDegrees() < 180f);
        }
    }

    @Test
    public void ringsThinOutTowardThePoles() {
        SphereTargetPlan plan = SphereTargetPlan.create();
        Map<Float, Integer> perRing = new HashMap<Float, Integer>();
        List<SphereTarget> targets = plan.getTargets();
        for (int i = 0; i < targets.size(); i++) {
            Float elevation = targets.get(i).getElevationDegrees();
            Integer count = perRing.get(elevation);
            perRing.put(elevation, count == null ? 1 : count + 1);
        }

        // 30 degrees of yaw at the equator, widened by 1/cos(elevation) above it.
        Assert.assertEquals(Integer.valueOf(12), perRing.get(0f));
        Assert.assertEquals(Integer.valueOf(10), perRing.get(30f));
        Assert.assertEquals(Integer.valueOf(10), perRing.get(-30f));
        Assert.assertEquals(Integer.valueOf(6), perRing.get(60f));
        Assert.assertEquals(Integer.valueOf(6), perRing.get(-60f));
        Assert.assertEquals(44, plan.size());
    }

    @Test
    public void captureStartsAtTheBearingTheUserIsAlreadyFacing() {
        SphereTargetPlan plan = SphereTargetPlan.create(-75f);

        Assert.assertEquals(-75f, plan.get(0).getYawDegrees(), TOLERANCE);
        Assert.assertEquals(0f, plan.get(0).getElevationDegrees(), TOLERANCE);
    }

    @Test
    public void alternateRingsAreSweptTheOtherWaySoTheSeamsMeet() {
        SphereTargetPlan plan = SphereTargetPlan.create(0f);
        List<SphereTarget> equator = filterByElevation(plan.getTargets(), 0f);
        List<SphereTarget> secondRing = filterByElevation(plan.getTargets(), 30f);

        // The equator sweeps clockwise and finishes just short of a full turn...
        Assert.assertEquals(30f, equator.get(1).getYawDegrees(), TOLERANCE);
        Assert.assertEquals(-30f, equator.get(equator.size() - 1).getYawDegrees(), TOLERANCE);
        // ...and the next ring is walked back the other way, so it picks up near
        // the bearing the equator left off at instead of a turn away from it.
        float handover = OrientationTracker.normalizeDegrees(
                secondRing.get(0).getYawDegrees() - equator.get(equator.size() - 1).getYawDegrees());
        Assert.assertTrue("ring hand-over turns " + handover + " degrees", Math.abs(handover) < 45f);
        Assert.assertEquals(0f, secondRing.get(secondRing.size() - 1).getYawDegrees(), TOLERANCE);
    }

    @Test
    public void neighboursWithinARingStayAConstantAngularDistanceApart() {
        SphereTargetPlan plan = SphereTargetPlan.create();

        float[] elevations = new float[] {0f, 30f, 60f};
        for (int e = 0; e < elevations.length; e++) {
            float elevation = elevations[e];
            List<SphereTarget> ring = filterByElevation(plan.getTargets(), elevation);
            for (int index = 0; index < ring.size(); index++) {
                SphereTarget a = ring.get(index);
                SphereTarget b = ring.get((index + 1) % ring.size());
                float separation = angularDistance(a, b);
                // The ring counts are integers, so the spacing lands near the
                // 30-degree target rather than exactly on it.
                Assert.assertTrue(
                        "elevation " + elevation + " spaced " + separation + " degrees apart",
                        separation >= 24f && separation <= 37f);
            }
        }
    }

    @Test
    public void fieldOfViewScalingKeepsOverlapRoughlyConstantAcrossLenses() {
        SphereTargetPlan narrow = SphereTargetPlan.createForFieldOfView(
                0f,
                new FieldOfView(52f, 66f));
        SphereTargetPlan wide = SphereTargetPlan.createForFieldOfView(
                0f,
                new FieldOfView(76f, 66f));

        // A wider lens needs fewer, larger frames per ring.
        Assert.assertTrue("wide lens took more frames", wide.size() < narrow.size());

        // The band is wide because the plan deliberately spends less of the
        // lens than the lens claims (FIELD_OF_VIEW_SAFETY_FACTOR), so the real
        // overlap sits above the nominal target rather than below it.
        float narrowOverlap = equatorOverlap(narrow, 52f);
        float wideOverlap = equatorOverlap(wide, 76f);
        Assert.assertTrue(
                "narrow-lens overlap off target",
                narrowOverlap >= 0.30f && narrowOverlap <= 0.55f);
        Assert.assertTrue(
                "wide-lens overlap off target",
                wideOverlap >= 0.30f && wideOverlap <= 0.55f);
    }

    @Test
    public void aPlanCanBeLaidOutWithCustomRings() {
        SphereTargetPlan plan = SphereTargetPlan.create(
                0f,
                Arrays.asList(0f),
                90f);

        Assert.assertEquals(4, plan.size());
        List<Float> yaws = new ArrayList<Float>();
        List<SphereTarget> targets = plan.getTargets();
        for (int i = 0; i < targets.size(); i++) {
            yaws.add(targets.get(i).getYawDegrees());
        }
        Assert.assertEquals(Arrays.asList(0f, 90f, -180f, -90f), yaws);
    }

    @Test
    public void adaptiveRingsStepWithTheVerticalFieldOfViewAndReachThePoles() {
        float vFov = 66f;
        float overlap = SphereTargetPlan.DEFAULT_TARGET_OVERLAP_FRACTION;
        float step = vFov * (1f - overlap);
        List<Float> elevations = SphereTargetPlan.adaptiveRingElevations(vFov, overlap);

        // The horizon is always the first ring, and rings are symmetric.
        Assert.assertTrue(elevations.contains(0f));
        for (int i = 0; i < elevations.size(); i++) {
            float elevation = elevations.get(i);
            if (elevation != 0f) {
                Assert.assertTrue("ring " + elevation + " has no mirror", elevations.contains(-elevation));
            }
        }

        // The first ring above the horizon is one vertical step up, so vertical
        // overlap is guaranteed whatever the lens.
        List<Float> positive = new ArrayList<Float>();
        for (int i = 0; i < elevations.size(); i++) {
            if (elevations.get(i) > 0f) {
                positive.add(elevations.get(i));
            }
        }
        Collections.sort(positive);
        Assert.assertEquals("first ring above the horizon", step, positive.get(0), 1f);

        // And the top ring must cover the pole with room to spare.
        float top = 0f;
        for (int i = 0; i < elevations.size(); i++) {
            top = Math.max(top, Math.abs(elevations.get(i)));
        }
        Assert.assertTrue("top ring " + top + " leaves the pole uncovered", top + vFov / 2f >= 90f);
    }

    @Test
    public void aNarrowerLensGetsMoreRingsThanAWiderOne() {
        SphereTargetPlan narrow = SphereTargetPlan.createForFieldOfView(
                0f,
                new FieldOfView(52f, 40f));
        SphereTargetPlan wide = SphereTargetPlan.createForFieldOfView(
                0f,
                new FieldOfView(76f, 90f));

        // A small vertical field of view needs more, thinner rings to cover the
        // same sphere; every target still stays inside the legal band.
        Assert.assertTrue("narrow lens took fewer frames", narrow.size() > wide.size());
        List<SphereTarget> targets = narrow.getTargets();
        for (int i = 0; i < targets.size(); i++) {
            SphereTarget target = targets.get(i);
            Assert.assertTrue(
                    "elevation " + target.getElevationDegrees() + " outside the band",
                    target.isNadir()
                            || Math.abs(target.getElevationDegrees()) <= 75f + TOLERANCE);
        }
    }

    @Test
    public void anUltraWideLensDoesNotStackNearDuplicateZenithRings() {
        // The S23's ultrawide in portrait has a ~105° vertical field of view,
        // which puts the vertical step (~68°) and the pole-covering cap (~72°)
        // within half a step of each other — the intermediate ring would be a
        // near-duplicate, so only the cap ring is laid out above the horizon.
        List<Float> elevations = SphereTargetPlan.adaptiveRingElevations(105f, 0.35f);
        List<Float> positive = new ArrayList<Float>();
        for (int i = 0; i < elevations.size(); i++) {
            if (elevations.get(i) > 0f) {
                positive.add(elevations.get(i));
            }
        }
        Collections.sort(positive);

        Assert.assertEquals(1, positive.size());
        float top = positive.get(0);
        Assert.assertTrue("cap " + top + " leaves the pole uncovered", top + 105f / 2f >= 90f);
    }

    @Test
    public void aRingCaptureLaysOutTheHorizonAndNothingElse() {
        SphereTargetPlan ring = SphereTargetPlan.createForFieldOfView(
                0f,
                new FieldOfView(52f, 66f),
                SphereCaptureScope.Ring);

        Assert.assertEquals(1, ring.getRingCount());
        Assert.assertTrue(
                "a ring should still take a dozen-odd frames",
                ring.size() >= 8 && ring.size() <= 20);
        List<SphereTarget> targets = ring.getTargets();
        for (int i = 0; i < targets.size(); i++) {
            Assert.assertEquals(
                    "ring target off the horizon",
                    0f,
                    targets.get(i).getElevationDegrees(),
                    TOLERANCE);
        }
    }

    @Test
    public void aRingCaptureClosesTheFullCircle() {
        // The "regular pano that goes all the way around": the horizon band,
        // but the sweep must cover the whole 360° — not just a 180° slice of it.
        SphereTargetPlan ring = SphereTargetPlan.createForFieldOfView(
                40f,
                new FieldOfView(52f, 66f),
                SphereCaptureScope.Ring);

        double gaps = 0.0;
        List<SphereTarget> targets = ring.getTargets();
        for (int index = 0; index < targets.size(); index++) {
            gaps += angularDistance(targets.get(index), targets.get((index + 1) % targets.size()));
        }
        Assert.assertEquals("the ring does not close on itself", 360.0, gaps, 1.0);
    }

    @Test
    public void aSphereCaptureIsManyRingsAndStartsWithTheHorizon() {
        SphereTargetPlan sphere = SphereTargetPlan.createForFieldOfView(
                0f,
                new FieldOfView(52f, 66f),
                SphereCaptureScope.Sphere);
        SphereTargetPlan ring = SphereTargetPlan.createForFieldOfView(
                0f,
                new FieldOfView(52f, 66f),
                SphereCaptureScope.Ring);

        Assert.assertTrue("a sphere should take more rings than a ring", sphere.getRingCount() > 1);
        // The first ring of a sphere run *is* the ring run, which is what lets a
        // user start on a sphere and stop with a complete band anyway.
        Assert.assertEquals(ring.size(), sphere.getRings().get(0).last + 1);
    }

    @Test
    public void aSphereCaptureEndsWithASingleNadirShot() {
        float startYaw = -40f;
        SphereTargetPlan sphere = SphereTargetPlan.createForFieldOfView(
                startYaw,
                new FieldOfView(52f, 66f),
                SphereCaptureScope.Sphere);

        IntRange nadirRing = sphere.getRings().get(sphere.getRingCount() - 1);
        Assert.assertEquals("nadir is its own last band", nadirRing.first, nadirRing.last);
        SphereTarget nadir = sphere.get(nadirRing.first);
        Assert.assertEquals(
                "nadir elevation",
                SphereTargetPlan.NADIR_ELEVATION_DEGREES,
                nadir.getElevationDegrees(),
                TOLERANCE);
        Assert.assertEquals("nadir yaw follows the start bearing", startYaw, nadir.getYawDegrees(), TOLERANCE);
        Assert.assertTrue(nadir.isNadir());
        Assert.assertEquals(1, countNadirTargets(sphere));
    }

    @Test
    public void aRingCaptureDoesNotAddANadirShot() {
        SphereTargetPlan ring = SphereTargetPlan.createForFieldOfView(
                12f,
                new FieldOfView(52f, 66f),
                SphereCaptureScope.Ring);

        Assert.assertEquals(0, countNadirTargets(ring));
        List<SphereTarget> targets = ring.getTargets();
        for (int i = 0; i < targets.size(); i++) {
            Assert.assertFalse(targets.get(i).isNadir());
        }
    }

    @Test
    public void ringsCoverThePlanExactlyOnceInOrder() {
        SphereTargetPlan plan = SphereTargetPlan.createForFieldOfView(
                12f,
                new FieldOfView(52f, 66f));

        Assert.assertEquals(0, plan.getRings().get(0).first);
        Assert.assertEquals(plan.size() - 1, plan.getRings().get(plan.getRings().size() - 1).last);
        List<IntRange> rings = plan.getRings();
        for (int i = 0; i < rings.size() - 1; i++) {
            Assert.assertEquals("rings must be contiguous", rings.get(i).last + 1, rings.get(i + 1).first);
        }
    }

    @Test
    public void aRingCountsAsCompleteOnlyOnceItsLastTargetIsShot() {
        SphereTargetPlan plan = SphereTargetPlan.createForFieldOfView(
                0f,
                new FieldOfView(52f, 66f));
        IntRange firstRing = plan.getRings().get(0);

        // One short of the ring's last target is still no complete ring.
        Assert.assertEquals(0, plan.completedRings(firstRing.last));
        Assert.assertEquals(1, plan.completedRings(firstRing.last + 1));

        // Nothing captured is no rings, whatever the plan looks like.
        Assert.assertEquals(0, plan.completedRings(0));
        Assert.assertEquals(plan.getRingCount(), plan.completedRings(plan.size()));
    }

    /** Great-circle angle between two targets, for checking coverage. */
    private float angularDistance(SphereTarget a, SphereTarget b) {
        return SphereProjection.angularDistanceDegrees(
                new OrientationData(a.getYawDegrees(), a.getPitchDegrees(), 0f),
                b);
    }

    private float equatorOverlap(SphereTargetPlan plan, float horizontalFov) {
        List<SphereTarget> equator = filterByElevation(plan.getTargets(), 0f);
        float separation = angularDistance(equator.get(0), equator.get(1));
        return 1f - separation / horizontalFov;
    }

    private int countNadirTargets(SphereTargetPlan plan) {
        int count = 0;
        List<SphereTarget> targets = plan.getTargets();
        for (int i = 0; i < targets.size(); i++) {
            if (targets.get(i).isNadir()) {
                count++;
            }
        }
        return count;
    }

    private List<SphereTarget> filterByElevation(List<SphereTarget> targets, float elevation) {
        List<SphereTarget> filtered = new ArrayList<SphereTarget>();
        for (int i = 0; i < targets.size(); i++) {
            if (targets.get(i).getElevationDegrees() == elevation) {
                filtered.add(targets.get(i));
            }
        }
        return filtered;
    }
}
