"""Real JDK 25 AOT integration check; run separately from the unit suite."""

import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile


def main():
    java_home = Path(os.environ["JAVA_HOME"])
    java, javac, jar = (str(java_home / "bin" / name) for name in ("java", "javac", "jar"))
    wrapper = Path(__file__).resolve().parents[1] / "tools" / "loadonce.py"
    # The fixture needs no authentication or injected JVM/native options.
    # Inherited developer/CI credentials must never enter its training JVM.
    clean_environment = {key: os.environ[key] for key in
                         ("PATH", "HOME", "JAVA_HOME", "LANG", "LC_ALL", "LC_CTYPE", "TMPDIR", "SYSTEMROOT")
                         if key in os.environ}
    with tempfile.TemporaryDirectory(prefix="loadonce-real-smoke-") as temporary:
        root = Path(temporary)
        source = root / "Smoke.java"
        source.write_text("""import java.nio.file.*;
public class Smoke {
    public static void main(String[] args) throws Exception {
        Path counter = Path.of(args[0]);
        int count = Files.exists(counter) ? Integer.parseInt(Files.readString(counter)) : 0;
        Files.writeString(counter, Integer.toString(count + 1));
        System.out.println("APP:" + String.join("|", java.util.Arrays.copyOfRange(args, 1, args.length)));
        System.exit(Integer.parseInt(System.getenv().getOrDefault("SMOKE_EXIT", "0")));
    }
}
""")
        subprocess.run([javac, str(source)], env=clean_environment, check=True, timeout=60)
        archive = root / "smoke.jar"
        subprocess.run([jar, "--create", "--file", str(archive), "-C", str(root), "Smoke.class"],
                       env=clean_environment, check=True, timeout=60)
        counter = root / "counter"
        command = [java, "-Xlog:aot", "-cp", str(archive), "Smoke", str(counter),
                   "a b", "literal$HOME", 'quote"value', "--flag=value"]
        expected = 'APP:a b|literal$HOME|quote"value|--flag=value'

        def invoke(mode, environment=None):
            result = subprocess.run(
                [sys.executable, str(wrapper), mode, "--cache-dir", str(root / "cache"), "--", *command],
                text=True, capture_output=True,
                env=clean_environment if environment is None else environment, timeout=60,
            )
            assert result.stdout.count(expected) == 1, result.stdout + result.stderr
            return result

        cold = invoke("run")
        assert cold.returncode == 0 and "ordinary startup" in cold.stderr, cold.stderr
        training = invoke("train")
        assert training.returncode == 0, training.stderr
        warm = invoke("run")
        assert warm.returncode == 0 and "using validated" in warm.stderr, warm.stderr
        assert "Opened AOT cache" in warm.stdout, warm.stdout
        failed = invoke("run", dict(clean_environment, SMOKE_EXIT="7"))
        assert failed.returncode == 7 and "using validated" in failed.stderr, failed.stderr
        assert counter.read_text() == "4", "an application launch was lost or replayed"

        entry = next((root / "cache").glob("*/cache.aot"))
        original = entry.read_bytes()
        failed_training = invoke("train", dict(clean_environment, SMOKE_EXIT="13"))
        assert failed_training.returncode == 13, failed_training.stderr
        assert entry.read_bytes() == original, "unsuccessful training replaced the existing cache"
        assert counter.read_text() == "5", "unsuccessful training lost or replayed its application launch"

        # Pass the outer integrity check but reject the archive inside HotSpot.
        # auto must fall back within this JVM, even when main exits nonzero.
        entry.write_bytes(b"not-a-hotspot-archive" * 50)
        manifest = entry.parent / "manifest.json"
        record = json.loads(manifest.read_text())
        record.update(sha256=hashlib.sha256(entry.read_bytes()).hexdigest(), bytes=entry.stat().st_size)
        manifest.write_text(json.dumps(record))
        rejected = invoke("run", dict(clean_environment, SMOKE_EXIT="7"))
        assert rejected.returncode == 7 and "using validated" in rejected.stderr, rejected.stderr
        assert "bad magic number" in rejected.stdout, rejected.stdout
        assert counter.read_text() == "6", "JVM archive rejection lost or replayed the application"

        entry.write_bytes(b"corrupt")
        corrupt = invoke("run")
        assert corrupt.returncode == 0 and "ordinary startup" in corrupt.stderr, corrupt.stderr
        assert counter.read_text() == "7", "corruption fallback replayed the application"

        credentials = subprocess.run(
            [sys.executable, str(wrapper), "train", "--cache-dir", str(root / "cache"), "--", *command],
            text=True, capture_output=True, env=dict(clean_environment, GH_TOKEN="fixture-credential"), timeout=60,
        )
        assert credentials.returncode == 2 and "credential environments" in credentials.stderr, credentials.stderr
        assert "APP:" not in credentials.stdout and counter.read_text() == "7", "credential training ran main"
        assert "fixture-credential" not in credentials.stdout + credentials.stderr, "credential value was logged"
    print("Real Leyden smoke passed: AOT mapping, exact arguments, training/run failures without replay, "
          "JVM archive rejection, corruption fallback, and credential-environment refusal.")


if __name__ == "__main__":
    main()
