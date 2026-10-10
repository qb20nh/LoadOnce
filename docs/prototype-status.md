# Prototype validation and continuation

Recorded on 2026-10-10. Continued from commit `114b5dd` on `codex/crac-leyden-native-restoration`; the destination branch is `main` only. Sources are in `/workspace/LoadOnce`; retained tools are under `/workspace/.loadonce`. Live processes do not survive an environment restart.

## Actual CRaC process images

Azul CRaC JDK 25.0.4.1, build `Zulu25.36+205-CRaC-CA`, is installed at:

```text
/workspace/.loadonce/prototype/runtime/zulu25.36.205-ca-crac-jdk25.0.4.1-linux_x64
```

The archive was checked against Azul's official package metadata before extraction:

- Package UUID: `5ca0f008-c0a5-45f0-8694-9919d3cc355d`.
- Metadata: <https://api.azul.com/metadata/v1/zulu/packages/5ca0f008-c0a5-45f0-8694-9919d3cc355d/>.
- Archive SHA-256: `6d1beee9795c544f53ff7e665d6455081960d18b476c0f6054f5e35cef97b61e`.

Both `api.azul.com` and `cdn.azul.com` work through the environment proxy. No additional host allowlist entries are needed for these checks.

The `warp` engine created an actual CPU fixture image and restored the same image into three new JVMs. The enhanced `CracSmoke` checks a retained random heap witness, value `42`, one preparation/restoration callback, coordinator `READY`, and the kernel PID. The driver compares the saved PID and witness with each separately launched child; ordinary fixture startup cannot satisfy the restored-state evidence.

The native fixture also created an actual Warp image: `core.img` was 68,231,168 bytes. Three fresh JVM restores of that same image each resumed at cycle 1 and passed GLFW timer/window/callback checks, OpenGL texture/buffer readback and shader rendering, OpenAL playback offset and rendered PCM comparison, and native cleanup. No initial cycle 0 ran in the restored JVMs. This proves process-image restoration with reconstruction on the selected software graphics/audio stack, not a Minecraft or hardware GPU snapshot.

The clean driver also passed real CRaC gate denial, partial preparation recovery, and failed restoration stopping the coordinator. An intentionally open, unregistered `FileInputStream` caused the real Warp checkpoint boundary to reject the attempt; both callbacks ran on the owner thread, the coordinator recovered to `READY`, and the rejected image directory stayed empty. `simengine` separately passed callbacks and is labelled as simulation.

The driver starts fixture JVMs with `PATH`, private `HOME`, and `LANG`, excluding inherited tokens, injected JVM options and native preloads. Native mode additionally uses the test display/auth path, private XDG/Mesa directories, software rendering, and an explicit null hardware audio backend. The local X11 cookie belongs to the temporary test display; it is not launcher authentication. Images/logs are private local test artifacts under ignored `build/`, never published with the source.

Repeat the CPU test:

```sh
python3 tools/smoke_crac.py --java /path/to/verified-crac-jdk/bin/java
```

Repeat the native image test after compiling its classpath with the prepared JDK 25:

```sh
source /workspace/.loadonce/setup/activate.sh
cd /workspace/LoadOnce
bash ./gradlew --no-daemon --console=plain --max-workers=2 writeNativeProbeClasspath
tools/native-smoke.sh \
  --xvfb /workspace/.loadonce/prototype/native-host/root/usr/bin/Xvfb \
  --command python3 tools/smoke_crac.py \
  --java /workspace/.loadonce/prototype/runtime/zulu25.36.205-ca-crac-jdk25.0.4.1-linux_x64/bin/java \
  --native-classpath-file build/native-probe-classpath.txt
```

The driver defaults to three restores, limits each child to 60 seconds, and stops its JVM/helper group on cancellation. The display wrapper uses a dynamically allocated X11 display, private authentication, and bounded process cleanup. Images still depend on the same runtime/native binaries, library paths and a compatible live display; the fixture driver is not an image compatibility/fallback launcher for arbitrary applications.

Local evidence from this continuation:

- CPU save/repeated restores and descriptor rejection: `build/crac-validation/run-dbx_vdu8/`.
- Native save/repeated restores: `build/crac-validation/run-tq1_2tj_/`.
- Callback-only simulation: `build/crac-validation/run-_jm2f3wt/`.
- CRIU rejection: `build/crac-validation/run-bf2tsxhk/`.

Warp logs a nonfatal attempt to write read-only `/proc/self/comm`; both engine success markers and application verification passed. This message is not suppressed.

## Native ownership and the PulseAudio boundary

Ten in-process reconstruction cycles passed on LWJGL 3.4.3, Xvfb, Mesa llvmpipe 25.0.7 and OpenAL Soft. Each generation renders 2,048 mono float PCM samples and compares them with the initial sample array within `1e-6`. The observed hash was `-750305279`; energy was nonzero. Playback offsets alternated between 128 and 2176 as expected. The available OpenAL build does not expose `SOFT_buffer_samples`, so direct buffer readback did not run; actual loopback sample rendering did.

Audio quiescence pauses the source before reading its cursor, preserving position for both rollback and reconstruction. GLFW elapsed time survives `glfwTerminate()`/`glfwInit()`. Native object deletion and detached contexts are checked rather than inferred from cleared Java fields. An injected invalid shader left the coordinator `STOPPED`, checked the failure marker, and released every tracked resource. The original default device path also passed three cycles with `ALSOFT_DRIVERS=null`.

The first native Warp attempt rejected a residual pipe even though tracked handles were zero. `strace -k` attributed it to OpenAL's library-global PulseAudio mainloop: `pa_mainloop_new`/`pa_threaded_mainloop_new` inside `alcLoopbackOpenDeviceSOFT`. Device/context close did not destroy that backend; its pipe closed only at process exit. GLFW's separate wakeup pipe closed correctly in `glfwTerminate()`.

For the headless loopback fixture, `ALSOFT_DRIVERS=null` prevents initialization of that unused physical backend while loopback still renders real PCM. A second trace confirmed that the residual pipe disappeared, and actual Warp save/repeated restore then passed. No descriptor exemption, forced close of an unknown handle, or validation bypass was used. Shared native libraries remain loaded; blindly unloading LWJGL providers would leave static function caches unsafe.

Production PulseAudio backend shutdown/restoration is not implemented. Physical OpenAL devices, hardware GPUs/drivers, SDL ownership, real keyboard/mouse events, and controllers still require separate integration and host testing.

The retained Xvfb was extracted from authenticated Debian trixie packages; it was not installed into the host. Logs are under `/workspace/.loadonce/prototype/native-host/logs/`; the pipe traces are under `/workspace/.loadonce/prototype/native-fd/`.

## Leyden and regression checks

The final Gradle build compiles both Minecraft source sets and the native fixture, packages the Java 17 standalone runtime, and runs 718 lifecycle contract checks. The default JUnit `test` task still has no sources; these executable contract checks are separate.

All 30 Python launcher tests pass. `tests/smoke_leyden.py` passes against Oracle JDK 25.0.2, verifying actual AOT cache mapping, exact argument boundaries, failed training retaining the previous cache, nonzero application exit without replay, JVM archive rejection falling back in the same JVM, prelaunch corruption fallback, and credential-environment refusal before main executes.

Known credential environment variables now disable caching/reject training. `JDK_AOT_VM_OPTIONS` requires ordinary startup. Explicit LWJGL library and extraction paths enter content fingerprints. Cache schema 2 invalidates earlier artifacts created before these checks. Custom credentials loaded from arbitrary files/environment variables still require `--sensitive` and host integration; there is no universal credential discovery.

CI includes the build, Python contracts, real Leyden smoke, software native loopback cycles and deliberate native failure cleanup. CRaC image tests require a verified CRaC JDK and remain explicit local integration checks.

## CRIU and remaining Minecraft integration

Bundled CRIU `3.17.1-crac` fails its actual kernel check and checkpoint in this container: read-only `/proc/loginuid`, socket operations rejected with `EPERM`, and failed TCP-repair probing. Effective and bounding capabilities are zero, `NoNewPrivs=1`, and seccomp filtering is enabled. The CRIU engine terminates the JVM after failure; Java callback recovery alone cannot provide conventional-startup fallback for that case. A future supervisor must own that decision. An HTTP allowlist cannot supply kernel capabilities or devices.

The driver supplies Warp's `checkpoint_exit_code=0` only to Warp. CRIU/simulation reject that engine option. CRIU's checkpoint termination is accepted only provisionally; image contents, successful separate restore, and application-state verification are still mandatory for a passing result. Actual CRIU restoration remains blocked/unverified here.

The Fabric target remains Minecraft 26.3, loader 0.19.5 and Java 25, with its original entrypoints/Mixins. It uses SDL, so the independently verified GLFW fixture does not implement its renderer integration. The previous conventional dedicated-server readiness/status/ping smoke remains prior evidence; it was not rerun for this fixture-only continuation. External Minecraft authentication routing is also separate from local status readiness.

Before automatic Minecraft checkpointing, establish renderer/audio/native ownership and reconstruction data, quiesce work on its owner threads, exclude credentials/world/session state, reattach authorized sessions, and prove a fully interactive main menu. Servers must load current persistent world data. Hardware restoration, startup-speed or memory improvements, lazy paging, persistent shader caching, warmed JIT reuse and native compilation have not been measured or implemented by these tests.
