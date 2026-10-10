# Experimental JDK 25 AOT backend

`leyden.py` wraps an ordinary Java JAR launch with the JDK's persistent AOT
cache. It uses the JEP 514 single-command workflow: the first application runs
normally, then HotSpot launches an assembly JVM after exit. The assembly JVM
does not invoke application `main`. A successful, unchanged training launch
publishes an integrity-checked cache; the next launch uses it with
`-XX:AOTMode=auto`.

```bash
python3 /path/to/LoadOnce/poc/leyden.py \
  --cache-dir .loadonce-leyden-cache \
  --report leyden-metrics.json \
  --input assets \
  -- java -Xmx4G -jar server.jar nogui
```

Run from the installation directory. Existing arguments follow the original
Java command unchanged. Supply additional immutable inputs with `--input`.
The common fingerprint includes JARs and their local manifest dependencies,
JDK binaries/modules, JVM options, platform, working directory, `mods`,
`plugins`, `config`, `resourcepacks`, `options.txt`, and `server.properties`.
Cache roots and entries require mode `0700`; archives, manifests, and reports
use `0600`. Application arguments and environment values are not saved in
manifests or reports. Temporary AOT configurations and diagnostic logs are
removed after each invocation.

The application performs initialization on every launch, reads current world
files, creates new native resources, and receives fresh launcher session
arguments. This backend preserves class loading/linking work and the JDK's
supported training data; it is not a process checkpoint, initialized mod heap,
GPU snapshot, or native executable. JDK 25 may reuse method profiles, but this
prototype does not verify application JIT code persistence or profile-specific
speedups.

Supported launches are `-jar` or an explicit local JAR classpath on a Unix
host with a JDK implementing `AOTCacheOutput` (tested with Oracle 25.0.2).
Java agents, module/source launches, argument files, wildcard/directory
classpaths, conflicting AOT/CDS controls, custom JVM logging, environment
injected JVM/AOT options, unsafe cache paths, and JVM `-D` properties use the
original command. Properties are deliberately excluded because the assembly
JVM echoes them through `JAVA_TOOL_OPTIONS`, which could expose secrets.
Many Minecraft launcher commands therefore still use conventional startup
with this experimental backend. The separate Dynamic CDS wrapper supports
additional integrations.

If the VM declines a selected cache, auto mode continues ordinary startup.
The wrapper invalidates the manifest for the next launch and never reruns
the application. It also preserves nonzero application exits and never
publishes failed training runs. Concurrent training uses a nonblocking lock;
other launches proceed conventionally.

`aotCacheLoaded` requires the selected file's map plus HotSpot's
`Using AOT-linked classes: true` diagnostic. This avoids treating the JDK's
default class archive as a successful application-cache hit. Metrics report
preparation time and total invocation time; total time includes application
execution and first-run assembly, so it is not an application-readiness metric.
Full input hashing and a flag probe add measurable launcher overhead; a
Minecraft readiness benchmark is required before claiming a startup gain.

Validation:

```bash
python3 -m unittest discover -s poc -p test_leyden.py -v
```

The real-JDK suite verifies cache mapping, one `main` invocation per launch,
failed launch handling, byte-content invalidation, corrupt-cache fallback,
concurrent training, current world data, fresh token arguments, and absence
of token canaries from persistent cache files. No display, audio device,
Minecraft EULA acceptance, or additional network host is needed for this suite.

References: [JEP 483](https://openjdk.org/jeps/483),
[JEP 514](https://openjdk.org/jeps/514),
[JEP 515](https://openjdk.org/jeps/515), and the installed JDK's
`man/man1/java.1` section “Ahead-of-Time Cache”. Exact flags and automatic
fallback were checked against that manual and the running JVM.
