#!/usr/bin/env python3
"""Controlled CRaC/CRIU experiments for the repository's pure Java fixture only."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import uuid
import zipfile

from launch import cache_lock, digest_file, hash_path, private_directory, Unsupported


SCHEMA = 1
ROOT = Path(__file__).resolve().parent.parent
FIXTURE = ROOT / "poc/runtime/build/libs/loadonce-runtime.jar"
CLASSES = ROOT / "poc/runtime/build/classes/java/main"
FIXTURE_MAIN = "io.loadonce.runtime.demo.RuntimeDemo"
FIXTURE_AGENT = "io.loadonce.runtime.LoadOnceAgent"


def clean_environment(java):
    # A checkpoint persists environment strings as well as the Java heap. Do not
    # inherit launcher credentials, injected JVM options, or unrelated service secrets.
    return {"PATH": str(java.parent) + os.pathsep + os.defpath,
            "LANG": "C.UTF-8", "LC_ALL": "C.UTF-8", "TZ": "UTC"}


def run_probe(command, java, timeout=30, library_dir=None):
    environment = clean_environment(java)
    if library_dir is not None:
        # Only the explicit standalone CRIU probe uses this library path. It must
        # never enter the environment of a captured Java process.
        environment["LD_LIBRARY_PATH"] = str(library_dir)
    process = subprocess.Popen(command, env=environment, cwd=ROOT,
                               stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, text=True, start_new_session=True)
    try:
        stdout, stderr = process.communicate(timeout=timeout)
    except (subprocess.TimeoutExpired, KeyboardInterrupt):
        try:
            os.killpg(process.pid, signal.SIGTERM)
            process.communicate(timeout=3)
        except (ProcessLookupError, subprocess.TimeoutExpired):
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.communicate()
        raise
    return subprocess.CompletedProcess(command, process.returncode, stdout, stderr)


def resolve_java(value):
    found = shutil.which(str(value))
    if not found:
        raise Unsupported("Java executable not found")
    java = Path(found).resolve(strict=True)
    if java.name not in ("java", "java.exe"):
        raise Unsupported("select a JDK Java executable")
    return java


def host_identity():
    cpu = ""
    try:
        stanza = Path("/proc/cpuinfo").read_text().split("\n\n", 1)[0]
        cpu = "\n".join(line for line in stanza.splitlines()
                        if line.split(":", 1)[0].strip() in
                        ("vendor_id", "cpu family", "model", "model name", "stepping",
                         "microcode", "flags", "Features", "CPU implementer", "CPU part"))
    except OSError:
        pass
    return {"system": platform.system(), "machine": platform.machine(),
            "kernel": platform.release(), "libc": list(platform.libc_ver()),
            "cpuSha256": hashlib.sha256(cpu.encode()).hexdigest()}


def host_restrictions():
    restrictions = []
    facts = {"linux": platform.system() == "Linux"}
    if not facts["linux"]:
        return facts, ["the controlled CRIU experiment requires Linux"]
    try:
        status = dict(line.split(":", 1) for line in Path("/proc/self/status").read_text().splitlines()
                      if ":" in line)
        capabilities = int(status["CapEff"].strip(), 16)
        seccomp = int(status["Seccomp"].strip())
        facts.update({"uid": os.getuid(), "effectiveCapabilitiesHex": format(capabilities, "x"),
                      "seccompMode": seccomp})
        # This first experiment deliberately excludes rootless restore and filtered
        # containers. A positive capability check still does not establish readiness.
        if not capabilities & ((1 << 21) | (1 << 40)):
            restrictions.append("CAP_SYS_ADMIN or CAP_CHECKPOINT_RESTORE is unavailable")
        if not capabilities & (1 << 19):
            restrictions.append("CAP_SYS_PTRACE is unavailable")
        if seccomp != 0:
            restrictions.append("seccomp filtering is outside the supported fixture host")
    except (OSError, KeyError, ValueError):
        restrictions.append("Linux checkpoint privilege state could not be inspected")
    return facts, restrictions


def find_criu(java):
    home = java.parent.parent
    for candidate in (home / "lib/criu", home / "bin/criu"):
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return candidate.resolve()
    found = shutil.which("criu")
    return Path(found).resolve() if found else None


def doctor(java, check_criu=False, criu_path=None, library_dir=None):
    version = run_probe([str(java), "-version"], java)
    version_lines = (version.stderr + version.stdout).strip().splitlines()
    major_match = re.search(r'version "(\d+)', "\n".join(version_lines))
    flags = run_probe([str(java), "-XX:+PrintFlagsFinal", "-version"], java)
    available = set(re.findall(r"\b(CRaCCheckpointTo|CRaCRestoreFrom|CRaCEngine|CREngine)\s*=",
                               flags.stdout))
    facts, restrictions = host_restrictions()
    if version.returncode != 0:
        restrictions.append("Java version probe failed")
    major = int(major_match.group(1)) if major_match else None
    if major is None or major < 17:
        restrictions.append("the portable repository fixture requires JDK 17 or later")
    if not {"CRaCCheckpointTo", "CRaCRestoreFrom"}.issubset(available):
        restrictions.append("this JDK does not expose the native CRaC checkpoint/restore flags")
    criu = Path(criu_path).resolve(strict=True) if criu_path else find_criu(java)
    if library_dir is not None:
        library_dir = Path(library_dir).resolve(strict=True)
        if not library_dir.is_dir():
            raise Unsupported("CRIU library directory is not a directory")
    criu_info = {"available": criu is not None, "checkPerformed": False}
    if criu:
        try:
            result = run_probe([str(criu), "--version"], java, library_dir=library_dir)
            criu_info["version"] = (result.stdout + result.stderr).strip().splitlines()[:2]
            if result.returncode != 0:
                restrictions.append("CRIU version probe failed")
            if check_criu:
                checked = run_probe([str(criu), "check", "--no-default-config"], java,
                                    timeout=30, library_dir=library_dir)
                criu_info.update({"checkPerformed": True, "checkExitCode": checked.returncode,
                                  "checkPassed": checked.returncode == 0})
                if checked.returncode != 0:
                    restrictions.append("the bounded CRIU check failed")
        except (OSError, subprocess.TimeoutExpired):
            restrictions.append("CRIU version/check probe failed or exceeded 30 seconds")
    else:
        restrictions.append("no bundled or PATH CRIU executable was found")
    return {"schemaVersion": SCHEMA, "backend": "crac-criu-fixture", "javaVersion": version_lines[:3],
            "javaMajor": major,
            "cracFlags": sorted(available), "host": facts, "criu": criu_info,
            "restrictions": restrictions, "nativeRestoreValidated": False,
            "capabilitiesAreNotReadiness": True}


def validate_fixture():
    """Refuse arbitrary application JARs, extra dependencies, and stale build output."""
    if FIXTURE.is_symlink() or not FIXTURE.is_file() or not CLASSES.is_dir():
        raise Unsupported("build the repository's poc-runtime fixture first")
    with zipfile.ZipFile(FIXTURE) as jar:
        names = jar.namelist()
        if len(names) != len(set(names)):
            raise Unsupported("fixture JAR contains duplicate entries")
        manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8").replace("\r\n", "\n").replace("\n ", "")
        attributes = {key.lower(): value for key, value in
                      (line.split(": ", 1) for line in manifest.split("\n\n", 1)[0].splitlines()
                       if ": " in line)}
        if (attributes.get("main-class") != FIXTURE_MAIN
                or attributes.get("premain-class") != FIXTURE_AGENT
                or "class-path" in attributes or "launcher-agent-class" in attributes):
            raise Unsupported("fixture manifest differs from the repository runtime")
        expected = {str(path.relative_to(CLASSES)).replace(os.sep, "/"): path
                    for path in CLASSES.rglob("*.class")}
        actual = {name for name in names if not name.endswith("/") and name != "META-INF/MANIFEST.MF"}
        if not expected or actual != set(expected):
            raise Unsupported("fixture must contain exactly the repository's compiled classes")
        for name, path in expected.items():
            if (not name.startswith("io/loadonce/runtime/") or path.is_symlink()
                    or hashlib.sha256(jar.read(name)).hexdigest() != digest_file(path)):
                raise Unsupported("fixture classes differ from the trusted local build output")
        demo = jar.read(FIXTURE_MAIN.replace(".", "/") + ".class")
        if demo[:4] != b"\xca\xfe\xba\xbe" or len(demo) < 8:
            raise Unsupported("fixture class header is invalid")
        return int.from_bytes(demo[6:8], "big") - 44


def fingerprint(java):
    digest = hashlib.sha256()
    digest.update(json.dumps((SCHEMA, host_identity(), str(ROOT), str(java),
                              FIXTURE_MAIN, ("--checkpoint", "--fixture-safe", "--server")),
                             sort_keys=True, separators=(",", ":")).encode())
    for path in (Path(__file__).resolve(), FIXTURE, java, java.parent.parent / "release",
                 java.parent.parent / "lib/modules"):
        hash_path(path, digest)
    for path in sorted((java.parent.parent / "lib").rglob("*")):
        if path.is_file() and (path.suffix == ".so" or "criu" in path.name):
            hash_path(path, digest)
    criu = find_criu(java)
    if criu:
        hash_path(criu, digest)
    return digest.hexdigest()


def image_inventory(image):
    if image.is_symlink() or not image.is_dir():
        raise Unsupported("checkpoint image directory is missing or symbolic")
    inventory = {}
    for path in sorted(image.rglob("*")):
        if path.is_symlink() or not (path.is_file() or path.is_dir()):
            raise Unsupported("checkpoint image contains symbolic links or special files")
        if path.is_file() and path.suffix != ".log":
            inventory[path.relative_to(image).as_posix()] = digest_file(path)
    if not {"inventory.img", "pstree.img"}.issubset(inventory):
        raise Unsupported("native CRIU inventory and process-tree images are required")
    if (image / "inventory.img").stat().st_size == 0 or (image / "pstree.img").stat().st_size == 0:
        raise Unsupported("native CRIU inventory files are empty")
    return inventory


def validate_manifest(entry, key):
    if entry.is_symlink() or (entry / "manifest.json").is_symlink():
        raise Unsupported("checkpoint metadata must not be symbolic")
    if not entry.is_dir():
        raise Unsupported("no verified checkpoint entry exists for this fingerprint")
    private_directory(entry)
    try:
        manifest = json.loads((entry / "manifest.json").read_text())
        checksum = manifest["fixtureHeapSha256"]
        if (manifest["schemaVersion"] != SCHEMA or manifest["fingerprint"] != key
                or manifest["backend"] != "crac-criu-fixture"
                or manifest["nativeRestoreValidated"] is not True
                or not isinstance(checksum, str) or not re.fullmatch(r"[0-9a-f]{64}", checksum)
                or manifest["imageSha256"] != image_inventory(entry / "image")):
            raise Unsupported("checkpoint fingerprint, restore proof, or image integrity differs")
        return manifest
    except (OSError, KeyError, TypeError, ValueError) as failure:
        raise Unsupported("checkpoint manifest is missing or malformed") from failure


def check_restored(result, report, run_id, checksum):
    initialized = re.search(r"^fixture initialized resources=2048 sha256=([0-9a-f]{64})$",
                            result.stdout, re.MULTILINE)
    verified = re.search(r"^RESTORED fixture heap verified resources=2048 sha256=([0-9a-f]{64})$",
                         result.stdout, re.MULTILINE)
    if (result.returncode != 0 or "RESTORED: CRaC restored execution" not in result.stdout
            or not initialized or initialized.group(1) != checksum
            or not verified or verified.group(1) != checksum):
        raise Unsupported("restored fixture did not verify its initialized heap and resource lifecycle")
    try:
        ready = json.loads(report.read_text())
        if (ready["schemaVersion"] != 1 or ready["runId"] != run_id
                or ready["milestone"] != "server_ready"
                or type(ready["elapsedNanos"]) is not int or ready["elapsedNanos"] < 0):
            raise Unsupported("restored readiness does not belong to the new launch")
    except (OSError, KeyError, TypeError, ValueError) as failure:
        raise Unsupported("restored readiness report is missing or malformed") from failure
    return {"milestone": ready["milestone"], "elapsedNanos": ready["elapsedNanos"]}


def restore_once(java, entry, checksum):
    # Restore system properties are applied by jdk.crac.Core before afterRestore.
    # Each invocation has fresh reporting state and executes the restored app once.
    with tempfile.TemporaryDirectory(prefix="restore-", dir=entry.parent) as temporary:
        run_id = uuid.uuid4().hex
        report = Path(temporary) / "ready.json"
        result = run_probe([str(java), f"-XX:CRaCRestoreFrom={entry / 'image'}",
                            f"-Dloadonce.report={report}", f"-Dloadonce.runId={run_id}"], java, timeout=60)
        return check_restored(result, report, run_id, checksum)


def experiment(args, java):
    if not args.fixture_safe:
        raise Unsupported("--fixture-safe attestation is required for this repository fixture")
    capabilities = doctor(java, check_criu=True, criu_path=args.criu,
                          library_dir=args.criu_library_dir)
    if capabilities["restrictions"]:
        return 2, {"status": "unsupported", **capabilities}
    required_major = validate_fixture()
    if capabilities["javaMajor"] < required_major:
        raise Unsupported("the selected JDK cannot load the built fixture class version")
    key = fingerprint(java)
    cache = args.cache_dir.absolute()
    private_directory(cache)
    entry = cache / key
    with cache_lock(cache) as acquired:
        if not acquired:
            raise Unsupported("another checkpoint experiment owns this cache")
        if args.action == "restore":
            manifest = validate_manifest(entry, key)
            readiness = restore_once(java, entry, manifest["fixtureHeapSha256"])
            if image_inventory(entry / "image") != manifest["imageSha256"]:
                raise Unsupported("restoration modified immutable image data; image is no longer reusable")
            return 0, {"status": "restored", "fingerprint": key, "nativeRestoreValidated": True,
                       "readiness": readiness}
        if entry.exists():
            raise Unsupported("checkpoint entry already exists; choose a new empty cache for capture")
        entry.mkdir(mode=0o700)
        image = entry / "image"
        image.mkdir(mode=0o700)
        captured = run_probe([str(java), f"-XX:CRaCCheckpointTo={image}",
                              f"-javaagent:{FIXTURE}", "-jar", str(FIXTURE),
                              "--checkpoint", "--fixture-safe", "--server"], java, timeout=60)
        prepared = re.search(r"^checkpoint fixture prepared resources=2048 sha256=([0-9a-f]{64})$",
                             captured.stdout, re.MULTILINE)
        # The normal native CRIU capture kills the captured process. A simulation
        # returning from checkpointRestore cannot qualify as a captured image.
        if captured.returncode != -9 or not prepared:
            raise Unsupported("native capture did not produce the expected SIGKILL checkpoint completion")
        before = image_inventory(image)
        checksum = prepared.group(1)
        readiness = restore_once(java, entry, checksum)
        if image_inventory(image) != before or fingerprint(java) != key:
            raise Unsupported("image or runtime changed during capture verification")
        manifest = {"schemaVersion": SCHEMA, "backend": "crac-criu-fixture", "fingerprint": key,
                    "nativeRestoreValidated": True, "fixtureHeapSha256": checksum, "imageSha256": before}
        staged = entry / ".manifest.tmp"
        with staged.open("x") as stream:
            os.chmod(staged, 0o600)
            stream.write(json.dumps(manifest, indent=2) + "\n")
        os.replace(staged, entry / "manifest.json")
        return 0, {"status": "captured-and-restore-verified", "fingerprint": key,
                   "nativeRestoreValidated": True, "readiness": readiness}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("doctor", "capture", "restore"))
    parser.add_argument("--java", default="java", help="JDK executable, not a launch command")
    parser.add_argument("--check-criu", action="store_true", help="doctor: run a bounded native CRIU check")
    parser.add_argument("--criu", type=Path, help="explicit standalone CRIU capability-probe executable")
    parser.add_argument("--criu-library-dir", type=Path, help="library directory for that standalone probe only")
    parser.add_argument("--fixture-safe", action="store_true", help="attest to the repository-built fixture only")
    parser.add_argument("--cache-dir", type=Path, default=ROOT / ".loadonce-checkpoints")
    args = parser.parse_args(argv)
    try:
        java = resolve_java(args.java)
        if args.action == "doctor":
            result, code = doctor(java, args.check_criu, args.criu, args.criu_library_dir), 0
        else:
            code, result = experiment(args, java)
    except (Unsupported, OSError, ValueError, KeyError, zipfile.BadZipFile,
            subprocess.TimeoutExpired) as failure:
        detail = str(failure) if isinstance(failure, Unsupported) else type(failure).__name__
        code, result = 2, {"schemaVersion": SCHEMA, "status": "refused-or-failed",
                           "detail": detail, "nativeRestoreValidated": False}
    print(json.dumps(result, indent=2))
    return code


if __name__ == "__main__":
    sys.exit(main())
