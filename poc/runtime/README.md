# Experimental Stage 1 runtime

This dependency-free Java 17 module contains a read-only Java agent, explicit readiness milestones,
a cooperative bridge to **real `jdk.crac`**, and a deterministic launcher test fixture.
It neither transforms Minecraft classes nor requests checkpoints automatically.

Build with the repository's Java 25 toolchain using `./gradlew :poc-runtime:build` (the repository settings map
`:poc-runtime` to this directory). The artifact is `poc/runtime/build/libs/loadonce-runtime.jar`.
`verifyRuntime` runs dependency-free contract tests for report publication, safety vetoes,
resource ordering, and failure recovery. These tests do not emulate a process checkpoint.

## Agent and readiness

```sh
java -javaagent:poc/runtime/build/libs/loadonce-runtime.jar \
  -Dloadonce.report=/absolute/private/run/ready.json -Dloadonce.runId=unique-launch-id \
  -jar poc/runtime/build/libs/loadonce-runtime.jar --client
```

The fixture initializes 2,048 CPU-side resources, calculates their checksum, marks readiness,
and exits cleanly. `--server` chooses the server milestone and is the default. The fixture is a
launcher smoke test, not a Minecraft startup benchmark.

Integrations call `Readiness.markClientMenuReady()` only when the main menu is interactive, or
`Readiness.markServerReady()` only after current world data is loaded and the server can accept
connections. The first milestone wins. The agent records time from `premain` and shares that clock
through `loadonce.agentStartNanos` with loader-isolated copies of the readiness API. An integration
without the agent records from first loading `Readiness`. This timing excludes native process
creation and earlier JVM initialization, so a launcher should also measure its own elapsed time.

Reports contain `schemaVersion`, `runId`, `pid`, `milestone`, `elapsedNanos`, and `observedAt`.
Publication uses a temporary file in the destination directory followed by an atomic replacement.
Filesystems without atomic replacement produce no report. Reporting failures never intentionally
stop the application. A fresh report destination and run ID are required for each ordinary launch.

## Guarded CRaC fixture

```sh
java -jar poc/runtime/build/libs/loadonce-runtime.jar --capabilities
```

A stock JDK reports `jdk.crac` unavailable. Classpath emulation such as `org.crac` is not used.
API availability alone does not prove that the kernel, privileges, CRIU, or native resources allow
a checkpoint. This module targets Java 17 bytecode and therefore requires a **CRaC-enabled JDK 17
or later** to execute the optional checkpoint fixture. The Minecraft/Fabric project still requires
its pinned Java 25 toolchain; the standalone runtime does not change those requirements.
Use the same compatible JDK build and environment to capture and restore.

On a compatible real CRaC JDK with its checkpoint engine installed, the explicit fixture path is:

```sh
java -XX:CRaCCheckpointTo=/absolute/private/fixture-image \
  -javaagent:poc/runtime/build/libs/loadonce-runtime.jar \
  -jar poc/runtime/build/libs/loadonce-runtime.jar --checkpoint --fixture-safe --server

java -XX:CRaCRestoreFrom=/absolute/private/fixture-image
```

`--fixture-safe` attests only to this fixture's application heap. It never authorizes a Minecraft
checkpoint. The demo prints the initialized heap checksum before checkpointing; after actual
restoration it checks the same heap checksum and adapter event ordering, prints
`RESTORED fixture heap verified resources=2048 sha256=...`, then emits readiness.
An unsupported or failed attempt exits with status 3. Omitting the attestation exits with status 2.
The JDK/engine may still veto its own native resources. No real checkpoint or restore has been
validated on the stock JDK used for development.

## Integration boundary and resources

`new CheckpointCoordinator()` registers a resource with the real CRaC global context when available,
but its default boundary guard vetoes every checkpoint. An audited integration can provide a
`Supplier<CheckpointSafety>` and register `ResourceAdapter` instances. Each adapter defaults to
veto until it explicitly implements `supportsReusableCheckpoint()` as true.

The boundary guard must attest, both before and after resource preparation, that the heap represents
reusable initialization and contains **no world state, authenticated session state, credentials,
secrets, or live application external handles**. The declaration cannot detect unsafe state;
the integration owns that audit, including every mod and plugin. Gameplay, network connections,
GPU/OpenGL/GLFW/OpenAL state, launcher tokens, and loaded server worlds have no implemented adapters
and must be refused. Do not represent a current menu or running server as safe merely because it is ready.
JDK-owned native resources additionally depend on the real CRaC implementation's own lifecycle support.

Preparation runs in registration order. Restoration or failed preparation recovery runs in reverse
order, including the adapter whose preparation partially failed. `afterRestore()` must therefore
handle partial preparation, rather than assuming a successful checkpoint. All prepared adapters are
given a recovery attempt; failed recovery disables further preparation. Duplicate CRaC callbacks
following a veto cannot repeat completed resource recovery. Retain the coordinator strongly for as
long as it is active: CRaC contexts may hold resource proxies weakly.

A successful restore resets the readiness clock before adapter reconstruction. Restored launches
must reattach a fresh report destination and launch ID through an audited CRaC/launcher integration;
the fixture commands above do not implement that mechanism. If the captured launch ID is reused,
the reporter generates a new ID. Capture and restore alone never refresh authentication or world
data, and this prototype supplies no generic Minecraft restore launcher.
