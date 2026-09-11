package com.n30dyn4m1c.photosphere.camera;

/**
 * Inclusive integer range, matching Kotlin {@code IntRange} ({@code first}..{@code last}).
 */
public final class IntRange {

    public final int first;
    public final int last;

    public IntRange(int first, int last) {
        this.first = first;
        this.last = last;
    }

    public int getFirst() {
        return first;
    }

    public int getLast() {
        return last;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IntRange)) return false;
        IntRange intRange = (IntRange) o;
        return first == intRange.first && last == intRange.last;
    }

    @Override
    public int hashCode() {
        return 31 * first + last;
    }

    @Override
    public String toString() {
        return first + ".." + last;
    }
}
