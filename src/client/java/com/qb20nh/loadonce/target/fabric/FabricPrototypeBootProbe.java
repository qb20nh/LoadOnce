package com.qb20nh.loadonce.target.fabric;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDLKeycode;
import org.lwjgl.sdl.SDLScancode;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in acceptance of a normal game boot after the separate CPU-only bootstrap. */
public final class FabricPrototypeBootProbe {
	private static final Logger LOGGER = LoggerFactory.getLogger("loadonce-prototype");
	private static final String WORLD = "LoadOncePrototype";
	private static FabricPrototypeBootProbe instance;

	private enum Outcome { RUNNING, PASSED, FAILED }
	private enum Phase { MENU, MENU_READBACK, MENU_INPUT, LOADING, WORLD, WORLD_READBACK,
		WORLD_INPUT, WRITE_WITNESS, SAVING, RELOADING, RELOADED, RELOADED_READBACK }

	private final long startedNanos = System.nanoTime();
	private final AtomicReference<Outcome> outcome = new AtomicReference<>(Outcome.RUNNING);
	private final ScheduledExecutorService deadline = Executors.newSingleThreadScheduledExecutor(task -> {
		Thread thread = new Thread(task, "LoadOnce prototype verification deadline");
		thread.setDaemon(true);
		return thread;
	});
	private volatile Phase phase = Phase.MENU;
	private volatile int menuFrames;
	private volatile int worldFrames;
	private int reloadedFrames;
	private boolean presented;
	private Screen frameScreen;
	private Object focusBefore;
	private Screen menuScreen;
	private boolean menuInputVerified;
	private boolean worldInputVerified;
	private boolean keyHeld;
	private Vec3 inputPosition;
	private int inputTick;
	private int initialWorldTick;
	private int reloadTick;
	private BlockPos witness;
	private Block witnessBlock;
	private CompletableFuture<Boolean> witnessTask;
	private Path worldDirectory;
	private boolean advancing;
	private Phase reportedPhase;
	private String reportedState;
	private volatile String lastState = "initializing";
	private long frameSequence;
	private long dialogInputFrame = -10;
	private Screen submittedDialog;

	private FabricPrototypeBootProbe() {}

	public static void initialize() {
		if (!Boolean.getBoolean("loadonce.prototype.verify")) {
			return;
		}
		if (instance != null) {
			throw new IllegalStateException("LoadOnce prototype probe already initialized");
		}
		instance = new FabricPrototypeBootProbe();
		FabricPrototypeBootProbe probe = instance;
		ClientTickEvents.END_CLIENT_TICK.register(probe::onTick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			probe.fail("client-stopped-before-evidence");
			probe.deadline.shutdownNow();
		});
		probe.deadline.schedule(() -> probe.fail("timeout"), 600, TimeUnit.SECONDS);
		LOGGER.info("LOADONCE_PROTOTYPE_STARTED world={} timeoutSeconds=600", WORLD);
	}

	public static void frameStarted(Minecraft client) {
		FabricPrototypeBootProbe probe = instance;
		if (probe == null || probe.outcome.get() != Outcome.RUNNING) {
			return;
		}
		assertOwner(client);
		probe.presented = false;
		probe.frameScreen = client.gui.screen();
		probe.frameSequence++;
	}

	public static void afterPresent(Minecraft client) {
		FabricPrototypeBootProbe probe = instance;
		if (probe != null && probe.outcome.get() == Outcome.RUNNING) {
			assertOwner(client);
			probe.presented = true;
		}
	}

	public static void frameFinished(Minecraft client) {
		FabricPrototypeBootProbe probe = instance;
		if (probe == null) {
			return;
		}
		assertOwner(client);
		if (probe.outcome.get() != Outcome.RUNNING) {
			probe.stop(client);
			return;
		}
		try {
			// Saving/loading screens can suppress client ticks while frames continue.
			// The guard in onTick also covers frames rendered recursively by disconnect.
			probe.onTick(client);
			if (probe.outcome.get() != Outcome.RUNNING) {
				probe.stop(client);
				return;
			}
			if (!probe.presented || probe.frameScreen != client.gui.screen() || client.gui.overlay() != null) {
				return;
			}
			if (probe.phase == Phase.MENU && eligibleMenu(client)) {
				if (++probe.menuFrames >= 3) {
					probe.menuScreen = client.gui.screen();
					probe.phase = Phase.MENU_READBACK;
					probe.readback(client, Phase.MENU_READBACK, "menu");
				}
			} else if (probe.phase == Phase.WORLD && eligibleWorld(client)) {
				if (++probe.worldFrames >= 3 && client.getSingleplayerServer().getTickCount() > probe.initialWorldTick + 3) {
					probe.phase = Phase.WORLD_READBACK;
					probe.readback(client, Phase.WORLD_READBACK, "world");
				}
			} else if (probe.phase == Phase.RELOADED && eligibleWorld(client)) {
				if (++probe.reloadedFrames >= 3 && client.getSingleplayerServer().getTickCount() > probe.reloadTick + 3) {
					probe.phase = Phase.RELOADED_READBACK;
					probe.readback(client, Phase.RELOADED_READBACK, "reloaded-world");
				}
			}
		} catch (Exception failure) {
			probe.fail("frame-" + failure.getClass().getSimpleName());
		}
	}

	private void onTick(Minecraft client) {
		assertOwner(client);
		if (advancing) {
			return;
		}
		if (outcome.get() != Outcome.RUNNING) {
			stop(client);
			return;
		}
		advancing = true;
		try {
			lastState = "level=" + (client.level != null) + " player=" + (client.player != null)
					+ " server=" + (client.getSingleplayerServer() != null)
					+ " screen=" + (client.gui.screen() == null ? "none" : client.gui.screen().getClass().getSimpleName());
			if (reportedPhase != phase || !lastState.equals(reportedState)) {
				reportedPhase = phase;
				reportedState = lastState;
				LOGGER.info("LOADONCE_PROTOTYPE_PHASE phase={} {}", phase, lastState);
			}
			if (worldDirectory == null) {
				Path gameDirectory = client.gameDirectory.toPath();
				if (!WORLD.equals(System.getProperty("loadonce.prototype.world", WORLD))
						|| !Files.isRegularFile(gameDirectory.resolve(".loadonce-prototype"))) {
					fail("private-prototype-profile-required");
					return;
				}
				worldDirectory = gameDirectory.resolve("saves").resolve(WORLD);
			}
			switch (phase) {
				case MENU_INPUT -> {
					if (client.gui.screen() != menuScreen) {
						fail("unexpected-menu-screen");
					} else if (menuScreen.getFocused() != null && menuScreen.getFocused() != focusBefore) {
						menuInputVerified = true;
						LOGGER.info("LOADONCE_PROTOTYPE_INPUT phase=menu evidence=sdl-tab-focus-change");
						openWorld(client);
					}
				}
				case LOADING -> {
					continueFixtureBackupWarning(client);
					if (eligibleWorld(client)) {
						initialWorldTick = client.getSingleplayerServer().getTickCount();
						phase = Phase.WORLD;
					}
				}
				case WORLD_INPUT -> verifyWorldInput(client);
				case WRITE_WITNESS -> {
					if (witnessTask.isDone()) {
						if (!witnessTask.join()) {
							fail("world-witness-write-failed");
							return;
						}
						phase = Phase.SAVING;
						client.disconnectWithSavingScreen();
					}
				}
				case SAVING -> {
					if (client.level == null && client.getSingleplayerServer() == null) {
						if (!Files.isRegularFile(worldDirectory.resolve("level.dat"))) {
							fail("saved-level-dat-missing");
							return;
						}
						phase = Phase.RELOADING;
						client.createWorldOpenFlows().openWorld(WORLD, () -> fail("world-reopen-returned-to-menu"));
					}
				}
				case RELOADING -> {
					continueFixtureBackupWarning(client);
					if (eligibleWorld(client)) {
						reloadTick = client.getSingleplayerServer().getTickCount();
						witnessTask = new CompletableFuture<>();
						var server = client.getSingleplayerServer();
						server.execute(() -> {
							try {
								witnessTask.complete(server.overworld().getBlockState(witness).is(witnessBlock));
							} catch (Exception failure) {
								witnessTask.completeExceptionally(failure);
							}
						});
						phase = Phase.RELOADED;
					}
				}
				default -> { }
			}
		} catch (Exception failure) {
			fail("tick-" + failure.getClass().getSimpleName());
		} finally {
			advancing = false;
		}
	}

	private void continueFixtureBackupWarning(Minecraft client) {
		Screen screen = client.gui.screen();
		if (!(screen instanceof BackupConfirmScreen) || screen == submittedDialog
				|| frameSequence - dialogInputFrame < 3) {
			return;
		}
		dialogInputFrame = frameSequence;
		if (screen.getFocused() instanceof AbstractWidget widget && widget.active && widget.visible
				&& widget.getMessage().equals(BackupConfirmScreen.BACKUP_AND_JOIN)) {
			// This known dialog belongs to the marker-validated, fixed fixture world.
			// Keep the ordinary backup path and activate its button through SDL input.
			submittedDialog = screen;
			pushKey(client, SDLKeycode.SDLK_RETURN, SDLScancode.SDL_SCANCODE_RETURN, true);
			pushKey(client, SDLKeycode.SDLK_RETURN, SDLScancode.SDL_SCANCODE_RETURN, false);
			LOGGER.info("LOADONCE_PROTOTYPE_WORLD phase={} evidence=sdl-backup-and-join", phase);
		} else {
			pushKey(client, SDLKeycode.SDLK_TAB, SDLScancode.SDL_SCANCODE_TAB, true);
			pushKey(client, SDLKeycode.SDLK_TAB, SDLScancode.SDL_SCANCODE_TAB, false);
		}
	}

	private void openWorld(Minecraft client) {
		phase = Phase.LOADING;
		if (Files.isRegularFile(worldDirectory.resolve("level.dat"))) {
			client.createWorldOpenFlows().openWorld(WORLD, () -> fail("existing-world-load-returned-to-menu"));
		} else if (Files.exists(worldDirectory)) {
			fail("incomplete-prototype-world");
		} else {
			client.createWorldOpenFlows().createFreshLevel(WORLD,
					new LevelSettings(WORLD, GameType.CREATIVE,
							new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false),
							true, WorldDataConfiguration.DEFAULT),
					new WorldOptions(123456789L, false, false), provider -> provider.lookupOrThrow(Registries.WORLD_PRESET)
							.getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
					new TitleScreen());
		}
		LOGGER.info("LOADONCE_PROTOTYPE_WORLD phase=loading world={}", WORLD);
	}

	private void verifyWorldInput(Minecraft client) {
		if (!eligibleWorld(client)) {
			return;
		}
		int elapsedTicks = client.player.tickCount - inputTick;
		Vec3 current = client.player.position();
		double dx = current.x - inputPosition.x;
		double dz = current.z - inputPosition.z;
		if (elapsedTicks >= 10 && dx * dx + dz * dz > 0.01) {
			pushKey(client, SDLKeycode.SDLK_W, SDLScancode.SDL_SCANCODE_W, false);
			keyHeld = false;
			worldInputVerified = true;
			LOGGER.info("LOADONCE_PROTOTYPE_INPUT phase=world evidence=sdl-w-horizontal-movement ticks={}", elapsedTicks);
			witness = client.player.blockPosition().offset(2, 0, 2);
			witnessTask = new CompletableFuture<>();
			var server = client.getSingleplayerServer();
			server.execute(() -> {
				try {
					witnessBlock = server.overworld().getBlockState(witness).is(Blocks.GOLD_BLOCK)
							? Blocks.DIAMOND_BLOCK : Blocks.GOLD_BLOCK;
					server.overworld().setBlock(witness, witnessBlock.defaultBlockState(), 3, 512);
					witnessTask.complete(server.overworld().getBlockState(witness).is(witnessBlock));
				} catch (Exception failure) {
					witnessTask.completeExceptionally(failure);
				}
			});
			phase = Phase.WRITE_WITNESS;
		} else if (elapsedTicks > 100) {
			fail("sdl-world-movement-not-observed");
		}
	}

	private void readback(Minecraft client, Phase expected, String name) {
		Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> onReadback(client, expected, name, image));
	}

	private void onReadback(Minecraft client, Phase expected, String name, NativeImage image) {
		try (image) {
			assertOwner(client);
			if (outcome.get() != Outcome.RUNNING || phase != expected) {
				return;
			}
			int width = image.getWidth();
			int height = image.getHeight();
			if (width <= 0 || height <= 0) {
				fail("empty-" + name + "-framebuffer");
				return;
			}
			int first = image.getPixel(0, 0) & 0x00ffffff;
			boolean varied = false;
			long hash = 0xcbf29ce484222325L;
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					int pixel = image.getPixel(x, y);
					varied |= (pixel & 0x00ffffff) != first;
					hash = (hash ^ Integer.toUnsignedLong(pixel)) * 0x100000001b3L;
				}
			}
			if (!varied) {
				fail("uniform-" + name + "-framebuffer");
				return;
			}
			Path evidence = client.gameDirectory.toPath().resolve("loadonce-prototype-evidence");
			Files.createDirectories(evidence);
			image.writeToFile(evidence.resolve(name + "-" + System.getProperty("loadonce.prototype.launchId") + ".png"));
			LOGGER.info("LOADONCE_PROTOTYPE_RENDER phase={} framebuffer={}x{} pixelHash={}",
					name, width, height, Long.toUnsignedString(hash, 16));
			if (expected == Phase.MENU_READBACK) {
				if (client.gui.screen() != menuScreen || !eligibleMenu(client)) {
					fail("menu-changed-during-readback");
					return;
				}
				focusBefore = menuScreen.getFocused();
				phase = Phase.MENU_INPUT;
				pushKey(client, SDLKeycode.SDLK_TAB, SDLScancode.SDL_SCANCODE_TAB, true);
				pushKey(client, SDLKeycode.SDLK_TAB, SDLScancode.SDL_SCANCODE_TAB, false);
			} else if (expected == Phase.WORLD_READBACK) {
				if (!eligibleWorld(client)) {
					fail("world-changed-during-readback");
					return;
				}
				inputPosition = client.player.position();
				inputTick = client.player.tickCount;
				client.mouseHandler.grabMouse();
				pushKey(client, SDLKeycode.SDLK_W, SDLScancode.SDL_SCANCODE_W, true);
				keyHeld = true;
				phase = Phase.WORLD_INPUT;
			} else {
				if (!eligibleWorld(client) || !witnessTask.isDone() || !witnessTask.join()) {
					fail("saved-world-witness-missing");
					return;
				}
				if (outcome.compareAndSet(Outcome.RUNNING, Outcome.PASSED)) {
					deadline.shutdownNow();
					LOGGER.info("LOADONCE_PROTOTYPE_PASSED phase=world-reloaded menuFrames={} inputEvidence={}"
							+ " worldFrames={} reloadFrames={} reloadPassed=true persistedBlock=true elapsedMs={}",
							menuFrames, menuInputVerified && worldInputVerified, worldFrames, reloadedFrames, elapsedMillis());
				}
			}
		} catch (IOException failure) {
			fail("evidence-write-" + failure.getClass().getSimpleName());
		} catch (Exception failure) {
			fail("readback-" + failure.getClass().getSimpleName());
		}
	}

	private static void pushKey(Minecraft client, int key, int scancode, boolean down) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			SDL_Event event = SDL_Event.calloc(stack);
			event.key().type(down ? SDLEvents.SDL_EVENT_KEY_DOWN : SDLEvents.SDL_EVENT_KEY_UP)
					.windowID(SDLVideo.SDL_GetWindowID(client.getWindow().handle()))
					.key(key).scancode(scancode).down(down).repeat(false);
			if (!SDLEvents.SDL_PushEvent(event)) {
				throw new IllegalStateException("SDL key event rejected");
			}
		}
	}

	private void stop(Minecraft client) {
		if (keyHeld) {
			keyHeld = false;
			try {
				pushKey(client, SDLKeycode.SDLK_W, SDLScancode.SDL_SCANCODE_W, false);
			} catch (Exception failure) {
				LOGGER.warn("LOADONCE_PROTOTYPE_INPUT phase=stopping evidence=key-release-rejected");
			}
		}
		client.stop();
	}

	private void fail(String reason) {
		if (outcome.compareAndSet(Outcome.RUNNING, Outcome.FAILED)) {
			deadline.shutdownNow();
			LOGGER.error("LOADONCE_PROTOTYPE_FAILED phase={} reason={} menuFrames={} worldFrames={} elapsedMs={} {}",
					phase, reason, menuFrames, worldFrames, elapsedMillis(), lastState);
		}
	}

	private long elapsedMillis() {
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
	}

	private static boolean eligibleMenu(Minecraft client) {
		if (!client.isGameLoadFinished() || !(client.gui.screen() instanceof TitleScreen)
				|| client.gui.overlay() != null || client.level != null || client.player != null
				|| client.hasSingleplayerServer() || client.getWindow().isIconified()) {
			return false;
		}
		for (var child : client.gui.screen().children()) {
			if (child instanceof AbstractWidget widget && widget.active && widget.visible && widget.getAlpha() >= 1.0F) {
				return true;
			}
		}
		return false;
	}

	private static boolean eligibleWorld(Minecraft client) {
		return client.level != null && client.player != null && client.getSingleplayerServer() != null
				&& client.gui.screen() == null && client.gui.overlay() == null && !client.getWindow().isIconified();
	}

	private static void assertOwner(Minecraft client) {
		RenderSystem.assertOnRenderThread();
		if (Thread.currentThread() != client.getRunningThread()) {
			throw new IllegalStateException("LoadOnce prototype requires the client/render owner thread");
		}
	}
}
