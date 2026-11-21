"""The end of the ride (the bike switched off) told apart from a fall (plan 30, 17/11/2026).

The reader counted the dash's Wi-Fi vanishing with the bike as a "fall with a firm Wi-Fi" (the rides of 16/11/2026 gave the reference group 3.28 falls/h for a real 0). The system tells it:
the access point of the dash sends a deauthentication with `reason 3` (it is leaving) a moment BEFORE the app notices. Synthetic logs only: the MACs are put together while the test runs
and the dash's name is the invented `RE_AB12_345678`.

    python3 -B -m unittest test_fim_de_viagem     (from scripts/registro)
"""
import io
import contextlib
import re
import sys
import tempfile
import unittest
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))
import ler_registro  # noqa: E402
import logcat_sistema  # noqa: E402

MAC_AP = ":".join(["aa", "bb", "cc", "dd", "ee", "01"])
MAC_TODOS = ":".join(["ff"] * 6)
CABECALHO = "10:00:00.000  +     0ms  ==== process start: app — Google Pixel, Android 16 (API 36) ====\n"
FIRME = "rssi=-58 dBm tx=72 rx=72 Mbps 2.4 GHz powersave=on screen=on | last 60 s, every 5 s: rssi -58 -58 -58 -58 | tx 72 72 72 72"
OSCILANDO = "rssi=-41 dBm tx=72 rx=72 Mbps 2.4 GHz powersave=on screen=on | last 60 s, every 5 s: rssi -65 -65 -65 -56 -41 -41 | tx 52 52 52 72 72 72"


def linha(hora, etiqueta, texto):
    return f"{hora}  +     0ms  [{etiqueta}] {texto}\n"


def olhada(hora, bike):
    return linha(hora, "device", f"device battery=75% charging=no temp=33.0C volt=3.9V thermal=none mem=21/256MB phone=2850MB low=no "
                                 f"screen=on interactive=yes doze=no saver=off exempt=yes bike={bike} data=wifi/ok [30 s]")


def projecao(*meio, fim="10:09:00.000"):
    texto = CABECALHO + "10:00:01.000  +  1000ms  ---- projeção ligada: projection ----\n" + "".join(meio)
    if fim:
        texto += f"{fim}  +  1000ms  ---- projeção desligada: projection ----\n"
    return texto


def perda_do_wifi(hora="10:01:00.000"):
    return (linha(hora, "wifi", "W: link lost — reconnecting in 4000ms (after 1037 s on 2417 MHz; rssi -41, last dash packet 0.3 s before)")
            + linha(hora[:-3] + "003", "system", "bike network lost"))


def vigia(hora="10:01:04.200"):
    return linha(hora, "error", "E: RX watchdog: dash silent 4s → link lost")


def logcat_driver(hora, motivo=3):
    """The driver's line, with the colon inside the tag that the `threadtime` reader does not read."""
    return f"10-04 {hora}  2843  2843 I [schedu][0x9d85d365b10][12:03:58.477855] wlan: [2843:I:PE] Deauth RX: vdev 0 from {MAC_AP} for {MAC_TODOS} RSSI = -60 reason {motivo} mlm state = 14\n"


def logcat_wpa(hora, motivo=3, local=1):
    return f"10-04 {hora}   900   900 I wpa_supplicant: wlan0: CTRL-EVENT-DISCONNECTED bssid={MAC_AP} reason={motivo} locally_generated={local}\n"


def coleta(registro, logcat=None, dumpsys=None):
    d = Path(tempfile.mkdtemp())
    (d / "ride-20261004-100000.log").write_text(registro, encoding="utf-8")
    if logcat is not None:
        (d / "logcat-sistema.txt").write_text(logcat, encoding="utf-8")
    if dumpsys is not None:
        (d / "sistema").mkdir()
        (d / "sistema" / "dumpsys-wifi.txt").write_text(dumpsys, encoding="utf-8")
    return d


def lido(pasta):
    linhas = ler_registro.ler([pasta])
    ler_registro.marcar_fim_de_viagem([pasta], linhas)
    return linhas


def desligou(linhas):
    """[(hora da primeira linha, é fim de viagem)] de cada queda."""
    return [(g[0].hora[:8], ler_registro._painel_desligou(g, bloco)) for bloco in ler_registro.projecoes(linhas) for g in ler_registro._grupos_de_queda(bloco)]


class LeitorDoLogcatTest(unittest.TestCase):
    def desconexoes(self, texto):
        d = Path(tempfile.mkdtemp())
        (d / "logcat-sistema.txt").write_text(texto, encoding="utf-8")
        return logcat_sistema.desconexoes_do_wifi([d], 2026)

    def test_the_drivers_deauth_is_read_though_its_tag_has_colons_and_it_is_always_the_other_side(self):
        [d] = self.desconexoes(logcat_driver("10:01:00.500"))
        self.assertEqual((3, False), (d.motivo, d.local))
        self.assertEqual("2026-10-04 10:01:00.500", f"{d.quando:%Y-%m-%d %H:%M:%S.%f}"[:-3])

    def test_the_driver_disconnect_line_says_who_left_by_lg(self):
        fora = "10-04 10:01:00.700  2843  2843 I [schedu][0x1][12:03:58.496621] wlan: [2843:I:HDD] Disconnect reason: 3 WLAN_REASON_DEAUTH_LEAVING vendor: 0  LG: 0\n"
        d = self.desconexoes(fora) + self.desconexoes(fora.replace("LG: 0", "LG: 1"))
        self.assertEqual([(3, False), (3, True)], [(x.motivo, x.local) for x in d])

    def test_wpa_supplicant_says_who_left_by_locally_generated(self):
        d = self.desconexoes(logcat_wpa("10:01:00.000", 3, 1) + logcat_wpa("10:01:01.000", 3, 0) + logcat_wpa("10:01:02.000", 2, 0))
        self.assertEqual([(3, True), (3, False), (2, False)], [(x.motivo, x.local) for x in d])

    def test_other_lines_are_skipped_and_nothing_that_identifies_is_kept(self):
        d = self.desconexoes("10-04 10:01:00.000  1  1 I wificond: Failed to get NL80211_ATTR_MAX_NUM_AKM_SUITES\n" + "lixo\n" + logcat_driver("10:01:00.500"))
        self.assertEqual(1, len(d))
        self.assertNotIn("aa:bb", repr(d))

    def test_an_impossible_day_is_skipped(self):
        self.assertEqual([], self.desconexoes(logcat_driver("10:01:00.500").replace("10-04", "02-30")))

    def test_without_a_system_log_there_is_nothing(self):
        self.assertEqual([], logcat_sistema.desconexoes_do_wifi([Path(tempfile.mkdtemp())], 2026))


class FimDeViagemPeloSistemaTest(unittest.TestCase):
    def test_the_watchdog_alone_with_the_deauth_seconds_before_is_the_end_of_the_ride_not_a_fall(self):
        # The ride of 16/11 09:04: no `[link]` history, the watchdog the first sign, the deauth 5.4 s before it.
        pasta = coleta(projecao(olhada("10:00:40.000", "-65dBm/2452MHz/72Mbps"), vigia("10:01:04.200")), logcat=logcat_driver("10:00:58.800"))
        linhas = lido(pasta)
        self.assertEqual([("10:01:04", True)], desligou(linhas))
        tempo = "\n".join(ler_registro.linha_do_tempo(linhas))
        self.assertIn("painel desligou (moto desligada?): fim de viagem, não queda (painel calado); o sistema registrou a saída do painel (reason 3) às 10:00:58", tempo)

    def test_a_firm_wifi_with_a_rising_history_is_the_end_when_the_system_says_reason_3(self):
        # The ride of 16/11 19:13: the signal went -65 → -41 in the last minute, so the history rule (steady) does not hold; the deauth came 1.7 s before.
        registro = projecao(linha("10:00:30.000", "link", OSCILANDO), perda_do_wifi("10:01:00.000"), vigia("10:01:04.200"))
        sem = lido(coleta(registro))
        com = lido(coleta(registro, logcat=logcat_driver("10:00:58.300")))
        self.assertEqual([("10:01:00", False)], desligou(sem))
        self.assertEqual([("10:01:00", True)], desligou(com))

    def test_the_reason_3_of_the_dumpsys_events_counts_too(self):
        dumpsys = "StaEventList:\n10-04 10:00:59.000 NETWORK_DISCONNECTION_EVENT local_gen=false reason=3:DEAUTH_LEAVING lastRssi=-54 lastFreq=2452\n"
        linhas = lido(coleta(projecao(perda_do_wifi("10:01:00.000"), vigia("10:01:04.200")), dumpsys=dumpsys))
        self.assertEqual([("10:01:00", True)], desligou(linhas))

    def test_the_dumpsys_event_the_phone_made_itself_is_a_fall(self):
        dumpsys = "StaEventList:\n10-04 10:00:59.000 NETWORK_DISCONNECTION_EVENT local_gen=true reason=3:DEAUTH_LEAVING lastRssi=-54 lastFreq=2452\n"
        registro = projecao(olhada("10:00:40.000", "-50dBm/2452MHz/72Mbps"), perda_do_wifi("10:01:00.000"), vigia("10:01:04.200"))
        self.assertEqual([("10:01:00", False)], desligou(lido(coleta(registro, dumpsys=dumpsys))))

    def test_the_phone_leaving_by_itself_is_a_fall_even_with_reason_3(self):
        # `locally_generated=1`: the phone left the dash; the system saw the Wi-Fi go, but not the bike switched off.
        registro = projecao(olhada("10:00:40.000", "-50dBm/2452MHz/72Mbps"), perda_do_wifi("10:01:00.000"))
        linhas = lido(coleta(registro, logcat=logcat_wpa("10:00:59.000", 3, 1)))
        self.assertEqual([("10:01:00", False)], desligou(linhas))
        self.assertFalse(linhas[[x.hora for x in linhas].index("10:01:00.000")].saiu_reason3)

    def test_another_reason_in_the_system_log_is_a_fall_even_when_the_rider_stops_soon(self):
        # Firm signal, no return, stopped 8 s later: the no-log rule would call it the end; the system saw reason 2, so it stays a fall.
        registro = projecao(olhada("10:00:40.000", "-50dBm/2452MHz/72Mbps"), perda_do_wifi("10:01:00.000"), fim="10:01:08.000")
        self.assertEqual([("10:01:00", True)], desligou(lido(coleta(registro))))
        self.assertEqual([("10:01:00", False)], desligou(lido(coleta(registro, logcat=logcat_driver("10:00:59.000", motivo=2)))))

    def test_a_dash_that_comes_back_is_not_the_end_even_with_reason_3(self):
        registro = projecao(olhada("10:00:40.000", "-50dBm/2452MHz/72Mbps"), perda_do_wifi("10:01:00.000"), linha("10:01:30.000", "wifi", "connected 'RE_****34' in 900ms"))
        self.assertEqual([("10:01:00", False)], desligou(lido(coleta(registro, logcat=logcat_driver("10:00:59.000")))))
        # the watchdog alone has no Wi-Fi loss line for `_voltou`: the return is read all the same
        so_vigia = projecao(olhada("10:00:40.000", "-50dBm/2452MHz/72Mbps"), vigia("10:01:00.000"), linha("10:01:30.000", "session", "AUTHENTICATING → STREAMING"))
        self.assertEqual([("10:01:00", False)], desligou(lido(coleta(so_vigia, logcat=logcat_driver("10:00:57.000")))))

    def test_a_return_after_five_minutes_does_not_save_it(self):
        registro = projecao(vigia("10:01:00.000"), linha("10:06:10.000", "wifi", "connected 'RE_****34' in 900ms"), fim="10:07:00.000")
        self.assertEqual([("10:01:00", True)], desligou(lido(coleta(registro, logcat=logcat_driver("10:00:57.000")))))

    def test_the_window_is_20_s_before_and_5_s_after_the_first_line_of_the_fall(self):
        registro = projecao(vigia("10:01:00.000"))
        for hora, esperado in (("10:00:40.000", True), ("10:00:39.900", False), ("10:01:05.000", True), ("10:01:05.100", False)):
            with self.subTest(hora=hora):
                self.assertEqual([("10:01:00", esperado)], desligou(lido(coleta(registro, logcat=logcat_driver(hora)))))

    def test_a_fall_that_is_only_the_android_auto_session_is_not_the_end_of_the_ride(self):
        registro = projecao(linha("10:01:00.000", "aa", "[AA] AaReceiver: session ended (clean=false, userExit=false)"))
        self.assertEqual([("10:01:00", False)], desligou(lido(coleta(registro, logcat=logcat_driver("10:00:58.000")))))


class FimDeViagemSemLogDoSistemaTest(unittest.TestCase):
    def registro(self, bike="-65dBm/2452MHz/72Mbps", fim="10:01:20.000"):
        return projecao(olhada("10:00:40.000", bike), vigia("10:01:00.000"), fim=fim)

    def test_a_firm_wifi_that_does_not_return_and_the_rider_switching_off_soon_is_read_as_the_end(self):
        linhas = lido(coleta(self.registro()))
        self.assertEqual([("10:01:00", True)], desligou(linhas))
        self.assertIn("sem o log do sistema: o Wi-Fi estava firme, não voltou e o piloto desligou a projeção 20 s depois", "\n".join(ler_registro.linha_do_tempo(linhas)))

    def test_the_rider_waiting_longer_than_30_s_leaves_it_a_fall(self):
        self.assertEqual([("10:01:00", True)], desligou(lido(coleta(self.registro(fim="10:01:30.000")))))
        self.assertEqual([("10:01:00", False)], desligou(lido(coleta(self.registro(fim="10:01:30.100")))))

    def test_a_weak_signal_before_is_a_fall(self):
        self.assertEqual([("10:01:00", False)], desligou(lido(coleta(self.registro(bike="-85dBm/2452MHz/72Mbps")))))

    def test_without_any_signal_in_the_log_nothing_is_said(self):
        self.assertEqual([("10:01:00", False)], desligou(lido(coleta(projecao(vigia("10:01:00.000"), fim="10:01:10.000")))))

    def test_a_projection_that_has_not_ended_is_not_read_as_the_end(self):
        self.assertEqual([("10:01:00", False)], desligou(lido(coleta(self.registro(fim=None)))))


class ContagemDoGrupoETextoTest(unittest.TestCase):
    def test_the_end_of_the_ride_is_not_counted_as_a_fall_nor_a_firm_one_in_the_reference(self):
        # Two rides of the same collection: one ends with the bike off (reason 3), the other has a real fall with a firm Wi-Fi that comes back.
        fim = projecao(olhada("10:00:40.000", "-65dBm/2452MHz/72Mbps"), vigia("10:01:04.200"))
        queda = ("11:00:01.000  +  1000ms  ---- projeção ligada: projection ----\n" + olhada("11:00:40.000", "-50dBm/2452MHz/72Mbps") + perda_do_wifi("11:01:00.000")
                 + linha("11:01:30.000", "wifi", "connected 'RE_****34' in 900ms") + "11:09:00.000  +  1000ms  ---- projeção desligada: projection ----\n")
        registro = fim + queda
        linhas = lido(coleta(registro, logcat=logcat_driver("10:00:58.800")))
        [c1, c2] = ler_registro.corridas(linhas)
        self.assertEqual((0, 0), (c1["quedas"], c1["firmes"]))
        self.assertEqual((1, 1), (c2["quedas"], c2["firmes"]))

    def test_the_by_day_line_tells_the_ends_apart_from_the_falls(self):
        registro = projecao(olhada("10:00:40.000", "-65dBm/2452MHz/72Mbps"), vigia("10:01:04.200"))
        com = ler_registro._por_data_e_build(lido(coleta(registro, logcat=logcat_driver("10:00:58.800"))))
        self.assertTrue(com[0].endswith("0 quedas, 1 fim de viagem"), com)
        sem = ler_registro._por_data_e_build(lido(coleta(registro.replace("10:09:00.000", "10:30:00.000"))))
        self.assertTrue(sem[0].endswith(", 1 queda"), sem)

    def test_the_command_line_puts_the_end_in_the_moto_section_and_the_exit_code_stays_zero(self):
        registro = projecao(olhada("10:00:40.000", "-65dBm/2452MHz/72Mbps"), perda_do_wifi("10:01:00.000"), vigia("10:01:04.200"))
        pasta = coleta(registro, logcat=logcat_driver("10:00:58.800") + logcat_wpa("10:00:58.900", 3, 0))
        saida = io.StringIO()
        with contextlib.redirect_stdout(saida):
            codigo = ler_registro.main([str(pasta)])
        texto = saida.getvalue()
        self.assertEqual(0, codigo, texto)
        self.assertIn("fim de viagem, não queda", texto)
        self.assertIn("0 quedas, 1 fim de viagem", texto)
        self.assertNotIn("aa:bb", texto)


class PadraoDaAnaliseDe16Test(unittest.TestCase):
    """The two rides of 16/11/2026 that ended with the bike switched off (A1 of the analysis of 15 to 17/11), as synthetic logs: the signal firm, the last packet of the dash
    under 1 s before the loss, the rider stopping the projection within 10 s. With the system log it is the `reason 3`; without it, the firm Wi-Fi and the stop."""

    def registro(self, fim):
        return projecao(olhada("10:00:40.000", "-41dBm/2417MHz/72Mbps"), perda_do_wifi("10:01:00.000"), vigia("10:01:04.200"), fim=fim)

    def test_the_pattern_without_the_system_log_is_the_end_when_the_rider_stops_within_10_s(self):
        for fim in ("10:01:09.000", "10:01:10.000"):
            with self.subTest(fim=fim):
                self.assertEqual([("10:01:00", True)], desligou(lido(coleta(self.registro(fim)))))

    def test_the_same_pattern_with_the_deauth_says_the_system_saw_it(self):
        linhas = lido(coleta(self.registro("10:01:09.000"), logcat=logcat_driver("10:00:58.300")))
        self.assertEqual([("10:01:00", True)], desligou(linhas))
        self.assertIn("o sistema registrou a saída do painel (reason 3) às 10:00:58", "\n".join(ler_registro.linha_do_tempo(linhas)))

    def test_the_end_does_not_depend_on_which_of_the_two_logs_the_collection_has(self):
        dumpsys = "StaEventList:\n10-04 10:00:59.000 NETWORK_DISCONNECTION_EVENT local_gen=false reason=3:DEAUTH_LEAVING lastRssi=-55 lastFreq=2417\n"
        for kw in ({}, {"logcat": logcat_driver("10:00:58.300")}, {"dumpsys": dumpsys}):
            with self.subTest(fontes=list(kw)):
                self.assertEqual([("10:01:00", True)], desligou(lido(coleta(self.registro("10:01:09.000"), **kw))))


class NadaQueIdentifiqueSaiTest(unittest.TestCase):
    """The system log carries the MAC and the BSSID of the dash's access point and the name of the network: none of it may reach the reader's output, in any of its sections."""
    MAC = re.compile(r"(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}")

    def test_no_address_nor_network_name_in_the_whole_output_even_with_everything_on(self):
        nome = "RE_AB12_345678"
        logcat = (logcat_driver("10:00:58.300") + logcat_wpa("10:00:58.400", 3, 0)
                  + f"10-04 10:00:58.500   900   900 I wpa_supplicant: wlan0: CTRL-EVENT-SSID-TEMP-DISABLED id=3 ssid=\"{nome}\" bssid={MAC_AP}\n"
                  + f"10-04 10:00:58.600  1000  1000 I WifiNetworkFactory: Connection is terminated, cancelling NetworkRequest for \"{nome}\" {MAC_AP}\n")
        registro = projecao(olhada("10:00:40.000", "-65dBm/2452MHz/72Mbps"), perda_do_wifi("10:01:00.000"), vigia("10:01:04.200"))
        pasta = coleta(registro, logcat=logcat)
        for argumentos in ([str(pasta)], [str(pasta), "--tudo"]):
            with self.subTest(argumentos=argumentos[1:]):
                saida = io.StringIO()
                with contextlib.redirect_stdout(saida):
                    codigo = ler_registro.main(argumentos)
                texto = saida.getvalue()
                self.assertEqual(0, codigo, texto)
                self.assertIn("fim de viagem, não queda", texto)
                self.assertIsNone(self.MAC.search(texto), texto)
                self.assertNotIn(nome, texto)


if __name__ == "__main__":
    unittest.main()
