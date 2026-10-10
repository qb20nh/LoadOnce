# Prototype validation and continuation

Recorded on 2026-10-10. Work continues on `main` only, from `ff737b4` and the earlier `codex/crac-leyden-native-restoration` prototype. Sources are in `/workspace/LoadOnce`; prepared toolchains are under `/workspace/.loadonce`. Live displays and processes do not survive an environment restart.

The Fabric identity is now `loadonce` / `LoadOnce`, with Maven group `com.qb20nh.loadonce` and `loadonce-1.0.0` artifact names. The entrypoints are `com.qb20nh.loadonce.LoadOnceMod` and `com.qb20nh.loadonce.client.LoadOnceClient`; mixin resources and classes use the same project identity. Remove the previous mod artifact when upgrading, update direct agent paths, and reinstall private runtimes into new directories because installed artifact paths are absolute. See the [local development handover](HANDOVER.md).

The last verified supervisor baseline before this identity rename is commit `374308dcf38b6d25a25d81902d07449405f8e9d3`, with green [Linux/Windows CI](https://github.com/qb20nh/LoadOnce/actions/runs/38051901162). The cloud evidence below belongs to that baseline or the explicitly identified earlier runs; it does not by itself verify the identity rename. Use `git rev-parse HEAD` and the CI run for that commit when continuing locally.

## Local Linux continuation

Local work on 2026-10-10 started from `main` at `1d332e731116b2db0c275b2da9d722fbd961e5d1`, whose [hosted build](https://github.com/qb20nh/LoadOnce/actions/runs/38054428572) passed. The host is CachyOS Linux `7.2.9-1-cachyos`, glibc `2.44`, Arch OpenJDK `25.0.4.1`, NVIDIA RTX 3060 Ti with driver `615.71.09`, and PulseAudio on PipeWire `1.6.9`.

The local build passed lifecycle, Java launcher, packaged-agent and native executable contracts, real Leyden/agent/supervisor AOT training and reuse, and distribution packaging. The new opt-in `prismLauncherSmoke` passed against the installed Prism `11.0.3` JavaCheck/NewLaunch JARs: checker output/exit, real stdin bootstrap parameters/properties/cwd/environment, abort, cancellation of fixture/helper processes, and no cache publication under `train`. It runs a harmless fixture without accounts or game state; launcher GUI runtime selection remains untested.

`nativeDisplaySmoke -PloadonceDesktop=true` now uses an authorized local desktop with a narrow endpoint environment instead of forcing Xvfb/software rendering. Optional `-PloadonceSystemAudio=true` opens the current audio device. The new environment/probe contracts run without hardware in the launcher suite. Hardware testing exposed a real window-manager ordering bug: fixing nonresizable limits before an asynchronous resize completed locked the previous geometry. The fixture now permits resize, waits for exact geometry on its owner thread, then applies the requested limits. Ten NVIDIA changed-settings cycles passed, including loopback PCM equality; ten system-audio cycles and the injected invalid-shader STOPPED/cleanup scenario also passed. Physical playback, library-global PulseAudio descriptor shutdown and Minecraft SDL/Vulkan reconstruction are not established by those checks.

The authenticated software-display path also passed ten changed-settings cycles and the invalid-shader STOPPED/cleanup case using a checksum-verified Xvfb extracted under `build/local-tools/`; no system package installation was required. Final verification included 82 packaged-agent checks with a real CRaC veto, 718 runtime checks and the complete launcher suite, AOT/redirector/distribution tests and installed Prism smoke.

Minecraft `26.3` started through `build/linux-runtime/bin/java`, the Java supervisor and the Fabric agent on the actual NVIDIA OpenGL backend and onboard analog OpenAL device. The menu diagnostic passed three 854×480 frames with pixel hash `414aba83da061bbd`; its PNG is under `build/menu-probe/linux-local/game/loadonce-menu-probe/`. It reported `inputVerified=false restorationVerified=false checkpointTaken=false`. The separate development game directory contains no selected user account or world; ordinary development-token Realms authorization errors did not prevent the menu. This is physical-device cold-start evidence, not restored-game evidence.

An official checksum-verified Azul `Zulu25.36+205-CRaC-CA` JDK captured the isolated CPU fixture successfully. Independent Warp restoration exited `139` (SIGSEGV), with a system coredump pointing into native libc code and no useful engine diagnostic. A direct CPU capture/restore outside the LoadOnce supervisor also reproduced SIGSEGV; disabling rseq/CET CPU tunables did not resolve it. The underlying engine/host cause is not established, and no successful local Warp restoration is claimed. The integration failure now reports engine, mode, exit code and evidence path rather than relying on an empty log. Existing cloud restoration evidence remains host-specific.

CRIU `4.2.1` rejected the ordinary host capability check. Its unprivileged check passed after creating private user, PID, network, IPC, UTS and cgroup namespaces and enabling only their private loopback interface. Azul's bundled CRIU `3.17.1-crac` still failed mapped-file access on this host. The new opt-in `criuNamespaceSmoke`, with `-Xshare:off` and explicit external CRIU `4.2.1`, captured a 33,361,876-byte CPU image with `Dumping finished successfully`, then restored it in three separate namespace launches with the same heap witness/value `42`, coordinator `READY` and exactly one preparation/restoration callback. Namespace-visible PID `129` remained `129`, as expected for CRIU; distinct host controller processes and separate `unshare` launches establish independence. Runtime JDK, external CRIU, runtime JAR and sealed-image hashes remained stable, and retained evidence was owner-only. Restore logs contain a nonfatal `RLIMIT_NOFILE` permission warning. Azul labels `upstream_criu=true` unsupported, so this is experimental external-provider evidence, not production admission. No system capabilities or host kernel/network settings were changed. Stock JDK 25 lacks CRaC, so normal local launch uses AOT/ordinary startup with the unconditional agent checkpoint veto.

Local artifacts, runtime installation, private fixture images and game diagnostics are under ignored `build/` paths. Keep those paths stable while selecting an installed wrapper; a clean build removes them. These earlier checks establish no Minecraft restoration or startup performance improvement. The separate early-JVM prototype below uses a narrower checkpoint boundary.

## Linux Minecraft early-JVM prototype

The opt-in `MinecraftPrototype` controller captures a JVM before Fabric, mods or Minecraft code execute. `MinecraftPrototypeBootstrap` reads the private current launch request only after successful restoration, installs a game checkpoint veto and invokes the configured Fabric development launcher once. Cold and warm paths initialize every game/native resource freshly and load current settings/world data. Initialized menus and game assets are not cached. This avoids per-mod snapshot hooks by excluding initialized application state from the image. Arbitrary modpacks, immunity to mod bugs, an initialized-menu image, SDL reconstruction and startup gains are not established. The ordinary external-launcher runtime agent still vetoes checkpoints.

The local smoke completed at 23:38 KST on 2026-10-10 with one cold launch, capture and three warm launches. The sealed image totaled 51,057,057 bytes and retained heap UUID `54dba1ef-5c03-4464-b7cf-09985c40d52c`, namespace PID `128` and callbacks `1/1` across three distinct host controllers. The separate `loadonce-additional-mod-fixture.jar` was installed as an actual mod; each cold/warm boot initialized it exactly once with a fresh UUID. This verifies fresh mod initialization for that fixture, not all modpacks.

All four game boots used OpenGL 3.3 on NVIDIA driver `615.71.09` and the system analog OpenAL device. Each passed three presented menu frames and SDL Tab focus, presented world frames with advancing server ticks, SDL W horizontal movement, alternating gold/diamond block writes through the server, saved `level.dat`, and a reopened world with the persisted block, presented frames and framebuffer readback before clean exit. Warm game PASS markers reported `reloadPassed=true persistedBlock=true`; screenshots of the menu, world and reopened world use a unique launch UUID so namespace PID reuse cannot overwrite earlier evidence.

The cold log is `build/minecraft-prototype/cache/.cold-11730108145581299328/cold-game.log`. Warm logs are private under `build/minecraft-prototype/cache/.capture-8170016446276415405/`, named `warm-2228570-game.log`, `warm-2229061-game.log` and `warm-2229520-game.log`. Historical CPU/native and ordinary-wrapper evidence above remains separate.

After pinning the namespace's launch spec and correcting rejected-profile permission handling, `build minecraftPrototypeSmoke` passed again with 178 new real-child bootstrap checks and one cold plus three warm game boots. That 49,582,451-byte image and four accepted warm logs (including a later integrity recovery check) remain under `.capture-11343816741948298771`. Changing the actual fixture mod's version to `1.0.1` made explicit warm preflight fail before creating a workspace; `Run` then performed exactly one cold boot with full world persistence evidence. Corrupting an image inventory byte also failed admission before launch; restoring the original bytes allowed another verified warm boot. The earlier experimental fixture world passed SDL backup-and-join handling on both load and reopen. Existing Leyden, agent AOT, redirector and supervisor AOT checks passed; the optional Warp engine still exits 139 on this host as recorded above. These are local results; no new hosted CI result is claimed here.

The default `build/minecraft-prototype/game` profile also passed one cold and three warm boots at 23:47 KST. Its currently published 51,261,845-byte image is `.capture-9530744996460602764`, with heap UUID `37d67731-2871-4059-8964-5c6ed2877e80` and warm logs `warm-2252789-game.log`, `warm-2253222-game.log`, `warm-2253698-game.log`. This leaves the default development command ready to select a validated image under the same runtime/session inputs.

Tasks `minecraftPrototypeTrain`, `minecraftPrototypeCold`, `minecraftPrototypeWarm`, `minecraftPrototypeRun` and `minecraftPrototypeSmoke` are explicit Linux development workflows. Training captures only the pre-application bootstrap; cold starts without an image; warm requires a validated image; run selects one cold launch only when image preflight fails. Smoke requires one verified cold launch, capture and three separate verified warm launches, including menu input and dedicated-world create/load/save/reopen evidence. `loadoncePrototypeVerify=true` enables the same game probe for individual cold/warm/run commands; without it, those clients remain open for manual use. Once a restore attempt starts, an engine/game failure or timeout never causes a cold retry.

```sh
./gradlew minecraftPrototypeFixtureMod writeMinecraftPrototypeSpec
cp build/minecraft-prototype/loadonce-additional-mod-fixture.jar build/minecraft-prototype/game/mods/
./gradlew minecraftPrototypeSmoke \
  -PloadonceCracJava=/absolute/path/to/crac-jdk/bin/java \
  -PloadonceCriu=/usr/bin/criu
```

`loadonceCracJava` defaults to the retained local JDK at `build/local-runtime/zulu25.36.205-ca-crac-jdk25.0.4.1-linux_x64/bin/java`; configure it for another compatible installation. `loadoncePrototypeGameDir`, `loadoncePrototypeCache` and `loadoncePrototypeTimeout` select the marked dedicated profile, owner-only cache/evidence and child deadline (600 seconds by default). The generated launch uses an offline test identity and rejects an unmarked existing personal profile. Default profile/cache paths are `build/minecraft-prototype/game` and `build/minecraft-prototype/cache`.

This path uses private Linux user/PID/network/IPC/UTS/cgroup/mount namespaces with only loopback, private HOME/temp directories, CRaC with CDS disabled and external CRIU. It requires `unshare`, `ip`, `setsid` and enabled unprivileged user namespaces. Azul's `upstream_criu=true` integration is unsupported and experimental. No authenticated launcher session is restored or connected through this offline workflow.

Image fingerprints cover the runtime, external CRIU, controller, kernel and namespace mapping, launch request including session arguments, classpath/dependencies, mod files and display/session endpoint environment including Xauthority bytes. Changes invalidate the image before restore. Current game settings and persistent worlds are read afresh rather than cached in the heap. The namespace controller waits for explicit CRIU dump completion before exiting; capture publication requires substantial image bytes, unchanged inputs and owner-only sealed payload. After restoration, the bootstrap restores the retained `java.class.path` property before fresh Fabric discovery because CRaC otherwise applies the restore command's default property. Warm acceptance requires the retained heap witness, expected namespace PID/callbacks, a distinct host controller and game verification markers. Images/logs stay private under ignored build paths and are not build artifacts.

## Initialized-state continuation

The stronger initialized-game/mod continuation contract and its external-CRIU/KVM experiments are recorded separately in [initialized-state prototype status](transparent-state-prototype.md). The early-JVM evidence above verifies fresh game initialization after restoring its bootstrap.

## External launchers and redirected Java executables

The external-launcher integration now runs a Java 17 supervisor behind private Linux `java` and Windows `java.exe`/`javaw.exe` executables. Prism, Modrinth, CurseForge and vanilla select that executable; normal launches need no manually invoked JAR launcher. The installed supervisor uses the launcher artifact internally, with all startup/cache policy in Java. A direct premain-only configuration remains available. See [setup and boundaries](external-launchers.md).

The agent installs a strongly retained CRaC veto and exposes only fixed capability identifiers. It has no class transformers, invokes no game main and creates no child JVM. The Fabric initializer resolves its system-loader endpoint explicitly; the agent namespace is excluded from Fabric's target classes and mod JAR. An isolated test against actual Fabric 0.19.5 showed ordinary Knot loading duplicates classes/static state while explicit system-loader reflection reaches the original endpoint.

Native code owns transport and process lifetime. Original argv/environment travel only through bounded anonymous pipes; application stdin/stdout/stderr and cwd remain inherited. Bootstrap environment filtering prevents JVM/native injection from applying twice. Linux uses a guardian, dedicated worker session and subreapers; Windows uses explicit inherited handles and atomic kill-on-close job assignment. A completion packet releases intentional helpers after ordinary exit, including exit 7. Without it, native/controller crashes clean up the owned tree. The Linux scanner reads complete `/proc/PID/stat` records, including task names containing newlines, and works without the kernel's optional task/children file. Backend JDKs remain unchanged.

Windows tests distinguish the complete native→supervisor→backend wide-character boundary from the selected JVM's native argument encoding. OpenJDK 25 can replace non-ANSI arguments with `?`; setup requires its controller launch paths to round-trip through `native.encoding`. A test-only native witness uses a separate real bootstrap JDK to record arguments before Java conversion and is excluded from release ZIPs. Production and test subprocesses encode literal CRT command tokens explicitly. Windows cache ownership still fails closed to ordinary startup; ACL validation is not implemented. Graceful console-hook evidence is currently Linux-only; forced Windows native/controller cleanup is exercised by hosted CI.

The supervisor now selects AOT flags before backend startup. Default auto only reads a validated existing cache; an absent cache starts the backend directly without creating a cache or probing another JVM. Explicit train may fall cold before a child attempt, but application/publication failures never replay main. Credential-bearing commands/environments, checker/version applications, argument files and opaque Prism/MultiMC stdin entrypoints bypass caching. Original manual AOT flags select ordinary forwarding. Environment/argument-file LoadOnce agent injection must be removed to avoid duplicate premain; those sources are not rewritten. The agent vetoes checkpoints in both controller and game JVMs. Authentication/session and native ownership reconstruction remain prerequisites for Minecraft image reuse.

The Linux build passed 718 lifecycle checks, 454 launcher checks and 82 packaged-agent checks, including three real CRaC simengine vetoes. The executable supervisor passed 313 contracts: exact arguments/properties, checker JARs, Unicode paths, process ancestry, environment/cwd, 256KiB+ raw binary stdin/stdout/stderr, probe parity, JVM environment options once, ordinary helper survival, forced frontend/controller cleanup and graceful TERM hooks. Real supervisor AOT checks passed linked-cache mapping, exit 7, unsuccessful training preserving a prior cache, post-main input mutation refusal without replay, outer integrity and HotSpot bad-magic fallback, credentials/Prism bypass and binary stdin preservation. Windows MSVC/runtime checks run on every push alongside these Linux checks.

An actual Minecraft 26.3 development client started through the installed Linux native executable and Java supervisor. It logged the agent through Fabric's system-loader bridge and passed three rendered Vulkan/CPU menu frames at 854×480, pixel hash `c57a1389e45b30`, elapsed 11,171 ms. Logs, runtime and PNG are under `build/menu-probe/agent-redirect-1930547683695446215/`. It reported `inputVerified=false restorationVerified=false checkpointTaken=false`; no game checkpoint was taken. This exercises an external-style Java executable selection, not the Prism/Modrinth/CurseForge applications themselves, and makes no startup-speed claim.

Windows supervisor verification passed with MSVC 19.51 and `/W4 /WX`: 489 native java/javaw checks, 69 packaged-agent checks, direct real AOT mapping, private-cache refusal without replay, and binary Prism stdin preservation. The [hosted Windows job](https://github.com/qb20nh/LoadOnce/actions/runs/38051441397/job/114211214876) verifies the complete native→Java supervisor→backend path. Linux additionally compiles with `_FORTIFY_SOURCE=3` to check hardened libc return-value diagnostics.

## Java launcher and harnesses

The launcher, image supervisor, contract tests, Leyden smoke, CRaC smoke and authenticated display harness use Java. The former scripting tools and their CI steps have been removed. `build` packages the premain agent JAR, executable Java 17 launcher JAR, standalone checkpoint runtime JAR and Fabric mod JAR. The agent/launcher have no third-party dependencies; native probes use LWJGL and system graphics/audio providers.

The same Java startup policy serves the optional standalone CLI and the executable supervisor. Existing mod hooks manage resources inside the game. Premain runs after startup-option parsing, so the supervisor selects Leyden flags before creating the game JVM. POSIX ownership/permission checks fail closed to ordinary startup on Windows until ACL validation exists.

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
java -cp "$(cat build/launcher-test-classpath.txt)" com.qb20nh.loadonce.launcher.NativeDisplaySmoke \
  --xvfb /workspace/.loadonce/prototype/native-host/root/usr/bin/Xvfb \
  --command /workspace/.loadonce/toolchains/jdk-25.0.2/bin/java \
  -cp "$(cat build/launcher-test-classpath.txt)" com.qb20nh.loadonce.launcher.CracIntegrationSmoke \
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

## Cloud CRIU, CI and host limits

Bundled CRIU `3.17.1-crac` failed its kernel check/checkpoint in the cloud environment: read-only `/proc/loginuid`, socket operations rejected with `EPERM` and failed TCP-repair probing. Effective/bounding capabilities were zero, `NoNewPrivs=1`, and seccomp filtering was enabled. The CRIU engine terminates the JVM after failure; callbacks cannot recover a process that has exited. The local experimental external-provider result above does not change these cloud limits.

Only Warp receives `checkpoint_exit_code=0`. A CRIU capture exit is accepted only with substantial payload and explicit successful dump evidence; a passing integration still requires a separate verified restore. An HTTP host allowlist cannot supply kernel capabilities or devices. Existing network hosts suffice for the verified runtime/dependency downloads; no additions are needed for this continuation.

Linux CI builds all four JARs and runs Java launcher/agent contracts, actual Leyden with and without the agent, executable supervisor policy/transport tests, default/changed software native reconstruction and injected-failure cleanup. Windows CI compiles both MSVC executables with warnings as errors and runs packaged-agent, direct AOT, executable-supervisor and unsupported-cache fallback tests before packaging its distribution. CRaC image checks require a verified CRaC runtime and remain explicit local integration checks. The previous direct-redirector baseline `1592df1` passed Linux/Windows CI at <https://github.com/qb20nh/LoadOnce/actions/runs/38043133740>.

Before automatic game checkpointing, establish renderer/audio/native ownership, quiesce work on owner threads, exclude credential/world/session state, reconstruct native resources, reattach authorized sessions and prove an interactive menu. Servers must load current persistent world data. The earlier conventional dedicated-server readiness/status/ping smoke remains prior evidence; it was not rerun here.
