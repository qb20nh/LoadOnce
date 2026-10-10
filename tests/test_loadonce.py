"""Launcher contract tests; fake JVM results never execute an application twice."""

import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import zipfile


MODULE_PATH = Path(__file__).resolve().parents[1] / "tools" / "loadonce.py"
SPEC = importlib.util.spec_from_file_location("loadonce", MODULE_PATH)
loadonce = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(loadonce)


class LauncherTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name)
        self.java = self.base / "jdk" / "bin" / "java"
        self.java.parent.mkdir(parents=True)
        self.java.write_bytes(b"fake-java-runtime")
        self.java.chmod(0o700)
        self.home = self.java.parent.parent
        (self.home / "release").write_text('JAVA_VERSION="25"\n')
        (self.home / "lib" / "server").mkdir(parents=True)
        (self.home / "lib" / "server" / "libjvm.so").write_bytes(b"runtime-library")
        (self.home / "conf").mkdir()
        (self.home / "conf" / "java.security").write_bytes(b"runtime-config")
        self.jar = self.base / "app.jar"
        self.make_jar(self.jar)
        self.resource = self.base / "declared" / "resource.bin"
        self.resource.parent.mkdir()
        self.resource.write_bytes(b"resource-v1")
        self.inputs = [self.resource.parent]
        self.root = self.base / "cache"
        self.command = [str(self.java), "-cp", str(self.jar), "Main", "app argument"]
        self.environment = mock.patch.dict(os.environ, {}, clear=True)
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.stderr = io.StringIO()
        redirect = contextlib.redirect_stderr(self.stderr)
        redirect.__enter__()
        self.addCleanup(redirect.__exit__, None, None, None)

    @staticmethod
    def make_jar(path, manifest="Manifest-Version: 1.0\n\n", payload=b"class-v1"):
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", manifest)
            archive.writestr("Main.class", payload)

    def train_cache(self, command=None, returncode=0, output=b"fake-aot-cache", mutate=None):
        command = self.command if command is None else command

        def java_child(arguments):
            self.assertEqual(arguments[0], command[0])
            self.assertEqual(arguments[2:], command[1:])
            self.assertTrue(arguments[1].startswith("-XX:AOTCacheOutput="))
            if output is not None:
                archive = Path(arguments[1].split("=", 1)[1])
                archive.write_bytes(output)
                archive.chmod(0o600)
            if mutate is not None:
                mutate()
            return subprocess.CompletedProcess(arguments, returncode)

        with mock.patch.object(loadonce, "probe_java", return_value={"leyden": True}), \
                mock.patch.object(loadonce.subprocess, "run", side_effect=java_child) as child:
            try:
                status = loadonce.train(command, self.root, self.inputs)
            finally:
                self.assertEqual(child.call_count, 1)
        return status

    def run_once(self, command=None, returncode=0, leyden=True, force_sensitive=False):
        command = self.command if command is None else command
        with mock.patch.object(loadonce, "probe_java", return_value={"leyden": leyden}), \
                mock.patch.object(loadonce.subprocess, "run", return_value=
                                  subprocess.CompletedProcess(command, returncode)) as child:
            status = loadonce.run(command, self.root, self.inputs, force_sensitive)
        self.assertEqual(status, returncode)
        child.assert_called_once()
        return child.call_args.args[0]

    def cache_entry(self):
        key = loadonce.fingerprint(self.command, self.inputs)
        return self.root / key

    def test_missing_cache_and_unsupported_runtime_choose_cold_before_application(self):
        for supported in (True, False):
            with self.subTest(leyden=supported):
                self.assertEqual(self.run_once(leyden=supported, returncode=19), self.command)
        self.assertFalse(any(self.root.glob("*/manifest.json")))

    def test_warm_nonzero_exit_is_returned_without_replay(self):
        self.assertEqual(self.train_cache(), 0)
        launched = self.run_once(returncode=23)
        self.assertEqual(launched[0], self.command[0])
        self.assertIn("-XX:AOTMode=auto", launched)
        self.assertTrue(launched[2].startswith("-XX:AOTCache="))
        self.assertEqual(launched[3:], self.command[1:])

    def test_training_failure_after_application_does_not_promote_or_replay(self):
        self.assertEqual(self.train_cache(returncode=17), 17)
        self.assertFalse(any(self.root.glob("*/manifest.json")))
        self.assertFalse(any(self.root.glob(".train-*")))

    def test_incomplete_training_output_is_discarded_without_replay(self):
        for output in (None, b""):
            with self.subTest(output=output), self.assertRaises((loadonce.CacheError, OSError)):
                self.train_cache(output=output)
            self.assertFalse(any(self.root.glob("*/manifest.json")))
            self.assertFalse(any(self.root.glob(".train-*")))

    def test_inputs_changed_during_training_are_not_promoted(self):
        with self.assertRaises(loadonce.CacheError):
            self.train_cache(mutate=lambda: self.resource.write_bytes(b"resource-v2"))
        self.assertFalse(any(self.root.glob("*/manifest.json")))
        self.assertFalse(any(self.root.glob(".train-*")))

    def test_manifest_contains_digests_without_original_arguments(self):
        marker = "opaque-private-launch-value"
        self.command.append(marker)
        self.assertEqual(self.train_cache(), 0)
        manifest = (self.cache_entry() / "manifest.json").read_text()
        self.assertNotIn(marker, manifest)
        self.assertNotIn(str(self.java), manifest)
        self.assertNotIn(str(self.jar), manifest)
        self.assertEqual(loadonce.valid_cache(self.root, self.cache_entry().name),
                         self.cache_entry() / "cache.aot")

    def test_sensitive_and_explicitly_sensitive_arguments_preserve_original_launch(self):
        commands = [self.command + ["--accessToken", "credential-value"],
                    self.command + ["--custom-login", "opaque-value"]]
        for command, forced in zip(commands, (False, True)):
            with self.subTest(forced=forced), \
                    mock.patch.object(loadonce, "probe_java") as probe, \
                    mock.patch.object(loadonce.subprocess, "run", return_value=
                                      subprocess.CompletedProcess(command, 7)) as child:
                self.assertEqual(loadonce.run(command, self.root, self.inputs, forced), 7)
                child.assert_called_once_with(command)
                probe.assert_not_called()
                with self.assertRaises(loadonce.CacheError):
                    loadonce.train(command, self.root, self.inputs, forced)
        self.assertFalse(self.root.exists())
        self.assertNotIn("credential-value", self.stderr.getvalue())
        self.assertNotIn("opaque-value", self.stderr.getvalue())

    def test_hidden_jvm_options_and_argument_files_choose_cold_preserving_arguments(self):
        for name in loadonce.JVM_ENV:
            with self.subTest(environment=name), mock.patch.dict(os.environ, {name: "-Xmx256m"}):
                self.assertEqual(self.run_once(), self.command)
                with self.assertRaises(loadonce.CacheError):
                    loadonce.train(self.command, self.root, self.inputs)
        command = self.command + ["@application-argument.txt", "literal $() *", "spaces remain"]
        self.assertEqual(self.run_once(command), command)
        with self.assertRaises(loadonce.CacheError):
            loadonce.train(command, self.root, self.inputs)
        self.assertFalse(self.root.exists())

    def test_known_credential_environments_reject_training_before_any_child(self):
        for name in ("GH_TOKEN", "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN",
                     "GOOGLE_APPLICATION_CREDENTIALS", "MINECRAFT_ACCESS_TOKEN", "gh_token"):
            with self.subTest(environment=name), mock.patch.dict(os.environ, {name: "opaque-credential-value"}), \
                    mock.patch.object(loadonce, "probe_java") as probe, \
                    mock.patch.object(loadonce.subprocess, "run", return_value=
                                      subprocess.CompletedProcess(self.command, 9)) as child:
                self.assertEqual(loadonce.run(self.command, self.root, self.inputs), 9)
                child.assert_called_once_with(self.command)
                probe.assert_not_called()
                child.reset_mock()
                with self.assertRaises(loadonce.CacheError):
                    loadonce.train(self.command, self.root, self.inputs)
                child.assert_not_called()
                probe.assert_not_called()
        self.assertFalse(self.root.exists())
        self.assertNotIn("opaque-credential-value", self.stderr.getvalue())

    def test_proxy_credentials_reject_training_without_exposing_values(self):
        for value in ("https://user:opaque-password@proxy.invalid", "https://opaque-token@proxy.invalid"):
            with self.subTest(proxy=value), mock.patch.dict(os.environ, {"https_proxy": value}), \
                    mock.patch.object(loadonce.subprocess, "run") as child:
                with self.assertRaises(loadonce.CacheError):
                    loadonce.train(self.command, self.root, self.inputs)
                child.assert_not_called()
        self.assertFalse(self.root.exists())
        self.assertNotIn("opaque-password", self.stderr.getvalue())

    def test_noncredential_session_and_empty_credential_variables_allow_caching(self):
        with mock.patch.dict(os.environ, {"CODEX_SESSION_ID": "ordinary-session", "XDG_SESSION_TYPE": "x11",
                                        "GH_TOKEN": "", "HTTP_PROXY": "http://session-proxy.invalid:3128"}):
            self.assertEqual(self.train_cache(), 0)
            self.assertIn("-XX:AOTMode=auto", self.run_once())

    def test_native_library_environment_preserves_cold_launch_and_rejects_training(self):
        library = self.base / "injected.so"
        library.write_bytes(b"native-library-v1")
        for name in loadonce.NATIVE_ENV:
            value = str(library.parent if name.endswith("LIBRARY_PATH") else library)
            with self.subTest(environment=name), mock.patch.dict(os.environ, {name: value}), \
                    mock.patch.object(loadonce, "probe_java") as probe, \
                    mock.patch.object(loadonce.subprocess, "run", return_value=
                                      subprocess.CompletedProcess(self.command, 5)) as child:
                library.write_bytes(b"native-library-v2")
                self.assertEqual(loadonce.run(self.command, self.root, self.inputs), 5)
                child.assert_called_once_with(self.command)
                probe.assert_not_called()
                with self.assertRaises(loadonce.CacheError):
                    loadonce.train(self.command, self.root, self.inputs)
                child.assert_called_once_with(self.command)
                probe.assert_not_called()
        self.assertFalse(self.root.exists())

    def test_explicit_native_library_paths_invalidate_on_content_changes(self):
        libraries = self.base / "native"
        libraries.mkdir()
        library = libraries / "libfixture.so"
        base_command = self.command[:]
        for option in ("-Djava.library.path=", "-Dorg.lwjgl.librarypath=",
                       "-Dorg.lwjgl.system.SharedLibraryExtractPath="):
            with self.subTest(option=option):
                library.write_bytes(b"native-v1")
                self.command = [base_command[0], option + str(libraries), *base_command[1:]]
                self.assertIn(libraries, loadonce.launch_inputs(self.command)[0])
                self.assertEqual(self.train_cache(), 0)
                self.assertIn("-XX:AOTMode=auto", self.run_once())
                info = library.stat()
                library.write_bytes(b"native-v2")
                os.utime(library, ns=(info.st_atime_ns, info.st_mtime_ns))
                self.assertEqual(self.run_once(), self.command)

    def test_native_library_changes_during_training_discard_archive(self):
        native = self.base / "native"
        native.mkdir()
        library = native / "libfixture.so"
        library.write_bytes(b"native-v1")
        self.command.insert(1, "-Dorg.lwjgl.librarypath=" + str(native))
        with self.assertRaises(loadonce.CacheError):
            self.train_cache(mutate=lambda: library.write_bytes(b"native-v2"))
        self.assertFalse(any(self.root.glob("*/manifest.json")))

    def test_warm_launch_preserves_argument_boundaries_and_order(self):
        self.command.extend(["two words", "literal$HOME", "literal*", "unicode-π", "--app-option=value"])
        self.assertEqual(self.train_cache(), 0)
        self.assertEqual(self.run_once()[3:], self.command[1:])

    def test_runtime_classpath_resources_and_arguments_invalidate_cache(self):
        mutations = [
            lambda: (self.home / "lib" / "server" / "libjvm.so").write_bytes(b"different-runtime"),
            lambda: (self.home / "conf" / "java.security").write_bytes(b"different-security"),
            lambda: self.make_jar(self.jar, payload=b"different-class"),
            lambda: self.resource.write_bytes(b"resource-v2"),
            lambda: self.command.append("changed-argument"),
        ]
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                self.assertEqual(self.train_cache(), 0)
                mutate()
                self.assertEqual(self.run_once(), self.command)

    def test_fingerprint_uses_content_even_when_size_and_mtime_are_unchanged(self):
        before = loadonce.fingerprint(self.command, self.inputs)
        info = self.resource.stat()
        self.resource.write_bytes(b"resource-v2")
        os.utime(self.resource, ns=(info.st_atime_ns, info.st_mtime_ns))
        self.assertEqual(self.resource.stat().st_size, info.st_size)
        self.assertEqual(self.resource.stat().st_mtime_ns, info.st_mtime_ns)
        self.assertNotEqual(loadonce.fingerprint(self.command, self.inputs), before)

    def test_directory_and_wildcard_classpaths_fingerprint_nested_resources(self):
        classes = self.base / "classes"
        nested = classes / "assets" / "resource.bin"
        nested.parent.mkdir(parents=True)
        nested.write_bytes(b"nested-v1")
        command = [str(self.java), "-cp", str(classes), "Main"]
        before = loadonce.fingerprint(command, [])
        nested.write_bytes(b"nested-v2")
        self.assertNotEqual(loadonce.fingerprint(command, []), before)
        with mock.patch.object(loadonce.subprocess, "run") as child, self.assertRaises(loadonce.CacheError):
            loadonce.train(command, self.root, [])
        child.assert_not_called()
        wildcard = [str(self.java), "-cp", str(classes / "*"), "Main"]
        self.assertFalse(loadonce.launch_inputs(wildcard)[1])
        before = loadonce.fingerprint(wildcard, [])
        self.make_jar(classes / "new.jar")
        self.assertNotEqual(loadonce.fingerprint(wildcard, []), before)

    def test_jar_manifest_classpath_dependencies_are_fingerprinted_recursively(self):
        dependency = self.base / "dependency.jar"
        transitive = self.base / "transitive.jar"
        self.make_jar(transitive)
        self.make_jar(dependency, "Manifest-Version: 1.0\nClass-Path: transitive.jar\n\n")
        self.make_jar(self.jar, "Manifest-Version: 1.0\nClass-Path: dependency.jar\n\n")
        detected, _ = loadonce.launch_inputs(self.command)
        self.assertIn(transitive, detected)
        before = loadonce.fingerprint(self.command, [])
        self.make_jar(transitive, payload=b"updated-transitive-class")
        self.assertNotEqual(loadonce.fingerprint(self.command, []), before)

    def test_manifest_self_reference_normalizes_dot_segments(self):
        (self.base / "nested").mkdir()
        self.make_jar(self.jar, "Manifest-Version: 1.0\nClass-Path: nested/../app.jar\n\n")
        self.assertEqual(loadonce.launch_inputs(self.command), ([self.jar], False))
        before = loadonce.fingerprint(self.command, self.inputs)
        self.assertEqual(loadonce.fingerprint(self.command, self.inputs), before)

    def test_wildcard_jar_manifest_external_dependencies_are_fingerprinted(self):
        libraries = self.base / "libraries"
        libraries.mkdir()
        dependency = self.base / "outside.jar"
        self.make_jar(dependency)
        self.make_jar(libraries / "entry.jar",
                      "Manifest-Version: 1.0\nClass-Path: ../outside.jar\n\n")
        command = [str(self.java), "-cp", str(libraries / "*"), "Main"]
        before = loadonce.fingerprint(command, [])
        self.make_jar(dependency, payload=b"updated-external-class")
        self.assertNotEqual(loadonce.fingerprint(command, []), before)

    def test_duplicate_manifest_classpaths_choose_cold_before_application(self):
        self.make_jar(self.jar, "Manifest-Version: 1.0\nClass-Path: first.jar\n"
                      "Class-Path: effective-last.jar\n\n")
        self.assertEqual(self.run_once(), self.command)
        with mock.patch.object(loadonce.subprocess, "run") as child, self.assertRaises(loadonce.CacheError):
            loadonce.train(self.command, self.root, self.inputs)
        child.assert_not_called()

    def test_manifest_named_sections_and_empty_classpath_do_not_add_inputs(self):
        manifests = [
            "Manifest-Version: 1.0\n\nName: Main.class\n"
            "Class-Path: https://example.invalid/irrelevant.jar\n\n",
            "Manifest-Version: 1.0\nClass-Path: \nMain-Class: Main\n\n",
        ]
        for manifest in manifests:
            with self.subTest(manifest=manifest):
                self.make_jar(self.jar, manifest)
                self.assertEqual(loadonce.launch_inputs(self.command)[0], [self.jar])

    def test_nonjar_zip_manifest_and_continuation_dependencies_are_fingerprinted(self):
        dependency = self.base / "dependency.jar"
        self.make_jar(dependency)
        archive = self.base / "application.zip"
        self.make_jar(archive, "Manifest-Version: 1.0\r\nClass-Path: depen\r\n dency.jar\r\n\r\n")
        command = [str(self.java), "-cp", str(archive), "Main"]
        self.assertIn(dependency, loadonce.launch_inputs(command)[0])
        before = loadonce.fingerprint(command, [])
        self.make_jar(dependency, payload=b"changed-dependency")
        self.assertNotEqual(loadonce.fingerprint(command, []), before)

    def test_busy_cache_lock_uses_one_cold_launch_and_rejects_training(self):
        self.assertEqual(self.train_cache(), 0)
        holder = subprocess.Popen(
            [sys.executable, "-c",
             "import fcntl, os, sys; "
             "descriptor = os.open(sys.argv[1], os.O_RDWR); "
             "fcntl.flock(descriptor, fcntl.LOCK_EX); "
             "print('locked', flush=True); sys.stdin.readline()",
             str(self.root / ".lock")],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        )
        try:
            self.assertEqual(holder.stdout.readline(), "locked\n")
            self.assertEqual(self.run_once(), self.command)
            with mock.patch.object(loadonce, "probe_java", return_value={"leyden": True}), \
                    mock.patch.object(loadonce.subprocess, "run") as child, \
                    self.assertRaises(loadonce.CacheError):
                loadonce.train(self.command, self.root, self.inputs)
            child.assert_not_called()
            self.assertFalse(any(self.root.glob(".train-*")))
        finally:
            try:
                _, errors = holder.communicate("release\n", timeout=5)
            except subprocess.TimeoutExpired:
                holder.kill()
                holder.communicate()
                raise
        self.assertEqual(holder.returncode, 0, errors)
        self.assertEqual(self.run_once()[3:], self.command[1:])

    def test_declared_input_symlink_cycle_chooses_cold_and_rejects_training(self):
        (self.resource.parent / "cycle").symlink_to("cycle")
        self.assertEqual(self.run_once(returncode=11), self.command)
        with mock.patch.object(loadonce, "probe_java", return_value={"leyden": True}), \
                mock.patch.object(loadonce.subprocess, "run") as child, \
                self.assertRaises(loadonce.CacheError):
            loadonce.train(self.command, self.root, self.inputs)
        child.assert_not_called()
        self.assertFalse(any(self.root.glob(".train-*")))

    def test_malformed_manifest_url_chooses_cold_and_rejects_training(self):
        self.make_jar(self.jar, "Manifest-Version: 1.0\nClass-Path: http://[\n\n")
        self.assertEqual(self.run_once(returncode=13), self.command)
        with mock.patch.object(loadonce.subprocess, "run") as child, self.assertRaises(loadonce.CacheError):
            loadonce.train(self.command, self.root, self.inputs)
        child.assert_not_called()
        self.assertFalse(self.root.exists())

    def test_corrupt_or_incomplete_manifest_chooses_cold(self):
        self.assertEqual(self.train_cache(), 0)
        manifest = self.cache_entry() / "manifest.json"
        original = json.loads(manifest.read_text())
        corruptions = [
            {**original, "completed": False},
            {**original, "schema": True},
            {**original, "schema": loadonce.SCHEMA - 1},
            {**original, "schema": loadonce.SCHEMA + 1},
            {**original, "uid": os.getuid() + 1},
            {**original, "bytes": True},
            {**original, "fingerprint": "different"},
            {**original, "sha256": "0" * 64},
            {**original, "unexpected": "field"},
            [],
        ]
        for record in corruptions:
            with self.subTest(record=record):
                manifest.write_text(json.dumps(record))
                self.assertEqual(self.run_once(), self.command)
        manifest.write_text("{broken json")
        self.assertEqual(self.run_once(), self.command)

    def test_archive_corruption_and_unsafe_file_permissions_choose_cold(self):
        self.assertEqual(self.train_cache(), 0)
        entry = self.cache_entry()
        archive = entry / "cache.aot"
        original = archive.read_bytes()
        archive.write_bytes(b"X" * len(original))
        self.assertEqual(self.run_once(), self.command)
        archive.write_bytes(original)
        for path in (entry / "manifest.json", archive):
            with self.subTest(path=path):
                path.chmod(0o644)
                self.assertEqual(self.run_once(), self.command)
                path.chmod(0o600)
        self.root.chmod(0o755)
        self.assertEqual(self.run_once(), self.command)

    def test_symlink_and_hardlink_cache_artifacts_choose_cold(self):
        self.assertEqual(self.train_cache(), 0)
        archive = self.cache_entry() / "cache.aot"
        target = self.base / "shared-aot"
        archive.rename(target)
        archive.symlink_to(target)
        self.assertEqual(self.run_once(), self.command)
        archive.unlink()
        os.link(target, archive)
        self.assertEqual(self.run_once(), self.command)


if __name__ == "__main__":
    unittest.main()
