"""Tests of olhadas.py: the readings of the picture classifier with p < 0.98 and the alarm of the rate of "not sure" (item 5, 11/11/2026). Since 15/11/2026 the alarm counts only the
class UNSURE (p < 0.9, what the app does not trust); the band 0.9 to 0.98 is information, and a cut of the app's audit by its line limit is a warning."""
import sys
import unittest
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))
import olhadas  # noqa: E402
from test_ler_registro import linha, projecao  # noqa: E402
import ler_registro  # noqa: E402
import tempfile  # noqa: E402
from test_ler_registro import escreve  # noqa: E402


def look(hora, classe, p, motivo="p under 0.98"):
    return linha(hora, "compositor", f"picture look: {classe} p={p:.3f} ({motivo})")


def janela(hora, looks):
    return linha(hora, "stream", f"video fps target=4 sent=2.0 (10frames/5.1s) kbps=107 late=0 full=false look={looks}/3.44/7.61ms latch=153/0.31/1.42ms")


def bloco_de(*meio):
    d = tempfile.mkdtemp()
    escreve(d, "ride-20261108-100000.log", projecao(*meio))
    return ler_registro.projecoes(ler_registro.ler([d]))[0]


class LeiturasTest(unittest.TestCase):
    def test_the_lines_come_with_class_p_reason_and_skipped_count(self):
        b = bloco_de(look("10:01:00.000", "MAP", 0.942, "p under 0.98; 3 looks not written"))
        r = olhadas.leituras(b)
        self.assertEqual(1, len(r))
        self.assertEqual(("MAP", 0.942, 3), (r[0]["classe"], r[0]["p"], r[0]["puladas"]))

    def test_one_look_not_written_in_the_singular_also_counts(self):
        b = bloco_de(look("10:01:00.000", "MAP", 0.95, "p under 0.98; 1 look not written"))
        self.assertEqual(1, olhadas.leituras(b)[0]["puladas"])

    def test_the_black_picture_p_zero_is_not_a_low_reading(self):
        b = bloco_de(look("10:01:00.000", "UNSURE", 0.0, "was nothing"), look("10:01:01.000", "MAP", 0.999, "was UNSURE"),
                     look("10:01:02.000", "MAP", 0.979), look("10:01:03.000", "MAP", 0.980))
        self.assertEqual([0.979], [r["p"] for r in olhadas.baixas(olhadas.leituras(b))])  # 0.98 is not under 0.98
        t = olhadas.taxa(b)
        self.assertEqual((1, 1, 1), (t["baixas"], t["pretas"], t["incertas"]))

    def test_the_screen_of_aa_screen_before_each_look_and_the_divergence_are_told(self):
        b = bloco_de(linha("10:00:50.000", "aa", "aa-screen: nav (by the picture)"), look("10:01:00.000", "MAP", 0.95),
                     linha("10:01:10.000", "aa", "aa-screen: menu (by the picture)"), look("10:01:20.000", "MAP", 0.90),
                     look("10:01:30.000", "APPS", 0.96))
        t = olhadas.tabela(b)
        self.assertEqual(3, len(t))
        self.assertIn("tela do AA: mapa", t[0])
        self.assertNotIn("DIVERGE", t[0])
        self.assertIn("tela do AA: menu (DIVERGE do aa-screen)", t[1])
        self.assertIn("DIVERGE", t[2])  # APPS against a menu

    def test_a_look_before_any_aa_screen_line_has_no_screen_and_does_not_diverge(self):
        b = bloco_de(look("10:01:00.000", "MAP", 0.95))
        self.assertIn("tela do AA: sem aa-screen", olhadas.tabela(b)[0])
        self.assertNotIn("DIVERGE", olhadas.tabela(b)[0])

    def test_the_table_stops_at_the_limit_and_says_how_many_are_left(self):
        b = bloco_de(*[look(f"10:{m:02d}:{s:02d}.000", "MAP", 0.95) for m in range(1, 4) for s in range(0, 30)])
        t = olhadas.tabela(b)
        self.assertEqual(41, len(t))  # 40 readings listed and one line for the rest: the numbers are written out, not read from the module
        self.assertIn("mais 50 leituras", t[-1])


class TaxaTest(unittest.TestCase):
    def dezenas(self, n, p=0.85, extra="", classe="UNSURE"):
        """`n` readings the app is not sure of (the class UNSURE, p < 0.9: what alarms since 15/11/2026; before, a MAP at 0.95 was counted too)."""
        return [look(f"10:01:{k % 60:02d}.{k:03d}", classe, p, "p under 0.98" + extra) for k in range(n)]

    def test_the_rate_counts_the_written_and_the_skipped_over_the_looks_made(self):
        b = bloco_de(*self.dezenas(10, extra="; 5 looks not written"), janela("10:02:00.000", 400), janela("10:02:05.000", 600))
        t = olhadas.taxa(b)
        self.assertEqual((10, 50, 60, 1000), (t["escritas"], t["puladas"], t["baixas"], t["feitas"]))
        self.assertAlmostEqual(6.0, t["pct"])
        self.assertTrue(t["alarme"])

    def test_at_exactly_the_limit_there_is_no_alarm_and_above_it_there_is(self):
        # 30 not sure of 1000 looks = 3.0 %: not above; 31 of 1000 = 3.1 %: above
        b = bloco_de(*self.dezenas(10, extra="; 2 looks not written"), janela("10:02:00.000", 1000))
        t = olhadas.taxa(b)
        self.assertEqual(30, t["baixas"])
        self.assertAlmostEqual(3.0, t["pct"])
        self.assertFalse(t["alarme"])
        b = bloco_de(*self.dezenas(11, extra=""), janela("10:02:00.000", 1000), *self.dezenas(0))
        b2 = bloco_de(*self.dezenas(10, extra="; 2 looks not written"), look("10:03:00.000", "UNSURE", 0.85), janela("10:02:00.000", 1000))
        self.assertEqual(31, olhadas.taxa(b2)["baixas"])
        self.assertTrue(olhadas.taxa(b2)["alarme"])
        self.assertFalse(olhadas.taxa(b)["alarme"])  # 11 of 1000 = 1.1 %

    def test_the_good_run_of_08_11_is_not_an_alarm(self):
        # 156 not sure of 11239 looks (1.4 %): the J4 on a map with a call card over it
        b = bloco_de(*self.dezenas(55, extra="; 2 looks not written")[:55], janela("10:02:00.000", 11239))
        t = olhadas.taxa(b)
        self.assertLess(t["pct"], olhadas.LIMITE_PCT)
        self.assertFalse(t["alarme"])

    def test_fewer_than_ten_not_sure_never_alarm_even_in_a_short_run(self):
        b = bloco_de(*self.dezenas(9), janela("10:02:00.000", 100))  # 9 of 100 = 9 %
        self.assertFalse(olhadas.taxa(b)["alarme"])
        b = bloco_de(*self.dezenas(10), janela("10:02:00.000", 100))
        self.assertTrue(olhadas.taxa(b)["alarme"])

    def test_without_video_windows_the_rate_is_per_minute_of_projection(self):
        # the projection helper runs 10:00:01 to 10:09:00 = ~9 min: 63 not sure is 7.0 per minute, 70 is above
        b = bloco_de(*self.dezenas(30, extra="; 1 look not written"))  # 60 -> 6.7 per minute
        t = olhadas.taxa(b)
        self.assertIsNone(t["pct"])
        self.assertAlmostEqual(60 / ((8 * 60 + 59) / 60), t["por_minuto"], places=3)
        self.assertFalse(t["alarme"])
        b = bloco_de(*self.dezenas(35, extra="; 1 look not written"))  # 70 -> 7.8 per minute
        self.assertTrue(olhadas.taxa(b)["alarme"])

    def test_the_text_names_the_unit_of_the_rate(self):
        b = bloco_de(*self.dezenas(10, extra="; 5 looks not written"), janela("10:02:00.000", 1000))
        self.assertIn("60 leituras sem certeza (p < 0,90; 10 escritas e 50 que o app disse não ter escrito) em 1000 leituras: 6,0 % (limite 3 %), a pior p=0,850; mais 0 na faixa de 0,90 a 0,98 (só informação, não alarma)",
                      olhadas.texto_da_taxa(olhadas.taxa(b)))
        b = bloco_de(*self.dezenas(35, extra="; 1 look not written"))
        self.assertIn("por minuto (limite 7)", olhadas.texto_da_taxa(olhadas.taxa(b)))


class SecaoEProblemasTest(unittest.TestCase):
    def test_a_run_without_low_readings_is_silent(self):
        b = bloco_de(look("10:01:00.000", "UNSURE", 0.0, "was nothing"), look("10:01:01.000", "MAP", 0.999, "was UNSURE"))
        self.assertEqual([], olhadas.secao([b]))
        self.assertEqual([], olhadas.problemas([b]))

    def test_the_alarm_goes_to_the_problems_with_the_run_named_and_the_section_lists_the_table(self):
        b = bloco_de(*[look(f"10:01:{k:02d}.000", "UNSURE", 0.85) for k in range(12)], janela("10:02:00.000", 100))
        p = olhadas.problemas([b])
        self.assertEqual(1, len(p))
        self.assertIn("ficou sem certeza demais", p[0])
        self.assertIn("ride-20261108-100000.log", p[0])
        s = olhadas.secao([b])
        self.assertEqual(1 + 12, len(s))

    def test_the_reader_puts_the_alarm_in_the_problems_and_the_table_in_its_own_section(self):
        d = tempfile.mkdtemp()
        escreve(d, "ride-20261108-100000.log", projecao(*[look(f"10:01:{k:02d}.000", "UNSURE", 0.85) for k in range(12)], janela("10:02:00.000", 100)))
        achados = ler_registro.problemas(ler_registro.ler([d]))
        self.assertTrue([a for a in achados if "sem certeza demais" in a], achados)
        import subprocess
        feito = subprocess.run([sys.executable, "-I", str(AQUI / "ler_registro.py"), d, "--tudo"], capture_output=True, text=True, encoding="utf-8")
        self.assertIn("LEITURAS DO CLASSIFICADOR DE IMAGEM", feito.stdout)
        self.assertEqual(1, feito.returncode)


class AlarmeSoComUnsureTest(unittest.TestCase):
    """The alarm counts the class UNSURE (p < 0.9, what the app keeps the last screen for), not the audit line (p < 0.98); the band in between is information.
    The three projections are the ones of the study of 15/11/2026, from the collection of 14/11: the picture lines are copied, the numbers of `look=` are the sums of its `video fps` lines."""

    # 12/11 19:56, a map at night just after it appears: real lines (class, p, reason), 157 looks made
    NOITE_RURAL = [
        ("19:56:23.900", "UNSURE", 0.000, "was nothing"), ("19:56:26.301", "UNSURE", 0.890, "p under 0.98"), ("19:56:27.310", "UNSURE", 0.883, "p under 0.98; 3 looks not written"),
        ("19:56:27.824", "MAP", 0.981, "was UNSURE; 1 looks not written"), ("19:56:28.880", "MAP", 0.930, "p under 0.98"), ("19:56:30.404", "MAP", 0.905, "p under 0.98; 1 looks not written"),
        ("19:56:31.426", "UNSURE", 0.897, "was MAP, p under 0.98; 3 looks not written"), ("19:56:32.452", "MAP", 0.909, "was UNSURE, p under 0.98; 3 looks not written"),
        ("19:56:33.472", "MAP", 0.902, "p under 0.98; 3 looks not written"), ("19:56:33.982", "UNSURE", 0.891, "was MAP, p under 0.98; 1 looks not written"),
        ("19:56:35.017", "MAP", 1.000, "was UNSURE; 3 looks not written"), ("19:56:37.053", "MAP", 0.971, "p under 0.98"), ("19:56:37.331", "UNSURE", 0.000, "was MAP"),
        ("19:56:38.094", "MENU", 1.000, "was UNSURE"),
    ]

    def bloco(self, linhas, feitas):
        return bloco_de(*[linha(h, "compositor", f"picture look: {c} p={p:.3f} ({m})") for h, c, p, m in linhas], janela("20:00:00.000", feitas))

    def test_the_map_read_at_0_979_is_not_a_not_sure_reading_13_11_08_31(self):
        # Waze with the dark map, stopped: MAP p 0.963 to 0.980 for 43 s, 29 written lines with 3 left out each (116 under 0.98) of 159 looks: 73 % by the old count
        linhas = [("08:31:21.854", "UNSURE", 0.0, "was nothing"), ("08:31:26.014", "MAP", 0.998, "was UNSURE")]
        linhas += [(f"08:31:{31 + k // 20:02d}.{(k % 20) * 40:03d}", "MAP", 0.963 + (k % 17) * 0.001, "p under 0.98; 3 looks not written") for k in range(29)]
        t = olhadas.taxa(self.bloco(linhas, 159))
        self.assertEqual(116, t["baixas"])
        self.assertAlmostEqual(73.0, 100.0 * t["baixas"] / t["feitas"], places=0)  # what the alarm said
        self.assertEqual((0, 116), (t["sem_certeza"], t["faixa"]))
        self.assertAlmostEqual(0.0, t["pct"])
        self.assertFalse(t["alarme"])
        self.assertEqual([], olhadas.problemas([self.bloco(linhas, 159)]))
        self.assertEqual(29, len(olhadas.tabela(self.bloco(linhas, 159))))  # the band still shows, as information
        self.assertIn("mais 116 na faixa de 0,90 a 0,98", olhadas.texto_da_taxa(t))

    def test_the_dark_rural_map_of_12_11_19_56_is_still_an_alarm_with_the_exact_count(self):
        b = self.bloco(self.NOITE_RURAL, 157)
        t = olhadas.taxa(b)
        self.assertEqual(27, t["baixas"])  # the old count: 17.2 % of 157
        # 4 UNSURE lines (p 0.890 0.883 0.897 0.891) and the 10 looks left out of their class: the 3 declared by 27.310, the 1 by 27.824, the 3 by 32.452 and the 3 by 35.017 were UNSURE
        # (`was UNSURE` or no change); the ones declared by 31.426 and 33.982 (`was MAP`) were of the band. The old rule (the class of the declaring line) gave 11 and 7.0 %.
        self.assertEqual((4, 10, 14, 13), (t["sem_certeza_escritas"], t["sem_certeza_puladas"], t["sem_certeza"], t["faixa"]))
        self.assertAlmostEqual(100.0 * 14 / 157, t["pct"])
        self.assertTrue(t["alarme"])
        self.assertEqual(1, len(olhadas.problemas([b])))

    def test_the_spotify_night_of_14_11_20_49_is_still_an_alarm_and_says_its_audit_was_cut(self):
        # an UNSURE song for minutes (a line a second with 3 left out), a lighter one in the band, 12 069 looks, and the app's 300-line limit at 21:03:06
        linhas = [(f"20:58:{k // 1:02d}.000", "UNSURE", 0.69, "p under 0.98; 3 looks not written") for k in range(60)]
        linhas += [(f"20:59:{k:02d}.000", "UNSURE", 0.7, "p under 0.98; 3 looks not written") for k in range(60)]
        linhas += [(f"21:00:{k:02d}.000", "APPS", 0.95, "p under 0.98; 1 looks not written") for k in range(30)]
        corte = linha("21:03:06.222", "compositor", "picture look: 300 lines written, no more in this session")
        b = bloco_de(*[linha(h, "compositor", f"picture look: {c} p={p:.3f} ({m})") for h, c, p, m in linhas], corte, janela("21:40:00.000", 12069))
        t = olhadas.taxa(b)
        self.assertEqual((480, 60), (t["sem_certeza"], t["faixa"]))
        self.assertAlmostEqual(100.0 * 480 / 12069, t["pct"])
        self.assertTrue(t["alarme"])
        self.assertIn("a auditoria do app parou às 21:03:06 (300 linhas escritas", olhadas.problemas([b])[0])
        self.assertEqual(1, len(olhadas.avisos([b])))
        self.assertIn("a conta dali em diante está incompleta", olhadas.avisos([b])[0])

    def test_the_band_alone_never_alarms_however_big_it_is(self):
        b = bloco_de(*[look(f"10:{m:02d}:{s:02d}.000", "MAP", 0.95, "p under 0.98; 3 looks not written") for m in range(1, 4) for s in range(0, 60)], janela("10:05:00.000", 1000))
        t = olhadas.taxa(b)
        self.assertEqual((0, 720), (t["sem_certeza"], t["faixa"]))  # 180 written and 540 left out: 72 % of the looks by the old count
        self.assertFalse(t["alarme"])
        self.assertEqual([], olhadas.problemas([b]))
        self.assertTrue(olhadas.secao([b]))  # still listed, as information
        self.assertIn("(faixa 0,90 a 0,98)", olhadas.tabela(b)[0])

    def test_the_line_between_unsure_and_the_band_is_the_class_not_the_printed_p(self):
        # the app is UNSURE under 0.9 and the log prints p with 3 decimals: a MAP p=0.900 is a sure class, an UNSURE p=0.899 is not
        b = bloco_de(look("10:01:00.000", "MAP", 0.900), look("10:01:01.000", "UNSURE", 0.899), janela("10:02:00.000", 100))
        t = olhadas.taxa(b)
        self.assertEqual((1, 1), (t["sem_certeza"], t["faixa"]))

    def test_looks_left_out_are_of_the_class_the_line_says_it_was(self):
        # the line that changes the class carries the ones left out before it, of the old class
        b = bloco_de(look("10:01:00.000", "UNSURE", 0.8, "was nothing, p under 0.98"), look("10:01:09.000", "MAP", 0.95, "was UNSURE, p under 0.98; 7 looks not written"),
                     look("10:01:19.000", "UNSURE", 0.8, "was MAP, p under 0.98; 5 looks not written"), look("10:01:29.000", "UNSURE", 0.8, "p under 0.98; 2 looks not written"))
        r = olhadas.leituras(b)
        self.assertEqual([0, 7, 0, 2], [x["puladas_incertas"] for x in r])
        self.assertEqual([0, 7, 5, 2], [x["puladas"] for x in r])
        t = olhadas.taxa(b)
        self.assertEqual(3 + 9, t["sem_certeza"])  # 3 UNSURE lines and 7 + 2 left out; the 5 after the MAP were MAP
        self.assertEqual(1 + 5, t["faixa"])

    def test_a_projection_without_the_cut_has_no_warning_and_with_it_the_hour_and_the_limit_are_told(self):
        limpo = bloco_de(look("10:01:00.000", "MAP", 0.95))
        self.assertIsNone(olhadas.taxa(limpo)["cortada"])
        self.assertEqual([], olhadas.avisos([limpo]))
        cortado = bloco_de(look("10:01:00.000", "MAP", 0.95), linha("10:07:30.500", "compositor", "picture look: 3000 lines written, no more in this session"))
        self.assertEqual(["projeção de 10:00:01.000 (ride-20261108-100000.log): leituras do classificador — a auditoria do app parou às 10:07:30 (3000 linhas escritas, o limite dela): "
                          "a conta dali em diante está incompleta"], olhadas.avisos([cortado]))
        self.assertIn("a auditoria do app parou às 10:07:30", olhadas.texto_da_taxa(olhadas.taxa(cortado)))

    def test_the_reader_puts_the_cut_in_its_warnings_and_not_in_the_problems(self):
        d = tempfile.mkdtemp()
        escreve(d, "ride-20261108-100000.log", projecao(look("10:01:00.000", "MAP", 0.95), linha("10:07:30.500", "compositor", "picture look: 300 lines written, no more in this session")))
        import subprocess
        feito = subprocess.run([sys.executable, "-I", str(AQUI / "ler_registro.py"), d, "--tudo"], capture_output=True, text=True, encoding="utf-8")
        self.assertIn("AVISOS DA LEITURA", feito.stdout)
        avisos = feito.stdout.split("AVISOS DA LEITURA")[-1]
        self.assertIn("a auditoria do app parou às 10:07:30", avisos)
        self.assertNotIn("sem certeza demais", feito.stdout)


if __name__ == "__main__":
    unittest.main()
