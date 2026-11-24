"""A8 of the analysis of the runs of 21 and 22/11/2026: two defects of the reader, with synthetic logs only (no network name, address, coordinate or name of a person).

1. A projection left open by the end of the log (the rider switched the log off, or the app died) was kept open through the next files until the next start: 7 288 s for ~5 min.
   It now ends at the last event it knows (the line that switched the log off, or the one before the app started again) and says so on the timeline.
2. Two falls of the same signature were told apart by what the log still had to show afterwards: the dash that came back 276 s later was "Wi-Fi do painel reiniciou" (a problem) and the
   one that did not (the rider stopped the projection 13 s later) was the end of the ride. The criterion is now the signature (see `CRITÉRIO DA QUEDA DO PAINEL` in ler_registro.py).

    python3 -B -m unittest test_a8_leitor     (from scripts/registro)
"""
import contextlib
import io
import sys
import tempfile
import unittest
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))
import ler_registro  # noqa: E402
from test_fim_de_viagem import FIRME, OSCILANDO, linha, logcat_driver, perda_do_wifi, projecao, vigia  # noqa: E402


def cabecalho(hora="10:00:00.000", motivo="app"):
    return f"{hora}  +     0ms  ==== process start: {motivo} — app 0.3.0-dev+26.11.20-20.14 (29920274, 0783542), Google Pixel, Android 16 (API 36) ====\n"


def pasta_de(*arquivos):
    """Uma coleta com um `ride-…` por texto, em ordem de nome."""
    d = Path(tempfile.mkdtemp())
    for k, texto in enumerate(arquivos):
        (d / f"ride-20261004-{10 + k:02d}0000.log").write_text(texto, encoding="utf-8")
    return d


def lido(pasta):
    linhas = ler_registro.ler([pasta])
    ler_registro.marcar_fim_de_viagem([pasta], linhas)
    return linhas


LIGAR = "10:00:01.000  +  1000ms  ---- projeção ligada: projection ----\n"
DESLIGAR_O_REGISTRO = "10:05:03.000  ==== log switched off: the rider switched it off ====\n"
OUTRA_PROJECAO = "13:30:00.000  +  1000ms  ---- projeção ligada: projection ----\n"


def duracoes(linhas):
    return [round(b[-1].segundos - b[0].segundos) for b in ler_registro.projecoes(linhas)]


class RegistroDesligadoComAProjecaoLigadaTest(unittest.TestCase):
    def primeiro(self):
        return cabecalho() + LIGAR + linha("10:03:00.000", "aa", "[AA] Android Auto conectado") + linha("10:05:00.000", "ui", "setting rideLog: true -> false") + DESLIGAR_O_REGISTRO

    def segundo(self):
        return (cabecalho("10:17:06.000", "the rider switched the log on") + linha("10:17:07.000", "ui", "setting detailedLog: false -> true")
                + linha("12:38:22.000", "ui", "settings in use: density=180") + OUTRA_PROJECAO + linha("13:35:00.000", "aa", "[AA] Android Auto conectado")
                + "13:40:00.000  +  1000ms  ---- projeção desligada: projection ----\n")

    def test_the_projection_ends_on_the_line_that_switched_the_log_off_not_at_the_next_start(self):
        linhas = lido(pasta_de(self.primeiro(), self.segundo()))
        self.assertEqual([302, 600], duracoes(linhas))
        [primeira, _] = ler_registro.projecoes(linhas)
        self.assertTrue(primeira[-1].texto.startswith("==== log switched off"))
        self.assertEqual("registro desligado pelo piloto", primeira.sem_fim)

    def test_what_comes_after_the_switch_off_is_not_the_projections(self):
        linhas = lido(pasta_de(self.primeiro(), self.segundo()))
        [primeira, segunda] = ler_registro.projecoes(linhas)
        self.assertFalse(any("settings in use" in x.texto or "detailedLog" in x.texto for x in primeira))
        self.assertEqual("", segunda.sem_fim)

    def test_the_timeline_says_there_is_no_end_on_record_and_how_long_it_lasted(self):
        tempo = "\n".join(ler_registro.linha_do_tempo(lido(pasta_de(self.primeiro(), self.segundo()))))
        self.assertIn("10:05:03.000  projeção sem fim registrado (registro desligado pelo piloto): vale até o último evento dela, 5 min 02 s depois de ligar", tempo)
        self.assertEqual(1, tempo.count("sem fim registrado"))

    def test_the_by_day_line_counts_the_five_minutes_not_the_gap_to_the_next_start(self):
        [dia] = ler_registro._por_data_e_build(lido(pasta_de(self.primeiro(), self.segundo())))
        self.assertIn("2 projeções, 15 min 02 s ligadas", dia)
        self.assertTrue(dia.endswith(", 1 sem fim registrado"), dia)

    def test_the_reader_alone_on_the_first_file_gives_the_same_duration(self):
        sozinha = lido(pasta_de(self.primeiro()))
        junta = lido(pasta_de(self.primeiro(), self.segundo()))
        self.assertEqual(duracoes(sozinha), duracoes(junta)[:1])

    def test_the_command_line_does_not_count_the_gap_in_the_classifier_minutes(self):
        saida = io.StringIO()
        with contextlib.redirect_stdout(saida):
            ler_registro.main([str(pasta_de(self.primeiro(), self.segundo())), "--tudo"])
        self.assertIn("sem fim registrado", saida.getvalue())
        self.assertNotIn("7288", saida.getvalue())

    def test_the_app_dying_with_the_projection_on_ends_it_on_its_last_line_not_on_the_restart(self):
        morreu = cabecalho() + LIGAR + linha("10:04:00.000", "aa", "[AA] Android Auto conectado")
        voltou = cabecalho("11:00:00.000") + linha("11:00:05.000", "ui", "settings in use: density=180") + OUTRA_PROJECAO
        linhas = lido(pasta_de(morreu, voltou))
        [primeira, segunda] = ler_registro.projecoes(linhas)
        self.assertEqual("10:04:00.000", primeira[-1].hora)
        self.assertEqual("o app recomeçou", primeira.sem_fim)
        self.assertEqual([239, 0], duracoes(linhas))
        self.assertEqual("", segunda.sem_fim)

    def test_a_clean_process_end_closes_it_too(self):
        arquivo = cabecalho() + LIGAR + "10:04:00.000  ==== process end: the app was closed ====\n"
        [bloco] = ler_registro.projecoes(lido(pasta_de(arquivo)))
        self.assertEqual("o app terminou", bloco.sem_fim)

    def test_a_file_that_goes_on_from_the_other_does_not_end_the_projection(self):
        # The same process rolls to a new file at midnight or at the size limit: `continued from` is not an end.
        primeiro = cabecalho() + LIGAR + linha("10:03:00.000", "aa", "[AA] Android Auto conectado")
        segundo = "10:04:00.000  ==== continued from ride-20261004-100000.log ====\n" + linha("10:05:00.000", "aa", "[AA] Android Auto conectado") + "10:09:00.000  +  1000ms  ---- projeção desligada: projection ----\n"
        [bloco] = ler_registro.projecoes(lido(pasta_de(primeiro, segundo)))
        self.assertEqual("", bloco.sem_fim)
        self.assertEqual(539, round(bloco[-1].segundos - bloco[0].segundos))
        self.assertNotIn("sem fim registrado", "\n".join(ler_registro.linha_do_tempo(lido(pasta_de(primeiro, segundo)))))

    def test_two_starts_with_no_stop_between_close_the_first_at_its_last_event(self):
        arquivo = cabecalho() + LIGAR + linha("10:03:00.000", "aa", "[AA] Android Auto conectado") + OUTRA_PROJECAO
        [primeira, segunda] = ler_registro.projecoes(lido(pasta_de(arquivo)))
        self.assertEqual("10:03:00.000", primeira[-1].hora)
        self.assertTrue(primeira.sem_fim)
        self.assertEqual("", segunda.sem_fim)

    def test_the_end_of_the_data_with_the_projection_on_says_nothing(self):
        # The collection may have been made with the projection still on: not a missing end.
        [bloco] = ler_registro.projecoes(lido(pasta_de(cabecalho() + LIGAR + linha("10:03:00.000", "aa", "[AA] Android Auto conectado"))))
        self.assertEqual("", bloco.sem_fim)

    def test_a_projection_with_its_stop_is_untouched(self):
        linhas = lido(pasta_de(projecao(linha("10:03:00.000", "aa", "[AA] Android Auto conectado"))))
        tempo = "\n".join(ler_registro.linha_do_tempo(linhas))
        self.assertNotIn("sem fim registrado", tempo)
        self.assertIn("projeção desligada", tempo)


def firme():
    return linha("10:00:40.000", "link", FIRME)


def volta(hora_unido, idade, hora_idade=None, hora_transmitindo=None):
    """The dash back: the Wi-Fi joined, the heartbeat age of the first packet, the stream again."""
    return (linha(hora_unido, "wifi", "connected 'RE_****34' in 900ms")
            + linha(hora_idade or hora_unido[:-3] + "100", "auth", f"dash heartbeat age {idade} s at the first packet")
            + linha(hora_transmitindo or hora_unido[:-3] + "500", "session", "AUTHENTICATING → STREAMING"))


def classe(linhas):
    """[(hora da queda, é a moto desligada)] de cada queda."""
    return [(g[0].hora[:8], ler_registro._painel_desligou(g, bloco)) for bloco in ler_registro.projecoes(linhas) for g in ler_registro._grupos_de_queda(bloco)]


def religou(linhas):
    return [ler_registro._painel_religou(g, bloco) is not None for bloco in ler_registro.projecoes(linhas) for g in ler_registro._grupos_de_queda(bloco)]


class QuedaDoPainelUmCriterioSoTest(unittest.TestCase):
    """The same signature (a firm Wi-Fi gone at once, the dash silent, the dash's own `reason 3`) is the same reading whether or not the log was still there to see the dash come back."""

    def volta_ligando(self, fim="10:09:00.000", **kw):
        # Fell at 10:01:00; back at 10:02:30 with the age 20 s: the dash powered on 70 s after the fall.
        return pasta_de(projecao(firme(), perda_do_wifi("10:01:00.000"), vigia("10:01:04.200"), volta("10:02:30.000", 20), fim=fim)), kw

    def sem_voltar(self):
        # The rider stopping the projection 13 s later: the log has nothing more to show.
        return pasta_de(projecao(firme(), perda_do_wifi("10:01:00.000"), vigia("10:01:04.200"), fim="10:01:13.000"))

    def test_the_dash_that_came_back_powered_on_again_reads_like_the_one_that_did_not_come_back(self):
        com_volta = lido(self.volta_ligando()[0])
        sem_volta = lido(self.sem_voltar())
        self.assertEqual([("10:01:00", True)], classe(com_volta))
        self.assertEqual(classe(sem_volta), classe(com_volta))
        self.assertEqual([True], religou(com_volta))
        self.assertEqual([False], religou(sem_volta))

    def test_the_same_with_the_system_saying_reason_3_instead_of_the_signal_history(self):
        registro = projecao(linha("10:00:30.000", "link", OSCILANDO), perda_do_wifi("10:01:00.000"), vigia("10:01:04.200"), volta("10:02:30.000", 20))
        sem = lido(pasta_de(registro))
        self.assertEqual([("10:01:00", False)], classe(sem))  # the oscillating history alone is not "gone at once"
        d = pasta_de(registro)
        (d / "logcat-sistema.txt").write_text(logcat_driver("10:00:58.300"), encoding="utf-8")
        self.assertEqual([("10:01:00", True)], classe(lido(d)))
        tempo = "\n".join(ler_registro.linha_do_tempo(lido(d)))
        self.assertIn("painel desligou e religou (moto desligada e ligada de novo?)", tempo)
        self.assertIn("o sistema registrou a saída do painel (reason 3) às 10:00:58", tempo)

    def test_the_timeline_says_it_came_back_and_that_the_age_shows_it_powered_on(self):
        tempo = "\n".join(ler_registro.linha_do_tempo(lido(self.volta_ligando()[0])))
        self.assertIn("painel desligou e religou (moto desligada e ligada de novo?)", tempo)
        self.assertIn("voltou em 90 s", tempo)
        self.assertIn("a idade do painel no primeiro pacote mostra que ele ligou de novo 70 s depois da queda", tempo)
        self.assertNotIn("Wi-Fi do painel reiniciou", tempo)
        self.assertNotIn("a idade é um mínimo", tempo)  # under 256 s the counter has not wrapped

    def test_the_command_line_puts_the_dumpsys_line_in_the_moto_section_and_the_exit_code_is_zero(self):
        registro = projecao(firme(), perda_do_wifi("10:01:00.000"), vigia("10:01:04.200"), volta("10:02:30.000", 20),
                            linha("10:02:40.000", "dash", "dash DECODED first IDR (09 06 55) — video accepted"))
        pasta = pasta_de(registro)
        (pasta / "sistema").mkdir()
        (pasta / "sistema" / "dumpsys-wifi.txt").write_text("StaEventList:\n10-04 10:01:00.050 NETWORK_DISCONNECTION_EVENT local_gen=false reason=3:DEAUTH_LEAVING lastRssi=-54 lastFreq=2452\n",
                                                            encoding="utf-8")
        saida = io.StringIO()
        with contextlib.redirect_stdout(saida):
            codigo = ler_registro.main([str(pasta)])
        texto = saida.getvalue()
        self.assertEqual(0, codigo, texto)
        problemas, _, secao = texto.partition("MOTO DESLIGADA")
        self.assertIn("o Wi-Fi do painel caiu (moto desligada)", secao)
        self.assertNotIn("o Wi-Fi do painel caiu", problemas.rsplit("PROBLEMAS:", 1)[-1])
        self.assertIn("0 quedas, 1 moto desligada e religada", texto)

    def test_the_by_day_line_tells_the_switch_off_and_on_from_the_end_of_the_ride_and_from_a_fall(self):
        [dia] = ler_registro._por_data_e_build(lido(self.volta_ligando()[0]))
        self.assertTrue(dia.endswith("0 quedas, 1 moto desligada e religada"), dia)
        [dia] = ler_registro._por_data_e_build(lido(self.sem_voltar()))
        self.assertTrue(dia.endswith("0 quedas, 1 fim de viagem"), dia)

    def test_the_dash_that_stayed_on_the_whole_time_is_still_a_wifi_that_fell_and_came_back(self):
        # Age 120 s at 91 s after the fall: it was on before the fall (the counter was 29 s then).
        registro = projecao(firme(), perda_do_wifi("10:01:00.000"), vigia("10:01:04.200"), volta("10:02:30.000", 120))
        linhas = lido(pasta_de(registro))
        self.assertEqual([("10:01:00", False)], classe(linhas))
        self.assertIn("Wi-Fi do painel", "\n".join(ler_registro.linha_do_tempo(linhas)))
        self.assertNotIn("painel desligou e religou", "\n".join(ler_registro.linha_do_tempo(linhas)))

    def test_without_the_age_in_the_log_a_short_return_is_read_as_before(self):
        registro = projecao(firme(), perda_do_wifi("10:01:00.000"), vigia("10:01:04.200"), linha("10:02:30.000", "wifi", "connected 'RE_****34' in 900ms"),
                            linha("10:02:30.500", "session", "AUTHENTICATING → STREAMING"))
        self.assertEqual([("10:01:00", False)], classe(lido(pasta_de(registro))))

    def test_a_weak_signal_before_the_fall_is_not_the_bike(self):
        fraco = linha("10:00:40.000", "link", FIRME.replace("-58", "-88"))
        registro = projecao(fraco, perda_do_wifi("10:01:00.000"), vigia("10:01:04.200"), volta("10:02:30.000", 20))
        self.assertEqual([("10:01:00", False)], classe(lido(pasta_de(registro))))

    def test_the_system_seeing_the_phone_itself_leave_keeps_it_a_fall(self):
        d = self.volta_ligando()[0]
        (d / "logcat-sistema.txt").write_text(logcat_driver("10:00:58.300", motivo=2), encoding="utf-8")
        self.assertEqual([("10:01:00", False)], classe(lido(d)))

    def test_a_long_fall_cannot_be_told_by_the_age_so_it_is_read_as_the_switch_off_and_says_the_age_is_a_minimum(self):
        # 270 s: the 256 s counter wraps, an age of 20 s may be 276 s; the signature still says the dash left.
        registro = projecao(firme(), perda_do_wifi("10:01:00.000"), vigia("10:01:04.200"), volta("10:05:30.000", 20), fim="10:09:00.000")
        linhas = lido(pasta_de(registro))
        self.assertEqual([("10:01:00", True)], classe(linhas))
        tempo = "\n".join(ler_registro.linha_do_tempo(linhas))
        self.assertIn("a queda passou de 256 s, o contador de idade do painel dá a volta e a idade é um mínimo", tempo)

    def test_a_long_fall_without_the_signature_stays_a_fall(self):
        registro = projecao(perda_do_wifi("10:01:00.000"), vigia("10:01:04.200"), volta("10:05:30.000", 20), fim="10:09:00.000")
        self.assertEqual([("10:01:00", False)], classe(lido(pasta_de(registro))))

    def test_a_return_after_the_five_minutes_is_the_old_end_of_the_ride(self):
        registro = projecao(firme(), perda_do_wifi("10:01:00.000"), vigia("10:01:04.200"), volta("10:06:30.000", 20), fim="10:09:00.000")
        linhas = lido(pasta_de(registro))
        self.assertEqual([("10:01:00", True)], classe(linhas))
        self.assertEqual([False], religou(linhas))

    def test_the_image_that_stopped_with_the_bike_is_not_listed_as_a_problem_when_it_came_back(self):
        # `quedas.interrupcoes` and the resumo's `dark=`: the interruption that begins at a switch-off is told as such.
        resumo = linha("10:08:50.000", "session", "resumo duration=8m49s streaming=7m00s drops dash=1 aa=0 retries dash=0 watchdogs=1 (dash-rx=1) frames=100 fps avg=5.0 min=3.0 gap max=0.5s dark=1/1m33s (max 1m33s) thermal max=none battery 80→79% (-1) marks=0")
        registro = projecao(firme(), perda_do_wifi("10:01:00.000"), vigia("10:01:04.200"), volta("10:02:30.000", 20), resumo)
        saida = io.StringIO()
        with contextlib.redirect_stdout(saida):
            ler_registro.main([str(pasta_de(registro))])
        texto = saida.getvalue()
        problemas = texto.split("PROBLEMAS:")[1].split("MOTO DESLIGADA")[0] if "PROBLEMAS:" in texto else ""
        self.assertNotIn("painel sem imagem", problemas)


if __name__ == "__main__":
    unittest.main()
