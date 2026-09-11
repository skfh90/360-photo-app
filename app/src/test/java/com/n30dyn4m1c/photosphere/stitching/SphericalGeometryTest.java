package com.n30dyn4m1c.photosphere.stitching;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * The geometry the sphere is built out of.
 *
 * This is the half of the stitcher that has no OpenCV in it, and it is the half
 * that decides whether a frame lands in the right place — so it is worth
 * pinning down here rather than discovering on a phone.
 */
public class SphericalGeometryTest {

    private static final double TOLERANCE = 1e-9;

    @Test
    public void aLevelCameraFacingNorthHasTheAxesTheWorldFrameExpects() {
        CameraBasis basis = CameraBasis.of(new CameraPose(0f, 0f, 0f));

        // Forward is north, right is east, up is up.
        assertVector(0.0, 1.0, 0.0, basis.forwardX, basis.forwardY, basis.forwardZ);
        assertVector(1.0, 0.0, 0.0, basis.rightX, basis.rightY, basis.rightZ);
        assertVector(0.0, 0.0, 1.0, basis.upX, basis.upY, basis.upZ);
    }

    @Test
    public void facingEastSwingsTheAxesAQuarterTurn() {
        CameraBasis basis = CameraBasis.of(new CameraPose(90f, 0f, 0f));

        assertVector(1.0, 0.0, 0.0, basis.forwardX, basis.forwardY, basis.forwardZ);
        // "Right" of a camera facing east is south.
        assertVector(0.0, -1.0, 0.0, basis.rightX, basis.rightY, basis.rightZ);
        assertVector(0.0, 0.0, 1.0, basis.upX, basis.upY, basis.upZ);
    }

    @Test
    public void aNegativePitchAimsTheCameraAboveTheHorizon() {
        // The sensor convention is "negative pitch is aimed up".
        CameraBasis basis = CameraBasis.of(new CameraPose(0f, -90f, 0f));

        assertVector(0.0, 0.0, 1.0, basis.forwardX, basis.forwardY, basis.forwardZ);
    }

    @Test
    public void ofPrefersAMeasuredBasisOverTheAnglesWhenThePoseCarriesOne() {
        CameraBasis measured = CameraBasis.of(new CameraPose(10f, 20f, -15f));
        CameraPose pose = new CameraPose(0f, 0f, 0f, measured.toRotationMatrix());

        // The matrix wins even though the angles describe a different pose:
        // the measured basis is exact where a reconstruction from the angles
        // is not (the zenith).
        CameraBasis basis = CameraBasis.of(pose);
        Assert.assertEquals(
                0.0,
                RotationMath.angle(basis.toRotationMatrix(), measured.toRotationMatrix()),
                1e-9);
    }

    @Test
    public void theBasisStaysOrthonormalAndRightHandedAtEveryAttitude() {
        for (int yaw = -180; yaw <= 170; yaw += 30) {
            for (int pitch = -80; pitch <= 80; pitch += 20) {
                for (int roll = -180; roll <= 150; roll += 45) {
                    CameraBasis basis = CameraBasis.of(
                            new CameraPose((float) yaw, (float) pitch, (float) roll));
                    String label = "yaw " + yaw + " pitch " + pitch + " roll " + roll;

                    double[] forward = new double[] {basis.forwardX, basis.forwardY, basis.forwardZ};
                    double[] right = new double[] {basis.rightX, basis.rightY, basis.rightZ};
                    double[] up = new double[] {basis.upX, basis.upY, basis.upZ};

                    Assert.assertEquals(label + ": forward not unit", 1.0, norm(forward), 1e-9);
                    Assert.assertEquals(label + ": right not unit", 1.0, norm(right), 1e-9);
                    Assert.assertEquals(label + ": up not unit", 1.0, norm(up), 1e-9);

                    Assert.assertEquals(label + ": right·forward", 0.0, dot(right, forward), 1e-9);
                    Assert.assertEquals(label + ": up·forward", 0.0, dot(up, forward), 1e-9);
                    Assert.assertEquals(label + ": right·up", 0.0, dot(right, up), 1e-9);

                    // The triple follows the convention SphereProjection places
                    // markers with — "up" is right × forward, which makes
                    // (right, up, forward) left-handed and so `up × right` the
                    // combination that recovers forward.
                    double[] crossed = cross(up, right);
                    assertVector(forward[0], forward[1], forward[2], crossed[0], crossed[1], crossed[2]);
                }
            }
        }
    }

    @Test
    public void projectingADirectionAndRotatingItBackAreInverses() {
        CameraBasis basis = CameraBasis.of(new CameraPose(35f, -12f, 8f));
        double[] direction = Equirectangular.direction(30.0, 5.0);

        double cameraX = basis.lateralOf(direction[0], direction[1], direction[2]);
        double cameraY = basis.verticalOf(direction[0], direction[1], direction[2]);
        double cameraZ = basis.depthOf(direction[0], direction[1], direction[2]);
        double[] world = basis.toWorld(cameraX, cameraY, cameraZ);

        assertVector(direction[0], direction[1], direction[2], world[0], world[1], world[2]);
    }

    @Test
    public void theCanvasCentreIsNorthAndItsEdgesAreTheDateLine() {
        int width = 4096;
        int height = 2048;

        // Longitude 0 sits at the middle column, latitude 0 at the middle row.
        Assert.assertEquals(0.0, Equirectangular.longitudeDegrees(width / 2, width), 0.1);
        Assert.assertEquals(0.0, Equirectangular.latitudeDegrees(height / 2, height), 0.1);

        // The first and last columns straddle the seam.
        Assert.assertEquals(-180.0, Equirectangular.longitudeDegrees(0, width), 0.1);
        Assert.assertEquals(180.0, Equirectangular.longitudeDegrees(width - 1, width), 0.1);

        // Rows run from the north pole down.
        Assert.assertEquals(90.0, Equirectangular.latitudeDegrees(0, height), 0.1);
        Assert.assertEquals(-90.0, Equirectangular.latitudeDegrees(height - 1, height), 0.1);
    }

    @Test
    public void aLongitudeAndLatitudeSurviveATripThroughADirection() {
        for (int longitude = -170; longitude <= 170; longitude += 20) {
            for (int latitude = -80; latitude <= 80; latitude += 20) {
                double[] direction = Equirectangular.direction((double) longitude, (double) latitude);
                Assert.assertEquals(
                        "longitude " + longitude,
                        (double) longitude,
                        Equirectangular.longitudeOf(direction[0], direction[1]),
                        1e-9);
                Assert.assertEquals(
                        "latitude " + latitude,
                        (double) latitude,
                        Equirectangular.latitudeOf(direction[2]),
                        1e-9);
            }
        }
    }

    @Test
    public void columnAndRowLookupsInvertTheCanvasSampling() {
        int width = 2048;
        int height = 1024;
        int[] columns = new int[] {0, 1, 511, 1024, width - 1};
        for (int i = 0; i < columns.length; i++) {
            int column = columns[i];
            double longitude = Equirectangular.longitudeDegrees(column, width);
            Assert.assertEquals((double) column, Equirectangular.columnFor(longitude, width), 1e-9);
        }
        int[] rows = new int[] {0, 1, 500, height - 1};
        for (int i = 0; i < rows.length; i++) {
            int row = rows[i];
            double latitude = Equirectangular.latitudeDegrees(row, height);
            Assert.assertEquals((double) row, Equirectangular.rowFor(latitude, height), 1e-9);
        }
    }

    @Test
    public void aRingCanvasMapsItsLatitudeBandAcrossTheFullHeight() {
        // A ring capture renders 360° of longitude but only the band of latitude
        // the level frames cover — say 72° about the horizon. Rows then span
        // ±36° rather than the poles, and the row lookup inverts the sampling.
        int height = 720;
        float span = 72f;
        float centre = 0f;

        Assert.assertEquals(36.0, Equirectangular.latitudeDegrees(0, height, span, centre), 0.1);
        Assert.assertEquals(-36.0, Equirectangular.latitudeDegrees(height - 1, height, span, centre), 0.1);
        Assert.assertEquals(0.0, Equirectangular.latitudeDegrees(height / 2, height, span, centre), 0.1);

        int[] rows = new int[] {0, 1, 359, height - 1};
        for (int i = 0; i < rows.length; i++) {
            int row = rows[i];
            double latitude = Equirectangular.latitudeDegrees(row, height, span, centre);
            Assert.assertEquals((double) row, Equirectangular.rowFor(latitude, height, span, centre), 1e-9);
        }
    }

    @Test
    public void theCentreOfAFrameLandsWhereTheCameraWasPointing() {
        CameraPose pose = new CameraPose(40f, -20f, 0f);
        CameraBasis basis = CameraBasis.of(pose);
        FrameIntrinsics intrinsics = squareFrame();

        // Elevation is the negated pitch, so aiming 20° up means latitude 20°.
        double[] direction = Equirectangular.direction(40.0, 20.0);
        double[] pixel = SphericalGeometry.projectDirection(
                basis, intrinsics, direction[0], direction[1], direction[2]);

        Assert.assertNotNull("the aim point must be inside the frame", pixel);
        Assert.assertEquals(intrinsics.getCenterXPx(), pixel[0], 1e-6);
        Assert.assertEquals(intrinsics.getCenterYPx(), pixel[1], 1e-6);
    }

    @Test
    public void aDirectionBehindTheCameraDoesNotProject() {
        CameraBasis basis = CameraBasis.of(new CameraPose(0f, 0f, 0f));
        FrameIntrinsics intrinsics = squareFrame();

        // Facing north; due south is behind.
        double[] behind = Equirectangular.direction(180.0, 0.0);
        Assert.assertNull(SphericalGeometry.projectDirection(
                basis, intrinsics, behind[0], behind[1], behind[2]));
    }

    @Test
    public void aDirectionPastTheEdgeOfTheFieldOfViewDoesNotProject() {
        CameraBasis basis = CameraBasis.of(new CameraPose(0f, 0f, 0f));
        // 60° across, so anything beyond 30° off the axis is outside the frame.
        FrameIntrinsics intrinsics = FrameIntrinsics.fromFieldOfView(200, 200, 60f, 60f);

        double[] justInside = Equirectangular.direction(25.0, 0.0);
        Assert.assertNotNull(SphericalGeometry.projectDirection(
                basis, intrinsics, justInside[0], justInside[1], justInside[2]));

        double[] outside = Equirectangular.direction(40.0, 0.0);
        Assert.assertNull(SphericalGeometry.projectDirection(
                basis, intrinsics, outside[0], outside[1], outside[2]));
    }

    @Test
    public void aFrameFacingNorthSitsInTheMiddleOfTheCanvas() {
        int width = 3600;
        int height = 1800;
        CameraBasis basis = CameraBasis.of(new CameraPose(0f, 0f, 0f));
        CanvasFootprint footprint = FrameFootprint.compute(
                basis,
                FrameIntrinsics.fromFieldOfView(1000, 1000, 60f, 60f),
                width,
                height,
                0);

        Assert.assertFalse("a frame facing north cannot cross the seam", footprint.wrapsSeam(width));
        // 60° of a 360° canvas is a sixth of its width, centred.
        int expectedSpan = width / 6;
        Assert.assertTrue(
                "span " + footprint.columnSpan + " should be about " + expectedSpan,
                Math.abs(footprint.columnSpan - expectedSpan) <= expectedSpan / 10);
        double centre = footprint.startColumn + footprint.columnSpan / 2.0;
        Assert.assertEquals("the frame should straddle the middle column", width / 2.0, centre, 5.0);
    }

    @Test
    public void aFrameFacingTheDateLineComesBackAsOneUnwrappedRange() {
        int width = 3600;
        CameraBasis basis = CameraBasis.of(new CameraPose(180f, 0f, 0f));
        CanvasFootprint footprint = FrameFootprint.compute(
                basis,
                FrameIntrinsics.fromFieldOfView(1000, 1000, 60f, 60f),
                width,
                width / 2,
                0);

        Assert.assertTrue("a frame aimed at ±180° must cross the seam", footprint.wrapsSeam(width));
        // Still one contiguous band of longitude, about a sixth of the canvas —
        // not the near-full-width range a naive min/max would produce.
        int expectedSpan = width / 6;
        Assert.assertTrue(
                "span " + footprint.columnSpan + " should be about " + expectedSpan,
                Math.abs(footprint.columnSpan - expectedSpan) <= expectedSpan / 10);
    }

    @Test
    public void aFrameOverThePoleOpensOutToTheWholeCanvasWidth() {
        int width = 3600;
        // Pitch -90° aims straight up.
        CameraBasis basis = CameraBasis.of(new CameraPose(0f, -90f, 0f));
        CanvasFootprint footprint = FrameFootprint.compute(
                basis,
                FrameIntrinsics.fromFieldOfView(1000, 1000, 60f, 60f),
                width,
                width / 2,
                0);

        // Every longitude passes under a frame containing the pole, so no
        // narrower range would be correct.
        Assert.assertEquals(0, footprint.startColumn);
        Assert.assertEquals(width, footprint.columnSpan);
        Assert.assertEquals("the footprint must reach the top row", 0, footprint.startRow);
    }

    @Test
    public void aFootprintNeverClaimsRowsOutsideTheCanvas() {
        int width = 2048;
        int height = 1024;
        for (int yaw = -180; yaw <= 150; yaw += 30) {
            for (int pitch = -90; pitch <= 90; pitch += 15) {
                CanvasFootprint footprint = FrameFootprint.compute(
                        CameraBasis.of(new CameraPose((float) yaw, (float) pitch, 0f)),
                        FrameIntrinsics.fromFieldOfView(800, 600, 66f, 52f),
                        width,
                        height);
                String label = "yaw " + yaw + " pitch " + pitch;
                Assert.assertTrue(label + ": start row " + footprint.startRow, footprint.startRow >= 0);
                Assert.assertTrue(
                        label + ": runs past the bottom",
                        footprint.startRow + footprint.rowSpan <= height);
                Assert.assertTrue(label + ": empty footprint", footprint.rowSpan > 0);
                Assert.assertTrue(
                        label + ": span " + footprint.columnSpan + " exceeds the canvas",
                        footprint.columnSpan <= width);
            }
        }
    }

    @Test
    public void theFootprintContainsEveryCanvasPixelAFrameCanPaint() {
        // The footprint decides which part of the canvas each frame is even
        // offered. Anything it leaves out is a piece of sphere that silently
        // never gets painted, so this walks all 120,000 pixels of a frame and
        // checks each one lands inside — a far denser check than the 100-point
        // border walk the footprint itself is built from.
        int canvasWidth = 720;
        int canvasHeight = 360;
        FrameIntrinsics intrinsics = FrameIntrinsics.fromFieldOfView(400, 300, 66f, 52f);

        int[] rolls = new int[] {0, 30};
        for (int yaw = -180; yaw <= 150; yaw += 60) {
            for (int pitch = -75; pitch <= 75; pitch += 30) {
                for (int r = 0; r < rolls.length; r++) {
                    int roll = rolls[r];
                    CameraPose pose = new CameraPose((float) yaw, (float) pitch, (float) roll);
                    CameraBasis basis = CameraBasis.of(pose);
                    CanvasFootprint footprint = FrameFootprint.compute(
                            basis,
                            intrinsics,
                            canvasWidth,
                            canvasHeight);
                    String label = "yaw " + yaw + " pitch " + pitch + " roll " + roll;
                    Set<Integer> columns = footprintColumns(footprint, canvasWidth);

                    for (int frameRow = 0; frameRow < intrinsics.heightPx; frameRow++) {
                        for (int frameColumn = 0; frameColumn < intrinsics.widthPx; frameColumn++) {
                            double cameraX =
                                    (frameColumn - intrinsics.getCenterXPx()) / intrinsics.focalXPx;
                            double cameraY = -(frameRow - intrinsics.getCenterYPx()) / intrinsics.focalYPx;
                            double[] world = basis.toWorld(cameraX, cameraY, 1.0);
                            double length = Math.sqrt(
                                    world[0] * world[0] + world[1] * world[1] + world[2] * world[2]);

                            double longitude = Equirectangular.longitudeOf(world[0], world[1]);
                            double latitude = Equirectangular.latitudeOf(world[2] / length);
                            int column = SphericalGeometry.wrapColumn(
                                    (int) Math.floor(Equirectangular.columnFor(longitude, canvasWidth)),
                                    canvasWidth);
                            int row = coerceIn(
                                    (int) Math.floor(Equirectangular.rowFor(latitude, canvasHeight)),
                                    0,
                                    canvasHeight - 1);

                            Assert.assertTrue(
                                    label + ": frame pixel (" + frameColumn + ", " + frameRow
                                            + ") maps to column " + column + ", outside the footprint",
                                    columns.contains(column));
                            Assert.assertTrue(
                                    label + ": frame pixel (" + frameColumn + ", " + frameRow
                                            + ") maps to row " + row + ", outside rows "
                                            + footprint.startRow + ".."
                                            + (footprint.startRow + footprint.rowSpan),
                                    row >= footprint.startRow
                                            && row < footprint.startRow + footprint.rowSpan);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void unwrappingPicksTheBranchNearestTheReference() {
        Assert.assertEquals(170.0, SphericalGeometry.unwrapNear(170.0, 175.0), 1e-9);
        // 175° and -175° are 10° apart, not 350°.
        Assert.assertEquals(185.0, SphericalGeometry.unwrapNear(-175.0, 175.0), 1e-9);
        Assert.assertEquals(-185.0, SphericalGeometry.unwrapNear(175.0, -175.0), 1e-9);
        Assert.assertEquals(0.0, SphericalGeometry.unwrapNear(720.0, 0.0), 1e-9);
    }

    @Test
    public void columnsWrapOntoTheCanvasFromEitherSide() {
        Assert.assertEquals(0, SphericalGeometry.wrapColumn(0, 100));
        Assert.assertEquals(99, SphericalGeometry.wrapColumn(99, 100));
        Assert.assertEquals(0, SphericalGeometry.wrapColumn(100, 100));
        Assert.assertEquals(5, SphericalGeometry.wrapColumn(105, 100));
        Assert.assertEquals(99, SphericalGeometry.wrapColumn(-1, 100));
        Assert.assertEquals(95, SphericalGeometry.wrapColumn(-105, 100));
    }

    @Test
    public void intrinsicsTurnAFieldOfViewIntoTheFocalLengthThatSpansIt() {
        // A 90° field of view puts the frame edge at 45°, where tan is 1, so the
        // focal length is exactly half the width.
        FrameIntrinsics intrinsics = FrameIntrinsics.fromFieldOfView(1000, 500, 90f, 90f);
        Assert.assertEquals(500.0, intrinsics.focalXPx, 1e-6);
        Assert.assertEquals(250.0, intrinsics.focalYPx, 1e-6);
    }

    @Test
    public void aBasisRoundTripsThroughThePoseItWasBuiltFrom() {
        for (int yaw = -170; yaw <= 170; yaw += 30) {
            for (int pitch = -70; pitch <= 70; pitch += 20) {
                for (int roll = -160; roll <= 150; roll += 40) {
                    CameraPose pose = new CameraPose((float) yaw, (float) pitch, (float) roll);
                    CameraBasis basis = CameraBasis.of(pose);
                    CameraBasis recovered = CameraBasis.of(basis.toPose());
                    Assert.assertEquals(
                            "yaw " + yaw + " pitch " + pitch + " roll " + roll,
                            0.0,
                            RotationMath.angle(
                                    basis.toRotationMatrix(),
                                    recovered.toRotationMatrix()),
                            1e-6);
                }
            }
        }
    }

    @Test
    public void aBasisRoundTripsThroughItsOwnRotationMatrix() {
        // The regression this guards: fromRotationMatrix used to read the
        // matrix's *rows* as the vectors while toRotationMatrix stores them as
        // columns, so the inverse was actually a transpose. A level frame's
        // recovered forward collapsed to (0,1,0) whatever its yaw, and every
        // frame of a capture stacked onto the same longitude.
        for (int yaw = -170; yaw <= 170; yaw += 20) {
            for (int pitch = -60; pitch <= 60; pitch += 20) {
                for (int roll = -160; roll <= 150; roll += 40) {
                    CameraBasis basis = CameraBasis.of(
                            new CameraPose((float) yaw, (float) pitch, (float) roll));
                    CameraBasis recovered = CameraBasis.fromRotationMatrix(basis.toRotationMatrix());
                    Assert.assertEquals(
                            "yaw " + yaw + " pitch " + pitch + " roll " + roll + ": forward",
                            0.0,
                            RotationMath.angle(
                                    basis.toRotationMatrix(),
                                    recovered.toRotationMatrix()),
                            1e-6);
                    // The recovered forward must aim where the yaw says it does.
                    CameraPose pose = recovered.toPose();
                    Assert.assertEquals("yaw " + yaw + ": recovered yaw", (float) yaw, pose.yawDegrees, 1e-3f);
                    Assert.assertEquals(
                            "pitch " + pitch + ": recovered pitch",
                            (float) pitch,
                            pose.pitchDegrees,
                            1e-3f);
                    Assert.assertEquals("roll " + roll + ": recovered roll", (float) roll, pose.rollDegrees, 1e-3f);
                }
            }
        }
    }

    @Test
    public void levelFramesAtDifferentYawsGetDifferentForwards() {
        // The exact failure the user saw: three level frames across a pan all
        // collapsed to due north. Distinct yaw must mean distinct forward.
        CameraBasis a = CameraBasis.fromRotationMatrix(
                CameraBasis.of(new CameraPose(72f, 0f, 0f)).toRotationMatrix());
        CameraBasis b = CameraBasis.fromRotationMatrix(
                CameraBasis.of(new CameraPose(116f, 0f, 0f)).toRotationMatrix());
        double angleBetween = Math.toDegrees(
                Math.acos(coerceIn(
                        a.forwardX * b.forwardX + a.forwardY * b.forwardY + a.forwardZ * b.forwardZ,
                        -1.0,
                        1.0)));
        Assert.assertTrue(
                "forwards 72° and 116° apart must stay apart, were " + angleBetween + "° apart",
                angleBetween > 40.0);
    }

    @Test
    public void theOpticalAxisIsUntouchedByRadialDistortion() {
        CameraBasis basis = CameraBasis.of(new CameraPose(0f, 0f, 0f));
        FrameIntrinsics intrinsics = FrameIntrinsics.fromFieldOfView(
                400, 400, 60f, 60f,
                new double[] {-2.5e-7, 0.0, 0.0});

        // Facing north along the axis: the ideal pixel is the centre and the
        // distortion factor there is exactly 1.
        double[] pixel = SphericalGeometry.projectDirection(basis, intrinsics, 0.0, 1.0, 0.0);
        Assert.assertNotNull(pixel);
        Assert.assertEquals(intrinsics.getCenterXPx(), pixel[0], 1e-6);
        Assert.assertEquals(intrinsics.getCenterYPx(), pixel[1], 1e-6);
    }

    @Test
    public void anOffAxisDirectionLandsElsewhereOnceTheLensIsDistorted() {
        CameraBasis basis = CameraBasis.of(new CameraPose(0f, 0f, 0f));
        FrameIntrinsics pinhole = FrameIntrinsics.fromFieldOfView(400, 400, 60f, 60f);
        FrameIntrinsics lens = FrameIntrinsics.fromFieldOfView(
                400, 400, 60f, 60f,
                new double[] {-2.5e-7, 0.0, 0.0});
        double[] direction = Equirectangular.direction(28.0, 12.0);

        double[] atPinhole = SphericalGeometry.projectDirection(
                basis, pinhole, direction[0], direction[1], direction[2]);
        double[] atLens = SphericalGeometry.projectDirection(
                basis, lens, direction[0], direction[1], direction[2]);
        Assert.assertNotNull(atPinhole);
        Assert.assertNotNull(atLens);
        Assert.assertTrue(
                "barrel distortion should pull the pixel inward",
                Math.abs(atPinhole[0] - atLens[0]) + Math.abs(atPinhole[1] - atLens[1]) > 1.0);
    }

    @Test
    public void aBarrelDistortedFramePaintsAWiderFootprintThanAPinhole() {
        CameraBasis basis = CameraBasis.of(new CameraPose(0f, 0f, 0f));
        CanvasFootprint plain = FrameFootprint.compute(
                basis,
                FrameIntrinsics.fromFieldOfView(1000, 1000, 60f, 60f),
                3600,
                1800,
                0);
        CanvasFootprint barrel = FrameFootprint.compute(
                basis,
                FrameIntrinsics.fromFieldOfView(
                        1000, 1000, 60f, 60f,
                        new double[] {-2.5e-7, 0.0, 0.0}),
                3600,
                1800,
                0);

        // A barrel-distorted frame sees past the pinhole's field of view at its
        // edges, so its footprint must reach further — a footprint that shrank
        // here would be a missed edge in the stitch.
        Assert.assertTrue(
                "barrel span " + barrel.columnSpan + " should exceed pinhole " + plain.columnSpan,
                barrel.columnSpan > plain.columnSpan);
    }

    @Test
    public void overlappingAimsAreFoundByThePoseGraph() {
        // Two level frames 20° apart on a portrait lens (52° wide, 66° tall):
        // well inside the shared view, so the edge is measured.
        CameraBasis a = CameraBasis.of(new CameraPose(0f, 0f, 0f));
        CameraBasis b = CameraBasis.of(new CameraPose(20f, 0f, 0f));
        Assert.assertNotNull(SphericalGeometry.angularOverlap(a, b, 52f, 66f));
        // Vertical separation is judged against the taller axis.
        CameraBasis above = CameraBasis.of(new CameraPose(0f, -30f, 0f));
        Assert.assertNotNull(SphericalGeometry.angularOverlap(a, above, 52f, 66f));
    }

    @Test
    public void aimsPastAFullFieldOfViewDoNotOverlap() {
        CameraBasis a = CameraBasis.of(new CameraPose(0f, 0f, 0f));
        CameraBasis beyond = CameraBasis.of(new CameraPose(60f, 0f, 0f));
        Assert.assertNull(SphericalGeometry.angularOverlap(a, beyond, 52f, 66f));
    }

    @Test
    public void theOverlapTestIsDirectionalNotADistanceBallpark() {
        CameraBasis a = CameraBasis.of(new CameraPose(0f, 0f, 0f));
        // 55° of aim difference, all of it horizontal: on a 52°-wide portrait
        // frame the images cannot share a pixel, so the edge is rejected even
        // though a single distance threshold (the old "widest FOV" rule) would
        // have accepted it.
        CameraBasis farSideways = CameraBasis.of(new CameraPose(55f, 0f, 0f));
        Assert.assertNull(SphericalGeometry.angularOverlap(a, farSideways, 52f, 66f));
        // The same 55° of aim difference, split across both axes, still
        // overlaps on the tall axis.
        CameraBasis upAndAcross = CameraBasis.of(new CameraPose(35f, -40f, 0f));
        Assert.assertNotNull(SphericalGeometry.angularOverlap(a, upAndAcross, 52f, 66f));
    }

    @Test
    public void closerAimsReportAStrongerOverlap() {
        CameraBasis a = CameraBasis.of(new CameraPose(0f, 0f, 0f));
        Double near = SphericalGeometry.angularOverlap(a, CameraBasis.of(new CameraPose(10f, 0f, 0f)), 52f, 66f);
        Double far = SphericalGeometry.angularOverlap(a, CameraBasis.of(new CameraPose(30f, 0f, 0f)), 52f, 66f);
        Assert.assertNotNull(near);
        Assert.assertNotNull(far);
        Assert.assertTrue("near " + near + " should be smaller than far " + far, near < far);
    }

    @Test
    public void anUltraWideLensStillBuildsAPoseGraph() {
        // The bug this guards: the overlap test bounds a separation by
        // `tan(fov)`, which turns negative past 90° and used to reject every
        // pair a wide lens could see. The default device profile *prefers* the
        // widest rear camera, so that silently disabled pose refinement and gain
        // compensation on exactly the hardware they were tuned for.
        CameraBasis a = CameraBasis.of(new CameraPose(0f, 0f, 0f));
        CameraBasis neighbour = CameraBasis.of(new CameraPose(40f, 0f, 0f));
        Assert.assertNotNull(SphericalGeometry.angularOverlap(a, neighbour, 104f, 120f));
        // And a frame facing away is still rejected, wide lens or not.
        Assert.assertNull(SphericalGeometry.angularOverlap(
                a,
                CameraBasis.of(new CameraPose(170f, 0f, 0f)),
                104f,
                120f));
    }

    @Test
    public void theFeatherIsStrongestOnTheOpticalAxisAndDiesAtTheBorder() {
        Assert.assertEquals(1.0, SphericalGeometry.featherWeight(200.0, 150.0, 200.0, 150.0), 1e-12);
        Assert.assertEquals(0.0, SphericalGeometry.featherWeight(0.0, 150.0, 200.0, 150.0), 1e-12);
        Assert.assertEquals(0.0, SphericalGeometry.featherWeight(400.0, 150.0, 200.0, 150.0), 1e-12);
        Assert.assertEquals(0.0, SphericalGeometry.featherWeight(200.0, 0.0, 200.0, 150.0), 1e-12);
        Assert.assertTrue(
                SphericalGeometry.featherWeight(240.0, 160.0, 200.0, 150.0)
                        < SphericalGeometry.featherWeight(210.0, 152.0, 200.0, 150.0));
    }

    @Test
    public void aPixelOutsideTheFrameOnBothAxesNeverEarnsAWeight() {
        // Two negative factors multiply into a positive one. Radial distortion
        // can pull a pixel whose *ideal* position is well outside the frame back
        // inside the distorted bounds, and an unclamped feather would then hand
        // a direction the frame never saw a full-strength vote in the blend —
        // and, at an odd blend power, a negative one that subtracts colour from
        // its neighbours.
        Assert.assertEquals(0.0, SphericalGeometry.featherWeight(-500.0, -400.0, 200.0, 150.0), 1e-12);
        Assert.assertEquals(0.0, SphericalGeometry.featherWeight(900.0, 700.0, 200.0, 150.0), 1e-12);
        Assert.assertEquals(0.0, SphericalGeometry.featherWeight(-500.0, 150.0, 200.0, 150.0), 1e-12);
    }

    @Test(expected = IllegalArgumentException.class)
    public void anImpossibleFieldOfViewIsRejectedRatherThanWarpedAround() {
        FrameIntrinsics.fromFieldOfView(1000, 1000, 180f, 60f);
    }

    private FrameIntrinsics squareFrame() {
        return FrameIntrinsics.fromFieldOfView(400, 400, 66f, 66f);
    }

    /** The canvas columns a footprint covers, with any seam crossing resolved. */
    private Set<Integer> footprintColumns(CanvasFootprint footprint, int canvasWidth) {
        Set<Integer> columns = new HashSet<Integer>();
        for (int i = 0; i < footprint.columnSpan; i++) {
            columns.add(SphericalGeometry.wrapColumn(footprint.startColumn + i, canvasWidth));
        }
        return columns;
    }

    private void assertVector(
            double expectedX,
            double expectedY,
            double expectedZ,
            double actualX,
            double actualY,
            double actualZ) {
        Assert.assertEquals("x", expectedX, actualX, TOLERANCE);
        Assert.assertEquals("y", expectedY, actualY, TOLERANCE);
        Assert.assertEquals("z", expectedZ, actualZ, TOLERANCE);
    }

    private double norm(double[] vector) {
        return Math.sqrt(dot(vector, vector));
    }

    private double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private double[] cross(double[] a, double[] b) {
        return new double[] {
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]
        };
    }

    private static int coerceIn(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double coerceIn(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
