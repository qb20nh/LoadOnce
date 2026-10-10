#!/usr/bin/env python3
"""Experimental JDK 25 AOT command wrapper; application state initializes afresh."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import zipfile

try:
    from . import launch as shared
except ImportError:
    import launch as shared

SCHEMA = 1
BACKEND = "leyden-aot"


def parse_launch(command):
    parsed = shared.parse_launch(command)
    if os.environ.get("JDK_AOT_VM_OPTIONS"):
        raise shared.Unsupported("environment-injected AOT assembly options require conventional startup")
    for option in parsed[1]:
        # Assembly echoes system properties via JAVA_TOOL_OPTIONS. Until there is
        # a safe integration contract, do not expose potential JVM-side secrets.
        if option.startswith("-D"):
            raise shared.Unsupported("JVM system properties require conventional startup with this AOT prototype")
        if option.startswith("-Xlog"):
            raise shared.Unsupported("custom JVM logging requires conventional startup with this AOT prototype")
        if option.startswith(("-XX:+AOT", "-XX:-AOT", "-XX:+AutoCreateSharedArchive", "-XX:-AutoCreateSharedArchive")):
            raise shared.Unsupported("existing AOT controls require conventional startup")
    return parsed


def fingerprint(parsed, inputs):
    digest = hashlib.sha256()
    digest.update(f"{BACKEND}:{SCHEMA}\0".encode())
    digest.update(shared.fingerprint(parsed, inputs, None).encode())
    shared.hash_path(Path(__file__).resolve(), digest)
    return digest.hexdigest()


def valid_archive(entry, key):
    try:
        if any(path.is_symlink() for path in (entry, entry / "manifest.json", entry / "classes.aot")):
            return False
        info = entry.stat()
        if info.st_uid != os.getuid() or info.st_mode & 0o077:
            return False
        manifest = json.loads((entry / "manifest.json").read_text())
        return (manifest["schemaVersion"] == SCHEMA and manifest["backend"] == BACKEND
                and manifest["fingerprint"] == key and (entry / "classes.aot").stat().st_size > 0
                and manifest["archiveSha256"] == shared.digest_file(entry / "classes.aot"))
    except (OSError, ValueError, KeyError, TypeError):
        return False


def probe(parsed, temporary):
    # Off mode validates JEP 514 flags without a training/assembly JVM on every
    # launch. -version never enters application main, even with its classpath.
    result = subprocess.run([str(parsed[0]), *parsed[1], "-XX:AOTMode=off",
                             f"-XX:AOTCacheOutput={temporary / 'probe.aot'}", "-version"],
                            capture_output=True, timeout=30)
    return result.returncode == 0


def archive_loaded(log, archive):
    # Default JDK classes can also be mapped after the VM rejects an application
    # archive. Require the chosen file and its AOT-linked classes, not any map.
    return (f"Opened AOT cache {archive}." in log and "Mapped static  region #0" in log
            and "Using AOT-linked classes: true" in log)


def launch_cached(args, command, execution):
    began = time.monotonic_ns()
    parsed = parse_launch(command)
    if shared.fcntl is None or not hasattr(os, "O_NOFOLLOW"):
        raise shared.Unsupported("cache locking currently requires a Unix platform")
    cache = args.cache_dir.absolute()
    if any(character in str(cache) for character in (":", ",", "\n", "\r", "%")):
        raise shared.Unsupported("cache path contains JVM logging/archive delimiters")
    shared.private_directory(cache)
    inputs = [Path(name).absolute() for name in shared.DEFAULT_INPUTS] + [path.absolute() for path in args.input]
    for path in inputs:
        if path == cache or path in cache.parents:
            raise shared.Unsupported("cache directory cannot be inside a fingerprint input")
    key = fingerprint(parsed, inputs)
    entry = cache / key
    with tempfile.TemporaryDirectory(prefix="run-", dir=cache) as temporary:
        temporary = Path(temporary)
        if not probe(parsed, temporary):
            raise shared.Unsupported("this JVM or its options do not support JDK 25 automatic AOT caches")
        with shared.cache_lock(cache) as acquired:
            if not acquired:
                raise shared.Unsupported("another launch is training this cache")
            hit = valid_archive(entry, key)
            action = "selected" if hit else "training"
            archive = entry / "classes.aot" if hit else temporary / "classes.aot"
            log_path = temporary / "aot.log"
            extra = ["-XX:AOTMode=auto", f"-Xlog:aot=info:file={log_path}",
                     f"-XX:AOTCache={archive}" if hit else f"-XX:AOTCacheOutput={archive}"]
            print(f"LoadOnce: {action} JDK AOT cache; ordinary application initialization continues", file=sys.stderr)
            preparation = time.monotonic_ns() - began
            # JEP 514 starts an assembly JVM after exit. That JVM does not invoke
            # main. Never retry the application after starting this one process.
            code = shared.execute([command[0], *extra, *command[1:]], execution)
            try:
                log = log_path.read_text()
            except OSError:
                log = ""
            mapped = hit and archive_loaded(log, archive)
            published = False
            if not hit and code == 0 and archive.is_file() and archive.stat().st_size > 0:
                try:
                    if fingerprint(parsed, inputs) == key:
                        shared.private_directory(entry)
                        os.chmod(archive, 0o600)
                        os.replace(archive, entry / "classes.aot")
                        manifest = {"schemaVersion": SCHEMA, "backend": BACKEND, "fingerprint": key,
                                    "archiveSha256": shared.digest_file(entry / "classes.aot")}
                        staged = temporary / "manifest.json"
                        staged.write_text(json.dumps(manifest, indent=2) + "\n")
                        os.chmod(staged, 0o600)
                        os.replace(staged, entry / "manifest.json")
                        published = True
                        print("LoadOnce: AOT cache published for the next launch", file=sys.stderr)
                    else:
                        print("LoadOnce: inputs changed during training; AOT cache discarded", file=sys.stderr)
                except (OSError, shared.Unsupported):
                    print("LoadOnce: AOT publication failed; original launch result preserved", file=sys.stderr)
            if hit and not mapped:
                (entry / "manifest.json").unlink(missing_ok=True)
                print("LoadOnce: JVM declined AOT cache; no application retry", file=sys.stderr)
            return code, {"schemaVersion": SCHEMA, "backend": BACKEND, "cacheAction": action,
                          "archivePublished": published, "aotCacheLoaded": mapped,
                          "launcherPreparationNanos": preparation,
                          "totalNanos": time.monotonic_ns() - began, "exitCode": code}


def write_report(path, summary):
    # The report never contains the launch command. Publish privately and replace
    # symlinks rather than following a pre-existing metrics file.
    with tempfile.NamedTemporaryFile(mode="w", prefix=".loadonce-report-", dir=path.absolute().parent,
                                     delete=False) as stream:
        staged = Path(stream.name)
        try:
            json.dump(summary, stream, indent=2)
            stream.write("\n")
            stream.flush()
            os.replace(staged, path)
        finally:
            staged.unlink(missing_ok=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache-dir", type=Path, default=Path(".loadonce-leyden-cache"))
    parser.add_argument("--input", type=Path, action="append", default=[],
                        help="additional immutable/configuration input to fingerprint")
    parser.add_argument("--report", type=Path, help="write metrics without command or credentials")
    parser.add_argument("--off", action="store_true", help="execute the original command")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args(argv)
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("provide the original Java command after --")
    execution = {"started": False}
    try:
        if args.off:
            code = shared.execute(command, execution)
            summary = {"schemaVersion": SCHEMA, "backend": "conventional", "cacheAction": "off", "exitCode": code}
        else:
            code, summary = launch_cached(args, command, execution)
    except (shared.Unsupported, OSError, ValueError, zipfile.BadZipFile, subprocess.TimeoutExpired) as error:
        if execution["started"]:
            print("LoadOnce: AOT bookkeeping failed; application will not be restarted", file=sys.stderr)
            code = execution.get("exitCode", 1)
            summary = {"schemaVersion": SCHEMA, "backend": BACKEND, "cacheAction": "error", "exitCode": code}
        else:
            reason = str(error) if isinstance(error, shared.Unsupported) else "AOT preparation unavailable"
            print(f"LoadOnce: {reason}; conventional startup", file=sys.stderr)
            try:
                code = shared.execute(command, execution)
            except OSError:
                print("LoadOnce: could not start the original command", file=sys.stderr)
                code = 127
            summary = {"schemaVersion": SCHEMA, "backend": "conventional", "cacheAction": "fallback", "exitCode": code}
    if args.report:
        try:
            write_report(args.report, summary)
        except OSError:
            print("LoadOnce: could not write metrics report", file=sys.stderr)
    return 128 - code if code < 0 else code


if __name__ == "__main__":
    sys.exit(main())
