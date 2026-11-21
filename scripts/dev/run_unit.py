"""Runs REau's JVM unit tests, optionally only some classes, and says plainly how it ended.

Usage:  python scripts/dev/run_unit.py [TestClass ...]
Gradle needs a JDK 21: JAVA_HOME set, or java on the PATH.
Exit code 0 = green. Prints "NÃO COMPILOU" when the build broke before any test ran (a mutation runner must not
count that as a test catching the change), then the "e:" lines or, with none, what Gradle says went wrong, or one "FALHOU: <test>" line per failed test.
"""
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ENV = dict(os.environ)
GRADLEW = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[union-attr]  # a TextIOWrapper, from a terminal or a pipe


def cause(out, limit=1500):
    """Why a build failed that had no compile error and no failed test: the "What went wrong" block of Gradle, or the end of its output."""
    lines = out.splitlines()
    if "* What went wrong:" in lines:
        block = lines[lines.index("* What went wrong:") + 1:]
        block = block[:block.index("* Try:")] if "* Try:" in block else block
        text = "\n".join(line.rstrip() for line in block if line.strip())
    else:
        text = "\n".join(line.rstrip() for line in lines[-15:] if line.strip())
    return ("sem erro de compilação nem teste que falhou; o Gradle diz:\n" + (text or "(nenhuma saída)"))[:limit]


def main(classes):
    # One app since 0.3.0 (no editions): a single test task. The old "--lite" among the classes is ignored.
    classes = [name for name in classes if name != "--lite"]
    # -PreauAllowWarnings: the build fails on a compiler warning (app/build.gradle.kts), and a mutation may leave one by itself.
    command = [str(GRADLEW), ":app:testDebugUnitTest", "--console=plain", "-PreauAllowWarnings"]
    for name in classes:
        command += ["--tests", f"*.{name}"]
    done = subprocess.run(command, cwd=ROOT, env=ENV, capture_output=True, text=True, encoding="utf-8",
                          errors="replace", stdin=subprocess.DEVNULL)
    out = done.stdout + done.stderr
    if done.returncode == 0:
        print("VERDE")
        return 0
    failed = re.findall(r"^(\w+ > .+) FAILED$", out, flags=re.MULTILINE)
    if not failed:
        print("NÃO COMPILOU")
        errors = [line for line in out.splitlines() if line.startswith("e: ")]
        # No "e:" line and no failed test: the build broke for a reason that is neither (a task, a download, a missing test class, no disk).
        # Say it: "NÃO COMPILOU" alone made 84 mutations of a CI slice look invalid with no hint of why (20/11/2026).
        print("\n".join(errors)[:1500] if errors else cause(out))
        return 2
    for name in failed:
        print(f"FALHOU: {name}")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
