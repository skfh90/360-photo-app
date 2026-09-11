package com.n30dyn4m1c.photosphere.stitching;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Covers the pure-Java seam solver: the Dinic max-flow against an exhaustive
 * minimum cut, and the α-expansion assignment against an exhaustive search over
 * every labelling on tiny grids.
 */
public class SeamSolverTest {

    @Test
    public void maxFlowSaturatesTheMinimumCutOnRandomSmallGraphs() {
        int nodeCount = 6;
        int source = 0;
        int sink = 5;
        Random random = new Random(42);
        for (int trial = 0; trial < 20; trial++) {
            SeamSolver.MaxFlow graph = new SeamSolver.MaxFlow(nodeCount);
            List<int[]> edges = new ArrayList<int[]>();
            for (int u = 0; u < nodeCount; u++) {
                for (int v = 0; v < nodeCount; v++) {
                    if (u == v) {
                        continue;
                    }
                    int capacity = random.nextInt(20);
                    if (capacity > 0) {
                        edges.add(new int[] {u, v, capacity});
                        graph.addEdge(u, v, (double) capacity);
                    }
                }
            }
            double flow = graph.maxflow(source, sink);
            double cut = bruteForceMinCut(nodeCount, edges, source, sink);
            Assert.assertEquals("flow " + flow + " must equal min cut " + cut, cut, flow, 1e-6);
        }
    }

    @Test
    public void anEdgeOfZeroCapacityCarriesNoFlow() {
        SeamSolver.MaxFlow graph = new SeamSolver.MaxFlow(4);
        graph.addEdge(0, 1, 0.0);
        graph.addEdge(0, 2, 5.0);
        graph.addEdge(2, 3, 5.0);
        graph.addEdge(1, 3, 0.0);
        Assert.assertEquals(5.0, graph.maxflow(0, 3), 1e-9);
    }

    @Test
    public void reachableNodesBoundAMinCutOfTheMaxFlowValue() {
        SeamSolver.MaxFlow graph = new SeamSolver.MaxFlow(4);
        graph.addEdge(0, 1, 3.0);
        graph.addEdge(1, 2, 3.0);
        graph.addEdge(2, 3, 3.0);
        double flow = graph.maxflow(0, 3);
        boolean[] reachable = graph.reachableFrom(0);
        // The reachable set is the source side of a min cut: source in, sink
        // out, and the graph edges it separates carry exactly the flow value.
        Assert.assertTrue(reachable[0]);
        Assert.assertTrue(!reachable[3]);
        int[][] edges = new int[][] {
                {0, 1, 3},
                {1, 2, 3},
                {2, 3, 3}
        };
        double cutCapacity = 0.0;
        for (int i = 0; i < edges.length; i++) {
            int[] edge = edges[i];
            if (reachable[edge[0]] && !reachable[edge[1]]) {
                cutCapacity += edge[2];
            }
        }
        Assert.assertEquals(flow, cutCapacity, 1e-9);
    }

    @Test
    public void theSolvedLabellingIsLocallyOptimalUnderSinglePixelFlips() {
        // α-expansion converges to a labelling no single pixel can improve. If
        // the graph construction were wrong, the min-cuts would minimize the
        // wrong energy and this would fail — a stronger check than comparing
        // energies, since the solver's local optimum need not be the global one.
        Random random = new Random(7);
        for (int trial = 0; trial < 10; trial++) {
            PackedGrid grid = randomGrid(3, 3, 2, random);
            double lambda = 3.0;
            int[] solved = SeamSolver.solve(grid, lambda, 4);
            double base = SeamSolver.energy(grid, solved, lambda);
            for (int node = 0; node < grid.nodeCount; node++) {
                int current = solved[node];
                for (int label = 0; label < grid.labelCount; label++) {
                    if (label == current) {
                        continue;
                    }
                    int[] flipped = java.util.Arrays.copyOf(solved, solved.length);
                    flipped[node] = label;
                    double energy = SeamSolver.energy(grid, flipped, lambda);
                    Assert.assertTrue(
                            "single flip at " + node + " to " + label + " improved " + base + " to " + energy,
                            energy >= base - 1e-6);
                }
            }
        }
    }

    @Test
    public void theSolvedLabellingNeverLandsBelowTheTrueOptimum() {
        Random random = new Random(11);
        for (int trial = 0; trial < 6; trial++) {
            PackedGrid grid = randomGrid(2, 3, 3, random);
            double lambda = 2.0;
            int[] solved = SeamSolver.solve(grid, lambda, 4);
            double energy = SeamSolver.energy(grid, solved, lambda);
            double best = exhaustiveMinimum(grid, lambda);
            // A sanity floor: α-expansion can only approximate the global
            // optimum, but it must never do better than it.
            Assert.assertEquals("solver landed below the true minimum", best, energy, 1e-6);
        }
    }

    @Test
    public void theSolverImprovesOnTheDataOnlyStart() {
        Random random = new Random(3);
        for (int trial = 0; trial < 6; trial++) {
            PackedGrid grid = randomGrid(3, 2, 3, random);
            double lambda = 4.0;
            double greedy = SeamSolver.energy(grid, SeamSolver.greedyLabels(grid), lambda);
            int[] solved = SeamSolver.solve(grid, lambda, 4);
            double energy = SeamSolver.energy(grid, solved, lambda);
            Assert.assertTrue("solver did not improve on the greedy start", energy <= greedy + 1e-6);
        }
    }

    @Test
    public void theSeamFollowsThePlaceWhereTheFramesAgree() {
        // A strip of four pixels and two frames. Both frames see grey in the
        // middle; frame 0 owns the left two pixels, frame 1 the right two, and
        // the best cut is between pixels 1 and 2, where the frames agree
        // exactly. The seam must land there rather than at either end.
        int width = 4;
        int height = 1;
        int labelCount = 2;
        float[] grey = new float[] {100f, 100f, 100f};
        float[] bright = new float[] {200f, 200f, 200f};
        float[] data = new float[width * height * labelCount];
        float[] colors = new float[width * height * labelCount * 3];
        for (int n = 0; n < width * height; n++) {
            // Frame 0: left territory (data 0), the border on the right.
            data[n * 2] = n <= 1 ? 0f : 10000f;
            colors[n * 6] = grey[0];
            colors[n * 6 + 1] = grey[1];
            colors[n * 6 + 2] = grey[2];
            // Frame 1: right territory, grey where it agrees with frame 0.
            data[n * 2 + 1] = n >= 2 ? 0f : 10000f;
            float[] color = n >= 2 ? grey : bright;
            int base = (n * 2 + 1) * 3;
            colors[base] = color[0];
            colors[base + 1] = color[1];
            colors[base + 2] = color[2];
        }
        int[] nodeLabelCount = new int[width * height];
        java.util.Arrays.fill(nodeLabelCount, 2);
        int[] nodeLabels = new int[width * height * 2];
        for (int i = 0; i < nodeLabels.length; i++) {
            nodeLabels[i] = i % 2;
        }
        float[] nodeMean = new float[width * height * 3];
        java.util.Arrays.fill(nodeMean, grey[0]);
        PackedGrid grid = new PackedGrid(
                width, height, labelCount, nodeLabelCount, nodeLabels, data, colors, nodeMean);

        int[] solved = SeamSolver.solve(grid, 1.0, 4);
        Assert.assertArrayEquals(new int[] {0, 0, 1, 1}, solved);
        Assert.assertEquals(
                exhaustiveMinimum(grid, 1.0),
                SeamSolver.energy(grid, solved, 1.0),
                1e-6);
    }

    @Test
    public void aSingleFeasibleLabelIsRespectedEverywhere() {
        // Only label 1 has finite cost at every pixel: everything must take it.
        int width = 3;
        int height = 2;
        float[] data = new float[width * height * 2];
        float[] colors = new float[width * height * 2 * 3];
        for (int n = 0; n < width * height; n++) {
            data[n * 2] = 1000000f;
            data[n * 2 + 1] = 1f;
            colors[n * 6 + 2] = 200f;
            int base = (n * 2 + 1) * 3;
            colors[base] = 80f;
            colors[base + 1] = 120f;
            colors[base + 2] = 160f;
        }
        int[] nodeLabelCount = new int[width * height];
        java.util.Arrays.fill(nodeLabelCount, 2);
        int[] nodeLabels = new int[width * height * 2];
        for (int i = 0; i < nodeLabels.length; i++) {
            nodeLabels[i] = i % 2;
        }
        float[] nodeMean = new float[width * height * 3];
        java.util.Arrays.fill(nodeMean, 100f);
        PackedGrid grid = new PackedGrid(
                width, height, 2, nodeLabelCount, nodeLabels, data, colors, nodeMean);
        int[] solved = SeamSolver.solve(grid, 5.0, 4);
        for (int i = 0; i < solved.length; i++) {
            Assert.assertEquals(1, solved[i]);
        }
    }

    /** A dense grid (every label allowed everywhere) with random costs/colours. */
    private PackedGrid randomGrid(int width, int height, int labelCount, Random random) {
        int nodeCount = width * height;
        int[] nodeLabelCount = new int[nodeCount];
        java.util.Arrays.fill(nodeLabelCount, labelCount);
        int[] nodeLabels = new int[nodeCount * labelCount];
        float[] nodeData = new float[nodeCount * labelCount];
        float[] nodeColors = new float[nodeCount * labelCount * 3];
        float[] nodeMean = new float[nodeCount * 3];
        for (int n = 0; n < nodeCount; n++) {
            for (int label = 0; label < labelCount; label++) {
                nodeLabels[n * labelCount + label] = label;
                nodeData[n * labelCount + label] = (float) random.nextInt(10000);
                int base = (n * labelCount + label) * 3;
                nodeColors[base] = (float) random.nextInt(256);
                nodeColors[base + 1] = (float) random.nextInt(256);
                nodeColors[base + 2] = (float) random.nextInt(256);
                nodeMean[n * 3] += nodeColors[base];
                nodeMean[n * 3 + 1] += nodeColors[base + 1];
                nodeMean[n * 3 + 2] += nodeColors[base + 2];
            }
            nodeMean[n * 3] /= labelCount;
            nodeMean[n * 3 + 1] /= labelCount;
            nodeMean[n * 3 + 2] /= labelCount;
        }
        return new PackedGrid(
                width, height, labelCount, nodeLabelCount, nodeLabels, nodeData, nodeColors, nodeMean);
    }

    private double bruteForceMinCut(int nodeCount, List<int[]> edges, int source, int sink) {
        List<Integer> movable = new ArrayList<Integer>();
        for (int i = 0; i < nodeCount; i++) {
            if (i != source && i != sink) {
                movable.add(i);
            }
        }
        boolean[] side = new boolean[nodeCount];
        side[source] = true;
        side[sink] = false;
        double best = Double.MAX_VALUE;
        int combinations = 1 << movable.size();
        for (int mask = 0; mask < combinations; mask++) {
            for (int i = 0; i < movable.size(); i++) {
                side[movable.get(i)] = ((mask >> i) & 1) == 1;
            }
            double cut = 0.0;
            for (int e = 0; e < edges.size(); e++) {
                int[] edge = edges.get(e);
                if (side[edge[0]] && !side[edge[1]]) {
                    cut += edge[2];
                }
            }
            if (cut < best) {
                best = cut;
            }
        }
        return best;
    }

    /** The energy of every possible labelling of a dense grid, minimized. */
    private double exhaustiveMinimum(PackedGrid grid, double lambda) {
        double[] best = new double[] {Double.MAX_VALUE};
        int[] labels = new int[grid.nodeCount];
        recurseLabels(grid, lambda, labels, 0, best);
        return best[0];
    }

    private void recurseLabels(PackedGrid grid, double lambda, int[] labels, int position, double[] best) {
        if (position == grid.nodeCount) {
            double energy = bruteForceEnergy(grid, labels, lambda);
            if (energy < best[0]) {
                best[0] = energy;
            }
            return;
        }
        for (int label = 0; label < grid.labelCount; label++) {
            labels[position] = label;
            recurseLabels(grid, lambda, labels, position + 1, best);
        }
    }

    private double bruteForceEnergy(PackedGrid grid, int[] labels, double lambda) {
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
        for (int r = 0; r < grid.height; r++) {
            for (int c = 0; c < grid.width; c++) {
                int n = r * grid.width + c;
                int fp = labels[n];
                if (c + 1 < grid.width) {
                    int m = n + 1;
                    total += lambda * (differenceAt(grid, n, fp, labels[m]) + differenceAt(grid, m, fp, labels[m]));
                }
                if (r + 1 < grid.height) {
                    int m = n + grid.width;
                    total += lambda * (differenceAt(grid, n, fp, labels[m]) + differenceAt(grid, m, fp, labels[m]));
                }
            }
        }
        return total;
    }

    /** |I_fp(node) − I_fq(node)| for a dense grid. */
    private double differenceAt(PackedGrid grid, int node, int fp, int fq) {
        int off = grid.offsets[node];
        double[] cp = new double[3];
        double[] cq = new double[3];
        for (int j = 0; j < grid.nodeLabelCount[node]; j++) {
            int label = grid.nodeLabels[off + j];
            int base = (off + j) * 3;
            if (label == fp) {
                cp[0] = grid.nodeColors[base];
                cp[1] = grid.nodeColors[base + 1];
                cp[2] = grid.nodeColors[base + 2];
            }
            if (label == fq) {
                cq[0] = grid.nodeColors[base];
                cq[1] = grid.nodeColors[base + 1];
                cq[2] = grid.nodeColors[base + 2];
            }
        }
        double d0 = cp[0] - cq[0];
        double d1 = cp[1] - cq[1];
        double d2 = cp[2] - cq[2];
        return Math.sqrt(d0 * d0 + d1 * d1 + d2 * d2);
    }
}
