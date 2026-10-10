# External Minecraft launchers

Select LoadOnce's private Java executable in an external launcher to run its original command through the Java supervisor. The supervisor selects startup policy and loads the agent in the game JVM. The Fabric mod provides version-specific game hooks. The same agent can also be configured directly when only premain integration is wanted.

The Fabric mod ID is `loadonce` and its display name is `LoadOnce`. Builds use the `loadonce` artifact prefix. If upgrading from the former template names, remove the old mod JAR from the instance, update direct agent paths, and recreate any private runtime in a new directory. Do not leave both mod JARs installed. Installed paths are absolute and are not migrated automatically.

## JVM-argument setup

Build `./gradlew build`. Put `loadonce-1.0.0.jar` and matching Fabric API into a Minecraft 26.3 Fabric instance's mods directory. Store `loadonce-1.0.0-agent.jar` outside that directory and add one JVM argument:

```text
-javaagent:/absolute/path/loadonce-1.0.0-agent.jar
```

If a path contains spaces, quote the entire argument according to your launcher's argument editor, for example `"-javaagent:C:\My Tools\loadonce-1.0.0-agent.jar"`. Do not add `java -jar` to the application's arguments. A plain vanilla installation can load the agent, but it needs a Fabric profile to load this mod's game hooks. The agent targets Java 17 or newer; this mod version requires Java 25 and Minecraft 26.3.

The agent leaves the launcher's main class, account arguments and standard streams intact. It stays quiet by default so Java-version/checker output remains usable. Add `-Dloadonce.agent.verbose=true` to request fixed startup identifiers on stderr. The Fabric initializer reaches its status endpoint through the system classloader, avoiding duplicate agent state in Fabric's Knot classloader. Agent classes are excluded from the mod JAR.

On a normal JDK, the status reports `crac_api=unavailable`. On a compatible CRaC JDK it reports `checkpoint_guard=true policy=deny`. The strongly held guard vetoes every checkpoint attempt until game ownership and authentication reconstruction exist. A veto prevents an approved image; CRaC can still notify other registered resources before reporting that veto. No option bypasses this guard.

## Selecting a redirected Java executable

A native executable starts a small Java supervisor, which launches the selected backend with the original arguments and the quiet agent. Cache and game policy remain in Java. Linux uses a native guardian to own the process tree; Windows uses console `java.exe` and windowed `javaw.exe` with atomic kill-on-close job assignment. The launcher tracks the native executable; supervisor and game PIDs differ from it.

Build on the target platform with a C compiler: GCC on Linux, or CMake and MSVC on Windows. The Windows redirector requires Windows 10 version 1703 or newer for atomic child-job assignment.

Windows executables link the C runtime statically; the private runtime directory needs no separate Visual C++ runtime DLL.

```sh
./gradlew redirectorDistribution redirectorSmoke supervisorAotSmoke
```

The distribution ZIP under `build/distributions/` is named `loadonce-1.0.0-redirector-linux.zip` or `loadonce-1.0.0-redirector-windows.zip`. It contains the supervisor/agent JARs and `redirectors/` executables for that platform. From the build tree, install into a new directory:

```sh
java -cp build/libs/loadonce-1.0.0-agent.jar com.qb20nh.loadonce.agent.RuntimeRedirectInstaller \
  --java-home /path/to/real/jdk --redirectors-dir build/native-redirectors \
  --agent-jar build/libs/loadonce-1.0.0-agent.jar \
  --supervisor-jar build/libs/loadonce-1.0.0-launcher.jar --output-dir /path/to/new/loadonce-runtime
```

This command runs setup once. Normal game launches use the native executable directly. The installer copies the supervisor, agent and native binaries, writes fixed paths into `bin/loadonce-runtime.conf`, and writes startup policy to `loadonce-policy.conf`. It requires a new output directory outside the backend JDK. Do not move an installed directory; rerun setup into a new location if paths change.

The supervisor receives original arguments and environment through a bounded anonymous pipe. It does not use stdin for control data, expand argument files, or persist the launch command. The backend inherits the original cwd and raw stdin/stdout/stderr. JVM-option and native-loader environment variables apply to the backend once; they are removed from the supervisor's own environment. Both JVMs load the checkpoint guard. A completion packet releases intentional helpers after normal application exit, including nonzero exits; abrupt native/controller termination keeps process-tree cleanup armed.

On Windows, run setup with the selected backend JDK. The installer requires its supervisor launch paths to round-trip through the setup JVM's `native.encoding`. Original arguments travel through the supervisor in UTF-8 and reach the backend through the wide-character Windows API. The selected Java launcher may then convert them through the host's ANSI code page, replacing unavailable characters with `?` even with direct Java. Use paths supported by that runtime's native encoding.

On Windows, build with `.\gradlew.bat` and use a single-line PowerShell setup command, substituting your actual JDK and output paths:

```powershell
java -cp build/libs/loadonce-1.0.0-agent.jar com.qb20nh.loadonce.agent.RuntimeRedirectInstaller --java-home "C:\Java\jdk-25" --redirectors-dir build/native-redirectors --agent-jar build/libs/loadonce-1.0.0-agent.jar --supervisor-jar build/libs/loadonce-1.0.0-launcher.jar --output-dir "C:\Java\loadonce-runtime"
```

When using an extracted ZIP, use its agent/launcher JAR paths and `redirectors` directory.

Point the external launcher at the installed executable:

| Launcher | Selection |
| --- | --- |
| Prism | Instance Java settings: choose `bin/java` or the Windows executable. Keep Java auto-detection from replacing the selection. |
| Modrinth | Settings → Java installations: set the location for the required Java version. Its Windows runtime lookup expects `javaw.exe`; Unix expects `java`. |
| CurseForge | Minecraft settings: choose the custom Java executable, or configure the delegated Mojang installation. |
| Vanilla | Installations → Edit → More Options → Java Executable. Choose a Fabric installation to use the mod. |

Use the real JDK's required Java version. Changing the path does not make an incompatible Java version support a newer Minecraft release. Java-version probes are forwarded to the real JVM; the redirector never fabricates their output. Prism runs a checker JAR and sends its game launch script through stdin. Modrinth runs a Java probe class and parses stdout. These streams remain available throughout forwarding. The redirector also adds the quiet agent to probe applications; opting into verbose diagnostics changes their stderr output.

The configured agent is injected once; an existing JVM argument referring to the installed agent is retained. Restore selection and launcher-session reconstruction remain disabled.

Remove a previous LoadOnce agent option in JVM-option environment variables or an argument file when selecting the wrapper. These opaque sources are forwarded unchanged and are not inspected for duplicate agents.

## Leyden and restoration boundaries

The executable wrapper applies AOT policy before launching the game JVM. Setup accepts `--aot auto|off|train`, `--cache /absolute/cache`, repeated `--input /absolute/mods-or-config`, and `--verbose`. Default `auto` reuses only a validated existing cache; missing, incompatible, corrupt or unsupported caches choose ordinary startup. `off` forwards normally. `train` explicitly runs the original application once and publishes only after successful exit and unchanged inputs. Change the installed policy's `aot=train` line to `aot=auto` after training; the installed artifact paths must remain the same.

Declare dynamically loaded mods, configuration, resources and native providers with `--input`. Known credential arguments/environments, checker/version probes, argument files and opaque Prism/MultiMC stdin entrypoints use ordinary startup without caching, even in `train` mode. Current authenticated Minecraft commands therefore use ordinary startup. Custom credentials loaded from arbitrary files are not automatically discoverable; use `off` until a host integration establishes a reusable boundary.

Fingerprints and private immutable generations use the existing Java cache validation. A failure after starting the application never causes a replay. Windows wrappers preserve executable integration but choose ordinary startup because cache ACL validation is not implemented. Linux AOT reuse requires a supporting JDK and JAR classpaths; nonempty development class directories cannot be trained by JDK 25.

`./gradlew supervisorAotSmoke` exercises policy through the installed executable; `agentAotSmoke` checks direct agent/AOT compatibility. Keep runtime, agent JARs/options, classpath, mods and transformation behavior consistent between training and use. A mapped cache can bypass a transformer added later even when the JVM accepts it. Current agent code installs no class transformers. Manually supplied AOT/restore flags remain the launcher's responsibility and bypass automatic cache selection.

Do not treat successful agent startup or a Java probe as a Minecraft restoration test. The current game diagnostic observes an ordinary rendered menu. SDL/Vulkan resources, physical GPU/audio devices, input and authentication reattachment remain unimplemented. The selected CRaC runtime retains checkpointed environment keys omitted by a restore environment, and a fresh launch argument cannot replace credentials already held in the restored heap. A safe reusable game boundary must exclude that state and acquire authorized current launcher data after restoration. The agent's checkpoint veto remains active for this reason.

Launcher settings and code references:

- [Prism Java settings](https://prismlauncher.org/wiki/help-pages/java-settings/) and [launch process](https://github.com/PrismLauncher/PrismLauncher/blob/develop/launcher/minecraft/launch/LauncherPartLaunch.cpp).
- [Modrinth Java installations](https://support.modrinth.com/en/articles/8797659-java-installations) and [runtime validation](https://github.com/modrinth/code/blob/main/packages/app-lib/src/util/jre.rs).
- [CurseForge Minecraft setup](https://support.curseforge.com/support/solutions/articles/9000218572-getting-started).
- [Mojang's Java executable guidance](https://feedback.minecraft.net/hc/en-us/articles/15044886907149-Minecraft-Java-Edition-23w16a).

See the [local development handover](HANDOVER.md) for build commands, verification evidence and remaining restoration work.
