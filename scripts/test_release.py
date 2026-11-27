"""Tests for scripts/release.sh: the checks that stop a release, and what it copies and sends when they all pass.

Each test builds a small git repository in a temporary folder (the script is copied into its scripts/, since it finds the repository by
its own place) and runs the script against fakes: a `gradlew` that writes a pretend APK, an `apksigner` in a fake SDK, a `gh` on the
PATH, and an audit script. Every fake writes its arguments to a log, so a test can say what was NOT run (nothing is compiled or sent
when a check fails). git is the real one, on the temporary repository only. The release key is never used: the variables hold dummy
secrets, and the tests check that no output repeats them. Run from the repo root:  python3 -I -m unittest discover -s scripts -p "test_release.py" -v
"""
import os
import shutil
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "release.sh"
OFFICIAL = "63f5c3ba687699b0b4288a878bd8372bed2c9632222d7f673ea71073aa208970"
OTHER = "a" * 64
SECRETS = {
    "REAU_KEY_ALIAS": "alias-secreto-xyz",
    "REAU_KEYSTORE_PASSWORD": "senha-da-loja-123",
    "REAU_KEY_PASSWORD": "senha-da-chave-456",
}
GIT_ENV = {"GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@example.com", "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@example.com",
           "GIT_CONFIG_GLOBAL": "/dev/null", "GIT_CONFIG_SYSTEM": "/dev/null"}


def executable(path: Path, text: str) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    path.chmod(path.stat().st_mode | stat.S_IXUSR)
    return path


def fake_apksigner(path: Path, cert: str, v2: bool = True) -> None:
    v2_line = f"Verified using v2 scheme (APK Signature Scheme v2): {'true' if v2 else 'false'}"
    executable(path, f"""#!/usr/bin/env bash
echo "apksigner $*" >> "$FAKE_LOG"
echo "Verifies"
echo "Verified using v1 scheme (JAR signing): false"
echo "{v2_line}"
echo "Number of signers: 1"
echo "Signer #1 certificate DN: CN=REau"
echo "Signer #1 certificate SHA-256 digest: {cert.upper()}"
""")


class Release:
    """A temporary repository at version 0.3.1, tagged v0.3.1, with the fakes around it."""

    def __init__(self, tmp: Path):
        self.tmp = tmp
        self.repo = tmp / "repo"
        self.log = tmp / "calls.log"
        self.sdk = tmp / "sdk"
        self.bin = tmp / "bin"
        self.keystore = tmp / "chave.jks"
        self.keystore.write_bytes(b"not a real key")
        (self.repo / "scripts").mkdir(parents=True)
        (self.repo / "app").mkdir()
        (self.repo / "scripts" / "release.sh").write_text(SCRIPT.read_text(encoding="utf-8"), encoding="utf-8")
        (self.repo / "app" / "build.gradle.kts").write_text('val appVersionName = "0.3.1"\nval releaseVersionCode = 30_030_100\n', encoding="utf-8")
        for name in ("CHANGELOG.md", "CHANGELOG.pt-br.md", "CHANGELOG.es.md"):
            (self.repo / name).write_text("# Changelog\n\n## [0.3.1] - 2026-12-01\n\n### Fixed\n- x\n\n## [0.3.0] - 2026-11-25\n", encoding="utf-8")
        (self.repo / ".gitignore").write_text("/build\n/app/build\n", encoding="utf-8")
        executable(self.repo / "gradlew", """#!/usr/bin/env bash
echo "gradlew $*" >> "$FAKE_LOG"
echo "env REAU_KEYSTORE=${REAU_KEYSTORE:-vazio} REAU_KEY_PASSWORD=${REAU_KEY_PASSWORD:-vazio}" >> "$FAKE_LOG"
if [ -n "${FAKE_GRADLE_FAILS:-}" ]; then exit 1; fi
mkdir -p app/build/outputs/apk/release
printf 'APK de mentira' > app/build/outputs/apk/release/app-release.apk
""")
        executable(self.repo / "scripts" / "audit.py", """import os, sys
open(os.environ["FAKE_LOG"], "a").write("audit " + " ".join(sys.argv[1:]) + "\\n")
sys.exit(int(os.environ.get("FAKE_AUDIT_EXIT", "0")))
""")
        executable(self.bin / "gh", '#!/usr/bin/env bash\necho "gh $*" >> "$FAKE_LOG"\n')
        fake_apksigner(self.sdk / "build-tools" / "36.1.0" / "apksigner", OFFICIAL)
        fake_apksigner(self.sdk / "build-tools" / "9.0.0" / "apksigner", OTHER)  # older by version, newer as text: must not be taken
        self.git("init", "-q", "-b", "main")
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "release 0.3.1")
        self.git("tag", "-a", "v0.3.1", "-m", "REau 0.3.1")
        self.notes = tmp / "notas.md"
        self.notes.write_text("Notas da release.\n", encoding="utf-8")
        self.apk = tmp / "pronto.apk"
        self.apk.write_bytes(b"APK ja montado")

    def guard_git(self) -> None:
        """Puts a `git` on the PATH that logs every call and refuses (exit 99) the ones that change anything, then hands the rest to the real git."""
        real = shutil.which("git")
        executable(self.bin / "git", f"""#!/usr/bin/env bash
echo "git $*" >> "$FAKE_LOG"
case "$1" in
  status | rev-parse | log | diff | show | describe | ls-files | cat-file | tag) ;;
  *) echo "PROIBIDO git $*" >> "$FAKE_LOG"; exit 99 ;;
esac
if [ "$1" = tag ] && [ $# -gt 1 ]; then echo "PROIBIDO git $*" >> "$FAKE_LOG"; exit 99; fi
exec {real} "$@"
""")

    def git(self, *args: str) -> str:
        return subprocess.run(["git", *args], cwd=self.repo, env={**os.environ, **GIT_ENV}, capture_output=True, text=True, check=True).stdout.strip()

    def run(self, *args: str, env: dict | None = None, remove: tuple = ()):
        full = {
            **os.environ, **GIT_ENV, **SECRETS,
            "PATH": f"{self.bin}{os.pathsep}{os.environ['PATH']}", "HOME": str(self.tmp / "home"),
            "ANDROID_HOME": str(self.sdk), "ANDROID_SDK_ROOT": "", "REAU_KEYSTORE": str(self.keystore),
            "FAKE_LOG": str(self.log), "APK_AUDIT": str(self.repo / "scripts" / "audit.py"),
            **(env or {}),
        }
        for name in remove:
            full.pop(name, None)
        return subprocess.run(["bash", str(self.repo / "scripts" / "release.sh"), *args], env=full, capture_output=True, text=True, cwd=self.tmp)

    def calls(self) -> list[str]:
        return self.log.read_text(encoding="utf-8").splitlines() if self.log.exists() else []

    def called(self, prefix: str) -> bool:
        return any(c.startswith(prefix) for c in self.calls())


class ReleaseCase(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.r = Release(Path(self._tmp.name))

    def assertStopped(self, done, message: str, compiled: bool = False):
        self.assertEqual(1, done.returncode, done.stdout + done.stderr)
        self.assertIn(message, done.stderr)
        self.assertEqual(compiled, self.r.called("gradlew"), self.r.calls())
        self.assertFalse(self.r.called("gh"), self.r.calls())
        self.assertFalse((self.r.repo / "build").exists())


class RefusedBeforeBuildingTest(ReleaseCase):
    def test_a_dirty_tree_stops_before_anything_is_built(self):
        (self.r.repo / "sobra.txt").write_text("x", encoding="utf-8")
        done = self.r.run("0.3.1")
        self.assertStopped(done, "não está limpa")
        self.assertIn("sobra.txt", done.stderr)

    def test_a_modified_tracked_file_is_dirty_too(self):
        (self.r.repo / "CHANGELOG.md").write_text("mudou\n", encoding="utf-8")
        self.assertStopped(self.r.run("0.3.1"), "não está limpa")

    def test_a_missing_tag_stops_and_says_how_to_make_it_without_making_it(self):
        self.r.git("tag", "-d", "v0.3.1")
        done = self.r.run("0.3.1")
        self.assertStopped(done, 'git tag -a v0.3.1 -m "REau 0.3.1"')
        self.assertEqual("", self.r.git("tag", "--list"))

    def test_a_tag_on_another_commit_stops(self):
        (self.r.repo / "novo.txt").write_text("x", encoding="utf-8")
        self.r.git("add", "-A")
        self.r.git("commit", "-q", "-m", "depois da tag")
        done = self.r.run("0.3.1")
        self.assertStopped(done, "a tag v0.3.1 aponta para")
        self.assertIn(self.r.git("rev-parse", "v0.3.1^{commit}")[:12], done.stderr)

    def test_a_version_different_from_the_build_version_name_stops(self):
        for name in ("CHANGELOG.md", "CHANGELOG.pt-br.md", "CHANGELOG.es.md"):
            path = self.r.repo / name
            path.write_text(path.read_text(encoding="utf-8").replace("0.3.1", "0.3.2"), encoding="utf-8")
        self.r.git("commit", "-q", "-am", "changelogs na 0.3.2")
        self.r.git("tag", "-a", "v0.3.2", "-m", "REau 0.3.2")
        done = self.r.run("0.3.2")
        self.assertStopped(done, "o versionName do build é 0.3.1, não 0.3.2")

    def test_a_changelog_without_the_section_stops_and_names_it(self):
        path = self.r.repo / "CHANGELOG.es.md"
        path.write_text(path.read_text(encoding="utf-8").replace("## [0.3.1] - 2026-12-01", "## [0.3.1]"), encoding="utf-8")
        self.r.git("commit", "-q", "-am", "sem data")
        self.r.git("tag", "-f", "-a", "v0.3.1", "-m", "REau 0.3.1")
        self.assertStopped(self.r.run("0.3.1"), "CHANGELOG.es.md não tem a seção")

    def test_a_missing_key_variable_stops_naming_only_the_variable(self):
        done = self.r.run("0.3.1", remove=("REAU_KEY_PASSWORD",))
        self.assertStopped(done, "REAU_KEY_PASSWORD")
        self.assertNotIn("REAU_KEY_ALIAS", done.stderr)
        for secret in SECRETS.values():
            self.assertNotIn(secret, done.stdout + done.stderr)

    def test_several_missing_variables_are_all_named_and_no_value_leaks(self):
        done = self.r.run("0.3.1", env={"REAU_KEY_ALIAS": ""}, remove=("REAU_KEYSTORE_PASSWORD",))
        self.assertStopped(done, "REAU_KEY_ALIAS REAU_KEYSTORE_PASSWORD")
        for secret in SECRETS.values():
            self.assertNotIn(secret, done.stdout + done.stderr)

    def test_a_keystore_that_is_not_a_file_stops_without_printing_its_path(self):
        done = self.r.run("0.3.1", env={"REAU_KEYSTORE": str(self.r.tmp / "nao-existe.jks")})
        self.assertStopped(done, "REAU_KEYSTORE não é um arquivo")
        self.assertNotIn("nao-existe", done.stdout + done.stderr)

    def test_a_relative_keystore_path_stops(self):
        self.assertStopped(self.r.run("0.3.1", env={"REAU_KEYSTORE": "chave.jks"}), "ABSOLUTO")

    def test_publishing_without_notes_is_a_usage_error_before_any_check(self):
        done = self.r.run("0.3.1", "--publicar")
        self.assertEqual(2, done.returncode)
        self.assertIn("--notas", done.stderr)
        self.assertEqual([], self.r.calls())

    def test_a_bad_version_or_option_is_a_usage_error(self):
        for args in (("v0.3.1",), ("0.3",), (), ("0.3.1", "--nada"), ("0.3.1", "--apk", "x.apk"), ("0.3.1", "--so-conferir")):
            with self.subTest(args=args):
                self.assertEqual(2, self.r.run(*args).returncode)


class RefusedAfterBuildingTest(ReleaseCase):
    def test_a_different_certificate_stops_and_nothing_is_copied_or_sent(self):
        fake_apksigner(self.r.sdk / "build-tools" / "36.1.0" / "apksigner", OTHER)
        done = self.r.run("0.3.1", "--publicar", "--notas", str(self.r.notes))
        self.assertStopped(done, "o certificado do APK não é o do projeto", compiled=True)
        self.assertIn(OTHER, done.stderr)
        self.assertFalse(self.r.called("audit"))

    def test_a_signature_without_v2_stops(self):
        fake_apksigner(self.r.sdk / "build-tools" / "36.1.0" / "apksigner", OFFICIAL, v2=False)
        self.assertStopped(self.r.run("0.3.1"), "não confirma a assinatura v2", compiled=True)

    def test_the_newest_build_tools_by_version_number_is_the_one_used(self):
        done = self.r.run("0.3.1")
        self.assertEqual(0, done.returncode, done.stderr)
        self.assertEqual(1, sum(1 for c in self.r.calls() if c.startswith("apksigner")))
        self.assertIn("apksigner verify --verbose --print-certs", " ".join(self.r.calls()))

    def test_no_apksigner_stops(self):
        done = self.r.run("0.3.1", env={"ANDROID_HOME": str(self.r.tmp / "vazio")})
        self.assertStopped(done, "não achei o apksigner", compiled=True)

    def test_an_audit_that_finds_something_new_stops_before_the_copy(self):
        done = self.r.run("0.3.1", env={"FAKE_AUDIT_EXIT": "1"})
        self.assertStopped(done, "a auditoria do APK acusou algo", compiled=True)

    def test_a_failed_build_stops_and_no_old_apk_is_taken(self):
        old = self.r.repo / "app" / "build" / "outputs" / "apk" / "release"
        old.mkdir(parents=True)
        (old / "app-release.apk").write_bytes(b"velho")
        done = self.r.run("0.3.1", env={"FAKE_GRADLE_FAILS": "1"})
        self.assertStopped(done, "o Gradle falhou", compiled=True)


class ReleaseRunTest(ReleaseCase):
    def test_it_builds_checks_copies_and_sums_without_publishing(self):
        done = self.r.run("0.3.1")
        self.assertEqual(0, done.returncode, done.stdout + done.stderr)
        self.assertEqual(["gradlew :app:assembleRelease"], [c for c in self.r.calls() if c.startswith("gradlew")])
        out = self.r.repo / "build" / "release-0.3.1"
        self.assertEqual(["REau-0.3.1.apk", "SHA256SUMS"], sorted(p.name for p in out.iterdir()))
        self.assertEqual(b"APK de mentira", (out / "REau-0.3.1.apk").read_bytes())
        checked = subprocess.run(["sha256sum", "-c", "SHA256SUMS"], cwd=out, capture_output=True, text=True)
        self.assertEqual(0, checked.returncode, checked.stderr)
        self.assertFalse(self.r.called("gh"))
        self.assertIn("gh release create v0.3.1 --draft --verify-tag --title REau\\ 0.3.1 --notes-file NOTAS.md", done.stdout)
        self.assertIn(self.r.git("rev-parse", "HEAD"), done.stdout)
        self.assertIn(OFFICIAL, done.stdout)
        self.assertIn("sha256sum -c", done.stdout)
        self.assertEqual("", self.r.git("status", "--porcelain"))
        for secret in SECRETS.values():
            self.assertNotIn(secret, done.stdout + done.stderr)

    def test_the_output_folder_can_be_chosen(self):
        out = self.r.tmp / "minha-saida"
        done = self.r.run("0.3.1", "--saida", str(out))
        self.assertEqual(0, done.returncode, done.stderr)
        self.assertTrue((out / "REau-0.3.1.apk").is_file())
        self.assertFalse((self.r.repo / "build").exists())

    def test_publishing_creates_a_draft_of_the_tag_with_the_two_files(self):
        done = self.r.run("0.3.1", "--publicar", "--notas", str(self.r.notes))
        self.assertEqual(0, done.returncode, done.stdout + done.stderr)
        out = self.r.repo / "build" / "release-0.3.1"
        self.assertEqual(
            [f"gh release create v0.3.1 --draft --verify-tag --title REau 0.3.1 --notes-file {self.r.notes} {out}/REau-0.3.1.apk {out}/SHA256SUMS"],
            [c for c in self.r.calls() if c.startswith("gh")])

    def test_only_check_runs_the_checks_on_a_built_apk_and_does_not_build_copy_or_send(self):
        done = self.r.run("0.3.1", "--so-conferir", "--apk", str(self.r.apk))
        self.assertEqual(0, done.returncode, done.stdout + done.stderr)
        self.assertFalse(self.r.called("gradlew"))
        self.assertFalse(self.r.called("gh"))
        self.assertTrue(self.r.called("apksigner"))
        self.assertTrue(self.r.called(f"audit {self.r.apk}"))
        self.assertFalse((self.r.repo / "build").exists())

    def test_only_check_still_does_the_tree_tag_version_and_key_checks(self):
        (self.r.repo / "sobra.txt").write_text("x", encoding="utf-8")
        self.assertEqual(1, self.r.run("0.3.1", "--so-conferir", "--apk", str(self.r.apk)).returncode)
        (self.r.repo / "sobra.txt").unlink()
        done = self.r.run("0.3.1", "--so-conferir", "--apk", str(self.r.apk), remove=("REAU_KEYSTORE_PASSWORD",))
        self.assertEqual(1, done.returncode)
        self.assertIn("REAU_KEYSTORE_PASSWORD", done.stderr)
        self.assertFalse(self.r.called("apksigner"))

    def test_only_check_with_another_certificate_stops(self):
        fake_apksigner(self.r.sdk / "build-tools" / "36.1.0" / "apksigner", OTHER)
        done = self.r.run("0.3.1", "--so-conferir", "--apk", str(self.r.apk))
        self.assertEqual(1, done.returncode)
        self.assertIn("não é o do projeto", done.stderr)


class DryRunTest(ReleaseCase):
    """--ensaio (and --dry-run): the whole path, with the checks and the build for real and nothing that changes the repository, the output
    folder or GitHub. git and gh on the PATH are guards that log every call and fail on anything that changes something."""

    def setUp(self):
        super().setUp()
        self.r.guard_git()
        # The dry run builds with the debug key, so its APK carries another certificate than the project's (the official one would stop it, below).
        fake_apksigner(self.r.sdk / "build-tools" / "36.1.0" / "apksigner", OTHER)
        self.before = (self.r.git("rev-parse", "HEAD"), self.r.git("tag", "--list"), self.r.git("status", "--porcelain"))

    def assertNothingChanged(self, done):
        self.assertFalse(any("PROIBIDO" in c for c in self.r.calls()), self.r.calls())
        self.assertFalse(self.r.called("gh"), self.r.calls())
        self.assertFalse((self.r.repo / "build").exists())
        self.assertFalse(self.r.called("git commit") or self.r.called("git push") or self.r.called("git merge"))
        self.assertEqual(self.before, (self.r.git("rev-parse", "HEAD"), self.r.git("tag", "--list"), self.r.git("status", "--porcelain")))
        for secret in SECRETS.values():
            self.assertNotIn(secret, done.stdout + done.stderr)

    def test_it_runs_the_checks_and_the_build_but_only_prints_what_changes_things(self):
        done = self.r.run("0.3.1", "--ensaio")
        self.assertEqual(0, done.returncode, done.stdout + done.stderr)
        self.assertNothingChanged(done)
        self.assertTrue(self.r.called("git status"))
        self.assertTrue(self.r.called("gradlew"))
        self.assertTrue(self.r.called("apksigner"))
        self.assertTrue(self.r.called("audit"))
        out = self.r.repo / "build" / "release-0.3.1"
        self.assertIn(f"[ensaio] faria: mkdir -p {out}", done.stdout)
        self.assertIn(f"[ensaio] faria: cp -f {self.r.repo}/app/build/outputs/apk/release/app-release.apk {out}/REau-0.3.1.apk", done.stdout)
        self.assertIn(f"[ensaio] faria: (cd {out} && sha256sum REau-0.3.1.apk > SHA256SUMS)", done.stdout)
        self.assertIn("Ensaio terminado: nada foi publicado.", done.stdout)
        self.assertIn("Sem --publicar: nada foi enviado", done.stdout)

    def test_dry_run_is_a_synonym(self):
        done = self.r.run("0.3.1", "--dry-run")
        self.assertEqual(0, done.returncode, done.stdout + done.stderr)
        self.assertIn("Ensaio terminado: nada foi publicado.", done.stdout)
        self.assertNothingChanged(done)

    def test_the_release_key_is_never_given_to_the_build_and_the_debug_key_is_asked_for(self):
        done = self.r.run("0.3.1", "--ensaio")
        self.assertEqual(0, done.returncode, done.stdout + done.stderr)
        self.assertIn("gradlew -PreauDebugSigned=true :app:assembleRelease", self.r.calls())
        self.assertIn("env REAU_KEYSTORE=vazio REAU_KEY_PASSWORD=vazio", self.r.calls())
        self.assertNotIn(str(self.r.keystore), "\n".join(self.r.calls()))

    def test_the_certificate_of_the_debug_key_is_not_a_problem_and_the_official_one_is_named(self):
        fake_apksigner(self.r.sdk / "build-tools" / "36.1.0" / "apksigner", OTHER)
        done = self.r.run("0.3.1", "--ensaio")
        self.assertEqual(0, done.returncode, done.stdout + done.stderr)
        self.assertIn(OTHER, done.stdout)
        self.assertIn(f"exige o certificado do projeto: {OFFICIAL}", done.stdout)

    def test_the_project_certificate_in_a_dry_run_stops_it(self):
        # The release key must never reach the dry run's build: if the APK comes out with the project's certificate, it got there some other way.
        fake_apksigner(self.r.sdk / "build-tools" / "36.1.0" / "apksigner", OFFICIAL)
        done = self.r.run("0.3.1", "--ensaio")
        self.assertEqual(1, done.returncode, done.stdout + done.stderr)
        self.assertIn("saiu assinado com o certificado do projeto", done.stderr)
        self.assertIn("Ensaio interrompido: nada foi publicado.", done.stderr)
        self.assertNothingChanged(done)

    def test_a_description_in_the_callers_environment_is_not_printed(self):
        done = self.r.run("0.3.1", "--ensaio", env={"DESCRICAO": "texto de fora"})
        self.assertEqual(0, done.returncode, done.stdout + done.stderr)
        self.assertNotIn("texto de fora", done.stdout)

    def test_publishing_only_prints_the_gh_command(self):
        done = self.r.run("0.3.1", "--ensaio", "--publicar", "--notas", str(self.r.notes))
        self.assertEqual(0, done.returncode, done.stdout + done.stderr)
        self.assertNothingChanged(done)
        out = self.r.repo / "build" / "release-0.3.1"
        self.assertIn(f"[ensaio] faria: gh release create v0.3.1 --draft --verify-tag --title REau\\ 0.3.1 --notes-file {self.r.notes} "
                      f"{out}/REau-0.3.1.apk {out}/SHA256SUMS", done.stdout)
        self.assertIn("Ensaio terminado: nada foi publicado.", done.stdout)
        self.assertNotIn("rascunho criado", done.stdout)

    def test_the_output_folder_is_not_created_even_when_chosen(self):
        out = self.r.tmp / "minha-saida"
        done = self.r.run("0.3.1", "--ensaio", "--saida", str(out))
        self.assertEqual(0, done.returncode, done.stdout + done.stderr)
        self.assertFalse(out.exists())
        self.assertIn(f"[ensaio] faria: mkdir -p {out}", done.stdout)
        self.assertNothingChanged(done)

    def test_problems_do_not_stop_it_but_are_listed_and_the_exit_is_1(self):
        self.r.git("tag", "-d", "v0.3.1")
        self.before = (self.r.git("rev-parse", "HEAD"), self.r.git("tag", "--list"), self.r.git("status", "--porcelain"))
        (self.r.repo / "sobra.txt").write_text("x", encoding="utf-8")
        self.before = self.before[:2] + (self.r.git("status", "--porcelain"),)
        done = self.r.run("0.3.1", "--ensaio", "--publicar", "--notas", str(self.r.notes), remove=("REAU_KEY_PASSWORD",))
        self.assertEqual(1, done.returncode, done.stdout + done.stderr)
        self.assertNothingChanged(done)
        self.assertIn("não está limpa", done.stderr)
        self.assertIn("a tag v0.3.1 não existe", done.stderr)
        self.assertIn("REAU_KEY_PASSWORD", done.stderr)
        self.assertIn("Pendências", done.stdout)
        self.assertIn("Ensaio terminado: nada foi publicado.", done.stdout)
        self.assertTrue(self.r.called("gradlew"))

    def test_a_failed_build_still_stops_and_says_nothing_was_published(self):
        done = self.r.run("0.3.1", "--ensaio", env={"FAKE_GRADLE_FAILS": "1"})
        self.assertEqual(1, done.returncode)
        self.assertIn("o Gradle falhou", done.stderr)
        self.assertIn("Ensaio interrompido: nada foi publicado.", done.stderr)
        self.assertNothingChanged(done)

    def test_it_does_not_combine_with_only_check_and_the_help_names_it(self):
        done = self.r.run("0.3.1", "--ensaio", "--so-conferir", "--apk", str(self.r.apk))
        self.assertEqual(2, done.returncode)
        self.assertEqual([], self.r.calls())
        helped = self.r.run("--help")
        self.assertEqual(0, helped.returncode)
        self.assertIn("--ensaio", helped.stderr)
        self.assertIn("--dry-run", helped.stderr)

    def test_without_the_dry_run_the_guards_would_have_been_hit(self):
        """Proves the guard works: the real run calls gh, so the test above that finds no gh call means something."""
        fake_apksigner(self.r.sdk / "build-tools" / "36.1.0" / "apksigner", OFFICIAL)  # a real run is signed with the release key
        done = self.r.run("0.3.1", "--publicar", "--notas", str(self.r.notes))
        self.assertEqual(0, done.returncode, done.stdout + done.stderr)
        self.assertTrue(self.r.called("gh release create"))


class ShellcheckTest(unittest.TestCase):
    def test_shellcheck_passes_when_it_is_installed(self):
        done = subprocess.run(["shellcheck", "-S", "warning", str(SCRIPT)], capture_output=True, text=True) if _has("shellcheck") else None
        if done is None:
            self.skipTest("shellcheck is not installed")
        self.assertEqual(0, done.returncode, done.stdout)


def _has(command: str) -> bool:
    return any((Path(p) / command).is_file() for p in os.environ.get("PATH", "").split(os.pathsep))


if __name__ == "__main__":
    unittest.main()
