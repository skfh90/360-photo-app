package com.n30dyn4m1c.photosphere.sensor;

import android.hardware.SensorManager;
import android.view.Surface;

/**
 * Axis pairs handed to {@link SensorManager#remapCoordinateSystem}, one per display
 * rotation.
 *
 * <p>Each entry answers "where do the chassis X and Y axes point once the display
 * has been rotated by this much?" — e.g. at {@link Surface#ROTATION_90} the display
 * has turned 90° counter-clockwise, so the chassis X axis now runs along the
 * display's Y axis.
 */
public enum DisplayAxes {
    Rotation0(SensorManager.AXIS_X, SensorManager.AXIS_Y),
    Rotation90(SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X),
    Rotation180(SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y),
    Rotation270(SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X);

    public final int axisX;
    public final int axisY;

    DisplayAxes(int axisX, int axisY) {
        this.axisX = axisX;
        this.axisY = axisY;
    }

    public int getAxisX() {
        return axisX;
    }

    public int getAxisY() {
        return axisY;
    }

    public static DisplayAxes forDisplayRotation(int displayRotation) {
        switch (displayRotation) {
            case Surface.ROTATION_90:
                return Rotation90;
            case Surface.ROTATION_180:
                return Rotation180;
            case Surface.ROTATION_270:
                return Rotation270;
            default:
                return Rotation0;
        }
    }
}
