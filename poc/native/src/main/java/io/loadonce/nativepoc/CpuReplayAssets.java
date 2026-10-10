package io.loadonce.nativepoc;

import java.util.Objects;

/** Heap-only replay inputs. GPU identifiers and off-heap allocations are deliberately absent. */
public final class CpuReplayAssets {
    private final int width;
    private final int height;
    private final byte[] rgba;
    private final float[] vertices;
    private final short[] pcm;
    private final int sampleRate;
    private final String vertexShader;
    private final String fragmentShader;

    public CpuReplayAssets(int width, int height, byte[] rgba, float[] vertices, short[] pcm,
                           int sampleRate, String vertexShader, String fragmentShader) {
        if (width < 1 || height < 1 || width > 4_096 || height > 4_096) {
            throw new IllegalArgumentException("Texture dimensions must be in [1, 4096]");
        }
        if (Objects.requireNonNull(rgba).length != Math.multiplyExact(Math.multiplyExact(width, height), 4)) {
            throw new IllegalArgumentException("Expected tightly packed RGBA8 pixels");
        }
        if (Objects.requireNonNull(vertices).length != 6) {
            throw new IllegalArgumentException("Fixture requires three two-dimensional triangle vertices");
        }
        for (float value : vertices) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Vertex must be finite");
        }
        if (Objects.requireNonNull(pcm).length < 1 || pcm.length > 1_000_000
                || sampleRate < 8_000 || sampleRate > 192_000) {
            throw new IllegalArgumentException("Expected bounded mono PCM16 audio and supported sample rate");
        }
        if (Objects.requireNonNull(vertexShader).isBlank() || Objects.requireNonNull(fragmentShader).isBlank()) {
            throw new IllegalArgumentException("Shader sources must be present for binary-cache fallback");
        }
        this.width = width;
        this.height = height;
        this.rgba = rgba.clone();
        this.vertices = vertices.clone();
        this.pcm = pcm.clone();
        this.sampleRate = sampleRate;
        this.vertexShader = vertexShader;
        this.fragmentShader = fragmentShader;
    }

    public int width() { return width; }
    public int height() { return height; }
    public byte[] rgba() { return rgba.clone(); }
    public float[] vertices() { return vertices.clone(); }
    public short[] pcm() { return pcm.clone(); }
    public int sampleRate() { return sampleRate; }
    public String vertexShader() { return vertexShader; }
    public String fragmentShader() { return fragmentShader; }

    public static CpuReplayAssets fixture() {
        byte[] pixels = {
                (byte) 255, 0, 0, (byte) 255, 0, (byte) 255, 0, (byte) 255,
                0, 0, (byte) 255, (byte) 255, (byte) 255, (byte) 255, (byte) 255, (byte) 255
        };
        short[] samples = new short[8_000];
        for (int index = 0; index < samples.length; index++) {
            samples[index] = (short) (Math.sin(index * Math.PI * 2 * 440 / 8_000) * 4_000);
        }
        return new CpuReplayAssets(2, 2, pixels, new float[] {-1, -1, 3, -1, -1, 3}, samples, 8_000,
                "#version 330 core\nlayout(location=0) in vec2 position;\n"
                        + "void main() { gl_Position = vec4(position, 0.0, 1.0); }\n",
                "#version 330 core\nout vec4 color;\n"
                        + "void main() { color = vec4(0.25, 0.5, 0.75, 1.0); }\n");
    }
}
