"""Tests of the GPS the experimental «velocidade do GPS» key switches on during a route (15/11/2026): what the reader makes of the app's lines and of the time the GPS was on
because of the key. Synthetic logs only; the lines are the ones the app writes (SpeedGps.kt, ProfileSpeed.kt)."""
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import chaves  # noqa: E402
import gps_chave  # noqa: E402
import ler_registro  # noqa: E402
from test_ler_registro import escreve, linha, projecao  # noqa: E402

LIGA = "gps: on for the speed profile (route)"


def solta(motivo):
    return f"gps: off for the speed profile ({motivo})"


class X:
    """A line of a projection, as far as gps_chave reads one."""

    def __init__(self, hora, texto):
        h, m, s = hora.split(":")
        self.hora, self.texto, self.segundos, self.etiqueta = hora, texto, int(h) * 3600 + int(m) * 60 + float(s), "stream"


class PedidosTest(unittest.TestCase):
    def test_a_request_lasts_from_its_on_line_to_its_off_line(self):
        bloco = [X("10:00:00.000", LIGA), X("10:12:30.000", solta("route ended"))]
        self.assertEqual([(750.0, True)], gps_chave.pedidos(bloco))

    def test_two_requests_add_up_and_are_counted(self):
        bloco = [X("10:00:00.000", LIGA), X("10:02:00.000", solta("route ended")), X("10:10:00.000", LIGA), X("10:11:30.000", solta("key off"))]
        self.assertEqual(["GPS ligado pela chave «velocidade do GPS»: 3 min 30 s em 2 pedidos"], gps_chave.texto(bloco))

    def test_one_request_is_said_in_the_singular(self):
        bloco = [X("10:00:00.000", LIGA), X("10:00:45.000", solta("projection stopped"))]
        self.assertEqual(["GPS ligado pela chave «velocidade do GPS»: 45 s em 1 pedido"], gps_chave.texto(bloco))

    def test_a_request_with_no_end_line_is_counted_to_the_last_line_and_says_so(self):
        bloco = [X("10:00:00.000", LIGA), X("10:05:00.000", "something else")]
        self.assertEqual([(300.0, False)], gps_chave.pedidos(bloco))
        self.assertEqual(["GPS ligado pela chave «velocidade do GPS»: 5 min 00 s em 1 pedido (o último não tem a linha de fim: contado até o fim da projeção)"], gps_chave.texto(bloco))

    def test_an_on_line_inside_an_open_request_opens_no_other_and_a_lone_off_counts_nothing(self):
        self.assertEqual([(120.0, True)], gps_chave.pedidos([X("10:00:00.000", LIGA), X("10:01:00.000", LIGA), X("10:02:00.000", solta("route ended"))]))
        self.assertEqual([], gps_chave.pedidos([X("10:00:00.000", solta("route ended"))]))

    def test_the_midnight_does_not_make_a_negative_time(self):
        bloco = [X("23:59:30.000", LIGA), X("00:00:30.000", solta("route ended"))]
        self.assertEqual([(60.0, True)], gps_chave.pedidos(bloco))

    def test_without_the_key_nothing_is_said(self):
        self.assertEqual([], gps_chave.texto([X("10:00:00.000", "speed profile: back to the route's estimate (no GPS speed, or no route)")]))
        self.assertEqual([], gps_chave.texto([]))


class EventosTest(unittest.TestCase):
    def test_each_line_of_the_key_is_translated(self):
        self.assertIn("GPS ligado para o perfil de velocidade, durante a rota", gps_chave.evento(LIGA))
        for motivo, dito in (("route ended", "a rota acabou"), ("key off", "a chave foi desligada"), ("location off", "«Usar a localização» foi desligado"),
                             ("speed profile off", "o perfil por velocidade foi desligado"), ("no location permission", "sem a permissão de localização"),
                             ("projection stopped", "a projeção parou")):
            self.assertEqual(f"GPS solto pelo perfil de velocidade: {dito} (chave experimental)", gps_chave.evento(solta(motivo)))
        self.assertIn("ainda sem fix do GPS", gps_chave.evento("speed profile: GPS key on, no GPS fix yet; route speed used"))

    def test_a_reason_the_reader_does_not_know_passes_as_it_came(self):
        self.assertEqual("GPS solto pelo perfil de velocidade: something new (chave experimental)", gps_chave.evento(solta("something new")))

    def test_other_lines_are_none(self):
        self.assertIsNone(gps_chave.evento("speed profile: reads the GPS speed (72 km/h; the route says 60 km/h)"))
        self.assertIsNone(gps_chave.evento("gps: on for the ring"))


class NaLinhaDoTempoTest(unittest.TestCase):
    def tempo(self, *meio):
        d = Path(tempfile.mkdtemp())
        escreve(d, "ride-20261115-100000.log", projecao(*meio))
        return "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([d])))

    def test_the_timeline_says_the_lines_and_the_time_the_key_kept_the_gps_on(self):
        t = self.tempo(
            linha("10:02:00.000", "stream", "speed profile: GPS key on, no GPS fix yet; route speed used"),
            linha("10:01:00.000", "stream", LIGA),
            linha("10:03:00.000", "stream", "speed profile: reads the GPS speed (72 km/h; the route says 60 km/h)"),
            linha("10:06:00.000", "stream", solta("route ended")),
            linha("10:07:00.000", "stream", LIGA),
            linha("10:08:00.000", "stream", solta("projection stopped")),
        )
        self.assertIn("GPS ligado para o perfil de velocidade, durante a rota (chave experimental; gasta bateria)", t)
        self.assertIn("GPS solto pelo perfil de velocidade: a rota acabou (chave experimental)", t)
        self.assertIn("GPS solto pelo perfil de velocidade: a projeção parou (chave experimental)", t)
        self.assertIn("perfil de velocidade: chave do GPS ligada, ainda sem fix do GPS", t)
        self.assertIn("perfil de velocidade passou a ler o GPS: 72 km/h", t)
        self.assertIn("GPS ligado pela chave «velocidade do GPS»: 6 min 00 s em 2 pedidos", t)

    def test_a_projection_without_the_key_has_no_such_line(self):
        t = self.tempo(linha("10:03:00.000", "stream", "speed profile: back to the route's estimate (no GPS speed, or no route)"))
        self.assertNotIn("GPS ligado pela chave", t)
        self.assertNotIn("GPS ligado para o perfil", t)


class ChaveTest(unittest.TestCase):
    def test_the_old_and_the_new_phrase_of_the_settings_line_find_the_key(self):
        for frase in ("GPS speed for the speed profile when the GPS is already listened to",
                      "GPS speed for the speed profile: the GPS is switched on during a route (uses battery)"):
            self.assertEqual(({"experimentGpsSpeed"}, True), chaves.chaves_da_linha(f"connection experiments ON: {frase}"), frase)
        self.assertEqual(({"experimentGpsSpeed", "experimentWeatherAge"}, True),
                         chaves.chaves_da_linha("connection experiments ON: weather hidden from the dash after 30 min without a fetch (not 3 h); "
                                                "GPS speed for the speed profile: the GPS is switched on during a route (uses battery)"))


if __name__ == "__main__":
    unittest.main()
