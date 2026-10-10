"""Real-JDK checks for JEP 514 training, archive reuse, and single-run fallback."""

import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

try:
    from . import leyden
except ImportError:
    import leyden

LAUNCHER = Path(__file__).with_name("leyden.py").resolve()
ACTIVATION = Path("/workspace/.loadonce/setup/activate.sh")
FIXTURE = r"""
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.TreeMap;

public final class LeydenFixture {
    public static void main(String[] args) throws Exception {
        Files.writeString(Path.of(args[0]), "run\n", StandardOpenOption.CREATE,
                          StandardOpenOption.APPEND);
        TreeMap<String, Integer> resources = new TreeMap<>();
        for (int i = 0; i < 2048; i++) resources.put("resource-" + i, i);
        switch (args[1]) {
            case "fail": System.exit(23); break;
            case "echo":
                for (int i = 2; i < args.length; i++) System.out.println(args[i]);
                break;
            case "world": System.out.println(Files.readString(Path.of(args[2]))); break;
            case "mutate": Files.writeString(Path.of(args[2]), args[3]); break;
            case "hold":
                Files.writeString(Path.of(args[2]), "started");
                while (!Files.exists(Path.of(args[3]))) Thread.sleep(10);
                break;
            default: break;
        }
        System.out.println("FIXTURE_READY resources=" + resources.size());
    }
}
"""


class LeydenIntegrationTests(unittest.TestCase):
    @classmethod
    def activated_command(cls, command):
        if ACTIVATION.is_file():
            return ["bash", "-c", 'source "$1" && shift && exec "$@"',
                    "loadonce-leyden-tests", str(ACTIVATION), *map(str, command)]
        return list(map(str, command))

    @classmethod
    def setUpClass(cls):
        if os.name != "posix":
            raise unittest.SkipTest("Unix cache locking is required")
        cls.environment = os.environ.copy()
        for name in ("JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH", "JDK_AOT_VM_OPTIONS"):
            cls.environment.pop(name, None)
        cls.build = tempfile.TemporaryDirectory(prefix="loadonce-leyden-fixture-")
        cls.addClassCleanup(cls.build.cleanup)
        directory = Path(cls.build.name)
        source = directory / "LeydenFixture.java"
        source.write_text(FIXTURE)
        cls.jar = directory / "fixture.jar"
        try:
            probe = subprocess.run(cls.activated_command([
                "java", "-XX:AOTMode=off", f"-XX:AOTCacheOutput={directory / 'probe.aot'}", "-version"]),
                env=cls.environment, capture_output=True, timeout=30)
            if probe.returncode != 0:
                raise unittest.SkipTest("JDK 25 automatic AOT caches are unavailable")
            for command in (["javac", "-d", directory, source],
                            ["jar", "--create", "--file", cls.jar, "--main-class", "LeydenFixture",
                             "-C", directory, "LeydenFixture.class"]):
                result = subprocess.run(cls.activated_command(command), env=cls.environment,
                                        capture_output=True, timeout=30)
                if result.returncode != 0:
                    raise unittest.SkipTest("a complete JDK is required: " + result.stderr.decode())
            result = subprocess.run(cls.activated_command(["bash", "-c", "command -v java"]),
                                    env=cls.environment, capture_output=True, text=True, timeout=30)
            cls.java = Path(result.stdout.strip()).resolve()
        except FileNotFoundError as error:
            raise unittest.SkipTest(str(error))

    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="loadonce-leyden-test-")
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.cache = self.directory / "cache"
        self.report = self.directory / "metrics.json"
        self.counter = self.directory / "executions.txt"

    def command(self, action="record", app_arguments=(), wrapper_arguments=(), java_arguments=(), java=None):
        return self.activated_command([
            sys.executable, LAUNCHER, "--cache-dir", self.cache, "--report", self.report,
            *wrapper_arguments, "--", java or self.java, *java_arguments,
            "-jar", self.jar, self.counter, action, *app_arguments])

    def run_launch(self, environment=None, **arguments):
        result = subprocess.run(self.command(**arguments), cwd=self.directory,
                                env=environment or self.environment, capture_output=True, text=True, timeout=60)
        self.assertTrue(self.report.is_file(), result.stderr)
        return result, json.loads(self.report.read_text())

    def count(self):
        return len(self.counter.read_text().splitlines()) if self.counter.exists() else 0

    def manifests(self):
        return sorted(self.cache.glob("*/manifest.json"))

    def assert_trained(self, result, report):
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(report["backend"], leyden.BACKEND)
        self.assertEqual(report["cacheAction"], "training")
        self.assertTrue(report["archivePublished"], result.stderr)
        self.assertFalse(report["aotCacheLoaded"])

    def test_training_assembly_does_not_reexecute_main_and_next_launch_maps_aot(self):
        result, report = self.run_launch()
        self.assert_trained(result, report)
        self.assertEqual(self.count(), 1)
        self.assertIn("AOTCache creation is complete", result.stdout)
        manifest_path, = self.manifests()
        manifest = json.loads(manifest_path.read_text())
        archive = manifest_path.with_name("classes.aot")
        self.assertGreater(archive.stat().st_size, 1024)
        self.assertEqual(manifest["archiveSha256"], hashlib.sha256(archive.read_bytes()).hexdigest())
        self.assertEqual(archive.stat().st_mode & 0o777, 0o600)
        self.assertEqual(manifest_path.stat().st_mode & 0o777, 0o600)
        self.assertEqual(self.report.stat().st_mode & 0o777, 0o600)
        result, report = self.run_launch()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(report["cacheAction"], "selected")
        self.assertTrue(report["aotCacheLoaded"], result.stderr)
        self.assertFalse(report["archivePublished"])
        self.assertEqual(self.count(), 2)
        self.assertIn("FIXTURE_READY", result.stdout)

    def test_nonzero_training_runs_main_once_and_does_not_publish(self):
        result, report = self.run_launch(action="fail")
        self.assertEqual(result.returncode, 23, result.stderr)
        self.assertEqual(report["exitCode"], 23)
        self.assertFalse(report["archivePublished"])
        self.assertEqual(self.count(), 1)
        self.assertEqual(self.manifests(), [])

    def test_nonzero_cached_application_is_not_retried(self):
        self.assert_trained(*self.run_launch())
        result, report = self.run_launch(action="fail")
        self.assertEqual(result.returncode, 23, result.stderr)
        self.assertTrue(report["aotCacheLoaded"], result.stderr)
        self.assertEqual(self.count(), 2)

    def test_arguments_tokens_and_world_data_are_fresh_and_not_persisted(self):
        first = "leyden-secret-canary-first-328e0d426bd9"
        second = "leyden-secret-canary-second-46fb91cec672"
        self.assert_trained(*self.run_launch(action="echo", app_arguments=["--accessToken", first]))
        result, report = self.run_launch(action="echo", app_arguments=["--accessToken", second])
        self.assertTrue(report["aotCacheLoaded"], result.stderr)
        self.assertIn(second, result.stdout)
        self.assertNotIn(first, result.stdout)
        world = self.directory / "world.txt"
        world.write_text("current-world-1")
        result, report = self.run_launch(action="world", app_arguments=[world])
        self.assertTrue(report["aotCacheLoaded"], result.stderr)
        self.assertIn("current-world-1", result.stdout)
        world.write_text("current-world-2")
        result, report = self.run_launch(action="world", app_arguments=[world])
        self.assertTrue(report["aotCacheLoaded"], result.stderr)
        self.assertIn("current-world-2", result.stdout)
        for path in [self.report, *self.cache.rglob("*")]:
            if path.is_file():
                content = path.read_bytes()
                for value in (first, second, "--accessToken", "current-world-1", "current-world-2"):
                    self.assertNotIn(value.encode(), content, str(path))
        self.assertEqual(self.count(), 4)
        self.assertFalse(any(self.cache.glob("run-*")))

    def test_same_size_input_content_change_invalidates_despite_identical_timestamp(self):
        supplied = self.directory / "assets.txt"
        supplied.write_text("AAAA")
        before = supplied.stat()
        arguments = {"wrapper_arguments": ["--input", supplied]}
        self.assert_trained(*self.run_launch(**arguments))
        supplied.write_text("BBBB")
        os.utime(supplied, ns=(before.st_atime_ns, before.st_mtime_ns))
        self.assert_trained(*self.run_launch(**arguments))
        self.assertEqual(len(self.manifests()), 2)
        result, report = self.run_launch(**arguments)
        self.assertTrue(report["aotCacheLoaded"], result.stderr)
        self.assertEqual(self.count(), 3)

    def test_changed_inputs_during_training_discard_cache(self):
        supplied = self.directory / "assets.txt"
        supplied.write_text("AAAA")
        result, report = self.run_launch(action="mutate", app_arguments=[supplied, "BBBB"],
                                       wrapper_arguments=["--input", supplied])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(report["archivePublished"])
        self.assertEqual(self.manifests(), [])
        self.assertEqual(self.count(), 1)

    def test_corrupt_integrity_retrains_once(self):
        self.assert_trained(*self.run_launch())
        manifest, = self.manifests()
        manifest.with_name("classes.aot").write_bytes(b"truncated")
        self.assert_trained(*self.run_launch())
        self.assertEqual(self.count(), 2)
        result, report = self.run_launch()
        self.assertTrue(report["aotCacheLoaded"], result.stderr)

    def test_vm_declined_integrity_valid_archive_falls_back_before_main_once(self):
        self.assert_trained(*self.run_launch())
        manifest_path, = self.manifests()
        archive = manifest_path.with_name("classes.aot")
        archive.write_bytes(b"not a valid AOT image despite valid manifest hash")
        manifest = json.loads(manifest_path.read_text())
        manifest["archiveSha256"] = hashlib.sha256(archive.read_bytes()).hexdigest()
        manifest_path.write_text(json.dumps(manifest))
        result, report = self.run_launch()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(report["cacheAction"], "selected")
        self.assertFalse(report["aotCacheLoaded"])
        self.assertIn("JVM declined AOT cache", result.stderr)
        self.assertEqual(self.count(), 2)
        self.assertFalse(manifest_path.exists())
        self.assert_trained(*self.run_launch())
        self.assertEqual(self.count(), 3)

    def test_unsupported_options_keep_original_command_and_main_once(self):
        for option in ("-Xshare:off", "-XX:AOTMode=off", "-XX:-AOTClassLinking", "-Xlog:aot=off",
                       "-Dcustom.secret=leyden-property-canary-82a967"):
            with self.subTest(option=option):
                result, report = self.run_launch(action="echo", app_arguments=["fresh"], java_arguments=[option])
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(report["backend"], "conventional")
                self.assertIn("fresh", result.stdout)
                self.assertNotIn("leyden-property-canary", result.stderr)
                self.assertEqual(self.manifests(), [])
        self.assertEqual(self.count(), 5)

    def test_unsupported_jdk_probe_does_not_enter_main_before_fallback(self):
        bin_directory = self.directory / "bin"
        bin_directory.mkdir()
        java = bin_directory / "java"
        java.write_text('#!/bin/sh\nfor option do\n  case "$option" in -XX:AOTMode=off) exit 1;; esac\ndone\nexec '
                        + shlex.quote(str(self.java)) + ' "$@"\n')
        java.chmod(0o700)
        result, report = self.run_launch(java=java)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(report["backend"], "conventional")
        self.assertEqual(self.count(), 1)
        self.assertEqual(self.manifests(), [])

    def test_assembly_environment_options_are_not_used_by_optimizer(self):
        environment = self.environment.copy()
        environment["JDK_AOT_VM_OPTIONS"] = "-Dsecret=leyden-assembly-secret-1616f2"
        result, report = self.run_launch(environment=environment)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(report["backend"], "conventional")
        self.assertNotIn("leyden-assembly-secret", result.stderr)
        self.assertEqual(self.count(), 1)
        self.assertEqual(self.manifests(), [])

    def test_cache_delimiters_and_unprivate_permissions_use_conventional_startup(self):
        for delimiter in (":", ",", "%p", "\n"):
            with self.subTest(delimiter=delimiter):
                self.cache = self.directory / ("cache" + delimiter)
                result, report = self.run_launch()
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(report["backend"], "conventional")
        self.cache = self.directory / "public-cache"
        self.cache.mkdir(mode=0o755)
        self.cache.chmod(0o755)
        result, report = self.run_launch()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(report["backend"], "conventional")
        self.assertEqual(self.count(), 5)

    def test_symlink_cache_is_rejected_before_main(self):
        target = self.directory / "other"
        target.mkdir(mode=0o700)
        self.cache.symlink_to(target, target_is_directory=True)
        result, report = self.run_launch()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(report["backend"], "conventional")
        self.assertEqual(self.count(), 1)
        self.assertEqual(list(target.iterdir()), [])

    def test_concurrent_training_falls_back_without_blocking_or_reexecuting_main(self):
        started = self.directory / "started"
        release = self.directory / "release"
        first = subprocess.Popen(self.command(action="hold", app_arguments=[started, release]),
                                 cwd=self.directory, env=self.environment, stdout=subprocess.PIPE,
                                 stderr=subprocess.PIPE, text=True)
        self.addCleanup(lambda: first.kill() if first.poll() is None else None)
        try:
            deadline = time.monotonic() + 20
            while not started.exists() and first.poll() is None and time.monotonic() < deadline:
                time.sleep(0.02)
            self.assertTrue(started.exists())
            result, report = self.run_launch()
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(report["backend"], "conventional")
            self.assertEqual(self.count(), 2)
        finally:
            release.touch()
            stdout, stderr = first.communicate(timeout=30)
        self.assertEqual(first.returncode, 0, stderr)
        self.assertIn("AOTCache creation is complete", stdout)
        self.assertEqual(self.count(), 2)
        self.assertEqual(len(self.manifests()), 1)

    def test_post_application_bookkeeping_failure_never_restarts_main(self):
        self.assert_trained(*self.run_launch())
        original_directory = Path.cwd()
        original_environment = os.environ.copy()
        try:
            os.chdir(self.directory)
            for name in ("JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH", "JDK_AOT_VM_OPTIONS"):
                os.environ.pop(name, None)
            captured = io.StringIO()
            with mock.patch.object(leyden, "archive_loaded", side_effect=OSError("post-main bookkeeping failure")), \
                    contextlib.redirect_stderr(captured):
                code = leyden.main(["--cache-dir", str(self.cache), "--report", str(self.report), "--",
                                    str(self.java), "-jar", str(self.jar), str(self.counter), "record"])
        finally:
            os.chdir(original_directory)
            os.environ.clear()
            os.environ.update(original_environment)
        self.assertEqual(code, 0)
        self.assertIn("will not be restarted", captured.getvalue())
        self.assertEqual(self.count(), 2)
        self.assertEqual(json.loads(self.report.read_text())["cacheAction"], "error")


if __name__ == "__main__":
    unittest.main()
