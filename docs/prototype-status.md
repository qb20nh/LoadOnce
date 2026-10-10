# Prototype validation and continuation

Recorded on 2026-10-10. Sources are in `/workspace/LoadOnce`; retained tools and local test dependencies are outside the checkout in `/workspace/.loadonce`. Runtime processes do not survive an environment restart.

## Validated paths

- Oracle JDK 25.0.2 exposes `AOTCacheOutput`, `AOTCache`, and `AOTMode`. Real isolated JAR and modular-JAR applications trained AOT caches and launched with them. This establishes cache creation/use, not a Minecraft speed improvement.
- The common runtime compiles independently with `javac --release 17 -Xlint:all -Werror`. Its contract test covers lifecycle dependency order, partial rollback, failed restoration/resumption, engine abort recovery, registration, reentrancy, thread ownership and gate denial.
- `build/libs/modid-1.0.0-runtime.jar` contains only the shared runtime classes and license. Its class file version is 61 (Java 17), independent of the Java 25 Fabric target.
- The final native probe completed ten full release/recreate cycles with Xvfb, Mesa llvmpipe 25.0.7 and OpenAL Soft's null backend. Every cycle checked native callback delivery, resource contents, shader rendering and OpenAL state. This is an in-process lifecycle test, not process checkpoint restoration.
- The full Gradle build passed, including 718 executable lifecycle checks. All 25 Python launcher tests passed. The deliberate shader failure left the coordinator `STOPPED` and verified all tracked handles were cleared with successful native cleanup.
- The conventional dedicated-server smoke reached `Done`, returned Minecraft 26.3 / protocol 777 with 0/20 players and a valid ping response, and stopped through stdin with Gradle exit status 0. This checks the existing target's normal startup, not optimized restoration or external authentication.

`python3 tests/smoke_leyden.py` is the retained real JVM integration test. It checks that the JVM actually opens the AOT cache, preserves literal arguments, executes a failing warm application once, and falls back once on corrupt cache bytes. CI runs the build, Python contract suite and this real Leyden check.

The native tools came from Debian's signed trixie repository using a private APT state directory and extracted packages. They were not installed into the host. The retained Xvfb binary is `/workspace/.loadonce/native-test/root/usr/bin/Xvfb`.

To repeat the native check after restarting the environment, first start a display using the retained binary; `:91` is the display used here. Create `/tmp/.X11-unix` if absent. Keep the process controllable and stop it after testing:

```sh
/workspace/.loadonce/native-test/root/usr/bin/Xvfb :91 \
  -screen 0 640x480x24 -nolisten tcp -noreset -xkbdir /usr/share/X11/xkb
```

In another shell, activate Java and run:

```sh
source /workspace/.loadonce/setup/activate.sh
cd /workspace/LoadOnce
DISPLAY=:91 XDG_RUNTIME_DIR=/workspace/.loadonce/native-test/xdg \
MESA_SHADER_CACHE_DIR=/workspace/.loadonce/native-test/mesa-cache \
LIBGL_ALWAYS_SOFTWARE=1 ALSOFT_DRIVERS=null \
bash ./gradlew nativeRestorationProbe -PloadonceCycles=10
```

The retained `xdg` directory is private and the Mesa cache directory is writable. Verification logs are in `/workspace/.loadonce/native-test/`.

## CRaC runtime download

The installed Oracle JDK has neither the CRaC flags nor `jdk.crac.Core`. The reflective bridge and its CRaC native-probe path therefore remain unverified against a real CRaC engine.

`cdn.azul.com` is allowed and its official directory lists CRaC JDK 25 packages, including `zulu25.32.23-ca-crac-jdk25.0.2-linux_x64.tar.gz`. The attempted checksum suffixes were not published there. The package metadata request to `api.azul.com` was explicitly denied by the proxy with HTTP 403.

**Add `api.azul.com` to the environment host allowlist on the next chat/environment restart**, preserving the existing hosts and package-manager presets. The user reported that applying this change requires restarting the chat. After restart, verify the active network policy, fetch Azul's official package metadata/checksum, and verify the runtime archive before testing. No CRaC archive was installed without that verification.

Then run both the isolated `cracSmoke` and the native probe with the actual runtime's supported engine settings. First verify callbacks and failure recovery; separately verify checkpoint image creation and restoration in a new process. A successful simulation engine is only callback evidence.

## CRIU and host requirements

CRIU 4.1.1 is extracted at `/workspace/.loadonce/native-test/root/usr/sbin/criu`. To inspect it:

```sh
source /workspace/.loadonce/setup/activate.sh
cd /workspace/LoadOnce
PATH=/workspace/.loadonce/native-test/root/usr/sbin:$PATH \
LD_LIBRARY_PATH=/workspace/.loadonce/native-test/root/usr/lib/x86_64-linux-gnu \
python3 tools/loadonce.py doctor --java "$JAVA_HOME/bin/java"
```

The actual kernel check exits 1 and requires `CAP_SYS_ADMIN` or `CAP_CHECKPOINT_RESTORE`. The container's effective and bounding capabilities are zero. `/dev/dri` is absent. An HTTP allowlist change cannot supply kernel capabilities, physical graphics or audio devices.

Use a suitably configured Linux test host for actual process images and hardware restoration. Do not use plain CRIU to snapshot the running Minecraft test world: the prototype has not established credential/session exclusion, native ownership, or persistent-world consistency.

## Remaining Minecraft integration

The Fabric target is still pinned to Minecraft 26.3, loader 0.19.5 and Java 25. Existing entrypoints and Mixins are unchanged. The generic lifecycle library has no Minecraft imports, but there is no version/loader-agnostic lifecycle discovery or launcher integration yet. Minecraft 26.3 uses SDL, so its renderer integration needs an SDL adapter as well as the GLFW path demonstrated here.

Before automatic process checkpointing, enumerate renderer/audio/native resource owners and reconstruction data, quiesce asynchronous work on the appropriate threads, exclude credentials and gameplay/world state, refresh authorized session state after restore, and prove a fully interactive main menu. Dedicated servers must reload current persistent world data rather than restoring stale in-memory world state. Existing authentication must remain enabled.

The earlier setup's external Minecraft Services discovery routing limitation remains separate from these local checks; status/ping readiness does not validate account authentication. No interactive Minecraft client or hardware GPU/audio restore has been verified. There is no measured startup-speed, memory, lazy-page restoration, shader-cache, native-fusion or warmed JIT reuse result yet.
