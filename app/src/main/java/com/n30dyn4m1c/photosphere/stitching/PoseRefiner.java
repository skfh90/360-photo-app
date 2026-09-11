package com.n30dyn4m1c.photosphere.stitching;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import org.opencv.core.Core;
import org.opencv.core.DMatch;
import org.opencv.core.KeyPoint;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDMatch;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.core.Size;
import org.opencv.features2d.DescriptorMatcher;
import org.opencv.features2d.ORB;
import org.opencv.imgproc.Imgproc;

/**
 * Sharpens the measured poses against the image content.
 *
 * The sensor places every frame to within a degree or two, which is good enough
 * for a panorama but leaves the overlaps soft. This pass measures the residual:
 * frames that overlap are matched on ORB features, a pure-rotation RANSAC
 * recovers the *content-observed* relative rotation, and a Gauss–Seidel solve
 * distributes those measurements over the whole pose graph with the sensor
 * rotations as the starting guess. The result is a small per-frame correction on
 * top of the measured pose — the sensor stays the anchor, and the pixels decide
 * the fine alignment.
 *
 * The same correspondences also sharpen the *field of view*. A focal-length
 * error pulls every bearing radially about its optical axis, so the residual
 * the rotations leave behind carries a measure of it; a per-frame focal scale
 * is solved from the matched bearings by least squares ({@link RotationMath#refineFocalLengths})
 * and handed back with the poses, and the stitcher projects through the
 * corrected focal lengths. Frame 0 is anchored — rotation averaging already
 * fixes it, and the whole sphere may be uniformly scaled without changing any
 * alignment, so the absolute scale is only defined relative to the frame that
 * is held still.
 *
 * Nothing here is allowed to make a stitch *worse*: every edge is checked
 * against the sensor-relative rotation it should be near, frames with no
 * reliable matches keep their sensor pose and their reported focal length, and
 * if nothing matches at all the whole pass hands the sensor rotations straight
 * back.
 */
public final class PoseRefiner {

    /** Long edge frames are downscaled to before ORB runs. */
    public static final int FEATURE_DETECT_LONG_EDGE = 512;

    /** ORB detector settings: plenty of features, tolerant of small pose error. */
    private static final int ORB_MAX_FEATURES = 1500;

    /** Ratio test: keep a match only if clearly better than the second-best. */
    private static final float RATIO_TEST = 0.8f;

    /**
     * Below this many matches an edge is not worth solving for.
     *
     * Deliberately low: the RANSAC result still has to agree with the sensor's
     * relative rotation within {@link #MAX_SENSOR_DEVIATION_DEGREES}, so a sparse but
     * honest set of matches from a low-texture scene is accepted where a strict
     * count would hand the whole edge back to the sensor alone.
     */
    public static final int MIN_MATCHES = 16;

    /** Below this many inliers a rotation estimate is not trusted. */
    public static final int MIN_INLIERS = 12;

    /** Inlier agreement for the RANSAC, in degrees. */
    public static final double RANSAC_THRESHOLD_DEGREES = 2.0;

    /** How many neighbours each frame is allowed to measure. */
    public static final int MAX_EDGES_PER_FRAME = 5;

    /**
     * An edge whose rotation lands further than this from the sensor's relative
     * rotation is assumed to be a false match on repetitive texture, not a lens
     * that suddenly moved.
     */
    public static final double MAX_SENSOR_DEVIATION_DEGREES = 6.0;

    /** Gauss–Seidel sweeps over the pose graph. */
    private static final int AVERAGE_ITERATIONS = 8;

    /**
     * Below this many matched feature correspondences the focal correction is
     * not worth solving for.
     *
     * The solve needs enough bearings spread across the frame to separate a
     * genuine focal-scale error from feature-location noise; a handful of
     * matches from a low-texture scene would happily explain themselves as a
     * focal shift, so below this floor the reported field of view is kept.
     */
    public static final int MIN_FOCAL_CORRESPONDENCES = 40;

    /**
     * Largest focal correction a frame is allowed to take, as a fraction.
     *
     * A lens that genuinely wanted a bigger correction would be a badly
     * described device, and a bad solve must not be allowed to rescale a frame
     * enough to open gaps its neighbours cannot close. 15% is an order of
     * magnitude more than the residual scale error a loosely-described lens
     * leaves behind.
     */
    public static final double MAX_FOCAL_CORRECTION = 0.15;

    /**
     * Gauss–Newton passes over the focal correction.
     *
     * Each pass re-linearises the residuals around the current estimate and
     * re-solves; two passes absorb most of the nonlinearity a multi-degree
     * correction leaves behind. The rotations stay put between passes — the
     * focal shift is a second-order correction to them — so each pass is just a
     * rebuild of the correspondences and one solve.
     */
    private static final int FOCAL_ITERATIONS = 3;

    public interface ProgressListener {
        void onProgress(int completed, int total);
    }

    /** One descriptor match: query index in the first frame, train index in the second. */
    public static final class IndexPair {
        public final int first;
        public final int second;

        public IndexPair(int first, int second) {
            this.first = first;
            this.second = second;
        }
    }

    private PoseRefiner() {}

    public static RefinementResult refine(
        List<DecodedFrame> frames,
        float horizontalFovDegrees,
        float verticalFovDegrees
    ) {
        return refine(frames, horizontalFovDegrees, verticalFovDegrees, 0.0, null);
    }

    public static RefinementResult refine(
        List<DecodedFrame> frames,
        float horizontalFovDegrees,
        float verticalFovDegrees,
        double pivotRatio,
        ProgressListener onProgress
    ) {
        List<int[]> candidateEdges = overlapGraph(frames, horizontalFovDegrees, verticalFovDegrees);

        ORB orb = ORB.create(
            ORB_MAX_FEATURES, 1.2f, 8, 31, 0, 2, ORB.HARRIS_SCORE, 31, 20
        );
        Mat emptyMask = new Mat();
        ArrayList<FrameFeatures> features = new ArrayList<FrameFeatures>(frames.size());
        for (int i = 0; i < frames.size(); i++) {
            features.add(extractFeatures(frames.get(i), orb, emptyMask, pivotRatio));
        }
        ArrayList<RotationMath.RotationEdge> acceptedEdges = new ArrayList<RotationMath.RotationEdge>();
        // The inlier match indices of each accepted edge, kept in the same order
        // as [acceptedEdges] so the focal solve knows which pixels to re-project.
        ArrayList<List<IndexPair>> edgeInliers = new ArrayList<List<IndexPair>>();
        // One matcher for the whole pass: `create` builds a native object, and
        // the pose graph asks it for a hundred-odd pairs.
        DescriptorMatcher matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING);

        try {
            int total = candidateEdges.size();
            int done = 0;
            for (int e = 0; e < candidateEdges.size(); e++) {
                int first = candidateEdges.get(e)[0];
                int second = candidateEdges.get(e)[1];
                List<IndexPair> matches = match(matcher, features.get(first), features.get(second));
                if (matches.size() >= MIN_MATCHES) {
                    double[][] from = new double[matches.size()][];
                    double[][] to = new double[matches.size()][];
                    for (int i = 0; i < matches.size(); i++) {
                        from[i] = features.get(first).bearings.get(matches.get(i).first);
                        to[i] = features.get(second).bearings.get(matches.get(i).second);
                    }
                    RotationMath.RotationEstimate estimate = RotationMath.estimateRotation(
                        from,
                        to,
                        Math.toRadians(RANSAC_THRESHOLD_DEGREES),
                        300,
                        new Random(seedFor(first, second)),
                        MIN_INLIERS
                    );
                    if (estimate != null && estimate.getInlierCount() >= MIN_INLIERS) {
                        double[] sensorRelative = RotationMath.multiply(
                            RotationMath.transpose(frames.get(second).sensorBasis.toRotationMatrix()),
                            frames.get(first).sensorBasis.toRotationMatrix()
                        );
                        double deviation = Math.toDegrees(
                            RotationMath.angle(estimate.rotation, sensorRelative)
                        );
                        if (deviation <= MAX_SENSOR_DEVIATION_DEGREES) {
                            acceptedEdges.add(new RotationMath.RotationEdge(
                                first,
                                second,
                                estimate.rotation,
                                (double) estimate.getInlierCount()
                            ));
                            ArrayList<IndexPair> inliers = new ArrayList<IndexPair>(estimate.inliers.size());
                            for (int i = 0; i < estimate.inliers.size(); i++) {
                                inliers.add(matches.get(estimate.inliers.get(i)));
                            }
                            edgeInliers.add(inliers);
                        }
                    }
                }
                done++;
                if (onProgress != null) onProgress.onProgress(done, total);
            }

            ArrayList<double[]> initial = new ArrayList<double[]>(frames.size());
            for (int i = 0; i < frames.size(); i++) {
                initial.add(frames.get(i).sensorBasis.toRotationMatrix());
            }
            ArrayList<CameraBasis> refined = new ArrayList<CameraBasis>(frames.size());
            if (acceptedEdges.isEmpty()) {
                for (int i = 0; i < initial.size(); i++) {
                    refined.add(CameraBasis.fromRotationMatrix(initial.get(i)));
                }
            } else {
                List<double[]> averaged = RotationMath.averageRotations(
                    initial, acceptedEdges, AVERAGE_ITERATIONS
                );
                for (int i = 0; i < averaged.size(); i++) {
                    refined.add(CameraBasis.fromRotationMatrix(averaged.get(i)));
                }
            }

            double[] meanLuma = new double[frames.size()];
            for (int i = 0; i < frames.size(); i++) meanLuma[i] = features.get(i).meanLuma;
            float[] gains = ExposureCompensation.solveGains(meanLuma, candidateEdges);

            ArrayList<double[]> refinedRotations = new ArrayList<double[]>(refined.size());
            for (int i = 0; i < refined.size(); i++) {
                refinedRotations.add(refined.get(i).toRotationMatrix());
            }
            double[] focalScales = solveFocalScales(
                frames,
                features,
                acceptedEdges,
                edgeInliers,
                refinedRotations,
                pivotRatio
            );

            return new RefinementResult(refined, gains, acceptedEdges.size(), focalScales);
        } finally {
            for (int i = 0; i < features.size(); i++) features.get(i).descriptors.release();
            matcher.clear();
            orb.clear();
            emptyMask.release();
        }
    }

    private static final class FrameFeatures {
        final List<double[]> bearings;
        /**
         * The undistorted (ideal pinhole) pixel of each keypoint, aligned by
         * position with {@link #bearings}. The bearing is the same information in
         * angular form under the *assumed* focal length; the pixel is what lets
         * the focal solve re-project the keypoint through a corrected one
         * without re-running ORB.
         */
        final List<double[]> idealPixels;
        final Mat descriptors;
        final double meanLuma;

        FrameFeatures(
            List<double[]> bearings,
            List<double[]> idealPixels,
            Mat descriptors,
            double meanLuma
        ) {
            this.bearings = bearings;
            this.idealPixels = idealPixels;
            this.descriptors = descriptors;
            this.meanLuma = meanLuma;
        }
    }

    /**
     * Detects ORB features on a downscaled copy and converts each keypoint to a
     * unit bearing in the frame's camera space.
     *
     * The keypoint is scaled back to the decoded frame's pixels, un-distorted to
     * the ideal pinhole coordinate (the same lens model the renderer samples
     * through), and projected through the decoded intrinsics — so a bearing is
     * directly comparable to a bearing from any other frame.
     *
     * With a {@code pivotRatio} the bearing is then re-referred to the pivot: the ray
     * is followed out to the nominal scene distance and looked back along from
     * the axis the user turned about, then rotated back into the camera's own
     * frame. Two frames of the same body-swivel translate relative to each
     * other, and a pure-rotation RANSAC cannot describe that; two *pivot*
     * bearings of the same scene point differ by a rotation alone, which it can.
     */
    private static FrameFeatures extractFeatures(
        DecodedFrame frame,
        ORB orb,
        Mat mask,
        double pivotRatio
    ) {
        Mat gray = new Mat();
        Mat small = new Mat();
        MatOfKeyPoint keypoints = new MatOfKeyPoint();
        Mat descriptors = new Mat();
        try {
            Imgproc.cvtColor(frame.image, gray, Imgproc.COLOR_RGB2GRAY);
            int width = frame.image.cols();
            int height = frame.image.rows();
            int longest = Math.max(width, height);
            double scale = (double) FEATURE_DETECT_LONG_EDGE / longest;
            int targetWidth = Math.max((int) Math.round(width * scale), 2);
            int targetHeight = Math.max((int) Math.round(height * scale), 2);
            Imgproc.resize(
                gray, small,
                new Size((double) targetWidth, (double) targetHeight),
                0.0, 0.0, Imgproc.INTER_AREA
            );
            orb.detectAndCompute(small, mask, keypoints, descriptors);

            double scaleX = (double) width / targetWidth;
            double scaleY = (double) height / targetHeight;
            FrameIntrinsics intrinsics = frame.intrinsics;
            double[] radial = intrinsics.radial;
            double cx = intrinsics.getCenterXPx();
            double cy = intrinsics.getCenterYPx();
            double fx = intrinsics.focalXPx;
            double fy = intrinsics.focalYPx;

            CameraBasis basis = frame.sensorBasis;
            KeyPoint[] keypointArray = keypoints.toArray();
            ArrayList<double[]> bearings = new ArrayList<double[]>(keypointArray.length);
            ArrayList<double[]> idealPixels = new ArrayList<double[]>(keypointArray.length);
            for (int i = 0; i < keypointArray.length; i++) {
                KeyPoint kp = keypointArray[i];
                double u = kp.pt.x * scaleX;
                double v = kp.pt.y * scaleY;
                double[] ideal;
                if (radial != null) {
                    ideal = LensModel.undistortPixel(u, v, cx, cy, radial);
                } else {
                    ideal = new double[] {u, v};
                }
                idealPixels.add(ideal);
                double x = (ideal[0] - cx) / fx;
                double y = -(ideal[1] - cy) / fy;
                double length = Math.sqrt(x * x + y * y + 1.0);
                x /= length;
                y /= length;
                double z = 1.0 / length;
                if (pivotRatio > 0.0) {
                    bearings.add(pivotReferredBearing(basis, pivotRatio, x, y, z));
                } else {
                    bearings.add(new double[] {x, y, z});
                }
            }

            return new FrameFeatures(bearings, idealPixels, descriptors, Core.mean(small).val[0]);
        } finally {
            gray.release();
            small.release();
            keypoints.release();
        }
    }

    /**
     * A camera-frame bearing rewritten as the bearing a camera *at the pivot*
     * would have recorded for the same scene point.
     *
     * Out to the world, out to the scene, back to the pivot, back into the
     * camera's axes. The round trip through the sensor pose is what makes the
     * result comparable between frames: the pivot is common to all of them,
     * so the only thing left between two frames' pivot bearings is rotation.
     */
    private static double[] pivotReferredBearing(
        CameraBasis basis,
        double pivotRatio,
        double x,
        double y,
        double z
    ) {
        double[] world = basis.toWorld(x, y, z);
        double[] fromPivot = SphericalGeometry.pivotDirection(basis, pivotRatio, world[0], world[1], world[2]);
        return new double[] {
            basis.lateralOf(fromPivot[0], fromPivot[1], fromPivot[2]),
            basis.verticalOf(fromPivot[0], fromPivot[1], fromPivot[2]),
            basis.depthOf(fromPivot[0], fromPivot[1], fromPivot[2]),
        };
    }

    /**
     * The focal-correction solve: one per-frame scale, or all ones when there
     * is nothing to measure against.
     *
     * The scale describes the lens the *feature matches* saw, which is why a
     * matchless run keeps the reported field of view: a correction with no
     * content behind it is a guess. The rotations from the pose graph are the
     * starting point, but the solve itself never touches them directly — each
     * correspondence carries its edge's rotation already baked into the
     * transformed bearing, so a rebuild of the correspondences is all a
     * Gauss–Newton pass needs.
     */
    private static double[] solveFocalScales(
        List<DecodedFrame> frames,
        List<FrameFeatures> features,
        List<RotationMath.RotationEdge> edges,
        List<List<IndexPair>> edgeInliers,
        List<double[]> refinedRotations,
        double pivotRatio
    ) {
        double[] scales = new double[frames.size()];
        for (int i = 0; i < scales.length; i++) scales[i] = 1.0;
        if (edges.isEmpty()) return scales;
        int inlierTotal = 0;
        for (int i = 0; i < edgeInliers.size(); i++) inlierTotal += edgeInliers.get(i).size();
        if (inlierTotal < MIN_FOCAL_CORRESPONDENCES) return scales;

        // The relative rotations the rendering will actually use — smoother
        // than the raw RANSAC edges, since rotation averaging has already
        // spread each measurement across the graph.
        ArrayList<double[]> relative = new ArrayList<double[]>(edges.size());
        for (int i = 0; i < edges.size(); i++) {
            RotationMath.RotationEdge edge = edges.get(i);
            relative.add(RotationMath.multiply(
                RotationMath.transpose(refinedRotations.get(edge.to)),
                refinedRotations.get(edge.from)
            ));
        }

        double[] logScale = new double[frames.size()];
        for (int iteration = 0; iteration < FOCAL_ITERATIONS; iteration++) {
            List<RotationMath.FocalCorrespondence> correspondences = buildFocalCorrespondences(
                frames, features, edges, edgeInliers, relative, pivotRatio, scales
            );
            double[] delta = RotationMath.refineFocalLengths(
                correspondences, frames.size(), MAX_FOCAL_CORRECTION
            );
            for (int frame = 0; frame < frames.size(); frame++) logScale[frame] += delta[frame];
            for (int frame = 0; frame < frames.size(); frame++) scales[frame] = Math.exp(logScale[frame]);
        }
        // The per-pass clamp bounds each step, but the steps accumulate; clamp
        // the total too so a pathological solve still cannot rescale a frame
        // beyond the safety limit.
        for (int frame = 0; frame < frames.size(); frame++) {
            logScale[frame] = clamp(logScale[frame], -MAX_FOCAL_CORRECTION, MAX_FOCAL_CORRECTION);
            scales[frame] = Math.exp(logScale[frame]);
        }
        return scales;
    }

    /**
     * The {@link RotationMath.FocalCorrespondence}s the accepted edges imply, with
     * bearings and derivatives evaluated at {@code scales}.
     *
     * Each inlier of each edge becomes one correspondence: the *from* bearing
     * is projected through the edge's (smoothed) relative rotation and carried
     * alongside the *to* bearing, with the derivative of each with respect to
     * its frame's focal scale. The derivatives are numerical — a central
     * difference of the full bearing pipeline including the pivot referral,
     * which has no closed form worth inlining — and each costs two re-projections
     * of one pixel, so a few hundred correspondences come out in microseconds.
     */
    private static List<RotationMath.FocalCorrespondence> buildFocalCorrespondences(
        List<DecodedFrame> frames,
        List<FrameFeatures> features,
        List<RotationMath.RotationEdge> edges,
        List<List<IndexPair>> edgeInliers,
        List<double[]> relativeRotations,
        double pivotRatio,
        double[] scales
    ) {
        ArrayList<RotationMath.FocalCorrespondence> correspondences =
            new ArrayList<RotationMath.FocalCorrespondence>();
        for (int edgeIndex = 0; edgeIndex < edges.size(); edgeIndex++) {
            RotationMath.RotationEdge edge = edges.get(edgeIndex);
            DecodedFrame fromFrame = frames.get(edge.from);
            DecodedFrame toFrame = frames.get(edge.to);
            FrameFeatures fromFeatures = features.get(edge.from);
            FrameFeatures toFeatures = features.get(edge.to);
            double[] relative = relativeRotations.get(edgeIndex);
            List<IndexPair> inliers = edgeInliers.get(edgeIndex);
            for (int i = 0; i < inliers.size(); i++) {
                IndexPair pair = inliers.get(i);
                double[] fromPixel = fromFeatures.idealPixels.get(pair.first);
                double[] toPixel = toFeatures.idealPixels.get(pair.second);
                double[] pFrom = cameraBearing(
                    fromFrame.intrinsics, fromFrame.sensorBasis, pivotRatio,
                    fromPixel, scales[edge.from]
                );
                double[] pTo = cameraBearing(
                    toFrame.intrinsics, toFrame.sensorBasis, pivotRatio,
                    toPixel, scales[edge.to]
                );
                double[] dFrom = focalDerivative(
                    fromFrame.intrinsics, fromFrame.sensorBasis, pivotRatio,
                    fromPixel, scales[edge.from]
                );
                double[] dTo = focalDerivative(
                    toFrame.intrinsics, toFrame.sensorBasis, pivotRatio,
                    toPixel, scales[edge.to]
                );
                correspondences.add(new RotationMath.FocalCorrespondence(
                    edge.from,
                    edge.to,
                    RotationMath.apply(relative, pFrom),
                    pTo,
                    RotationMath.apply(relative, dFrom),
                    dTo
                ));
            }
        }
        return correspondences;
    }

    /**
     * The unit bearing an ideal pixel records under a focal-length multiplier
     * of {@code scale}.
     *
     * A focal scale of 1 reproduces the bearing {@link #extractFeatures} computes, so
     * the two halves of the refinement measure the same geometry. Scaling the
     * focal length moves the bearing radially about the optical axis — the
     * pixel is divided through by the *corrected* focal length — and the pivot
     * referral (when there is one) runs afterwards, exactly as for a freshly
     * detected keypoint.
     */
    private static double[] cameraBearing(
        FrameIntrinsics intrinsics,
        CameraBasis basis,
        double pivotRatio,
        double[] idealPixel,
        double scale
    ) {
        double x = (idealPixel[0] - intrinsics.getCenterXPx()) / (intrinsics.focalXPx * scale);
        double y = -(idealPixel[1] - intrinsics.getCenterYPx()) / (intrinsics.focalYPx * scale);
        double length = Math.sqrt(x * x + y * y + 1.0);
        x /= length;
        y /= length;
        double z = 1.0 / length;
        if (pivotRatio > 0.0) {
            return pivotReferredBearing(basis, pivotRatio, x, y, z);
        }
        return new double[] {x, y, z};
    }

    /**
     * How a bearing at {@code idealPixel} moves when its frame's focal length is
     * scaled by {@code e^δ}, evaluated at the current {@code scale}.
     *
     * Central difference in log-scale space: the bearing is re-projected at
     * {@code scale·e^±h} and the difference divided through. This is the derivative
     * the focal solve needs, and doing it numerically means the pivot referral
     * (a nonlinear map from the camera-frame bearing) is automatically included
     * — no closed-form Jacobian to derive and get wrong.
     */
    private static double[] focalDerivative(
        FrameIntrinsics intrinsics,
        CameraBasis basis,
        double pivotRatio,
        double[] idealPixel,
        double scale
    ) {
        double step = 1e-4;
        double[] plus = cameraBearing(intrinsics, basis, pivotRatio, idealPixel, scale * Math.exp(step));
        double[] minus = cameraBearing(intrinsics, basis, pivotRatio, idealPixel, scale * Math.exp(-step));
        return new double[] {
            (plus[0] - minus[0]) / (2.0 * step),
            (plus[1] - minus[1]) / (2.0 * step),
            (plus[2] - minus[2]) / (2.0 * step),
        };
    }

    /** Descriptor matches between two frames, filtered by the ratio test. */
    private static List<IndexPair> match(DescriptorMatcher matcher, FrameFeatures a, FrameFeatures b) {
        if (a.bearings.isEmpty() || b.bearings.isEmpty()) return Collections.emptyList();
        if (a.descriptors.empty() || b.descriptors.empty()) return Collections.emptyList();
        ArrayList<MatOfDMatch> knn = new ArrayList<MatOfDMatch>();
        try {
            matcher.knnMatch(a.descriptors, b.descriptors, knn, 2);
            ArrayList<IndexPair> matched = new ArrayList<IndexPair>();
            for (int i = 0; i < knn.size(); i++) {
                DMatch[] candidates = knn.get(i).toArray();
                if (candidates.length >= 2
                    && candidates[0].distance < RATIO_TEST * candidates[1].distance) {
                    matched.add(new IndexPair(candidates[0].queryIdx, candidates[0].trainIdx));
                }
            }
            return matched;
        } finally {
            for (int i = 0; i < knn.size(); i++) knn.get(i).release();
        }
    }

    /**
     * The pose graph: pairs of frames whose images genuinely overlap, capped at
     * {@link #MAX_EDGES_PER_FRAME} per frame and ordered by how much they overlap so
     * the strongest edges are measured first.
     *
     * A pair is a candidate only when {@link SphericalGeometry#angularOverlap} says its aims fall
     * within one field of view of each other on *both* axes — a directional
     * test, rather than a single angular-distance threshold, because a portrait
     * frame is far taller than it is wide: two aims 55° apart overlap
     * vertically but not at all horizontally, and a distance-only threshold
     * would waste a match on them.
     */
    private static List<int[]> overlapGraph(
        List<DecodedFrame> frames,
        float horizontalFovDegrees,
        float verticalFovDegrees
    ) {
        int n = frames.size();
        ArrayList<double[]> candidates = new ArrayList<double[]>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                Double overlap = SphericalGeometry.angularOverlap(
                    frames.get(i).sensorBasis,
                    frames.get(j).sensorBasis,
                    horizontalFovDegrees,
                    verticalFovDegrees
                );
                if (overlap != null) {
                    candidates.add(new double[] {overlap.doubleValue(), i, j});
                }
            }
        }
        Collections.sort(candidates, new Comparator<double[]>() {
            public int compare(double[] a, double[] b) {
                return Double.compare(a[0], b[0]);
            }
        });
        int[] degree = new int[n];
        ArrayList<int[]> edges = new ArrayList<int[]>();
        for (int c = 0; c < candidates.size(); c++) {
            int i = (int) candidates.get(c)[1];
            int j = (int) candidates.get(c)[2];
            if (degree[i] >= MAX_EDGES_PER_FRAME || degree[j] >= MAX_EDGES_PER_FRAME) continue;
            edges.add(new int[] {i, j});
            degree[i]++;
            degree[j]++;
        }
        return edges;
    }

    private static long seedFor(int a, int b) {
        return ((long) a * 31 + (long) b * 17) & 0x7fffffffL;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
