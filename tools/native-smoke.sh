#!/usr/bin/env bash
# Run a native probe or image driver on a temporary software-rendered display.
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: tools/native-smoke.sh [--xvfb PATH] [-- GRADLE_ARGS...]
       tools/native-smoke.sh [--xvfb PATH] --command COMMAND [ARGS...]

Defaults: nativeRestorationProbe -PloadonceCycles=10 -PloadonceAudioLoopback=true
Xvfb is selected by --xvfb, LOADONCE_XVFB, or PATH, in that order.
Requires Xvfb, xauth, setsid, Mesa software OpenGL, and the project's JDK.
Example: tools/native-smoke.sh -- --offline nativeRestorationProbe \
    -PloadonceCycles=20 -PloadonceAudioLoopback=true

For actual CRaC process images, use tools/smoke_crac.py's isolated environment.
EOF
}

xvfb=${LOADONCE_XVFB:-Xvfb}
run_command=false
while (( $# )); do
    case $1 in
        --xvfb)
            if (( $# < 2 )); then usage >&2; exit 2; fi
            xvfb=$2
            shift 2
            ;;
        --xvfb=*) xvfb=${1#*=}; shift ;;
        --help|-h) usage; exit 0 ;;
        --command)
            shift
            if (( $# == 0 )); then usage >&2; exit 2; fi
            run_command=true
            break
            ;;
        --) shift; break ;;
        *) printf 'Unknown option: %s\n' "$1" >&2; usage >&2; exit 2 ;;
    esac
done

for program in "$xvfb" xauth setsid; do
    if ! command -v "$program" >/dev/null 2>&1; then
        printf 'Required program unavailable: %s\n' "$program" >&2
        exit 2
    fi
done

repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
if [[ $run_command == true ]]; then
    command_args=("$@")
elif (( $# )); then
    gradle_args=("$@")
    command_args=(bash ./gradlew "${gradle_args[@]}" --no-daemon)
else
    command_args=(bash ./gradlew nativeRestorationProbe -PloadonceCycles=10 \
        -PloadonceAudioLoopback=true --no-daemon)
fi

umask 077
runtime_dir=$(mktemp -d "${TMPDIR:-/tmp}/loadonce-native-smoke.XXXXXX")
xvfb_pid=
command_pid=
terminate() {
    local target=$1
    kill -TERM -- "$target" 2>/dev/null || true
    for (( grace=0; grace<20; grace++ )); do
        if ! kill -0 -- "$target" 2>/dev/null; then return 0; fi
        sleep 0.1
    done
    kill -KILL -- "$target" 2>/dev/null || true
    return 0
}
cleanup() {
    if [[ -n $command_pid ]]; then
        terminate "-$command_pid"
        wait "$command_pid" 2>/dev/null || true
    fi
    if [[ -n $xvfb_pid ]]; then
        terminate "$xvfb_pid"
        wait "$xvfb_pid" 2>/dev/null || true
    fi
    rm -rf -- "$runtime_dir"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

mkdir "$runtime_dir/xdg" "$runtime_dir/mesa-cache"
export XDG_RUNTIME_DIR="$runtime_dir/xdg"
export MESA_SHADER_CACHE_DIR="$runtime_dir/mesa-cache"
export XAUTHORITY="$runtime_dir/Xauthority"
export LIBGL_ALWAYS_SOFTWARE=1
touch "$XAUTHORITY"
cookie=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
# Xvfb reads the cookie independently of the client display entry. Add the
# dynamically assigned display entry once -displayfd reports server readiness.
xauth -f "$XAUTHORITY" add :0 MIT-MAGIC-COOKIE-1 "$cookie"
"$xvfb" -displayfd 3 -screen 0 640x480x24 -nolisten tcp \
    -auth "$XAUTHORITY" -noreset \
    3> "$runtime_dir/display" > "$runtime_dir/Xvfb.log" 2>&1 &
xvfb_pid=$!
for (( attempt=0; attempt<100; attempt++ )); do
    if [[ -s $runtime_dir/display ]]; then break; fi
    if ! kill -0 "$xvfb_pid" 2>/dev/null; then
        printf 'Xvfb exited before reporting a ready display.\n' >&2
        cat "$runtime_dir/Xvfb.log" >&2
        exit 1
    fi
    sleep 0.1
done
if [[ ! -s $runtime_dir/display ]]; then
    printf 'Xvfb did not report a ready display within 10 seconds.\n' >&2
    cat "$runtime_dir/Xvfb.log" >&2
    exit 1
fi
read -r display_number < "$runtime_dir/display"
if [[ ! $display_number =~ ^[0-9]+$ ]]; then
    printf 'Xvfb returned an invalid display number.\n' >&2
    exit 1
fi
export DISPLAY=":$display_number"
xauth -f "$XAUTHORITY" add "$DISPLAY" MIT-MAGIC-COOKIE-1 "$cookie"
unset cookie
printf 'NATIVE_SMOKE display=%s software-gl=true\n' "$DISPLAY"

cd "$repo_dir"
# A separate process group lets cancellation stop the driver and its probe JVM
# before the display and private runtime directories are released.
setsid "${command_args[@]}" &
command_pid=$!
command_exit_code=0
wait "$command_pid" || command_exit_code=$?
exit "$command_exit_code"
