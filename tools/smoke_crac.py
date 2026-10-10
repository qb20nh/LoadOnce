#!/usr/bin/env python3
"""Verify isolated CRaC images with a minimal environment and fresh JVMs.

This is a fixture driver, not an arbitrary application checkpoint launcher.
Warp needs no CRIU privileges; CRIU still requires its host/kernel capabilities.
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import signal
import subprocess
import tempfile


class SmokeFailure(Exception):
    pass


def execute(command: list[str], directory: Path, environment: dict[str, str],
            log: Path, timeout: float, allowed_codes: tuple[int, ...] = (0,)) -> tuple[int, int, str]:
    with log.open("wb") as output:
        with subprocess.Popen(command, cwd=directory, env=environment,
                              stdout=output, stderr=subprocess.STDOUT,
                              close_fds=True, umask=0o077, start_new_session=True) as child:
            try:
                code = child.wait(timeout=timeout)
            except subprocess.TimeoutExpired as failure:
                # Engines can have helper processes. Stop the entire isolated group.
                try:
                    os.killpg(child.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                child.wait()
                raise SmokeFailure(f"process timed out; inspect {log}") from failure
            except BaseException:
                try:
                    os.killpg(child.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                child.wait()
                raise
            pid = child.pid
    text = log.read_text(errors="replace")
    if code not in allowed_codes:
        raise SmokeFailure(f"process exited {code}; inspect {log}")
    return pid, code, text


def success_record(output: str) -> tuple[int, int, str]:
    record = re.search(r"CRAC_SMOKE restored-pid=(\d+) initial-pid=(\d+) "
                       r"heap-witness=([0-9a-f-]+) value=42 coordinator-state=READY", output)
    if record is None or "preparationCallbacks=1, restorationCallbacks=1" not in output:
        raise SmokeFailure("restored CPU state/callback evidence is missing")
    return int(record[1]), int(record[2]), record[3]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", type=Path, required=True, help="verified CRaC JDK java executable")
    parser.add_argument("--engine", choices=("warp", "criuengine", "simengine"), default="warp")
    parser.add_argument("--work-dir", type=Path, default=Path("build/crac-validation"))
    parser.add_argument("--restores", type=int, default=3, help="fresh JVM restores of the same image (1..10)")
    parser.add_argument("--timeout", type=float, default=60, help="per-process deadline in seconds (1..60)")
    parser.add_argument("--native-classpath-file", type=Path,
                        help="build/native-probe-classpath.txt from writeNativeProbeClasspath")
    parser.add_argument("--skip-failure-cases", action="store_true")
    args = parser.parse_args()
    if not 1 <= args.restores <= 10 or not 1 <= args.timeout <= 60:
        parser.error("restores must be 1..10 and timeout must be 1..60")
    if not Path("/proc/self/stat").is_file():
        parser.error("this process-identity check requires Linux /proc")
    java = args.java.resolve(strict=True)
    javac = java.parent / "javac"
    if not java.is_file() or not os.access(java, os.X_OK) or not javac.is_file():
        parser.error("--java must point to a CRaC JDK with java and javac")
    repository = Path(__file__).resolve().parent.parent
    work_root = args.work_dir.absolute()
    work_root.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix="run-", dir=work_root))
    print(f"CRAC_SMOKE logs-and-private-images={directory}", flush=True)
    home = directory / "home"
    temporary = directory / "tmp"
    home.mkdir(mode=0o700)
    temporary.mkdir(mode=0o700)
    # Never forward the caller's environment: even an application that ignores
    # tokens can retain them inside a complete process image. No injected JVM
    # options, authentication variables, preload paths, or launcher sessions.
    environment = {"PATH": "/usr/bin:/bin", "HOME": str(home), "LANG": "C.UTF-8"}
    base = [str(java), f"-Duser.home={home}", f"-Djava.io.tmpdir={temporary}", "-Xms32m", "-Xmx128m"]
    classes = directory / "classes"
    classes.mkdir(mode=0o700)
    sources = sorted((repository / "src/main/java/dev/loadonce/runtime").glob("*.java"))
    if not sources:
        raise SmokeFailure("standalone runtime sources are missing")
    execute([str(javac), "--release", "17", "-Xlint:all", "-Werror", "-d", str(classes),
             *map(str, sources)], directory, environment, directory / "compile.log", args.timeout)
    cpu = ["-cp", str(classes), "dev.loadonce.runtime.CracSmoke"]
    if not args.skip_failure_cases:
        for scenario, state in (("denied", "READY"), ("prepare-failure", "READY"), ("restore-failure", "STOPPED")):
            log = directory / f"failure-{scenario}.log"
            _, _, output = execute([*base, "-XX:CRaCEngine=simengine", *cpu, scenario],
                                    directory, environment, log, args.timeout)
            if f"CRAC_SMOKE expected-failure={scenario} coordinator-state={state}" not in output:
                raise SmokeFailure(f"real CRaC failure recovery evidence is missing; inspect {log}")
            print(f"CRAC_SMOKE verified scenario={scenario} coordinator-state={state}", flush=True)
        if args.engine == "warp":
            rejected_image = directory / "rejected-image"
            rejected_image.mkdir(mode=0o700)
            log = directory / "failure-descriptor-rejection.log"
            _, _, output = execute([*base, "-XX:CRaCEngine=warp", f"-XX:CRaCCheckpointTo={rejected_image}",
                                    *cpu, "descriptor-rejection"], directory, environment, log, args.timeout)
            if ("CRAC_SMOKE expected-failure=descriptor-rejection coordinator-state=READY" not in output
                    or any(rejected_image.iterdir())):
                raise SmokeFailure(f"real engine rejection failed to recover cleanly; inspect {log}")
            print("CRAC_SMOKE verified scenario=descriptor-rejection coordinator-state=READY image-files=0", flush=True)
    native = args.native_classpath_file is not None
    application = cpu
    if native:
        if not os.environ.get("DISPLAY"):
            raise SmokeFailure("native smoke requires an existing X11 DISPLAY (for example tools/native-smoke.sh)")
        # Only display and software-rendering paths enter the isolated fixture.
        for name in ("DISPLAY", "XAUTHORITY"):
            if os.environ.get(name):
                environment[name] = os.environ[name]
        for name, child_dir in (("XDG_RUNTIME_DIR", "xdg"), ("MESA_SHADER_CACHE_DIR", "mesa-cache")):
            private_path = directory / child_dir
            private_path.mkdir(mode=0o700)
            environment[name] = str(private_path)
        environment["LIBGL_ALWAYS_SOFTWARE"] = "1"
        # Loopback renders PCM in memory. Avoid initializing an unused PulseAudio
        # backend whose library-global dispatcher outlives alcCloseDevice().
        # This selects the supported headless fixture; FD checks stay enabled.
        environment["ALSOFT_DRIVERS"] = "null"
        classpath = args.native_classpath_file.resolve(strict=True).read_text().strip()
        if not classpath or "\n" in classpath:
            raise SmokeFailure("native classpath must contain one nonempty line")
        paths = [Path(part) for part in classpath.split(os.pathsep)]
        if any(not path.is_absolute() for path in paths) or not all(path.exists() for path in paths):
            raise SmokeFailure("native classpath must use existing absolute build paths")
        base.append("--enable-native-access=ALL-UNNAMED")
        application = ["-cp", classpath, "dev.loadonce.prototype.NativeRestorationProbe",
                       "--cycles=1", "--crac", "--audio-loopback"]
    image = directory / "image"
    image.mkdir(mode=0o700)
    checkpoint_log = directory / "checkpoint.log"
    # checkpoint_exit_code is a Warp option; CRIU rejects it at VM startup.
    engine_options = ["-XX:CRaCEngineOptions=checkpoint_exit_code=0"] if args.engine == "warp" else []
    initial_pid, _, checkpoint = execute([
        *base, f"-XX:CRaCEngine={args.engine}", f"-XX:CRaCCheckpointTo={image}",
        *engine_options, *application,
    ], directory, environment, checkpoint_log, args.timeout,
        allowed_codes=(0, -signal.SIGKILL) if args.engine == "criuengine" else (0,))
    if args.engine == "simengine":
        if native:
            if "NATIVE_PROBE PASS cycles=1" not in checkpoint or "native-release=ok" not in checkpoint:
                raise SmokeFailure("native simulation callbacks failed")
        else:
            restored_pid, saved_pid, _ = success_record(checkpoint)
            if restored_pid != initial_pid or saved_pid != initial_pid:
                raise SmokeFailure("simulation process identity is inconsistent")
        print("CRAC_SMOKE PASS mode=simulation; callbacks verified, no process image proved", flush=True)
        return 0
    files = [path for path in image.rglob("*") if path.is_file()]
    payload = image / ("core.img" if args.engine == "warp" else "inventory.img")
    if not payload.is_file() or not files or not any(path.stat().st_size > 4096 for path in files):
        raise SmokeFailure(f"engine exited successfully without substantial image data; inspect {checkpoint_log}")
    witness = None
    if native:
        if "NATIVE_PROBE verified cycle=0" not in checkpoint or "NATIVE_PROBE PASS" in checkpoint:
            raise SmokeFailure("native checkpoint did not stop at the prepared lifecycle boundary")
    else:
        initial = re.search(r"CRAC_SMOKE initial-pid=(\d+) heap-witness=([0-9a-f-]+)", checkpoint)
        if initial is None or int(initial[1]) != initial_pid or "restored-pid=" in checkpoint:
            raise SmokeFailure("CPU checkpoint did not stop at the prepared lifecycle boundary")
        witness = initial[2]
    for attempt in range(1, args.restores + 1):
        log = directory / f"restore-{attempt}.log"
        restored_pid, _, restored = execute([*base, f"-XX:CRaCRestoreFrom={image}"],
                                            directory, environment, log, args.timeout)
        if restored_pid == initial_pid:
            raise SmokeFailure("restore did not execute in a new process")
        if native:
            if ("NATIVE_PROBE verified cycle=1" not in restored or "NATIVE_PROBE verified cycle=0" in restored
                    or "NATIVE_PROBE PASS cycles=1" not in restored or "native-release=ok" not in restored
                    or "preparationCallbacks=1, restorationCallbacks=1" not in restored):
                raise SmokeFailure(f"native restore did not resume and verify recreated resources; inspect {log}")
        else:
            actual_pid, saved_pid, saved_witness = success_record(restored)
            if actual_pid != restored_pid or saved_pid != initial_pid or saved_witness != witness:
                raise SmokeFailure(f"retained heap/process identity mismatch; inspect {log}")
        print(f"CRAC_SMOKE verified restore={attempt} source-pid={initial_pid} restored-pid={restored_pid}", flush=True)
    image_bytes = sum(path.stat().st_size for path in files)
    print(f"CRAC_SMOKE PASS mode=process-image engine={args.engine} native={str(native).lower()} "
          f"restores={args.restores} image-bytes={image_bytes}; isolated fixture only", flush=True)
    return 0


if __name__ == "__main__":
    def terminate(signum, frame):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, terminate)
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("CRAC_SMOKE interrupted; isolated JVM group stopped", flush=True)
        raise SystemExit(130)
    except (SmokeFailure, OSError) as failure:
        print(f"CRAC_SMOKE FAIL {failure}", flush=True)
        raise SystemExit(1)
