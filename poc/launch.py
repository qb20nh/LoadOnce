#!/usr/bin/env python3
"""Experimental command wrapper: cache class metadata, never application heap state."""

import argparse
import contextlib
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import uuid
import zipfile
from urllib.parse import unquote, urlparse

try:
    import fcntl
except ImportError:
    fcntl = None

SCHEMA = 1
DEFAULT_INPUTS = ("mods", "plugins", "config", "resourcepacks", "options.txt", "server.properties")


class Unsupported(ValueError):
    pass


def digest_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def hash_path(path, digest):
    """Include names, missing inputs, and content; refuse ambiguous symlink trees."""
    path = path.absolute()
    digest.update(str(path).encode() + b"\0")
    if path.is_symlink():
        raise Unsupported("symbolic links in cache inputs require conventional startup")
    if not path.exists():
        digest.update(b"missing\0")
    elif path.is_file():
        digest.update(b"file\0" + digest_file(path).encode() + b"\0")
    elif path.is_dir():
        digest.update(b"directory\0")
        for entry in sorted(path.iterdir()):
            hash_path(entry, digest)
    else:
        raise Unsupported("special files cannot be cache inputs")


def jar_dependencies(path, seen=None):
    """Follow local manifest Class-Path entries; no network or JAR execution."""
    seen = set() if seen is None else seen
    path = path.resolve(strict=True)
    if path in seen:
        return []
    seen.add(path)
    if not path.is_file():
        raise Unsupported("CDS requires JAR classpath entries in this prototype")
    result = [path]
    with zipfile.ZipFile(path) as jar:
        try:
            info = jar.getinfo("META-INF/MANIFEST.MF")
        except KeyError:
            return result
        if info.file_size > 1024 * 1024:
            raise Unsupported("oversized JAR manifest")
        manifest = jar.read(info).decode("utf-8").replace("\r\n", "\n").replace("\n ", "")
    main_section = manifest.split("\n\n", 1)[0]
    for line in main_section.splitlines():
        if not line.lower().startswith("class-path: "):
            continue
        for entry in line.split(": ", 1)[1].split():
            parsed = urlparse(entry)
            if parsed.scheme or parsed.netloc or parsed.query or parsed.fragment:
                raise Unsupported("only relative local JAR manifest dependencies are supported")
            dependency = path.parent / unquote(parsed.path)
            result.extend(jar_dependencies(dependency, seen))
    return result


def parse_launch(command):
    """Recognize ordinary -jar/-cp launches without interpreting application arguments."""
    if not command:
        raise Unsupported("no Java command provided")
    executable = shutil.which(command[0])
    if not executable:
        raise Unsupported("Java executable was not found")
    java = Path(executable).resolve()
    if java.name not in ("java", "java.exe"):
        raise Unsupported("the wrapped command must be a Java executable")
    if any(os.environ.get(name) for name in ("JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH")):
        raise Unsupported("environment-injected JVM options/classpath require conventional startup")
    options, jars, entrypoint = [], [], None
    index = 1
    while index < len(command):
        arg = command[index]
        if arg.startswith("@"):
            raise Unsupported("Java argument files require conventional startup")
        if arg in ("-jar", "-cp", "-classpath", "--class-path"):
            if index + 1 >= len(command):
                raise Unsupported("incomplete Java launch")
            value = command[index + 1]
            if arg == "-jar":
                jars.extend(jar_dependencies(Path(value)))
                entrypoint = ("jar", str(Path(value).resolve()))
                break
            if jars:
                raise Unsupported("multiple classpath options require conventional startup")
            for entry in value.split(os.pathsep):
                if not entry or "*" in entry:
                    raise Unsupported("empty/wildcard classpaths require conventional startup")
                jars.extend(jar_dependencies(Path(entry)))
            options.extend((arg, value))
            index += 2
            continue
        if arg.startswith("--class-path="):
            raise Unsupported("use a separate --class-path value for this prototype")
        if arg in ("-m", "--module", "-p", "--module-path", "--upgrade-module-path", "--patch-module") or arg.startswith(("--module=", "--module-path=", "--patch-module=", "--upgrade-module-path=")):
            raise Unsupported("module launches require conventional startup")
        if arg in ("-version", "--version", "-help", "--help", "-h", "--list-modules"):
            raise Unsupported("no cacheable application entrypoint")
        if arg == "-Xlog:disable" or arg.startswith("-Xlog:cds=off"):
            raise Unsupported("disabled CDS logging requires conventional startup")
        if arg.startswith(("-javaagent", "-agentlib", "-agentpath", "-Xbootclasspath", "-Xshare", "-XX:SharedArchive", "-XX:Archive", "-XX:AOT", "-XX:+AllowArchiving", "-XX:-AllowArchiving", "-Dloadonce.")):
            raise Unsupported("existing agents or archive/runtime controls require conventional startup")
        if arg.startswith("-"):
            options.append(arg)
            if arg in ("--add-opens", "--add-exports", "--add-reads", "--add-modules", "--limit-modules", "--enable-native-access"):
                if index + 1 >= len(command):
                    raise Unsupported("incomplete JVM option")
                options.append(command[index + 1])
                index += 1
            index += 1
            continue
        if not jars or arg.endswith(".java"):
            raise Unsupported("this prototype requires a JAR application or an explicit JAR classpath")
        entrypoint = ("main", arg)
        break
    if not entrypoint:
        raise Unsupported("no cacheable application entrypoint")
    return java, options, jars, entrypoint


def fingerprint(launch, inputs, runtime_jar):
    java, options, jars, entrypoint = launch
    digest = hashlib.sha256()
    # Only digests are persisted. Application arguments, credentials, and environment
    # values are never written to the cache; the original JVM reads them every time.
    digest.update(json.dumps((SCHEMA, platform.system(), platform.machine(), str(Path.cwd()),
                              str(java), options, entrypoint), separators=(",", ":")).encode())
    hash_path(Path(__file__).resolve(), digest)
    for runtime_file in (java, java.parent.parent / "release", java.parent.parent / "lib/modules"):
        hash_path(runtime_file, digest)
    for native in sorted((java.parent.parent / "lib").glob("**/*jvm*")):
        if native.is_file():
            hash_path(native, digest)
    for path in jars:
        hash_path(path, digest)
    if runtime_jar:
        hash_path(runtime_jar, digest)
    for path in inputs:
        hash_path(path, digest)
    return digest.hexdigest()


def private_directory(path):
    if path.is_symlink():
        raise Unsupported("cache directory must not be a symbolic link")
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = path.stat()
    if not path.is_dir() or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise Unsupported("cache directory must be owned by this user with mode 0700")


@contextlib.contextmanager
def cache_lock(cache):
    descriptor = os.open(cache / ".lock", os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            yield False
        else:
            yield True
    finally:
        os.close(descriptor)


def valid_archive(entry, key):
    try:
        if entry.is_symlink() or (entry / "manifest.json").is_symlink() or (entry / "classes.jsa").is_symlink():
            return False
        manifest = json.loads((entry / "manifest.json").read_text())
        return (manifest["schemaVersion"] == SCHEMA and manifest["fingerprint"] == key
                and (entry / "classes.jsa").stat().st_size > 0
                and manifest["archiveSha256"] == digest_file(entry / "classes.jsa"))
    except (OSError, ValueError, KeyError, TypeError):
        return False


def execute(command, execution=None):
    # No shell and no retry after the application starts: a failed server may have
    # already written its world or contacted an external service.
    process = subprocess.Popen(command)
    if execution is not None:
        execution["started"] = True
    try:
        code = process.wait()
    except KeyboardInterrupt:
        process.send_signal(signal.SIGINT)
        code = process.wait()
    if execution is not None:
        execution["exitCode"] = code
    return code


def probe(java, directory, agent_options, runtime_jar):
    agent = [f"-javaagent:{runtime_jar}"] if runtime_jar else []
    result = subprocess.run([str(java), *agent_options, *agent, "-Xshare:auto", f"-XX:ArchiveClassesAtExit={directory / 'probe.jsa'}", "-version"],
                            capture_output=True, timeout=30)
    return result.returncode == 0


def read_readiness(path, run_id, target):
    try:
        report = json.loads(path.read_text())
        milestone = "client_menu_ready" if target == "client" else "server_ready"
        if (report["schemaVersion"] == 1 and report["runId"] == run_id
                and report["milestone"] == milestone and type(report["elapsedNanos"]) is int
                and report["elapsedNanos"] >= 0):
            return {"milestone": milestone, "elapsedNanos": report["elapsedNanos"]}
    except (OSError, ValueError, KeyError, TypeError):
        pass
    return None


def launch_cached(args, command, execution):
    began = time.monotonic_ns()
    launch = parse_launch(command)
    if fcntl is None or not hasattr(os, "O_NOFOLLOW"):
        raise Unsupported("cache locking currently requires a Unix platform")
    runtime_jar = args.runtime_jar.resolve(strict=True) if args.runtime_jar else None
    if runtime_jar and not runtime_jar.is_file():
        raise Unsupported("runtime agent must be a JAR file")
    if runtime_jar and "=" in str(runtime_jar):
        raise Unsupported("agent paths containing '=' require conventional startup")
    cache = args.cache_dir.absolute()
    if any(character in str(cache) for character in (":", ",", "\n", "\r")):
        raise Unsupported("cache path contains JVM logging/archive delimiters")
    private_directory(cache)
    inputs = [Path(name).absolute() for name in DEFAULT_INPUTS] + [path.absolute() for path in args.input]
    # Avoid recursively fingerprinting files produced by this launcher.
    for path in inputs:
        if path == cache or path in cache.parents:
            raise Unsupported("cache directory cannot be inside a fingerprint input")
    key = fingerprint(launch, inputs, runtime_jar)
    entry = cache / key
    run_id = uuid.uuid4().hex
    with tempfile.TemporaryDirectory(prefix="run-", dir=cache) as temporary:
        temporary = Path(temporary)
        # HotSpot requires explicit opt-in even for our non-transforming agent.
        # Probe these diagnostic options before starting the real application.
        agent_options = ["-XX:+UnlockDiagnosticVMOptions", "-XX:+AllowArchivingWithJavaAgent"] if runtime_jar else []
        if not probe(launch[0], temporary, agent_options, runtime_jar):
            raise Unsupported("this JVM does not support Dynamic CDS")
        report_path, log_path = temporary / "ready.json", temporary / "cds.log"
        extra = [*agent_options, "-Xshare:auto", f"-Xlog:cds=info:file={log_path}"]
        if runtime_jar:
            extra.extend((f"-javaagent:{runtime_jar}", f"-Dloadonce.report={report_path}", f"-Dloadonce.runId={run_id}"))
        with cache_lock(cache) as acquired:
            if not acquired:
                raise Unsupported("another launch is training this cache")
            hit = valid_archive(entry, key)
            action = "selected" if hit else "training"
            archive = entry / "classes.jsa" if hit else temporary / "classes.jsa"
            extra.append(f"-XX:SharedArchiveFile={archive}" if hit else f"-XX:ArchiveClassesAtExit={archive}")
            print(f"LoadOnce: {action} class archive; ordinary application initialization continues", file=sys.stderr)
            preparation = time.monotonic_ns() - began
            # Keep one writer until clean exit. Concurrent launches take the original
            # path without waiting for an interactive client/server to shut down.
            code = execute([command[0], *extra, *command[1:]], execution)
            try:
                log = log_path.read_text()
            except OSError:
                log = ""
            mapped = hit and "Mapped dynamic region #0" in log
            published = False
            if not hit and code == 0 and archive.is_file() and archive.stat().st_size > 0:
                try:
                    if fingerprint(launch, inputs, runtime_jar) == key:
                        if entry.is_symlink():
                            raise Unsupported("archive entry must not be a symbolic link")
                        entry.mkdir(mode=0o700, exist_ok=True)
                        # A manifest is the commit marker; replace it last.
                        os.replace(archive, entry / "classes.jsa")
                        manifest = {"schemaVersion": SCHEMA, "backend": "dynamic-cds", "fingerprint": key,
                                    "archiveSha256": digest_file(entry / "classes.jsa")}
                        staged = temporary / "manifest.json"
                        staged.write_text(json.dumps(manifest, indent=2) + "\n")
                        os.chmod(staged, 0o600)
                        os.replace(staged, entry / "manifest.json")
                        published = True
                        print("LoadOnce: class archive published for the next launch", file=sys.stderr)
                    else:
                        print("LoadOnce: inputs changed during training; archive discarded", file=sys.stderr)
                except (OSError, Unsupported):
                    print("LoadOnce: archive publication failed; normal launch result preserved", file=sys.stderr)
            if hit and not mapped:
                # VM fallback occurred before main. Re-record on the next launch,
                # never rerun the application in this invocation.
                (entry / "manifest.json").unlink(missing_ok=True)
                print("LoadOnce: JVM declined archive; ordinary startup used", file=sys.stderr)
            readiness = read_readiness(report_path, run_id, args.target)
            if readiness:
                print(f"LoadOnce: {readiness['milestone']} after {readiness['elapsedNanos'] / 1e6:.3f} ms in JVM", file=sys.stderr)
            summary = {"schemaVersion": SCHEMA, "backend": "dynamic-cds", "cacheAction": action,
                       "archivePublished": published, "sharedArchiveLoaded": mapped,
                       "launcherPreparationNanos": preparation, "totalNanos": time.monotonic_ns() - began,
                       "exitCode": code, "readiness": readiness}
            return code, summary


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache-dir", type=Path, default=Path(".loadonce-cache"))
    parser.add_argument("--input", type=Path, action="append", default=[], help="additional immutable/configuration input to fingerprint")
    parser.add_argument("--runtime-jar", type=Path, help="optional LoadOnce readiness Java agent")
    parser.add_argument("--target", choices=("client", "server"), default="server")
    parser.add_argument("--report", type=Path, help="write this launch's metrics, with no command or credentials")
    parser.add_argument("--off", action="store_true", help="execute the original command")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args(argv)
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("provide the original Java command after --")
    execution = {"started": False}
    try:
        if args.off:
            code = execute(command, execution)
            summary = {"schemaVersion": SCHEMA, "backend": "conventional", "cacheAction": "off", "exitCode": code}
        else:
            code, summary = launch_cached(args, command, execution)
    except (Unsupported, OSError, ValueError, zipfile.BadZipFile, subprocess.TimeoutExpired) as error:
        if execution["started"]:
            print("LoadOnce: cache bookkeeping failed; application will not be restarted", file=sys.stderr)
            code = execution.get("exitCode", 1)
            summary = {"schemaVersion": SCHEMA, "backend": "dynamic-cds", "cacheAction": "error", "exitCode": code}
        else:
            reason = str(error) if isinstance(error, Unsupported) else "cache preparation unavailable"
            print(f"LoadOnce: {reason}; conventional startup", file=sys.stderr)
            try:
                code = execute(command, execution)
            except OSError:
                print("LoadOnce: could not start the original command", file=sys.stderr)
                code = 127
            summary = {"schemaVersion": SCHEMA, "backend": "conventional", "cacheAction": "fallback", "exitCode": code}
    if args.report:
        try:
            args.report.write_text(json.dumps(summary, indent=2) + "\n")
            os.chmod(args.report, 0o600)
        except OSError:
            print("LoadOnce: could not write metrics report", file=sys.stderr)
    return 128 - code if code < 0 else code


if __name__ == "__main__":
    sys.exit(main())
