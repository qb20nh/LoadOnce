#!/usr/bin/env python3
"""Private CRaC capture and single-launch fallback for two isolated fixtures.

This deliberately accepts no arbitrary Java/application command. It is not a
Minecraft checkpoint launcher. A failed restore is returned without replay.
Native provider files outside the classpath must be declared with --input.
"""

from __future__ import annotations

import argparse
import contextlib
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import signal
import subprocess
import tempfile

import loadonce

SCHEMA = 1
CPU = "dev.loadonce.runtime.CracSmoke"
NATIVE = "dev.loadonce.prototype.NativeRestorationProbe"
FIELDS = {"schema", "completed", "fixture", "engine", "fingerprint", "generation",
          "sha256", "bytes", "uid", "source_pid", "heap_witness"}


class FixtureError(loadonce.CacheError):
    pass


@dataclass(frozen=True)
class Fixture:
    java: Path
    classpath: str
    native: bool = False
    engine: str = "warp"
    extra_inputs: tuple[Path, ...] = ()
    native_flags: tuple[str, ...] = ()

    def __post_init__(self):
        if self.engine not in ("warp", "criuengine"):
            raise FixtureError("only actual Warp/CRIU process images are supported")
        if not self.classpath or "\n" in self.classpath or any(
                not part or not Path(part).is_absolute() or Path(part).name == "*"
                for part in self.classpath.split(os.pathsep)):
            raise FixtureError("fixture classpath must contain explicit absolute paths")
        if self.native_flags not in ((), ("--reconfigure",)) or (self.native_flags and not self.native):
            raise FixtureError("unsupported isolated fixture arguments")
        object.__setattr__(self, "java", Path(loadonce.executable(str(self.java))))
        object.__setattr__(self, "extra_inputs", tuple(Path(p).absolute() for p in self.extra_inputs))

    @property
    def application(self) -> str:
        return NATIVE if self.native else CPU

    def arguments(self, checkpoint: bool) -> list[str]:
        args = ["-cp", self.classpath, self.application]
        if self.native:
            args += ["--cycles=1", "--audio-loopback", *self.native_flags]
            if checkpoint:
                args.append("--crac")
        else:
            args.append("success" if checkpoint else "cold")
        return args


@dataclass(frozen=True)
class Outcome:
    mode: str
    returncode: int
    pid: int
    log: Path
    output: str
    reason: str = ""


def environment(fixture: Fixture, directory: Path) -> dict[str, str]:
    result = {"PATH": "/usr/bin:/bin", "HOME": str(directory / "home"), "LANG": "C.UTF-8"}
    if fixture.native:
        display = os.environ.get("DISPLAY", "")
        if not re.fullmatch(r":\d+(?:\.\d+)?", display):
            raise FixtureError("native fixture requires an explicit local X11 DISPLAY")
        result.update(DISPLAY=display, LIBGL_ALWAYS_SOFTWARE="1", ALSOFT_DRIVERS="null",
                      XDG_RUNTIME_DIR=str(directory / "xdg"),
                      MESA_SHADER_CACHE_DIR=str(directory / "mesa-cache"))
        if os.environ.get("XAUTHORITY"):
            result["XAUTHORITY"] = str(Path(os.environ["XAUTHORITY"]).resolve(strict=True))
    return result


def _workspace(root: Path | None = None) -> Path:
    directory = Path(tempfile.mkdtemp(prefix=".capture-" if root else "loadonce-fixture-", dir=root))
    for child in ("home", "tmp", "xdg", "mesa-cache"):
        (directory / child).mkdir(mode=0o700)
    return directory


def _base(fixture: Fixture, directory: Path) -> list[str]:
    args = [str(fixture.java), f"-Duser.home={directory / 'home'}",
            f"-Djava.io.tmpdir={directory / 'tmp'}", "-Xms32m", "-Xmx128m"]
    if fixture.native:
        args += ["--enable-native-access=ALL-UNNAMED",
                 f"-Dorg.lwjgl.system.SharedLibraryExtractPath={directory / 'tmp' / 'lwjgl'}"]
    return args


def fingerprint(fixture: Fixture, directory: Path) -> str:
    clean = environment(fixture, directory)
    digest = hashlib.sha256()
    digest.update(json.dumps({"schema": SCHEMA, "engine": fixture.engine,
                             "command": [*_base(fixture, directory), *fixture.arguments(True)],
                             "environment": clean,
                             "platform": [platform.system(), platform.machine(), platform.release()]},
                            sort_keys=True).encode())
    home = fixture.java.parent.parent
    inputs = [fixture.java, home / "release", home / "lib", home / "conf",
              *(Path(p) for p in fixture.classpath.split(os.pathsep)), *fixture.extra_inputs]
    # Java may resolve local Class-Path manifest entries outside the listed JARs.
    # Reuse the launcher's parser without consulting inherited JVM options,
    # which the fixture deliberately never forwards to its child.
    pending = [Path(p) for p in fixture.classpath.split(os.pathsep)]
    seen: set[Path] = set()
    while pending:
        path = pending.pop().absolute()
        if path in seen:
            continue
        seen.add(path)
        added = loadonce._manifest_paths(path)
        inputs.extend(added)
        pending.extend(added)
    if "XAUTHORITY" in clean:
        inputs.append(Path(clean["XAUTHORITY"]))
    for path in sorted(set(inputs)):
        loadonce._digest_path(digest, path, set())
    return digest.hexdigest()


def _execute(command: list[str], directory: Path, clean: dict[str, str], timeout: float, mode: str) -> Outcome:
    if not 1 <= timeout <= 60:
        raise FixtureError("per-process timeout must be between 1 and 60 seconds")
    descriptor, name = tempfile.mkstemp(prefix=mode + "-", suffix=".log", dir=directory)
    log = Path(name)
    with os.fdopen(descriptor, "wb") as output:
        with subprocess.Popen(command, cwd=directory, env=clean, stdout=output,
                              stderr=subprocess.STDOUT, close_fds=True, umask=0o077,
                              start_new_session=True) as child:
            try:
                code = child.wait(timeout=timeout)
            except BaseException:
                try:
                    os.killpg(child.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                child.wait()
                raise
            pid = child.pid
    return Outcome(mode, code, pid, log, log.read_text(errors="replace"))


def _payload(fixture: Fixture, directory: Path) -> tuple[str, int]:
    image = directory / "image"
    expected = image / ("core.img" if fixture.engine == "warp" else "inventory.img")
    digest = hashlib.sha256()
    size = 0
    substantial = False
    for root in (image, directory / "tmp"):
        loadonce._private(root, True)
        for path in [root, *root.rglob("*")]:
            # No links, devices, sockets or multiply-linked payloads may be restored.
            loadonce._private(path, path.is_dir() and not path.is_symlink())
            if path.is_file():
                count = path.stat().st_size
                size += count
                substantial |= root == image and count > 4096
        loadonce._digest_path(digest, root, set())
    loadonce._private(expected, False)
    if expected.stat().st_size == 0 or not substantial:
        raise FixtureError("engine image is incomplete")
    sidecar = image / "engine"
    if sidecar.exists() and sidecar.read_text().strip() != fixture.engine:
        raise FixtureError("engine image belongs to a different engine")
    return digest.hexdigest(), size


def _boundary(fixture: Fixture, result: Outcome, directory: Path) -> str | None:
    output = result.output
    if fixture.engine == "warp":
        if result.returncode != 0 or "warp: Checkpoint successful!" not in output:
            raise FixtureError("Warp checkpoint did not finish successfully")
    else:
        # This CRaC JDK writes dump4.log; other CRIU integrations use dump.log.
        dumps = [path for path in (directory / "image").glob("dump*.log")
                 if re.fullmatch(r"dump\d*\.log", path.name)]
        if result.returncode not in (0, -signal.SIGKILL) or not any(
                path.is_file() and "Dumping finished successfully" in path.read_text(errors="replace")
                for path in dumps):
            raise FixtureError("CRIU checkpoint completion evidence is missing")
    if fixture.native:
        if "NATIVE_PROBE verified cycle=0" not in output or "NATIVE_PROBE PASS" in output:
            raise FixtureError("native fixture did not stop at its prepared boundary")
        return None
    initial = re.search(r"CRAC_SMOKE initial-pid=(\d+) heap-witness=([0-9a-f-]+)", output)
    if initial is None or int(initial[1]) != result.pid or "restored-pid=" in output:
        raise FixtureError("CPU fixture did not stop at its prepared boundary")
    return initial[2]


def capture(fixture: Fixture, root_path: Path, timeout: float = 60) -> Outcome:
    """Run this fixture once explicitly; atomically publish only complete images."""
    root = loadonce.cache_root(root_path)
    with loadonce.cache_lock(root, True):
        directory = _workspace(root)
        (directory / "image").mkdir(mode=0o700)
        before = fingerprint(fixture, directory)
        options = ["-XX:CRaCEngineOptions=checkpoint_exit_code=0"] if fixture.engine == "warp" else []
        result = _execute([*_base(fixture, directory), f"-XX:CRaCEngine={fixture.engine}",
                           f"-XX:CRaCCheckpointTo={directory / 'image'}", *options,
                           *fixture.arguments(True)], directory, environment(fixture, directory), timeout, "capture")
        witness = _boundary(fixture, result, directory)
        checksum, size = _payload(fixture, directory)
        if fingerprint(fixture, directory) != before:
            raise FixtureError("fixture inputs changed during capture; image not promoted")
        record = {"schema": SCHEMA, "completed": True, "fixture": fixture.application,
                  "engine": fixture.engine, "fingerprint": before, "generation": directory.name,
                  "sha256": checksum, "bytes": size, "uid": os.getuid(),
                  "source_pid": result.pid, "heap_witness": witness}
        # Keep all embedded absolute fixture paths stable. Only the completion
        # marker is replaced atomically; failed capture retains the previous one.
        descriptor, name = tempfile.mkstemp(prefix=".manifest-", dir=root)
        pending = Path(name)
        try:
            with os.fdopen(descriptor, "w") as destination:
                destination.write(json.dumps(record, sort_keys=True) + "\n")
                destination.flush()
                os.fsync(destination.fileno())
            for tree in (directory / "image", directory / "tmp"):
                for path in tree.rglob("*"):
                    if path.is_file():
                        loadonce._sync(path)
                loadonce._sync(tree)
            loadonce._sync(directory)
            pending.replace(root / "manifest.json")
            loadonce._sync(root)
        finally:
            pending.unlink(missing_ok=True)
        return result


def valid_image(fixture: Fixture, root: Path) -> tuple[Path, dict]:
    manifest = root / "manifest.json"
    loadonce._private(manifest, False)
    if manifest.stat().st_size > 4096:
        raise FixtureError("invalid fixture manifest")
    try:
        record = json.loads(manifest.read_text())
    except (ValueError, UnicodeError) as error:
        raise FixtureError("invalid fixture manifest") from error
    if not isinstance(record, dict) or set(record) != FIELDS or (
            type(record["schema"]) is not int or record["schema"] != SCHEMA
            or record["completed"] is not True or record["fixture"] != fixture.application
            or record["engine"] != fixture.engine or type(record["uid"]) is not int
            or record["uid"] != os.getuid() or type(record["source_pid"]) is not int
            or record["source_pid"] <= 0 or type(record["bytes"]) is not int or record["bytes"] <= 0
            or not isinstance(record["generation"], str)
            or re.fullmatch(r"\.capture-[a-zA-Z0-9_]+", record["generation"]) is None
            or (record["heap_witness"] is not None if fixture.native else
                not isinstance(record["heap_witness"], str)
                or re.fullmatch(r"[0-9a-f-]+", record["heap_witness"]) is None)):
        raise FixtureError("incomplete or incompatible fixture manifest")
    directory = root / record["generation"]
    for path in (directory, *(directory / name for name in ("home", "tmp", "xdg", "mesa-cache"))):
        loadonce._private(path, True)
    if record["fingerprint"] != fingerprint(fixture, directory):
        raise FixtureError("fixture inputs are incompatible")
    checksum, size = _payload(fixture, directory)
    if record["sha256"] != checksum or record["bytes"] != size:
        raise FixtureError("fixture payload is corrupt")
    return directory, record


def _verify_restore(fixture: Fixture, result: Outcome, record: dict) -> None:
    if result.returncode != 0:
        return
    if result.pid == record["source_pid"]:
        raise FixtureError("restore did not run in a fresh process; no replay attempted")
    if fixture.native:
        if ("NATIVE_PROBE verified cycle=1" not in result.output or "NATIVE_PROBE verified cycle=0" in result.output
                or "NATIVE_PROBE PASS cycles=1" not in result.output or "native-release=ok" not in result.output
                or "preparationCallbacks=1, restorationCallbacks=1" not in result.output):
            raise FixtureError("native restore evidence is incomplete; no replay attempted")
    else:
        match = re.search(r"CRAC_SMOKE restored-pid=(\d+) initial-pid=(\d+) "
                          r"heap-witness=([0-9a-f-]+) value=42 coordinator-state=READY", result.output)
        if (match is None or int(match[1]) != result.pid or int(match[2]) != record["source_pid"]
                or match[3] != record["heap_witness"]
                or "preparationCallbacks=1, restorationCallbacks=1" not in result.output):
            raise FixtureError("CPU restore evidence is incomplete; no replay attempted")


def run(fixture: Fixture, root_path: Path, timeout: float = 60) -> Outcome:
    """Choose restore or fresh cold startup before launching exactly one child."""
    with contextlib.ExitStack() as stack:
        try:
            root = loadonce.cache_root(root_path)
            stack.enter_context(loadonce.cache_lock(root, False))
            directory, record = valid_image(fixture, root)
        except (loadonce.CacheError, OSError, RuntimeError):
            stack.close()
            directory = _workspace()
            result = _execute([*_base(fixture, directory), *fixture.arguments(False)], directory,
                              environment(fixture, directory), timeout, "cold")
            return Outcome(result.mode, result.returncode, result.pid, result.log, result.output,
                           "compatible complete fixture image unavailable")
        result = _execute([*_base(fixture, directory), f"-XX:CRaCRestoreFrom={directory / 'image'}"],
                          directory, environment(fixture, directory), timeout, "restore")
        _verify_restore(fixture, result, record)
        return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("capture", "run"))
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--cache-dir", type=Path, required=True)
    parser.add_argument("--classpath-file", type=Path, required=True)
    parser.add_argument("--native", action="store_true")
    parser.add_argument("--reconfigure", action="store_true", help="fixed native settings preservation fixture")
    parser.add_argument("--input", type=Path, action="append", default=[], help="explicit native/fixture dependency; repeatable")
    parser.add_argument("--engine", choices=("warp", "criuengine"), default="warp")
    parser.add_argument("--timeout", type=float, default=60)
    args = parser.parse_args(argv)
    try:
        fixture = Fixture(args.java, args.classpath_file.read_text().strip(), args.native, args.engine,
                          tuple(args.input), ("--reconfigure",) if args.reconfigure else ())
        result = (capture if args.action == "capture" else run)(fixture, args.cache_dir, args.timeout)
        print(f"CRAC_FIXTURE mode={result.mode} exit={result.returncode} pid={result.pid} log={result.log}; "
              "isolated fixture only; no replay after child startup", flush=True)
        return 128 - result.returncode if result.returncode < 0 else result.returncode
    except KeyboardInterrupt:
        return 130
    except (loadonce.CacheError, OSError, subprocess.TimeoutExpired):
        print("CRAC_FIXTURE failed; inspect private fixture logs; no automatic replay", flush=True)
        return 1


if __name__ == "__main__":
    def terminate(signum, frame):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, terminate)
    raise SystemExit(main())
