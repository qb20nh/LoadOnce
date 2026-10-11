The optional relative-input module targets the installed Weston 15 ABI. It sends
relative and unaccelerated pointer motion through the private compositor. It does
not launch applications, select physical devices, or prove that a game accepted
the motion. The Java wrapper retains ownership and display policy.

Build explicitly from the repository root using the installed compiler and
development headers. No automatic build or dependency installation occurs:

```sh
test ! -L tools/native/private-relative-input.c
test -O tools/native/private-relative-input.c
input_build_dir="$(mktemp -d /tmp/loadonce-relative-input-build-XXXXXX)"
cc -shared -fPIC -O2 -Wall -Wextra \
  -o "$input_build_dir/private-relative-input.so" \
  tools/native/private-relative-input.c \
  $(pkg-config --cflags --libs libweston-15 wayland-server)
chmod 600 "$input_build_dir/private-relative-input.so"
/usr/lib/jvm/java-17-openjdk/bin/java -Xmx128m tools/HardwareDisplaySession.java \
  --relative-input-module "$input_build_dir/private-relative-input.so" -- COMMAND
```

The module path must be absolute, normalized, owned by the current user, free of
symlinks and commas, and have owner-only permissions. It must remain available
while the session runs. The wrapper records it in `hardware-display.properties`
and validates the exact owned Weston arguments. Omitting the option preserves the
normal private display launch.

Send one bounded relative movement to a live session:

```sh
/usr/lib/jvm/java-17-openjdk/bin/java -Xmx128m tools/PrivateRelativeMotion.java \
  /tmp/loadonce-native-display-SESSION 100 0
```

The socket admits only the current UID inside the private runtime directory. It
allows eight pending clients, a 64-byte request, two seconds per request, and
finite deltas within -4096..4096. The Java sender has a three-second deadline and
rechecks live process identities. `dispatched` confirms compositor dispatch only;
verify camera yaw and pitch in the game before accepting raw camera input.

The separate [native GPU snapshot experiment](../../docs/native-gpu-restoration.md)
uses `gfxstream-snapshot-validation.patch` to build the external renderer's
standalone snapshot API and assert physical GPU identity in its GLES tests.
`GpuResourceSnapshotSmoke.java` coordinates resource capture and three fresh
restores through Java 25 FFM. It does not enable Minecraft restoration.

`kumquat-private-storage.patch` applies to the pinned native transport described
in that experiment. It requires private snapshot storage and refuses existing
socket paths. It does not implement fresh-server resource or backing restoration.
