package com.n30dyn4m1c.photosphere.camera;

import org.junit.Assert;
import org.junit.Test;

public class AlignmentGateTest {

    private AlignmentGate gate() {
        return new AlignmentGate(2f, 300L);
    }

    @Test
    public void anAimOutsideTheThresholdNeverFires() {
        AlignmentGate gate = gate();

        AlignmentGate.Reading reading = gate.update(2.5f, 0L);
        Assert.assertFalse(reading.isAligned());
        Assert.assertFalse(reading.isTriggered());
        Assert.assertEquals(0f, reading.getDwellProgress(), 1e-4f);

        // Even after far longer than the dwell.
        Assert.assertFalse(gate.update(2.5f, 5000L).isTriggered());
    }

    @Test
    public void aHeldAimFiresOnceTheDwellElapses() {
        AlignmentGate gate = gate();

        Assert.assertFalse(gate.update(1f, 0L).isTriggered());
        Assert.assertFalse(gate.update(1f, 150L).isTriggered());
        Assert.assertFalse(gate.update(1f, 299L).isTriggered());
        Assert.assertTrue(gate.update(1f, 300L).isTriggered());
    }

    @Test
    public void dwellProgressReportsHowFarThroughTheHoldTheUserIs() {
        AlignmentGate gate = gate();

        gate.update(1f, 0L);
        Assert.assertEquals(0.5f, gate.update(1f, 150L).getDwellProgress(), 1e-4f);
        Assert.assertEquals(1f, gate.update(1f, 900L).getDwellProgress(), 1e-4f);
    }

    @Test
    public void leavingTheThresholdRestartsTheHoldFromZero() {
        AlignmentGate gate = gate();

        gate.update(1f, 0L);
        gate.update(1f, 250L);
        // A wobble out of the window at 250ms — this is the case the dwell exists
        // for, and it must not be forgiven.
        Assert.assertFalse(gate.update(9f, 260L).isAligned());
        Assert.assertFalse(gate.update(1f, 270L).isTriggered());
        Assert.assertFalse(gate.update(1f, 560L).isTriggered());
        Assert.assertTrue(gate.update(1f, 570L).isTriggered());
    }

    @Test
    public void theTriggerIsConsumedSoASteadyHandDoesNotFireTwice() {
        AlignmentGate gate = gate();

        gate.update(1f, 0L);
        Assert.assertTrue(gate.update(1f, 300L).isTriggered());
        // Still perfectly aligned, but the shutter stays quiet until a fresh
        // dwell has been served.
        Assert.assertFalse(gate.update(1f, 310L).isTriggered());
        Assert.assertFalse(gate.update(1f, 599L).isTriggered());
        Assert.assertTrue(gate.update(1f, 610L).isTriggered());
    }

    @Test
    public void resetDropsAPartServedDwell() {
        AlignmentGate gate = gate();

        gate.update(1f, 0L);
        gate.reset();
        Assert.assertFalse(gate.update(1f, 299L).isTriggered());
        Assert.assertTrue(gate.update(1f, 600L).isTriggered());
    }

    @Test
    public void aDistanceWithNoFixBehindItIsNotAnAlignment() {
        AlignmentGate.Reading reading = gate().update(Float.NaN, 0L);

        Assert.assertFalse(reading.isAligned());
        Assert.assertFalse(reading.isTriggered());
    }

    @Test
    public void theShippedDefaultsAreTheOnesTheCaptureFlowPromises() {
        Assert.assertEquals(2f, AlignmentGate.DEFAULT_THRESHOLD_DEGREES, 1e-4f);
        Assert.assertEquals(300L, AlignmentGate.DEFAULT_DWELL_MILLIS);
    }
}
