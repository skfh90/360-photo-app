package com.n30dyn4m1c.photosphere.camera;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The ordered set of frames that makes up one capture run.
 *
 * Targets sit on horizontal rings. Each ring holds evenly spaced yaws, and the
 * spacing widens with elevation by {@code 1 / cos(elevation)} so neighbouring frames
 * stay a constant <em>angular</em> distance apart rather than bunching up as the rings
 * shrink toward the poles — bunched frames cost capture time and buy the
 * stitcher nothing.
 *
 * The order is a boustrophedon: every other ring is swept in the opposite
 * direction, so each ring ends roughly where the next one begins and the user
 * never has to spin back through 360° between frames.
 *
 * {@link #getRings()} records where each ring starts and ends in {@link #getTargets()},
 * which is what lets the capture screen say "that's the horizon done" at the moment a ring
 * closes rather than only when the whole plan is walked. A run stitched at a
 * ring boundary is a complete band of sphere; one stopped mid-ring has a gap in
 * it, and the difference is worth telling the user about.
 */
public final class SphereTargetPlan {

    /**
     * Elevations of each ring, in capture order: the horizon first (where
     * the user is already pointing), then up, then down.
     */
    public static final List<Float> DEFAULT_RING_ELEVATIONS = Collections.unmodifiableList(
            Arrays.asList(0f, 30f, 60f, -30f, -60f)
    );

    /**
     * Yaw gap between neighbouring frames on the equator.
     *
     * 30° leaves roughly 40% overlap on a typical ~50° portrait field of
     * view, which is about the minimum a feature-based stitcher wants.
     */
    public static final float DEFAULT_EQUATOR_SPACING_DEGREES = 30f;

    /**
     * How much of each frame its neighbours may re-shoot, as a fraction of
     * the field of view.
     *
     * This stitcher refines the measured poses by matching features between
     * overlapping frames, so the overlap has to be wide enough for a
     * feature-based stitcher to find its bearings. A third is that: 35% of
     * each frame is well inside the neighbours' view on both axes, which is
     * what the pose refinement needs. The field of view it is measured
     * against is read off the <em>bound</em> camera's own intrinsic calibration
     * ({@link CameraOptics}) rather than a guess, so the estimate no longer needs
     * the half-the-frame margin it once did — what remains absorbs a degree
     * or two of sensor drift. It costs about a third fewer frames per ring
     * than the old 50% target, at the price of the drift tolerance the
     * calibration already buys back.
     */
    public static final float DEFAULT_TARGET_OVERLAP_FRACTION = 0.35f;

    /**
     * Fraction of the reported field of view the plan actually spends.
     *
     * The overlap above is only as good as the field of view it is measured
     * against, and that number comes from {@link CameraOptics}, which reconciles
     * two independent estimates and re-reads them from the lens CameraX
     * actually bound. It is still an estimate, so the plan spends a little
     * less of it than the lens claims — but only a tenth now, instead of the
     * fifth that made sense when the field of view was a guess. Overestimating
     * it spaces the targets too far apart, and the overlap the stitcher needs
     * is the first thing to be eaten.
     */
    public static final float FIELD_OF_VIEW_SAFETY_FACTOR = 0.9f;

    /**
     * The tallest ring the yaw spacing survives.
     *
     * Rings this high have shrunk to a handful of frames; going beyond it
     * would make the {@code 1 / cos(elevation)} widening of {@link #ringYaws}
     * degenerate and silently break the constant-overlap guarantee.
     */
    public static final float MAX_RING_ELEVATION_DEGREES = 75f;

    /** The single ring a {@link SphereCaptureScope#Ring} run walks. */
    private static final List<Float> RING_ELEVATIONS = Collections.unmodifiableList(
            Arrays.asList(0f)
    );

    private final List<SphereTarget> targets;
    private final List<IntRange> rings;

    public SphereTargetPlan(List<SphereTarget> targets) {
        this(targets, defaultRings(targets));
    }

    public SphereTargetPlan(List<SphereTarget> targets, List<IntRange> rings) {
        this.targets = Collections.unmodifiableList(new ArrayList<SphereTarget>(targets));
        this.rings = Collections.unmodifiableList(new ArrayList<IntRange>(rings));
    }

    private static List<IntRange> defaultRings(List<SphereTarget> targets) {
        if (targets.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(
                Arrays.asList(new IntRange(0, targets.size() - 1))
        );
    }

    public List<SphereTarget> getTargets() {
        return targets;
    }

    public List<IntRange> getRings() {
        return rings;
    }

    public int getSize() {
        return targets.size();
    }

    public int size() {
        return targets.size();
    }

    public SphereTarget get(int index) {
        return targets.get(index);
    }

    public SphereTarget getOrNull(int index) {
        if (index < 0 || index >= targets.size()) {
            return null;
        }
        return targets.get(index);
    }

    /** How many rings the plan lays out. */
    public int getRingCount() {
        return rings.size();
    }

    /**
     * How many whole rings the first {@code capturedCount} targets cover.
     *
     * Counting closed rings rather than frames is what makes "you have a
     * complete band, stitch now or carry on" something the screen can say.
     */
    public int completedRings(int capturedCount) {
        int count = 0;
        for (int i = 0; i < rings.size(); i++) {
            if (capturedCount > rings.get(i).last) {
                count++;
            }
        }
        return count;
    }

    /**
     * Lays out a sphere whose first target sits at {@code startYawDegrees}.
     *
     * Anchoring on the bearing the user is already facing means capture
     * starts with the reticle on the first marker instead of asking them to
     * find magnetic north.
     */
    public static SphereTargetPlan create() {
        return create(0f, DEFAULT_RING_ELEVATIONS, DEFAULT_EQUATOR_SPACING_DEGREES);
    }

    public static SphereTargetPlan create(float startYawDegrees) {
        return create(startYawDegrees, DEFAULT_RING_ELEVATIONS, DEFAULT_EQUATOR_SPACING_DEGREES);
    }

    public static SphereTargetPlan create(float startYawDegrees, List<Float> ringElevations) {
        return create(startYawDegrees, ringElevations, DEFAULT_EQUATOR_SPACING_DEGREES);
    }

    public static SphereTargetPlan create(
            float startYawDegrees,
            List<Float> ringElevations,
            float equatorSpacingDegrees
    ) {
        if (equatorSpacingDegrees <= 0f) {
            throw new IllegalArgumentException("spacing must be positive");
        }
        return createUnchecked(startYawDegrees, ringElevations, equatorSpacingDegrees);
    }

    /**
     * Lays out a sphere whose frame spacing matches the device's optics.
     *
     * The yaw gap is the horizontal field of view minus the target overlap,
     * so a wide-angle phone takes fewer, larger frames and a narrow one
     * takes more, smaller frames — every frame covers roughly the same
     * slice of the sphere instead of the fixed 30° spacing piling up far
     * more overlap on wide lenses.
     *
     * Ring elevations adapt to the <em>vertical</em> field of view the same way,
     * and always reach far enough up to cover the poles, so no lens leaves
     * an uncovered cap at the top or bottom of the sphere.
     */
    public static SphereTargetPlan createForFieldOfView(float startYawDegrees, FieldOfView fieldOfView) {
        return createForFieldOfView(
                startYawDegrees,
                fieldOfView,
                SphereCaptureScope.Sphere,
                null,
                DEFAULT_TARGET_OVERLAP_FRACTION
        );
    }

    public static SphereTargetPlan createForFieldOfView(
            float startYawDegrees,
            FieldOfView fieldOfView,
            SphereCaptureScope scope
    ) {
        return createForFieldOfView(
                startYawDegrees,
                fieldOfView,
                scope,
                null,
                DEFAULT_TARGET_OVERLAP_FRACTION
        );
    }

    public static SphereTargetPlan createForFieldOfView(
            float startYawDegrees,
            FieldOfView fieldOfView,
            SphereCaptureScope scope,
            List<Float> ringElevations
    ) {
        return createForFieldOfView(
                startYawDegrees,
                fieldOfView,
                scope,
                ringElevations,
                DEFAULT_TARGET_OVERLAP_FRACTION
        );
    }

    public static SphereTargetPlan createForFieldOfView(
            float startYawDegrees,
            FieldOfView fieldOfView,
            SphereCaptureScope scope,
            List<Float> ringElevations,
            float targetOverlapFraction
    ) {
        if (targetOverlapFraction < 0f || targetOverlapFraction > 1f) {
            throw new IllegalArgumentException(
                    "overlap fraction must be between 0 and 1, was " + targetOverlapFraction
            );
        }
        // Both axes are shaded down together, so the rings step as
        // conservatively as the yaws do and the vertical overlap keeps pace
        // with the horizontal one.
        FieldOfView planned = new FieldOfView(
                fieldOfView.getHorizontalDegrees() * FIELD_OF_VIEW_SAFETY_FACTOR,
                fieldOfView.getVerticalDegrees() * FIELD_OF_VIEW_SAFETY_FACTOR
        );
        List<Float> elevations = ringElevations;
        if (elevations == null) {
            if (scope == SphereCaptureScope.Ring) {
                elevations = RING_ELEVATIONS;
            } else {
                elevations = adaptiveRingElevations(
                        planned.getVerticalDegrees(),
                        targetOverlapFraction
                );
            }
        }
        float spacing = planned.getHorizontalDegrees() * (1f - targetOverlapFraction);
        return createUnchecked(startYawDegrees, elevations, spacing);
    }

    private static SphereTargetPlan createUnchecked(
            float startYawDegrees,
            List<Float> ringElevations,
            float equatorSpacingDegrees
    ) {
        if (equatorSpacingDegrees <= 0f) {
            throw new IllegalArgumentException("spacing must be positive");
        }

        List<SphereTarget> targets = new ArrayList<SphereTarget>();
        List<IntRange> rings = new ArrayList<IntRange>(ringElevations.size());
        for (int ringIndex = 0; ringIndex < ringElevations.size(); ringIndex++) {
            float elevation = ringElevations.get(ringIndex);
            List<Float> ring = ringYaws(startYawDegrees, elevation, equatorSpacingDegrees);
            // Reverse every other ring so consecutive rings meet at the
            // same bearing instead of a full turn apart.
            List<Float> ordered;
            if (ringIndex % 2 == 0) {
                ordered = ring;
            } else {
                ordered = new ArrayList<Float>(ring);
                Collections.reverse(ordered);
            }
            int start = targets.size();
            for (int i = 0; i < ordered.size(); i++) {
                targets.add(SphereTarget.atElevation(ordered.get(i), elevation));
            }
            if (targets.size() > start) {
                rings.add(new IntRange(start, targets.size() - 1));
            }
        }
        return new SphereTargetPlan(targets, rings);
    }

    private static List<Float> ringYaws(
            float startYawDegrees,
            float elevationDegrees,
            float equatorSpacingDegrees
    ) {
        float shrink = (float) Math.cos(Math.toRadians(elevationDegrees));
        // A ring at the pole degenerates to a single frame; guard the divide
        // rather than letting the spacing run away to infinity.
        float spacing = shrink <= 1e-3f ? 360f : equatorSpacingDegrees / shrink;
        int count = Math.max(1, Math.round(360f / spacing));
        float step = 360f / count;
        List<Float> yaws = new ArrayList<Float>(count);
        for (int i = 0; i < count; i++) {
            yaws.add(normalizeDegrees(startYawDegrees + i * step));
        }
        return yaws;
    }

    /** Wraps {@code degrees} into {@code [-180, 180)}, so yaw crossing north stays continuous. */
    private static float normalizeDegrees(float degrees) {
        float value = degrees % 360f;
        if (value >= 180f) {
            value -= 360f;
        }
        if (value < -180f) {
            value += 360f;
        }
        return value;
    }

    /**
     * Ring elevations that cover the whole sphere for a given vertical lens.
     *
     * Rings step up and down from the horizon by {@code vFov × (1 − overlap)}, so
     * the vertical overlap is guaranteed regardless of how narrow or wide the
     * lens is, and the outermost ring always reaches the poles — its upper
     * edge lands past ±90° with room to spare. Without it, a lens whose
     * rings were laid out for another lens's field of view would leave an
     * uncovered cap at the top and bottom of the sphere.
     */
    public static List<Float> adaptiveRingElevations(float verticalFovDegrees, float overlapFraction) {
        if (verticalFovDegrees < 1f || verticalFovDegrees > 179f) {
            throw new IllegalArgumentException("vertical fov: " + verticalFovDegrees);
        }
        if (overlapFraction < 0f || overlapFraction > 1f) {
            throw new IllegalArgumentException("overlap: " + overlapFraction);
        }
        float step = verticalFovDegrees * (1f - overlapFraction);
        // The top ring's upper edge must clear the pole by a good margin, so
        // it is parked half a step beyond the point where its centre alone
        // would just touch it.
        float cap = Math.min(
                MAX_RING_ELEVATION_DEGREES,
                Math.max(step, 90f - verticalFovDegrees / 2f + step / 2f)
        );
        List<Float> elevations = new ArrayList<Float>();
        elevations.add(0f);
        // Intermediate rings only while they are clearly below the cap — a
        // ring within half a step of it would be a near-duplicate zenith
        // ring, which an ultra-wide lens (a step of ~70° plus a cap of ~72°)
        // would otherwise produce.
        float elevation = step;
        while (elevation < cap - step / 2f) {
            elevations.add(elevation);
            elevations.add(-elevation);
            elevation += step;
        }
        elevations.add(cap);
        elevations.add(-cap);
        return elevations;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SphereTargetPlan)) return false;
        SphereTargetPlan that = (SphereTargetPlan) o;
        return targets.equals(that.targets) && rings.equals(that.rings);
    }

    @Override
    public int hashCode() {
        return 31 * targets.hashCode() + rings.hashCode();
    }
}
