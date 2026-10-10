"""Validation and real stock-JDK refusal checks; these never fabricate an OS restore."""

import argparse
import contextlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock
import zipfile

import checkpoint


class CheckpointValidationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="loadonce-checkpoint-validation-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.entry = self.root / "entry"
        self.entry.mkdir(mode=0o700)
        self.image = self.entry / "image"
        self.image.mkdir(mode=0o700)
        # Synthetic validator inputs only. No runner capture/publication path is
        # mocked and these bytes are never presented to a CRIU restore process.
        (self.image / "inventory.img").write_bytes(b"validator inventory fixture")
        (self.image / "pstree.img").write_bytes(b"validator process-tree fixture")
        self.key = "a" * 64
        self.manifest = {"schemaVersion": checkpoint.SCHEMA, "backend": "crac-criu-fixture",
                         "fingerprint": self.key, "nativeRestoreValidated": True,
                         "fixtureHeapSha256": "b" * 64,
                         "imageSha256": checkpoint.image_inventory(self.image)}
        self.write_manifest()

    def write_manifest(self):
        (self.entry / "manifest.json").write_text(json.dumps(self.manifest))

    def test_private_matching_manifest_validates_then_mutation_is_refused(self):
        self.assertEqual(checkpoint.validate_manifest(self.entry, self.key), self.manifest)
        (self.image / "pstree.img").write_bytes(b"modified same owner")
        with self.assertRaises(checkpoint.Unsupported):
            checkpoint.validate_manifest(self.entry, self.key)

    def test_unverified_or_different_runtime_manifest_is_refused(self):
        for key, value in (("nativeRestoreValidated", False), ("fingerprint", "c" * 64),
                           ("fixtureHeapSha256", "not a checksum")):
            with self.subTest(key=key):
                original = self.manifest[key]
                self.manifest[key] = value
                self.write_manifest()
                with self.assertRaises(checkpoint.Unsupported):
                    checkpoint.validate_manifest(self.entry, self.key)
                self.manifest[key] = original

    def test_extra_image_file_changes_inventory(self):
        (self.image / "unexpected.img").write_bytes(b"new image data")
        with self.assertRaises(checkpoint.Unsupported):
            checkpoint.validate_manifest(self.entry, self.key)

    def test_empty_or_missing_native_inventory_is_refused(self):
        (self.image / "inventory.img").write_bytes(b"")
        with self.assertRaises(checkpoint.Unsupported):
            checkpoint.image_inventory(self.image)
        (self.image / "inventory.img").unlink()
        with self.assertRaises(checkpoint.Unsupported):
            checkpoint.image_inventory(self.image)

    def test_symlinked_image_and_insecure_entry_are_refused(self):
        (self.image / "escape.img").symlink_to(self.entry / "manifest.json")
        with self.assertRaises(checkpoint.Unsupported):
            checkpoint.validate_manifest(self.entry, self.key)
        (self.image / "escape.img").unlink()
        self.entry.chmod(0o755)
        with self.assertRaises(checkpoint.Unsupported):
            checkpoint.validate_manifest(self.entry, self.key)

    def test_missing_manifest_is_refused_without_creating_an_entry(self):
        absent = self.root / "absent"
        with self.assertRaises(checkpoint.Unsupported):
            checkpoint.validate_manifest(absent, self.key)
        self.assertFalse(absent.exists())

    def test_restore_proof_requires_heap_marker_and_new_readiness(self):
        report = self.root / "ready.json"
        run_id = "fresh-restored-run"
        checksum = "b" * 64
        report.write_text(json.dumps({"schemaVersion": 1, "runId": run_id,
                                     "milestone": "server_ready", "elapsedNanos": 42}))
        # This verifies the output parser only, not any operating-system restore.
        stdout = ("RESTORED: CRaC restored execution\n"
                  f"RESTORED fixture heap verified resources=2048 sha256={checksum}\n"
                  f"fixture initialized resources=2048 sha256={checksum}\n")
        result = subprocess.CompletedProcess([], 0, stdout, "")
        self.assertEqual(checkpoint.check_restored(result, report, run_id, checksum)["elapsedNanos"], 42)
        with self.assertRaises(checkpoint.Unsupported):
            checkpoint.check_restored(result, report, "old-captured-run", checksum)
        result.stdout = stdout.replace("RESTORED fixture heap verified", "unverified fixture heap")
        with self.assertRaises(checkpoint.Unsupported):
            checkpoint.check_restored(result, report, run_id, checksum)

    def test_fixture_manifest_dependency_is_refused_case_insensitively(self):
        classes = self.root / "classes"
        class_name = checkpoint.FIXTURE_MAIN.replace(".", "/") + ".class"
        class_path = classes / class_name
        class_path.parent.mkdir(parents=True)
        class_path.write_bytes(b"\xca\xfe\xba\xbe\x00\x00\x00\x3dvalidator-only")
        jar_path = self.root / "runtime.jar"
        manifest = (f"Manifest-Version: 1.0\nMain-Class: {checkpoint.FIXTURE_MAIN}\n"
                    f"Premain-Class: {checkpoint.FIXTURE_AGENT}\nclass-path: external.jar\n\n")
        with zipfile.ZipFile(jar_path, "w") as jar:
            jar.writestr("META-INF/MANIFEST.MF", manifest)
            jar.writestr(class_name, class_path.read_bytes())
        with mock.patch.object(checkpoint, "FIXTURE", jar_path), mock.patch.object(checkpoint, "CLASSES", classes):
            with self.assertRaises(checkpoint.Unsupported):
                checkpoint.validate_fixture()


class StockJdkChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        found = shutil.which("java")
        if not found:
            raise unittest.SkipTest("a real Java executable is required for doctor checks")
        cls.java = Path(found).resolve()

    def test_doctor_has_no_readiness_claim_and_omits_injected_secret(self):
        canary = "loadonce-doctor-secret-canary-98afe0"
        with mock.patch.dict(os.environ, {"JDK_JAVA_OPTIONS": "-Dsecret=" + canary,
                                         "JAVA_TOOL_OPTIONS": "-Dother.secret=" + canary,
                                         "CRAC_CRIU_OPTS": canary}):
            result = checkpoint.doctor(self.java)
        self.assertFalse(result["nativeRestoreValidated"])
        self.assertTrue(result["capabilitiesAreNotReadiness"])
        self.assertFalse(result["criu"]["checkPerformed"])
        self.assertNotIn(canary, json.dumps(result))
        self.assertNotIn("JDK_JAVA_OPTIONS", json.dumps(result))

    def test_stock_jdk_capture_refuses_before_creating_image(self):
        observed = checkpoint.doctor(self.java)
        if {"CRaCCheckpointTo", "CRaCRestoreFrom"}.issubset(observed["cracFlags"]):
            self.skipTest("this is a CRaC JDK; the stock-JDK refusal contract does not apply")
        with tempfile.TemporaryDirectory(prefix="loadonce-stock-refusal-") as temporary:
            cache = Path(temporary) / "cache"
            args = argparse.Namespace(action="capture", fixture_safe=True, cache_dir=cache,
                                      criu=None, criu_library_dir=None)
            code, result = checkpoint.experiment(args, self.java)
            self.assertEqual(code, 2)
            self.assertEqual(result["status"], "unsupported")
            self.assertFalse(result["nativeRestoreValidated"])
            self.assertFalse(cache.exists())

    def test_missing_fixture_attestation_never_starts_a_process(self):
        args = argparse.Namespace(action="capture", fixture_safe=False)
        with mock.patch.object(checkpoint, "doctor", side_effect=AssertionError("must not probe")):
            with self.assertRaises(checkpoint.Unsupported):
                checkpoint.experiment(args, self.java)

    def test_cli_does_not_accept_arbitrary_application_commands(self):
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as failure:
                checkpoint.main(["capture", "--fixture-safe", "--", "-jar", "minecraft.jar", "--accessToken", "secret"])
        self.assertEqual(failure.exception.code, 2)


if __name__ == "__main__":
    unittest.main()
