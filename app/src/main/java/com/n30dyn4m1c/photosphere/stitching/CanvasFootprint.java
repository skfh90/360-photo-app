package com.n30dyn4m1c.photosphere.stitching;

/**
 * The block of canvas one frame can contribute to.
 *
 * {@link #startColumn} may be negative and {@code startColumn + columnSpan} may run past the
 * canvas width: a frame straddling the ±180° seam covers a contiguous band of
 * longitude that is split in the canvas, and it is cheaper to describe that as
 * one unwrapped range than to force every caller to reason about two. Rows never
 * wrap — latitude is clamped at the poles — so {@link #startRow} and {@link #rowSpan} are
 * always within the canvas.
 */
public final class CanvasFootprint {
    public final int startColumn;
    public final int columnSpan;
    public final int startRow;
    public final int rowSpan;

    public CanvasFootprint(int startColumn, int columnSpan, int startRow, int rowSpan) {
        this.startColumn = startColumn;
        this.columnSpan = columnSpan;
        this.startRow = startRow;
        this.rowSpan = rowSpan;
    }

    public boolean isEmpty() {
        return columnSpan <= 0 || rowSpan <= 0;
    }

    /** True when the footprint runs off the right edge and resumes on the left. */
    public boolean wrapsSeam(int canvasWidth) {
        return startColumn < 0 || startColumn + columnSpan > canvasWidth;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CanvasFootprint)) return false;
        CanvasFootprint that = (CanvasFootprint) other;
        return startColumn == that.startColumn
            && columnSpan == that.columnSpan
            && startRow == that.startRow
            && rowSpan == that.rowSpan;
    }

    @Override
    public int hashCode() {
        int result = startColumn;
        result = 31 * result + columnSpan;
        result = 31 * result + startRow;
        result = 31 * result + rowSpan;
        return result;
    }
}
