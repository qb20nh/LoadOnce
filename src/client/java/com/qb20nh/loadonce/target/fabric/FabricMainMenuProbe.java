package com.qb20nh.loadonce.target.fabric;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Opt-in readiness evidence for Fabric/Minecraft 26.3's SDL/RenderPearl client.
 * This observes conventional startup; it neither takes a checkpoint nor restores natives.
 */
public final class FabricMainMenuProbe {
	private static final Logger LOGGER = LoggerFactory.getLogger("loadonce-menu-probe");
	private static FabricMainMenuProbe instance;

	private enum Outcome { RUNNING, PASSED, FAILED }

	private final boolean exitWhenFinished = Boolean.getBoolean("loadonce.menuProbe.exit");
	private final boolean saveScreenshot = Boolean.getBoolean("loadonce.menuProbe.screenshot");
	private final int requiredFrames = integerProperty("loadonce.menuProbe.frames", 3, 1, 120);
	private final int timeoutSeconds = integerProperty("loadonce.menuProbe.timeoutSeconds", 120, 1, 600);
	private final long startedNanos = System.nanoTime();
	private final AtomicReference<Outcome> outcome = new AtomicReference<>(Outcome.RUNNING);
	private final ScheduledExecutorService deadline = Executors.newSingleThreadScheduledExecutor(task -> {
		Thread thread = new Thread(task, "LoadOnce menu probe deadline");
		thread.setDaemon(true);
		return thread;
	});
	private volatile String lastObservation = "phase=initializing";
	private Screen extractingScreen;
	private Screen consecutiveScreen;
	private boolean extracted;
	private boolean presented;
	private boolean readbackRequested;
	private int consecutiveFrames;
	private int capturedFrames;

	private FabricMainMenuProbe() {
	}

	public static void initialize() {
		if (!Boolean.getBoolean("loadonce.menuProbe")) {
			return;
		}
		if (instance != null) {
			throw new IllegalStateException("LoadOnce menu probe already initialized");
		}
		instance = new FabricMainMenuProbe();
		FabricMainMenuProbe probe = instance;
		ClientTickEvents.END_CLIENT_TICK.register(probe::onTick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			probe.fail("client-stopped-before-evidence");
			probe.deadline.shutdownNow();
		});
		probe.deadline.schedule(() -> probe.fail("timeout"), probe.timeoutSeconds, TimeUnit.SECONDS);
		LOGGER.info("LOADONCE_MENU_PROBE_STARTED target=fabric-26.3-sdl frames={} timeoutSeconds={} screenshot={}",
				probe.requiredFrames, probe.timeoutSeconds, probe.saveScreenshot);
	}

	/** Called at frame entry, including frames that return without acquiring a surface. */
	public static void frameStarted(Minecraft client) {
		if (instance == null || instance.outcome.get() != Outcome.RUNNING) {
			return;
		}
		assertOwnerThread(client);
		instance.extractingScreen = null;
		instance.extracted = false;
		instance.presented = false;
	}

	/** Brackets the real extraction call, rather than accepting a tick with a TitleScreen. */
	public static void beforeExtraction(Minecraft client) {
		if (instance != null && instance.outcome.get() == Outcome.RUNNING) {
			assertOwnerThread(client);
			instance.extractingScreen = eligibleScreen(client);
		}
	}

	public static void afterExtraction(Minecraft client) {
		if (instance != null && instance.outcome.get() == Outcome.RUNNING) {
			assertOwnerThread(client);
			instance.extracted = instance.extractingScreen != null
					&& instance.extractingScreen == eligibleScreen(client);
		}
	}

	/** The mixin calls this only after GpuSurface.present() returns successfully. */
	public static void afterPresent(Minecraft client) {
		if (instance != null && instance.outcome.get() == Outcome.RUNNING) {
			assertOwnerThread(client);
			instance.presented = true;
		}
	}

	public static void frameFinished(Minecraft client) {
		FabricMainMenuProbe probe = instance;
		if (probe == null) {
			return;
		}
		assertOwnerThread(client);
		if (probe.outcome.get() != Outcome.RUNNING) {
			probe.stopIfRequested(client);
			return;
		}
		Screen screen = eligibleScreen(client);
		if (!probe.extracted || !probe.presented || screen != probe.extractingScreen) {
			probe.consecutiveFrames = 0;
			probe.consecutiveScreen = null;
			return;
		}
		if (probe.consecutiveScreen != screen) {
			probe.consecutiveFrames = 0;
			probe.consecutiveScreen = screen;
		}
		probe.consecutiveFrames++;
		if (!probe.readbackRequested) {
			probe.lastObservation = "phase=presented-menu frames=" + probe.consecutiveFrames;
		}
		if (probe.consecutiveFrames >= probe.requiredFrames && !probe.readbackRequested) {
			probe.readbackRequested = true;
			probe.capturedFrames = probe.consecutiveFrames;
			probe.lastObservation = "phase=gpu-readback frames=" + probe.consecutiveFrames;
			// The normal screenshot path copies GPU pixels and invokes the callback after
			// completion. Keep rendering until that callback; a queued copy is not evidence.
			Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> probe.onReadback(client, screen, image));
		}
	}

	private void onTick(Minecraft client) {
		if (outcome.get() == Outcome.RUNNING && !readbackRequested) {
			lastObservation = "phase=waiting-menu loaded=" + client.isGameLoadFinished()
					+ " title=" + (client.gui.screen() instanceof TitleScreen)
					+ " overlay=" + (client.gui.overlay() != null)
					+ " world=" + (client.level != null);
		}
		stopIfRequested(client);
	}

	private void onReadback(Minecraft client, Screen screen, NativeImage image) {
		try (image) {
			assertOwnerThread(client);
			if (outcome.get() != Outcome.RUNNING) {
				return;
			}
			if (screen != eligibleScreen(client)) {
				fail("screen-changed-during-readback");
				return;
			}
			int width = image.getWidth();
			int height = image.getHeight();
			if (width <= 0 || height <= 0) {
				fail("empty-framebuffer");
				return;
			}
			int firstRgb = image.getPixel(0, 0) & 0x00ffffff;
			boolean varied = false;
			long hash = 0xcbf29ce484222325L;
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					int pixel = image.getPixel(x, y);
					varied |= (pixel & 0x00ffffff) != firstRgb;
					hash = (hash ^ Integer.toUnsignedLong(pixel)) * 0x100000001b3L;
				}
			}
			if (!varied) {
				fail("uniform-framebuffer");
				return;
			}
			if (saveScreenshot) {
				Path directory = client.gameDirectory.toPath().resolve("loadonce-menu-probe");
				Files.createDirectories(directory);
				image.writeToFile(directory.resolve("menu-" + ProcessHandle.current().pid() + ".png"));
			}
			var device = RenderSystem.getDevice().getDeviceInfo();
			if (outcome.compareAndSet(Outcome.RUNNING, Outcome.PASSED)) {
				deadline.shutdownNow();
				LOGGER.info("LOADONCE_MENU_PROBE_PASSED target=fabric-26.3-sdl milestone=rendered-main-menu"
						+ " frames={} framebuffer={}x{} pixelHash={} elapsedMs={} window={}x{}"
						+ " backend={} deviceType={} audioChannels=\"{}\" screenshot={}"
						+ " inputVerified=false restorationVerified=false checkpointTaken=false",
						capturedFrames, width, height, Long.toUnsignedString(hash, 16), elapsedMillis(),
						client.getWindow().getWidth(), client.getWindow().getHeight(),
						device.backendName(), device.type(), client.getSoundManager().getChannelDebugString(), saveScreenshot);
			}
		} catch (IOException exception) {
			fail("screenshot-write-" + exception.getClass().getSimpleName());
		}
		// Exit is deferred until frameFinished/onTick, after the GPU callback releases its buffer.
	}

	private void stopIfRequested(Minecraft client) {
		if (exitWhenFinished && outcome.get() != Outcome.RUNNING) {
			client.stop();
		}
	}

	private void fail(String reason) {
		if (outcome.compareAndSet(Outcome.RUNNING, Outcome.FAILED)) {
			LOGGER.error("LOADONCE_MENU_PROBE_FAILED reason={} elapsedMs={} {}", reason, elapsedMillis(), lastObservation);
			deadline.shutdownNow();
		}
	}

	private long elapsedMillis() {
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
	}

	private static Screen eligibleScreen(Minecraft client) {
		Screen screen = client.gui.screen();
		if (!client.isGameLoadFinished() || !(screen instanceof TitleScreen) || client.gui.overlay() != null
				|| client.level != null || client.player != null || client.hasSingleplayerServer()
				|| client.getConnection() != null || client.getWindow().isIconified()
				|| client.getWindow().getWidth() <= 0 || client.getWindow().getHeight() <= 0) {
			return null;
		}
		int activeWidgets = 0;
		for (var child : screen.children()) {
			if (child instanceof AbstractWidget widget && widget.visible && widget.active) {
				if (widget.getAlpha() < 1.0F) {
					return null;
				}
				activeWidgets++;
			}
		}
		return activeWidgets > 0 ? screen : null;
	}

	private static void assertOwnerThread(Minecraft client) {
		RenderSystem.assertOnRenderThread();
		if (Thread.currentThread() != client.getRunningThread()) {
			throw new IllegalStateException("LoadOnce menu probe requires the client/render owner thread");
		}
	}

	private static int integerProperty(String name, int defaultValue, int minimum, int maximum) {
		String raw = System.getProperty(name);
		int value = raw == null ? defaultValue : Integer.parseInt(raw);
		if (value < minimum || value > maximum) {
			throw new IllegalArgumentException(name + " must be in " + minimum + ".." + maximum);
		}
		return value;
	}
}
