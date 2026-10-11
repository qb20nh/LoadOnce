# Initialized-state continuation prototype

Recorded on 2026-10-11. This document tracks the stronger continuation contract separately from the [early-JVM prototype](prototype-status.md#linux-minecraft-early-jvm-prototype).

## Contract

The intended warm launch resumes an already initialized game and its external mods. Their existing heap objects, thread stacks, native resources and open-file positions must continue without rerunning initialization or requiring mod-specific checkpoint callbacks. The pause should be observable as elapsed time when execution resumes.

The ordinary early-JVM prototype captures before Fabric, mods and Minecraft execute. Its warm launches initialize all of them freshly. That remains useful bootstrap evidence, but it does not satisfy initialized-state continuation.

Supporting externally authored mods means avoiding a required checkpoint adapter for each mod. It does not guarantee that every mod combination works, that existing mod bugs disappear, or that every external resource can be captured. The snapshot boundary must cover every participant whose state the initialized application depends on: the application, its helpers, display server, kernel resources and any relevant external peers. A host GPU driver, audio daemon, remote service or tracer outside that boundary remains a dependency requiring separate handling.

Admission must reject an unsupported boundary before reuse. Once capture or restoration has been attempted, failure must remain visible; the controller must not replay application initialization as a hidden cold fallback. Passing ordinary continuation checks, reaching a cold menu, or restoring a pre-application JVM is insufficient evidence of an initialized-game restore.

## External CRIU experiment

`OpaqueProcessSmoke` uses the stock JVM and external CRIU, without invoking application CRaC callbacks. It captures a helper and its initialized application, then performs three independent restores of that original image in fresh private Linux namespaces. Its CPU fixture retains an opaque object graph, a parked worker's stack locals, a running background counter and an open file positioned after an already consumed prefix.

The Minecraft variant includes its private Xvfb server in the captured process tree. Capture begins after the initialized menu has rendered and accepted input and the additional fixture mod has published its initialized-thread witness. The harness selects software rendering and null audio, masks hardware device nodes inside its own mount namespace, and checks actual descriptor device identities. These restrictions exercise a bounded software environment; they do not establish host GPU or system-audio restoration.

Successful Minecraft acceptance requires fresh evidence for each generation: the retained menu objects, continued fixture-mod thread and counter, the complete menu-input/world-save/reopen probe, and successful helper/application completion. Earlier success text in an accumulated log cannot satisfy a later generation.

## Current local evidence

| Experiment | Result | Evidence directory |
| --- | --- | --- |
| Stock-JVM initialized CPU state, external CRIU | Capture and three independent restores passed | `build/opaque-process-validation/opaque-7485095934757553048/` |
| External CRIU initialized Minecraft menu, private Xvfb and software renderer | Menu rendering/input and fixture readiness passed; capture failed on a kernel `[uprobes]` mapping | `build/opaque-minecraft-validation/opaque-12862535437653780480/` |
| Whole-VM initialized CPU state | Capture and three restores in fresh QEMU processes passed | `build/vm-state-validation/guest-image-8447268428299591986/` |
| Whole-VM initialized Minecraft and additional fixture mod | Cold world acceptance, initialized-menu capture and three independent warm world acceptances passed | `build/vm-minecraft-validation/guest-image-3262078034132455089/` |

The CPU results retained heap witness `e96c3db7-8c38-4897-9dd6-2514beb6fa72`, object identity `1234776885`, initialization count `1`, and parked-stack witness `822415e9-e5e0-40f7-a36c-de3ab0bf50e4` across all three restores. Each background counter progressed beyond its initialized value. Each restored open file continued from offset `17` to `43`. Observed polling gaps were `1390`, `2848` and `4249` milliseconds. Inspect `ready.properties`, `result-1.properties` through `result-3.properties`, and `helper-completed-1.properties` through `helper-completed-3.properties` in the CPU evidence directory.

The stock-JVM fixture protocol passed 29 checks. Final source verification passed with `./gradlew build runtimeContractTest agentContractTest launcherContractTest --offline --no-daemon --max-workers=1 -Dorg.gradle.parallel=false`. Those checks cover ordinary continuation and rejection behavior; the snapshot runs supply separate process-image and VM evidence.

The whole-VM CPU run used stock JDK 25, KVM, 512 MiB of guest RAM and one CPU, with checksum-verified QEMU 11.1.1 extracted under `build/local-tools/qemu/root/`. Its guest reused the locally available `6.18.55-1-cachyos-lts` kernel and installed host runtime; it required no guest OS download or system installation. The read-only root image was `88,150,016` bytes (84.07 MiB), the initrd was `7,956,250` bytes (7.59 MiB), and the qcow2 file containing the VM snapshot was `193,462,272` bytes. These sizes describe this fixture rather than a general Minecraft capacity requirement.

All three fresh VM restores retained heap witness `d34efa9b-a6b3-4ec0-a2fe-7c6766a6fa6a`, object identity `2094548358`, initialization count `1`, and parked-stack witness `525a1933-04fe-4d70-9086-ed83f8791a3b`. Each background counter continued from `4` to `7`, and each open file continued from offset `17` to `43`. Observed wall-clock gaps were `2637`, `5660` and `8669` milliseconds after explicit guest OS clock correction. Inspect `guest-build.properties`, `ready.properties`, `result-1.properties` through `result-3.properties`, and the capture/restore serial and QMP logs in the VM evidence directory.

The external CRIU Minecraft evidence directory contains `probe-ready.properties` and `mod-ready.properties`. Its `image/dump.log` reports `PID 146 has uprobes vma` and `Collect mappings (pid: 146) failed`, followed by `Dumping FAILED`. That raw CRIU experiment has no accepted Minecraft warm restore.

The whole-VM Minecraft run completed with `completion.properties` recording `status=passed`, `target=minecraft` and `restore_cycles=3`. Its private run log is `build/vm-minecraft-validation/run-1791650564569.log`. The same initialized-menu probe UUID `eb243e3d-c56f-44e4-ba7d-15fe31627c32`, mod UUID `d0d7c605-e071-471f-a2cd-97f33ce223b4` and mod-thread UUID `128885a6-2b9a-4454-aae0-3b7ef667edd3` survived all three restores. Probe and mod initialization counts remained `1`; client, menu, window, renderer, render-target and mod-thread identities/handles matched readiness evidence. The original fixture thread counter progressed from `488` to `489` after release in each independent restore. Each generation passed framebuffer checks, SDL-injected menu/world input, block saving and world reopen. Inspect `ready.properties`, `result-1.properties` through `result-3.properties`, `completion.properties` and the serial/QMP logs in the Minecraft VM evidence directory.

This guest's read-only root image was `686,813,184` bytes, its initrd was `7,956,254` bytes, and its qcow2 snapshot file was `1,943,797,760` bytes. `guest-build.properties` records hashes of the packed root, initrd and kernel. The restored menu's wall-clock deltas were `7890`, `24892` and `42890` milliseconds, while corresponding monotonic deltas were approximately `40.44`, `40.17` and `40.62` milliseconds. The guest OS explicitly corrected wall time before continuation; guest monotonic time remained frozen during the VM pause. This is evidence of those specific clock semantics, not universal timer transparency or a measured startup improvement.

These are local results from ignored build artifacts, not a hosted CI or release acceptance claim. The earlier CPU VM state/restore qcow2 payloads were removed to reclaim disk space; their logs, manifests and result properties remain as historical evidence, while rerunning the task creates new images. Cleaning `build/` removes the retained evidence.

## Reproduce the optional experiments

For the external CRIU tasks, use Linux with compatible unprivileged user/PID/mount/network/IPC/UTS/cgroup namespaces, the stock JDK required by this project, and installed `criu`, `unshare`, `ip`, `mount` and `setsid`. These tasks neither install OS tools nor download an Xvfb executable. Keep the evidence paths private and use the dedicated offline profile rather than personal game data. The VM tasks have their own KVM and image-builder prerequisites below.

Run the protocol suite and the CPU snapshot experiment explicitly:

```bash
./gradlew launcherContractTest
./gradlew opaqueStateSmoke
```

Build the additional mod and initialize the default marker-owned game profile before installing the fixture:

```bash
./gradlew minecraftPrototypeFixtureMod writeMinecraftPrototypeSpec
cp build/minecraft-prototype/loadonce-additional-mod-fixture.jar \
  build/minecraft-prototype/game/mods/
./gradlew opaqueMinecraftSmoke \
  -PloadonceXvfb=/absolute/path/to/Xvfb
```

The copy is a manual fixture installation into `build/minecraft-prototype/game/mods/`, after the spec task has created and checked the isolated `.loadonce-prototype` profile marker. `opaqueMinecraftSmoke` does not install the fixture mod automatically. A missing fixture cannot satisfy initialized-mod readiness. The recorded external CRIU Minecraft attempt failed at capture as described above; this command is an experimental diagnostic, not a known successful warm-launch recipe.

For the whole-VM CPU fixture, provide the root of an existing extracted QEMU toolchain. The successful local run used:

```bash
./gradlew vmStateSmoke \
  -PloadonceQemuDirectory=/home/cash/git/loadonce/build/local-tools/qemu/root
```

The current VM builder is specific to the locally installed `6.18.55-1-cachyos-lts` kernel and its matching SquashFS module. It requires `/boot/vmlinuz-linux-cachyos-lts`, `/usr/lib/modules/6.18.55-1-cachyos-lts/kernel/fs/squashfs/squashfs.ko.zst`, `/usr/lib/initcpio/busybox`, the current JDK and its shared-library dependencies, and the local `ldd`, `zstd`, `mksquashfs`, `find`, `cpio` and `gzip` utilities. The controller additionally requires KVM access, compatible QEMU firmware/modules and private snapshot storage. It prefers reflinks and falls back to sparse byte copies after reserving space for all remaining copies; storage without reflinks needs enough space for full copies. Another host needs an explicitly adapted builder; this is not a portable guest OS installer. The builder records the chosen guest files and hashes in `guest-build.properties`.

`vmMinecraftSmoke` uses the same manually installed fixture and marked profile. Compilation and dependency preflight passed for the 14 checked Linux native ELF libraries; foreign-platform JNI binaries were excluded. The local run then passed one cold world check, initialized-menu capture and three warm world checks:

```bash
./gradlew vmMinecraftSmoke \
  -PloadonceQemuDirectory=/absolute/path/to/extracted/qemu/root \
  -PloadonceXvfb=/absolute/path/to/Xvfb
```

The Minecraft guest uses 3 GiB of RAM and two CPUs, versus 512 MiB and one CPU for the CPU fixture. The current controller requires at least 3 GiB of free space after building the Minecraft guest image; actual capacity still depends on the image and snapshot contents.

This remains an explicitly selected test harness. It is not integrated into the installed launcher or an authenticated session. Minecraft runs headlessly on private Xvfb with software rendering, null audio and SDL-injected input. The guest's test game directory and world fixture live in tmpfs: save/reopen acceptance tests guest-local state during that run, not persistence of a user's world across guest shutdown. Each independent restore starts from the captured guest state. This boundary must not be advertised as production support for arbitrary external mods.

The OS snapshot smoke tasks run only when explicitly selected. A passing `build` or contract suite does not substitute for running and inspecting the corresponding snapshot experiment.

## Verified blockers and resource limits

JNA normally deletes its extracted JNI dispatch library after loading it. Unprivileged CRIU cannot always open `/proc/<pid>/map_files` and falls back to opening the mapped pathname; a deleted pathname can therefore stop capture. The Minecraft harness uses `-Djnidispatch.preserve=true`, which JNA 5.17.0 supports for both immediate deletion and `deleteOnExit`. This preserves the library file without an application callback. See [JNA's exact deletion guard](https://github.com/java-native-access/jna/blob/5.17.0/src/com/sun/jna/Native.java#L1045-L1052) and [CRIU's unprivileged mapping fallback](https://github.com/checkpoint-restore/criu/blob/v4.2.1/criu/proc_parse.c#L350-L377).

The subsequent `[uprobes]` failure is a different boundary. CRIU's `--allow-uprobes` option skips that kernel mapping and requires the option during both capture and restore. Active source probes must still be active after restoration; removing them can make the restored process receive SIGTRAP at an instrumented instruction. Enabling the option does not safely remove external instrumentation or produce a self-contained image. The current harness preserves this failure rather than enabling it silently. See [CRIU 4.2.1's documented restore dependency](https://github.com/checkpoint-restore/criu/blob/v4.2.1/Documentation/criu.txt#L468-L490).

Other resources can impose further limits even with CPU rendering. Native libraries may create deleted executable mappings, shared memory, sockets or kernel objects. Disabling GPU plugins or selecting a software renderer does not prove those resources are snapshot-compatible. Unknown descriptors must not be closed or exempted merely to make capture pass.

## Whole-VM boundary

A whole-VM boundary can include the guest kernel and virtual devices alongside the initialized application and its peers. QEMU documents VM snapshots as covering CPU state, RAM, device state and writable disks; its internal snapshots require a writable non-removable qcow2 device. This is a broader boundary than a process image, while host resources and remote peers still require explicit treatment. See [QEMU's VM snapshot documentation](https://www.qemu.org/docs/master/system/images.html#vm-snapshots).

The QMP controller and guest-image builder passed both the CPU fixture and the Minecraft/additional-mod fixture after capture at initialized readiness and three restores into fresh QEMU processes. The controller requires retained-state witnesses and successful guest/application completion. It accepts the guest's own shutdown after completion and cleans up its process and owned sockets even when protocol-channel cleanup fails. These results establish the tested headless software-rendered fixture boundary. Interactive frontend behavior, installed-launcher integration, authenticated sessions, host GPU/audio and other external mod sets remain separate validation gates.

The guest OS clock adjustment before releasing the fixture is explicitly a wall-clock operation, performed without an application callback or repeated application initialization. It is reported separately from restoration. A passing wall-clock gap does not establish transparency of monotonic clocks, scheduled timers, timeouts or every mod's time-dependent behavior. The Minecraft VM test covers software rendering, injected input and the included fixture mod; null audio and guest-local world saves do not establish system-audio restoration or durable user-world persistence.
