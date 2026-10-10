#!/usr/bin/env python3
"""Conservative Java command wrapper for JDK 25 Leyden AOT caches.

This caches JVM class/linking data, not an application heap or native handles.
Training explicitly runs the application once. No launch is ever replayed.
"""

from __future__ import annotations

import argparse
import contextlib
import fcntl
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import urllib.parse
import zipfile

SCHEMA = 2  # Reject archives trained before credential-environment safeguards.
JVM_ENV = ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JDK_AOT_VM_OPTIONS")
NATIVE_ENV = ("LD_LIBRARY_PATH", "LD_PRELOAD", "DYLD_LIBRARY_PATH", "DYLD_INSERT_LIBRARIES")
FINGERPRINT_ENV = JVM_ENV + NATIVE_ENV + ("CLASSPATH",)
# Explicit names avoid treating unrelated session/configuration variables as secrets.
# This cannot discover credentials an arbitrary application reads from files or custom variables.
CREDENTIAL_ENV = frozenset({
    "TOKEN", "ACCESS_TOKEN", "AUTH_TOKEN", "REFRESH_TOKEN", "SESSION_TOKEN", "API_KEY",
    "PASSWORD", "CLIENT_SECRET", "GH_TOKEN", "GITHUB_TOKEN", "GITHUB_PAT", "GITLAB_TOKEN",
    "CI_JOB_TOKEN", "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN",
    "AWS_SECURITY_TOKEN", "AWS_WEB_IDENTITY_TOKEN_FILE", "GOOGLE_APPLICATION_CREDENTIALS",
    "GOOGLE_OAUTH_ACCESS_TOKEN", "CLOUDSDK_AUTH_ACCESS_TOKEN", "AZURE_CLIENT_SECRET",
    "AZURE_CLIENT_CERTIFICATE_PASSWORD", "AZURE_FEDERATED_TOKEN_FILE", "OPENAI_API_KEY",
    "ANTHROPIC_API_KEY", "MINECRAFT_ACCESS_TOKEN", "MC_ACCESS_TOKEN", "MICROSOFT_ACCESS_TOKEN",
    "MS_ACCESS_TOKEN", "XBOX_TOKEN", "XSTS_TOKEN", "DISCORD_TOKEN", "NPM_TOKEN", "SSH_AUTH_SOCK",
})
PROXY_ENV = ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy")
PROXY_CREDENTIAL = re.compile(r"://[^/\s@]+@")
NATIVE_PATH_OPTIONS = ("-Djava.library.path=", "-Dorg.lwjgl.librarypath=")
SECRET = re.compile(
    r"(?i)(?:(?:access|refresh|auth|id).?token|jwt|authorization|session(?:.?token|.?id)?|password|passwd|secret|"
    r"credential|api.?key|bearer|(?:^|[-_.])auth(?:entication)?(?:[-_.=:]|$)|"
    r"(?:^|[-_.])token(?:[-_.=:]|$)|://[^/\s:@]+:[^/\s@]+@)"
)


class CacheError(Exception):
    pass


def message(value: str) -> None:
    print("loadonce: " + value, file=sys.stderr)


def probe_env() -> dict[str, str]:
    # Java echoes injected options, which can contain authentication values.
    return {key: value for key, value in os.environ.items() if key not in JVM_ENV}


def probe_java(java: str) -> dict:
    result = subprocess.run(
        [java, "-XX:+PrintFlagsFinal", "-version"], env=probe_env(),
        capture_output=True, text=True, timeout=20,
    )
    output = result.stdout + result.stderr
    flags = set(re.findall(r"^\s*\w+\s+(\w+)\s+=", output, re.M))
    version = re.search(r'(?:java|openjdk) version "([^"]+)"', output)
    return {
        "version": version.group(1) if version else "unknown",
        "leyden": result.returncode == 0 and {"AOTCacheOutput", "AOTCache", "AOTMode"} <= flags,
        "crac_flags": {"CRaCCheckpointTo", "CRaCRestoreFrom"} <= flags,
    }


def doctor(java: str) -> dict:
    info = probe_java(java)
    javap = Path(java).resolve().parent / "javap"
    core_classes = []
    if javap.is_file():
        for name in ("jdk.crac.Core", "org.crac.Core"):
            result = subprocess.run(
                [str(javap), name], env=probe_env(), capture_output=True, timeout=20,
            )
            if result.returncode == 0:
                core_classes.append(name)
    info["crac_core_classes"] = core_classes
    info["crac"] = "capability-present" if info["crac_flags"] and core_classes else "unavailable"
    criu = shutil.which("criu")
    info["criu"] = {"executable": criu, "status": "unavailable"}
    if criu:
        try:
            with tempfile.TemporaryDirectory(prefix="loadonce-criu-check-") as temporary:
                result = subprocess.run([criu, "check"], cwd=temporary, capture_output=True, text=True, timeout=20)
            info["criu"].update(
                status="kernel-check-passed" if result.returncode == 0 else "kernel-or-permission-blocked",
                exit_code=result.returncode,
                diagnostic=(result.stdout + result.stderr)[-4000:],
            )
        except subprocess.TimeoutExpired:
            info["criu"]["status"] = "kernel-check-timed-out"
    info["checkpoint_safety"] = (
        "Capabilities do not establish safe Minecraft checkpointing. Plain CRIU does not "
        "repair GPU, audio, GLFW, authentication, network, or persistent world state."
    )
    return info


def executable(value: str) -> str:
    resolved = shutil.which(value)
    if not resolved:
        raise CacheError("Java executable was not found")
    return str(Path(resolved).resolve())


def sensitive(command: list[str]) -> bool:
    return any(SECRET.search(value) for value in command[1:]) or any(
        SECRET.search(os.environ.get(key, "")) for key in JVM_ENV
    ) or any(value and key.upper() in CREDENTIAL_ENV for key, value in os.environ.items()) or any(
        PROXY_CREDENTIAL.search(os.environ.get(key, "")) for key in PROXY_ENV
    )


def _manifest_paths(path: Path) -> list[Path]:
    if not path.is_file():
        return []
    try:
        with zipfile.ZipFile(path) as archive:
            if archive.getinfo("META-INF/MANIFEST.MF").file_size > 1024 * 1024:
                raise CacheError("classpath manifest is too large to inspect safely")
            text = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
    except (KeyError, zipfile.BadZipFile):
        return []
    except (OSError, ValueError, UnicodeError) as error:
        raise CacheError("cannot inspect a classpath JAR manifest") from error
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    text = re.sub(r"\n ", "", text).split("\n\n", 1)[0]
    attributes = re.findall(r"(?im)^Class-Path:[ \t]*(.*)$", text)
    if len(attributes) > 1:
        raise CacheError("duplicate JAR manifest classpaths require ordinary startup")
    paths = []
    if attributes:
        for entry in attributes[0].split():
            try:
                parsed = urllib.parse.urlsplit(urllib.parse.urljoin(path.absolute().as_uri(), entry))
            except ValueError as error:
                raise CacheError("invalid JAR manifest classpath URL") from error
            if parsed.scheme not in ("", "file") or parsed.netloc not in ("", "localhost"):
                raise CacheError("remote JAR manifest classpaths cannot be fingerprinted")
            decoded = urllib.parse.unquote(parsed.path)
            paths.append(Path(decoded))
    return paths


def launch_inputs(command: list[str]) -> tuple[list[Path], bool]:
    """Find local launcher inputs without changing the original argument vector."""
    if any(os.environ.get(key) for key in JVM_ENV):
        raise CacheError("injected JVM options require ordinary startup")
    if any(os.environ.get(key) for key in NATIVE_ENV):
        raise CacheError("injected native libraries require ordinary startup")
    if any(value.startswith("@") for value in command[1:]):
        raise CacheError("Java argument files require ordinary startup in this prototype")
    paths: list[Path] = []
    classpath_directories: list[Path] = []
    classpath: str | None = None
    jar_launch = False
    module_launch = False
    args = command[1:]
    index = 0
    operand_options = {
        "--add-modules", "--limit-modules", "--add-exports", "--add-opens", "--add-reads",
        "--enable-native-access", "--source", "--finalization", "--describe-module", "-d",
    }

    def path_list(value: str, is_classpath: bool = False) -> None:
        for item in value.split(os.pathsep):
            path = Path(item or ".")
            if path.name == "*":
                paths.append(path.parent)
                if path.parent.is_dir():
                    paths.extend(child for child in path.parent.iterdir() if child.suffix.lower() == ".jar")
            else:
                paths.append(path)
                if is_classpath:
                    classpath_directories.append(path)

    while index < len(args):
        value = args[index]
        if value.startswith("@"):
            raise CacheError("Java argument files require ordinary startup in this prototype")
        if value.startswith(("-XX:AOT", "-XX:SharedArchive", "-XX:ArchiveClassesAtExit", "-Xshare:")):
            raise CacheError("existing JVM archive options require ordinary startup")
        if value in ("-cp", "-classpath", "--class-path"):
            index += 1
            if index >= len(args):
                raise CacheError("incomplete Java classpath option")
            classpath = args[index]
        elif value.startswith("--class-path=") or value.startswith("-Djava.class.path="):
            classpath = value.split("=", 1)[1]
        elif value in ("--module-path", "-p", "--upgrade-module-path"):
            index += 1
            if index >= len(args):
                raise CacheError("incomplete Java module path option")
            path_list(args[index])
        elif value.startswith(("--module-path=", "--upgrade-module-path=", *NATIVE_PATH_OPTIONS)):
            path_list(value.split("=", 1)[1])
        elif value.startswith("-Dorg.lwjgl.system.SharedLibraryExtractPath="):
            paths.append(Path(value.split("=", 1)[1]))
        elif value == "--patch-module":
            index += 1
            if index >= len(args) or "=" not in args[index]:
                raise CacheError("incomplete Java patch module option")
            path_list(args[index].split("=", 1)[1])
        elif value.startswith("--patch-module="):
            path_list(value.split("=", 2)[-1])
        elif value.startswith(("-javaagent:", "-agentpath:")):
            paths.append(Path(value.split(":", 1)[1].split("=", 1)[0]))
        elif value.startswith("-agentlib:"):
            raise CacheError("native agent library lookup requires ordinary startup")
        elif value.startswith(("-XX:Flags=", "-XX:VMOptionsFile=")):
            raise CacheError("external JVM option files require ordinary startup")
        elif value.startswith(("-Xbootclasspath/a:", "-Xbootclasspath/p:")):
            path_list(value.split(":", 1)[1])
        elif value == "-jar":
            index += 1
            if index >= len(args):
                raise CacheError("incomplete Java JAR option")
            paths.append(Path(args[index]))
            jar_launch = True
            break
        elif value in ("-m", "--module") or value.startswith("--module="):
            module_launch = True
            break
        elif value in operand_options:
            index += 1
        elif value == "--":
            break
        elif not value.startswith("-"):
            if value.endswith(".java"):
                paths.append(Path(value))
                raise CacheError("Java source launches require ordinary startup")
            break
        index += 1
    if not jar_launch and (not module_launch or classpath is not None):
        path_list(classpath if classpath is not None else os.environ.get("CLASSPATH", "."), True)

    # Manifest Class-Path can recursively add dependencies outside the explicit CP.
    seen: set[Path] = set()
    pending = paths[:]
    while pending:
        path = pending.pop().absolute()
        if path in seen:
            continue
        seen.add(path)
        added = _manifest_paths(path)
        pending.extend(added)
        classpath_directories.extend(added)
    return sorted(seen), any(path.is_dir() and any(path.iterdir()) for path in classpath_directories)


def _digest_path(digest, path: Path, visiting: set[Path]) -> None:
    digest.update(os.fsencode(str(path.absolute())) + b"\0")
    if not path.exists() and not path.is_symlink():
        digest.update(b"missing\0")
        return
    try:
        target = path.resolve()
    except RuntimeError as error:
        raise CacheError("input contains a symbolic link cycle") from error
    if target in visiting:
        raise CacheError("input directory contains a symbolic link cycle")
    if path.is_symlink():
        digest.update(b"link\0" + os.fsencode(os.readlink(path)) + b"\0")
    if path.is_file():
        digest.update(b"file\0" + str(path.stat().st_size).encode() + b"\0")
        with path.open("rb") as source:
            for block in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(block)
        digest.update(b"\0end\0")
    elif path.is_dir():
        digest.update(b"directory\0")
        visiting.add(target)
        for child in sorted(path.iterdir()):
            _digest_path(digest, child, visiting)
        visiting.remove(target)
        digest.update(b"\0end\0")
    else:
        raise CacheError("inputs must be regular files or directories")


def fingerprint(command: list[str], inputs: list[Path]) -> str:
    java = Path(executable(command[0]))
    home = java.parent.parent
    detected, _ = launch_inputs(command)
    digest = hashlib.sha256()
    context = {
        "schema": SCHEMA, "command": command, "cwd": str(Path.cwd()),
        "platform": [platform.system(), platform.machine()],
        "environment": {key: os.environ.get(key) for key in FINGERPRINT_ENV},
    }
    # Only this digest leaves memory; commands and credentials are never metadata.
    digest.update(json.dumps(context, sort_keys=True, ensure_ascii=True).encode())
    runtime = [java, home / "release", home / "lib", home / "conf"]
    for path in sorted(set(runtime + detected + [p.absolute() for p in inputs])):
        _digest_path(digest, path, set())
    return digest.hexdigest()


def _private(path: Path, directory: bool) -> None:
    info = path.lstat()
    expected = stat.S_ISDIR if directory else stat.S_ISREG
    if (
        not expected(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077
        or (not directory and info.st_nlink != 1)
    ):
        raise CacheError("cache paths must be owned by this user and private regular files/directories")


def cache_root(path: Path) -> Path:
    path = path.absolute()
    for component in (path, *path.parents):
        if component.is_symlink():
            raise CacheError("cache paths cannot contain symbolic links")
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    _private(path, True)
    return path


@contextlib.contextmanager
def cache_lock(root: Path, exclusive: bool):
    descriptor = os.open(root / ".lock", os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        info = os.fstat(descriptor)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
            raise CacheError("cache lock must be a private file owned by this user")
        try:
            fcntl.flock(descriptor, (fcntl.LOCK_EX if exclusive else fcntl.LOCK_SH) | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise CacheError("cache is in use") from error
        yield
    finally:
        os.close(descriptor)


def file_hash(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def valid_cache(root: Path, key: str) -> Path:
    entry = root / key
    _private(entry, True)
    manifest = entry / "manifest.json"
    archive = entry / "cache.aot"
    _private(manifest, False)
    _private(archive, False)
    if manifest.stat().st_size > 4096:
        raise CacheError("invalid cache manifest")
    try:
        record = json.loads(manifest.read_text())
    except (ValueError, UnicodeError) as error:
        raise CacheError("invalid cache manifest") from error
    if not isinstance(record, dict) or set(record) != {"schema", "completed", "fingerprint", "sha256", "bytes", "uid"}:
        raise CacheError("invalid cache manifest schema")
    if (
        type(record["schema"]) is not int or record["schema"] != SCHEMA
        or record["completed"] is not True or record["fingerprint"] != key
        or type(record["uid"]) is not int or record["uid"] != os.getuid()
        or type(record["bytes"]) is not int or record["bytes"] <= 0
        or record["bytes"] != archive.stat().st_size
        or record["sha256"] != file_hash(archive)
    ):
        raise CacheError("cache is incomplete, incompatible, or corrupt")
    return archive


def _sync(path: Path) -> None:
    descriptor = os.open(path, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def train(command: list[str], root_path: Path, inputs: list[Path], force_sensitive: bool = False) -> int:
    if force_sensitive or sensitive(command):
        raise CacheError("training with sensitive launch inputs or credential environments is disabled; use ordinary run")
    _, unsupported_directory = launch_inputs(command)
    if unsupported_directory:
        raise CacheError("JDK AOT training requires JAR classpaths; nonempty directories are unsupported")
    java = executable(command[0])
    if not probe_java(java)["leyden"]:
        raise CacheError("this Java runtime does not support JDK 25 AOT cache output")
    root = cache_root(root_path)
    with cache_lock(root, True):
        key = fingerprint(command, inputs)
        stage = Path(tempfile.mkdtemp(prefix=".train-", dir=root))
        try:
            archive = stage / "cache.aot"
            message("training runs the original application once; cache promotion requires a successful exit")
            # Private staging also covers transient AOT configuration files.
            previous_umask = os.umask(0o077)
            try:
                result = subprocess.run([command[0], "-XX:AOTCacheOutput=" + str(archive), *command[1:]])
            finally:
                os.umask(previous_umask)
            if result.returncode != 0:
                message("training application exited unsuccessfully; no cache promoted and no replay attempted")
                return result.returncode
            _private(archive, False)
            if archive.stat().st_size == 0:
                raise CacheError("JVM did not create a complete AOT cache")
            if fingerprint(command, inputs) != key:
                raise CacheError("inputs changed during training; cache discarded")
            record = {
                "schema": SCHEMA, "completed": True, "fingerprint": key,
                "sha256": file_hash(archive), "bytes": archive.stat().st_size, "uid": os.getuid(),
            }
            manifest = stage / "manifest.json"
            manifest.write_text(json.dumps(record, sort_keys=True) + "\n")
            manifest.chmod(0o600)
            _sync(archive)
            _sync(manifest)
            _sync(stage)
            old = None
            entry = root / key
            if entry.exists() or entry.is_symlink():
                _private(entry, True)
                old = Path(tempfile.mkdtemp(prefix=".old-", dir=root))
                old.rmdir()
                entry.rename(old)
            stage.rename(entry)
            _sync(root)
            if old:
                shutil.rmtree(old)
            message("AOT cache promoted; it contains JVM class data, not a Minecraft process checkpoint")
            return 0
        finally:
            if stage.exists():
                shutil.rmtree(stage)


def run(command: list[str], root_path: Path, inputs: list[Path], force_sensitive: bool = False) -> int:
    if force_sensitive or sensitive(command):
        message("sensitive launch inputs: ordinary startup")
        return subprocess.run(command).returncode
    # Fallback is decided before starting Java. A cached child is never replayed.
    with contextlib.ExitStack() as stack:
        try:
            java = executable(command[0])
            launch_inputs(command)
            if not probe_java(java)["leyden"]:
                raise CacheError("runtime has no JDK 25 AOT cache support")
            root = cache_root(root_path)
            stack.enter_context(cache_lock(root, False))
            key = fingerprint(command, inputs)
            archive = valid_cache(root, key)
        except (CacheError, OSError, subprocess.TimeoutExpired):
            message("compatible complete AOT cache unavailable: ordinary startup")
            # Release locks before the application runs on the fallback path.
            stack.close()
            return subprocess.run(command).returncode
        message("using validated AOT cache; JVM may discard incompatible data during this single launch")
        return subprocess.run([
            command[0], "-XX:AOTMode=auto", "-XX:AOTCache=" + str(archive), *command[1:],
        ]).returncode


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="action", required=True)
    diagnostics = subparsers.add_parser("doctor", help="probe Java and Linux CRIU capabilities")
    diagnostics.add_argument("--java", default="java")
    for name in ("train", "run"):
        sub = subparsers.add_parser(name, help="explicit training" if name == "train" else "single-launch cache wrapper")
        sub.add_argument("--cache-dir", type=Path, required=True)
        sub.add_argument("--input", type=Path, action="append", default=[], help="declare mod/config/resource inputs; repeatable")
        sub.add_argument("--sensitive", action="store_true", help="disable caching for custom sensitive arguments")
        sub.add_argument("command", nargs=argparse.REMAINDER, help="-- java <original JVM and application arguments>")
    args = parser.parse_args(argv)
    try:
        if args.action == "doctor":
            print(json.dumps(doctor(executable(args.java)), indent=2, sort_keys=True))
            return 0
        command = args.command
        if command and command[0] == "--":
            command = command[1:]
        if not command:
            parser.error("an original Java command is required after --")
        if args.action == "train":
            return train(command, args.cache_dir, args.input, args.sensitive)
        return run(command, args.cache_dir, args.input, args.sensitive)
    except CacheError as error:
        message(str(error))
        return 2
    except (OSError, subprocess.TimeoutExpired):
        # Keep paths, argument strings, and injected JVM options out of errors.
        message("operation could not complete safely; check doctor, private cache paths, and supported launch inputs")
        return 2
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    result = main()
    raise SystemExit(128 - result if result < 0 else result)
