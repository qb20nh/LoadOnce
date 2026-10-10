# LoadOnce

LoadOnce is a prototype for reusing JVM initialization and reconstructing external resources. The current checkout contains a Fabric 26.3 test target, a loader-independent checkpoint lifecycle library, a Linux JVM command wrapper, and an isolated LWJGL restoration probe.

The prototype does not yet restore Minecraft to its main menu or create reusable Minecraft process images. The Fabric entrypoints remain the original test target. In particular, Minecraft 26.3 uses SDL; the GLFW probe exercises the reconstruction design independently of that renderer.

## Build and contract checks

Use JDK 25 and Python 3.10 or newer on Linux. In the prepared cloud workspace, first refresh and activate the retained tools:

```sh
bash /workspace/.loadonce/setup/configure-runtime.sh
source /workspace/.loadonce/setup/activate.sh
cd /workspace/LoadOnce
bash ./gradlew --no-daemon --console=plain --max-workers=2 build
python3 -m unittest discover -s tests -v
python3 tests/smoke_leyden.py
```

`build` compiles both Minecraft source sets and the native probe, and runs the executable `runtimeContractTest` through `check`. It also produces a standalone Java 17 runtime JAR with the `runtime` classifier, containing no Minecraft or Fabric dependencies. The default Gradle `test` task has no JUnit sources; the runtime contract tests and Python suite are separate checks.

## Checkpoint resource lifecycle

`dev.loadonce.runtime.CheckpointCoordinator` captures its owner thread. Register resource dependencies first. It quiesces and releases resources in reverse dependency order, recreates them in dependency order, and resumes producers only after every restoration succeeds.

A host integration supplies a `CheckpointGate` that must prove the lifecycle boundary is reusable: credentials, sessions, connections, world state, and unmanaged external resources must be excluded. The library cannot establish that proof for an arbitrary game or mod. A denied gate changes no resources. Partial preparation is compensated; failed compensation or restoration stops every participant and leaves the coordinator `STOPPED`.

`CracBridge` optionally registers a strongly held resource with the actual `jdk.crac` API through reflection. It does not implement a checkpoint engine or simulate CRaC callbacks. `cracSmoke` invokes the engine on an isolated CPU-only probe:

```sh
bash ./gradlew cracSmoke -PloadonceCracJava=/path/to/crac-jdk/bin/java
```

The VM needs its own documented engine configuration. The tasks accept `-PloadonceCracEngine=ENGINE_NAME` and provide an image directory under `build/`. Engine availability, callback thread behavior, and real images must be tested on that runtime. Callback completion, including a runtime's simulation mode, does not establish a kernel snapshot.

## GLFW, graphics, and audio reconstruction

`nativeRestorationProbe` creates and destroys real LWJGL 3.4.3 resources. It verifies GLFW window settings, six native callback registrations and delivered resize events; OpenGL texture and buffer contents, shader linking, framebuffer completeness and rendered pixels; and OpenAL device/context, PCM format, source attachment and playback state. It retains CPU descriptors and clears tracked handle values after release. Numeric handle values may be reused by drivers.

With an existing X11 display and audio device:

```sh
bash ./gradlew nativeRestorationProbe -PloadonceCycles=10
```

With Xvfb, Mesa software rendering and an explicit null audio backend:

```sh
xvfb-run -a env LIBGL_ALWAYS_SOFTWARE=1 ALSOFT_DRIVERS=null \
  bash ./gradlew nativeRestorationProbe -PloadonceCycles=10
```

The null backend verifies OpenAL lifecycle, not physical playback. Software rendering does not verify a hardware GPU or driver survives process restoration. Input callback registration is tested; real keyboard/mouse interaction and controllers require additional checks. The probe source is deliberately muted.

The failure probe must exit unsuccessfully after an invalid shader interrupts restoration. Its output must include `coordinator-state=STOPPED`, `failure-stop-verified=true`, and `cleanup=tracked-handles-zero native-release=ok`:

```sh
bash ./gradlew nativeRestorationProbe -PloadonceInvalidShader=true
```

On a configured CRaC JDK, add `-PloadonceUseCrac=true` and `-PloadonceCracJava=/path/to/crac-jdk/bin/java` to route the same resources through real CRaC callbacks. This path remains unverified in the current environment.

## Leyden AOT command wrapper

The wrapper probes the selected Java executable rather than assuming its version provides CRaC or Leyden. `doctor` also runs CRIU's kernel check when `criu` is on `PATH`:

```sh
python3 tools/loadonce.py doctor --java "$JAVA_HOME/bin/java"
```

For a JAR-based launch, explicitly train once, then wrap the same original command:

```sh
python3 tools/loadonce.py train --cache-dir build/aot-cache \
  --input mods --input config --input resourcepacks -- \
  "$JAVA_HOME/bin/java" -cp application.jar your.MainClass
python3 tools/loadonce.py run --cache-dir build/aot-cache \
  --input mods --input config --input resourcepacks -- \
  "$JAVA_HOME/bin/java" -cp application.jar your.MainClass
```

Training executes the application once and promotes a cache only after a successful exit and unchanged inputs. Declare dynamically loaded mods, configuration and resource inputs with repeated `--input`; there is no universal loader discovery yet. JDK 25 rejects nonempty classpath directories for AOT training, so development launches normally need packaged JARs. Modular JAR launches are also supported.

The fingerprint covers the JVM binary/libraries/configuration, original arguments, relevant environment, classpath and manifest dependencies, module paths, agents and declared inputs. It reads their contents on each launch, so fingerprinting has a cost that is not yet optimized. Private cache entries require a complete manifest and matching archive checksum. Unsupported, missing, changed, corrupt or busy caches fall back before launching the application. A child launch is never replayed after failure; the JVM may discard incompatible AOT data during that single launch.

Recognized sensitive launch options disable caching; use `--sensitive` for custom secret-bearing options. Argument files, injected JVM options and native-library injection environments also select ordinary startup. Original argument boundaries and authentication behavior are preserved. Metadata contains digests rather than argument text. The AOT cache reuses JVM class/linking data; it is not a saved application process or warmed JIT machine-code cache.

## Current evidence and next step

See [prototype validation and continuation notes](docs/prototype-status.md) for the exact environment evidence and restart requirements. The next integration work is to capture Minecraft's renderer/resource ownership and a credential-free lifecycle boundary, then validate CRaC/CRIU restoration on a host with the necessary kernel and graphics capabilities. Startup speed and memory improvements have not been measured yet.

## License

CC0-1.0, inherited from the Fabric template.
