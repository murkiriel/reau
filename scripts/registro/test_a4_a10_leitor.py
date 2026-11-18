"""The reader's side of the low-priority findings of the rides of 15 to 17/11/2026 (A4, A5, A7). Synthetic logs only: the dash's name is the invented `RE_AB12_345678`, the other
app's package is invented, the MACs are put together while the test runs.

    python3 -B -m unittest test_a4_a10_leitor     (from scripts/registro)
"""
import sys
import tempfile
import unittest
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))
import ler_registro  # noqa: E402
import logcat_sistema  # noqa: E402
import olhadas  # noqa: E402

MAC_AP = ":".join(["aa", "bb", "cc", "dd", "ee", "01"])
CABECALHO = "10:00:00.000  +     0ms  ==== process start: app — Google Pixel, Android 16 (API 36) ====\n"


def linha(hora, etiqueta, texto):
    return f"{hora}  +     0ms  [{etiqueta}] {texto}\n"


def projecao(*meio, fim="10:09:00.000"):
    texto = CABECALHO + "10:00:01.000  +  1000ms  ---- projeção ligada: projection ----\n" + "".join(meio)
    return texto + (f"{fim}  +  1000ms  ---- projeção desligada: projection ----\n" if fim else "")


def escreve(pasta, nome, texto):
    destino = Path(pasta) / nome
    destino.parent.mkdir(parents=True, exist_ok=True)
    destino.write_text(texto, encoding="utf-8")


EXPIROU = linha("10:00:30.000", "wifi", "W: unavailable after 8029ms (mid-session, #1 of this absence) — retrying in 4000ms; seenInScan=true quick=0 joined=false")


def join_falhou(hora):
    return f"10-04 {hora}  2843  2843 E [schedu][0xa89b4c8a698][23:05:11.366305] wlan: [2843:E:PE] lim_process_join_failure_timeout: Join Failure Timeout, In eLIM_MLM_WT_JOIN_BEACON_STATE session:0 {MAC_AP}\n"


# An app other than the REau (uid 10349) had the dash's Wi-Fi from 09:59:10 and let go at 09:59:40 (the registro's own span starts 30 s before its first line, 09:59:30).
DUMPSYS_OUTRO_APP = f"""mConnectionEvents:
startTime=10-4 9:59:10.0, SSID="RE_AB12_345678", BSSID={MAC_AP}, durationMillis=700, connectionResult=1, signalStrength=-50, mChannelInfo=2452, connectionNominator=NOMINATOR_SPECIFIER, uid=10349
uid=10349 name=com.exemplo.outroapp
StaEventList:
10-04 09:59:40.000 NETWORK_DISCONNECTION_EVENT local_gen=true reason=3:DEAUTH_LEAVING lastRssi=-50 lastFreq=2452
"""


class PedidoExpiradoComCausaTest(unittest.TestCase):
    def coleta(self, logcat=None, dumpsys=None):
        raiz = Path(tempfile.mkdtemp())
        escreve(raiz, "diag/ride-20261004-100000.log", projecao(EXPIROU))
        if logcat is not None:
            escreve(raiz, "logcat-sistema.txt", logcat)
        if dumpsys is not None:
            escreve(raiz, "sistema/dumpsys-wifi.txt", dumpsys)
            escreve(raiz, "INFO.txt", "--- pacotes\nuser,com.reau.app,10492\n")  # the REau's uid: the other app's is not it
        return raiz

    def problema(self, raiz):
        linhas = ler_registro.ler([raiz])
        achados = ler_registro.explicar_pedidos_expirados(ler_registro.problemas(linhas), [raiz], linhas)
        [p] = [a for a in achados if "pedido de rede expirou" in a]
        return p

    def test_without_any_system_data_the_problem_is_the_one_of_before(self):
        self.assertEqual("10:00:30.000: pedido de rede expirou com o painel na varredura (seenInScan=true)", self.problema(self.coleta()))

    def test_the_drivers_join_failures_around_the_request_are_told_with_their_count_and_times(self):
        p = self.problema(self.coleta(logcat=join_falhou("10:00:20.100") + join_falhou("10:00:21.200") + join_falhou("10:00:22.300")))
        self.assertTrue(p.startswith("10:00:30.000: pedido de rede expirou com o painel na varredura (seenInScan=true) — "), p)
        self.assertIn("o driver do Wi-Fi registrou 3× Join Failure Timeout entre 10:00:20 e 10:00:22: o painel não respondeu à entrada em tempo", p)
        self.assertNotIn("aa:bb", p)

    def test_the_window_of_the_join_failures_is_15_s_before_and_2_s_after(self):
        for hora, conta in (("10:00:15.000", 1), ("10:00:14.900", 0), ("10:00:32.000", 1), ("10:00:32.100", 0)):
            with self.subTest(hora=hora):
                p = self.problema(self.coleta(logcat=join_falhou(hora)))
                self.assertEqual(bool(conta), "Join Failure Timeout" in p, p)

    def test_another_app_that_had_the_dash_in_the_60_s_before_is_told_with_its_package(self):
        p = self.problema(self.coleta(dumpsys=DUMPSYS_OUTRO_APP))
        self.assertIn("outro app, com.exemplo.outroapp (uid 10349), tinha o Wi-Fi do painel: entrou às 09:59:10 e saiu 50 s antes do pedido", p)
        self.assertNotIn("RE_AB12", p)
        self.assertNotIn(MAC_AP, p)

    def test_an_app_that_let_go_more_than_60_s_before_is_not_told(self):
        antigo = DUMPSYS_OUTRO_APP.replace("09:59:40.000", "09:59:05.000").replace("9:59:10.0", "9:59:00.0")  # let go 85 s before
        self.assertNotIn("outro app", self.problema(self.coleta(dumpsys=antigo)))

    def test_an_app_still_on_the_dash_when_the_request_expired_is_told_so_and_one_that_came_later_is_not(self):
        ainda = DUMPSYS_OUTRO_APP.replace("09:59:40.000", "10:00:40.000")
        self.assertIn("entrou às 09:59:10 e ainda estava nele", self.problema(self.coleta(dumpsys=ainda)))
        depois = DUMPSYS_OUTRO_APP.replace("9:59:10.0", "10:00:35.0").replace("09:59:40.000", "10:00:50.000")
        self.assertNotIn("outro app", self.problema(self.coleta(dumpsys=depois)))

    def test_both_causes_come_together_and_it_stays_a_problem(self):
        raiz = self.coleta(logcat=join_falhou("10:00:25.000"), dumpsys=DUMPSYS_OUTRO_APP)
        linhas = ler_registro.ler([raiz])
        achados = ler_registro.explicar_pedidos_expirados(ler_registro.problemas(linhas), [raiz], linhas)
        [p] = [a for a in achados if "pedido de rede expirou" in a]
        self.assertIn("Join Failure Timeout", p)
        self.assertIn("outro app", p)
        self.assertEqual(len(ler_registro.problemas(linhas)), len(achados))

    def test_the_driver_reader_gives_only_times(self):
        raiz = self.coleta(logcat=join_falhou("10:00:25.000"))
        [t] = logcat_sistema.falhas_de_entrada([raiz], 2026)
        self.assertEqual("2026-10-04 10:00:25", f"{t:%Y-%m-%d %H:%M:%S}")

    def test_the_command_line_prints_the_explained_problem(self):
        import contextlib
        import io
        raiz = self.coleta(logcat=join_falhou("10:00:25.000"))
        saida = io.StringIO()
        with contextlib.redirect_stdout(saida):
            ler_registro.main([str(raiz)])
        self.assertIn("pedido de rede expirou com o painel na varredura (seenInScan=true) — o driver do Wi-Fi registrou 1× Join Failure Timeout", saida.getvalue())


def muitas_incertas(n=40):
    return [linha(f"10:01:{k % 60:02d}.{k:03d}", "picture", "picture look: UNSURE p=0.700 (p under 0.98)") for k in range(n)]


class DensidadeDiferenteNaoAlarmaTest(unittest.TestCase):
    def blocos(self, *meio):
        d = Path(tempfile.mkdtemp())
        (d / "ride-20261004-100000.log").write_text(projecao(*meio, fim="10:02:00.000"), encoding="utf-8")  # 2 min: 40 doubts are 20 a minute, over the limit of 7
        return ler_registro.projecoes(ler_registro.ler([d]))

    def test_the_same_doubt_alarms_with_the_density_of_the_training(self):
        [b] = self.blocos(linha("10:00:02.000", "settings", "settings in use: density=180 marginW=0"), *muitas_incertas())
        self.assertTrue(olhadas.taxa(b)["alarme"])
        self.assertEqual(1, len(olhadas.problemas([b])))
        self.assertIsNone(olhadas.densidade_fora(b))

    def test_a_density_swapped_while_running_takes_the_alarm_away_and_the_text_says_why(self):
        [b] = self.blocos(linha("10:00:02.000", "settings", "settings in use: density=180 marginW=0"), linha("10:00:40.000", "ui", "setting density: 180 -> 170"), *muitas_incertas())
        t = olhadas.taxa(b)
        self.assertFalse(t["alarme"])
        self.assertTrue(t["alarme_pela_taxa"])
        self.assertEqual(170, t["densidade_fora"])
        self.assertEqual([], olhadas.problemas([b]))
        texto = olhadas.texto_da_taxa(t)
        self.assertIn("densidade 170 nesta projeção e o classificador foi treinado a 180", texto)
        self.assertIn("(pela taxa alarmaria)", texto)

    def test_a_projection_that_started_with_another_density_does_not_alarm_either(self):
        [b] = self.blocos(linha("10:00:02.000", "settings", "settings in use: density=170 marginW=0"), *muitas_incertas())
        self.assertEqual([], olhadas.problemas([b]))
        self.assertEqual(170, olhadas.densidade_fora(b))

    def test_swapping_back_to_180_still_counts_as_a_test_projection(self):
        [b] = self.blocos(linha("10:00:02.000", "ui", "setting density: 180 -> 170"), linha("10:00:30.000", "ui", "setting density: 170 -> 180"), *muitas_incertas())
        self.assertEqual([], olhadas.problemas([b]))

    def test_a_swap_to_the_same_180_is_not_a_test(self):
        [b] = self.blocos(linha("10:00:02.000", "ui", "setting density: 170 -> 180"), *muitas_incertas())
        self.assertEqual(1, len(olhadas.problemas([b])))

    def test_the_text_without_the_swap_has_no_density_remark(self):
        [b] = self.blocos(*muitas_incertas())
        self.assertNotIn("densidade", olhadas.texto_da_taxa(olhadas.taxa(b)))

    def test_the_section_still_lists_the_readings_of_a_density_test(self):
        [b] = self.blocos(linha("10:00:40.000", "ui", "setting density: 180 -> 170"), *muitas_incertas(12))
        secao = olhadas.secao([b])
        self.assertTrue(secao and "densidade 170" in secao[0], secao)
        self.assertGreater(len(secao), 1)


class PrimeiroQuadroDoPainelTest(unittest.TestCase):
    def video(self, hora, dash, janela="5.2s"):
        return linha(hora, "stream", f"video fps target=4 sent=2.1 (11frames/{janela}) composed=2.1 dash={dash} kbps=92 set=400k frame=5396B source=5")

    def timeline(self, *meio):
        d = Path(tempfile.mkdtemp())
        (d / "ride-20261004-100000.log").write_text(projecao(*meio), encoding="utf-8")
        return ler_registro.linha_do_tempo(ler_registro.ler([d]))

    def test_the_first_window_with_dash_above_zero_gives_the_range_after_streaming(self):
        tempo = self.timeline(linha("10:00:10.000", "session", "AUTHENTICATING → STREAMING"), self.video("10:00:15.000", "0.0"), self.video("10:00:20.800", "0.4"),
                              self.video("10:00:26.000", "0.4"))
        achadas = [x for x in tempo if "painel confirmou o primeiro quadro" in x]
        self.assertEqual(1, len(achadas), achadas)
        self.assertEqual("10:00:20.800  painel confirmou o primeiro quadro entre 5,6 e 10,8 s depois de transmitir (calculado da janela de 5,2 s com dash=0.4; o app não escreve o instante)", achadas[0])

    def test_the_range_does_not_go_below_zero(self):
        tempo = self.timeline(linha("10:00:10.000", "session", "AUTHENTICATING → STREAMING"), self.video("10:00:12.000", "0.4"))
        self.assertTrue(any("entre 0,0 e 2,0 s" in x for x in tempo), tempo)

    def test_a_dash_that_never_confirms_gives_nothing(self):
        tempo = self.timeline(linha("10:00:10.000", "session", "AUTHENTICATING → STREAMING"), self.video("10:00:15.000", "0.0"))
        self.assertFalse(any("painel confirmou" in x for x in tempo))

    def test_without_streaming_nothing_is_said(self):
        self.assertFalse(any("painel confirmou" in x for x in self.timeline(self.video("10:00:15.000", "0.4"))))

    def test_a_log_that_already_has_the_apps_first_frame_line_is_left_as_it_is(self):
        tempo = self.timeline(linha("10:00:10.000", "session", "AUTHENTICATING → STREAMING"), linha("10:00:19.000", "dash", "dash DECODED first IDR (09 06 55) — video accepted ✓"),
                              self.video("10:00:20.800", "0.4"))
        self.assertFalse(any("painel confirmou o primeiro quadro entre" in x for x in tempo))
        self.assertTrue(any("primeiro quadro confirmado pelo painel" in x for x in tempo))

    def test_a_window_before_streaming_is_not_the_first_frame(self):
        tempo = self.timeline(self.video("10:00:05.000", "0.4"), linha("10:00:10.000", "session", "AUTHENTICATING → STREAMING"), self.video("10:00:15.000", "0.0"))
        self.assertFalse(any("painel confirmou" in x for x in tempo))


if __name__ == "__main__":
    unittest.main()
