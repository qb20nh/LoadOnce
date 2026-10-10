"""Fixture supervisor contracts with real child processes and private fake images.

The fake runtime tests launcher decisions, not CRaC capabilities. Real Warp
capture/restore is exercised separately by tools/smoke_crac.py.
"""

import contextlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import crac_fixture


class FixtureTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name)
        self.root = self.base / "cache"
        self.java = self.base / "jdk/bin/java"
        self.java.parent.mkdir(parents=True)
        self.inputs = self.base / "classes"
        self.inputs.mkdir()
        (self.inputs / "CracSmoke.class").write_bytes(b"isolated-fixture-v1")
        self.native_library = self.base / "provider.so"
        self.native_library.write_bytes(b"provider-v1")
        self.launches = self.base / "launches.jsonl"
        self.control = self.base / "control"
        home = self.java.parent.parent
        (home / "release").write_text("JAVA_VERSION=25-crac\n")
        (home / "lib").mkdir()
        (home / "lib/libjvm.so").write_bytes(b"runtime-v1")
        (home / "conf").mkdir()
        (home / "conf/java.security").write_bytes(b"config-v1")
        self.java.write_text("#!/usr/bin/python3\n" + self.fake_runtime())
        self.java.chmod(0o700)
        self.fixture = crac_fixture.Fixture(self.java, str(self.inputs))
        patch = mock.patch.dict(os.environ, {}, clear=True)
        patch.start()
        self.addCleanup(patch.stop)

    def fake_runtime(self):
        # Complete child isolation is checked through exec rather than mocking
        # the environment argument that would otherwise mask accidental leaks.
        return f'''
import json, os, pathlib, sys
args = sys.argv[1:]
launches = pathlib.Path({str(self.launches)!r})
control = pathlib.Path({str(self.control)!r})
failure = control.read_text() if control.exists() else ""
checkpoint = next((v.split("=", 1)[1] for v in args if v.startswith("-XX:CRaCCheckpointTo=")), None)
restore = next((v.split("=", 1)[1] for v in args if v.startswith("-XX:CRaCRestoreFrom=")), None)
mode = "capture" if checkpoint else "restore" if restore else "cold"
with launches.open("a") as output:
    output.write(json.dumps({{"mode": mode, "args": args, "env": dict(os.environ), "pid": os.getpid()}}) + "\\n")
if failure == "timeout":
    import time
    time.sleep(5)
if failure == "restore-failure" and restore:
    sys.exit(23)
if failure == "restore-evidence-missing" and restore:
    print("application exited without the expected restored evidence")
    sys.exit(0)
native = "dev.loadonce.prototype.NativeRestorationProbe" in args
if restore:
    record = json.loads((pathlib.Path(restore) / "core.img").read_text().rstrip(" "))
    native = record["native"]
    if native:
        print("NATIVE_PROBE verified cycle=1")
        print("NATIVE_PROBE cleanup=tracked-handles-zero native-release=ok")
        print("NATIVE_PROBE PASS cycles=1")
    else:
        print(f"CRAC_SMOKE restored-pid={{os.getpid()}} initial-pid={{record['pid']}} heap-witness=01234567-89ab value=42 coordinator-state=READY")
    print("preparationCallbacks=1, restorationCallbacks=1")
elif checkpoint:
    image = pathlib.Path(checkpoint)
    record = json.dumps({{"pid": os.getpid(), "native": native}})
    (image / "core.img").write_text(record + " " * 5000)
    engine = "criuengine" if "-XX:CRaCEngine=criuengine" in args else "warp"
    (image / "engine").write_text(engine)
    if engine == "criuengine":
        (image / "inventory.img").write_text(record)
        (image / "dump4.log").write_text("Dumping finished successfully" if failure != "no-boundary" else "dump failed")
    if native:
        print("NATIVE_PROBE verified cycle=0")
        temporary = pathlib.Path(next(v.split("=", 1)[1] for v in args if v.startswith("-Djava.io.tmpdir=")))
        (temporary / "libfixture.so").write_bytes(b"extracted-native")
    else:
        print(f"CRAC_SMOKE initial-pid={{os.getpid()}} heap-witness=01234567-89ab")
    if failure != "no-boundary":
        print("warp: Checkpoint successful!")
    if failure == "capture-failure":
        sys.exit(17)
else:
    print("NATIVE_PROBE PASS cycles=1" if native else "CRAC_SMOKE COLD value=42 coordinator-state=READY")
    if failure == "cold-failure":
        sys.exit(19)
'''

    def captured(self, fixture=None):
        fixture = fixture or self.fixture
        result = crac_fixture.capture(fixture, self.root)
        self.assertEqual((result.mode, result.returncode), ("capture", 0))
        return result

    def run_once(self, fixture=None):
        before = len(self.records())
        result = crac_fixture.run(fixture or self.fixture, self.root)
        self.assertEqual(len(self.records()), before + 1)
        if result.mode == "cold":
            self.addCleanup(shutil.rmtree, result.log.parent)
        return result

    def records(self):
        return [json.loads(line) for line in self.launches.read_text().splitlines()] if self.launches.exists() else []

    def manifest(self):
        return json.loads((self.root / "manifest.json").read_text())

    def generation(self):
        return self.root / self.manifest()["generation"]

    def test_missing_image_chooses_one_cold_start_without_checkpoint(self):
        result = self.run_once()
        self.assertEqual((result.mode, result.returncode), ("cold", 0))
        self.assertIn("CRAC_SMOKE COLD", result.output)
        self.assertFalse(any(v.startswith("-XX:CRaC") for v in self.records()[0]["args"]))
        self.assertEqual(self.records()[0]["args"][-1], "cold")

    def test_failed_cold_child_is_returned_without_replay(self):
        self.control.write_text("cold-failure")
        result = self.run_once()
        self.assertEqual((result.mode, result.returncode), ("cold", 19))
        self.assertEqual([r["mode"] for r in self.records()], ["cold"])

    def test_criu_requires_inventory_and_dump_completion_without_warp_options(self):
        fixture = crac_fixture.Fixture(self.java, str(self.inputs), engine="criuengine")
        self.captured(fixture)
        self.assertNotIn("-XX:CRaCEngineOptions=checkpoint_exit_code=0", self.records()[0]["args"])
        self.assertEqual(self.run_once(fixture).mode, "restore")
        (self.generation() / "image/inventory.img").unlink()
        self.assertEqual(self.run_once(fixture).mode, "cold")
        self.control.write_text("no-boundary")
        with self.assertRaises(crac_fixture.FixtureError):
            crac_fixture.capture(fixture, self.root)

    def test_complete_capture_restores_into_three_distinct_children(self):
        captured = self.captured()
        pids = {captured.pid}
        for _ in range(3):
            result = self.run_once()
            self.assertEqual((result.mode, result.returncode), ("restore", 0))
            self.assertNotIn(result.pid, pids)
            pids.add(result.pid)
            self.assertIn(f"initial-pid={captured.pid}", result.output)
        self.assertEqual([r["mode"] for r in self.records()], ["capture", "restore", "restore", "restore"])

    def test_runtime_executable_release_library_and_config_changes_choose_cold(self):
        home = self.java.parent.parent
        for path in (self.java, home / "release", home / "lib/libjvm.so", home / "conf/java.security"):
            with self.subTest(path=path.name):
                self.captured()
                before = path.read_bytes()
                path.write_bytes(before + (b"\n# changed\n" if path == self.java else b"changed"))
                self.assertEqual(self.run_once().mode, "cold")
                path.write_bytes(before)

    def test_classpath_and_declared_native_dependency_changes_choose_cold(self):
        fixture = crac_fixture.Fixture(self.java, str(self.inputs), extra_inputs=(self.native_library,))
        self.captured(fixture)
        (self.inputs / "CracSmoke.class").write_bytes(b"fixture-v2")
        self.assertEqual(self.run_once(fixture).mode, "cold")
        self.captured(fixture)
        self.native_library.write_bytes(b"provider-v2")
        self.assertEqual(self.run_once(fixture).mode, "cold")

    def test_transitive_jar_manifest_dependency_changes_choose_cold(self):
        main = self.base / "fixture.jar"
        dependency = self.base / "dependency.jar"
        with zipfile.ZipFile(main, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nClass-Path: dependency.jar\n\n")
        with zipfile.ZipFile(dependency, "w") as archive:
            archive.writestr("FixtureDependency.class", b"dependency-v1")
        fixture = crac_fixture.Fixture(self.java, str(main))
        self.captured(fixture)
        with zipfile.ZipFile(dependency, "w") as archive:
            archive.writestr("FixtureDependency.class", b"dependency-v2")
        self.assertEqual(self.run_once(fixture).mode, "cold")

    def test_nonprivate_root_manifest_and_busy_cache_choose_cold(self):
        self.captured()
        manifest = self.root / "manifest.json"
        manifest.chmod(0o644)
        self.assertEqual(self.run_once().mode, "cold")
        manifest.chmod(0o600)
        self.root.chmod(0o755)
        self.assertEqual(self.run_once().mode, "cold")
        self.root.chmod(0o700)
        with crac_fixture.loadonce.cache_lock(self.root, True):
            self.assertEqual(self.run_once().mode, "cold")
        self.assertEqual(self.run_once().mode, "restore")

    def test_corrupt_missing_extra_and_nonprivate_payload_choose_cold(self):
        for change in ("corrupt", "missing", "extra", "nonprivate", "symlink", "hardlink"):
            with self.subTest(change=change):
                self.captured()
                image = self.generation() / "image"
                core = image / "core.img"
                if change == "corrupt":
                    core.write_bytes(b"bad" + core.read_bytes()[3:])
                elif change == "missing":
                    core.unlink()
                elif change == "extra":
                    (image / "unrecorded").write_bytes(b"extra")
                    (image / "unrecorded").chmod(0o600)
                elif change == "nonprivate":
                    core.chmod(0o644)
                elif change == "symlink":
                    core.rename(image / "original")
                    core.symlink_to("original")
                else:
                    os.link(core, image / "duplicate")
                self.assertEqual(self.run_once().mode, "cold")

    def test_invalid_or_incomplete_manifest_chooses_cold(self):
        for change in ("json", "completed", "schema", "fixture", "engine", "generation", "uid", "bytes", "heap_witness"):
            with self.subTest(change=change):
                self.captured()
                record = self.manifest()
                values = {"completed": False, "schema": 0, "fixture": "Main", "engine": "criuengine",
                          "generation": "../escape", "uid": os.getuid() + 1, "bytes": "5000", "heap_witness": "invalid!"}
                if change in values:
                    record[change] = values[change]
                (self.root / "manifest.json").write_text("[" if change == "json" else json.dumps(record))
                self.assertEqual(self.run_once().mode, "cold")

    def test_restore_failure_returns_failure_without_replay(self):
        self.captured()
        self.control.write_text("restore-failure")
        result = self.run_once()
        self.assertEqual((result.mode, result.returncode), ("restore", 23))
        self.assertEqual([r["mode"] for r in self.records()], ["capture", "restore"])

    def test_missing_success_evidence_after_restore_never_replays(self):
        self.captured()
        self.control.write_text("restore-evidence-missing")
        with self.assertRaises(crac_fixture.FixtureError):
            crac_fixture.run(self.fixture, self.root)
        self.assertEqual([r["mode"] for r in self.records()], ["capture", "restore"])

    def test_timed_out_restore_stops_child_without_replay(self):
        self.captured()
        self.control.write_text("timeout")
        with self.assertRaises(subprocess.TimeoutExpired):
            crac_fixture.run(self.fixture, self.root, timeout=1)
        records = self.records()
        self.assertEqual([r["mode"] for r in records], ["capture", "restore"])
        with self.assertRaises(ProcessLookupError):
            os.kill(records[-1]["pid"], 0)

    def test_failed_capture_does_not_replace_previously_complete_manifest(self):
        self.captured()
        previous = (self.root / "manifest.json").read_bytes()
        for failure in ("capture-failure", "no-boundary"):
            with self.subTest(failure=failure):
                self.control.write_text(failure)
                before = len(self.records())
                with self.assertRaises(crac_fixture.FixtureError):
                    crac_fixture.capture(self.fixture, self.root)
                self.assertEqual(len(self.records()), before + 1)
                self.assertEqual((self.root / "manifest.json").read_bytes(), previous)
        self.control.unlink()
        self.assertEqual(self.run_once().mode, "restore")

    def test_capture_input_mutation_does_not_publish_manifest(self):
        execute = crac_fixture._execute

        def mutate(*args):
            result = execute(*args)
            (self.inputs / "CracSmoke.class").write_bytes(b"changed-during-capture")
            return result

        with mock.patch.object(crac_fixture, "_execute", side_effect=mutate), self.assertRaises(crac_fixture.FixtureError):
            crac_fixture.capture(self.fixture, self.root)
        self.assertEqual(len(self.records()), 1)
        self.assertFalse((self.root / "manifest.json").exists())

    def test_inherited_credentials_and_jvm_native_injection_never_enter_child(self):
        caller = {"GH_TOKEN": "private-value", "CUSTOM_SECRET": "private-value",
                  "JAVA_TOOL_OPTIONS": "-Dopaque=private-value", "JDK_JAVA_OPTIONS": "-Xmx1g",
                  "LD_PRELOAD": "secret.so", "HOME": "/caller/home", "AWS_SESSION_TOKEN": "private-value",
                  "HTTP_PROXY": "https://name:private-value@localhost"}
        with mock.patch.dict(os.environ, caller):
            self.captured()
            self.run_once()
        for record in self.records():
            self.assertEqual(set(record["env"]), {"PATH", "HOME", "LANG"})
            self.assertNotIn("private-value", json.dumps(record))
            self.assertNotEqual(record["env"]["HOME"], "/caller/home")
        self.assertNotIn("private-value", (self.root / "manifest.json").read_text())

    def test_native_exact_environment_and_extracted_libraries_are_validated(self):
        authority = self.base / "Xauthority"
        authority.write_bytes(b"local-display-cookie")
        fixture = crac_fixture.Fixture(self.java, str(self.inputs), native=True,
                                      extra_inputs=(self.native_library,), native_flags=("--reconfigure",))
        with mock.patch.dict(os.environ, {"DISPLAY": ":12", "XAUTHORITY": str(authority),
                                         "PULSE_SERVER": "unwanted", "ALSOFT_DRIVERS": "pulse"}):
            self.captured(fixture)
            self.assertEqual(self.run_once(fixture).mode, "restore")
            expected = {"PATH", "HOME", "LANG", "DISPLAY", "XAUTHORITY", "LIBGL_ALWAYS_SOFTWARE",
                        "ALSOFT_DRIVERS", "XDG_RUNTIME_DIR", "MESA_SHADER_CACHE_DIR"}
            self.assertEqual(set(self.records()[0]["env"]), expected)
            self.assertEqual(self.records()[0]["env"]["ALSOFT_DRIVERS"], "null")
            self.assertIn("--reconfigure", self.records()[0]["args"])
            (self.generation() / "tmp/libfixture.so").write_bytes(b"modified-native")
            self.assertEqual(self.run_once(fixture).mode, "cold")

    def test_native_display_authority_and_fixture_arguments_bind_image(self):
        authority = self.base / "Xauthority"
        authority.write_bytes(b"local-cookie")
        fixture = crac_fixture.Fixture(self.java, str(self.inputs), native=True)
        with mock.patch.dict(os.environ, {"DISPLAY": ":12", "XAUTHORITY": str(authority)}):
            self.captured(fixture)
            authority.write_bytes(b"changed-cookie")
            self.assertEqual(self.run_once(fixture).mode, "cold")
            self.captured(fixture)
            altered = crac_fixture.Fixture(self.java, str(self.inputs), native=True, native_flags=("--reconfigure",))
            self.assertEqual(self.run_once(altered).mode, "cold")
            with mock.patch.dict(os.environ, {"DISPLAY": ":13"}):
                self.assertEqual(self.run_once(fixture).mode, "cold")

    def test_kernel_change_invalidates_image_before_child(self):
        self.captured()
        with mock.patch.object(crac_fixture.platform, "release", return_value="changed-kernel"):
            self.assertEqual(self.run_once().mode, "cold")

    def test_simulation_arbitrary_arguments_and_remote_display_are_rejected(self):
        for kwargs in ({"engine": "simengine"}, {"native_flags": ("--accessToken",)},
                       {"classpath": "relative/classes"}, {"classpath": str(self.inputs) + ":*"}):
            values = {"java": self.java, "classpath": str(self.inputs), **kwargs}
            with self.subTest(kwargs=kwargs), self.assertRaises(crac_fixture.FixtureError):
                crac_fixture.Fixture(**values)
        fixture = crac_fixture.Fixture(self.java, str(self.inputs), native=True)
        with mock.patch.dict(os.environ, {"DISPLAY": "remote.example:0"}), self.assertRaises(crac_fixture.FixtureError):
            crac_fixture.capture(fixture, self.root)
        self.assertEqual(self.records(), [])

    def test_private_atomic_manifest_contains_no_runtime_command_paths(self):
        self.captured()
        manifest = self.root / "manifest.json"
        self.assertEqual(manifest.stat().st_mode & 0o777, 0o600)
        self.assertEqual(self.root.stat().st_mode & 0o777, 0o700)
        self.assertFalse(list(self.root.glob(".manifest-*")))
        text = manifest.read_text()
        self.assertNotIn(str(self.java), text)
        self.assertNotIn(str(self.inputs), text)
        crac_fixture.valid_image(self.fixture, self.root)

    def test_cli_launches_fixed_fixture(self):
        classpath_file = self.base / "classpath.txt"
        classpath_file.write_text(str(self.inputs) + "\n")
        args = ["run", "--java", str(self.java), "--cache-dir", str(self.root),
                "--classpath-file", str(classpath_file)]
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(crac_fixture.main(args), 0)
        self.assertIn("mode=cold", output.getvalue())
        self.assertEqual(len(self.records()), 1)
        self.addCleanup(shutil.rmtree, Path(self.records()[0]["env"]["HOME"]).parent)


if __name__ == "__main__":
    unittest.main()
