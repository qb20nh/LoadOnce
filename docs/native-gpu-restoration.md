# Native Linux GPU snapshot experiment

A VM is not required for display isolation or GPU-resource restoration. A VM can
capture the application's memory, virtual device driver and supporting services
together, but its renderer must still support snapshots. Native restoration must
also preserve the application's display/audio connections, shared mappings and
GPU transport. This experiment tests the renderer component separately.

## Verified result

On 2026-10-11, `GpuResourceSnapshotSmoke.java` captured and restored resources on
the NVIDIA GeForce RTX 3060 Ti in three fresh JVM processes. The renderer itself
reported the selected NVIDIA device and Vulkan 1.4.351 through its debug callback.
The test did not connect to a display, sound service, network or desktop session.

The snapshot contains a 64x64 RGBA image in GPU memory and a separate 4096-byte
host-memory blob. Each restore reads and compares the original image pixels
before uploading anything, verifies the blob, then writes and reads a different
pattern and completes a fence. Original resource IDs and the hashes of all three
snapshot files remain unchanged across the three restores. The host-memory blob
is not counted as GPU-memory evidence.

Retained evidence:

- `build/local-tools/gpu-snapshot/resource-run-2/result.properties`
- `capture.properties`, `restore-1.properties` through `restore-3.properties`,
  and corresponding bounded logs in that directory
- provider revision `07ee40efb0e7037a9a9b7fe59071e7c4997e7cbe`
- provider library SHA-256
  `e32abe93a246ed38d32a86edfacf5a4bdfe011b3e225350db3d2bb159786b8d4`

These are resource-fixture timings, not Minecraft cold/warm boot timings:

| Phase | Fresh JVM through phase exit | Renderer initialization | Snapshot API |
| --- | ---: | ---: | ---: |
| Capture | 1196 ms | 264 ms | save 1.22 ms |
| Restore 1 | 1164 ms | 238 ms | restore 2.52 ms |
| Restore 2 | 1165 ms | 242 ms | restore 2.59 ms |
| Restore 3 | 1155 ms | 237 ms | restore 2.54 ms |

The tiny fixture contains 29,298 bytes of snapshot data. These timings do not
predict game startup or restoration performance.

Separately, the provider's GLES context snapshot tests passed restored 2D mipmap
pixel comparisons, cubemap pixel comparisons, and buffer binding/size/usage
checks on the RTX. They reconstruct contexts within one process. The buffer test
does not compare buffer contents. Evidence is
`build/local-tools/gpu-snapshot/gfxstream-private-gpu-resources-test.log` and
`/tmp/loadonce-native-display-12452094774479672174/command.log`.

## Reproduce the resource fixture

This uses Java 25 FFM for coordination and the external renderer's C API. It does
not change the Java runtime selected by Minecraft or enable LoadOnce checkpoints.
The local build needs CMake, a C++ compiler, EGL/GLES/Vulkan/DRM/XCB headers and
protobuf development files. No Python or system driver installation is used.

From this repository, create an ignored provider checkout and select the exact
revision before applying the small standalone build/validation patch:

```sh
git clone https://github.com/google/gfxstream.git build/local-tools/gpu-snapshot/gfxstream
git -C build/local-tools/gpu-snapshot/gfxstream checkout --detach 07ee40efb0e7037a9a9b7fe59071e7c4997e7cbe
git -C build/local-tools/gpu-snapshot/gfxstream apply "$(pwd)/tools/native/gfxstream-snapshot-validation.patch"
cmake -S build/local-tools/gpu-snapshot/gfxstream \
  -B build/local-tools/gpu-snapshot/gfxstream-build \
  -G 'Unix Makefiles' -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_STANDALONE=ON -DENABLE_VKCEREAL_TESTS=ON \
  -DDEPENDENCY_RESOLUTION=SYSTEM -DGFXSTREAM_SNAPSHOT_FRONTEND=ON \
  -DCMAKE_CXX_FLAGS=-DGFXSTREAM_BUILD_WITH_SNAPSHOT_SUPPORT
cmake --build build/local-tools/gpu-snapshot/gfxstream-build \
  --target gfxstream_backend --parallel 2
/usr/lib/jvm/java-25-openjdk/bin/java -Xmx128m --enable-native-access=ALL-UNNAMED \
  tools/GpuResourceSnapshotSmoke.java \
  "$(pwd)/build/local-tools/gpu-snapshot/gfxstream-build/libgfxstream_backend.so" \
  "$(pwd)/build/local-tools/gpu-snapshot/NEW-resource-run"
```

Use a new output directory on each run. The tool requires Linux x86-64, a private
caller-owned provider library and the NVIDIA ICD at
`/usr/share/vulkan/icd.d/nvidia_icd.json`. It fails if the provider does not select
the RTX 3060 Ti or enable VulkanSnapshots. Each child receives fresh private
HOME/TMPDIR/XDG directories, a cleared environment, a 120-second deadline and a
3 MiB log limit. Generated directories are mode 0700 and files mode 0600.

The shutdown/start race regression runs without loading a GPU provider:

```sh
gpu_verify_dir="$(mktemp -d /tmp/loadonce-gpu-resource-verify-XXXXXX)"
/usr/lib/jvm/java-25-openjdk/bin/javac -d "$gpu_verify_dir" \
  tools/GpuResourceSnapshotSmoke.java tools/GpuResourceSnapshotSmokeContract.java
/usr/lib/jvm/java-25-openjdk/bin/java -cp "$gpu_verify_dir" \
  GpuResourceSnapshotSmokeContract
```

Shutdown closes the child-registration gate under the same lock used to start
and register processes. A shutdown between phases cannot admit another renderer
child after cleanup has finished. The regression failed before this fix, then
passed; the fixed coordinator also passed capture and three fresh GPU restores.

## Remaining application boundaries

This result does not restore an OpenGL shader/draw context, a Java application,
Minecraft, mods, a window, input, sound or networking. The normal Minecraft
checkpoint veto remains enabled.

The desktop-GL GLES test build omits ANGLE's shader translator; shader compilation
failed before checkpointing and the draw case aborted. A native EGL experiment
also failed context creation. Both failed runs are retained in
`gfxstream-private-gpu-test.log` and `gfxstream-private-gpu-egl-test.log`.

The pinned standalone frontend passes null texture saver/loader pointers into
its full GLES renderer save/load path. Its classic GLES pipe context also has
unimplemented host-pipe snapshot/restore. Enabling the API macros alone does not
solve these. Native Zink over the snapshot-capable Vulkan renderer is another
route, but still needs verified presentation and GPU-transport reconnection.

Kumquat's wrapper keeps client sockets, allocator state and resource/shared-memory
mapping state outside the gfxstream snapshot. Its existing save/restore RPC
targets the same server and defaults to shared `/tmp`. A fresh native application
restore must preserve that wrapper state and substitute connected sockets and
matching shared mappings, using private storage. CRIU's inherited Unix socket
and memfd support is a possible transport mechanism, not a completed integration.

The pinned Kumquat server also builds locally with Cargo's `gfxstream` feature,
without Python or a system installation. The private pkg-config description must
define `GFXSTREAM_UNSTABLE`; merely setting `GFXSTREAM_PATH` skips that detection
and disables the Rust snapshot implementation. The release build linked the
existing backend, emitted `gfxstream_unstable`, imported both snapshot and restore
symbols, and passed `--help`. This is build/link validation only: no server or
Minecraft client has been restored through it. Evidence is retained in
`build/local-tools/gpu-snapshot/kumquat-build.log` and
`rutabaga-target/release/build/rutabaga_gfx-*/output`.

Apply `tools/native/kumquat-private-storage.patch` to Rutabaga revision
`ced6af5b1135173c269a037e06213e8a263de271` before runtime tests. This transport
patch requires `--snapshot-directory` pointing to an existing caller-owned
mode-0700 directory, validates it before save/restore, and refuses existing
socket paths without deleting them. It removes the shared `/tmp` snapshot
fallback. Four offline storage tests and the patched release build passed; clean
apply and reverse checks passed. Allocator, resource identity, backing FD and
CPU restoration are still unimplemented. Imported-fence compatibility between
the pinned Rust and C interfaces has not been verified.

The native client still needs a Mesa gfxstream ICD built with
`-Dvirtgpu_kumquat=true`. Arch's matching `vulkan-gfxstream` package omits that
option and builds a null Kumquat transport stub. Its installation would not
unblock this route. A private client-driver build needs Mesa's Meson build tools;
it remains pending clarification of the handover's no-Python constraint. No
system graphics driver or desktop session has been changed.

The observed CRIU `[uprobes]` mapping is contingent kernel instrumentation state.
Current CRIU rejects it unless explicitly told to skip it. Fresh owned JVMs must
be checked at checkpoint time; this experiment does not remove host probes or
relax that rejection.

Provider references: [snapshot design](https://github.com/google/gfxstream/blob/07ee40efb0e7037a9a9b7fe59071e7c4997e7cbe/docs/snapshot.md),
[GPU-image save/load](https://github.com/google/gfxstream/blob/07ee40efb0e7037a9a9b7fe59071e7c4997e7cbe/host/vulkan/color_buffer_vk.cpp),
[frontend snapshot implementation](https://github.com/google/gfxstream/blob/07ee40efb0e7037a9a9b7fe59071e7c4997e7cbe/host/virtio_gpu_frontend.cpp),
[native Kumquat route](https://github.com/magma-gpu/rutabaga_gfx/blob/ced6af5b1135173c269a037e06213e8a263de271/README.md),
[Kumquat Cargo feature detection](https://github.com/magma-gpu/rutabaga_gfx/blob/ced6af5b1135173c269a037e06213e8a263de271/build.rs),
[Arch Mesa build recipe](https://gitlab.archlinux.org/archlinux/packaging/packages/mesa/-/raw/main/PKGBUILD),
[Mesa 26.2.4 Kumquat build selection](https://gitlab.freedesktop.org/mesa/mesa/-/blob/mesa-26.2.4/src/gfxstream/guest/platform/kumquat/meson.build),
[CRIU uprobes handling](https://github.com/checkpoint-restore/criu/blob/v4.2.1/criu/proc_parse.c).
