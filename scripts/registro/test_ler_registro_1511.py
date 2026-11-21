"""Tests of the reader's changes of 15/11/2026: the fall of the Android Auto projection process told apart by what the REau did just before it, and the notice that
the collection's INFO.txt shows Android Auto with its battery restricted. Synthetic logs only."""
import io
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ler_registro  # noqa: E402
import logcat_sistema as ls  # noqa: E402
from test_ler_registro import linha, projecao  # noqa: E402

DISCAR = "[AA] AaReceiver: connected to the head unit server on :5277, starting the handshake"
BYEBYE = "[AA] AapTransport stopping and sending byebye"


def queda(hora, processo="com.google.android.projection.gearhead:projection", mensagem="java.lang.RuntimeException: Failed to start connection", pid=900):
    """A `FATAL EXCEPTION` of the logcat of the system, as the phone writes it (10-04, `-v threadtime`)."""
    base = f"10-04 {hora} {pid} {pid + 1} E AndroidRuntime: "
    return (f"{base}FATAL EXCEPTION: Thread-2\n{base}Process: {processo}, PID: {pid}\n{base}{mensagem}\n{base}Caused by: android.os.DeadObjectException\n")


def pasta(registro, logcat, info=None):
    raiz = Path(tempfile.mkdtemp())
    (raiz / "ride-20261004-100000.log").write_text(registro, encoding="utf-8")
    (raiz / "logcat-sistema.txt").write_text(logcat, encoding="utf-8")
    if info is not None:
        (raiz / "INFO.txt").write_text(info, encoding="utf-8")
    return raiz


class QuedaDoAndroidAuto(unittest.TestCase):
    """14/11 19:47:55 the REau dialled the frozen Android Auto and its projection process fell 4,3 s later: the REau's trigger. 13/11 04:15 it fell with no REau dialling
    and no projection: not the REau's, information only."""

    def achados(self, registro, logcat):
        raiz = pasta(registro, logcat)
        return ler_registro.do_sistema([raiz], ler_registro.ler([raiz]))

    def test_a_fall_up_to_sixty_seconds_after_a_dial_of_the_reau_is_the_reaus_trigger_and_says_how_long(self):
        registro = projecao(linha("10:05:00.000", "aa", DISCAR))
        [achado] = self.achados(registro, queda("10:05:04.300"))
        self.assertEqual("10:05:04: o Android Auto (gearhead:projection) travou — java.lang.RuntimeException (4,3 s depois do discar do REau)", achado)
        self.assertNotIn(ls.NAO_E_DO_REAU, achado)

    def test_a_fall_with_no_dial_and_no_byebye_in_the_sixty_seconds_before_is_not_the_reaus(self):
        registro = projecao(linha("10:05:00.000", "aa", DISCAR))
        [achado] = self.achados(registro, queda("10:06:30.000"))
        self.assertIn("(sem byebye do REau nos 60 s antes)", achado)
        self.assertIn("sem discar do REau nos 60 s antes", achado)
        self.assertIn("congelado, morto ou sem projeção", achado)
        self.assertTrue(achado.endswith(f"{ls.NAO_E_DO_REAU})"), achado)

    def test_the_dial_exactly_sixty_seconds_before_counts_and_a_millisecond_more_does_not(self):
        registro = projecao(linha("10:05:00.000", "aa", DISCAR))
        self.assertIn("(60,0 s depois do discar do REau)", self.achados(registro, queda("10:06:00.000"))[0])
        self.assertIn(ls.NAO_E_DO_REAU, self.achados(registro, queda("10:06:00.001"))[0])

    def test_a_fall_before_the_dial_is_not_after_it(self):
        registro = projecao(linha("10:05:00.000", "aa", DISCAR))
        self.assertIn(ls.NAO_E_DO_REAU, self.achados(registro, queda("10:04:59.000"))[0])

    def test_no_dial_in_the_registro_at_all_is_a_fall_that_is_not_the_reaus(self):
        self.assertIn(ls.NAO_E_DO_REAU, self.achados(projecao(linha("10:05:00.000", "aa", "[AA] AaReceiver: stopped")), queda("10:05:04.000"))[0])

    def test_a_fall_after_a_byebye_is_still_the_reaus_as_before(self):
        registro = projecao(linha("10:05:00.000", "aa", BYEBYE))
        [achado] = self.achados(registro, queda("10:05:01.200"))
        self.assertIn("(1,2 s depois do byebye do REau)", achado)
        self.assertNotIn(ls.NAO_E_DO_REAU, achado)

    def test_the_closer_of_the_dial_and_the_byebye_is_the_one_told(self):
        registro = projecao(linha("10:05:00.000", "aa", DISCAR), linha("10:05:03.000", "aa", BYEBYE))
        self.assertIn("(1,0 s depois do byebye do REau)", self.achados(registro, queda("10:05:04.000"))[0])
        registro = projecao(linha("10:05:03.000", "aa", BYEBYE), linha("10:05:03.500", "aa", DISCAR))
        self.assertIn("(0,5 s depois do discar do REau)", self.achados(registro, queda("10:05:04.000"))[0])

    def test_any_fatal_exception_of_the_projection_process_is_told_not_only_the_failed_connection(self):
        registro = projecao(linha("10:05:00.000", "aa", DISCAR))
        [achado] = self.achados(registro, queda("10:05:02.000", mensagem="java.lang.IllegalStateException: boom"))
        self.assertIn("IllegalStateException", achado)
        self.assertIn("(2,0 s depois do discar do REau)", achado)

    def test_the_other_processes_of_android_auto_and_of_other_apps_are_told_the_same_way(self):
        registro = projecao(linha("10:05:00.000", "aa", DISCAR))
        self.assertIn("(gearhead:car)", self.achados(registro, queda("10:05:02.000", processo="com.google.android.projection.gearhead:car"))[0])
        self.assertEqual([], self.achados(registro, queda("10:05:02.000", processo="com.exemplo.outro")))

    def test_the_message_of_the_exception_is_still_never_told(self):
        registro = projecao(linha("10:05:00.000", "aa", DISCAR))
        [achado] = self.achados(registro, queda("10:05:02.000", mensagem="java.lang.RuntimeException: account owner@example.invalid HomeWifi"))
        self.assertNotIn("owner@example.invalid", achado)
        self.assertNotIn("HomeWifi", achado)

    def test_the_function_of_the_logcat_module_without_dials_behaves_as_before(self):
        entradas = ls.ler([pasta(projecao(), queda("10:05:01.200"))])
        self.assertEqual(["10:05:01: o Android Auto (gearhead:projection) travou — java.lang.RuntimeException (1,2 s depois do byebye do REau)"],
                         ls.travadas_do_android_auto(entradas, [datetime(2026, 10, 4, 10, 5, 0)], 2026))


class NaLeitura(unittest.TestCase):
    """Where the lines go: the REau's trigger in PROBLEMAS (and the exit code), the other in the notices of the reading."""

    def roda(self, registro, logcat, info=None):
        raiz = pasta(registro, logcat, info)
        saida = io.StringIO()
        with redirect_stdout(saida):
            codigo = ler_registro.main([str(raiz), "--tudo"])
        return codigo, saida.getvalue()

    def secoes(self, texto):
        problemas = texto.split("PROBLEMAS:")[1].split("\n\n")[0] if "PROBLEMAS:" in texto else ""
        avisos = texto.split("AVISOS DA LEITURA")[1] if "AVISOS DA LEITURA" in texto else ""
        return problemas, avisos

    def test_the_trigger_of_the_reau_is_a_problem_and_fails_the_exit_code(self):
        codigo, texto = self.roda(projecao(linha("10:05:00.000", "aa", DISCAR)), queda("10:05:04.300"))
        problemas, avisos = self.secoes(texto)
        self.assertEqual(1, codigo)
        self.assertIn("depois do discar do REau", problemas)
        self.assertNotIn("travou", avisos)

    def test_the_fall_that_is_not_the_reaus_is_a_notice_and_not_a_problem(self):
        codigo, texto = self.roda(projecao(linha("10:05:00.000", "aa", DISCAR)), queda("10:30:00.000"))
        problemas, avisos = self.secoes(texto)
        self.assertNotIn("gearhead", problemas)
        self.assertIn("o Android Auto (gearhead:projection) travou", avisos)
        self.assertIn("não é do REau", avisos)
        self.assertEqual(0, codigo, texto)


class BateriaRestrita(unittest.TestCase):
    INFO = ("# coletado por adb (somente leitura) em dom 15 nov 2026 08:49:05 -03; aparelho 0000000000\n"
            "--- com.reau.app\n    versionCode=29912077 minSdk=29 targetSdk=36\n"
            "--- com.google.android.projection.gearhead\n    versionCode=180664274 minSdk=32 targetSdk=37\n"
            "--- o Android Auto em segundo plano\n{appops}\n10\nuser,com.reau.app,10492\n"
            "--- economia de bateria e bateria\nlow_power=0\n")
    AVISO = "o Android Auto está com a bateria restrita: o Android o congela entre as sessões"

    def avisos(self, appops, **k):
        raiz = pasta(projecao(), "", self.INFO.format(appops=appops))
        return ler_registro.avisos_da_leitura([raiz], ler_registro.ler([raiz]), **k)

    def test_run_any_in_background_ignore_in_the_section_of_android_auto_is_told(self):
        self.assertIn(self.AVISO, self.avisos("RUN_ANY_IN_BACKGROUND: ignore"))

    def test_the_mode_comes_with_what_the_phone_adds_after_it(self):
        self.assertIn(self.AVISO, self.avisos("RUN_ANY_IN_BACKGROUND: ignore; rejectTime=+1h2m3s400ms ago"))

    def test_allow_or_no_operations_is_not_told(self):
        self.assertNotIn(self.AVISO, self.avisos("RUN_ANY_IN_BACKGROUND: allow"))
        self.assertNotIn(self.AVISO, self.avisos("No operations."))

    def test_the_line_only_counts_in_the_section_of_android_auto(self):
        info = self.INFO.format(appops="RUN_ANY_IN_BACKGROUND: allow").replace("--- economia de bateria e bateria\n", "--- economia de bateria e bateria\nRUN_ANY_IN_BACKGROUND: ignore\n")
        raiz = pasta(projecao(), "", info)
        self.assertNotIn(self.AVISO, ler_registro.avisos_da_leitura([raiz], ler_registro.ler([raiz])))

    def test_it_is_told_once_whatever_the_collections(self):
        avisos = self.avisos("RUN_ANY_IN_BACKGROUND: ignore")
        self.assertEqual(1, avisos.count(self.AVISO))

    def test_without_an_info_file_nothing_is_told(self):
        raiz = pasta(projecao(), "")
        self.assertNotIn(self.AVISO, ler_registro.avisos_da_leitura([raiz], ler_registro.ler([raiz])))

    def test_the_info_file_in_the_folder_above_the_diag_folder_is_found(self):
        raiz = pasta(projecao(), "", self.INFO.format(appops="RUN_ANY_IN_BACKGROUND: ignore"))
        diag = raiz / "diag"
        diag.mkdir()
        (diag / "ride-20261004-100000.log").write_text(projecao(), encoding="utf-8")
        self.assertIn(self.AVISO, ler_registro.avisos_da_leitura([diag], ler_registro.ler([diag])))

    def test_it_comes_out_in_the_notices_of_the_reading(self):
        raiz = pasta(projecao(), "", self.INFO.format(appops="RUN_ANY_IN_BACKGROUND: ignore"))
        saida = io.StringIO()
        with redirect_stdout(saida):
            ler_registro.main([str(raiz), "--tudo"])
        self.assertIn(self.AVISO, saida.getvalue().split("AVISOS DA LEITURA")[1])


if __name__ == "__main__":
    unittest.main()
