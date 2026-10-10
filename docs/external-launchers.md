# External Minecraft launchers

LoadOnce can run inside the JVM started by an external launcher. The agent JAR is loaded with `-javaagent:`; it is not an application launcher and starts no child JVM. The Fabric mod provides version-specific game hooks. The optional standalone launcher JAR is unnecessary for this path.

## JVM-argument setup

Build `./gradlew build`. Put `modid-1.0.0.jar` and matching Fabric API into a Minecraft 26.3 Fabric instance's mods directory. Store `modid-1.0.0-agent.jar` outside that directory and add one JVM argument:

```text
-javaagent:/absolute/path/modid-1.0.0-agent.jar
```

If a path contains spaces, quote the entire argument according to your launcher's argument editor, for example `"-javaagent:C:\My Tools\modid-1.0.0-agent.jar"`. Do not add `java -jar` to the application's arguments. A plain vanilla installation can load the agent, but it needs a Fabric profile to load this mod's game hooks. The agent targets Java 17 or newer; this mod version requires Java 25 and Minecraft 26.3.

The agent leaves the launcher's main class, account arguments and standard streams intact. It stays quiet by default so Java-version/checker output remains usable. Add `-Dloadonce.agent.verbose=true` to request fixed startup identifiers on stderr. The Fabric initializer reaches its status endpoint through the system classloader, avoiding duplicate agent state in Fabric's Knot classloader. Agent classes are excluded from the mod JAR.

On a normal JDK, the status reports `crac_api=unavailable`. On a compatible CRaC JDK it reports `checkpoint_guard=true policy=deny`. The strongly held guard vetoes every checkpoint attempt until game ownership and authentication reconstruction exist. A veto prevents an approved image; CRaC can still notify other registered resources before reporting that veto. No option bypasses this guard.

## Selecting a redirected Java executable

A small native redirector can insert the agent automatically. It contains no cache or game logic. Linux forwards with `exec`, retaining the PID tracked by the launcher. Windows uses console `java.exe` and windowed `javaw.exe`, Unicode argument quoting and a kill-on-close job to stop the Java child if the redirector is terminated. No second wrapper JVM is started.

Build on the target platform with a C compiler: GCC on Linux, or CMake and MSVC on Windows. The Windows redirector requires Windows 10 version 1703 or newer for atomic child-job assignment.

```sh
./gradlew redirectorDistribution redirectorSmoke
```

The distribution ZIP under `build/distributions/` contains the agent JAR and `redirectors/` executables for that platform. From the build tree, install into a new directory:

```sh
java -cp build/libs/modid-1.0.0-agent.jar dev.loadonce.agent.RuntimeRedirectInstaller \
  --java-home /path/to/real/jdk --redirectors-dir build/native-redirectors \
  --agent-jar build/libs/modid-1.0.0-agent.jar --output-dir /path/to/new/loadonce-runtime
```

This command runs setup once. Normal game launches use the native executable directly. The installer copies the agent and forwarding binaries, writes their absolute paths into `bin/loadonce-runtime.conf`, and retains the selected backend JDK. It requires a new output directory and refuses to install inside that JDK. Do not move an installed redirector directory; rerun setup into a new location if paths change. Installation neither replaces system Java nor edits the original runtime.

On Windows, run setup with the selected backend JDK. The installer rejects an agent path that this JVM's `native.encoding` cannot represent. The redirector forwards Unicode through the Windows wide-character API, but the selected Java launcher may convert arguments through the host's ANSI code page. Characters unavailable in that code page can become `?` in application arguments even with direct Java. Use paths and arguments supported by that runtime's native encoding; the redirector does not change it.

On Windows, build with `.\gradlew.bat` and use a single-line PowerShell setup command, substituting your actual JDK and output paths:

```powershell
java -cp build/libs/modid-1.0.0-agent.jar dev.loadonce.agent.RuntimeRedirectInstaller --java-home "C:\Java\jdk-25" --redirectors-dir build/native-redirectors --agent-jar build/libs/modid-1.0.0-agent.jar --output-dir "C:\Java\loadonce-runtime"
```

When using an extracted distribution ZIP, replace the build-tree agent paths with `modid-1.0.0-agent.jar` and the native directory with `redirectors`.

Point the external launcher at the installed executable:

| Launcher | Selection |
| --- | --- |
| Prism | Instance Java settings: choose `bin/java` or the Windows executable. Keep Java auto-detection from replacing the selection. |
| Modrinth | Settings → Java installations: set the location for the required Java version. Its Windows runtime lookup expects `javaw.exe`; Unix expects `java`. |
| CurseForge | Minecraft settings: choose the custom Java executable, or configure the delegated Mojang installation. |
| Vanilla | Installations → Edit → More Options → Java Executable. Choose a Fabric installation to use the mod. |

Use the real JDK's required Java version. Changing the path does not make an incompatible Java version support a newer Minecraft release. Java-version probes are forwarded to the real JVM; the redirector never fabricates their output. Prism runs a checker JAR and sends its game launch script through stdin. Modrinth runs a Java probe class and parses stdout. These streams remain available throughout forwarding. The redirector also adds the quiet agent to probe applications; opting into verbose diagnostics changes their stderr output.

This prototype does not inject additional AOT/restore flags, choose images or rewrite launcher sessions. Do not configure the agent both manually and through the redirector; duplicate premain registration is rejected before application main.

## Leyden and restoration boundaries

An agent starts after JVM startup-option parsing. It cannot enable `AOTCache`, change `AOTMode`, or turn an ordinary launch into `CRaCRestoreFrom` retroactively. Those choices belong to the external launcher's JVM arguments or a future prelaunch redirector policy.

`./gradlew agentAotSmoke` verifies direct AOT training/use with the same agent on an isolated application. The real JDK 25 can map an AOT-linked cache with this premain-only agent. Keep runtime, agent JARs/options, classpath, mods and transformation behavior consistent between training and use; retrain when they change. A mapped cache can bypass a transformer added later even when the JVM accepts the cache. Current agent code installs no class transformers. The standalone cache supervisor's fingerprints and credential guards do not apply to manually supplied JVM options.

Do not treat successful agent startup or a Java probe as a Minecraft restoration test. The current game diagnostic observes an ordinary rendered menu. SDL/Vulkan resources, physical GPU/audio devices, input and authentication reattachment remain unimplemented. The selected CRaC runtime retains checkpointed environment keys omitted by a restore environment, and a fresh launch argument cannot replace credentials already held in the restored heap. A safe reusable game boundary must exclude that state and acquire authorized current launcher data after restoration. The agent's checkpoint veto remains active for this reason.

Launcher settings and code references:

- [Prism Java settings](https://prismlauncher.org/wiki/help-pages/java-settings/) and [launch process](https://github.com/PrismLauncher/PrismLauncher/blob/develop/launcher/minecraft/launch/LauncherPartLaunch.cpp).
- [Modrinth Java installations](https://support.modrinth.com/en/articles/8797659-java-installations) and [runtime validation](https://github.com/modrinth/code/blob/main/packages/app-lib/src/util/jre.rs).
- [CurseForge Minecraft setup](https://support.curseforge.com/support/solutions/articles/9000218572-getting-started).
- [Mojang's Java executable guidance](https://feedback.minecraft.net/hc/en-us/articles/15044886907149-Minecraft-Java-Edition-23w16a).
