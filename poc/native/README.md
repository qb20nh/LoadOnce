# Experimental native resource replay

This isolated Java 17+ / LWJGL 3.4.3 fixture releases GLFW windows, callbacks, OpenGL
contexts and objects, and OpenAL devices, contexts, buffers and sources, then
recreates them from heap-only texture, vertex, shader and PCM inputs. It is not
automatically attached to Minecraft. Current Minecraft 26.3 uses SDL; the GLFW
fixture models older clients and other LWJGL applications. Linux x64 is the only
native classifier and platform currently tested.

## Run the real native checks

Provide a working X11 display and Mesa or a working GPU driver. A hidden window
still requires a display. For software-only CI, launch Xvfb and set
`LIBGL_ALWAYS_SOFTWARE=1` and `GALLIUM_DRIVER=llvmpipe`.

```sh
ALSOFT_DRIVERS=null ./gradlew :poc-native:verifyNative -PnativeCycles=20
```

The Gradle task selects OpenAL Soft's real null output backend. It checks actual
texture and vertex-buffer readback, shader-rendered framebuffer pixels, recreated
GLFW resize callbacks, and OpenAL buffer format and playing-source state. It
also injects failures after eight native construction stages, verifies cleanup,
and rebuilds again; tests wrong-thread calls, cache fingerprint mismatch, and
sequential adapter lifetimes. A failed native check fails the task. The ordinary
`check` task includes only CPU-data/thread invariant checks and does not imply
GPU or audio verification.

The headless path does not verify physical GPU drivers, display reconnects,
monitor changes, audible PCM output, audio-device reconnects, or Minecraft menu
readiness. It does not capture arbitrary game GL objects, streaming audio,
third-party callbacks, plugin threads, or unsupported JNI state.

Observed on this managed host: 100 reconstruction cycles and all eight injected
partial failures passed using Xvfb, Mesa 25.0.7 llvmpipe and OpenAL Soft null.
An actual upstream OpenJDK 24 CRaC checkpoint request reached the native release
boundary but failed with `CheckpointException`; native reconstruction and
readback succeeded on the rollback path. No native process image restoration
was verified on this host.

## CRaC experiment

The standalone runtime and native fixture target Java 17 bytecode so CRaC JDK
21/24 builds can run them; the Minecraft mod still targets Java 25. Use an actual
CRaC build and a host capable of its checkpoint engine.
The stock JDK fails explicitly. Build a classpath file first:

```sh
./gradlew :poc-native:writeClasspath
ALSOFT_DRIVERS=null "$CRAC_JAVA" --enable-native-access=ALL-UNNAMED \
  -XX:CRaCCheckpointTo=/absolute/fixture-image \
  -cp "$(cat poc/native/build/runtime-classpath.txt)" \
  io.loadonce.nativepoc.NativeFixture --cycles 1 --checkpoint --fixture-safe
"$CRAC_JAVA" -XX:CRaCRestoreFrom=/absolute/fixture-image
```

The display and native-library paths must remain compatible on restoration.
`--fixture-safe` applies only to this unauthenticated, world-free fixture and
does not grant permission to checkpoint a Minecraft process. The engine may
reject residual native threads or descriptors; no handles are ignored or
force-closed by CRaC configuration. A true restore must return through the
coordinator and pass the same native readback checks before reporting success.

The owner thread explicitly suspends native resources before asking CRaC to
checkpoint. ResourceAdapter callbacks validate the suspended state and schedule
restoration; after the checkpoint call returns, the owner thread creates new
contexts and uploads CPU assets. Real game integration must marshal this work
to the game's render/audio threads and audit every native owner.

LWJGL native **library mappings remain pinned**. In LWJGL 3.4.3, ALC10 stores
exported function addresses in static fields; unloading and reloading OpenAL
invalidates these addresses and caused a reproduced native crash. Code mappings
can be part of an execution image; they are not saved window/device/context
handles. Reconstructed numeric GL/AL identifiers may coincidentally equal old
ones, so correctness is checked by creating new contexts and reading their
contents, rather than asserting that handle numbers differ.

Shader binaries are retained as bounded heap byte arrays and reused only when
vendor, renderer, GL version and shading-language version match. Unsupported or
rejected binaries fall back to shader sources. This is a process-image CPU cache,
not a persistent on-disk cache shared between installations.
