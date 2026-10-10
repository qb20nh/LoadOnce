# LoadOnce — experimental Stage 1

LoadOnce explores reusable JVM startup state for Minecraft clients and dedicated
servers. This prototype has loader-independent CDS and Leyden command wrappers,
a cooperative CRaC runtime, a GLFW/OpenGL/OpenAL replay experiment, and a Fabric
26.3 adapter that measures application readiness.

The working backend is **Dynamic CDS**: the first cleanly completed launch records
class metadata; subsequent compatible launches memory-map that archive. Normal
Minecraft initialization, authentication, resource loading, graphics creation, and
world loading still execute. CDS does not preserve an initialized heap, mod state,
JIT code, or GPU resources. Near-instant Minecraft startup and lower memory use have
not been demonstrated.

A separate **CRaC resource coordinator** supports cooperative, audited applications
on a real CRaC JDK. It defaults to vetoing checkpoints. The standalone runtime uses
Java 17 bytecode so existing CRaC JDKs can run it; the Minecraft build remains on
Java 25. The Fabric adapter never requests a process snapshot: the main menu has
graphics/audio/session resources, and a started server already contains world state.
Both need additional integration before they can safely checkpoint.

## Build and run the proof of concept

Requirements: JDK 25, Python 3.9+, and a Unix platform for launcher locking.
Use your normal Java environment. In the prepared cloud workspace, first run:

```bash
source /workspace/.loadonce/setup/activate.sh
```

From the repository directory:

```bash
bash ./gradlew --no-daemon --console=plain --max-workers=2 build
python3 -m unittest discover -s poc -p 'test_*.py' -v
```

The build produces `build/libs/loadonce-0.1.0-poc.jar` (Fabric mod) and
`poc/runtime/build/libs/loadonce-runtime.jar` (standalone runtime/Java agent).
The Java build also runs resource lifecycle, readiness, and native ownership
invariant checks. Native device tests are explicit and require a display/audio
backend; they are not silently replaced by mocks during the ordinary build.

Run this command twice to train and then use an actual class archive:

```bash
python3 poc/launch.py \
  --cache-dir .loadonce-cache \
  --runtime-jar poc/runtime/build/libs/loadonce-runtime.jar \
  --target server --report build/launch-report.json \
  -- java -jar poc/runtime/build/libs/loadonce-runtime.jar --server
```

The fixture prepares CPU-side resources and computes their checksum before reporting
readiness. It is a small Java workload, not a Minecraft benchmark. Use `--client`
instead of `--server` in both places to exercise the client milestone report.
`sharedArchiveLoaded: true` in the launch report means the JVM actually mapped the
dynamic archive; merely selecting a cache does not count as a successful load.

When the readiness agent is enabled, HotSpot requires the diagnostic
`AllowArchivingWithJavaAgent` option. The wrapper probes it first and supplies it only
with this agent. HotSpot labels these archives suitable for testing only. Leave out
`--runtime-jar` to use CDS without the agent; that mode has no readiness report.

## Wrap an existing Minecraft launch

Keep the launcher's authentication and account/session preparation. At its Java
command boundary, prefix the existing command with the wrapper:

```bash
python3 /path/to/LoadOnce/poc/launch.py \
  --cache-dir /private/cache/loadonce \
  --input /installation/libraries --input /installation/assets \
  --runtime-jar /path/to/LoadOnce/poc/runtime/build/libs/loadonce-runtime.jar \
  --target client --report /private/reports/launch.json \
  -- /path/to/java -cp 'game.jar:loader.jar:dependency.jar' example.Main [existing arguments]
```

The example entrypoint and JAR names must be replaced with the launcher's actual
command. For a server, use `--target server` and its existing `-jar` or `-cp` command.
The child inherits stdin, stdout, stderr, environment, working directory, and all
application arguments. No shell evaluates them. Python remains as a small parent
process until Java exits; this is a launcher integration prototype, not a native launcher.

Supported optimized launches use HotSpot Dynamic CDS with `-jar` or an explicit
classpath made of JARs, including local JAR manifest dependencies. Wildcards,
classpath directories, Java argument files, module launches, existing Java agents,
conflicting cache options, disabled CDS logging, and environment-injected JVM options conservatively run
the original command. This restriction includes some Forge/NeoForge launch scripts.
Use `--off` to run the original command directly.

Install the Fabric mod to get the two Minecraft milestones:

- **Client:** a presented main-menu frame after loading and the title fade finish,
  with no loading overlay, world, integrated server, or multiplayer connection.
- **Dedicated server:** Fabric's `SERVER_STARTED` event after initialization and
  current world loading. A server-list ping remains a separate end-to-end check.

The adapter uses Minecraft 26.3 APIs. Other versions/loaders require their own small
readiness bridges; the launcher and runtime contain no Minecraft dependencies.
Installing the probe alone does not accelerate startup.

## Cache validity and fallback

The cache key hashes the launcher implementation, platform, Java executable/runtime
image/native JVM libraries, working directory, entrypoint, ordered JAR contents and
manifest dependencies, JVM options, runtime agent, and chosen configuration inputs.
By default it includes `mods`, `plugins`, `config`, `resourcepacks`, `options.txt`,
and `server.properties` in the current directory. Add `--input` for loader libraries,
assets, native libraries, or other inputs outside those paths. Input trees containing
symlinks or special files take the conventional path.

Application arguments and environment values are never persisted, and this CDS
backend never writes heap/session/world state. It always starts a fresh Java
application with the current arguments. This is why application arguments are not
part of the class-metadata fingerprint. Keep archives private: the cache must be
owned by the launching user with mode `0700`.
Cache paths containing JVM logging delimiters such as `:` or `,` also take the
conventional path.

Training publishes only after exit code zero, a nonempty archive, and an unchanged
post-run fingerprint. The archive has a SHA-256 integrity check and an atomically
published manifest. A second launch during training executes conventionally rather
than waiting for a client or server to exit. Every optimized launch uses
`-Xshare:auto`, letting the JVM reject an incompatible archive before application
execution. A failed application is never restarted by the wrapper, since it may
already have written a world or performed external work.

Training requires graceful application shutdown. An image is not available while
that first client/server is still running. Strong content validation and the JVM
capability probe add startup cost, included in `launcherPreparationNanos`. Readiness
measures time from agent entry; include launcher preparation when comparing total
startup. `totalNanos` includes the application's entire lifetime, not just startup.

## Leyden and native reconstruction experiments

The [Leyden backend](poc/LEYDEN.md) uses JDK 25's automatic AOT-cache workflow to
preserve class loading/linking work and supported training data. It verifies the
selected cache was mapped with AOT-linked classes and keeps fresh application
arguments and world reads. Existing agents, JVM properties, and unsupported launch
forms conservatively take the conventional path.

```bash
python3 poc/leyden.py --cache-dir .loadonce-leyden-cache \
  --report build/aot-report.json \
  -- java -jar poc/runtime/build/libs/loadonce-runtime.jar --server
```

The [native replay fixture](poc/native/README.md) retains CPU texture, vertex,
shader, and PCM representations, releases its windows, contexts, objects, callbacks,
and audio devices, then reconstructs them on the owning thread. It verifies texture
and vertex readback, shader-rendered pixels, driver-compatible shader binaries,
window callbacks, and OpenAL source/buffer state. Failed allocation paths exercise
cleanup before a new generation can open. Native library mappings remain loaded
because LWJGL caches function addresses; unloading those libraries made subsequent
native calls invalid during testing.

This fixture targets GLFW/OpenGL/OpenAL. Minecraft 26.3's renderer uses a different
window/render stack, so replay is not wired into that client. Xvfb/Mesa llvmpipe and
OpenAL's null backend can exercise native reconstruction here; real hardware and a
checkpoint-enabled host are needed to verify driver restoration after a real image.

## Full checkpoint experiments

See [the runtime experiment](poc/runtime/README.md) for the guarded CRaC API and
controlled heap fixture. A stock JDK reports `UNSUPPORTED` rather than pretending
to restore a process. Do not use manual CRaC restoration on a Minecraft heap.
The [managed checkpoint experiment](poc/CHECKPOINT.md) adds a host doctor,
private image fingerprints, and capture/restore validation for the repository's
fixed cooperative heap fixture. It requires explicit fixture attestation and
refuses arbitrary Minecraft commands. A real restore must pass heap and fresh
readiness checks before an image can be published.

| Technology | Prototype status |
| --- | --- |
| Dynamic CDS | Implemented and exercised on stock JDK 25; class metadata only |
| OpenJDK CRaC | Lifecycle bridge, fixed heap fixture, and managed image validation; real OS restoration requires a CRaC JDK and supported host |
| CRIU | Real host capability probe; the current container's missing checkpoint capabilities prevent image capture |
| Leyden | Implemented JDK 25 AOT training/reuse; transformed loader classes and Minecraft readiness gains remain unverified |
| GPU/audio/window reconstruction | Actual GLFW/OpenGL/OpenAL replay fixture; software backend tested, Minecraft and hardware checkpoint integration pending |
| Azul Warp / persistent application JIT machine code | No backend or persistence verification yet |
| Native compilation/fusion | Stage 2; outside this prototype |

Before a reusable Minecraft process image is possible, adapters must quiesce
threads, exclude credentials and world/session state, detach external handles,
recreate graphics/audio/input resources, refresh launcher sessions, and validate
image compatibility. Those checks are application-specific and cannot be inferred
from a readiness callback.

## Observed validation

On October 10, 2026, the JDK 25 build and 43 Python integration/validation tests
passed, along with 71 runtime assertions and 11 native CPU/thread assertions.
The explicit native trial passed 100 reconstruction cycles, eight injected partial
allocation failures, and sequential adapter lifetimes on Xvfb/Mesa llvmpipe with
OpenAL Soft's null backend. An upstream OpenJDK 24 CRaC request reached its native
checkpoint engine but failed under this container's restrictions; the native
fixture rebuilt its resources and passed readback checks after that failure.
No process image was successfully restored.

The Fabric dedicated server also reached its readiness marker, answered a real
Minecraft status query, and stopped gracefully. Its second measured launch reported
about 8.41 seconds from agent entry to server readiness. This is a functional smoke
test, not evidence of a Minecraft startup speedup. The clock is shared across agent
and Fabric classloaders; an isolated-classloader regression covers that handoff.
Graphical Minecraft main-menu readiness remains unverified.

Further native checkpoint testing needs a Linux host on which the chosen CRaC
engine and `criu check` succeed. This container has no effective or bounding Linux
capabilities, an active seccomp filter, and no physical GPU/audio devices. Network
allowlist changes can enable runtime downloads, but cannot supply checkpoint
permissions or those devices. Physical-driver replay needs separate GPU/audio
testing in addition to the software-backend trial.

## License

CC0-1.0, inherited from the Fabric template. Runtime APIs and the wrapper are
independently usable under the same license.
