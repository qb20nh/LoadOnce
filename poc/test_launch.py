"""Real-JVM integration checks: python3 -m unittest discover -s poc -v.

Build :poc-runtime:jar first with JDK 25. Tests compile a disposable application,
run its original Java command through the wrapper, and check JVM archive use
and application side effects. Hosts without Dynamic CDS skip this suite.
"""

import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock


LAUNCHER = Path(__file__).with_name("launch.py").resolve()
RUNTIME_JAR = LAUNCHER.parent / "runtime" / "build" / "libs" / "loadonce-runtime.jar"
ACTIVATION = Path("/workspace/.loadonce/setup/activate.sh")
FIXTURE = r"""
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class LaunchFixture {
    public static void main(String[] args) throws Exception {
        Files.writeString(Path.of(args[0]), "run\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        switch (args[1]) {
            case "fail": System.exit(23); break;
            case "echo":
                for (int index = 2; index < args.length; index++) System.out.println(args[index]);
                break;
            case "mutate": Files.writeString(Path.of(args[2]), args[3]); break;
            case "hold":
                Files.writeString(Path.of(args[2]), "started");
                while (!Files.exists(Path.of(args[3]))) Thread.sleep(10);
                break;
            default: break;
        }
        System.out.println("FIXTURE_READY");
    }
}
"""


class LauncherIntegrationTests(unittest.TestCase):
    @classmethod
    def activated_command(cls, command):
        # The managed workspace installs its JDK outside the default PATH.
        # Ordinary checkouts just use their configured Java toolchain.
        if ACTIVATION.is_file():
            return ["bash", "-c", 'source "$1" && shift && exec "$@"',
                    "loadonce-tests", str(ACTIVATION), *map(str, command)]
        return list(map(str, command))

    @classmethod
    def setUpClass(cls):
        if os.name != "posix":
            raise unittest.SkipTest("the prototype currently requires Unix cache locking")
        cls.tool_environment = os.environ.copy()
        for name in ("JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH"):
            cls.tool_environment.pop(name, None)
        cls.build = tempfile.TemporaryDirectory(prefix="loadonce-fixture-")
        cls.addClassCleanup(cls.build.cleanup)
        directory = Path(cls.build.name)
        source = directory / "LaunchFixture.java"
        source.write_text(FIXTURE)
        cls.jar = directory / "fixture.jar"
        try:
            version = subprocess.run(cls.activated_command(["java", "-version"]),
                                     capture_output=True, env=cls.tool_environment, timeout=30)
            if version.returncode != 0:
                raise unittest.SkipTest("a working JDK is required")
            for command in (["javac", "-d", directory, source],
                            ["jar", "--create", "--file", cls.jar,
                             "--main-class", "LaunchFixture", "-C", directory, "LaunchFixture.class"]):
                built = subprocess.run(cls.activated_command(command), capture_output=True,
                                       env=cls.tool_environment, timeout=30)
                if built.returncode != 0:
                    raise unittest.SkipTest("a complete JDK is required: " + built.stderr.decode())
            probe = subprocess.run(cls.activated_command([
                "java", "-Xshare:auto", f"-XX:ArchiveClassesAtExit={directory / 'probe.jsa'}", "-version"]),
                capture_output=True, env=cls.tool_environment, timeout=30)
            if probe.returncode != 0:
                raise unittest.SkipTest("this JDK does not support Dynamic CDS")
            java_path = subprocess.run(cls.activated_command([
                "bash", "-c", "command -v java"]), capture_output=True, text=True,
                env=cls.tool_environment, timeout=30)
            cls.java = Path(java_path.stdout.strip()).resolve()
        except FileNotFoundError as error:
            raise unittest.SkipTest(f"Java build tools are unavailable: {error}")

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="loadonce-launch-test-")
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.cache = self.directory / "cache"
        self.counter = self.directory / "executions.txt"
        self.report = self.directory / "metrics.json"

    def command(self, action="record", app_arguments=(), wrapper_arguments=(), java_arguments=()):
        return self.activated_command([
            sys.executable, LAUNCHER, "--cache-dir", self.cache,
            "--report", self.report, *wrapper_arguments, "--", "java", *java_arguments,
            "-jar", self.jar, self.counter, action, *app_arguments])

    def run_launch(self, **arguments):
        result = subprocess.run(self.command(**arguments), cwd=self.directory,
                                env=self.tool_environment, capture_output=True, text=True, timeout=45)
        self.assertTrue(self.report.exists(), result.stderr)
        return result, json.loads(self.report.read_text())

    def execution_count(self):
        return len(self.counter.read_text().splitlines()) if self.counter.exists() else 0

    def manifests(self):
        return sorted(self.cache.glob("*/manifest.json"))

    def assert_trained(self, result, report):
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(report["backend"], "dynamic-cds")
        self.assertEqual(report["cacheAction"], "training")
        self.assertTrue(report["archivePublished"], result.stderr)
        self.assertFalse(report["sharedArchiveLoaded"])

    def test_first_launch_publishes_real_archive_and_second_maps_it(self):
        result, report = self.run_launch()
        self.assert_trained(result, report)
        self.assertEqual(self.execution_count(), 1)
        manifests = self.manifests()
        self.assertEqual(len(manifests), 1)
        manifest = json.loads(manifests[0].read_text())
        archive = manifests[0].with_name("classes.jsa")
        self.assertGreater(archive.stat().st_size, 1024)
        self.assertEqual(manifest["archiveSha256"], hashlib.sha256(archive.read_bytes()).hexdigest())
        result, report = self.run_launch()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(report["cacheAction"], "selected")
        self.assertTrue(report["sharedArchiveLoaded"], result.stderr)
        self.assertFalse(report["archivePublished"])
        self.assertEqual(self.execution_count(), 2)

    def test_same_size_content_change_with_identical_timestamp_invalidates(self):
        supplied_input = self.directory / "immutable-input.txt"
        supplied_input.write_text("AAAA")
        before = supplied_input.stat()
        arguments = {"wrapper_arguments": ["--input", supplied_input]}
        result, report = self.run_launch(**arguments)
        self.assert_trained(result, report)
        old_key = self.manifests()[0].parent.name
        supplied_input.write_text("BBBB")
        os.utime(supplied_input, ns=(before.st_atime_ns, before.st_mtime_ns))
        result, report = self.run_launch(**arguments)
        self.assert_trained(result, report)
        keys = {path.parent.name for path in self.manifests()}
        self.assertEqual(len(keys), 2)
        self.assertIn(old_key, keys)
        result, report = self.run_launch(**arguments)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(report["sharedArchiveLoaded"], result.stderr)
        self.assertEqual(self.execution_count(), 3)

    def test_truncated_archive_is_retrained_and_application_runs_once(self):
        result, report = self.run_launch()
        self.assert_trained(result, report)
        archive = self.manifests()[0].with_name("classes.jsa")
        archive.write_bytes(b"truncated")
        result, report = self.run_launch()
        self.assert_trained(result, report)
        self.assertEqual(self.execution_count(), 2)
        self.assertGreater(archive.stat().st_size, 1024)
        result, report = self.run_launch()
        self.assertTrue(report["sharedArchiveLoaded"], result.stderr)
        self.assertEqual(self.execution_count(), 3)

    def test_jvm_declined_archive_uses_normal_main_once_and_retrains_next_launch(self):
        result, report = self.run_launch()
        self.assert_trained(result, report)
        manifest_path = self.manifests()[0]
        archive = manifest_path.with_name("classes.jsa")
        archive.write_bytes(b"invalid JVM archive with matching manifest integrity")
        manifest = json.loads(manifest_path.read_text())
        manifest["archiveSha256"] = hashlib.sha256(archive.read_bytes()).hexdigest()
        manifest_path.write_text(json.dumps(manifest))
        result, report = self.run_launch()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(report["cacheAction"], "selected")
        self.assertFalse(report["sharedArchiveLoaded"])
        self.assertIn("JVM declined archive", result.stderr)
        self.assertIn("FIXTURE_READY", result.stdout)
        self.assertEqual(self.execution_count(), 2)
        self.assertFalse(manifest_path.exists())
        result, report = self.run_launch()
        self.assert_trained(result, report)
        self.assertEqual(self.execution_count(), 3)

    def test_nonzero_application_runs_once_and_does_not_publish_archive(self):
        result, report = self.run_launch(action="fail")
        self.assertEqual(result.returncode, 23, result.stderr)
        self.assertEqual(report["exitCode"], 23)
        self.assertFalse(report["archivePublished"])
        self.assertEqual(self.execution_count(), 1)
        self.assertEqual(self.manifests(), [])

    def test_input_changed_during_training_discards_archive(self):
        supplied_input = self.directory / "immutable-input.txt"
        supplied_input.write_text("AAAA")
        result, report = self.run_launch(action="mutate", app_arguments=[supplied_input, "BBBB"],
                                       wrapper_arguments=["--input", supplied_input])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(report["archivePublished"])
        self.assertIn("inputs changed", result.stderr)
        self.assertEqual(self.execution_count(), 1)
        self.assertEqual(self.manifests(), [])

    def test_application_arguments_are_fresh_and_never_persisted(self):
        first_token = "loadonce-secret-canary-first-93d7f268"
        second_token = "loadonce-secret-canary-second-f41c6af2"
        result, report = self.run_launch(action="echo", app_arguments=["--accessToken", first_token])
        self.assert_trained(result, report)
        self.assertIn(first_token, result.stdout)
        result, report = self.run_launch(action="echo", app_arguments=["--accessToken", second_token])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(report["sharedArchiveLoaded"], result.stderr)
        self.assertIn(second_token, result.stdout)
        self.assertNotIn(first_token, result.stdout)
        for path in [self.report, *self.cache.rglob("*")]:
            if path.is_file():
                content = path.read_bytes()
                self.assertNotIn(first_token.encode(), content, str(path))
                self.assertNotIn(second_token.encode(), content, str(path))
                self.assertNotIn(b"--accessToken", content, str(path))
        self.assertEqual(self.execution_count(), 2)

    def test_conflicting_archive_control_preserves_conventional_command(self):
        result, report = self.run_launch(java_arguments=["-Xshare:off"])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(report["backend"], "conventional")
        self.assertIn("FIXTURE_READY", result.stdout)
        self.assertEqual(self.execution_count(), 1)
        self.assertEqual(self.manifests(), [])

    def test_logging_disable_preserves_conventional_command(self):
        result, report = self.run_launch(java_arguments=["-Xlog:disable"])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(report["backend"], "conventional")
        self.assertIn("FIXTURE_READY", result.stdout)
        self.assertEqual(self.execution_count(), 1)
        self.assertEqual(self.manifests(), [])

    def test_cache_path_logging_delimiters_preserve_conventional_command(self):
        for index, character in enumerate((":", ","), start=1):
            with self.subTest(character=character):
                self.cache = self.directory / f"cache{character}delimiter"
                result, report = self.run_launch()
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(report["backend"], "conventional")
                self.assertIn("FIXTURE_READY", result.stdout)
                self.assertEqual(self.execution_count(), index)
                self.assertEqual(self.manifests(), [])

    def test_invalid_runtime_agent_is_rejected_before_application_launch(self):
        result, report = self.run_launch(wrapper_arguments=["--runtime-jar", self.jar])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(report["backend"], "conventional")
        self.assertIn("FIXTURE_READY", result.stdout)
        self.assertEqual(self.execution_count(), 1)
        self.assertEqual(self.manifests(), [])

    def test_runtime_agent_records_client_and_server_readiness_on_training_and_reuse(self):
        self.assertTrue(RUNTIME_JAR.is_file(),
                        "Build :poc-runtime:jar before running launcher integration tests")
        for target, milestone in (("server", "server_ready"), ("client", "client_menu_ready")):
            with self.subTest(target=target):
                self.cache = self.directory / f"cache-{target}"
                command = self.activated_command([
                    sys.executable, LAUNCHER, "--cache-dir", self.cache,
                    "--report", self.report, "--runtime-jar", RUNTIME_JAR,
                    "--target", target, "--", "java", "-jar", RUNTIME_JAR, "--" + target])
                for launch_number in range(2):
                    result = subprocess.run(command, cwd=self.directory, env=self.tool_environment,
                                            capture_output=True, text=True, timeout=45)
                    report = json.loads(self.report.read_text())
                    self.assertEqual(result.returncode, 0, result.stderr)
                    if launch_number == 0:
                        self.assert_trained(result, report)
                    else:
                        self.assertEqual(report["cacheAction"], "selected")
                        self.assertTrue(report["sharedArchiveLoaded"], result.stderr)
                        self.assertFalse(report["archivePublished"])
                    self.assertEqual(report["readiness"]["milestone"], milestone)
                    self.assertIs(type(report["readiness"]["elapsedNanos"]), int)
                    self.assertGreater(report["readiness"]["elapsedNanos"], 0)
                    self.assertIn("fixture initialized resources=2048", result.stdout)
                self.assertEqual(len(self.manifests()), 1)

    def test_argument_file_preserves_nonzero_conventional_application_once(self):
        arguments_file = self.directory / "java.args"
        arguments_file.write_text(f'-jar "{self.jar}" "{self.counter}" fail\n')
        command = self.activated_command([
            sys.executable, LAUNCHER, "--cache-dir", self.cache,
            "--report", self.report, "--", "java", "@" + str(arguments_file)])
        result = subprocess.run(command, cwd=self.directory, env=self.tool_environment,
                                capture_output=True, text=True, timeout=45)
        report = json.loads(self.report.read_text())
        self.assertEqual(result.returncode, 23, result.stderr)
        self.assertEqual(report["backend"], "conventional")
        self.assertEqual(report["exitCode"], 23)
        self.assertEqual(self.execution_count(), 1)
        self.assertEqual(self.manifests(), [])

    def test_unsafe_cache_permissions_use_conventional_startup(self):
        self.cache.mkdir(mode=0o755)
        self.cache.chmod(0o755)
        result, report = self.run_launch()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(report["backend"], "conventional")
        self.assertEqual(self.execution_count(), 1)
        self.assertEqual(self.manifests(), [])

    def test_concurrent_training_does_not_block_or_rerun_second_application(self):
        started = self.directory / "started.txt"
        release = self.directory / "release.txt"
        first = subprocess.Popen(self.command(action="hold", app_arguments=[started, release]),
                                 cwd=self.directory, env=self.tool_environment,
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            deadline = time.monotonic() + 30
            while not started.exists() and first.poll() is None and time.monotonic() < deadline:
                time.sleep(0.02)
            self.assertTrue(started.exists(), "first application did not reach its holding state")
            result, report = self.run_launch()
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(report["backend"], "conventional")
            self.assertEqual(self.execution_count(), 2)
            self.assertIsNone(first.poll(), "first application should still be running")
            release.write_text("release")
            stdout, stderr = first.communicate(timeout=30)
            self.assertEqual(first.returncode, 0, stderr)
            self.assertIn("FIXTURE_READY", stdout)
            self.assertEqual(self.execution_count(), 2)
            self.assertEqual(len(self.manifests()), 1)
        finally:
            if first.poll() is None:
                first.kill()
            first.communicate()

    def test_post_launch_reporting_failure_never_retries_application(self):
        specification = importlib.util.spec_from_file_location("loadonce_launcher_under_test", LAUNCHER)
        launcher = importlib.util.module_from_spec(specification)
        specification.loader.exec_module(launcher)
        environment = self.tool_environment.copy()
        environment["PATH"] = str(self.java.parent) + os.pathsep + environment.get("PATH", "")
        original_directory = Path.cwd()
        try:
            os.chdir(self.directory)
            with mock.patch.dict(os.environ, environment, clear=True), \
                    mock.patch.object(launcher, "read_readiness", side_effect=OSError("fixture report failure")), \
                    contextlib.redirect_stderr(io.StringIO()):
                code = launcher.main(["--cache-dir", str(self.cache), "--report", str(self.report),
                                      "--", str(self.java), "-jar", str(self.jar), str(self.counter), "fail"])
        finally:
            os.chdir(original_directory)
        self.assertEqual(code, 23)
        self.assertEqual(self.execution_count(), 1)
        self.assertEqual(self.manifests(), [])


if __name__ == "__main__":
    unittest.main()
