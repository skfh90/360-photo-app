package com.n30dyn4m1c.photosphere.stitching;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;

/**
 * The multi-label seam solver: assigns every canvas pixel to exactly one frame.
 *
 * Where the renderer's cross-fade lets every overlapping frame contribute to a
 * pixel, seam carving asks which single frame should paint it and cuts there,
 * reserving the blend for a few pixels either side of the cut. The assignment
 * is the minimizer of an energy over the label grid:
 *
 * <pre>
 * E(f) = Σ_p D(p, f(p))  +  Σ_{edges (p,q)} λ · ( |I_fp(p) − I_fq(p)| + |I_fp(q) − I_fq(q)| )
 * </pre>
 *
 * The data term {@code D(p, i)} says how far frame i's sample sits from the mean of
 * every frame covering p — the frame closest to what the overlap actually
 * shows wins the interior. The edge term is the *seam cost*: cutting between
 * pixels p and q with different frames costs how differently the two frames
 * see p and q, so a cut through a region where the frames agree is cheap and a
 * cut through a ghost is expensive. {@code λ} scales the two against each other.
 *
 * The energy is minimized with α-expansion (Boykov–Veksler–Zabih): each label
 * in turn offers every pixel the move "keep your frame, or switch to this one",
 * and the binary subproblem is an s-t min-cut on the pixel grid. The smoothness
 * term is a metric — the colour difference at a pixel between any two labels is
 * a Euclidean distance, with a frame that does not cover the pixel filled by
 * that pixel's mean colour, and the sum over two endpoints of two metrics is a
 * metric — so every expansion move is submodular and its exact min-cut can
 * never raise the energy. A couple of passes over the labels converge to a
 * local minimum that is typically global for the small label sets a capture
 * produces.
 *
 * Everything here is plain Java with no OpenCV, so the whole solver is
 * exercised by JVM unit tests against an exhaustive search on tiny grids.
 */
public final class SeamSolver {

    /** Residual edges at or below this carry no flow. */
    private static final double EPS = 1e-9;

    /** A label the current expansion move forbids: switching to it costs this. */
    private static final double INF_UNARY = 1e15;

    private SeamSolver() {}

    /** Solves the assignment, returning one frame index per grid pixel. */
    public static int[] solve(PackedGrid grid, double smoothLambda) {
        return solve(grid, smoothLambda, 2, null);
    }

    public static int[] solve(PackedGrid grid, double smoothLambda, int maxPasses) {
        return solve(grid, smoothLambda, maxPasses, null);
    }

    public static int[] solve(PackedGrid grid, double smoothLambda, int maxPasses, Runnable onExpansion) {
        int[] labels = greedyLabels(grid);

        // Large frames first: an expansion of a frame that covers many pixels
        // does the most work, and solving it before the smaller ones keeps each
        // later move within the context the big frame already set.
        int[] order = labelOrder(grid);
        for (int pass = 0; pass < maxPasses; pass++) {
            boolean changed = false;
            for (int i = 0; i < order.length; i++) {
                int alpha = order[i];
                if (expandTo(grid, smoothLambda, labels, alpha)) changed = true;
                if (onExpansion != null) onExpansion.run();
            }
            if (!changed) return labels;
        }
        return labels;
    }

    /**
     * The energy {@link #solve} minimizes, evaluated for {@code labels} — kept for tests and
     * diagnostics to reason about a solved assignment.
     */
    public static double energy(PackedGrid grid, int[] labels, double smoothLambda) {
        double total = 0.0;
        for (int n = 0; n < grid.nodeCount; n++) {
            int off = grid.offsets[n];
            for (int j = 0; j < grid.nodeLabelCount[n]; j++) {
                if (grid.nodeLabels[off + j] == labels[n]) {
                    total += grid.nodeData[off + j];
                    break;
                }
            }
        }
        // Four separate scratch colours so an edge can never read a stale one.
        float[] fpAtN = new float[3];
        float[] fqAtN = new float[3];
        float[] fpAtM = new float[3];
        float[] fqAtM = new float[3];
        for (int r = 0; r < grid.height; r++) {
            for (int c = 0; c < grid.width; c++) {
                int n = r * grid.width + c;
                int fp = labels[n];
                if (fp < 0) continue;
                colorOf(grid, n, fp, fpAtN, 0);
                if (c + 1 < grid.width) {
                    int m = n + 1;
                    int fq = labels[m];
                    if (fq >= 0) {
                        colorOf(grid, n, fq, fqAtN, 0);
                        colorOf(grid, m, fp, fpAtM, 0);
                        colorOf(grid, m, fq, fqAtM, 0);
                        total += smoothLambda * (dist(fpAtN, 0, fqAtN, 0) + dist(fpAtM, 0, fqAtM, 0));
                    }
                }
                if (r + 1 < grid.height) {
                    int m = n + grid.width;
                    int fq = labels[m];
                    if (fq >= 0) {
                        colorOf(grid, n, fq, fqAtN, 0);
                        colorOf(grid, m, fp, fpAtM, 0);
                        colorOf(grid, m, fq, fqAtM, 0);
                        total += smoothLambda * (dist(fpAtN, 0, fqAtN, 0) + dist(fpAtM, 0, fqAtM, 0));
                    }
                }
            }
        }
        return total;
    }

    /** Each node's cheapest label on the data term alone; also the solver's start. */
    public static int[] greedyLabels(PackedGrid grid) {
        int[] labels = new int[grid.nodeCount];
        Arrays.fill(labels, -1);
        for (int n = 0; n < grid.nodeCount; n++) {
            int count = grid.nodeLabelCount[n];
            if (count == 0) continue;
            int off = grid.offsets[n];
            int best = grid.nodeLabels[off];
            double bestCost = grid.nodeData[off];
            for (int j = 1; j < count; j++) {
                double cost = grid.nodeData[off + j];
                if (cost < bestCost) {
                    bestCost = cost;
                    best = grid.nodeLabels[off + j];
                }
            }
            labels[n] = best;
        }
        return labels;
    }

    /** Labels that cover at least one pixel, largest coverage first. */
    private static int[] labelOrder(PackedGrid grid) {
        int[] coverage = new int[grid.labelCount];
        for (int n = 0; n < grid.nodeCount; n++) {
            int off = grid.offsets[n];
            for (int j = 0; j < grid.nodeLabelCount[n]; j++) coverage[grid.nodeLabels[off + j]]++;
        }
        ArrayList<Integer> order = new ArrayList<Integer>();
        for (int label = 0; label < grid.labelCount; label++) {
            if (coverage[label] > 0) order.add(label);
        }
        final int[] coverageRef = coverage;
        Collections.sort(order, new Comparator<Integer>() {
            public int compare(Integer a, Integer b) {
                return Integer.compare(coverageRef[b], coverageRef[a]);
            }
        });
        int[] result = new int[order.size()];
        for (int i = 0; i < order.size(); i++) result[i] = order.get(i);
        return result;
    }

    /**
     * One α-expansion move: every pixel may keep its current frame or switch to
     * {@code alpha}, solved exactly as an s-t min-cut. Returns whether any pixel moved.
     *
     * Only the pixels {@code alpha} covers can move; every other pixel is pinned, and
     * an edge that joins a moving pixel to a pinned one collapses into a unary
     * on the moving side rather than a graph node of its own. The graph is
     * therefore built over the moving pixels alone.
     */
    private static boolean expandTo(PackedGrid grid, double smoothLambda, int[] labels, int alpha) {
        // Nodes that may move: exactly those [alpha] covers.
        boolean[] active = new boolean[grid.nodeCount];
        boolean anyActive = false;
        for (int n = 0; n < grid.nodeCount; n++) {
            if (grid.nodeLabelCount[n] == 0) continue;
            int off = grid.offsets[n];
            for (int j = 0; j < grid.nodeLabelCount[n]; j++) {
                if (grid.nodeLabels[off + j] == alpha) {
                    active[n] = true;
                    anyActive = true;
                    break;
                }
            }
        }
        if (!anyActive) return false;

        int[] vertexOf = new int[grid.nodeCount];
        Arrays.fill(vertexOf, -1);
        int vertexCount = 0;
        for (int n = 0; n < grid.nodeCount; n++) {
            if (active[n]) vertexOf[n] = vertexCount++;
        }

        MaxFlow flow = new MaxFlow(vertexCount + 2);
        int source = vertexCount;
        int sink = vertexCount + 1;

        // Per-vertex costs of keeping the current label (x = 0) or switching to
        // alpha (x = 1); the edge terms below accumulate into them.
        double[] u0 = new double[vertexCount];
        double[] u1 = new double[vertexCount];
        for (int n = 0; n < grid.nodeCount; n++) {
            int v = vertexOf[n];
            if (v == -1) continue;
            u0[v] = dataOf(grid, n, labels[n]);
            u1[v] = dataOf(grid, n, alpha);
        }

        // The smoothness needs each pixel's colour under its current label and
        // under alpha (mean-filled where alpha is absent), computed once per
        // expansion and reused across all the edges that touch the pixel.
        float[] curColor = new float[grid.nodeCount * 3];
        float[] alphaColor = new float[grid.nodeCount * 3];
        for (int n = 0; n < grid.nodeCount; n++) {
            if (grid.nodeLabelCount[n] == 0) continue;
            colorOf(grid, n, labels[n], curColor, n * 3);
            colorOf(grid, n, alpha, alphaColor, n * 3);
        }

        // Each grid edge becomes, for the binary move, either a full pairwise
        // term (both endpoints can switch) or a unary on the switching endpoint
        // (the other is pinned). The pairwise term decomposes into a constant,
        // two unaries and one n-link; the n-link is exactly the submodularity
        // surplus (B + C − A) / 2, which the metric property keeps non-negative.
        float[] scratch = new float[6];
        for (int r = 0; r < grid.height; r++) {
            for (int c = 0; c < grid.width; c++) {
                int n = r * grid.width + c;
                if (grid.nodeLabelCount[n] == 0) continue;
                if (c + 1 < grid.width) {
                    int m = n + 1;
                    if (grid.nodeLabelCount[m] > 0) {
                        addEdgeTerm(
                            grid, smoothLambda, labels, active, vertexOf, flow,
                            n, m, curColor, alphaColor, scratch, u0, u1
                        );
                    }
                }
                if (r + 1 < grid.height) {
                    int m = n + grid.width;
                    if (grid.nodeLabelCount[m] > 0) {
                        addEdgeTerm(
                            grid, smoothLambda, labels, active, vertexOf, flow,
                            n, m, curColor, alphaColor, scratch, u0, u1
                        );
                    }
                }
            }
        }

        // t-links encode the unaries: with x = 0 on the source side, cutting
        // p→sink pays "keep" and cutting source→p pays "switch". A common
        // offset keeps both capacities non-negative without changing the
        // minimizer.
        for (int n = 0; n < grid.nodeCount; n++) {
            int v = vertexOf[n];
            if (v == -1) continue;
            double offset = Math.max(0.0, Math.max(-u0[v], -u1[v]));
            if (u1[v] + offset > 0.0) flow.addEdge(source, v, u1[v] + offset);
            if (u0[v] + offset > 0.0) flow.addEdge(v, sink, u0[v] + offset);
        }

        flow.maxflow(source, sink);
        boolean[] keep = flow.reachableFrom(source);

        boolean changed = false;
        for (int n = 0; n < grid.nodeCount; n++) {
            int v = vertexOf[n];
            if (v == -1) continue;
            // Not reachable from the source means the cut put the pixel on the
            // switch side.
            if (!keep[v] && labels[n] != alpha) {
                labels[n] = alpha;
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Adds one grid edge's contribution to the expansion graph.
     *
     * {@code n} is the lower-left endpoint of a canonical (right or down) edge to {@code m};
     * {@code curColor}/{@code alphaColor} hold each node's colour under its current label
     * and under the expansion label, and {@code scratch} is a 6-float buffer for the
     * two cross colours the edge needs.
     */
    private static void addEdgeTerm(
        PackedGrid grid,
        double smoothLambda,
        int[] labels,
        boolean[] active,
        int[] vertexOf,
        MaxFlow flow,
        int n,
        int m,
        float[] curColor,
        float[] alphaColor,
        float[] scratch,
        double[] u0,
        double[] u1
    ) {
        boolean activeN = active[n];
        boolean activeM = active[m];
        if (!activeN && !activeM) return;

        int fp = labels[n];
        int fq = labels[m];
        // scratch[0..2] = I_fq(p), scratch[3..5] = I_fp(q).
        colorOf(grid, n, fq, scratch, 0);
        colorOf(grid, m, fp, scratch, 3);

        double a = smoothLambda * (dist(curColor, n * 3, scratch, 0) + dist(scratch, 3, curColor, m * 3));
        double b = smoothLambda * (dist(curColor, n * 3, alphaColor, n * 3) + dist(scratch, 3, alphaColor, m * 3));
        double c = smoothLambda * (dist(alphaColor, n * 3, scratch, 0) + dist(alphaColor, m * 3, curColor, m * 3));

        if (activeN && activeM) {
            int vN = vertexOf[n];
            int vM = vertexOf[m];
            double cutWeight = Math.max(0.0, (b + c - a) / 2.0);
            u1[vN] += c - a - cutWeight;
            u1[vM] += b - a - cutWeight;
            if (cutWeight > 0.0) flow.addUndirectedEdge(vN, vM, cutWeight);
        } else if (activeN) {
            int vN = vertexOf[n];
            u0[vN] += a;
            u1[vN] += c;
        } else {
            int vM = vertexOf[m];
            u0[vM] += a;
            u1[vM] += b;
        }
    }

    /** Cost of assigning {@code node} the label {@code label}, from the packed data. */
    private static double dataOf(PackedGrid grid, int node, int label) {
        int off = grid.offsets[node];
        for (int j = 0; j < grid.nodeLabelCount[node]; j++) {
            if (grid.nodeLabels[off + j] == label) return grid.nodeData[off + j];
        }
        return INF_UNARY;
    }

    /**
     * The colour of {@code label} at {@code node}, written into {@code out} at {@code outOffset}.
     *
     * A label the node's pixel is not covered by reads that pixel's mean colour
     * — the fill that makes the pairwise seam cost a metric, and the whole
     * reason the expansion moves stay submodular.
     */
    private static void colorOf(PackedGrid grid, int node, int label, float[] out, int outOffset) {
        int off = grid.offsets[node];
        for (int j = 0; j < grid.nodeLabelCount[node]; j++) {
            if (grid.nodeLabels[off + j] == label) {
                int base = (off + j) * 3;
                out[outOffset] = grid.nodeColors[base];
                out[outOffset + 1] = grid.nodeColors[base + 1];
                out[outOffset + 2] = grid.nodeColors[base + 2];
                return;
            }
        }
        int base = node * 3;
        out[outOffset] = grid.nodeMean[base];
        out[outOffset + 1] = grid.nodeMean[base + 1];
        out[outOffset + 2] = grid.nodeMean[base + 2];
    }

    /** Euclidean distance between two RGB triples, at the given array offsets. */
    private static double dist(float[] a, int aOffset, float[] b, int bOffset) {
        double d0 = a[aOffset] - b[bOffset];
        double d1 = a[aOffset + 1] - b[bOffset + 1];
        double d2 = a[aOffset + 2] - b[bOffset + 2];
        return Math.sqrt(d0 * d0 + d1 * d1 + d2 * d2);
    }

    /**
     * Dinic's max-flow over a directed graph of real capacities.
     *
     * Every expansion builds a fresh graph and the buffers are grown to fit, so
     * a long stitch churns a fixed pool rather than a new array per move. The
     * level graph stays shallow here — the source reaches every moving pixel
     * directly — so the recursive blocking-flow DFS is well within the stack.
     */
    public static final class MaxFlow {
        private final int vertexCount;
        private final int[] head;
        private int[] to;
        private double[] cap;
        private int[] next;
        private int edgeCount;

        private final int[] level;
        private final int[] queue;
        private final int[] iter;

        public MaxFlow(int vertexCount) {
            this.vertexCount = vertexCount;
            this.head = new int[vertexCount];
            Arrays.fill(this.head, -1);
            this.to = new int[16];
            this.cap = new double[16];
            this.next = new int[16];
            this.edgeCount = 0;
            this.level = new int[vertexCount];
            this.queue = new int[vertexCount];
            this.iter = new int[vertexCount];
        }

        private void addArc(int u, int v, double c) {
            if (edgeCount >= to.length) {
                int grown = to.length * 2;
                to = Arrays.copyOf(to, grown);
                cap = Arrays.copyOf(cap, grown);
                next = Arrays.copyOf(next, grown);
            }
            to[edgeCount] = v;
            cap[edgeCount] = c;
            next[edgeCount] = head[u];
            head[u] = edgeCount;
            edgeCount++;
        }

        /** Directed edge u→v of capacity {@code c}, plus its zero-capacity reverse. */
        public void addEdge(int u, int v, double c) {
            if (c <= 0.0) return;
            addArc(u, v, c);
            addArc(v, u, 0.0);
        }

        /**
         * Undirected edge of capacity {@code c}: cut for a fixed amount whichever way
         * the two endpoints split, which is what a {@code [x_p ≠ x_q]} penalty means.
         */
        public void addUndirectedEdge(int u, int v, double c) {
            if (c <= 0.0) return;
            addArc(u, v, c);
            addArc(v, u, c);
        }

        private boolean bfs(int source, int sink) {
            Arrays.fill(level, -1);
            int read = 0;
            int write = 0;
            level[source] = 0;
            queue[write++] = source;
            while (read < write) {
                int u = queue[read++];
                int e = head[u];
                while (e != -1) {
                    int v = to[e];
                    if (cap[e] > EPS && level[v] < 0) {
                        level[v] = level[u] + 1;
                        queue[write++] = v;
                    }
                    e = next[e];
                }
            }
            return level[sink] >= 0;
        }

        private double dfs(int u, int sink, double pushed) {
            if (u == sink) return pushed;
            int e = iter[u];
            while (e != -1) {
                int v = to[e];
                if (cap[e] > EPS && level[v] == level[u] + 1) {
                    double flow = dfs(v, sink, Math.min(pushed, cap[e]));
                    if (flow > EPS) {
                        cap[e] -= flow;
                        cap[e ^ 1] += flow;
                        return flow;
                    }
                }
                e = next[e];
                iter[u] = e;
            }
            return 0.0;
        }

        public double maxflow(int source, int sink) {
            double total = 0.0;
            while (bfs(source, sink)) {
                System.arraycopy(head, 0, iter, 0, vertexCount);
                while (true) {
                    double flow = dfs(source, sink, Double.MAX_VALUE / 4);
                    if (flow <= EPS) break;
                    total += flow;
                }
            }
            return total;
        }

        /** Nodes reachable from {@code source} through positive residual capacity. */
        public boolean[] reachableFrom(int source) {
            boolean[] seen = new boolean[vertexCount];
            int read = 0;
            int write = 0;
            queue[write++] = source;
            seen[source] = true;
            while (read < write) {
                int u = queue[read++];
                int e = head[u];
                while (e != -1) {
                    int v = to[e];
                    if (cap[e] > EPS && !seen[v]) {
                        seen[v] = true;
                        queue[write++] = v;
                    }
                    e = next[e];
                }
            }
            return seen;
        }
    }
}
