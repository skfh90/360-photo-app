package com.n30dyn4m1c.photosphere.camera;

import android.hardware.camera2.CaptureRequest;

import org.junit.Assert;
import org.junit.Test;

public class SphereCaptureProfileTest {

    @Test
    public void aFocusableLensIsCapturedByTappingToFocus() {
        Assert.assertEquals(
                FocusMode.FOCUS_POINT,
                SphereCaptureProfile.resolveFocusMode(0.005f));
        Assert.assertEquals(
                FocusMode.FOCUS_POINT,
                SphereCaptureProfile.resolveFocusMode(1f));
    }

    @Test
    public void anUnknownCameraFallsBackToTapToFocus() {
        Assert.assertEquals(
                FocusMode.FOCUS_POINT,
                SphereCaptureProfile.resolveFocusMode(null));
    }

    @Test
    public void aFixedFocusLensIsNeverAskedToAutoFocus() {
        Assert.assertEquals(
                FocusMode.FIXED_FOCUS,
                SphereCaptureProfile.resolveFocusMode(0f));
    }

    @Test
    public void lockSupportDefaultsToTrueWhenTheCameraStaysSilent() {
        Assert.assertTrue(SphereCaptureProfile.resolveLockSupport(null));
        Assert.assertTrue(SphereCaptureProfile.resolveLockSupport(true));
        Assert.assertFalse(SphereCaptureProfile.resolveLockSupport(false));
    }

    @Test
    public void oisIsOnlyClaimedWhenTheLensActuallyOffersIt() {
        Assert.assertTrue(
                SphereCaptureProfile.resolveOpticalStabilization(
                        new int[] {
                                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF,
                                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                        }));
        Assert.assertFalse(
                SphereCaptureProfile.resolveOpticalStabilization(
                        new int[] {CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF}));
        Assert.assertFalse(SphereCaptureProfile.resolveOpticalStabilization(null));
    }

    @Test
    public void theDefaultDeviceProfileKeepsASaneCaptureConfiguration() {
        SphereDeviceProfile profile = SphereDeviceProfile.forDevice();
        Assert.assertTrue(profile.getPreferWidestCamera());
        Assert.assertTrue(
                "capture cap " + profile.getCaptureMaxLongEdgePx(),
                profile.getCaptureMaxLongEdgePx() >= 3000);
        Assert.assertTrue(
                "burst " + profile.getBurstPerTarget(),
                profile.getBurstPerTarget() >= 2);
    }
}
