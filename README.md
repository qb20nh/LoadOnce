# LoadOnce

LoadOnce uses Java JARs to reuse JVM initialization and reconstruct external resources. It contains a standalone Java/javaw launcher, a loader-independent checkpoint lifecycle library, a Fabric 26.3 mod target, and real GLFW/OpenGL/OpenAL restoration probes. Application code, launcher tools and integration harnesses use Java; no Python or project shell helper is required.

The launcher selects Leyden or CRaC startup flags before starting the application. Mod hooks own the resource lifecycle inside the running game. An agent attached after startup cannot supply JVM startup flags retroactively. The current Minecraft integration observes conventional startup to a rendered main menu; reusable Minecraft images and SDL restoration remain unimplemented.

## Build and Java checks

Use JDK 25 to build the Minecraft target and run the native tests:

```sh
./gradlew build
./gradlew leydenSmoke
```

`build` compiles both mod source sets, runs the lifecycle and Java launcher contracts, and creates three artifacts under `build/libs/`:

- `modid-1.0.0.jar`: Fabric mod with opt-in client observation hooks.
- `modid-1.0.0-runtime.jar`: checkpoint lifecycle library without Minecraft/Fabric dependencies.
- `modid-1.0.0-launcher.jar`: executable Java 17 launcher and lifecycle library, with no third-party dependencies.

In the prepared cloud workspace, use `source /workspace/.loadonce/setup/activate.sh` to select the retained JDK and Gradle cache. The launcher's code targets Java 17; Leyden training requires a supporting JVM, and CRaC requires a CRaC-enabled JVM. The current cache/image privacy implementation requires POSIX ownership and permissions. Unsupported cache platforms select an ordinary application launch; Windows/javaw cache behavior has not been verified.

## Java/javaw launcher and Leyden

Probe the selected JVM rather than assuming its version provides CRaC or Leyden:

```sh
java -jar build/libs/modid-1.0.0-launcher.jar doctor --java /path/to/jdk/bin/java
```

`doctor` also checks CRaC core classes and can run `criu check` with `--criu /path/to/criu`. Capability presence does not prove safe game checkpointing.

Train once explicitly, then wrap the same original Java command:

```sh
java -jar build/libs/modid-1.0.0-launcher.jar train --cache-dir build/aot-cache \
  --input mods --input config --input resourcepacks -- \
  /path/to/jdk/bin/java -cp application.jar your.MainClass
java -jar build/libs/modid-1.0.0-launcher.jar run --cache-dir build/aot-cache \
  --input mods --input config --input resourcepacks -- \
  /path/to/jdk/bin/java -cp application.jar your.MainClass
```

The same launcher JAR can be started by `javaw`. It supplies JVM startup options to a child JVM; it does not replace the system Java executable or globally intercept unrelated launches. Original application argument boundaries and environment are preserved. Declare dynamically loaded mods/configuration/resources with repeated `--input`. Development classpath directories containing files cannot be trained by JDK 25 AOT; package those classes into JARs first.

Fingerprints cover the runtime, arguments, working directory, classpath and transitive local manifest dependencies, module paths, agents, explicit native paths and declared inputs. Private immutable generations and an atomic `manifest.properties` completion marker prevent partial training from replacing a working cache. Missing, changed, corrupt, busy or unsupported caches select ordinary startup before the application begins. A warm application failure is returned without replay; `AOTMode=auto` can discard incompatible data inside that single JVM.

Known credential environments, secret-bearing launch options and credential-bearing proxy URLs disable caching and reject training. Use `--sensitive` for custom credentials or secrets read from application files. Argument files, injected JVM options (including `JDK_AOT_VM_OPTIONS`) and native-library injection select ordinary startup. Metadata stores digests rather than command text. AOT schema 3 and fixture schema 2 invalidate the former prototype's JSON artifacts. Leyden reuses class/linking data; these tests do not claim a saved heap, warmed machine code or measured startup gains.

## Checkpoint lifecycle and isolated images

`CheckpointCoordinator` binds to its owner thread. Register dependencies first; preparation releases resources in reverse dependency order, restoration recreates them in dependency order, and producers resume after every restoration succeeds. A denied gate changes no resources. Failed preparation is compensated; failed compensation/restoration stops every participant and leaves the coordinator `STOPPED`.

The host's `CheckpointGate` must establish a reusable boundary excluding credentials, sessions, connections, persistent world state and unmanaged external resources. `CracBridge` registers with the actual optional `jdk.crac` API through reflection. Neither component supplies a checkpoint engine or makes arbitrary games safe to snapshot.

Run real isolated image checks with a verified CRaC JDK:

```sh
./gradlew cracIntegrationSmoke -PloadonceCracJava=/path/to/crac-jdk/bin/java
```

The Java harness verifies real callbacks, descriptor rejection, capture and three distinct process restores, retained heap/PID evidence, compatibility/corruption fallback and failed restore without replay. `-PloadonceCracEngine=simengine` checks callbacks and explicitly claims no image; `criuengine` requires suitable kernel capabilities. Warp has passed without elevated capabilities on the selected host.

For explicit CPU fixture capture/run:

```sh
realpath build/libs/*-runtime.jar > build/crac-fixture-classpath.txt
java -jar build/libs/modid-1.0.0-launcher.jar fixture-capture \
  --java /path/to/crac-jdk/bin/java --cache-dir build/fixture-cache \
  --classpath-file build/crac-fixture-classpath.txt
java -jar build/libs/modid-1.0.0-launcher.jar fixture-run \
  --java /path/to/crac-jdk/bin/java --cache-dir build/fixture-cache \
  --classpath-file build/crac-fixture-classpath.txt
```

These actions accept only `CracSmoke` or `NativeRestorationProbe`. A minimal child environment excludes inherited credentials and JVM/native injection. Image validation covers runtime/kernel, classpath dependencies, fixed fixture arguments, selected display/Xauthority, declared providers, image bytes and extracted native libraries. A failed preflight selects one cold fixture launch. After a restore child starts, failure or timeout never triggers another launch.

CRaC fixtures use Linux `setsid`/`kill` utilities through Java's `ProcessBuilder` to stop their entire process group on cancellation, including orphaned engine helpers. No shell command strings are used. Native providers outside the classpath require explicit `--input` entries; automatic host-library discovery is not implemented. Images, cookies and logs stay private and are never uploaded as build artifacts.

## GLFW, graphics and audio reconstruction

`NativeRestorationProbe` owns real LWJGL 3.4.3 resources and retains their CPU descriptors. It checks native deletion/context detachment, callbacks and resize delivery, GLFW settings/clock, OpenGL buffer/texture readback and rendered pixels, and OpenAL PCM/playback state. The changed-settings scenario includes gain/looping/cursor changes, playing/paused/stopped sources and finite-buffer exhaustion. In loopback mode, actual rendered PCM must match the reference within `1e-6`; paused/stopped sources must remain silent.

The Java display harness creates an authenticated temporary Xvfb display and bounds startup, child execution and cleanup. It requires native `Xvfb`, `xauth` and Mesa providers:

```sh
./gradlew nativeDisplaySmoke
./gradlew nativeDisplaySmoke -PloadonceReconfigure=true
./gradlew nativeDisplaySmoke -PloadonceReconfigure=true -PloadonceInvalidShader=true
```

All default runs use ten cycles and in-memory OpenAL loopback. The invalid-shader task succeeds only when the child fails with coordinator `STOPPED` and releases every tracked handle. Use `-PloadonceXvfb=/path/to/Xvfb` for an extracted binary. With an existing display, `./gradlew nativeRestorationProbe -PloadonceAudioLoopback=true` remains available.

To verify a native process image while keeping its authenticated display alive, build the Java harness classpath and launch it directly:

```sh
./gradlew writeLauncherTestClasspath writeNativeProbeClasspath
java -cp "$(cat build/launcher-test-classpath.txt)" dev.loadonce.launcher.NativeDisplaySmoke \
  --xvfb /path/to/Xvfb --command /path/to/jdk/bin/java \
  -cp "$(cat build/launcher-test-classpath.txt)" dev.loadonce.launcher.CracIntegrationSmoke \
  --java /path/to/crac-jdk/bin/java --native-classpath-file build/native-probe-classpath.txt \
  --native-reconfigure
```

For persistent native fixture capture/run, use that native classpath plus `--native --reconfigure` and repeated `--input` provider paths. Keep the same display/authentication alive for all launches; changing Xauthority selects cold startup. Software rendering and explicit null hardware audio avoid OpenAL's unused library-global PulseAudio dispatcher while loopback still renders real PCM. Production PulseAudio shutdown and hardware GPU/audio reconstruction need separate work.

## Minecraft rendered-menu diagnostic

The Fabric diagnostic is disabled by default. On a configured display:

```sh
./gradlew runClient -PloadonceMenuProbe=true -PloadonceMenuScreenshot=true \
  -PloadonceMenuGameDir=build/menu-probe/game -PloadonceMenuTimeout=120
```

It requires a loaded `TitleScreen`, opaque active widgets, no overlay/world/connection, three extracted and successfully presented frames, and completed nonuniform GPU readback. Optional PNGs go inside the selected game directory's `loadonce-menu-probe/`. A fresh test directory needs the ordinary `onboardAccessibility:false` option to skip first-run onboarding.

Require `LOADONCE_MENU_PROBE_PASSED` and no `LOADONCE_MENU_PROBE_FAILED`; failure can also stop the game cleanly with exit 0. Keep an outer process deadline for native hangs. The selected software Vulkan client reached the rendered menu. Human input, authentication readiness, physical audio and SDL restoration remain unverified; no Minecraft checkpoint was taken.

See [validation and continuation notes](docs/prototype-status.md) for recorded host evidence and remaining limits.

## License

CC0-1.0, inherited from the Fabric template.
