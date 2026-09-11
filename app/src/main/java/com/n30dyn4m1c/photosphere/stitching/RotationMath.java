package com.n30dyn4m1c.photosphere.stitching;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Pure rotation algebra for pose refinement.
 *
 * Rotations are row-major 3×3 matrices mapping *camera* axes to the *world*
 * frame — the same convention {@link CameraBasis#toRotationMatrix()} produces, so the
 * two halves of the pipeline can exchange rotations without a frame conversion.
 * Everything here is plain double arithmetic with no OpenCV, which is what makes
 * the whole refinement testable on a JVM without the native library.
 *
 * Three pieces:
 *
 * 1. {@link #estimateRotation} — RANSAC over matched unit bearings, using a two-point
 *    hypothesis built from orthonormal triples (a SVD-free stand-in for
 *    Orthogonal Procrustes that is exact for the pure-rotation case).
 * 2. {@link #weightedMeanRotation} — the weighted Karcher mean on SO(3), implemented
 *    as quaternion averaging, valid because the corrections are all small.
 * 3. {@link #averageRotations} — Gauss–Seidel rotation averaging over the pose graph,
 *    with the sensor poses as the starting guess and the anchor frame fixed.
 */
public final class RotationMath {

    public static final int SIZE = 9;

    private RotationMath() {}

    public static double[] identity() {
        return new double[] {
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0,
        };
    }

    public static double[] multiply(double[] a, double[] b) {
        double[] out = new double[SIZE];
        for (int i = 0; i <= 2; i++) {
            for (int j = 0; j <= 2; j++) {
                out[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
            }
        }
        return out;
    }

    public static double[] transpose(double[] matrix) {
        return new double[] {
            matrix[0], matrix[3], matrix[6],
            matrix[1], matrix[4], matrix[7],
            matrix[2], matrix[5], matrix[8],
        };
    }

    public static double[] apply(double[] matrix, double[] v) {
        return new double[] {
            matrix[0] * v[0] + matrix[1] * v[1] + matrix[2] * v[2],
            matrix[3] * v[0] + matrix[4] * v[1] + matrix[5] * v[2],
            matrix[6] * v[0] + matrix[7] * v[1] + matrix[8] * v[2],
        };
    }

    /** Geodesic distance between two rotations, in radians. */
    public static double angle(double[] a, double[] b) {
        double[] relative = multiply(transpose(a), b);
        // The cosine from the trace, and the sine from the skew part of the
        // relative rotation. `acos` alone would amplify the last bits of
        // roundoff in the trace into a phantom angle when the rotations are
        // nearly identical (the trace sum is only exact to a few ulps, and
        // d(acos)/dx diverges at x = 1) — with atan2(sin, cos) an identical
        // pair reports exactly 0 and a close pair is exact to first order.
        double cosine = clamp((relative[0] + relative[4] + relative[8] - 1.0) / 2.0, -1.0, 1.0);
        double s1 = relative[7] - relative[5];
        double s2 = relative[2] - relative[6];
        double s3 = relative[3] - relative[1];
        double sine = 0.5 * Math.sqrt(s1 * s1 + s2 * s2 + s3 * s3);
        return Math.atan2(sine, cosine);
    }

    /** The rotation taking {@code from} to {@code to}, both unit vectors. */
    public static double[] rotationBetween(double[] from, double[] to) {
        double[] f = normalize3(from);
        double[] t = normalize3(to);
        double cosine = clamp(dot3(f, t), -1.0, 1.0);
        if (cosine > 1.0 - 1e-12) return identity();
        double[] axis = cross3(f, t);
        double axisNorm = norm3(axis);
        if (axisNorm < 1e-12) {
            // Anti-parallel: any 180° rotation about a perpendicular axis.
            double[] seed = Math.abs(f[0]) < 0.9
                ? new double[] {1.0, 0.0, 0.0}
                : new double[] {0.0, 1.0, 0.0};
            return rotationAboutAxis(normalize3(cross3(f, seed)), Math.PI);
        }
        return rotationAboutAxis(scale3(axis, 1.0 / axisNorm), Math.acos(cosine));
    }

    /** Rodrigues: the rotation of {@code angle} radians about the unit {@code axis}. */
    public static double[] rotationAboutAxis(double[] axis, double angle) {
        double x = axis[0];
        double y = axis[1];
        double z = axis[2];
        double c = Math.cos(angle);
        double s = Math.sin(angle);
        double t = 1.0 - c;
        return new double[] {
            t * x * x + c, t * x * y - s * z, t * x * z + s * y,
            t * x * y + s * z, t * y * y + c, t * y * z - s * x,
            t * x * z - s * y, t * y * z + s * x, t * z * z + c,
        };
    }

    /**
     * The rotation mapping one pair of unit bearings onto another.
     *
     * Two correspondences (a1→b1, a2→b2) determine a rotation exactly under the
     * pure-rotation model: build an orthonormal triple from each pair and align
     * the frames. Used as the RANSAC hypothesis — two samples instead of the
     * three a homography needs, which cuts the iterations required.
     */
    public static double[] basisRotation(double[] a1, double[] b1, double[] a2, double[] b2) {
        double[] u1 = normalize3(a1);
        double[] u2 = normalize3(sub3(a2, scale3(u1, dot3(a2, u1))));
        double[] u3 = cross3(u1, u2);
        double[] v1 = normalize3(b1);
        double[] v2 = normalize3(sub3(b2, scale3(v1, dot3(b2, v1))));
        double[] v3 = cross3(v1, v2);

        double[] rotation = new double[SIZE];
        // R = B·A^T, the change of basis taking the a-triple onto the b-triple.
        // R[i][j] = sum_k B[i][k]·A[j][k] = sum_k v_k[i]·u_k[j].
        for (int i = 0; i <= 2; i++) {
            for (int j = 0; j <= 2; j++) {
                rotation[i * 3 + j] = v1[i] * u1[j] + v2[i] * u2[j] + v3[i] * u3[j];
            }
        }
        return rotation;
    }

    /**
     * A rotation hypothesis and the correspondences that agreed with it.
     *
     * {@link #residualRadians} is the median angular error over the inliers, a proxy for
     * the measurement noise that weights the edge in the global averaging.
     */
    public static final class RotationEstimate {
        public final double[] rotation;
        public final List<Integer> inliers;
        public final double residualRadians;

        public RotationEstimate(double[] rotation, List<Integer> inliers, double residualRadians) {
            this.rotation = rotation;
            this.inliers = inliers;
            this.residualRadians = residualRadians;
        }

        public int getInlierCount() {
            return inliers.size();
        }
    }

    /**
     * Finds the rotation with {@code to ≈ rotation·from} by RANSAC.
     *
     * {@code from} and {@code to} are matched unit bearings; a correspondence agrees with a
     * hypothesis when the rotated {@code from} vector is within {@code thresholdRadians} of
     * {@code to}. The winning inlier set is re-fit by averaging the per-correspondence
     * rotations, which averages out the feature-location noise.
     */
    public static RotationEstimate estimateRotation(
        double[][] from,
        double[][] to,
        double thresholdRadians,
        int maxIterations,
        Random random,
        int minInliers
    ) {
        if (from.length < 2) return null;

        RotationEstimate best = null;
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            int first = random.nextInt(from.length);
            int second = random.nextInt(from.length);
            int guard = 0;
            while (second == first && guard++ < 6) second = random.nextInt(from.length);
            if (second == first) continue;

            double[] candidate = basisRotation(from[first], to[first], from[second], to[second]);
            ArrayList<Integer> inliers = new ArrayList<Integer>(from.length / 2);
            for (int index = 0; index < from.length; index++) {
                double[] applied = apply(candidate, from[index]);
                double err = Math.acos(clamp(dot3(applied, to[index]), -1.0, 1.0));
                if (err <= thresholdRadians) inliers.add(index);
            }
            if (inliers.size() < minInliers) continue;
            if (best == null || inliers.size() > best.getInlierCount()) {
                best = new RotationEstimate(candidate, inliers, residual(inliers, candidate, from, to));
            }
        }

        RotationEstimate winner = best;
        if (winner == null) return null;
        if (winner.getInlierCount() < minInliers) return null;
        // Refit on the consensus set by least squares — Orthogonal Procrustes,
        // which minimizes the squared angular residual over every inlier at
        // once. (Averaging the per-correspondence rotations would be wrong: a
        // single vector pair does not determine the rotation uniquely, because
        // any spin about the target vector leaves it pointing the same way.)
        double[][] a = new double[winner.inliers.size()][];
        double[][] b = new double[winner.inliers.size()][];
        for (int i = 0; i < winner.inliers.size(); i++) {
            a[i] = from[winner.inliers.get(i)];
            b[i] = to[winner.inliers.get(i)];
        }
        double[] refit = procrustes(a, b);
        ArrayList<Integer> refitInliers = new ArrayList<Integer>();
        for (int index = 0; index < from.length; index++) {
            double[] applied = apply(refit, from[index]);
            if (Math.acos(clamp(dot3(applied, to[index]), -1.0, 1.0)) <= thresholdRadians) {
                refitInliers.add(index);
            }
        }
        return new RotationEstimate(refit, refitInliers, residual(refitInliers, refit, from, to));
    }

    public static RotationEstimate estimateRotation(
        double[][] from,
        double[][] to,
        double thresholdRadians,
        int maxIterations,
        Random random
    ) {
        return estimateRotation(from, to, thresholdRadians, maxIterations, random, 2);
    }

    private static double residual(
        List<Integer> inliers,
        double[] rotation,
        double[][] from,
        double[][] to
    ) {
        ArrayList<Double> errors = new ArrayList<Double>(inliers.size());
        for (int i = 0; i < inliers.size(); i++) {
            int index = inliers.get(i);
            double[] applied = apply(rotation, from[index]);
            errors.add(Math.acos(clamp(dot3(applied, to[index]), -1.0, 1.0)));
        }
        Collections.sort(errors);
        return errors.isEmpty() ? 0.0 : errors.get(errors.size() / 2);
    }

    /**
     * Weighted mean of rotations, valid because the inputs are close.
     *
     * Quaternions are averaged (signs aligned to the first) and renormalised.
     * This is the Karcher mean to first order, which is plenty for the few
     * degrees of correction this pipeline hunts.
     */
    public static double[] weightedMeanRotation(List<double[]> rotations, List<Double> weights) {
        if (rotations.isEmpty()) return identity();
        double[] reference = matrixToQuaternion(rotations.get(0));
        double[] sum = new double[] {0.0, 0.0, 0.0, 0.0};
        for (int index = 0; index < rotations.size(); index++) {
            double[] q = matrixToQuaternion(rotations.get(index));
            if (dot4(q, reference) < 0.0) q = scale4(q, -1.0);
            sum = add4(sum, scale4(q, weights.get(index)));
        }
        double magnitude = norm4(sum);
        if (magnitude < 1e-9) return rotations.get(0);
        return quaternionToMatrix(scale4(sum, 1.0 / magnitude));
    }

    /**
     * One measured rotation edge in the pose graph.
     *
     * {@link #rotation} maps frame {@link #from}'s camera axes to frame {@link #to}'s: a matched
     * bearing {@code b} in {@code from}'s frame reappears as {@code rotation·b} in {@code to}'s frame.
     */
    public static final class RotationEdge {
        public final int from;
        public final int to;
        public final double[] rotation;
        public final double weight;

        public RotationEdge(int from, int to, double[] rotation, double weight) {
            this.from = from;
            this.to = to;
            this.rotation = rotation;
            this.weight = weight;
        }
    }

    /**
     * Refines absolute rotations against the measured edges.
     *
     * Starts from {@code initial} (the sensor rotations) and runs Gauss–Seidel: every
     * frame except the anchor (index 0, held at its sensor value so the result
     * keeps the user's reference frame) is replaced by the weighted mean of the
     * estimates its neighbours' rotations imply. Converges to the least-squares
     * alignment because the corrections are small.
     */
    public static List<double[]> averageRotations(
        List<double[]> initial,
        List<RotationEdge> edges,
        int iterations
    ) {
        ArrayList<double[]> current = new ArrayList<double[]>(initial.size());
        for (int i = 0; i < initial.size(); i++) {
            current.add(Arrays.copyOf(initial.get(i), initial.get(i).length));
        }
        for (int iteration = 0; iteration < iterations; iteration++) {
            for (int i = 1; i < current.size(); i++) {
                ArrayList<double[]> estimates = new ArrayList<double[]>();
                ArrayList<Double> weights = new ArrayList<Double>();
                for (int e = 0; e < edges.size(); e++) {
                    RotationEdge edge = edges.get(e);
                    if (i == edge.from) {
                        estimates.add(multiply(current.get(edge.to), edge.rotation));
                        weights.add(edge.weight);
                    } else if (i == edge.to) {
                        estimates.add(multiply(current.get(edge.from), transpose(edge.rotation)));
                        weights.add(edge.weight);
                    }
                }
                if (!estimates.isEmpty()) {
                    current.set(i, weightedMeanRotation(estimates, weights));
                }
            }
        }
        return current;
    }

    /**
     * One matched feature correspondence used to estimate focal corrections.
     *
     * The two unit bearings {@link #transformedFrom} and {@link #toBearing} should agree: the
     * first is the *from* frame's bearing rotated into the *to* frame by the
     * edge's measured rotation, so the residual is simply their difference. Each
     * carries the derivative of its bearing with respect to the log-scale of its
     * own frame's focal length — {@link #transformedFromDerivative} is the derivative
     * of the already-rotated bearing — which is what turns the residual into a
     * linear least-squares problem in the per-frame corrections.
     */
    public static final class FocalCorrespondence {
        public final int from;
        public final int to;
        public final double[] transformedFrom;
        public final double[] toBearing;
        public final double[] transformedFromDerivative;
        public final double[] toDerivative;

        public FocalCorrespondence(
            int from,
            int to,
            double[] transformedFrom,
            double[] toBearing,
            double[] transformedFromDerivative,
            double[] toDerivative
        ) {
            this.from = from;
            this.to = to;
            this.transformedFrom = transformedFrom;
            this.toBearing = toBearing;
            this.transformedFromDerivative = transformedFromDerivative;
            this.toDerivative = toDerivative;
        }
    }

    /**
     * Least-squares per-frame focal corrections from matched correspondences.
     *
     * A focal-length error shows up as a systematic angular error between
     * matched bearings: the pixels were projected through the wrong focal
     * length, so the unit bearings recovered from them are pulled toward or
     * away from the optical axis. For a small log-scale correction {@code δ} per
     * frame, each correspondence's residual
     *
     *   r(δ_from, δ_to) = R·p_from(e^δ_from) − p_to(e^δ_to)
     *
     * is linearised with the derivative vectors the correspondence carries, and
     * the resulting normal equations are solved by Gauss–Seidel over the frames
     * — the same style {@link #averageRotations} uses for the rotations.
     *
     * {@code anchorFrame} stays at zero. Rotation averaging already fixes that frame
     * so the result keeps the user's reference frame, and pinning its focal
     * length removes the one remaining gauge: the whole sphere may be scaled by
     * a constant without changing any alignment, so the absolute scale is only
     * defined relative to a frame that is held still. Corrections are clamped
     * to ±{@code maxCorrection} (about ±16% of the focal length) each sweep, so a
     * degenerate or poorly-conditioned solve can never bend a frame out of
     * shape; frames the correspondences never touch simply keep their reported
     * focal length.
     *
     * Returns one log-scale per frame (zero for the anchor); the caller applies
     * {@code e^δ} to the frame's focal lengths.
     */
    public static double[] refineFocalLengths(
        List<FocalCorrespondence> correspondences,
        int frameCount,
        int iterations,
        double maxCorrection,
        int anchorFrame
    ) {
        double[] delta = new double[frameCount];
        if (correspondences.isEmpty() || frameCount < 2) return delta;

        double[][] hessian = new double[frameCount][frameCount];
        double[] gradient = new double[frameCount];
        for (int c = 0; c < correspondences.size(); c++) {
            FocalCorrespondence correspondence = correspondences.get(c);
            int from = correspondence.from;
            int to = correspondence.to;
            if (from < 0 || from >= frameCount || to < 0 || to >= frameCount) continue;
            // r = r₀ + δ_from·d_from − δ_to·d_to, so the Hessian block for the
            // pair carries the cross term negative and the gradient of the
            // *to* frame changes sign against the *from* one.
            double[] residual = sub3(correspondence.transformedFrom, correspondence.toBearing);
            double[] dFrom = correspondence.transformedFromDerivative;
            double[] dTo = correspondence.toDerivative;
            hessian[from][from] += dot3(dFrom, dFrom);
            hessian[to][to] += dot3(dTo, dTo);
            hessian[from][to] -= dot3(dFrom, dTo);
            hessian[to][from] = hessian[from][to];
            gradient[from] -= dot3(dFrom, residual);
            gradient[to] += dot3(dTo, residual);
        }

        // Gauss–Seidel over the frames; the anchor is skipped so its delta
        // stays zero. The deltas carry over between sweeps, which is a warm
        // start rather than a bug: each pass refines the previous estimate.
        for (int iteration = 0; iteration < iterations; iteration++) {
            for (int frame = 0; frame < frameCount; frame++) {
                if (frame == anchorFrame) continue;
                double estimate = gradient[frame];
                for (int neighbour = 0; neighbour < frameCount; neighbour++) {
                    if (neighbour != frame) estimate -= hessian[frame][neighbour] * delta[neighbour];
                }
                double diagonal = hessian[frame][frame];
                if (diagonal > 1e-9) {
                    delta[frame] = clamp(estimate / diagonal, -maxCorrection, maxCorrection);
                } else {
                    delta[frame] = 0.0;
                }
            }
        }

        for (int frame = 0; frame < frameCount; frame++) {
            if (!Double.isFinite(delta[frame])) delta[frame] = 0.0;
        }
        return delta;
    }

    public static double[] refineFocalLengths(List<FocalCorrespondence> correspondences, int frameCount) {
        return refineFocalLengths(correspondences, frameCount, 8, 0.15, 0);
    }

    public static double[] refineFocalLengths(
        List<FocalCorrespondence> correspondences,
        int frameCount,
        double maxCorrection
    ) {
        return refineFocalLengths(correspondences, frameCount, 8, maxCorrection, 0);
    }

    /**
     * Best rotation taking the unit bearings {@code a} onto {@code b} by least squares.
     *
     * Orthogonal Procrustes: build H = Σ b_i·a_i^T, take its SVD H = U S V^T,
     * and return R = U·V^T corrected to a proper rotation. This is the
     * maximum-likelihood rotation under isotropic noise, and the SVD is small
     * enough (3×3) to be done here with cyclic Jacobi rotations instead of
     * pulling in a linear-algebra library.
     */
    public static double[] procrustes(double[][] a, double[][] b) {
        if (a.length == 0 || a.length != b.length) {
            throw new IllegalArgumentException("procrustes needs matching, non-empty points");
        }
        double[] h = new double[SIZE];
        for (int i = 0; i < a.length; i++) {
            for (int r = 0; r <= 2; r++) {
                for (int c = 0; c <= 2; c++) h[r * 3 + c] += b[i][r] * a[i][c];
            }
        }
        // Right singular vectors from the eigen-decomposition of H^T·H.
        double[] mt = new double[SIZE];
        for (int r = 0; r <= 2; r++) {
            for (int c = 0; c <= 2; c++) {
                mt[r * 3 + c] = h[r] * h[c] + h[3 + r] * h[3 + c] + h[6 + r] * h[6 + c];
            }
        }
        EigenDecomposition eigen = symmetricEigen3x3(mt);
        double[] eigenvalues = eigen.values;
        double[] v = eigen.vectors;

        // Left singular vectors: u_i = H·v_i / s_i, where v_i is the i-th column
        // of V and s_i its singular value. H is row-major, so row r is
        // h[r*3 .. r*3+2].
        double[] u = new double[SIZE];
        for (int i = 0; i <= 2; i++) {
            double singular = Math.sqrt(Math.max(eigenvalues[i], 0.0));
            if (singular < 1e-9) {
                // Degenerate direction: leave the column zero; the determinant
                // fix below still produces a valid rotation in practice.
                continue;
            }
            for (int r = 0; r <= 2; r++) {
                u[r * 3 + i] = (h[r * 3] * v[i] + h[r * 3 + 1] * v[3 + i] + h[r * 3 + 2] * v[6 + i]) / singular;
            }
        }

        double[] rotation = new double[SIZE];
        // R = U·V^T, so the j-th column of V is read by row index.
        for (int i = 0; i <= 2; i++) {
            for (int j = 0; j <= 2; j++) {
                rotation[i * 3 + j] =
                    u[i * 3] * v[3 * j] + u[i * 3 + 1] * v[3 * j + 1] + u[i * 3 + 2] * v[3 * j + 2];
            }
        }
        if (det3(rotation) < 0.0) {
            // Reflect to a proper rotation by flipping the least-significant
            // left singular vector.
            for (int r = 0; r <= 2; r++) u[r * 3 + 2] = -u[r * 3 + 2];
            rotation = new double[SIZE];
            for (int i = 0; i <= 2; i++) {
                for (int j = 0; j <= 2; j++) {
                    rotation[i * 3 + j] =
                        u[i * 3] * v[3 * j] + u[i * 3 + 1] * v[3 * j + 1] + u[i * 3 + 2] * v[3 * j + 2];
                }
            }
        }
        return rotation;
    }

    /**
     * Eigenvalues and eigenvectors of a symmetric 3×3 matrix.
     *
     * {@link #values} are in descending order; {@link #vectors} holds the matching
     * eigenvectors as columns of a row-major 3×3 matrix.
     */
    public static final class EigenDecomposition {
        public final double[] values;
        public final double[] vectors;

        public EigenDecomposition(double[] values, double[] vectors) {
            this.values = values;
            this.vectors = vectors;
        }
    }

    /**
     * Eigen-decomposition of a symmetric 3×3 matrix by cyclic Jacobi rotations.
     *
     * Returns the eigenvalues in descending order and the eigenvectors as the
     * columns of the second array, in matching order. The implementation is the
     * textbook sweep: zero the largest off-diagonal element with a Givens
     * rotation, accumulate the rotations into the eigenvector matrix, repeat.
     */
    public static EigenDecomposition symmetricEigen3x3(double[] matrix) {
        double[] a = Arrays.copyOf(matrix, matrix.length);
        double[] v = new double[] {1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0};
        for (int sweep = 0; sweep < 32; sweep++) {
            double[] off = new double[] {Math.abs(a[1]), Math.abs(a[2]), Math.abs(a[5])};
            double max = Math.max(off[0], Math.max(off[1], off[2]));
            if (max < 1e-15) continue;
            int p;
            int q;
            if (off[0] >= off[1] && off[0] >= off[2]) {
                p = 0;
                q = 1;
            } else if (off[1] >= off[2]) {
                p = 0;
                q = 2;
            } else {
                p = 1;
                q = 2;
            }
            double apq = a[p * 3 + q];
            if (Math.abs(apq) < 1e-15) continue;
            double app = a[p * 3 + p];
            double aqq = a[q * 3 + q];
            double theta = 0.5 * Math.atan2(2.0 * apq, aqq - app);
            double c = Math.cos(theta);
            double s = Math.sin(theta);
            int r = 3 - p - q;

            double newPp = c * c * app - 2 * c * s * apq + s * s * aqq;
            double newQq = s * s * app + 2 * c * s * apq + c * c * aqq;
            double newPq = (c * c - s * s) * apq + c * s * (app - aqq);
            double apr = a[p * 3 + r];
            double aqr = a[q * 3 + r];
            double newPr = c * apr - s * aqr;
            double newQr = s * apr + c * aqr;

            a[p * 3 + p] = newPp;
            a[q * 3 + q] = newQq;
            a[p * 3 + q] = newPq;
            a[q * 3 + p] = newPq;
            a[p * 3 + r] = newPr;
            a[r * 3 + p] = newPr;
            a[q * 3 + r] = newQr;
            a[r * 3 + q] = newQr;

            // Accumulate the eigenvectors. The sweep is A' = J^T·A·J, so the
            // change of basis that diagonalises the original matrix accumulates
            // as V' = V·J — a rotation of columns p and q.
            for (int i = 0; i <= 2; i++) {
                double vip = v[i * 3 + p];
                double viq = v[i * 3 + q];
                v[i * 3 + p] = c * vip - s * viq;
                v[i * 3 + q] = s * vip + c * viq;
            }
        }

        int[] order = new int[] {0, 1, 2};
        double[] values = new double[] {a[0], a[4], a[8]};
        for (int i = 0; i <= 2; i++) {
            for (int j = i + 1; j <= 2; j++) {
                if (values[order[j]] > values[order[i]]) {
                    int tmp = order[i];
                    order[i] = order[j];
                    order[j] = tmp;
                }
            }
        }
        // Reorder so that returned column i is original column order[i]. `v` is
        // row-major, so a column lives at stride 3 and the interleave goes by
        // row index, not by column — laying the columns out back-to-back would
        // transpose the result.
        return new EigenDecomposition(
            new double[] {values[order[0]], values[order[1]], values[order[2]]},
            new double[] {
                v[order[0]], v[order[1]], v[order[2]],
                v[3 + order[0]], v[3 + order[1]], v[3 + order[2]],
                v[6 + order[0]], v[6 + order[1]], v[6 + order[2]],
            }
        );
    }

    private static double det3(double[] m) {
        return m[0] * (m[4] * m[8] - m[5] * m[7])
            - m[1] * (m[3] * m[8] - m[5] * m[6])
            + m[2] * (m[3] * m[7] - m[4] * m[6]);
    }

    // ---- Vector helpers (3-vectors and quaternions) ----

    public static double dot3(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    public static double[] cross3(double[] a, double[] b) {
        return new double[] {
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0],
        };
    }

    public static double[] add3(double[] a, double[] b) {
        return new double[] {a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    public static double[] sub3(double[] a, double[] b) {
        return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    public static double[] scale3(double[] a, double s) {
        return new double[] {a[0] * s, a[1] * s, a[2] * s};
    }

    public static double norm3(double[] a) {
        return Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
    }

    public static double[] normalize3(double[] a) {
        double magnitude = norm3(a);
        return magnitude < 1e-12 ? new double[] {0.0, 0.0, 1.0} : scale3(a, 1.0 / magnitude);
    }

    private static double dot4(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
    }

    private static double[] add4(double[] a, double[] b) {
        return new double[] {a[0] + b[0], a[1] + b[1], a[2] + b[2], a[3] + b[3]};
    }

    private static double[] scale4(double[] a, double s) {
        return new double[] {a[0] * s, a[1] * s, a[2] * s, a[3] * s};
    }

    private static double norm4(double[] a) {
        return Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2] + a[3] * a[3]);
    }

    public static double[] quaternionToMatrix(double[] q) {
        double x = q[0];
        double y = q[1];
        double z = q[2];
        double w = q[3];
        double n = x * x + y * y + z * z + w * w;
        if (n < 1e-12) return identity();
        double s = 2.0 / n;
        return new double[] {
            1 - s * (y * y + z * z), s * (x * y - w * z), s * (x * z + w * y),
            s * (x * y + w * z), 1 - s * (x * x + z * z), s * (y * z - w * x),
            s * (x * z - w * y), s * (y * z + w * x), 1 - s * (x * x + y * y),
        };
    }

    /** Shepperd's method: numerically stable quaternion from a rotation matrix. */
    public static double[] matrixToQuaternion(double[] m) {
        double trace = m[0] + m[4] + m[8];
        if (trace > 0.0) {
            double s = Math.sqrt(trace + 1.0);
            double w = 0.5 * s;
            s = 0.5 / s;
            return new double[] {
                (m[7] - m[5]) * s,
                (m[2] - m[6]) * s,
                (m[3] - m[1]) * s,
                w,
            };
        } else if (m[0] > m[4] && m[0] > m[8]) {
            double s = Math.sqrt(1.0 + m[0] - m[4] - m[8]);
            double x = 0.5 * s;
            s = 0.5 / s;
            return new double[] {
                x,
                (m[1] + m[3]) * s,
                (m[2] + m[6]) * s,
                (m[7] - m[5]) * s,
            };
        } else if (m[4] > m[8]) {
            double s = Math.sqrt(1.0 + m[4] - m[0] - m[8]);
            double y = 0.5 * s;
            s = 0.5 / s;
            return new double[] {
                (m[1] + m[3]) * s,
                y,
                (m[5] + m[7]) * s,
                (m[2] - m[6]) * s,
            };
        } else {
            double s = Math.sqrt(1.0 + m[8] - m[0] - m[4]);
            double z = 0.5 * s;
            s = 0.5 / s;
            return new double[] {
                (m[2] + m[6]) * s,
                (m[5] + m[7]) * s,
                z,
                (m[3] - m[1]) * s,
            };
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
