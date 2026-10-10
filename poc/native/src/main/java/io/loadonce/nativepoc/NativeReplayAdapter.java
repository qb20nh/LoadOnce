package io.loadonce.nativepoc;

import io.loadonce.runtime.ResourceAdapter;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWWindowSizeCallback;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.ALC10.*;
import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.opengl.GL41.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Isolated Linux fixture owning all GLFW/GL/AL state in its process, not a Minecraft adapter.
 * Call suspendOnOwnerThread before requesting CRaC, then resumeOnOwnerThread after it returns.
 * The CRaC callback does no native work: GLFW cannot be driven from an arbitrary callback thread.
 */
public final class NativeReplayAdapter implements ResourceAdapter, AutoCloseable {
    public enum State { NEW, ACTIVE, SUSPENDED, REBUILD_PENDING, BROKEN, CLOSED }
    public enum FailurePoint { NONE, WINDOW, TEXTURE, BUFFER, PROGRAM, AUDIO_DEVICE, AUDIO_CONTEXT, AUDIO_BUFFER, AUDIO_SOURCE }

    private static final AtomicReference<NativeReplayAdapter> GLOBAL_OWNER = new AtomicReference<>();
    private final OwnerThread ownerThread = new OwnerThread();
    private final CpuReplayAssets assets;
    private volatile State state = State.NEW;
    private FailurePoint failOnce = FailurePoint.NONE;
    private boolean glfwInitialized;
    // LWJGL ALC10 caches exported function addresses in static fields. Unloading/reloading its
    // library can invalidate those addresses; preserve code mappings while dropping all devices.
    private static boolean glLoaded;
    private static boolean alLoaded;
    private GLFWErrorCallback errorCallback;
    private GLFWKeyCallback keyCallback;
    private GLFWWindowSizeCallback sizeCallback;
    private long window;
    private int texture;
    private int vertexBuffer;
    private int vertexArray;
    private int program;
    private long audioDevice;
    private long audioContext;
    private int audioBuffer;
    private int audioSource;
    private String rendererIdentity;
    private byte[] shaderBinary;
    private int shaderBinaryFormat;
    private String shaderBinaryIdentity;
    private int generation;
    private int binaryReuses;
    private int sourceCompiles;
    private int resizeEvents;

    public NativeReplayAdapter(CpuReplayAssets assets) {
        this.assets = Objects.requireNonNull(assets);
        if (!System.getProperty("os.name").equals("Linux") || !System.getProperty("os.arch").equals("amd64")) {
            throw new UnsupportedOperationException("Native replay fixture currently audits Linux x64 only");
        }
        // This isolated module must not be attached to a process with existing LWJGL contexts.
        Configuration.OPENGL_EXPLICIT_INIT.set(true);
        Configuration.OPENAL_EXPLICIT_INIT.set(true);
    }

    @Override public String name() { return "isolated GLFW/OpenGL/OpenAL CPU replay fixture"; }
    @Override public boolean supportsReusableCheckpoint() { return state == State.SUSPENDED && noHandles(); }
    public State state() { return state; }
    public int generation() { return generation; }
    public int binaryReuses() { return binaryReuses; }
    public int sourceCompiles() { return sourceCompiles; }
    public String rendererIdentity() { return rendererIdentity; }

    /** Changes only the fixture cache key to exercise the real source fallback on the next build. */
    void invalidateShaderIdentityForFixture() {
        ownerThread.requireOwner();
        if (state != State.SUSPENDED) throw new IllegalStateException("Suspend before invalidating shader cache identity");
        shaderBinaryIdentity = "fixture invalidated driver identity";
    }

    /** Configure a one-shot, real partial-construction failure for the native verification fixture. */
    public void failNextConstructionAt(FailurePoint failurePoint) {
        ownerThread.requireOwner();
        if (state != State.SUSPENDED && state != State.NEW && state != State.REBUILD_PENDING) {
            throw new IllegalStateException("Suspend before injecting a reconstruction failure");
        }
        failOnce = Objects.requireNonNull(failurePoint);
    }

    public void resumeOnOwnerThread() {
        ownerThread.requireOwner();
        if (state != State.NEW && state != State.SUSPENDED && state != State.REBUILD_PENDING) {
            throw new IllegalStateException("Cannot reconstruct in state " + state);
        }
        if (GLOBAL_OWNER.get() != this && !GLOBAL_OWNER.compareAndSet(null, this)) {
            throw new IllegalStateException("This fixture requires exclusive GLFW/OpenGL/OpenAL ownership");
        }
        try {
            createWindow();
            createGraphics();
            createAudio();
            state = State.ACTIVE;
            generation++;
        } catch (RuntimeException | Error failure) {
            try {
                destroyNative();
                state = State.SUSPENDED;
            } catch (RuntimeException | Error cleanupFailure) {
                state = State.BROKEN;
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public void suspendOnOwnerThread() {
        ownerThread.requireOwner();
        if (state != State.ACTIVE) throw new IllegalStateException("Cannot suspend in state " + state);
        try {
            destroyNative();
            state = State.SUSPENDED;
        } catch (RuntimeException | Error failure) {
            state = State.BROKEN;
            throw failure;
        }
    }

    @Override public void beforeCheckpoint() {
        if (!supportsReusableCheckpoint()) {
            throw new IllegalStateException("Owner thread must release every native resource before checkpoint");
        }
    }

    @Override public synchronized void afterRestore() {
        if (state == State.SUSPENDED) state = State.REBUILD_PENDING;
        else if (state != State.REBUILD_PENDING) {
            throw new IllegalStateException("Cannot schedule restoration in state " + state);
        }
    }

    private void createWindow() {
        errorCallback = GLFWErrorCallback.createPrint(System.err);
        glfwSetErrorCallback(errorCallback);
        glfwInitHint(GLFW_PLATFORM, GLFW_PLATFORM_X11);
        if (!glfwInit()) throw new IllegalStateException("GLFW initialization failed; a working X11 display is required");
        glfwInitialized = true;
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        window = glfwCreateWindow(32, 32, "LoadOnce native reconstruction fixture", NULL, NULL);
        if (window == NULL) throw new IllegalStateException("GLFW could not create an OpenGL 3.3 window");
        keyCallback = GLFWKeyCallback.create((handle, key, scanCode, action, modifiers) -> {
            if (key == GLFW_KEY_ESCAPE && action == GLFW_PRESS) glfwSetWindowShouldClose(handle, true);
        });
        sizeCallback = GLFWWindowSizeCallback.create((handle, width, height) -> resizeEvents++);
        glfwSetKeyCallback(window, keyCallback);
        glfwSetWindowSizeCallback(window, sizeCallback);
        glfwMakeContextCurrent(window);
        if (!glLoaded) {
            GL.create();
            glLoaded = true;
        }
        GL.createCapabilities();
        rendererIdentity = glGetString(GL_VENDOR) + " / " + glGetString(GL_RENDERER) + " / "
                + glGetString(GL_VERSION) + " / " + glGetString(GL_SHADING_LANGUAGE_VERSION);
        failAt(FailurePoint.WINDOW);
    }

    private void createGraphics() {
        byte[] pixels = assets.rgba();
        ByteBuffer upload = memAlloc(pixels.length);
        try {
            upload.put(pixels).flip();
            texture = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, assets.width(), assets.height(), 0,
                    GL_RGBA, GL_UNSIGNED_BYTE, upload);
        } finally {
            memFree(upload);
        }
        failAt(FailurePoint.TEXTURE);

        float[] vertices = assets.vertices();
        FloatBuffer vertexUpload = memAllocFloat(vertices.length);
        try {
            vertexUpload.put(vertices).flip();
            vertexArray = glGenVertexArrays();
            glBindVertexArray(vertexArray);
            vertexBuffer = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
            glBufferData(GL_ARRAY_BUFFER, vertexUpload, GL_STATIC_DRAW);
            glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0L);
            glEnableVertexAttribArray(0);
        } finally {
            memFree(vertexUpload);
        }
        failAt(FailurePoint.BUFFER);
        createProgram();
        failAt(FailurePoint.PROGRAM);
        checkGl("graphics reconstruction");
    }

    private void createProgram() {
        GLCapabilities capabilities = GL.getCapabilities();
        boolean binarySupported = capabilities.OpenGL41 || capabilities.GL_ARB_get_program_binary;
        program = glCreateProgram();
        if (program == 0) throw new IllegalStateException("OpenGL could not allocate a shader program");
        if (binarySupported && shaderBinary != null && rendererIdentity.equals(shaderBinaryIdentity)) {
            ByteBuffer binary = memAlloc(shaderBinary.length);
            try {
                binary.put(shaderBinary).flip();
                glProgramBinary(program, shaderBinaryFormat, binary);
                if (glGetProgrami(program, GL_LINK_STATUS) == GL_TRUE && glGetError() == GL_NO_ERROR) {
                    binaryReuses++;
                    return;
                }
                // Driver caches are optional. A rejected binary never disables source compilation.
                while (glGetError() != GL_NO_ERROR) { }
            } finally {
                memFree(binary);
            }
            glDeleteProgram(program);
            program = glCreateProgram();
        }
        if (binarySupported) glProgramParameteri(program, GL_PROGRAM_BINARY_RETRIEVABLE_HINT, GL_TRUE);
        int vertex = 0;
        int fragment = 0;
        try {
            vertex = compileShader(GL_VERTEX_SHADER, assets.vertexShader());
            fragment = compileShader(GL_FRAGMENT_SHADER, assets.fragmentShader());
            glAttachShader(program, vertex);
            glAttachShader(program, fragment);
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) != GL_TRUE) {
                throw new IllegalStateException("Shader link failed: " + glGetProgramInfoLog(program));
            }
            sourceCompiles++;
        } finally {
            if (vertex != 0) { glDetachShader(program, vertex); glDeleteShader(vertex); }
            if (fragment != 0) { glDetachShader(program, fragment); glDeleteShader(fragment); }
        }
        if (binarySupported) {
            int length = glGetProgrami(program, GL_PROGRAM_BINARY_LENGTH);
            if (length > 0 && length <= 16 * 1_024 * 1_024) {
                ByteBuffer binary = memAlloc(length);
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    IntBuffer actualLength = stack.mallocInt(1);
                    IntBuffer format = stack.mallocInt(1);
                    glGetProgramBinary(program, actualLength, format, binary);
                    shaderBinary = new byte[actualLength.get(0)];
                    binary.get(shaderBinary);
                    shaderBinaryFormat = format.get(0);
                    shaderBinaryIdentity = rendererIdentity;
                } finally {
                    memFree(binary);
                }
            }
        }
    }

    private static int compileShader(int kind, String source) {
        int shader = glCreateShader(kind);
        try {
            glShaderSource(shader, source);
            glCompileShader(shader);
            if (glGetShaderi(shader, GL_COMPILE_STATUS) != GL_TRUE) {
                throw new IllegalStateException("Shader compile failed: " + glGetShaderInfoLog(shader));
            }
            return shader;
        } catch (RuntimeException | Error failure) {
            glDeleteShader(shader);
            throw failure;
        }
    }

    private void createAudio() {
        if (!alLoaded) {
            ALC.create();
            alLoaded = true;
        }
        audioDevice = alcOpenDevice((ByteBuffer) null);
        if (audioDevice == NULL) throw new IllegalStateException("OpenAL device unavailable; use ALSOFT_DRIVERS=null in headless CI");
        var deviceCapabilities = ALC.createCapabilities(audioDevice);
        failAt(FailurePoint.AUDIO_DEVICE);
        audioContext = alcCreateContext(audioDevice, (IntBuffer) null);
        if (audioContext == NULL || !alcMakeContextCurrent(audioContext)) {
            throw new IllegalStateException("OpenAL context creation failed");
        }
        AL.createCapabilities(deviceCapabilities);
        failAt(FailurePoint.AUDIO_CONTEXT);
        short[] pcm = assets.pcm();
        ShortBuffer upload = memAllocShort(pcm.length);
        try {
            upload.put(pcm).flip();
            audioBuffer = alGenBuffers();
            alBufferData(audioBuffer, AL_FORMAT_MONO16, upload, assets.sampleRate());
        } finally {
            memFree(upload);
        }
        failAt(FailurePoint.AUDIO_BUFFER);
        audioSource = alGenSources();
        alSourcei(audioSource, AL_BUFFER, audioBuffer);
        alSourcei(audioSource, AL_LOOPING, AL_TRUE);
        alSourcePlay(audioSource);
        failAt(FailurePoint.AUDIO_SOURCE);
        checkAl("audio reconstruction");
    }

    /** Read back actual GL resources, render the rebuilt shader, and inspect the native AL source. */
    public void verifyNativeState() {
        ownerThread.requireOwner();
        if (state != State.ACTIVE) throw new IllegalStateException("Native state is unavailable in " + state);
        if (glfwGetCurrentContext() != window || alcGetCurrentContext() != audioContext) {
            throw new IllegalStateException("Rebuilt contexts are not current on their owner thread");
        }
        byte[] expectedPixels = assets.rgba();
        ByteBuffer readback = memAlloc(expectedPixels.length);
        try {
            glBindTexture(GL_TEXTURE_2D, texture);
            glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, readback);
            byte[] actual = new byte[expectedPixels.length];
            readback.get(actual);
            if (!Arrays.equals(expectedPixels, actual)) throw new IllegalStateException("Rebuilt texture pixels differ");
        } finally {
            memFree(readback);
        }
        float[] expectedVertices = assets.vertices();
        FloatBuffer vertices = memAllocFloat(expectedVertices.length);
        try {
            glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
            glGetBufferSubData(GL_ARRAY_BUFFER, 0, vertices);
            float[] actual = new float[expectedVertices.length];
            vertices.get(actual);
            if (!Arrays.equals(expectedVertices, actual)) throw new IllegalStateException("Rebuilt vertex buffer differs");
        } finally {
            memFree(vertices);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            glfwGetFramebufferSize(window, width, height);
            glViewport(0, 0, width.get(0), height.get(0));
            glClearColor(0, 0, 0, 1);
            glClear(GL_COLOR_BUFFER_BIT);
            glUseProgram(program);
            glBindVertexArray(vertexArray);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            ByteBuffer pixel = stack.malloc(4);
            glReadPixels(width.get(0) / 2, height.get(0) / 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
            int[] expected = {64, 128, 191, 255};
            for (int channel = 0; channel < 4; channel++) {
                if (Math.abs(Byte.toUnsignedInt(pixel.get(channel)) - expected[channel]) > 1) {
                    throw new IllegalStateException("Rebuilt shader produced an unexpected framebuffer pixel");
                }
            }
            glfwGetWindowSize(window, width, height);
            if (width.get(0) != 32 || height.get(0) != 32 || glfwWindowShouldClose(window)) {
                throw new IllegalStateException("Rebuilt GLFW window has unexpected size or close state");
            }
        }
        if (alGetBufferi(audioBuffer, AL_SIZE) != assets.pcm().length * Short.BYTES
                || alGetBufferi(audioBuffer, AL_FREQUENCY) != assets.sampleRate()
                || alGetBufferi(audioBuffer, AL_BITS) != 16 || alGetBufferi(audioBuffer, AL_CHANNELS) != 1
                || alGetSourcei(audioSource, AL_BUFFER) != audioBuffer
                || alGetSourcei(audioSource, AL_SOURCE_STATE) != AL_PLAYING) {
            throw new IllegalStateException("Rebuilt OpenAL PCM buffer/source differs");
        }
        checkGl("GL readback and draw");
        checkAl("AL buffer/source verification");
        glfwPollEvents();
    }

    /** Exercises the recreated display callback, including after multiple reconstruction cycles. */
    public void verifyWindowCallback() {
        ownerThread.requireOwner();
        if (state != State.ACTIVE) throw new IllegalStateException("Window callback requires active state");
        int before = resizeEvents;
        glfwSetWindowSize(window, 40, 40);
        for (int attempt = 0; attempt < 10 && resizeEvents == before; attempt++) glfwWaitEventsTimeout(0.01);
        if (resizeEvents == before) throw new IllegalStateException("Rebuilt GLFW resize callback did not fire");
        glfwSetWindowSize(window, 32, 32);
        glfwPollEvents();
    }

    public void assertSuspended() {
        if ((state != State.SUSPENDED && state != State.REBUILD_PENDING) || !noHandles()) {
            throw new IllegalStateException("Native handles remain live at checkpoint boundary");
        }
    }

    private boolean noHandles() {
        return !glfwInitialized && errorCallback == null && keyCallback == null
                && sizeCallback == null && window == NULL && texture == 0 && vertexBuffer == 0
                && vertexArray == 0 && program == 0 && audioDevice == NULL && audioContext == NULL
                && audioBuffer == 0 && audioSource == 0;
    }

    private void destroyNative() {
        RuntimeException cleanupFailure = null;
        // Sources reference audio buffers; device lifetime encloses its context.
        try {
            if (audioSource != 0) {
                alSourceStop(audioSource);
                alSourcei(audioSource, AL_BUFFER, 0);
                alDeleteSources(audioSource);
                audioSource = 0;
            }
            if (audioBuffer != 0) { alDeleteBuffers(audioBuffer); audioBuffer = 0; }
            if (audioContext != NULL) {
                AL.setCurrentThread(null);
                AL.setCurrentProcess(null);
                if (!alcMakeContextCurrent(NULL)) throw new IllegalStateException("OpenAL context detach failed");
                alcDestroyContext(audioContext);
                audioContext = NULL;
            }
            if (audioDevice != NULL) {
                if (!alcCloseDevice(audioDevice)) throw new IllegalStateException("OpenAL device close failed");
                audioDevice = NULL;
            }
            if (alLoaded) ALC.setCapabilities(null);
        } catch (RuntimeException failure) {
            cleanupFailure = failure;
        }
        try {
            if (window != NULL && glLoaded) {
                glfwMakeContextCurrent(window);
                if (program != 0) { glUseProgram(0); glDeleteProgram(program); program = 0; }
                if (vertexArray != 0) { glBindVertexArray(0); glDeleteVertexArrays(vertexArray); vertexArray = 0; }
                if (vertexBuffer != 0) { glDeleteBuffers(vertexBuffer); vertexBuffer = 0; }
                if (texture != 0) { glDeleteTextures(texture); texture = 0; }
                checkGl("graphics deletion");
                glFinish();
                GL.setCapabilities(null);
                glfwMakeContextCurrent(NULL);
            }
            if (window != NULL) {
                glfwSetKeyCallback(window, null);
                glfwSetWindowSizeCallback(window, null);
                glfwDestroyWindow(window);
                window = NULL;
            }
            if (keyCallback != null) { keyCallback.free(); keyCallback = null; }
            if (sizeCallback != null) { sizeCallback.free(); sizeCallback = null; }
            if (glfwInitialized) { glfwTerminate(); glfwInitialized = false; }
            if (errorCallback != null) {
                glfwSetErrorCallback(null);
                errorCallback.free();
                errorCallback = null;
            }
        } catch (RuntimeException failure) {
            if (cleanupFailure == null) cleanupFailure = failure;
            else cleanupFailure.addSuppressed(failure);
        }
        if (cleanupFailure != null) throw cleanupFailure;
    }

    private void failAt(FailurePoint point) {
        if (failOnce == point) {
            failOnce = FailurePoint.NONE;
            throw new InjectedFailure(point);
        }
    }

    public static final class InjectedFailure extends RuntimeException {
        private InjectedFailure(FailurePoint point) { super("Injected native reconstruction failure at " + point); }
    }

    private static void checkGl(String operation) {
        int error = glGetError();
        if (error != GL_NO_ERROR) throw new IllegalStateException(operation + " failed with GL error " + error);
    }

    private static void checkAl(String operation) {
        int error = alGetError();
        if (error != AL_NO_ERROR) throw new IllegalStateException(operation + " failed with AL error " + error);
    }

    @Override public void close() {
        ownerThread.requireOwner();
        if (state == State.CLOSED) return;
        try {
            if (GLOBAL_OWNER.get() == this) destroyNative();
            if (!noHandles()) throw new IllegalStateException("Native cleanup left live handles");
            state = State.CLOSED;
            GLOBAL_OWNER.compareAndSet(this, null);
        } catch (RuntimeException | Error failure) {
            state = State.BROKEN;
            throw failure;
        }
    }
}
