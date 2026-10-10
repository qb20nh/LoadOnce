# LoadOnce — local development handover

## Baseline and identity

- Repository: <https://github.com/qb20nh/LoadOnce>.
- Continue on `main`; commit and push to `main` only.
- This handover includes the Fabric identity and Java namespace rename. Obtain the current revision with `git rev-parse HEAD` rather than treating an earlier verified commit as the latest revision.
- The last verified supervisor baseline before the rename was `374308dcf38b6d25a25d81902d07449405f8e9d3`.
- Linux and Windows CI passed for that baseline: <https://github.com/qb20nh/LoadOnce/actions/runs/38051901162>.
- The broader restoration evidence recorded below comes from that baseline or the identified earlier integration runs, before the Java namespace changed to `com.qb20nh.loadonce`. Check [build runs on main](https://github.com/qb20nh/LoadOnce/actions/workflows/build.yml?query=branch%3Amain) for the current revision's Linux and Windows results.

Local verification of the identity rename passed a clean build, lifecycle/launcher/agent contracts, real JDK 25 AOT mapping, native executable forwarding, supervisor AOT reuse and distribution packaging. Packaged metadata, entrypoints, mixins, icon and agent/supervisor manifests use the new names. Minecraft 26.3 also loaded `loadonce`, initialized the agent bridge and rendered three menu frames through the installed wrapper on software Vulkan. This verifies startup after the rename; it does not establish checkpoint restoration or physical-device support.

The project now uses:

| Metadata | Value |
| --- | --- |
| Fabric mod ID | `loadonce` |
| Display name | `LoadOnce` |
| Maven group | `com.qb20nh.loadonce` |
| Artifact prefix | `loadonce` |
| Main entrypoint | `com.qb20nh.loadonce.LoadOnceMod` |
| Client entrypoint | `com.qb20nh.loadonce.client.LoadOnceClient` |
| Server mixin | `com.qb20nh.loadonce.mixin.LoadOnceServerMixin` |
| Client mixin | `com.qb20nh.loadonce.client.mixin.LoadOnceClientMixin` |
| Mixin resources | `loadonce.mixins.json`, `loadonce.client.mixins.json` |
| Icon | `assets/loadonce/icon.png` |

The version remains `1.0.0`. All Java packages use the `com.qb20nh.loadonce` namespace. The mod metadata names `qb20nh` as author and points its homepage, source and issues links at this repository.

When upgrading from the former `modid` names, remove the old mod JAR before adding the new one. Do not leave both artifacts in the instance's mods directory. Update direct agent paths. Installed private runtimes contain absolute artifact paths, so recreate them in a new directory and reselect their Java executable. Clean old build outputs before packaging:

```bash
./gradlew clean
```

On Windows, use `.\gradlew.bat clean`.

## User requirements

- No Python.
- Keep startup policy and restoration logic in Java, Java agents and mod hooks.
- A wrapper JVM is acceptable.
- Support Linux and Windows.
- Prism, Modrinth, CurseForge and vanilla launchers should select a Java executable normally.
- Normal game launches must not require manually invoking a launcher JAR.
- Continue towards CRaC, CRIU, Leyden and stable graphics/audio/window restoration.

## Implemented launch path

```text
External launcher
  → private native java/javaw executable
  → Java supervisor
  → real backend JVM + LoadOnce agent
  → original application main
```

Native code handles transport and process ownership. Java handles startup/cache policy. The launcher JAR is used internally as the supervisor; the end user selects the installed executable.

Original arguments and environment travel through a bounded anonymous pipe. Application stdin/stdout/stderr and working directory remain inherited. The supervisor does not consume Prism's stdin launch script or persist launcher arguments.

JVM-option and native-loader environment variables are removed from the supervisor environment and restored for the backend. They apply once.

Linux uses a guardian, worker session and subreapers. Windows uses atomic job assignment and explicit handle inheritance. A completion packet releases intentional helpers after normal application exit, including nonzero exits. Native/controller crashes keep cleanup armed.

Both JVMs load the checkpoint guard. The agent has no class transformers and vetoes every checkpoint. No Minecraft image restoration is enabled.

### Startup policy

The installed `loadonce-policy.conf` supports:

- `auto`: use a validated existing AOT cache; otherwise start normally. It never trains automatically.
- `off`: ordinary startup.
- `train`: explicitly run the application once and publish a cache only after successful exit and unchanged inputs.

Failures after attempting application startup never replay main.

Credential-bearing commands/environments, checker/version probes, argument files and opaque Prism/MultiMC stdin entrypoints bypass caching. Current authenticated Minecraft commands therefore use ordinary startup.

Windows cache ownership checks fail closed to ordinary startup. ACL validation is still needed before enabling automatic Windows AOT reuse.

## Build locally

Use a full JDK 25 and set `JAVA_HOME` and `PATH` accordingly. The Minecraft target requires JDK 25. Standalone agent, supervisor and checkpoint runtime bytecode targets Java 17.

Current dependencies:

- Minecraft `26.3`.
- Fabric Loader `0.19.5`.
- Fabric API `0.161.0+26.3`.
- Loom `1.18-SNAPSHOT`.
- Gradle wrapper `9.7.1`.
- Standalone native probe: LWJGL `3.4.3`.

Loom is a snapshot, so its resolved version can differ between builds.

### Linux

Install GCC, then run:

```bash
./gradlew --no-daemon --console=plain --max-workers=2 \
  build leydenSmoke agentAotSmoke redirectorSmoke \
  supervisorAotSmoke redirectorDistribution
```

For software graphics/audio tests on Ubuntu, install:

```bash
sudo apt-get install xvfb xauth libgl1-mesa-dri libglx-mesa0
```

Then run:

```bash
./gradlew nativeDisplaySmoke
./gradlew nativeDisplaySmoke -PloadonceReconfigure=true
./gradlew nativeDisplaySmoke \
  -PloadonceReconfigure=true \
  -PloadonceInvalidShader=true
```

The invalid-shader scenario passes only when the expected failure stops the coordinator and releases all tracked handles.

### Windows

Install CMake and MSVC with C/C++ build tools. Native wrappers require Windows 10 version 1703 or newer.

Build the artifacts:

```powershell
.\gradlew.bat --no-daemon assemble runtimeJar agentJar launcherJar
```

Run the Windows CI verification set:

```powershell
.\gradlew.bat --no-daemon --continue agentContractTest agentAotSmoke redirectorSmoke supervisorAotSmoke redirectorDistribution
```

Windows graphical restoration is not verified. The standalone probe currently declares Linux native dependencies only.

Windows executables use a static C runtime. The final supervisor baseline's packaged `java.exe` and `javaw.exe` imported only `SHELL32.dll` and `KERNEL32.dll`; CI checks executable dependencies when packaging.

## Artifacts and installation

Build outputs under `build/libs/`:

| Artifact | Purpose |
| --- | --- |
| `loadonce-1.0.0.jar` | Fabric mod and client observation hooks |
| `loadonce-1.0.0-runtime.jar` | Standalone checkpoint lifecycle library |
| `loadonce-1.0.0-agent.jar` | Premain agent and runtime installer |
| `loadonce-1.0.0-launcher.jar` | Internal supervisor and optional standalone CLI |

Distributions are `build/distributions/loadonce-1.0.0-redirector-linux.zip` and `build/distributions/loadonce-1.0.0-redirector-windows.zip` on their respective build platforms.

On Linux:

```bash
"$JAVA_HOME/bin/java" \
  -cp build/libs/loadonce-1.0.0-agent.jar \
  com.qb20nh.loadonce.agent.RuntimeRedirectInstaller \
  --java-home "$JAVA_HOME" \
  --redirectors-dir build/native-redirectors \
  --agent-jar build/libs/loadonce-1.0.0-agent.jar \
  --supervisor-jar build/libs/loadonce-1.0.0-launcher.jar \
  --output-dir /absolute/path/to/new/loadonce-runtime
```

On Windows, run setup with the selected backend JDK:

```powershell
& "$env:JAVA_HOME\bin\java.exe" -cp build/libs/loadonce-1.0.0-agent.jar com.qb20nh.loadonce.agent.RuntimeRedirectInstaller --java-home "$env:JAVA_HOME" --redirectors-dir build/native-redirectors --agent-jar build/libs/loadonce-1.0.0-agent.jar --supervisor-jar build/libs/loadonce-1.0.0-launcher.jar --output-dir "C:\Java\loadonce-runtime"
```

The output directory must be new and outside the backend JDK. Select its `bin/java`, `bin/java.exe` or `bin/javaw.exe` in the external launcher. The backend JDK remains unchanged. Put `loadonce-1.0.0.jar` and matching Fabric API in the Fabric instance's mods directory; agent/supervisor JARs belong outside it.

Installation details:

- Installed configuration contains absolute paths. Reinstall into a new directory when paths change.
- Default policy is `auto`.
- Optional setup arguments include `--aot`, `--cache`, repeated `--input`, and `--verbose`.
- Keep installed artifact paths unchanged when switching from training to reuse.
- Remove previous manual LoadOnce agent options when switching to the wrapper, especially options in environment variables or argument files.
- Windows supervisor launch paths must round-trip through the setup JVM's `native.encoding`.
- Original arguments reach the backend through the wide-character API, but that Java launcher can still narrow unsupported characters through its native code page.

## Recorded verification

### Executable and AOT integration

The verified Linux/Windows supervisor baseline covers:

- Original argument boundaries and JVM properties.
- Checker JARs and version/settings/usage probes.
- Quiet agent startup and packaged-agent provenance.
- Raw binary stdin/stdout/stderr.
- JVM environment options applying once.
- Working directory and environment preservation.
- Nonzero application exits without replay.
- Helper survival after normal exit.
- Forced native/controller termination and process-tree cleanup.

Linux additionally verifies graceful TERM shutdown hooks.

Real JDK 25 AOT checks passed training, linked-cache mapping, failed training preserving an existing cache, post-main publication refusal, integrity fallback and HotSpot archive rejection without replay.

### CRaC

Actual Azul CRaC JDK `25.0.4.1`, build `Zulu25.36+205-CRaC-CA`, passed Warp capture and three independent restores on the recorded cloud host. On the local CachyOS host, capture passed but Warp restoration crashed with exit `139`; the native cause remains unresolved.

Tests verify retained heap state, changed process IDs, lifecycle callbacks, descriptor rejection, compatibility/corruption fallback and failed restoration without replay.

Standalone native Warp restoration passed on the cloud's software graphics/audio stack. This is fixture evidence, not a Minecraft image.

To reproduce CPU integration with a local compatible CRaC JDK:

```bash
./gradlew cracIntegrationSmoke \
  -PloadonceCracJava=/absolute/path/to/crac-jdk/bin/java
```

`simengine` verifies callbacks only; it does not create a real process image.

Local experimental CPU restoration passed with upstream CRIU `4.2.1` in private Linux namespaces: one substantial image, three separate restores, retained heap witness/value `42`, exact callbacks and unchanged runtime/image hashes. To reproduce, run `./gradlew criuNamespaceSmoke -PloadonceCracJava=/absolute/path/to/crac-jdk/bin/java`. Azul labels the external `upstream_criu=true` integration unsupported; this optional fixture does not admit Minecraft images. See [current host evidence](prototype-status.md#local-linux-continuation).

### Graphics/audio and Minecraft

The native fixture passed GLFW window/timer/callback reconstruction, OpenGL readback/rendering and OpenAL playback/PCM reconstruction. Reconfigured settings and failure cleanup passed.

Minecraft 26.3 reached a rendered menu through the installed native wrapper and Java supervisor using software Vulkan. That result reported:

```text
inputVerified=false
restorationVerified=false
checkpointTaken=false
```

The local NVIDIA desktop passed ten changed-settings native reconstruction cycles with loopback and system audio, plus invalid-shader cleanup. A window-manager resize-ordering bug found on this host was fixed. Minecraft reached its rendered menu through the installed wrapper on NVIDIA OpenGL and analog OpenAL; input and restoration flags remain false. These results do not establish a hardware process image or library-global PulseAudio shutdown.

The installed Prism `11.0.3` JavaCheck/NewLaunch bootstrap JARs passed the new optional `prismLauncherSmoke`: checker parity, real stdin protocol, arguments/properties/environment, abort, cancellation and untouched AOT cache. Launcher GUI/runtime selection and Modrinth/CurseForge applications remain untested.

## Run the Minecraft menu diagnostic locally

Use a configured desktop/display and the installed wrapper:

```bash
./gradlew runClient \
  -PloadonceClientJava=/absolute/path/to/loadonce-runtime/bin/java \
  -PloadonceMenuProbe=true \
  -PloadonceMenuScreenshot=true \
  -PloadonceMenuGameDir=build/menu-probe/local-game \
  -PloadonceMenuTimeout=120
```

On Windows, use `gradlew.bat` and the installed Windows executable path.

Do not simultaneously add `-PloadonceAgent=true`; the wrapper injects the agent. For a fresh game directory, put this in `options.txt` to skip onboarding:

```text
onboardAccessibility:false
```

Require `LOADONCE_FABRIC_AGENT` and `LOADONCE_MENU_PROBE_PASSED`, with no `LOADONCE_MENU_PROBE_FAILED`. Exit code 0 alone is insufficient. Keep an outer deadline for native hangs.

## Source navigation

| Area | Main entry points |
| --- | --- |
| Fabric metadata | `src/main/resources/fabric.mod.json` |
| Fabric startup | `LoadOnceMod`, `LoadOnceClient` |
| Bootstrap protocol and policy file | `SupervisorMain` |
| External startup/cache selection | `ExternalSupervisor` |
| AOT validation and publication | `AotLauncher`, `JavaCommand`, `LauncherFiles` |
| Child lifetime and Windows quoting | `ChildProcess`, `WindowsCommandLine` |
| Installation | `RuntimeRedirectInstaller` |
| Early checkpoint guard | `LoadOnceAgent` |
| Fabric/system-loader bridge | `FabricAgentIntegration` |
| Lifecycle ordering and rollback | `CheckpointCoordinator`, `CheckpointGate`, `CracBridge` |
| Image fixture supervisor | `CracFixture` |
| Native reconstruction fixture | `NativeRestorationProbe` |
| Minecraft observation | `FabricMainMenuProbe` |
| Native bootstrap/Windows ownership | `native/redirector.c` |
| Linux guardian | `native/linux_supervisor.h` |
| Executable contracts | `RuntimeRedirectTest` |
| Real supervisor AOT checks | `SupervisorAotSmoke` |

Production Java sources are under `src/main/java/com/qb20nh/loadonce/`, with client hooks under `src/client/java/com/qb20nh/loadonce/` and native fixtures under `src/prototype/java/`.

## Remaining work

1. Exercise external-launcher GUI/runtime selection and full game launch on local Linux and Windows desktops. Installed Linux Prism bootstrap probes, stdin and cancellation now have fixture coverage.
2. Implement Minecraft 26.3 SDL/renderer resource ownership and reconstruction on the correct owner threads. The standalone GLFW fixture does not restore Minecraft's SDL resources.
3. Establish a credential/session-free checkpoint boundary and reacquire authorized current launcher state after restoration. Keep the unconditional agent veto until this boundary is implemented and verified.
4. Verify library-global PulseAudio shutdown, input and an interactive restored menu. Local NVIDIA/system-audio reconstruction and Minecraft cold-menu rendering passed.
5. Implement Windows ACL-based cache privacy and validation before enabling automatic Windows AOT reuse.
6. Resolve bundled CRIU and ordinary-host compatibility; local experimental upstream CRIU CPU capture and three independent namespace restores passed.
7. Measure startup time and memory after correctness is established; no performance improvement has been demonstrated.

OpenAL's library-global PulseAudio dispatcher retained a pipe after device/context cleanup. Fixture tests use `ALSOFT_DRIVERS=null` with loopback PCM rendering. Production PulseAudio restoration still needs work.

CRIU was blocked in the cloud by kernel permissions/capabilities, seccomp restrictions and TCP-repair failures. The local experimental namespace fixture does not remove those cloud limits. HTTP allowlist changes cannot fix those restrictions.

Do not replay an application after a restore attempt has started. Do not add descriptor exemptions or close unknown native handles merely to make checkpointing pass.

## Moving from cloud to local

These cloud paths are not portable dependencies:

```text
/workspace/.loadonce/
/workspace/LoadOnce/build/
```

They contain extracted JDKs/providers, caches, images, logs, screenshots and generated harness files. Live displays do not survive migration.

Generated Java helpers under the ignored `build/` directory contain cloud-specific paths and disappear after a clean build. Use the installer and `runClient` commands above locally.

Rebuild artifacts and regenerate harness classpaths:

```bash
./gradlew writeLauncherTestClasspath writeNativeProbeClasspath
```

Retrain and recapture locally, including after the artifact/package rename. Cache/image validation covers runtime, kernel, working directory, providers, display and Xauthority. Copying old caches or images is not a supported migration path.

Keep credentials, session data, images and display cookies out of source control.

## References

- [External-launcher setup and platform boundaries](external-launchers.md).
- [Detailed evidence and continuation notes](prototype-status.md).
- [Build and fixture commands](../README.md).
