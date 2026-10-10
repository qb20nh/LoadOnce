# LoadOnce

LoadOnce prototypes JVM initialization reuse and native resource reconstruction. Prism, Modrinth, CurseForge and the vanilla launcher can select a private `java`/`javaw` executable that starts a Java supervisor, applies startup policy, then runs their original game command with the LoadOnce agent. The Fabric 26.3 mod provides game hooks. Normal launches require no manually invoked JAR launcher and no Python.

The supervisor supplies startup flags before the game JVM starts; mod hooks own game resource integration. The agent protects both JVMs from premature checkpoints. An opt-in Linux prototype checkpoints a JVM before Fabric, mods or game code execute, then initializes the game and its native resources freshly on cold and restored launches. It does not snapshot an initialized menu or implement Minecraft SDL restoration.

The Fabric mod ID is `loadonce`, its display name is `LoadOnce`, and its Maven group is `com.qb20nh.loadonce`. For local continuation, see the [development handover](docs/HANDOVER.md).

## External launchers

Put `loadonce-1.0.0.jar` and Fabric API in the instance's mods directory, then add this JVM argument in your launcher:

```text
-javaagent:/absolute/path/loadonce-1.0.0-agent.jar
```

The agent runs in that launcher's JVM, preserves its main class and authentication flow, and needs no extra Java process. An unmodified vanilla profile can load the agent; the game hooks require a Fabric profile matching Minecraft 26.3. The current agent denies CRaC checkpoints because the game resource/session boundary is not implemented. It does not automatically train, select or restore caches.

For executable integration, build the native wrapper for your platform:

```sh
./gradlew redirectorDistribution redirectorSmoke supervisorAotSmoke
java -cp build/libs/loadonce-1.0.0-agent.jar com.qb20nh.loadonce.agent.RuntimeRedirectInstaller \
  --java-home /path/to/real/jdk --redirectors-dir build/native-redirectors \
  --agent-jar build/libs/loadonce-1.0.0-agent.jar \
  --supervisor-jar build/libs/loadonce-1.0.0-launcher.jar --output-dir /path/to/new/loadonce-runtime
```

Select the new runtime's `bin/java` on Linux or `bin/java.exe`/`bin/javaw.exe` on Windows. The installer leaves the real JDK intact. The native executable owns the Java supervisor and its game process; cache policy lives in Java. Anonymous pipes carry the original arguments and environment in memory, while standard streams remain inherited. Default `auto` uses a validated existing AOT cache and otherwise starts normally; training is explicit. See [external-launcher setup and limits](docs/external-launchers.md).

When upgrading from the template artifact names, remove the previous mod JAR before adding `loadonce-1.0.0.jar`. Update manual agent paths, or reinstall the private runtime into a new directory and select its executable; installed runtime paths are absolute.

## Build and Java checks

Use JDK 25 to build the Minecraft target and run the native tests:

```sh
./gradlew build
./gradlew leydenSmoke
```

`build` compiles both mod source sets, runs lifecycle/launcher/agent contracts, and creates four artifacts under `build/libs/`:

- `loadonce-1.0.0.jar`: Fabric mod with opt-in client observation hooks.
- `loadonce-1.0.0-runtime.jar`: checkpoint lifecycle library without Minecraft/Fabric dependencies.
- `loadonce-1.0.0-agent.jar`: Java 17 premain agent and one-time redirector installer.
- `loadonce-1.0.0-launcher.jar`: executable Java 17 launcher and lifecycle library, with no third-party dependencies.

In the prepared cloud workspace, use `source /workspace/.loadonce/setup/activate.sh` to select the retained JDK and Gradle cache. The supervisor targets Java 17; Leyden training requires a supporting JVM, and CRaC requires a CRaC-enabled JVM. The current cache/image privacy implementation requires POSIX ownership and permissions. Windows wrappers use ordinary startup until cache ACL validation exists.

## Optional standalone launcher and Leyden

Probe the selected JVM rather than assuming its version provides CRaC or Leyden:

```sh
java -jar build/libs/loadonce-1.0.0-launcher.jar doctor --java /path/to/jdk/bin/java
```

`doctor` also checks CRaC core classes and can run `criu check` with `--criu /path/to/criu`. Capability presence does not prove safe game checkpointing.

Train once explicitly, then wrap the same original Java command:

```sh
java -jar build/libs/loadonce-1.0.0-launcher.jar train --cache-dir build/aot-cache \
  --input mods --input config --input resourcepacks -- \
  /path/to/jdk/bin/java -cp application.jar your.MainClass
java -jar build/libs/loadonce-1.0.0-launcher.jar run --cache-dir build/aot-cache \
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

The Java harness verifies real callbacks, descriptor rejection, capture and three distinct process restores, retained heap/PID evidence, compatibility/corruption fallback and failed restore without replay. `-PloadonceCracEngine=simengine` checks callbacks and explicitly claims no image; `criuengine` requires suitable kernel capabilities. Warp passed on the recorded cloud host but crashed during restoration on the local Linux host; engine compatibility must be verified locally.

For an experimental CPU-only check with installed upstream CRIU and a compatible Azul CRaC JDK:

```sh
./gradlew criuNamespaceSmoke -PloadonceCracJava=/path/to/crac-jdk/bin/java \
  -PloadonceCriu=/usr/bin/criu
```

This opt-in task requires Linux `unshare`, `ip`, `setsid`, CRIU and enabled unprivileged user namespaces. It creates private user/PID/network/IPC/UTS/cgroup/mount namespaces, enables only their loopback, disables CDS and uses Azul's explicitly unsupported `upstream_criu=true` option. It verifies one substantial CPU snapshot and three separate namespace restores, retained heap state and exact lifecycle callbacks, with runtime/provider/image hashes and private evidence under `build/criu-namespace-validation/`. Restored namespace-visible PIDs may match the source; distinct host controllers prove separate launches. A failed restore stops the test without cold fallback or replay. This does not enable production or Minecraft image reuse.

For explicit CPU fixture capture/run:

```sh
realpath build/libs/*-runtime.jar > build/crac-fixture-classpath.txt
java -jar build/libs/loadonce-1.0.0-launcher.jar fixture-capture \
  --java /path/to/crac-jdk/bin/java --cache-dir build/fixture-cache \
  --classpath-file build/crac-fixture-classpath.txt
java -jar build/libs/loadonce-1.0.0-launcher.jar fixture-run \
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

On a Linux desktop, the same bounded harness can use the current GPU and audio server:

```sh
./gradlew nativeDisplaySmoke -PloadonceDesktop=true -PloadonceReconfigure=true
./gradlew nativeDisplaySmoke -PloadonceDesktop=true -PloadonceSystemAudio=true -PloadonceReconfigure=true
./gradlew nativeDisplaySmoke -PloadonceDesktop=true -PloadonceReconfigure=true -PloadonceInvalidShader=true
```

Run from the authorized desktop session with `DISPLAY` or `WAYLAND_DISPLAY`, its `XAUTHORITY` when required, and `XDG_RUNTIME_DIR`. Desktop mode needs no Xvfb and does not force software rendering. Only display endpoint variables are forwarded; system-audio mode additionally forwards PulseAudio/PipeWire endpoint and cookie-file references. HOME, temporary files and Mesa cache remain private, and inherited JVM/native injection is excluded. Loopback remains the default; system audio opens the default device and verifies source reconstruction but does not measure physical playback. These runs reconstruct resources in one process; they do not verify a checkpoint image or Minecraft's SDL/Vulkan resources.

To verify a native process image while keeping its authenticated display alive, build the Java harness classpath and launch it directly:

```sh
./gradlew writeLauncherTestClasspath writeNativeProbeClasspath
java -cp "$(cat build/launcher-test-classpath.txt)" com.qb20nh.loadonce.launcher.NativeDisplaySmoke \
  --xvfb /path/to/Xvfb --command /path/to/jdk/bin/java \
  -cp "$(cat build/launcher-test-classpath.txt)" com.qb20nh.loadonce.launcher.CracIntegrationSmoke \
  --java /path/to/crac-jdk/bin/java --native-classpath-file build/native-probe-classpath.txt \
  --native-reconfigure
```

For persistent native fixture capture/run, use that native classpath plus `--native --reconfigure` and repeated `--input` provider paths. Keep the same display/authentication alive for all launches; changing Xauthority selects cold startup. Software rendering and explicit null hardware audio avoid OpenAL's unused library-global PulseAudio dispatcher while loopback still renders real PCM. Production PulseAudio shutdown and hardware GPU/audio process-image restoration need separate work.

## Minecraft rendered-menu diagnostic

The Fabric diagnostic is disabled by default. On a configured display:

```sh
./gradlew runClient -PloadonceMenuProbe=true -PloadonceMenuScreenshot=true \
  -PloadonceMenuGameDir=build/menu-probe/game -PloadonceMenuTimeout=120
```

It requires a loaded `TitleScreen`, opaque active widgets, no overlay/world/connection, three extracted and successfully presented frames, and completed nonuniform GPU readback. Optional PNGs go inside the selected game directory's `loadonce-menu-probe/`. A fresh test directory needs the ordinary `onboardAccessibility:false` option to skip first-run onboarding.

Require `LOADONCE_MENU_PROBE_PASSED` and no `LOADONCE_MENU_PROBE_FAILED`; failure can also stop the game cleanly with exit 0. Keep an outer process deadline for native hangs. The selected software Vulkan client reached the rendered menu. Human input, authentication readiness, physical audio and SDL restoration remain unverified; no Minecraft checkpoint was taken.

## Linux Minecraft checkpoint prototype

The separate `MinecraftPrototype` controller caches only the pre-application JVM. `MinecraftPrototypeBootstrap` does not read the current launch request until after restoration; Fabric, all mods, graphics, audio, input and world loading then start normally. Initialized menus and game assets are not cached. This boundary avoids per-mod checkpoint hooks, but arbitrary modpacks are not yet verified and mod bugs can still occur. The ordinary runtime agent still vetoes checkpoints, and the prototype installs a game checkpoint veto before invoking Fabric.

Use a compatible local CRaC JDK and external CRIU on an authorized Linux desktop:

```sh
./gradlew minecraftPrototypeTrain -PloadonceCracJava=/path/to/crac-jdk/bin/java -PloadonceCriu=/usr/bin/criu
./gradlew minecraftPrototypeCold -PloadonceCracJava=/path/to/crac-jdk/bin/java -PloadoncePrototypeVerify=true
./gradlew minecraftPrototypeWarm -PloadonceCracJava=/path/to/crac-jdk/bin/java -PloadoncePrototypeVerify=true
./gradlew minecraftPrototypeRun -PloadonceCracJava=/path/to/crac-jdk/bin/java
./gradlew minecraftPrototypeSmoke -PloadonceCracJava=/path/to/crac-jdk/bin/java -PloadonceCriu=/usr/bin/criu
```

`Train` captures without starting Minecraft. `Cold` starts without an image; `Warm` requires a valid image. `Run` selects a valid image or one cold launch during preflight. `Smoke` verifies one cold launch, captures an image and verifies three separate warm launches. Verification exercises menu input and a dedicated test world's create/load/save/reopen flow; require `LOADONCE_PROTOTYPE_PASSED` and the final controller markers, not exit 0 alone. Without verification, the client remains available for manual use.

An optional separate mod JAR demonstrates fresh mod initialization. Prepare the default dedicated profile, copy the fixture into its mods directory, then run smoke:

```sh
./gradlew minecraftPrototypeFixtureMod writeMinecraftPrototypeSpec
cp build/minecraft-prototype/loadonce-additional-mod-fixture.jar build/minecraft-prototype/game/mods/
./gradlew minecraftPrototypeSmoke -PloadonceCracJava=/path/to/crac-jdk/bin/java -PloadonceCriu=/usr/bin/criu
```

On the recorded local NVIDIA/system-audio host, one cold launch and three warm launches passed menu focus/input, world movement, block save and reopen checks with that fixture. Each boot initialized the mod once with a new UUID. See the [run evidence and limits](docs/prototype-status.md#linux-minecraft-early-jvm-prototype).

The default CRaC executable is `build/local-runtime/zulu25.36.205-ca-crac-jdk25.0.4.1-linux_x64/bin/java`; override it with `loadonceCracJava`. `loadonceCriu`, `loadoncePrototypeGameDir`, `loadoncePrototypeCache` and `loadoncePrototypeTimeout` override the provider, dedicated profile, private cache and 600-second child deadline. The profile defaults to `build/minecraft-prototype/game` and must be fresh or already carry the private `.loadonce-prototype` marker. The generated launch uses an offline test identity, no user account, private HOME/temp directories and private user/PID/network/IPC/UTS/cgroup/mount namespaces with only loopback. It requires Linux `unshare`, `ip`, `setsid`, CRIU and enabled unprivileged user namespaces. Azul's external `upstream_criu=true` integration is unsupported and experimental.

Image validation covers the CRaC runtime, CRIU, controller, kernel/namespace mapping, launch request, classpath/dependencies, mods and display/session endpoint environment including Xauthority bytes. Changed launch/session arguments or those inputs invalidate the image. Current settings and world data are read by the newly initialized game; they are not retained game state. Images and logs remain owner-only under the selected cache. A restore failure after the attempt starts is final and never replays Minecraft. This is an isolated development workflow, not automatic external-launcher integration, an initialized-game image or a demonstrated startup speedup.

See [validation and continuation notes](docs/prototype-status.md) for recorded host evidence and remaining limits.

## License

CC0-1.0, inherited from the Fabric template.
