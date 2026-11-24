"""The TYPE of each loss of the dash's Wi-Fi, told by what the system's logcat says around it (plan 32). Synthetic logs only: the MACs are invented, no network name.

    A  the dash left: `Deauth RX … reason 3` from the access point (the ignition went off);
    B  the dash vanished from the air: `BEACON MISS` / `BEACON_MISS_FAILURE`, no goodbye before;
    C  the dash stopped answering with the Wi-Fi up: `STA kickout` / `PEER_INACTIVITY`, no `BEACON MISS`;
    D  4-way refused: `reason 15` / `4WAY_HANDSHAKE_TIMEOUT` (the stuck dash of 21/11/2026 07:25);
    ?  there is no system logcat at the time.

The lines are the real ones of the collections of 21/11 and 23/11/2026, with the names and addresses swapped for invented ones.

    python3 -B -m unittest test_tipo_da_perda     (from scripts/registro)
"""
import sys
import tempfile
import unittest
from datetime import datetime
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))
import ler_registro  # noqa: E402
import logcat_sistema  # noqa: E402
from test_fim_de_viagem import FIRME, MAC_AP, MAC_TODOS, coleta, linha, logcat_driver, logcat_wpa, perda_do_wifi, projecao  # noqa: E402

MAC_CELULAR = ":".join(["aa", "bb", "cc", "dd", "ee", "02"])
TIPOS = logcat_sistema


def kickout(hora):
    return f"10-04 {hora}  2829  2829 I [schedu][0x1][13:01:00.000000] wlan: [2829:I:WMA] STA kickout for {MAC_AP}, on mac {MAC_CELULAR}, vdev 0, reason:2\n"


def desconexao_do_driver(hora, motivo, nome, vendor="vendor: 0", local=0):
    return f"10-04 {hora}  2829  2829 I [schedu][0x2][13:01:00.100000] wlan: [2829:I:HDD] Disconnect reason: {motivo} {nome} {vendor}  LG: {local}\n"


def beacon_miss(hora):
    return f"10-04 {hora}  2829  2829 I [schedu][0x3][13:01:00.284357] wlan: [2829:I:WMA] [13:01:00.226000] [ROAM_TRIGGER]: VDEV[0] Reason: \"BEACON MISS\" Sub-Reason: FULL SCAN\n"


def beacon_miss_falha(hora):
    return desconexao_do_driver(hora, 0, "Unknown", "vendor: 14 QCA_DISCONNECT_REASON_BEACON_MISS_FAILURE", 1)


def sem_resposta(hora):
    return desconexao_do_driver(hora, 1, "WLAN_REASON_UNSPECIFIED", "vendor: 12 QCA_DISCONNECT_REASON_PEER_INACTIVITY", 1)


def quatro_vias(hora):
    return (logcat_driver(hora, 15)
            + desconexao_do_driver(hora[:-3] + "015", 15, "WLAN_REASON_4WAY_HANDSHAKE_TIMEOUT"))


def enchimento(de=0, ate=120):
    """One ordinary line a second from 09:59:30 + `de` s to 09:59:30 + `ate` s: what makes the logcat cover the hour."""
    saida = []
    for s in range(de, ate):
        total = 9 * 3600 + 59 * 60 + 30 + s
        saida.append(f"10-04 {total // 3600:02d}:{total % 3600 // 60:02d}:{total % 60:02d}.000   699   699 I logd    : logdr: UID=2000 GID=2000 PID=1 n tail=0 logMask=1 pid=0 start=0ns deadline=0ns\n")
    return "".join(saida)


def tipo_da_primeira_queda(logcat, marcar=True, **opcoes):
    """The first fall of a ride that loses the Wi-Fi at 10:01:00 (the watchdog 4 s later, as in the real ones)."""
    registro = projecao(linha("10:00:30.000", "link", FIRME), perda_do_wifi("10:01:00.000"), linha("10:01:04.200", "error", "E: RX watchdog: dash silent 4s → link lost"))
    pasta = coleta(registro, logcat=logcat)
    linhas = ler_registro.ler([pasta])
    ler_registro.marcar_fim_de_viagem([pasta], linhas)
    if marcar:
        ler_registro.marcar_tipo_da_perda([pasta], linhas)
    [bloco] = ler_registro.projecoes(linhas)
    [grupo] = ler_registro._grupos_de_queda(bloco)
    return grupo[0].tipo_da_perda, "\n".join(ler_registro.linha_do_tempo(linhas)), linhas


class TipoDaPerdaDoLogcatTest(unittest.TestCase):
    def test_a_the_access_point_saying_reason_3_is_the_dash_leaving(self):
        tipo, tempo, _ = tipo_da_primeira_queda(enchimento() + logcat_driver("10:00:58.800", 3) + desconexao_do_driver("10:00:58.820", 3, "WLAN_REASON_DEAUTH_LEAVING"))
        self.assertEqual("A", tipo)
        self.assertIn("tipo da perda: A, o painel saiu", tempo)
        self.assertIn("às 10:00:58", tempo)

    def test_a_the_wpa_supplicant_without_locally_generated_is_the_dash_leaving_too(self):
        tipo, _, _ = tipo_da_primeira_queda(enchimento() + logcat_wpa("10:01:00.100", 3, 0))
        self.assertEqual("A", tipo)

    def test_a_a_deauth_that_the_phone_sent_itself_is_not_the_dash_leaving(self):
        # `LG: 1` and `locally_generated=1` are the phone leaving a network (the 23/11 21:15:09 lines): not A.
        tipo, _, _ = tipo_da_primeira_queda(enchimento() + desconexao_do_driver("10:00:55.000", 3, "WLAN_REASON_DEAUTH_LEAVING", local=1) + logcat_wpa("10:00:55.100", 3, 1))
        self.assertEqual("?", tipo)

    def test_b_the_beacon_miss_and_the_kickout_together_are_the_dash_vanishing(self):
        # 21/11 10:01: the roam trigger by BEACON MISS, the kickout and `reason 0 … BEACON_MISS_FAILURE`, no deauth before.
        tipo, tempo, _ = tipo_da_primeira_queda(enchimento() + beacon_miss("10:00:57.191") + kickout("10:00:59.800") + beacon_miss_falha("10:00:59.900"))
        self.assertEqual("B", tipo)
        self.assertIn("tipo da perda: B, o painel sumiu do ar", tempo)

    def test_b_the_beacon_miss_failure_alone_is_enough(self):
        tipo, _, _ = tipo_da_primeira_queda(enchimento() + beacon_miss_falha("10:00:59.900"))
        self.assertEqual("B", tipo)

    def test_b_the_system_may_give_up_seconds_after_the_first_line_of_the_fall(self):
        # 21/11 11:46: the watchdog of the app spoke 4,6 s before the system's first BEACON MISS and 8,4 s before the kickout.
        tipo, _, _ = tipo_da_primeira_queda(enchimento() + beacon_miss("10:01:04.600") + kickout("10:01:08.400") + beacon_miss_falha("10:01:08.450"))
        self.assertEqual("B", tipo)

    def test_c_the_kickout_for_inactivity_without_beacon_miss_is_the_dash_not_answering(self):
        # 08/11 13:02 and 23/11 21:15:40: `STA kickout … reason:2` and `PEER_INACTIVITY`, `LG: 1`, no BEACON MISS.
        tipo, tempo, _ = tipo_da_primeira_queda(enchimento() + kickout("10:00:59.100") + sem_resposta("10:00:59.200") + logcat_wpa("10:01:00.100", 1, 1))
        self.assertEqual("C", tipo)
        self.assertIn("tipo da perda: C, o painel parou de responder com o Wi-Fi no ar", tempo)

    def test_c_the_kickout_alone_without_the_driver_line_is_c_too(self):
        tipo, _, _ = tipo_da_primeira_queda(enchimento() + kickout("10:00:59.100"))
        self.assertEqual("C", tipo)

    def test_d_the_refused_4_way_after_the_kickout_is_the_stuck_dash(self):
        # 21/11 07:25: kickout 2 s before, our own deauth (`LG: 1`), and `reason 15` 8 s and 11 s after.
        logcat = (enchimento() + kickout("10:00:58.000") + sem_resposta("10:00:58.050") + desconexao_do_driver("10:01:02.000", 3, "WLAN_REASON_DEAUTH_LEAVING", local=1)
                  + quatro_vias("10:01:08.300") + logcat_wpa("10:01:11.400", 15, 0))
        tipo, tempo, _ = tipo_da_primeira_queda(logcat)
        self.assertEqual("D", tipo)
        self.assertIn("tipo da perda: D, 4-way recusado", tempo)

    def test_d_the_deauth_from_the_access_point_with_reason_15_counts_as_the_4_way(self):
        tipo, _, _ = tipo_da_primeira_queda(enchimento() + logcat_driver("10:01:09.000", 15))
        self.assertEqual("D", tipo)

    def test_the_dash_leaving_wins_over_everything_that_comes_after_it(self):
        logcat = enchimento() + logcat_driver("10:00:59.000", 3) + kickout("10:01:00.000") + beacon_miss("10:01:01.000") + quatro_vias("10:01:10.000")
        self.assertEqual("A", tipo_da_primeira_queda(logcat)[0])

    def test_the_4_way_wins_over_the_beacon_miss_and_the_beacon_miss_over_the_kickout(self):
        self.assertEqual("D", tipo_da_primeira_queda(enchimento() + beacon_miss("10:00:59.000") + kickout("10:01:00.000") + quatro_vias("10:01:10.000"))[0])
        self.assertEqual("B", tipo_da_primeira_queda(enchimento() + beacon_miss("10:00:59.000") + kickout("10:01:00.000"))[0])

    def test_the_deauth_of_the_next_fall_does_not_turn_this_one_into_a_(self):
        # 23/11: the kickout of 21:15:40 and, 27 s later, the real deauth of the next fall (21:16:07): the first stays C.
        tipo, _, _ = tipo_da_primeira_queda(enchimento() + kickout("10:00:59.100") + sem_resposta("10:00:59.200") + logcat_driver("10:01:31.000", 3))
        self.assertEqual("C", tipo)

    def test_an_event_of_another_hour_does_not_count(self):
        tipo, _, _ = tipo_da_primeira_queda(enchimento() + kickout("10:03:00.000") + logcat_driver("09:55:00.000", 3))
        self.assertEqual("?", tipo)

    def test_each_window_ends_where_it_says(self):
        # The fall is 10:01:00.000 to 10:01:04.200 (the watchdog): the dash's goodbye counts up to 5 s after it and 20 s before the first line, the BEACON MISS and the kickout up to
        # 15 s after it, the refused 4-way from 5 s before to 30 s after.
        casos = [
            ("A", logcat_driver("10:00:41.000", 3)), ("?", logcat_driver("10:00:38.000", 3)),
            ("A", logcat_driver("10:01:08.000", 3)), ("?", logcat_driver("10:01:11.000", 3)),
            ("B", beacon_miss("10:01:18.000")), ("?", beacon_miss("10:01:21.000")),
            ("C", kickout("10:01:18.000")), ("?", kickout("10:01:21.000")),
            ("B", beacon_miss("10:00:41.000")), ("?", beacon_miss("10:00:38.000")),
            ("D", logcat_driver("10:01:33.000", 15)), ("?", logcat_driver("10:01:37.000", 15)),
            ("D", logcat_driver("10:00:56.000", 15)), ("?", logcat_driver("10:00:54.000", 15)),
        ]
        for esperado, sinal in casos:
            with self.subTest(sinal=sinal[:60]):
                self.assertEqual(esperado, tipo_da_primeira_queda(enchimento(0, 180) + sinal)[0])


class SemLogcatTest(unittest.TestCase):
    def test_without_a_system_logcat_the_type_is_a_question_mark_and_says_why(self):
        tipo, tempo, _ = tipo_da_primeira_queda(None)
        self.assertEqual("?", tipo)
        self.assertIn("tipo da perda: ? (sem logcat do sistema na hora)", tempo)

    def test_a_logcat_of_another_hour_does_not_cover_the_fall(self):
        tipo, tempo, _ = tipo_da_primeira_queda(enchimento(0, 60).replace("09:59", "08:30").replace("10-04 10:00", "10-04 08:31") + logcat_driver("08:31:00.000", 3))
        self.assertEqual("?", tipo)
        self.assertIn("(sem logcat do sistema na hora)", tempo)

    def test_a_logcat_that_covers_the_hour_but_saw_no_loss_is_a_question_mark_with_another_text(self):
        tipo, tempo, _ = tipo_da_primeira_queda(enchimento())
        self.assertEqual("?", tipo)
        self.assertIn("tipo da perda: ? (o logcat do sistema cobre a hora mas não registrou a perda)", tempo)

    def test_the_thermal_flood_is_not_coverage(self):
        inundacao = "".join(f"10-04 10:00:{s % 60:02d}.{k:03d}   500   500 E ThermalHalWrapper: ThermalHAL not initialized properly\n" for s in range(40, 70) for k in range(2))
        _, tempo, _ = tipo_da_primeira_queda(inundacao)
        self.assertIn("(sem logcat do sistema na hora)", tempo)


class MarcacaoTest(unittest.TestCase):
    def test_nothing_is_said_before_the_marking(self):
        # Reading without `marcar_tipo_da_perda` (the tests of the older rules) leaves the timeline as it was.
        tipo, tempo, _ = tipo_da_primeira_queda(enchimento() + kickout("10:00:59.100"), marcar=False)
        self.assertEqual("", tipo)
        self.assertNotIn("tipo da perda", tempo)

    def test_the_type_does_not_change_what_is_the_end_of_the_ride(self):
        logcat = enchimento() + logcat_driver("10:00:58.800", 3)
        _, _, com = tipo_da_primeira_queda(logcat)
        _, _, sem = tipo_da_primeira_queda(logcat, marcar=False)
        quedas = lambda linhas: [(g[0].hora, ler_registro._painel_desligou(g, b)) for b in ler_registro.projecoes(linhas) for g in ler_registro._grupos_de_queda(b)]  # noqa: E731
        self.assertEqual(quedas(sem), quedas(com))
        self.assertEqual(ler_registro._por_data_e_build(sem), ler_registro._por_data_e_build(com))

    def test_a_fall_of_android_auto_without_the_wifi_signal_gets_no_type(self):
        registro = projecao(linha("10:01:00.000", "aa", "[AA] AaReceiver: session ended (clean=false, userExit=false)"))
        pasta = coleta(registro, logcat=enchimento() + logcat_driver("10:00:58.800", 3))
        linhas = ler_registro.ler([pasta])
        ler_registro.marcar_tipo_da_perda([pasta], linhas)
        self.assertEqual([""], [g[0].tipo_da_perda for b in ler_registro.projecoes(linhas) for g in ler_registro._grupos_de_queda(b)])

    def test_the_text_has_no_address_or_name(self):
        _, tempo, _ = tipo_da_primeira_queda(enchimento() + logcat_driver("10:00:58.800", 3))
        self.assertNotIn(MAC_AP, tempo)
        self.assertNotIn(MAC_TODOS, tempo)


class SinaisDoLogcatTest(unittest.TestCase):
    def sinais(self, texto):
        d = Path(tempfile.mkdtemp())
        (d / "logcat-sistema.txt").write_text(texto, encoding="utf-8")
        return logcat_sistema.sinais_da_perda([d], 2026)[0]

    def test_each_real_line_gives_its_signal(self):
        lidos = self.sinais(logcat_driver("10:00:01.000", 3) + logcat_driver("10:00:02.000", 15) + kickout("10:00:03.000") + beacon_miss("10:00:04.000")
                            + beacon_miss_falha("10:00:05.000") + sem_resposta("10:00:06.000") + logcat_wpa("10:00:07.000", 3, 0) + logcat_wpa("10:00:08.000", 15, 0))
        self.assertEqual(["saiu", "4way", "inatividade", "sumiu", "sumiu", "inatividade", "saiu", "4way"], [s.sinal for s in lidos])

    def test_what_is_not_a_loss_is_skipped(self):
        # The phone leaving (`LG: 1`), the ordinary reasons of the other lines, the signal changes, and the line without a time.
        lidos = self.sinais(desconexao_do_driver("10:00:01.000", 3, "WLAN_REASON_DEAUTH_LEAVING", local=1) + logcat_wpa("10:00:02.000", 2, 0)
                            + "10-04 10:00:03.000  2829  2829 I wpa_supplicant: wlan0: CTRL-EVENT-SIGNAL-CHANGE above=1 signal=-58\n"
                            + "lixo Deauth RX: vdev reason 3\n" + logcat_driver("02-30 10:00:00.000", 3).replace("10-04 02-30", "02-30"))
        self.assertEqual([], lidos)

    def test_the_signals_come_in_order_of_time_and_carry_no_address(self):
        lidos = self.sinais(kickout("10:00:09.000") + logcat_driver("10:00:01.000", 3))
        self.assertEqual([datetime(2026, 10, 4, 10, 0, 1), datetime(2026, 10, 4, 10, 0, 9)], [s.quando.replace(microsecond=0) for s in lidos])
        self.assertNotIn(MAC_AP, repr(lidos))

    def test_without_a_file_there_is_nothing(self):
        self.assertEqual(([], {}), logcat_sistema.sinais_da_perda([Path(tempfile.mkdtemp())], 2026))


if __name__ == "__main__":
    unittest.main()
