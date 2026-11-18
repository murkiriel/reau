"""Tests of the two warnings of scripts/coletar_do_celular.sh (item 11c, 12/11): the small logcat buffers (`logcat -g`, read first) and the recordings'
space (the `left of` line of the ride log and the size of what was copied). A fake `adb` stands for the phone; nothing here touches a real one.
Run:  cd scripts && python3 -B -m unittest discover -p "test_coletar_espaco.py"
"""
import os
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "coletar_do_celular.sh"
BUFFERS_PEQUENOS = ("main: ring buffer is 256 KiB (251 KiB consumed, 1 MiB readable), max entry is 5120 B, max payload is 4068 B\n"
                    "system: ring buffer is 256 KiB (244 KiB consumed, 964 KiB readable), max entry is 5120 B, max payload is 4068 B\n"
                    "crash: ring buffer is 256 KiB (0 B consumed, 0 B readable), max entry is 5120 B, max payload is 4068 B\n")
BUFFERS_GRANDES = BUFFERS_PEQUENOS.replace("256 KiB (251", "4 MiB (251").replace("256 KiB (244", "2048 KiB (244")


def fake_adb(pasta: Path, buffers: str, arquivos: Path | None, extra: str = "") -> Path:
    """A fake adb: `logcat -g` answers [buffers]; the `tar c -C files PASTA` of run-as is made from [arquivos]/PASTA (nothing if it is missing)."""
    log = pasta / "adb.log"
    (pasta / "buffers.txt").write_text(buffers, encoding="utf-8")
    adb = pasta / "adb"
    adb.write_text(
        "#!/usr/bin/env bash\n"
        f'echo "$*" >> "{log}"\n'
        'if [ "$1" = devices ]; then printf "List of devices attached\\nABC123\\tdevice\\n"; exit 0; fi\n'
        f'case "$*" in *"logcat -g"*) cat "{pasta / "buffers.txt"}"; exit 0;; esac\n'
        + extra +
        'case "$*" in *"exec-out run-as com.reau.app tar c -C files "*)\n'
        '  for ultimo; do :; done\n'
        f'  if [ -n "{arquivos or ""}" ] && [ -d "{arquivos or "/nao"}/$ultimo" ]; then tar c -C "{arquivos or "/nao"}" "$ultimo"; else exit 1; fi; exit 0;; esac\n'
        "echo fake\n",
        encoding="utf-8",
    )
    adb.chmod(adb.stat().st_mode | stat.S_IXUSR)
    return log


def rodar(buffers=BUFFERS_GRANDES, ride=None, tamanho_kb=0, extra="", **ambiente):
    """Runs the script. [ride]: text of a ride log for diag/; [tamanho_kb]: KB of a video/ file to be copied. Returns (done, out dir, adb log lines)."""
    tmp = tempfile.TemporaryDirectory()
    base = Path(tmp.name)
    arquivos = base / "celular"
    (arquivos / "diag").mkdir(parents=True)
    if ride is not None:
        (arquivos / "diag" / "ride-20261101-100000.log").write_text(ride, encoding="utf-8")
    if tamanho_kb:
        (arquivos / "video").mkdir()
        with open(arquivos / "video" / "reau_x.h264", "wb") as f:
            f.truncate(tamanho_kb * 1024)
    log = fake_adb(base, buffers, arquivos, extra)
    saida = base / "saida"
    env = {**os.environ, "ADB": str(base / "adb"), "REAU_SAIDA": str(saida), **ambiente}
    done = subprocess.run(["bash", str(SCRIPT), "ABC123"], env=env, capture_output=True, text=True)
    comandos = log.read_text(encoding="utf-8").splitlines()
    return done, saida, comandos, tmp


def linha_de_espaco(ocupado_kb, teto_kb=1048576):
    return f"10:00:00.000  + 5ms  [diag] recordings space: 3 files removed (45351 KB), {ocupado_kb} KB left of {teto_kb} KB\n"


class BuffersDoLogcatTest(unittest.TestCase):
    def test_small_main_and_system_buffers_are_warned_at_the_start_and_in_the_info(self):
        done, saida, comandos, tmp = rodar(BUFFERS_PEQUENOS)
        with tmp:
            self.assertEqual(0, done.returncode, done.stderr)
            self.assertIn("o buffer main do logcat é pequeno (256 KiB", done.stderr)
            self.assertIn("o buffer system do logcat é pequeno (256 KiB", done.stderr)
            self.assertNotIn("buffer crash", done.stderr)  # only main and system carry the history that matters
            info = (saida / "INFO.txt").read_text(encoding="utf-8")
            self.assertIn("--- avisos da coleta", info)
            self.assertIn("o buffer main", info)

    def test_big_buffers_are_not_warned(self):
        done, saida, comandos, tmp = rodar(BUFFERS_GRANDES)
        with tmp:
            self.assertNotIn("buffer", done.stderr)
            self.assertNotIn("avisos da coleta", (saida / "INFO.txt").read_text(encoding="utf-8"))

    def test_the_size_is_read_first_once_and_the_file_in_sistema_keeps_it(self):
        done, saida, comandos, tmp = rodar(BUFFERS_PEQUENOS)
        with tmp:
            self.assertEqual(["-s ABC123 logcat -g"], [c for c in comandos if "logcat -g" in c])
            primeiro_logcat = next(k for k, c in enumerate(comandos) if "logcat" in c)
            self.assertIn("logcat -g", comandos[primeiro_logcat])
            self.assertFalse([c for c in comandos[:primeiro_logcat] if "dumpsys" in c or "getprop" in c], "the buffers are read before the other reads")
            self.assertEqual(BUFFERS_PEQUENOS.strip(), (saida / "sistema" / "logcat-tamanho-dos-buffers.txt").read_text(encoding="utf-8").strip())

    def test_a_size_that_cannot_be_read_is_said_and_the_collection_goes_on(self):
        done, saida, comandos, tmp = rodar("logcat: error\n")
        with tmp:
            self.assertEqual(0, done.returncode, done.stderr)
            self.assertIn("não pôde ser lido", done.stderr)
            self.assertTrue((saida / "logcat-sistema.txt").exists())

    def test_the_minimum_can_be_set(self):
        done, saida, comandos, tmp = rodar(BUFFERS_PEQUENOS, LOGCAT_MIN_KIB="128")
        with tmp:
            self.assertNotIn("é pequeno", done.stderr)

    def test_a_buffer_exactly_at_the_minimum_is_not_small_and_one_below_is(self):
        done, _, _, tmp = rodar(BUFFERS_PEQUENOS, LOGCAT_MIN_KIB="256")
        with tmp:
            self.assertNotIn("é pequeno", done.stderr)
        done, _, _, tmp = rodar(BUFFERS_PEQUENOS, LOGCAT_MIN_KIB="257")
        with tmp:
            self.assertIn("o buffer main do logcat é pequeno (256 KiB, abaixo de 257)", done.stderr)

    def test_a_buffer_given_in_mib_or_gib_is_converted_to_kib(self):
        done, _, _, tmp = rodar("main: ring buffer is 1 MiB (1 KiB consumed)\nsystem: ring buffer is 1 GiB (1 KiB consumed)\n")
        with tmp:
            self.assertNotIn("buffer main", done.stderr)  # 1 MiB = 1024 KiB is not below 1024
            self.assertNotIn("buffer system", done.stderr)

    def test_a_line_of_main_without_the_size_is_told_with_a_question_mark(self):
        done, saida, comandos, tmp = rodar("main: weird format\nsystem: ring buffer is 4 MiB (1 KiB consumed)\n")
        with tmp:
            self.assertIn("o buffer main do logcat é pequeno (? KiB", done.stderr)
            self.assertNotIn("buffer system", done.stderr)


class EspacoDasGravacoesTest(unittest.TestCase):
    def test_the_left_of_line_at_or_above_800_mb_warns_with_the_free_space(self):
        done, saida, comandos, tmp = rodar(ride=linha_de_espaco(1027431))
        with tmp:
            self.assertIn("gravações em 1003 MB de 1024 MB (livre 20 MB", done.stderr)
            info = (saida / "INFO.txt").read_text(encoding="utf-8")
            self.assertIn("1027431 KB left of 1048576 KB", info)
            self.assertIn("avisos da coleta", info)

    def test_below_800_mb_there_is_no_warning(self):
        done, saida, comandos, tmp = rodar(ride=linha_de_espaco(700 * 1024))
        with tmp:
            self.assertNotIn("gravações em", done.stderr)

    def test_the_limit_is_exactly_800_mb_and_can_be_set(self):
        done, _, _, tmp = rodar(ride=linha_de_espaco(800 * 1024))
        with tmp:
            self.assertIn("gravações em 800 MB", done.stderr)
        done, _, _, tmp = rodar(ride=linha_de_espaco(800 * 1024 - 1))
        with tmp:
            self.assertNotIn("gravações em", done.stderr)
        done, _, _, tmp = rodar(ride=linha_de_espaco(500 * 1024), REAU_AVISO_MB="400")
        with tmp:
            self.assertIn("gravações em 500 MB", done.stderr)

    def test_both_the_line_and_the_copy_over_the_limit_make_one_warning_only(self):
        done, _, _, tmp = rodar(ride=linha_de_espaco(900 * 1024), tamanho_kb=900 * 1024)
        with tmp:
            self.assertEqual(1, done.stderr.count("gravações em"))

    def test_below_eighty_percent_of_the_default_there_is_no_warning_even_with_a_big_copy(self):
        done, _, _, tmp = rodar(ride=linha_de_espaco(799 * 1024), tamanho_kb=799 * 1024)
        with tmp:
            self.assertNotIn("gravações em", done.stderr)

    def test_only_the_last_line_counts(self):
        antigo = linha_de_espaco(1000 * 1024)
        done, _, _, tmp = rodar(ride=antigo + linha_de_espaco(300 * 1024))
        with tmp:
            self.assertNotIn("gravações em", done.stderr)

    def test_the_sessions_share_line_is_not_the_total_even_when_it_is_the_last(self):
        cota = "10:05:00.000  + 5ms  [diag] recordings space: sessions over their share: 2 files removed (230400 KB), 524288 KB of sessions left of 524288 KB\n"
        done, saida, _, tmp = rodar(ride=linha_de_espaco(900 * 1024) + cota)
        with tmp:
            self.assertIn("gravações em 900 MB de 1024 MB", done.stderr)
            info = (saida / "INFO.txt").read_text(encoding="utf-8")
            self.assertIn("3 files removed (45351 KB), 921600 KB left of 1048576 KB", info)
            self.assertNotIn("sessions over their share", info)

    def test_a_share_line_alone_says_there_is_no_total_line(self):
        cota = "10:05:00.000  + 5ms  [diag] recordings space: sessions over their share: 2 files removed (230400 KB), 900000 KB of sessions left of 524288 KB\n"
        done, saida, _, tmp = rodar(ride=cota)
        with tmp:
            self.assertNotIn("gravações em", done.stderr)
            self.assertIn("(nenhuma", (saida / "INFO.txt").read_text(encoding="utf-8"))

    def test_a_cap_other_than_1024_mb_is_taken_from_the_line(self):
        done, _, _, tmp = rodar(ride=linha_de_espaco(900 * 1024, 2 * 1048576))
        with tmp:
            self.assertIn("gravações em 900 MB de 2048 MB (livre 1148 MB", done.stderr)

    def test_without_a_line_the_size_of_the_copy_decides(self):
        done, saida, comandos, tmp = rodar(ride="10:00:00.000  [diag] nada\n", tamanho_kb=900 * 1024)
        with tmp:
            self.assertIn("gravações em 900 MB de 1024 MB", done.stderr)
            self.assertIn("nenhuma", (saida / "INFO.txt").read_text(encoding="utf-8"))
        done, saida, comandos, tmp = rodar(ride="10:00:00.000  [diag] nada\n", tamanho_kb=10)
        with tmp:
            self.assertNotIn("gravações em", done.stderr)

    def test_a_phone_with_nothing_to_copy_is_not_warned_and_the_script_ends_well(self):
        done, saida, comandos, tmp = rodar(ride=None)
        with tmp:
            self.assertEqual(0, done.returncode, done.stderr)
            self.assertNotIn("gravações em", done.stderr)

    def test_the_new_warnings_send_the_phone_nothing_but_reads(self):
        done, saida, comandos, tmp = rodar(BUFFERS_PEQUENOS, ride=linha_de_espaco(1027431))
        with tmp:
            for c in comandos:
                self.assertNotIn(" -G ", f" {c} ")
                self.assertNotIn("logcat -c", c)
                self.assertTrue(c.removeprefix("-s ABC123 ").startswith(("devices", "shell getprop", "shell dumpsys", "shell cmd appops get", "shell am get-standby-bucket",
                                                                       "shell settings get", "logcat", "exec-out run-as", "shell ps -A -o")), c)

class CopiaVenceALinhaAntigaTest(unittest.TestCase):
    """A2 (17/11/2026): a `left of` line is a photo of the moment the app deleted something; the one of 14/11 said 974 MB where the copy, made days later, had 806 MB."""

    def test_the_copy_size_decides_and_the_stale_line_does_not_inflate_the_warning(self):
        done, saida, _, tmp = rodar(ride=linha_de_espaco(974 * 1024), tamanho_kb=806 * 1024)
        with tmp:
            # `du` adds a few KB of directory blocks, so the free space is 217 or 218 MB depending on the file system
            self.assertRegex(done.stderr, r"gravações em 806 MB de 1024 MB \(livre 21[78] MB, 79 % ocupado, pelo tamanho de sessions/ \+ video/ copiados;")
            self.assertNotIn("974", done.stderr)
            info = (saida / "INFO.txt").read_text(encoding="utf-8")
            self.assertRegex(info, r"sessions/ \+ video/ copiados: 8253\d\d KB \(79 % do teto de 1048576 KB\)")
            self.assertIn(f"{974 * 1024} KB left of 1048576 KB", info)  # the line itself is still told in the INFO, as a record

    def test_a_stale_line_over_the_limit_does_not_warn_when_the_copy_is_small(self):
        done, _, _, tmp = rodar(ride=linha_de_espaco(974 * 1024), tamanho_kb=300 * 1024)
        with tmp:
            self.assertNotIn("gravações em", done.stderr)

    def test_a_small_line_does_not_hide_a_big_copy(self):
        done, _, _, tmp = rodar(ride=linha_de_espaco(100 * 1024), tamanho_kb=900 * 1024)
        with tmp:
            self.assertIn("gravações em 900 MB de 1024 MB (livre 12", done.stderr)  # 123 or 124 MB: `du` counts the directory blocks
            self.assertIn("88 % ocupado, pelo tamanho", done.stderr)

    def test_only_when_nothing_was_copied_the_line_decides_and_says_so(self):
        done, _, _, tmp = rodar(ride=linha_de_espaco(974 * 1024))
        with tmp:
            self.assertIn("gravações em 974 MB de 1024 MB (livre 50 MB, 95 % ocupado, pela última linha 'recordings space' dos registros (nada foi copiado);", done.stderr)

    def test_the_percentage_uses_the_cap_of_the_line_when_there_is_one(self):
        done, _, _, tmp = rodar(ride=linha_de_espaco(100, 2 * 1048576), tamanho_kb=900 * 1024)
        with tmp:
            self.assertRegex(done.stderr, r"gravações em 900 MB de 2048 MB \(livre 114[78] MB, 44 % ocupado")


# A9 (17/11/2026): what the INFO.txt says of the logcat buffers and of the Android Auto process that projects.
DUMP_PROCESSOS = (
    "  *APP* UID 10101 ProcessRecord{abc1 777:com.exemplo.outro/u0a101}\n"
    "    curAdj=900 setAdj=900 frozen=false\n"
    "  *APP* UID 10123 ProcessRecord{abc2 4242:com.google.android.projection.gearhead:projection/u0a123}\n"
    "    startTime=-12m3s curAdj=920 procState=17 frozen=true\n"
    "    lastActivityTime=-9m1s importance=cached\n"
    "  *APP* UID 10102 ProcessRecord{abc3 888:com.exemplo.terceiro/u0a102}\n"
    "    curAdj=100 frozen=true\n")
RESPOSTAS_A9 = (
    'case "$*" in *"logcat -b main -d -v threadtime -m"*) printf -- "--------- beginning of main\\n11-16 22:06:01.123  1  1 I Etiqueta: texto privado do aparelho\\n"; exit 0;; esac\n'
    'case "$*" in *"shell ps -A -o PID,STAT,ETIME,NAME"*) printf "PID STAT ELAPSED NAME\\n 777 S 01:02 com.exemplo.outro\\n 4242 S 12:03 com.google.android.projection.gearhead:projection\\n"; exit 0;; esac\n'
    f'case "$*" in *"shell dumpsys activity processes"*) printf "%s" "{DUMP_PROCESSOS}"; exit 0;; esac\n')


class InfoDoLogcatEDoAndroidAutoTest(unittest.TestCase):
    def info(self, **kw):
        done, saida, comandos, tmp = rodar(extra=RESPOSTAS_A9, **kw)
        with tmp:
            return done, (saida / "INFO.txt").read_text(encoding="utf-8"), comandos

    def test_each_buffer_says_its_size_and_the_time_of_its_first_line_and_nothing_else_of_the_line(self):
        done, info, _ = self.info(buffers=BUFFERS_PEQUENOS)
        self.assertEqual(0, done.returncode, done.stderr)
        self.assertIn("--- história de cada buffer do logcat", info)
        self.assertIn("main: 256 KiB; primeira linha 11-16 22:06:01.123", info)
        self.assertNotIn("texto privado", info)
        self.assertNotIn("Etiqueta", info)

    def test_a_buffer_with_no_line_or_no_size_is_told_as_not_read(self):
        _, info, _ = self.info(buffers="logcat: error\n")
        self.assertIn("system: tamanho não lido; primeira linha não lida", info)
        self.assertIn("crash: tamanho não lido; primeira linha não lida", info)

    def test_the_projection_process_gives_pid_state_and_age(self):
        _, info, _ = self.info()
        self.assertIn("--- o processo do Android Auto que projeta", info)
        self.assertIn("4242 S 12:03 com.google.android.projection.gearhead:projection", info)
        self.assertNotIn("com.exemplo.outro\n", info.split("PID STAT")[1].split("---")[0])

    def test_only_the_projection_process_block_of_the_dump_is_kept(self):
        _, info, _ = self.info()
        for esperado in ("ProcessRecord{abc2 4242:com.google.android.projection.gearhead:projection/u0a123}", "frozen=true", "importance=cached", "startTime=-12m3s"):
            self.assertIn(esperado, info)
        self.assertNotIn("abc1", info)
        self.assertNotIn("abc3", info)
        self.assertEqual(1, info.count("frozen=true"))  # the third process is also frozen: it must not come in

    def test_without_the_process_it_says_so_and_the_collection_goes_on(self):
        done, saida, _, tmp = rodar()
        with tmp:
            self.assertEqual(0, done.returncode, done.stderr)
            self.assertIn("(o processo :projection não existe agora)", (saida / "INFO.txt").read_text(encoding="utf-8"))

    def test_the_new_reads_are_reads_only(self):
        _, _, comandos = self.info()
        novos = [c for c in comandos if "-m 3" in c or "ps -A" in c or "activity processes" in c]
        self.assertEqual(5, len(novos), novos)
        for c in novos:
            self.assertNotIn(" -G ", f" {c} ")
            self.assertNotIn("logcat -c", c)
            self.assertNotIn(" kill", c)
            self.assertNotIn("force-stop", c)


if __name__ == "__main__":
    unittest.main()
