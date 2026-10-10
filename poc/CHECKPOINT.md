# Controlled CRaC / CRIU fixture

`checkpoint.py` is an experiment runner for the repository's pure Java
`RuntimeDemo`. It accepts no Minecraft command, application arguments, launcher
tokens, or third-party runtime JAR. The runtime artifact must match the classes
in the local `poc-runtime` build and its expected main/agent manifest.
`--fixture-safe` attests to that reviewed repository build; matching a manifest
alone would not establish that arbitrary third-party bytecode is safe.

Build the runtime, then inspect capabilities:

```sh
bash ./gradlew :poc-runtime:build
python3 poc/checkpoint.py doctor --java /path/to/crac-jdk/bin/java
python3 poc/checkpoint.py doctor --java /path/to/crac-jdk/bin/java --check-criu
```

Doctor probes Java version and native CRaC VM flags, reports Linux privileges and
seccomp mode, and finds bundled or PATH CRIU. `--check-criu` explicitly runs a
native `criu check --no-default-config` with a 30-second limit. For a separately
unpacked CRIU distribution, pass `--criu /path/to/criu` and optionally
`--criu-library-dir /path/to/libraries`. Those options affect the standalone
capability probe; they do not replace the CRaC JDK's bundled engine.

Capabilities are **not** a successful capture or restore. This prototype
conservatively requires Linux, effective `CAP_SYS_ADMIN` or
`CAP_CHECKPOINT_RESTORE`, `CAP_SYS_PTRACE`, no seccomp filter, and a successful
CRIU check. These are restrictions of this runner, not a claim that every CRIU
deployment needs the same configuration. Doctor remains read-only except for
the explicitly requested native CRIU check and reports restrictions as JSON.

On a supported, appropriately configured host:

```sh
python3 poc/checkpoint.py capture --java /path/to/crac-jdk/bin/java \
  --fixture-safe --cache-dir /private/fixture-images

python3 poc/checkpoint.py restore --java /path/to/crac-jdk/bin/java \
  --fixture-safe --cache-dir /private/fixture-images
```

The Java fixture targets Java 17 bytecode and can run on a compatible CRaC JDK
17 or later. The selected JDK must also support the built artifact's actual
class version. Stock Oracle/OpenJDK runtimes without native CRaC flags are
refused before invoking capture. No simulated checkpoint engine is selected.

The captured Java process receives only a minimal fixed locale/PATH environment.
Inherited credentials, JVM option variables, and `CRAC_CRIU_*` engine overrides
are excluded. It loads only the fixed fixture and its readiness agent, using
the server marker for this CPU-only workload. No Minecraft or persistent world
is opened.

Images live in a private `0700` cache under a fingerprint of the runner, runtime
artifact, JDK binaries, kernel/platform/CPU, working directory, and fixed fixture
launch. Capture refuses an already existing entry. An unsuccessful experiment
can leave private uncommitted image files for diagnosis; no manifest marks them
as usable.

Publication requires all of the following:

- Native capture ends with the expected SIGKILL completion.
- Nonempty CRIU `inventory.img` and `pstree.img` exist.
- An actual controlled restore exits successfully and verifies the captured
  initialized-heap checksum and resource lifecycle.
- The restored process writes readiness with a fresh launch ID and destination.
- Image data and runtime fingerprints remain unchanged during verification.

The manifest is published atomically after that restore. All image data files
are hashed; operational `.log` files are excluded. Subsequent restoration
rejects missing, modified, or incompatible manifests/images. The runner resumes
the application once and never retries it after a failure.

Restore supplies fresh `loadonce.report` and `loadonce.runId` system properties
using the CRaC restore command. Upstream `jdk.crac.Core` applies those restored
properties before `afterRestore` callbacks. The fixture must demonstrate this
handoff through its new readiness report before an image qualifies for reuse.
This is a fixture experiment; it does not implement launcher authentication,
GPU reconstruction, or a reusable Minecraft snapshot.

Validation:

```sh
python3 -m unittest discover -s poc -p 'test_checkpoint.py' -v
```

These checks cover manifest/image mutation, unsafe paths, fixture identity,
readiness proof parsing, secret-free doctor output, and actual stock-JDK refusal.
Synthetic validator inputs are never passed to a restore process. Native
checkpoint/restore requires a separately supported host and is not inferred
from these tests.
