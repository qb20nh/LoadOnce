# Isolated Fabric modpack testing

The current test targets are two smaller packs: Fabulously Optimized for visual
and performance mods, and Spectral Creation for content and quests. Their pinned
archives, installed files, and public Minecraft runtimes passed preparation
checks. This is separate from cold world and warm restoration acceptance.

Both packs passed real-GPU cold menu and world checks on the RTX 3060 Ti,
including observed keyboard, mouse, and raw camera input, world save/reopen,
known game sound captured through private audio, and normal exit. Fabulously
Optimized also passed loopback Minecraft status and nonce-ping checks in an
owned network namespace. A full second-client join and authenticated multiplayer
remain unverified; Spectral networking was not tested. Modpack GPU warm restoration
remains blocked, so full cold-and-warm acceptance is incomplete.

The [native GPU snapshot experiment](native-gpu-restoration.md) now passes GPU
image and host-memory resource restoration in three fresh processes on the RTX.
It is a renderer component check; Minecraft, its graphics context and its
connections have not been restored by that experiment.

End-user acceptance requires the full modpack using the real GPU, working input,
world loading and save/reopen, game audio, and usable network connections after
both cold and warm starts. The current software VM frontend does not meet this
gate. Its accelerated GPU checkpoint path remains unresolved.

The generic `VmOpaqueSmoke` frontend is experimental. It launches the original
Fabric client without the Minecraft 26.3 diagnostic bootstrap or probe.
Fabulously Optimized passed a software-rendered cold world cycle and three
independent initialized menu restores. Those tests used disabled audio and VM
networking, so they remain diagnostics. Spectral software runtime diagnostics
remain incomplete. The existing Minecraft 26.3 diagnostic is a separate
software-rendered test.

## Selected releases and current results

These are reproducible version pins, not a claim about the latest releases or
largest packs. Counts refer to physical client mod JARs, including overrides;
the count shown in a pack's menu can differ.

| Target | Minecraft / Java | Fabric Loader | Mod JARs | Prepared public runtime | Local result |
| --- | --- | --- | ---: | --- | --- |
| [Fabulously Optimized 6.5.0](https://modrinth.com/modpack/fabulously-optimized/version/N276l2ON) | 1.21.1 / 21 | 0.19.3 | 48 | 65 libraries/client JARs; 3,888 asset objects | Preparation and software diagnostics passed; real-GPU cold world, private audio, raw camera input, and loopback status/ping verified; full client join, authenticated multiplayer, and warm acceptance incomplete |
| [Spectral Creation 7.1.1](https://modrinth.com/modpack/spectral-creation/version/iJhfeFZL) | 1.20.1 / 17 | 0.18.4 | 146 | 61 libraries/client JARs; 3,575 asset objects | Preparation passed; real-GPU cold world, raw camera input, quest UI, private audio, save/reopen, and normal exit verified; network and warm acceptance incomplete |

Fabulously Optimized includes Sodium, Iris, Lithium, and other visual or
performance improvements. It does not bundle a shader pack. Spectral Creation
includes Create 6.0.8.1, Spectrum 1.8.12, and Modonomicon 1.79.3, with its bundled
journal, quest assets, and advancements. Its 146 JARs comprise 145 indexed files
and one override. It does not require the separate Prominence FTB downloads.

Java is selected for the Minecraft version, rather than for LoadOnce itself.
The 1.20.1 profile uses Java 17; [Mojang's 1.20.1 metadata](https://piston-meta.mojang.com/v1/packages/760a390e336b2b6202ec5906757cee3a929a7c85/1.20.1.json)
declares major version 17. The 1.21.1 profile uses Java 21. Java 25 is used for
the existing LoadOnce diagnostic and outer test controller; it is not substituted
for these packs' selected runtime. Each private profile overrides desktop JVM
arguments with `-XX:+UseG1GC` and keeps the pinned loader, mods, configurations,
resource packs, and scripts.

## Storage and memory

The private test root is
`/run/media/cash/Windows/LoadOnce-fabric-tests-2026-10-11`, on the writable
`ntfs3` mount `/run/media/cash/Windows`. The directory has mode 0700 and test
artifacts are private. The second unlocked Windows volume refused writes because
its NTFS filesystem was dirty; no forced mount or repair was performed.

This NTFS mount does not support reflinks. Snapshot cloning first tries a
reflink, then uses a sparse byte copy with a free-space reserve. Real copies
consume disk space. Check current capacity before each image or checkpoint;
installation preflight is not a guarantee that a later VM will fit. `/tmp` is
memory-backed and is unsuitable for large pack payloads or VM snapshots.

| Profile | Test Java heap | Experimental guest RAM | Minimum host `MemAvailable` for that guest |
| --- | ---: | ---: | ---: |
| Fabulously Optimized | 1,536 MiB | 3,072 MiB | 4,096 MiB |
| Spectral Creation | 2,304 MiB | 5,120 MiB | 6,144 MiB |

These are local test budgets, not publisher requirements. Run the host game,
builds, and VM tests sequentially. Display isolation does not isolate CPU,
memory, or storage pressure, and the helpers do not close unrelated applications.
The experimental guest currently accepts a full game directory up to 512 MiB,
copies it to a 768 MiB writable tmpfs, and keeps assets outside that directory.
Generated mod caches and world growth must fit those bounds. Its 64 MiB qcow2
drive holds VM state; it is not a persistent game filesystem.

Spectral Creation's default software chunk rendering exhausted even a 5 GiB
guest during a cold world join. The retained kernel log confirms that the OOM
killer killed Minecraft before snapshot capture. This diagnoses the software
test's memory failure; it does not establish a hardware-rendered failure.

## Reproducible preparation

Run these commands from the repository root. The Java 17 helper runtime can
prepare both packs; it does not determine the Java version used by Minecraft.
The Gson JAR is already present in the local Gradle cache.

```sh
TEST_ROOT=/run/media/cash/Windows/LoadOnce-fabric-tests-2026-10-11
PRISM_ROOT="$TEST_ROOT/prism"
LOADONCE_GSON=/home/cash/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.14.0/efc0e34ede4e3204eaefb84a00e55e8c86634382/gson-2.14.0.jar
```

1. Check storage, then download the exact archive. The preparation reserve is
   1 GiB for Fabulously Optimized or 2 GiB for Spectral Creation. The helper
   verifies the pinned archive size and SHA-512 before publishing it.

   ```sh
   /usr/lib/jvm/java-17-openjdk/bin/java -Xmx128m tools/PrepareFabricPack.java "$TEST_ROOT" --pack fabulously --check
   /usr/lib/jvm/java-17-openjdk/bin/java -Xmx128m tools/PrepareFabricPack.java "$TEST_ROOT" --pack fabulously
   /usr/lib/jvm/java-17-openjdk/bin/java -Xmx128m tools/PrepareFabricPack.java "$TEST_ROOT" --pack spectral --check
   /usr/lib/jvm/java-17-openjdk/bin/java -Xmx128m tools/PrepareFabricPack.java "$TEST_ROOT" --pack spectral
   ```

   | Archive | Size | Expected SHA-512 |
   | --- | ---: | --- |
   | `Fabulously-Optimized-6.5.0.mrpack` | 177,589 B | `56d67f427207f561e21eaf53e98e4af1429327f3fa1215361e9bfad73e19fa905687c7692de2a1174d66e3bff4ffd7c7d3fbd8fe88de5d3fb50ba79f673e0f00` |
   | `Spectral-Creation-7.1.1.mrpack` | 113,141,122 B | `89bd29caf5eecdeaf20a5d46cb054d0bfbed396fbd6856d83b008ab764b76c3e4912d7fa5c3bb63241ff5d7ce68c4d2dab6b1d468899a0828ffbdad227075e22` |

2. Install verified manifest files and archive overrides into the private Prism
   root. The installer refuses existing instance destinations, so do not rerun
   installation over an already prepared profile.

   ```sh
   /usr/lib/jvm/java-17-openjdk/bin/java -Xmx192m -cp "$LOADONCE_GSON" tools/InstallFabricPack.java "$TEST_ROOT/Fabulously-Optimized-6.5.0.mrpack" "$PRISM_ROOT"
   /usr/lib/jvm/java-17-openjdk/bin/java -Xmx192m -cp "$LOADONCE_GSON" tools/InstallFabricPack.java "$TEST_ROOT/Spectral-Creation-7.1.1.mrpack" "$PRISM_ROOT"
   ```

   The instance IDs are `loadonce-fabulously-optimized-6.5.0` and
   `loadonce-spectral-creation-7.1.1`. Both installed mod counts passed.

3. Prepare public runtime files and export a private offline VM specification.
   The helper resolves the four pinned component identities, fetches missing
   official Prism component metadata, verifies runtime sizes and SHA-1 hashes,
   and reuses only verified public cache files. It neither reads accounts nor
   starts Prism. The export extracts verified Linux native libraries and uses
   the original Fabric `KnotClient` entry point.

   ```sh
   /usr/lib/jvm/java-17-openjdk/bin/java -Xmx192m -cp "$LOADONCE_GSON" tools/PreparePrismRuntime.java "$PRISM_ROOT" --pack fabulously --export-spec "$TEST_ROOT/fabulously-opaque.properties"
   /usr/lib/jvm/java-17-openjdk/bin/java -Xmx192m -cp "$LOADONCE_GSON" tools/PreparePrismRuntime.java "$PRISM_ROOT" --pack spectral --export-spec "$TEST_ROOT/spectral-opaque.properties"
   ```

   Existing valid runtime files are reused; invalid files and existing export
   destinations are refused. Both runtime preparations and exports passed.
   Each export contains synthetic, internally consistent offline identity and
   token values, not copied sign-in credentials.

## Private host GUI baseline

`HardwareDisplaySession` creates a headless Weston GL compositor and authenticated
1280 by 720 Xwayland window, with a private home, runtime directory, and cookie.
It requires direct rendering on the RTX 3060 Ti before running its child command.
It does not connect to the desktop compositor or take control of a display device.
`PrivateAudioSession` provides a private PipeWire/Pulse socket, sink, and bounded
PCM recording without opening physical speakers. `RunFabricPackIsolated` validates
the live display and audio owners before creating AWT objects or launching a game.
Its private Prism root prevents launcher IPC forwarding to the normal launcher.
Mouse, text input, and screenshot assertions passed on this hardware display.
Those fixture checks do not establish game sound or world-loading acceptance.

The real-GPU Fabulously Optimized cold run on 11 October 2026 used all 48 mod
JARs, Java 21 with a 1,536 MiB heap, and NVIDIA RTX 3060 Ti driver 615.71.09.
The game itself logged that renderer and initialized OpenAL on
`LoadOnce-private-audio`. Its default-terrain `LOHardwareFO` world rendered;
movement, Space key input, creative inventory, and mouse block interaction were exercised.
A diamond block at `0, 100, 0` survived UI save/reopen: the server thread logged
`LOHardwareReopenedVerified` at 05:57:14 KST, with FastQuit's completed saves at
05:54:05 and 05:58:03. The command exited with code 0, the temporary account copy
was removed, and the owned game, helper, and Xwayland processes were absent.

The known `minecraft:block.note_block.pling` sound was requested on the server
thread at 05:49:13 KST during recording 2. Its private Pulse monitor captured
16,777,216 bytes with 8,343,326 nonzero samples and a final peak of 10,192;
physical speakers were not opened. The owned integrated server also passed
loopback Minecraft status and nonce-ping requests in 87 ms. This verifies that
local TCP path; authenticated multiplayer remains unverified. e4mc's automatic
relay connection was ordinary bundled-mod behavior, not a multiplayer acceptance
test. Menu observation took 52.727 seconds, a host-monotonic upper bound including
Prism UI and operator delay.

Evidence is retained under
`prism/isolated-validation/run-6347594804704915826`: `cold-result.properties`,
`game-verification.log`, the `gpu-*.png` screenshots, `boot-timing.properties`,
`network-check.log`, and `audio/audio-evidence.properties` with its PCM files.
The later Fabulously Optimized cold retest used all 48 physical JARs, with 44
entries shown in the Mods menu, in an owned user and network namespace. Namespace
UID/GID 0 mapped only to host UID/GID 1000; its only interface was loopback with
`127.0.0.1/8` and `::1/128`. The RTX 3060 Ti rendered the existing `LOHardwareFO`
world after joining at 06:29:01 KST. W input and the private relative-input bridge
changed yaw/pitch from `0, 0` to `30, 12` and position from `0.5, 101, 0.5` to
`-1.138, 74, 3.341` in the retained screenshots. `gpu-camera-before.png` and
`gpu-camera-after.png` retain
the result under `prism/isolated-validation/run-15397542229425223266`, with owned
display session `/tmp/loadonce-native-display-11090306721263822267`.

The prior process's diamond block persisted: the server thread logged the
conditional `LOFOColdReload` marker at 06:31:15 KST. LAN publication used port
25445 at 06:31:44 KST; e4mc's external relay failed as expected in this namespace.
The owned game process passed Minecraft loopback status and nonce-ping requests
in 97 ms, with `network-status-342758294148564.json` retained. This verifies local
protocol traffic; a full second-client join and authenticated multiplayer remain
unverified. Menu observation took 100.229 seconds, a host-monotonic upper
bound including Prism prompt entry, debugging, and operator delay. It is not a
performance benchmark.

The server thread requested the known pling at 06:32:52 KST after recording 2
started. Its final 16 MiB PCM file contained 8,287,598 nonzero samples with peak
amplitude 20,941 and verified private stereo routes. All three dimensions saved
at 06:33:24 KST, and GUI Quit returned command exit 0. The owned game, helper,
audio server, and display processes stopped; the temporary account copy was
removed. The run retains `cold-result.properties`, `game-verification.log`,
renderer and namespace/display evidence, and both private audio recordings.

The relative-input retests resolve the earlier Robot/XTest camera limitation for
these cold runs. End-user acceptance remains incomplete. Hardware warm
restoration is blocked by stock QEMU 11.1.1's
[virgl](https://github.com/qemu/qemu/blob/v11.1.1/hw/display/virtio-gpu-base.c#L228-L232)
and [rutabaga](https://github.com/qemu/qemu/blob/v11.1.1/hw/display/virtio-gpu-rutabaga.c#L1100-L1103)
migration blockers, which its
[internal snapshot path checks](https://github.com/qemu/qemu/blob/v11.1.1/migration/savevm.c#L3273-L3279).
The software VM restoration results below remain diagnostics.

The Spectral Creation real-GPU cold run used all 146 physical mod JARs,
Minecraft 1.20.1, Java 17 with a 2,304 MiB heap, default terrain, render distance
16, and simulation distance 12. The Mods menu showed 152 entries including nested entries; the
Fabric loader logged 366 components, a separate count from physical JARs.
F3 showed NVIDIA RTX 3060 Ti driver 615.71.09 and 60 FPS during the
check. With the game's default raw input retained, two private relative-motion
requests of `200, 80` changed yaw/pitch from `0, 0` to `30, 12`; the first request
established focus without rotating. W and Space also changed the player position
from `-36.5, 76, 38.5` to `-38.823, 74, 39.700`. The before/after images are
`gpu-camera-before.png` and `gpu-camera-after.png` under
`prism/isolated-validation/run-4683958074166766976`, with the owned display session
`/tmp/loadonce-native-display-11278769653451582306`. Menu observation took
80.024 seconds, a host-monotonic upper bound including Prism UI and operator
delay. The bridge uses Weston's `wet_module_init` entry point and injects bounded
relative motion into this private compositor.

The My Journal quest overview and a clicked journal entry rendered in
`gpu-journal.png` and `gpu-journal-entry.png`. A Create `water_wheel` was placed
with a world command at 06:16:01 KST; the server thread logged the conditional
`LOHardwareSpectralVerified` marker at 06:17:27 KST.

OpenAL initialized on the private Pulse device, and a known
`minecraft:block.note_block.pling` command at
06:15:14 KST occurred within recording 2. Its final 16 MiB PCM file contained
8,385,836 nonzero samples with peak amplitude 21,814. These checks
verify the observed cold camera, content UI, world block, and private game audio
behavior.

All dimensions finished saving at 06:23:27 KST, including the overworld,
Spectrum's deeper-down dimension, Nether, and End. After rejoining at
06:24:23 KST, the server thread logged the conditional `LOSpectralReopened`
marker at 06:25:02 KST, confirming that the placed Create water wheel survived
save/reopen; `gpu-reopened.png` retains the reopened world. A final save completed
for all four dimensions at 06:25:58 KST. GUI Quit returned command exit 0; the
helper and owned display processes stopped, and the temporary account copy was
removed. The run retains `cold-result.properties`, `game-verification.log`,
renderer and display evidence, and the private audio logs, configuration, and
PCM files. The pack logged nonfatal recipe, Jade, and Create Estrogen plugin
errors, so these observations do not establish an error-free pack.

Spectral networking was deliberately not performed: automatic approval review
rejected publishing the uncontained world because bundled e4mc could expose it
through a relay. The next network check requires an owned user and network
namespace with only loopback available. This run therefore has no network
acceptance result, and hardware warm restoration remains blocked as described
above.

Build the controller classes before starting a game:

```sh
./gradlew --offline --no-daemon --max-workers=1 launcherContractTest
```

Start one profile at a time. Change `fabulously` to `spectral` for the content
pack, or add `--display-check` to run only the private display fixture:

```sh
/usr/lib/jvm/java-17-openjdk/bin/java -Xmx128m tools/HardwareDisplaySession.java -- /usr/lib/jvm/java-17-openjdk/bin/java -Xmx128m /home/cash/git/loadonce/tools/PrivateAudioSession.java -- /usr/lib/jvm/java-17-openjdk/bin/java -Xmx128m /home/cash/git/loadonce/tools/RunFabricPackIsolated.java "$PRISM_ROOT" --pack fabulously
```

For the optional raw camera bridge, first build the owned Weston 15 module using
the commands in [tools/native/README.md](../tools/native/README.md). Keep its
private build directory available for the session, and supply the absolute
module path before the command separator:

```sh
/usr/lib/jvm/java-17-openjdk/bin/java -Xmx128m tools/HardwareDisplaySession.java \
  --relative-input-module "$input_build_dir/private-relative-input.so" -- \
  /usr/lib/jvm/java-17-openjdk/bin/java -Xmx128m /home/cash/git/loadonce/tools/PrivateAudioSession.java -- \
  /usr/lib/jvm/java-17-openjdk/bin/java -Xmx128m /home/cash/git/loadonce/tools/RunFabricPackIsolated.java "$PRISM_ROOT" --pack spectral
```

For a network-contained cold run, use the same private account and module
preparation, then start the whole display/audio/game chain inside a fresh user
and network namespace. Bring up only loopback:

```sh
(
set -eu
task_namespace_origin="$(/usr/lib/jvm/java-17-openjdk/bin/java -Xmx128m tools/RunFabricPackIsolated.java "$PRISM_ROOT" --namespace-origin)"
trap 'rm -f -- "$task_namespace_origin"' EXIT
/usr/bin/unshare --user --map-root-user --net --fork --forward-signals /bin/sh -c '
  set -eu
  /usr/bin/ip link set lo up
  exec "$@"
' sh /usr/lib/jvm/java-17-openjdk/bin/java -Xmx128m tools/HardwareDisplaySession.java \
  --relative-input-module "$input_build_dir/private-relative-input.so" -- \
  /usr/lib/jvm/java-17-openjdk/bin/java -Xmx128m /home/cash/git/loadonce/tools/PrivateAudioSession.java -- \
  /usr/lib/jvm/java-17-openjdk/bin/java -Xmx128m /home/cash/git/loadonce/tools/RunFabricPackIsolated.java "$PRISM_ROOT" --pack fabulously
)
```

The primer captures the original namespace IDs before entering the private
namespace and binds its mode-0600 proof to a live owned original-host ancestor.
The peer verifies that owner through its actual ancestry, its process start
time and UID/GID maps. Direct reads of host PID 1 namespace links are denied on
this machine; they cannot serve as the valid-path check. Remove only the proof
path created or verified by this primer when the private run finishes.

The wrapper records and validates the selected module and exact owned headless
Weston arguments. Omitting the option preserves the normal private display
launch. The module emits relative motion only; it does not open physical input
devices or launch games.

Prism requires an account owning Minecraft for its offline launch. The user
approved a temporary mode-0600 account-cache copy for these private host tests.
The recorded local test harness used an exit trap to remove that exact copy;
the original account cache is untouched. The example above assumes an already
authorized private account file. It does not copy or clean up accounts, and
`RunFabricPackIsolated` does not perform those operations either.

The launcher asks for an offline name prefilled with the cached account's name.
Keep that matching name: changing only the name while Prism retains the cached
UUID caused rejected commands in an earlier test. Passing an empty value to
Prism's `--offline` option displays the prefilled matching name. With that name,
Fabulously Optimized passed world commands and save/reopen acceptance. The VM
export uses its own consistent offline identity and contains no account cache.

The earlier software-rendered Fabulously Optimized cold run placed a diamond block at `0, 100, 0`
at 03:27:53, saved through the game's Save and Quit UI, and logged FastQuit's
finished-save message at 03:30:44. The world reopened at 03:31:17. A successful
conditional block check reported `LOHostVerified` on the server thread at
03:31:36, confirming the persisted block. A second UI save finished at 03:31:38;
Minecraft and the private launcher helper then exited with code 0. The temporary
account copy was removed. `/save-all flush` was unavailable in this integrated
server, so the acceptance evidence uses UI saving and its actual completion log.

Evidence is under
`prism/isolated-validation/run-14941704945697167749` (`prism.log` and
`screen-latest.png`), with game events in
`prism/isolated-validation/run-14941704945697167749/game-verification.log`
and helper output in `/tmp/loadonce-fabulously-private-identity.log`. These are
software host diagnostics with unverified hardware graphics, game sound, and
networking; the separate software VM results are recorded below.

The helper prints a private run directory, updates `screen-latest.png`, and
accepts bounded input batches for twenty minutes on the hardware display. Inspect that run's screenshot
before choosing coordinates. For example:

```sh
/usr/lib/jvm/java-17-openjdk/bin/java -Xmx128m tools/RunFabricPackIsolated.java "$PRISM_ROOT" --send "$PRISM_ROOT/isolated-validation/run-EXAMPLE" 'click 400 300' 'text loadonce-world-1' 'key ENTER'
```

Replace `run-EXAMPLE` and coordinates with the observed live private run. The
sender writes an input file; the guarded helper delivers events inside the private display.
It does not create a desktop Robot. Clicks, ASCII text, Enter, Escape, Tab,
Backspace, W/A/S/D, Space, E, and F3 are accepted. The recorded PID and start time
must match the live endpoint. Send `menu_ready` as its own batch only after observing
the menu; `boot-timing.properties` records a host-monotonic observation upper bound
including launcher UI and operator delay.

Camera motion uses the owned display session printed by `HardwareDisplaySession`,
which is distinct from the game helper's `run-*` directory:

```sh
/usr/lib/jvm/java-17-openjdk/bin/java -Xmx128m tools/PrivateRelativeMotion.java \
  /tmp/loadonce-native-display-SESSION 200 80
```

Replace `SESSION` with the live private display session. The sender validates its
module, socket, and live process identities, sends one bounded request, and has
a three-second deadline. The module permits eight pending clients and a
two-second request deadline. `dispatched` confirms compositor dispatch; inspect
the game's yaw/pitch and screenshots to confirm camera motion and focus.

The audio wrapper records at most three 16 MiB PCM files. To record a known game
sound after startup, create an owner-only mode-0600 temporary regular file in
the printed session's `audio` directory containing exactly `record` followed by
a newline, then atomically rename it to `audio/capture-next.txt`. Publishing the
completed file this way avoids a partial-read race; mode 0644 is rejected.
Wait for `PRIVATE_AUDIO_CAPTURE` before triggering that sound. Nonzero samples
and explicit game-to-sink routes are evidence; the wrapper never labels them a
game-sound pass automatically.

A separate private capture fixture remained active through 35 seconds without
playback, then recorded a tone with its first nonzero sample at 35.968 seconds.
Its retained evidence is under `/tmp/loadonce-native-display-6583750302811060826`.
That verifies silent-start capture continuity; known game-sound acceptance still
uses the in-game action and retained PCM evidence described above.

Run LAN publication only inside an owned user and network namespace with only
loopback available, so bundled relay mods cannot expose the test world. After
the private world is published there, run `MinecraftNetworkCheck.java`
with its live private run directory and LAN port. It verifies that a game helper
descendant owns the listening socket, performs Minecraft status and nonce ping
requests over loopback, and records the response. This checks the real integrated
server network path; authenticated multiplayer requires a separate successful test.

Run the check in that exact live namespace. Replace the run directory and port
with the values printed by your launch. The guard verifies ownership, distinct
namespace identities, the single-user mappings, and loopback-only interfaces
and addresses before executing the command:

```sh
(
set -eu
task_private_run="$PRISM_ROOT/isolated-validation/run-15397542229425223266"
set -- /usr/lib/jvm/java-17-openjdk/bin/java -Xmx128m /home/cash/git/loadonce/tools/MinecraftNetworkCheck.java "$task_private_run" 25445
task_host_net="$(readlink /proc/self/ns/net)"
task_host_user="$(readlink /proc/self/ns/user)"
task_helper_pid="$(awk -F= '$1=="helperPid" {print $2}' "$task_private_run/run.properties")"
case "$task_helper_pid" in ''|*[!0-9]*) exit 2;; esac
test -O "/proc/$task_helper_pid"
task_private_net="$(readlink /proc/$task_helper_pid/ns/net)"
task_private_user="$(readlink /proc/$task_helper_pid/ns/user)"
test "$task_private_net" != "$task_host_net"
test "$task_private_user" != "$task_host_user"
awk -v expected="$(id -u)" '$1==0 && $2==expected && $3==1 {ok++; next} {bad++} END {exit !(ok==1 && bad==0)}' "/proc/$task_helper_pid/uid_map"
awk -v expected="$(id -g)" '$1==0 && $2==expected && $3==1 {ok++; next} {bad++} END {exit !(ok==1 && bad==0)}' "/proc/$task_helper_pid/gid_map"
exec nsenter --target "$task_helper_pid" --user --net --preserve-credentials -- sh -c '
  set -eu
  test "$(readlink /proc/self/ns/user)" = "$1"
  test "$(readlink /proc/self/ns/net)" = "$2"
  shift 2
  test "$(id -u)" = 0
  test "$(id -g)" = 0
  test "$(ip -o link show | cut -d: -f2 | tr -d " ")" = lo
  test "$(ip -o -4 addr show dev lo | awk "\$3==\"inet\" {print \$4}")" = 127.0.0.1/8
  test "$(ip -o -6 addr show dev lo | awk "\$3==\"inet6\" {print \$4}")" = ::1/128
  exec "$@"
' sh "$task_private_user" "$task_private_net" "$@"
)
```

Keep `--preserve-credentials`: this mapped user namespace denies `setgroups`,
so `--setuid` or `--setgid` can fail before the command starts. The same guarded
entry can run the private input or screenshot commands in that session.

For the full second-client check, `PrepareFabricLanPeer` prepares and launches a
separate Fabulously Optimized peer in a second owned hardware/audio session,
inside the host game's live loopback-only namespace. Start the host with the
current `RunFabricPackIsolated` first: its run metadata now binds the launcher
root, pack and display session. Pass that live run directory and LAN port to
the preparation tool. It verifies the host listener, copies the unchanged
48-JAR pack and its dependencies into the second session's private launcher
root, and rechecks the source and copy fingerprints before launch.

The temporary owning-account copy authorized for the host must remain available
during preparation. The tool preserves that account and adds an explicitly
selected Offline profile with a distinct UUID; renaming the original account
would retain its UUID. Prism's normal ownership checks still apply. Preparation
and launch share one credential lifetime; shutdown closes the process-start
gate before stopping Prism and removing the generated account cache and its
pending save files. No credential values enter the evidence manifest.

Compile the four helpers with the existing Gson JAR, then run the synthetic and
namespace contracts before staging an account. Use a new verification directory:

```sh
peer_verify_dir="$(mktemp -d /tmp/loadonce-peer-verify-XXXXXX)"
peer_gson="$PRISM_ROOT/libraries/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar"
/usr/lib/jvm/java-17-openjdk/bin/javac -J-Xmx128m -cp "$peer_gson" \
  -d "$peer_verify_dir" tools/RunFabricPackIsolated.java \
  tools/PrepareFabricLanPeer.java tools/FabricLanPeerContract.java \
  tools/FabricLanPeerNamespaceContract.java
/usr/lib/jvm/java-17-openjdk/bin/java -Xmx64m \
  -cp "$peer_verify_dir:$peer_gson" FabricLanPeerContract
/usr/lib/jvm/java-17-openjdk/bin/java -Xmx64m \
  -cp "$peer_verify_dir" FabricLanPeerNamespaceContract
```

Inside the guarded namespace entry above, launch a second
`HardwareDisplaySession` containing `PrivateAudioSession`, using
`java -Xmx128m -cp "$peer_verify_dir:$peer_gson" PrepareFabricLanPeer HOST_RUN LAN_PORT`
as its child command. Each client keeps its own private GPU display and audio
sink. The peer uses Java 21, a 1536 MiB maximum heap, and the original pack's
graphics settings. It cannot launch outside the verified loopback-only
namespace or reuse the main display session.

The 24 synthetic contracts and Java 17 compilation passed. A separate 64 MiB
namespace contract reproduced the original PID 1 permission failure, then
passed real isolated admission and rejected host, stale and public-mode proofs.
Actual peer login,
movement, a shared-world block action, disconnect/rejoin and normal saves/exits
remain **unverified**. Host memory pressure prevented starting the two-client
test in this continuation. This setup does not establish authenticated Internet
multiplayer or hardware warm restoration.

## Experimental VM restoration

The following interface passed the Fabulously Optimized software diagnostic
subset; Spectral Creation diagnostics remain incomplete. It preserves the selected Java runtime, classpath, native libraries,
assets, and complete copied game directory. Game graphics use the guest's private
Xvfb and software rendering. QEMU uses `-display none -vga none -nic none`;
multiplayer, external services, shader performance, and physical GPU drivers are
outside this test.

World and configuration changes live in guest RAM and, when captured, its state
snapshot. This prototype does not synchronize those changes back to the host
Prism profile. Snapshots are test-controller artifacts; production Prism startup
integration is not implemented.

Use a new private work directory for each pack:

```sh
/usr/lib/jvm/java-25-openjdk/bin/java -Xmx256m -cp build/classes/java/launcherTest:build/classes/standaloneRuntime com.qb20nh.loadonce.launcher.VmOpaqueSmoke --qemu-directory /home/cash/git/loadonce/build/local-tools/qemu/root --work-directory "$TEST_ROOT/fabulously-vm-validation" --spec "$TEST_ROOT/fabulously-opaque.properties" --xvfb /home/cash/git/loadonce/build/local-tools/xvfb/usr/bin/Xvfb
```

For Spectral Creation, substitute `spectral-vm-validation` and
`spectral-opaque.properties`. The controller publishes a private command
endpoint and phase. Read screenshots and game logs through the serial control
channel; input stays inside the VM:

```sh
/usr/lib/jvm/java-25-openjdk/bin/java -Xmx128m -cp build/classes/java/launcherTest:build/classes/standaloneRuntime com.qb20nh.loadonce.launcher.VmOpaqueSmoke --send "$TEST_ROOT/fabulously-vm-validation" 'SCREEN' 'LOG' 'STATE'
```

After observing the initialized menu in a screenshot, send `MENU_READY` once
in each cold or warm phase. Its elapsed time is a manual observation upper
bound that includes operator and command polling delay. It records a private
menu screenshot without inspecting application classes. Duplicate observations
are rejected.

`image-build-timing.properties` records image construction separately.
`cold-timing.properties` records QEMU startup to external guest control readiness;
this does not establish menu readiness. Each `warm-N-timing.properties` records
snapshot cloning separately, then QEMU startup including VM-state loading to
frozen control readiness, clock correction, and application thaw. Durations use
the host monotonic clock, with nanosecond and millisecond fields and explicit
measurement labels. Console timing lines report milliseconds.

If the game process exits, `LOG` appends bounded guest kernel, memory, and
JVM crash-report evidence once. Rejected UI input retrieves that log before the
controller shuts down its VM. Inspect this evidence to distinguish a cold
application failure from a restoration failure.

The endpoint also accepts `CLICK X Y`, `TEXT ...`, and
`KEY ENTER/ESCAPE/TAB/BACKSPACE`. Use observed UI coordinates. Enter a
command with `TEXT /`, use `KEY BACKSPACE` and `SCREEN` to confirm an empty chat
field, then send `TEXT /execute ...` and `KEY ENTER`. Private Xvfb key repeat is delayed
beyond the command timeout to avoid repeated characters during brief stalls.
Do not send a
capture or completion command merely because a menu screenshot looks correct.

The cold phase requires a world change to survive saving, closing, and reopening
the world, with a successful conditional command reporting `LOColdVerified`.
Return to the initialized menu, then send `CAPTURE` as the final batch command.
The controller freezes the application, captures one original menu checkpoint,
and creates three independent restore copies sequentially. It corrects guest
wall time before thawing the application in each copy.

Each warm phase must verify the saved world again, report its exact successful
marker (`LOWarmVerified1`, `LOWarmVerified2`, or `LOWarmVerified3`), save and reopen
the world, and quit Minecraft normally through its UI. Send `NEXT` only after
those checks. The controller requires normal application and VM exits and keeps
the original checkpoint unchanged. Completed restore copies can be removed to
recover space; failed copies retain evidence.

Label **warm PASS** only when all three independent cycles complete the full
checks and `completion.properties` records a pass. The application process,
start time, and guest boot identity must continue across restore without a hidden
restart. For an uninstrumented pack, retained Java object identity and exactly
once mod initialization remain **unverified**. Process continuity and screenshots
do not establish those stronger properties or compatibility with arbitrary mods.

## Acceptance and retained reference tests

For each cold baseline and restored cycle, require:

- Continued main-menu rendering and working keyboard/mouse input.
- Direct hardware rendering on the intended GPU with the complete pack.
- World loading, rendering, and player interaction.
- A distinctive block change surviving save, close, and reopen.
- Successful conditional block verification, rather than a command echo or error.
- Successful game audio initialization and captured PCM from a known game sound.
- A usable Minecraft network connection, with its tested scope recorded.
- Normal exit, with private logs and screenshots retained.

An ordinary cold pack failure must be diagnosed before snapshots. Keep cold
baseline failure, capture failure, and restore-only failure distinct. The
`vmMinecraftSmoke` diagnostic task is not a smaller-pack acceptance result.
The software VM results below cover only the rendering/input/world subset;
they do not satisfy the full GPU, audio, and network criteria.

Fabulously Optimized completed on this NTFS mount under
`fabulously-vm-validation/guest-image-11383986529717306213`.
`completion.properties` records `status=passed`, `target=opaque-application`,
and `restore_cycles=3`. Every restore retained app PID `98`, start tick `72`,
boot UUID `c3a1ca9e-dea0-4e94-bba1-b9a9c93a23b1`, and control UUID
`5966626b-610b-4d29-9595-d9f721346a62`. The cold world saved a diamond block;
each restored cycle saved a fresh emerald block, reopened the world, verified
both blocks with a conditional server command, and exited normally with code
zero. The original checkpoint SHA-256 remained
`f96281a4db5320296eb9811e9db3d22f5e426d526562ecc8b16847e07d52e62c`.
Source checkpoint, boot image, screenshots, logs, and result properties remain;
completed restore copies and the generated staging root were reclaimed.
Internal object identity and exactly-once mod initialization are unverified.

This run preceded monotonic timing instrumentation. The following retrospective
estimates use zero-length process-log creation timestamps as the approximate
QEMU launch time and the first screenshot showing a menu as an upper bound.
They exclude image construction and snapshot cloning, and include screenshot
encoding/transfer and manual observation delay. They are not an exact startup
benchmark.

| Fabulously Optimized phase | Control ready / corrected thaw estimate | First menu screenshot upper bound |
| --- | ---: | ---: |
| Cold | 1.60 s (external control only) | 36.17 s |
| Warm 1 | 1.97 s | 9.85 s |
| Warm 2 | 2.15 s | 9.98 s |
| Warm 3 | 2.78 s | 10.48 s |

The menu evidence is `cold-screenshot-2.png`, `warm-1-screenshot-12.png`,
`warm-2-screenshot-25.png`, and `warm-3-screenshot-37.png`. Subsequent runs use
the separate monotonic timing artifacts described above.

The existing Minecraft 26.3 diagnostic passed cold world loading and three warm
restores on this NTFS mount, using the byte-copy fallback. Its results are under
`ntfs-vm-validation`: `completion.properties` records `status=passed`,
`target=minecraft`, and `restore_cycles=3`; all cycles retained probe UUID
`716d17d2-3c37-42bb-822e-a4aa1af86a32` and `probe.calls=1`. Its boot image,
logs, and result properties remain; its generated checkpoint and three
completed restore copies were reclaimed to make room for the modpack tests.
The original home-filesystem diagnostic checkpoint remains separately.

The larger [Prominence II 4.1.3](https://modrinth.com/modpack/prominence-2-fabric/version/sXMHqA8W)
reference profile was prepared under `loadonce-prominence-ii-4.1.3`: Java 17,
Minecraft 1.20.1, Fabric 0.19.3, 443 bundled JARs plus four required FTB JARs,
for 447 physical mod JARs. `PrepareFabricPack --pack prominence` and the installer
still support its pinned archive. Its disposable instance and archive were
reclaimed after testing moved to smaller packs, freeing approximately 2.3 GiB;
shared public runtime files and diagnostic evidence remain.
`InstallFtbDependencies.java` supplies the
four exact dependencies listed in the pack's bundled missing-mod screen, after
size and SHA-512 verification. It uses the user's selected 5 GiB heap for host
tests. Its cold world and restoration checks have **not passed**, and opaque VM
export does not support this larger profile.
