package dev.loadonce.prototype;

import dev.loadonce.runtime.CheckpointCoordinator;
import dev.loadonce.runtime.CheckpointGate;
import dev.loadonce.runtime.CheckpointResource;
import dev.loadonce.runtime.CracBridge;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import org.lwjgl.glfw.Callbacks;
import org.lwjgl.glfw.GLFWCursorPosCallback;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.glfw.GLFWWindowCloseCallback;
import org.lwjgl.glfw.GLFWWindowSizeCallback;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.Callback;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.AL11.AL_SAMPLE_OFFSET;
import static org.lwjgl.openal.ALC10.*;
import static org.lwjgl.openal.SOFTBufferSamples.*;
import static org.lwjgl.openal.SOFTLoopback.*;
import static org.lwjgl.opengl.GL33C.*;

/** Real native lifecycle proof, deliberately separate from Minecraft and process images. */
public final class NativeRestorationProbe {
    private NativeRestorationProbe() {}

    public static void main(String[] args) throws Exception {
        int cycles = 3;
        boolean invalidShaderOnRestore = false;
        boolean crac = false;
        boolean audioLoopback = false;
        for (String arg : args) {
            if (arg.startsWith("--cycles=")) {
                cycles = Integer.parseInt(arg.substring("--cycles=".length()));
            } else if (arg.equals("--invalid-shader-on-restore")) {
                invalidShaderOnRestore = true;
            } else if (arg.equals("--crac")) {
                crac = true;
            } else if (arg.equals("--audio-loopback")) {
                audioLoopback = true;
            } else {
                throw new IllegalArgumentException("Usage: NativeRestorationProbe [--cycles=1..100] [--crac] [--audio-loopback] [--invalid-shader-on-restore]");
            }
        }
        require(cycles >= 1 && cycles <= 100, "cycles must be between 1 and 100");
        System.out.println("NATIVE_PROBE mode=" + (crac ? "CRaC-callback" : "in-process-lifecycle")
                + "; no Minecraft renderer/session integration; callback/lifecycle proof alone does not establish a restorable OS image");
        String audioDriver = System.getenv("ALSOFT_DRIVERS");
        System.out.println("NATIVE_PROBE audio-driver=" + (audioLoopback ? "OpenAL Soft loopback" : audioDriver == null ? "system default" : audioDriver)
                + (audioLoopback ? "; rendered PCM is checked; physical playback is not measured"
                        : "null".equals(audioDriver) ? "; null backend proves OpenAL lifecycle, not physical audio" : "; physical playback is not measured"));

        WindowResource window = new WindowResource();
        GraphicsResource graphics = new GraphicsResource(window, invalidShaderOnRestore);
        AudioResource audio = new AudioResource(audioLoopback);
        CheckpointCoordinator coordinator = new CheckpointCoordinator(
                () -> CheckpointGate.Decision.allow("Isolated native probe has CPU descriptors and no sessions or world state"));
        coordinator.register(window);
        coordinator.register(graphics);
        coordinator.register(audio);
        CracBridge bridge = crac ? CracBridge.install(coordinator) : null;
        Throwable failure = null;
        try {
            window.restore();
            graphics.restore();
            audio.restore();
            audio.resume();
            verify(window, graphics, audio, 0);
            for (int cycle = 1; cycle <= cycles; cycle++) {
                if (bridge == null) {
                    coordinator.prepareCheckpoint();
                    assertReleased(window, graphics, audio);
                    coordinator.restoreCheckpoint();
                } else {
                    CracBridge.CallbackResult callbacks = bridge.checkpointRestore();
                    System.out.println("NATIVE_PROBE CRaC callbacks=" + callbacks);
                }
                verify(window, graphics, audio, cycle);
            }
        } catch (Throwable cause) {
            failure = cause;
            System.err.println("NATIVE_PROBE FAIL coordinator-state=" + coordinator.state());
            if (invalidShaderOnRestore && graphics.restores > 1) {
                try {
                    require(coordinator.state() == CheckpointCoordinator.State.STOPPED,
                            "partial GPU restoration failure did not stop the coordinator");
                    System.out.println("NATIVE_PROBE failure-stop-verified=true");
                } catch (Throwable assertion) {
                    cause.addSuppressed(assertion);
                }
            }
            rethrow(cause);
        } finally {
            Throwable cleanupCause = failure;
            Cleanup cleanup = new Cleanup();
            cleanup.run(() -> audio.stopSafely(cleanupCause));
            cleanup.run(() -> graphics.stopSafely(cleanupCause));
            cleanup.run(() -> window.stopSafely(cleanupCause));
            cleanup.run(() -> assertReleased(window, graphics, audio));
            if (cleanup.failure != null) {
                if (failure != null) failure.addSuppressed(cleanup.failure);
                else rethrow(cleanup.failure);
            }
            System.out.println("NATIVE_PROBE cleanup=tracked-handles-zero native-release="
                    + (cleanup.failure == null ? "ok" : "failed"));
        }
        System.out.println("NATIVE_PROBE PASS cycles=" + cycles);
    }

    private static void verify(WindowResource window, GraphicsResource graphics, AudioResource audio, int cycle) throws Exception {
        window.verify();
        graphics.verify();
        audio.verify();
        System.out.println("NATIVE_PROBE verified cycle=" + cycle
                + " window-state+restored-timer+native-callback-registration+resize-event+texture-bytes+buffer-floats+shader-link+rendered-pixels+audio-state"
                + (audio.loopback ? "+rendered-pcm-equality" : ""));
    }

    private static void assertReleased(WindowResource window, GraphicsResource graphics, AudioResource audio) {
        require(window.released(), "GLFW handles/callbacks retained after release");
        require(graphics.released(), "OpenGL object handles retained after release");
        require(audio.released(), "OpenAL device/context/object handles retained after release");
    }

    private abstract static class OwnedResource implements CheckpointResource {
        private final Thread owner = Thread.currentThread();

        final void owner() {
            require(Thread.currentThread() == owner, id() + " lifecycle called outside its native owner thread");
        }

        @Override public final void checkpoint() throws Exception {
            owner();
            release();
            require(released(), id() + " retained a native handle after checkpoint release");
        }
        @Override public final void stopSafely(Throwable cause) throws Exception { owner(); release(); }
        abstract void release() throws Exception;
        abstract boolean released();
    }

    private static final class WindowResource extends OwnedResource {
        private static final int WIDTH = 96;
        private static final int HEIGHT = 64;
        private long handle;
        private boolean initialized;
        private GLFWErrorCallback errorCallback;
        private GLFWWindowSizeCallback sizeCallback;
        private GLFWFramebufferSizeCallback framebufferCallback;
        private GLFWKeyCallback keyCallback;
        private GLFWMouseButtonCallback mouseCallback;
        private GLFWCursorPosCallback cursorCallback;
        private GLFWWindowCloseCallback closeCallback;
        private int sizeEvents;
        private int framebufferEvents;
        private double checkpointTime;
        private String lastError = "no GLFW error reported";

        @Override public String id() { return "glfw-window-context-callbacks"; }

        @Override public void quiesce() {
            owner();
            checkpointTime = glfwGetTime();
        }

        @Override public void restore() throws Exception {
            owner();
            require(released(), "GLFW restore requires released handles");
            try {
                errorCallback = GLFWErrorCallback.create((code, description) ->
                        lastError = "0x" + Integer.toHexString(code) + ": " + MemoryUtil.memUTF8(description));
                glfwSetErrorCallback(errorCallback);
                require(glfwInit(), "GLFW initialization failed; configure a display: " + lastError);
                initialized = true;
                // GLFW initialization resets its timer. Keep the application clock at the quiesced boundary.
                glfwSetTime(checkpointTime);
                glfwDefaultWindowHints();
                glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
                glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
                glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
                glfwWindowHint(GLFW_VISIBLE, GLFW_TRUE);
                glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
                handle = glfwCreateWindow(WIDTH, HEIGHT, "LoadOnce native restoration probe", 0, 0);
                require(handle != 0, "GLFW OpenGL 3.3 window creation failed: " + lastError);
                glfwMakeContextCurrent(handle);
                glfwSwapInterval(0);
                GL.createCapabilities();
                glfwSetWindowPos(handle, 20, 30);
                glfwSetInputMode(handle, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
                glfwSetInputMode(handle, GLFW_STICKY_KEYS, GLFW_TRUE);
                glfwSetWindowShouldClose(handle, false);
                sizeCallback = GLFWWindowSizeCallback.create((window, width, height) -> sizeEvents++);
                glfwSetWindowSizeCallback(handle, sizeCallback);
                framebufferCallback = GLFWFramebufferSizeCallback.create((window, width, height) -> framebufferEvents++);
                glfwSetFramebufferSizeCallback(handle, framebufferCallback);
                keyCallback = GLFWKeyCallback.create((window, key, scancode, action, mods) -> {});
                glfwSetKeyCallback(handle, keyCallback);
                mouseCallback = GLFWMouseButtonCallback.create((window, button, action, mods) -> {});
                glfwSetMouseButtonCallback(handle, mouseCallback);
                cursorCallback = GLFWCursorPosCallback.create((window, x, y) -> {});
                glfwSetCursorPosCallback(handle, cursorCallback);
                closeCallback = GLFWWindowCloseCallback.create(window -> glfwSetWindowShouldClose(window, true));
                glfwSetWindowCloseCallback(handle, closeCallback);
                System.out.println("NATIVE_PROBE GL renderer=" + glGetString(GL_RENDERER) + " version=" + glGetString(GL_VERSION));
            } catch (Throwable cause) {
                releaseAfterFailure(this, cause);
                rethrow(cause);
            }
        }

        void verify() {
            owner();
            require(handle != 0 && glfwGetCurrentContext() == handle, "GLFW context is not current");
            require(glfwGetTime() >= checkpointTime, "GLFW elapsed timer regressed across restoration");
            require(glfwGetWindowAttrib(handle, GLFW_VISIBLE) == GLFW_TRUE, "window visibility was not restored");
            require(glfwGetWindowAttrib(handle, GLFW_RESIZABLE) == GLFW_TRUE, "window resizability was not restored");
            require(glfwGetInputMode(handle, GLFW_CURSOR) == GLFW_CURSOR_NORMAL, "cursor mode was not restored");
            require(glfwGetInputMode(handle, GLFW_STICKY_KEYS) == GLFW_TRUE, "sticky-key mode was not restored");
            require(!glfwWindowShouldClose(handle), "window unexpectedly requests close");
            // The native setters return the actually installed function pointers. Do not invoke mock callbacks.
            sameCallback(glfwSetWindowSizeCallback(handle, sizeCallback), sizeCallback, "window size");
            sameCallback(glfwSetFramebufferSizeCallback(handle, framebufferCallback), framebufferCallback, "framebuffer size");
            sameCallback(glfwSetKeyCallback(handle, keyCallback), keyCallback, "key");
            sameCallback(glfwSetMouseButtonCallback(handle, mouseCallback), mouseCallback, "mouse button");
            sameCallback(glfwSetCursorPosCallback(handle, cursorCallback), cursorCallback, "cursor position");
            sameCallback(glfwSetWindowCloseCallback(handle, closeCallback), closeCallback, "close");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer width = stack.mallocInt(1);
                IntBuffer height = stack.mallocInt(1);
                IntBuffer x = stack.mallocInt(1);
                IntBuffer y = stack.mallocInt(1);
                glfwGetWindowSize(handle, width, height);
                require(width.get(0) == WIDTH && height.get(0) == HEIGHT, "window dimensions were not restored");
                glfwGetWindowPos(handle, x, y);
                require(x.get(0) == 20 && y.get(0) == 30, "window position was not restored");
                glfwGetFramebufferSize(handle, width, height);
                require(width.get(0) > 0 && height.get(0) > 0, "restored framebuffer is empty");
            }
            glfwPollEvents();
            int previousSizeEvents = sizeEvents;
            int previousFramebufferEvents = framebufferEvents;
            glfwSetWindowSize(handle, WIDTH + 8, HEIGHT + 8);
            awaitEvents(() -> sizeEvents > previousSizeEvents && framebufferEvents > previousFramebufferEvents,
                    "native resize/framebuffer callbacks were not delivered");
            int beforeReturn = sizeEvents;
            glfwSetWindowSize(handle, WIDTH, HEIGHT);
            awaitEvents(() -> sizeEvents > beforeReturn, "native callback did not observe restored window dimensions");
            glfwSwapBuffers(handle);
        }

        private void awaitEvents(BooleanSupplier condition, String message) {
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (!condition.getAsBoolean() && System.nanoTime() < deadline) glfwWaitEventsTimeout(0.01);
            require(condition.getAsBoolean(), message + ": " + lastError);
        }

        private static void sameCallback(Callback actual, Callback expected, String name) {
            require(actual != null && actual.address() == expected.address(), name + " native callback registration was not restored");
        }

        @Override void release() throws Exception {
            Cleanup cleanup = new Cleanup();
            long oldWindow = handle;
            handle = 0;
            if (oldWindow != 0) {
                cleanup.run(() -> Callbacks.glfwFreeCallbacks(oldWindow));
                cleanup.run(() -> GL.setCapabilities(null));
                cleanup.run(() -> glfwMakeContextCurrent(0));
                cleanup.run(() -> require(glfwGetCurrentContext() == 0, "released GLFW context remains current"));
                cleanup.run(() -> glfwDestroyWindow(oldWindow));
            }
            sizeCallback = null;
            framebufferCallback = null;
            keyCallback = null;
            mouseCallback = null;
            cursorCallback = null;
            closeCallback = null;
            if (initialized) {
                initialized = false;
                cleanup.run(() -> glfwTerminate());
            }
            if (errorCallback != null) {
                GLFWErrorCallback oldCallback = errorCallback;
                errorCallback = null;
                cleanup.run(() -> glfwSetErrorCallback(null));
                cleanup.run(oldCallback::free);
            }
            cleanup.finish();
        }

        boolean released() {
            return handle == 0 && !initialized && errorCallback == null && sizeCallback == null
                    && framebufferCallback == null && keyCallback == null && mouseCallback == null
                    && cursorCallback == null && closeCallback == null;
        }
    }

    private static final class GraphicsResource extends OwnedResource {
        private static final float[] VERTICES = {-1, -1, 3, -1, -1, 3};
        private static final byte[] PIXEL = {32, (byte) 160, (byte) 224, (byte) 255};
        private static final byte[] TEXTURE = new byte[16];
        private static final String VERTEX_SHADER = """
                #version 330 core
                layout(location = 0) in vec2 position;
                out vec2 uv;
                void main() { uv = (position + 1.0) * 0.5; gl_Position = vec4(position, 0.0, 1.0); }
                """;
        private static final String FRAGMENT_SHADER = """
                #version 330 core
                in vec2 uv;
                uniform sampler2D image;
                out vec4 color;
                void main() { color = texture(image, uv); }
                """;
        static {
            for (int pixel = 0; pixel < 4; pixel++) System.arraycopy(PIXEL, 0, TEXTURE, pixel * 4, 4);
        }
        private final WindowResource window;
        private final boolean invalidShaderOnRestore;
        private int restores;
        private int texture;
        private int targetTexture;
        private int vertexBuffer;
        private int vertexArray;
        private int framebuffer;
        private int program;

        GraphicsResource(WindowResource window, boolean invalidShaderOnRestore) {
            this.window = window;
            this.invalidShaderOnRestore = invalidShaderOnRestore;
        }

        @Override public String id() { return "gpu-cpu-descriptors"; }
        @Override public void quiesce() { owner(); glFinish(); }

        @Override public void restore() throws Exception {
            owner();
            require(released(), "GPU restore requires released object handles");
            require(window.handle != 0 && glfwGetCurrentContext() == window.handle, "GPU restoration needs the new current GLFW context");
            restores++;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                vertexArray = glGenVertexArrays();
                glBindVertexArray(vertexArray);
                vertexBuffer = glGenBuffers();
                glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
                glBufferData(GL_ARRAY_BUFFER, stack.floats(VERTICES), GL_STATIC_DRAW);
                glVertexAttribPointer(0, 2, GL_FLOAT, false, 2 * Float.BYTES, 0);
                glEnableVertexAttribArray(0);
                texture = glGenTextures();
                glBindTexture(GL_TEXTURE_2D, texture);
                textureParameters();
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 2, 2, 0, GL_RGBA, GL_UNSIGNED_BYTE, stack.bytes(TEXTURE));
                targetTexture = glGenTextures();
                glBindTexture(GL_TEXTURE_2D, targetTexture);
                textureParameters();
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 4, 4, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
                framebuffer = glGenFramebuffers();
                glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, targetTexture, 0);
                require(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE, "rebuilt GPU framebuffer is incomplete");
                int vertex = compileShader(GL_VERTEX_SHADER, VERTEX_SHADER);
                int fragment = 0;
                try {
                    fragment = compileShader(GL_FRAGMENT_SHADER, invalidShaderOnRestore && restores > 1
                            ? "#version 330 core\nthis is intentionally invalid GLSL" : FRAGMENT_SHADER);
                    program = glCreateProgram();
                    require(program != 0, "could not allocate GPU shader program");
                    glAttachShader(program, vertex);
                    glAttachShader(program, fragment);
                    glLinkProgram(program);
                    require(glGetProgrami(program, GL_LINK_STATUS) == GL_TRUE, "GPU shader link failed: " + glGetProgramInfoLog(program));
                } finally {
                    glDeleteShader(vertex);
                    if (fragment != 0) glDeleteShader(fragment);
                }
                checkGl("GPU descriptor upload");
            } catch (Throwable cause) {
                releaseAfterFailure(this, cause);
                rethrow(cause);
            }
        }

        private static void textureParameters() {
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        }

        private static int compileShader(int type, String source) {
            int shader = glCreateShader(type);
            require(shader != 0, "could not allocate GPU shader");
            try {
                glShaderSource(shader, source);
                glCompileShader(shader);
                require(glGetShaderi(shader, GL_COMPILE_STATUS) == GL_TRUE, "GPU shader compilation failed: " + glGetShaderInfoLog(shader));
                return shader;
            } catch (Throwable cause) {
                glDeleteShader(shader);
                throw cause;
            }
        }

        void verify() {
            owner();
            require(glIsTexture(texture) && glIsTexture(targetTexture) && glIsBuffer(vertexBuffer)
                    && glIsVertexArray(vertexArray) && glIsFramebuffer(framebuffer) && glIsProgram(program), "rebuilt GPU object is invalid");
            require(glGetProgrami(program, GL_LINK_STATUS) == GL_TRUE, "restored shader is unlinked");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                FloatBuffer buffer = stack.mallocFloat(VERTICES.length);
                glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
                glGetBufferSubData(GL_ARRAY_BUFFER, 0, buffer);
                for (int i = 0; i < VERTICES.length; i++) require(buffer.get(i) == VERTICES[i], "restored GPU buffer differs at float " + i);
                ByteBuffer bytes = stack.malloc(TEXTURE.length);
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, texture);
                glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, bytes);
                for (int i = 0; i < TEXTURE.length; i++) require(bytes.get(i) == TEXTURE[i], "restored GPU texture differs at byte " + i);
                glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
                require(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE, "restored framebuffer is incomplete");
                glViewport(0, 0, 4, 4);
                glDisable(GL_DITHER);
                glDisable(GL_FRAMEBUFFER_SRGB);
                glUseProgram(program);
                int sampler = glGetUniformLocation(program, "image");
                require(sampler >= 0, "restored texture shader uniform is missing");
                glUniform1i(sampler, 0);
                glBindVertexArray(vertexArray);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                ByteBuffer pixels = stack.malloc(4 * 4 * 4);
                glReadPixels(0, 0, 4, 4, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                for (int i = 0; i < pixels.capacity(); i++) {
                    require(pixels.get(i) == PIXEL[i % 4], "restored rendering differs at pixel byte " + i
                            + ": expected=" + Byte.toUnsignedInt(PIXEL[i % 4]) + " actual=" + Byte.toUnsignedInt(pixels.get(i)));
                }
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
                checkGl("GPU readback and rendering");
            }
        }

        @Override void release() throws Exception {
            Cleanup cleanup = new Cleanup();
            int oldProgram = program; program = 0;
            int oldFramebuffer = framebuffer; framebuffer = 0;
            int oldVertexArray = vertexArray; vertexArray = 0;
            int oldVertexBuffer = vertexBuffer; vertexBuffer = 0;
            int oldTexture = texture; texture = 0;
            int oldTarget = targetTexture; targetTexture = 0;
            boolean hadObjects = oldProgram != 0 || oldFramebuffer != 0 || oldVertexArray != 0
                    || oldVertexBuffer != 0 || oldTexture != 0 || oldTarget != 0;
            if (hadObjects) {
                cleanup.run(() -> glUseProgram(0));
                cleanup.run(() -> glBindVertexArray(0));
                cleanup.run(() -> glBindBuffer(GL_ARRAY_BUFFER, 0));
                cleanup.run(() -> glBindFramebuffer(GL_FRAMEBUFFER, 0));
                cleanup.run(() -> glBindTexture(GL_TEXTURE_2D, 0));
            }
            if (oldProgram != 0) cleanup.run(() -> glDeleteProgram(oldProgram));
            if (oldFramebuffer != 0) cleanup.run(() -> glDeleteFramebuffers(oldFramebuffer));
            if (oldVertexArray != 0) cleanup.run(() -> glDeleteVertexArrays(oldVertexArray));
            if (oldVertexBuffer != 0) cleanup.run(() -> glDeleteBuffers(oldVertexBuffer));
            if (oldTexture != 0) cleanup.run(() -> glDeleteTextures(oldTexture));
            if (oldTarget != 0) cleanup.run(() -> glDeleteTextures(oldTarget));
            if (hadObjects) cleanup.run(() -> require(!glIsProgram(oldProgram) && !glIsFramebuffer(oldFramebuffer)
                    && !glIsVertexArray(oldVertexArray) && !glIsBuffer(oldVertexBuffer)
                    && !glIsTexture(oldTexture) && !glIsTexture(oldTarget), "deleted GPU object is still live"));
            if (hadObjects) cleanup.run(() -> checkGl("GPU object release"));
            cleanup.finish();
        }

        boolean released() {
            return texture == 0 && targetTexture == 0 && vertexBuffer == 0 && vertexArray == 0 && framebuffer == 0 && program == 0;
        }
    }

    private static final class AudioResource extends OwnedResource {
        private static final int SAMPLE_RATE = 16_000;
        private static final int RENDER_FRAMES = 2048;
        private final boolean loopback;
        private final short[] pcm = new short[4096];
        private long device;
        private long context;
        private int buffer;
        private int source;
        private int verificationSource;
        private int sampleOffset = 128;
        private boolean wasPlaying = true;
        private float[] referenceRender;

        AudioResource(boolean loopback) {
            this.loopback = loopback;
            for (int i = 0; i < pcm.length; i++) pcm[i] = (short) (Math.sin(2 * Math.PI * 440 * i / SAMPLE_RATE) * 2048);
        }

        @Override public String id() { return "openal-device-context-pcm-source"; }
        @Override public void quiesce() {
            owner();
            if (source != 0) {
                wasPlaying = alGetSourcei(source, AL_SOURCE_STATE) == AL_PLAYING;
                // Freeze a real device's asynchronous mixer before recording the playback cursor.
                alSourcePause(source);
                sampleOffset = alGetSourcei(source, AL_SAMPLE_OFFSET);
                // Keep it paused so a preparation rollback can resume the existing source at this cursor.
            }
            checkAl("audio quiescence");
        }

        @Override public void resume() {
            owner();
            if (wasPlaying && source != 0) alSourcePlay(source);
            // OpenAL reports zero while a source is AL_INITIAL, even after an offset has been queued.
            if (loopback && wasPlaying && source != 0) require(alGetSourcei(source, AL_SAMPLE_OFFSET) == sampleOffset,
                    "audio playback position was not restored on resume");
            checkAl("audio playback resume");
        }

        @Override public void restore() throws Exception {
            owner();
            require(released(), "OpenAL restore requires released handles");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                if (loopback) {
                    require(ALC.createCapabilities(0).ALC_SOFT_loopback, "OpenAL Soft loopback extension unavailable");
                    device = alcLoopbackOpenDeviceSOFT((ByteBuffer) null);
                } else {
                    device = alcOpenDevice((ByteBuffer) null);
                }
                require(device != 0, loopback ? "OpenAL loopback device creation failed"
                        : "OpenAL device unavailable; configure a real device or explicit test ALSOFT_DRIVERS=null");
                ALCCapabilities capabilities = ALC.createCapabilities(device);
                IntBuffer attributes = null;
                if (loopback) {
                    require(capabilities.ALC_SOFT_loopback && alcIsRenderFormatSupportedSOFT(device, SAMPLE_RATE, ALC_MONO_SOFT, ALC_FLOAT_SOFT),
                            "OpenAL loopback does not support the requested mono float format");
                    attributes = stack.ints(ALC_FREQUENCY, SAMPLE_RATE, ALC_FORMAT_CHANNELS_SOFT, ALC_MONO_SOFT,
                            ALC_FORMAT_TYPE_SOFT, ALC_FLOAT_SOFT, 0);
                }
                context = alcCreateContext(device, attributes);
                require(context != 0, "OpenAL context creation failed: 0x" + Integer.toHexString(alcGetError(device)));
                require(alcMakeContextCurrent(context), "OpenAL context could not become current");
                AL.createCapabilities(capabilities);
                buffer = alGenBuffers();
                alBufferData(buffer, AL_FORMAT_MONO16, stack.shorts(pcm), SAMPLE_RATE);
                source = alGenSources();
                alSourcei(source, AL_BUFFER, buffer);
                alSourcei(source, AL_LOOPING, AL_TRUE);
                alSourcef(source, AL_GAIN, 0.0f);
                alSourcei(source, AL_SAMPLE_OFFSET, sampleOffset);
                checkAl("audio PCM/source restoration");
                System.out.println("NATIVE_PROBE AL device=" + alcGetString(device, ALC_DEVICE_SPECIFIER)
                        + " renderer=" + alGetString(AL_RENDERER) + " pcm-hash=" + Arrays.hashCode(pcm)
                        + " restored-sample-offset=" + sampleOffset);
            } catch (Throwable cause) {
                releaseAfterFailure(this, cause);
                rethrow(cause);
            }
        }

        void verify() throws Exception {
            owner();
            require(device != 0 && context != 0 && alcGetCurrentContext() == context, "restored OpenAL context is not current");
            require(alIsBuffer(buffer) && alIsSource(source), "restored OpenAL object is invalid");
            require(alGetBufferi(buffer, AL_SIZE) == pcm.length * Short.BYTES, "restored PCM buffer size differs");
            require(alGetBufferi(buffer, AL_FREQUENCY) == SAMPLE_RATE && alGetBufferi(buffer, AL_BITS) == 16
                    && alGetBufferi(buffer, AL_CHANNELS) == 1, "restored PCM format differs");
            require(alGetSourcei(source, AL_BUFFER) == buffer && alGetSourcei(source, AL_LOOPING) == AL_TRUE,
                    "restored source attachment/loop state differs");
            require(alGetSourcei(source, AL_SOURCE_STATE) == AL_PLAYING, "restored audio source is not playing");
            require(alGetSourcef(source, AL_GAIN) == 0.0f, "probe source should remain muted");
            if (loopback) {
                require(alGetSourcei(source, AL_SAMPLE_OFFSET) == sampleOffset, "loopback playback offset changed without rendering");
                verifyRenderedSamples();
            }
            if (AL.getCapabilities().AL_SOFT_buffer_samples) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    ShortBuffer samples = stack.mallocShort(pcm.length);
                    alGetBufferSamplesSOFT(buffer, 0, pcm.length, AL_MONO_SOFT, AL_SHORT_SOFT, samples);
                    checkAl("audio PCM buffer readback");
                    for (int i = 0; i < pcm.length; i++) require(samples.get(i) == pcm[i], "restored PCM differs at sample " + i);
                }
            }
            checkAl("audio state verification");
            require(alcGetError(device) == ALC_NO_ERROR, "OpenAL device reported an error");
        }

        private void verifyRenderedSamples() throws Exception {
            Throwable failure = null;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                // The main source stays muted. A new source makes the mixer/resampler input repeatable for each generation.
                verificationSource = alGenSources();
                require(verificationSource != 0, "audio render verification source allocation failed");
                alSourcei(verificationSource, AL_BUFFER, buffer);
                alSourcei(verificationSource, AL_LOOPING, AL_TRUE);
                alSourcei(verificationSource, AL_SOURCE_RELATIVE, AL_TRUE);
                alSourcef(verificationSource, AL_GAIN, 0.25f);
                alSourcePlay(verificationSource);
                checkAl("audio render verification source setup");
                FloatBuffer output = stack.mallocFloat(RENDER_FRAMES);
                alcRenderSamplesSOFT(device, output, RENDER_FRAMES);
                require(alcGetError(device) == ALC_NO_ERROR, "OpenAL loopback sample rendering failed");
                float[] rendered = new float[RENDER_FRAMES];
                output.get(rendered);
                double energy = 0;
                for (int i = 0; i < rendered.length; i++) {
                    require(Float.isFinite(rendered[i]), "nonfinite rendered PCM at sample " + i);
                    energy += rendered[i] * rendered[i];
                    if (referenceRender != null) require(Math.abs(rendered[i] - referenceRender[i]) <= 0.000001f,
                            "restored audio rendering differs at sample " + i + ": expected=" + referenceRender[i] + " actual=" + rendered[i]);
                }
                require(energy > 0.01, "OpenAL loopback rendered silence instead of the uploaded PCM");
                if (referenceRender == null) referenceRender = rendered;
                int expectedOffset = (sampleOffset + RENDER_FRAMES) % pcm.length;
                require(alGetSourcei(source, AL_SAMPLE_OFFSET) == expectedOffset,
                        "loopback source playback position did not advance by rendered frames");
                checkAl("audio rendered PCM and playback position verification");
                System.out.println("NATIVE_PROBE audio-render frames=" + RENDER_FRAMES
                        + " mono-float-hash=" + Arrays.hashCode(rendered) + " energy=" + energy
                        + " reference-equal=true buffer-readback=" + AL.getCapabilities().AL_SOFT_buffer_samples);
            } catch (Throwable cause) {
                failure = cause;
                rethrow(cause);
            } finally {
                int oldVerificationSource = verificationSource;
                verificationSource = 0;
                Cleanup cleanup = new Cleanup();
                if (oldVerificationSource != 0) {
                    cleanup.run(() -> alSourceStop(oldVerificationSource));
                    cleanup.run(() -> alDeleteSources(oldVerificationSource));
                    cleanup.run(() -> require(!alIsSource(oldVerificationSource), "deleted audio verification source is still live"));
                    cleanup.run(() -> checkAl("audio verification source release"));
                }
                if (cleanup.failure != null) {
                    if (failure != null) failure.addSuppressed(cleanup.failure);
                    else cleanup.finish();
                }
            }
        }

        @Override void release() throws Exception {
            Cleanup cleanup = new Cleanup();
            int oldVerificationSource = verificationSource; verificationSource = 0;
            int oldSource = source; source = 0;
            int oldBuffer = buffer; buffer = 0;
            long oldContext = context; context = 0;
            long oldDevice = device; device = 0;
            if (oldVerificationSource != 0) {
                cleanup.run(() -> alSourceStop(oldVerificationSource));
                cleanup.run(() -> alDeleteSources(oldVerificationSource));
            }
            if (oldSource != 0) {
                cleanup.run(() -> alSourceStop(oldSource));
                cleanup.run(() -> alDeleteSources(oldSource));
            }
            if (oldBuffer != 0) cleanup.run(() -> alDeleteBuffers(oldBuffer));
            if (oldSource != 0 || oldBuffer != 0 || oldVerificationSource != 0) {
                cleanup.run(() -> require((oldSource == 0 || !alIsSource(oldSource))
                        && (oldVerificationSource == 0 || !alIsSource(oldVerificationSource))
                        && (oldBuffer == 0 || !alIsBuffer(oldBuffer)),
                        "deleted OpenAL object is still live"));
                cleanup.run(() -> checkAl("OpenAL object release"));
            }
            if (oldContext != 0) {
                cleanup.run(() -> require(alcMakeContextCurrent(0), "OpenAL context could not be detached"));
                cleanup.run(() -> require(alcGetCurrentContext() == 0, "released OpenAL context remains current"));
                cleanup.run(() -> AL.setCurrentThread(null));
                cleanup.run(() -> AL.setCurrentProcess(null));
                cleanup.run(() -> alcDestroyContext(oldContext));
            }
            if (oldDevice != 0) {
                cleanup.run(() -> require(alcCloseDevice(oldDevice), "OpenAL device could not be closed"));
                cleanup.run(() -> ALC.setCapabilities(null));
            }
            cleanup.finish();
        }

        boolean released() { return device == 0 && context == 0 && buffer == 0 && source == 0 && verificationSource == 0; }
    }

    private static void checkGl(String operation) {
        int error = glGetError();
        require(error == GL_NO_ERROR, operation + " failed with GL error 0x" + Integer.toHexString(error));
    }

    private static void checkAl(String operation) {
        int error = alGetError();
        require(error == AL_NO_ERROR, operation + " failed with AL error 0x" + Integer.toHexString(error));
    }

    private static void releaseAfterFailure(OwnedResource resource, Throwable cause) {
        try { resource.release(); } catch (Throwable cleanup) { cause.addSuppressed(cleanup); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void rethrow(Throwable cause) throws Exception {
        if (cause instanceof Exception exception) throw exception;
        if (cause instanceof Error error) throw error;
        throw new RuntimeException(cause);
    }

    @FunctionalInterface private interface CleanupAction { void run() throws Exception; }

    private static final class Cleanup {
        private Throwable failure;

        void run(CleanupAction action) {
            try { action.run(); }
            catch (Throwable cause) {
                if (failure == null) failure = cause;
                else failure.addSuppressed(cause);
            }
        }

        void finish() throws Exception { if (failure != null) rethrow(failure); }
    }
}
