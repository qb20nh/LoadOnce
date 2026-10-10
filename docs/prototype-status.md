# Prototype validation and continuation

Recorded on 2026-10-10. Work continues on `main` only, from `ff737b4` and the earlier `codex/crac-leyden-native-restoration` prototype. Sources are in `/workspace/LoadOnce`; prepared toolchains are under `/workspace/.loadonce`. Live displays and processes do not survive an environment restart.

## Java launcher and harnesses

The launcher, image supervisor, contract tests, Leyden smoke, CRaC smoke and authenticated display harness now use Java. The former scripting tools and their CI steps have been removed. `build` packages an executable Java 17 launcher JAR, the standalone checkpoint runtime JAR and the Fabric mod JAR. The launcher has no third-party dependencies; native probes use LWJGL and system graphics/audio providers.

The launcher selects startup options before a child JVM starts. Existing mod hooks manage resources inside the game. There is no global replacement of `java`/`javaw`, and no new attach agent: attaching after startup cannot apply Leyden or CRaC startup flags retroactively. Windows/javaw cache behavior remains unverified. POSIX cache ownership/permission checks fail closed to ordinary startup on unsupported platforms.

AOT schema 3 and fixture schema 2 use bounded, strict `manifest.properties` files. Immutable private generations are published through an atomic completion marker only after successful training/capture, unchanged input fingerprints and sealed output. Earlier JSON caches choose cold startup. Runtime, classpath/transitive local manifest dependencies, explicit native paths and declared inputs enter content fingerprints. AOT additionally covers arguments, working directory, module paths and agents; fixture images cover the kernel, fixed fixture arguments, selected display/Xauthority and extracted LWJGL libraries.

Known credential environments and secret-bearing arguments disable caching or refuse training. JVM option injection, native preloads and argument files choose ordinary application startup. Custom credentials loaded from arbitrary files/environment variables require `--sensitive` and host integration; there is no universal credential discovery. Metadata contains digests rather than original command text.

Failed preflight chooses one conventional launch before application startup. A warm child failure, timeout or missing restored-state evidence is returned without replay. Failed training/capture preserves a previous complete generation. Ordinary application completion preserves deliberately launched descendants. Isolated native/CRaC fixtures instead own their Linux process groups, including orphaned helpers and nested isolated engine groups; Java invokes trusted `setsid`/`kill` utilities through exact argument lists, without a shell interpreter.

Repeat the Java checks with the prepared JDK 25:

```sh
source /workspace/.loadonce/setup/activate.sh
cd /workspace/LoadOnce
./gradlew --no-daemon --console=plain --max-workers=2 build leydenSmoke
```

The final build passed 718 lifecycle checks and 454 Java launcher checks: 28 file/process, 203 AOT and 223 fixture checks. They cover lifecycle ordering/rollback, private cache publication, content changes, symlinks/hardlinks, argument/environment boundaries, nested cancellation, fallback and no replay. Fake JVM/engine contract children are Java programs; their image files do not establish actual runtime capabilities. The real runtime checks below provide separate evidence. The ordinary JUnit `test` task still has no sources.

## Actual Leyden

The Java integration harness uses Oracle JDK 25.0.2 to train and map a real AOT cache. It checks exact application argument boundaries, a nonzero warm application exit without replay, failed training preserving the earlier archive, preflight corruption selecting cold startup, and credential-environment refusal before application main executes. A corrupted archive with a matching supervisor checksum reaches HotSpot, which rejects it and executes the application once using `AOTMode=auto` in the same JVM.

Leyden here reuses class/linking data. Startup speed, memory savings, warmed JIT machine code, lazy paging and native compilation have not been measured or implemented. Nonempty development classpath directories cannot be trained by the selected JDK; package classes into JARs.

## Actual CRaC images

Verified Azul CRaC JDK 25.0.4.1, build `Zulu25.36+205-CRaC-CA`, is retained at:

```text
/workspace/.loadonce/prototype/runtime/zulu25.36.205-ca-crac-jdk25.0.4.1-linux_x64
```

The archive was checked against official metadata before extraction:

- Package UUID: `5ca0f008-c0a5-45f0-8694-9919d3cc355d`.
- Metadata: <https://api.azul.com/metadata/v1/zulu/packages/5ca0f008-c0a5-45f0-8694-9919d3cc355d/>.
- SHA-256: `6d1beee9795c544f53ff7e665d6455081960d18b476c0f6054f5e35cef97b61e`.

The Java CRaC driver captures an actual Warp image and restores that image into three distinct JVMs. A retained random heap witness, value `42`, one preparation/restoration callback, coordinator `READY` and changed kernel PID distinguish restoration from ordinary fixture startup. Actual gate denial, preparation recovery, failed restoration and unregistered-descriptor rejection are also checked. The rejected checkpoint leaves an empty image directory and recovers to `READY` on its owner thread.

Repeat the CPU integration:

```sh
./gradlew cracIntegrationSmoke \
  -PloadonceCracJava=/workspace/.loadonce/prototype/runtime/zulu25.36.205-ca-crac-jdk25.0.4.1-linux_x64/bin/java
```

The supervisor checks corrupt manifests, changed/missing declared inputs and changed image bytes before selecting one cold fixture launch. A deliberately damaged ELF image header with a recomputed payload checksum passes preflight, reaches the real engine and fails restoration without a cold retry. The harness restores its original image and completion marker after this test. Every child is bounded to at most 60 seconds. `simengine` is explicitly callback-only simulation.

The native fixture captures a real changed-setting Warp image while its authenticated display stays alive. Three restores resume at cycle 1 without running initial cycle 0, then pass GLFW window/timer/callback checks, OpenGL buffer/texture readback and shader rendering, OpenAL state/cursor/PCM checks, and cleanup. This establishes process-image restoration with native reconstruction on the selected software stack. It does not establish a Minecraft or hardware-device image.

The final Java native run passed in `build/crac-validation/run-13524768964031322224/`: source PID 44460 restored into 44573, 44640 and 44722; image bytes totaled 68,386,820. All four preflight fallback cases and real failed restoration without replay also passed. The executable launcher JAR separately captured/restored the packaged runtime JAR in `build/java-cli-fixture/` (44547 → 44691).

Repeat with the Java display harness:

```sh
./gradlew writeLauncherTestClasspath writeNativeProbeClasspath
java -cp "$(cat build/launcher-test-classpath.txt)" dev.loadonce.launcher.NativeDisplaySmoke \
  --xvfb /workspace/.loadonce/prototype/native-host/root/usr/bin/Xvfb \
  --command /workspace/.loadonce/toolchains/jdk-25.0.2/bin/java \
  -cp "$(cat build/launcher-test-classpath.txt)" dev.loadonce.launcher.CracIntegrationSmoke \
  --java /workspace/.loadonce/prototype/runtime/zulu25.36.205-ca-crac-jdk25.0.4.1-linux_x64/bin/java \
  --native-classpath-file build/native-probe-classpath.txt --native-reconfigure
```

The final Java CPU run passed in `build/crac-validation/run-10689369794992280807/`: source PID 43299 restored into 43376, 43451 and 43544; image bytes totaled 30,707,716. All four preflight fallback cases and a real failed restore without replay passed.

Private images/logs live under ignored `build/crac-validation/`. They are never uploaded with source/build artifacts. Fixture children exclude inherited tokens, injected JVM options and native preloads. Native children additionally use the temporary display/authentication, private XDG/Mesa directories, software rendering and an explicit null hardware audio backend. The X11 cookie belongs only to that test display.

External provider discovery remains explicit: supply provider libraries/configuration using `--input`. The launcher does not discover every dynamically loaded host library. Fresh Xauthority or changed display state selects cold startup; restoring a native image requires a compatible live display. Warp logs a nonfatal attempt to write read-only `/proc/self/comm`; successful engine markers and application evidence are required despite that message.

## Native settings and the PulseAudio boundary

Java display tests use LWJGL 3.4.3, Xvfb, Mesa llvmpipe 25.0.7 and OpenAL Soft. Each generation renders 2,048 mono float PCM samples and compares them with the reference within `1e-6`; the observed reference hash is `-750305279`. The selected OpenAL build lacks `SOFT_buffer_samples`, so actual loopback rendering supplies PCM evidence rather than direct audio-buffer readback.

Audio quiescence pauses before sampling its cursor. Reconstruction retains GLFW time and actual settings: window size/position/resizability/cursor/sticky input/lock modifiers, and source gain/looping/cursor/playing/paused/stopped state. Every fifth changed-setting cycle reconstructs a stopped source at cursor 0. Loopback cycle 9 exhausts a finite buffer from its last 512 frames and checks `STOPPED`/cursor 0. Playing output must have energy; paused/stopped output must be silent. Restored source state is primed silently before saved gain/cursor are applied.

The native probe checks deletion and detached contexts through provider APIs. An injected invalid shader must stop the coordinator, emit the expected failure and release every tracked resource. The Java display harness checks these markers, bounds display startup/execution and reaps its owned server and command on cancellation.

Final Gradle checks passed ten default cycles, ten changed-setting cycles including finite-buffer exhaustion, and the expected invalid-shader failure with `STOPPED` and zero tracked handles. A separate Java harness check passed twenty changed-setting cycles and verified that SIGTERM reaped its recorded Xvfb and command processes. The launcher/runtime JARs contain Java 17 bytecode; the launcher's only JDK module dependencies are `java.base` and `jdk.security.auth`.

```sh
./gradlew nativeDisplaySmoke -PloadonceXvfb=/workspace/.loadonce/prototype/native-host/root/usr/bin/Xvfb
./gradlew nativeDisplaySmoke -PloadonceXvfb=/workspace/.loadonce/prototype/native-host/root/usr/bin/Xvfb -PloadonceReconfigure=true
./gradlew nativeDisplaySmoke -PloadonceXvfb=/workspace/.loadonce/prototype/native-host/root/usr/bin/Xvfb -PloadonceReconfigure=true -PloadonceInvalidShader=true
```

The first native Warp attempt in prior work rejected a residual pipe after tracked handles reached zero. `strace -k` attributed it to OpenAL's library-global PulseAudio mainloop; closing the loopback device/context did not destroy that backend. GLFW's wakeup pipe closed correctly. `ALSOFT_DRIVERS=null` prevents this unused physical backend while loopback still renders real PCM. A second trace and actual image restoration confirmed the pipe disappeared. No descriptor exemption or forced unknown-handle close was used. Shared native libraries remain loaded because unloading providers would invalidate cached function pointers.

Production PulseAudio shutdown/restoration, physical audio cursors, hardware GPUs/drivers, controllers and SDL ownership need separate integration. The retained Xvfb came from authenticated Debian trixie packages. Native traces remain under `/workspace/.loadonce/prototype/native-fd/`.

## Minecraft main menu: prior unchanged evidence

The opt-in Fabric diagnostic remains disabled by default and was not changed in this Java launcher migration. The actual Minecraft 26.3 client previously reached a rendered main menu under Xvfb and Mesa 25.0.7 lavapipe. It selected Vulkan/CPU after OpenGL could not find a matching GLX visual. Success required an opaque active `TitleScreen`, no overlay/world/connection, three extracted and presented frames, and completed nonuniform framebuffer readback.

The PASS marker recorded three frames, 854×480 pixels, pixel hash `bcd7fd7c66f9c0c2`, elapsed 7,611 ms and `Sounds: 0/247 + 1/8`. OpenAL used `No Output`. The PNG visibly contains the panorama, logo and main-menu buttons:

```text
/workspace/LoadOnce/build/menu-probe/final-game/loadonce-menu-probe/menu-15904.png
SHA-256: 03e8dac3bb9e60cede9734481dcd6e7d2c20feee1dade06f18c312cd42b3a974
```

A separate one-second deadline emitted `LOADONCE_MENU_PROBE_FAILED reason=timeout elapsedMs=1019 phase=initializing`, no PASS and no PNG. Both clients exited 0 through orderly shutdown, so a host must check markers and retain an outer deadline. Neither run created a world or checkpoint. Logs remain under `/workspace/.loadonce/prototype/client-host/logs/`, named `menu-positive-final-20261010.log` and `menu-timeout-final-20261010.log`.

The normal client explicitly chooses `Proxy.NO_PROXY`, while this environment provides HTTP CONNECT. Its discovery `UnknownHostException` was not an observed allowlist denial and did not prevent rendered-menu readiness. Authentication, human input, physical audio and SDL/Vulkan reconstruction remain unverified. The diagnostic reports `inputVerified=false restorationVerified=false checkpointTaken=false`. The independent GLFW fixture does not implement Minecraft's SDL renderer reconstruction.

## CRIU, CI and host limits

Bundled CRIU `3.17.1-crac` fails its kernel check/checkpoint here: read-only `/proc/loginuid`, socket operations rejected with `EPERM` and failed TCP-repair probing. Effective/bounding capabilities are zero, `NoNewPrivs=1`, and seccomp filtering is enabled. The CRIU engine terminates the JVM after failure; callbacks cannot recover a process that has exited. No successful CRIU restoration is claimed.

Only Warp receives `checkpoint_exit_code=0`. A CRIU capture exit is accepted only with substantial payload and explicit successful dump evidence; a passing integration still requires a separate verified restore. An HTTP host allowlist cannot supply kernel capabilities or devices. Existing network hosts suffice for the verified runtime/dependency downloads; no additions are needed for this continuation.

CI builds all three JARs and runs Java launcher contracts, actual Leyden, default/changed software native reconstruction and injected-failure cleanup. CRaC image checks require a verified CRaC runtime and remain explicit local integration checks. Prior `ff737b4` CI passed at <https://github.com/qb20nh/LoadOnce/actions/runs/38029398291>.

Before automatic game checkpointing, establish renderer/audio/native ownership, quiesce work on owner threads, exclude credential/world/session state, reconstruct native resources, reattach authorized sessions and prove an interactive menu. Servers must load current persistent world data. The earlier conventional dedicated-server readiness/status/ping smoke remains prior evidence; it was not rerun here.
