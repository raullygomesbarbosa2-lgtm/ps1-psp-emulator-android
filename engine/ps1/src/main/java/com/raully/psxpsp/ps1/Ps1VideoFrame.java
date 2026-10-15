package com.raully.psxpsp.ps1;

/** Immutable ARGB8888 snapshot produced from the PS1 GPU display area. */
public final class Ps1VideoFrame {
    private final int width;
    private final int height;
    private final int[] argb;

    Ps1VideoFrame(int width, int height, int[] argb) {
        if (width <= 0 || height <= 0 || argb == null || argb.length != width * height) {
            throw new IllegalArgumentException("invalid frame dimensions or pixel buffer");
        }
        this.width = width;
        this.height = height;
        this.argb = argb;
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }

    public int getArgb(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            throw new IndexOutOfBoundsException("pixel outside frame");
        }
        return argb[y * width + x];
    }

    public int[] copyArgb() { return argb.clone(); }
}
