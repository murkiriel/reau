"""Tests of corrente.py and of the lines it adds to the timeline: the current, the charge and the CPU of the `device …` line (15/11/2026). Synthetic logs only."""
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import corrente  # noqa: E402
import ler_registro  # noqa: E402
from test_ler_registro import escreve, linha, projecao  # noqa: E402

RESTO = "mem=45/512MB phone=1830MB low=no screen=on interactive=yes doze=no saver=off exempt=no bike=none data=cell/ok"


def device(hora, corrente_ma="-310", carga="2840", cpu="35", carregando="no", bateria="64"):
    novos = f"current={corrente_ma}mA charge={carga}mAh cpu={cpu}%".replace("=-mA", "=-").replace("=-mAh", "=-").replace("=-%", "=-")
    return linha(hora, "device", f"device battery={bateria}% charging={carregando} temp=31.5C volt=4.123V thermal=none {novos} {RESTO}")


def device_antigo(hora, bateria="64"):
    return linha(hora, "device", f"device battery={bateria}% charging=no temp=31.5C volt=4.123V thermal=none {RESTO}")


def tempo(*meio):
    d = Path(tempfile.mkdtemp())
    escreve(d, "ride-20261115-100000.log", projecao(*meio))
    return ler_registro.linha_do_tempo(ler_registro.ler([d]))


def so_nossas(linhas):
    return [t for t in linhas if any(p in t for p in ("corrente", "carga da bateria", "CPU do REau", "sinal da corrente"))]


class LinhaNova(unittest.TestCase):
    def test_the_averages_with_and_without_the_dash_streaming(self):
        t = tempo(
            device("10:00:05.000", "-600"), device("10:00:10.000", "-400"),
            linha("10:00:12.000", "session", "AUTHENTICATING → STREAMING"),
            device("10:00:15.000", "-1000", cpu="100"), device("10:00:20.000", "-800", cpu="60"),
            linha("10:05:00.000", "session", "video loop stop: the picture stopped"),
            device("10:05:05.000", "-200", cpu="5"),
        )
        texto = "\n".join(so_nossas(t))
        # With the dash: (1000 + 800) / 2 = 900 mA; without it: (600 + 400 + 200) / 3 = 400 mA. The sign is the phone's (negative when it discharges), so it is flipped.
        self.assertIn("transmitindo 900 mA (2 olhadas), sem transmitir 400 mA (3 olhadas)", texto)
        self.assertIn("sinal da corrente invertido", texto)

    def test_the_charge_is_the_first_minus_the_last_and_the_cpu_is_mean_and_max(self):
        t = tempo(device("10:00:05.000", carga="2840", cpu="35"), device("10:00:10.000", carga="2800", cpu="185"), device("10:00:15.000", carga="2720", cpu="-"))
        texto = "\n".join(so_nossas(t))
        self.assertIn("carga da bateria: consumiu 120 mAh (de 2840 a 2720 mAh)", texto)
        self.assertIn("CPU do REau: média 110 %, máxima 185 % de um núcleo (2 olhadas)", texto)

    def test_a_charge_that_rose_says_the_charger_was_on(self):
        t = tempo(device("10:00:05.000", carga="2700"), device("10:00:10.000", carga="2750"))
        self.assertIn("carga da bateria: subiu 50 mAh (o carregador esteve ligado) (de 2700 a 2750 mAh)", "\n".join(so_nossas(t)))

    def test_a_phone_that_gives_the_current_positive_when_discharging_is_not_flipped(self):
        t = tempo(device("10:00:05.000", "500"), device("10:00:10.000", "700"))
        texto = "\n".join(so_nossas(t))
        self.assertIn("sem transmitir 600 mA (2 olhadas)", texto)
        self.assertNotIn("sinal da corrente invertido", texto)

    def test_the_sign_follows_the_majority_of_the_looks_without_the_charger_only(self):
        # Three negative looks with the charger out and two positive looks with it in: the majority without the charger is negative, so it is flipped.
        t = tempo(device("10:00:05.000", "-300"), device("10:00:10.000", "-500"), device("10:00:15.000", "-400"),
                  device("10:00:20.000", "1500", carregando="yes"), device("10:00:25.000", "1600", carregando="yes"))
        texto = "\n".join(so_nossas(t))
        self.assertIn("sem transmitir 400 mA (3 olhadas)", texto)
        self.assertIn("sinal da corrente invertido", texto)

    def test_an_old_log_without_the_new_fields_says_nothing_about_them(self):
        t = tempo(device_antigo("10:00:05.000", "75"), device_antigo("10:00:10.000", "64"))
        self.assertEqual([], so_nossas(t))
        self.assertIn("bateria 75% → 64%", "\n".join(t))

    def test_a_phone_that_gave_dashes_says_so_and_invents_no_number(self):
        t = tempo(device("10:00:05.000", "-", "-", "-"), device("10:00:10.000", "-", "-", "-"))
        texto = "\n".join(so_nossas(t))
        self.assertIn("nenhuma olhada sem o carregador com a corrente lida", texto)
        self.assertIn("o aparelho não deu o contador", texto)
        self.assertIn("nenhuma olhada com o uso lido", texto)
        self.assertNotIn(" mA (", texto)


class OlhadasEResumo(unittest.TestCase):
    def blocos(self, *meio):
        d = Path(tempfile.mkdtemp())
        escreve(d, "ride-20261115-100000.log", projecao(*meio))
        return ler_registro.projecoes(ler_registro.ler([d]))[0]

    def test_a_new_line_and_an_old_one_are_both_read(self):
        o = corrente.olhadas(self.blocos(device("10:00:05.000", "-310", "2840", "35"), device_antigo("10:00:10.000")))
        self.assertEqual((True, -310, 2840, 35, "no"), (o[0]["tem"], o[0]["corrente"], o[0]["carga"], o[0]["cpu"], o[0]["carregando"]))
        self.assertEqual((False, None, None, None), (o[1]["tem"], o[1]["corrente"], o[1]["carga"], o[1]["cpu"]))

    def test_a_dash_is_none_not_zero(self):
        o = corrente.olhadas(self.blocos(device("10:00:05.000", "-", "-", "-")))
        self.assertEqual((True, None, None, None), (o[0]["tem"], o[0]["corrente"], o[0]["carga"], o[0]["cpu"]))

    def test_the_streaming_flag_comes_with_the_arrow_and_goes_with_the_ending(self):
        o = corrente.olhadas(self.blocos(
            device("10:00:05.000"), linha("10:00:06.000", "session", "AUTHENTICATING → STREAMING"), device("10:00:10.000"),
            linha("10:00:11.000", "session", "disconnect() — explicit, not a link drop"), device("10:00:15.000")))
        self.assertEqual([False, True, False], [x["transmitindo"] for x in o])

    def test_no_summary_for_a_projection_without_the_fields(self):
        self.assertIsNone(corrente.resumo(self.blocos(device_antigo("10:00:05.000"))))


if __name__ == "__main__":
    unittest.main()
