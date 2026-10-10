"""Real JDK 25 AOT integration check; run separately from the unit suite."""

import os
from pathlib import Path
import subprocess
import sys
import tempfile


def main():
    java_home = Path(os.environ["JAVA_HOME"])
    java, javac, jar = (str(java_home / "bin" / name) for name in ("java", "javac", "jar"))
    wrapper = Path(__file__).resolve().parents[1] / "tools" / "loadonce.py"
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
        subprocess.run([javac, str(source)], check=True, timeout=60)
        archive = root / "smoke.jar"
        subprocess.run([jar, "--create", "--file", str(archive), "-C", str(root), "Smoke.class"],
                       check=True, timeout=60)
        counter = root / "counter"
        command = [java, "-Xlog:aot", "-cp", str(archive), "Smoke", str(counter),
                   "a b", "literal$HOME", 'quote"value', "--flag=value"]
        expected = 'APP:a b|literal$HOME|quote"value|--flag=value'

        def invoke(mode, environment=None):
            result = subprocess.run(
                [sys.executable, str(wrapper), mode, "--cache-dir", str(root / "cache"), "--", *command],
                text=True, capture_output=True, env=environment, timeout=60,
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
        failed = invoke("run", dict(os.environ, SMOKE_EXIT="7"))
        assert failed.returncode == 7 and "using validated" in failed.stderr, failed.stderr
        assert counter.read_text() == "4", "an application launch was lost or replayed"

        entry = next((root / "cache").glob("*/cache.aot"))
        entry.write_bytes(b"corrupt")
        corrupt = invoke("run")
        assert corrupt.returncode == 0 and "ordinary startup" in corrupt.stderr, corrupt.stderr
        assert counter.read_text() == "5", "corruption fallback replayed the application"
    print("Real Leyden smoke passed: AOT mapping, exact arguments, failure without replay, corruption fallback.")


if __name__ == "__main__":
    main()
