"""Testes do ler_registro.py para as linhas `[audio]` do som do Bluetooth (0.4.0): o aparelho de áudio que entra ou sai aparece na linha do tempo, só com o tipo e a direção.

    python -B scripts\\registro\\test_ler_registro_bt.py
"""
import sys
import tempfile
import unittest
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))
import ler_registro  # noqa: E402
from test_ler_registro import escreve, linha, projecao  # noqa: E402


def tempo(texto):
    d = Path(tempfile.mkdtemp())
    escreve(d, "ride-20261125-100000.log", texto)
    return "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([d])))


class SomDoBluetooth(unittest.TestCase):
    def test_o_aparelho_que_entra_e_o_que_sai_entram_na_linha_do_tempo(self):
        t = tempo(projecao(
            linha("10:01:00.000", "audio", "bluetooth audio at start: no device"),
            linha("10:02:00.000", "audio", "bluetooth device added: A2DP out (rates 44100/48000, channels 2, encodings PCM16) - now 1 bluetooth output(s), 0 input(s)"),
            linha("10:02:00.100", "audio", "route: media -> BLUETOOTH_A2DP, call -> BUILTIN_EARPIECE, mode=NORMAL sco=false"),
            linha("10:03:00.000", "audio", "bluetooth devices added: SCO out (rates 8000, channels 1); SCO in (rates 16000) - now 2 bluetooth output(s), 1 input(s)"),
            linha("10:04:00.000", "audio", "audio becoming noisy: Android warns the output is changing (a headset or a Bluetooth link went away)"),
            linha("10:04:00.200", "audio", "bluetooth device removed: A2DP out - now 1 bluetooth output(s), 1 input(s)")))
        self.assertIn("10:02:00.000  Bluetooth: aparelho de áudio conectado (A2DP out); agora 1 saída(s) e 0 entrada(s)", t)
        self.assertIn("Bluetooth: aparelho de áudio conectado (SCO out; SCO in); agora 2 saída(s) e 1 entrada(s)", t)
        self.assertIn("Bluetooth: o Android avisou que o som vai mudar de saída", t)
        self.assertIn("Bluetooth: aparelho de áudio desconectado (A2DP out); agora 1 saída(s) e 1 entrada(s)", t)
        self.assertNotIn("rates", t)
        self.assertNotIn("route:", t)
        self.assertNotIn("at start", t)

    def test_os_aparelhos_que_ja_estavam_na_partida_entram_com_o_tipo(self):
        t = tempo(projecao(linha(
            "10:01:00.000", "audio",
            "bluetooth audio at start: A2DP out (rates 44100/48000, channels 2) - 1 bluetooth output(s), 0 input(s)")))
        self.assertIn("Bluetooth: aparelho de áudio já conectado na partida (A2DP out); 1 saída(s) e 0 entrada(s)", t)

    def test_a_linha_de_som_que_ja_existia_nao_vira_evento(self):
        t = tempo(projecao(linha("10:01:00.000", "audio", "the first sound of MEDIA arrived: 1920 bytes")))
        self.assertNotIn("Bluetooth", t)


if __name__ == "__main__":
    unittest.main()
