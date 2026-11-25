"""Tests of the reader for the choice in the menu that the picture takes to the map (item of 15/11/2026, round 6). The time from the touch to the `nav` is almost all the
Android Auto opening the chosen app; the wrong crop is only from the first `picture look: MAP` after the choice to the switch to `nav`. Synthetic logs, plus one test against a
real collection when the REAU_COLETA environment variable names one."""
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ler_registro  # noqa: E402
from test_ler_registro import escreve, linha, projecao  # noqa: E402


def _tela(hora, texto):
    return linha(hora, "aa-screen", f"aa-screen: {texto}")


def _olhada(hora, classe="MAP", p="0.999", motivo="was UNSURE"):
    return linha(hora, "compositor", f"picture look: {classe} p={p} ({motivo})")


SELECAO = "apps (a select in the menu)"
NAV = "nav (by the picture: a map, from apps)"


def _pasta(*meio):
    d = Path(tempfile.mkdtemp())
    escreve(d, "ride-20261111-100000.log", projecao(*meio))
    return d


def _leitura(*meio):
    pasta = _pasta(*meio)
    return pasta, ler_registro.ler([pasta])


class RecorteErradoDoMenuAoMapa(unittest.TestCase):
    def avisos(self, *meio):
        pasta, linhas = _leitura(*meio)
        return ler_registro.avisos_da_leitura([pasta], linhas)

    def problemas(self, *meio):
        return [p for p in ler_registro.problemas(_leitura(*meio)[1]) if "recorte dos apps sobre o mapa" in p]

    def test_the_opening_and_the_wrong_crop_are_told_apart(self):
        meio = (_tela("10:01:00.000", SELECAO), _olhada("10:01:00.600"), _tela("10:01:00.700", NAV))
        recorte = [a for a in self.avisos(*meio) if "levou ao mapa" in a]
        self.assertEqual(1, len(recorte), recorte)
        self.assertIn("a escolha no menu levou ao mapa 1 de 1 vezes (10:01:00,0 por 0,1 s)", recorte[0])
        self.assertIn("recorte errado", recorte[0])
        self.assertNotIn("teto", recorte[0])
        abertura = [a for a in self.avisos(*meio) if "abertura do app escolhido no menu (" in a]
        self.assertEqual(1, len(abertura), abertura)
        self.assertIn("(10:01:00,0 por 0,6 s)", abertura[0])
        self.assertEqual([], self.problemas(*meio))

    def test_without_the_looks_in_the_log_the_total_is_told_as_a_ceiling(self):
        a = [x for x in self.avisos(_tela("10:01:00.000", SELECAO), _tela("10:01:00.700", NAV)) if "levou ao mapa" in x]
        self.assertEqual(1, len(a), a)
        self.assertIn("a escolha no menu levou ao mapa 1 de 1 vezes (10:01:00,0 por 0,7 s)", a[0])
        self.assertIn("sem as olhadas no registro", a[0])
        self.assertIn("teto", a[0])
        self.assertFalse([x for x in self.avisos(_tela("10:01:00.000", SELECAO), _tela("10:01:00.700", NAV)) if "abertura do app escolhido no menu (" in x])

    def test_a_ceiling_is_never_a_problem_even_above_one_second(self):
        self.assertEqual([], self.problemas(_tela("10:01:00.000", SELECAO), _tela("10:01:03.000", NAV)))

    def test_a_wrong_crop_of_1_2_s_is_a_problem(self):
        p = self.problemas(_tela("10:01:00.000", SELECAO), _olhada("10:01:00.300"), _tela("10:01:01.500", NAV))
        self.assertEqual(1, len(p), p)
        self.assertIn("o painel mostrou o recorte dos apps sobre o mapa por 1,2 s", p[0])

    def test_a_wrong_crop_of_exactly_1_s_is_not_a_problem(self):
        self.assertEqual([], self.problemas(_tela("10:01:00.000", SELECAO), _olhada("10:01:00.300"), _tela("10:01:01.300", NAV)))

    def test_a_long_opening_with_a_short_crop_is_not_a_problem(self):
        self.assertEqual([], self.problemas(_tela("10:01:00.000", SELECAO), _olhada("10:01:03.000"), _tela("10:01:03.100", NAV)))

    def test_a_map_look_from_before_the_choice_does_not_count(self):
        # The old look is not the first look that sees the map after the choice: with no other one, the selection falls back to the ceiling.
        meio = (_olhada("10:00:58.000"), _tela("10:01:00.000", SELECAO), _tela("10:01:00.700", NAV))
        a = [x for x in self.avisos(*meio) if "levou ao mapa" in x]
        self.assertEqual(1, len(a), a)
        self.assertIn("teto", a[0])
        self.assertFalse([x for x in self.avisos(*meio) if "abertura do app escolhido no menu (" in x])
        self.assertEqual([], self.problemas(_olhada("10:00:50.000"), _tela("10:01:00.000", SELECAO), _tela("10:01:02.000", NAV)))

    def test_a_look_of_another_class_does_not_count(self):
        a = [x for x in self.avisos(_tela("10:01:00.000", SELECAO), _olhada("10:01:00.300", "APPS"), _tela("10:01:00.700", NAV)) if "levou ao mapa" in x]
        self.assertIn("teto", a[0])

    def test_a_map_look_after_the_switch_does_not_count(self):
        a = [x for x in self.avisos(_tela("10:01:00.000", SELECAO), _tela("10:01:00.700", NAV), _olhada("10:01:00.900")) if "levou ao mapa" in x]
        self.assertIn("teto", a[0])

    def test_the_first_map_look_after_the_choice_is_the_one_measured(self):
        meio = (_tela("10:01:00.000", SELECAO), _olhada("10:01:00.400"), _olhada("10:01:00.500", "MAP", "0.950", "p under 0.98"), _tela("10:01:00.600", NAV))
        a = [x for x in self.avisos(*meio) if "levou ao mapa" in x]
        self.assertIn("(10:01:00,0 por 0,2 s)", a[0])

    def test_measured_and_ceiling_selections_go_in_their_own_lines(self):
        meio = (_tela("10:01:00.000", SELECAO), _olhada("10:01:00.500"), _tela("10:01:00.600", NAV),
                _tela("10:02:00.000", SELECAO), _tela("10:02:00.900", NAV))
        a = [x for x in self.avisos(*meio) if "levou ao mapa" in x]
        self.assertEqual(2, len(a), a)
        self.assertIn("levou ao mapa 1 de 2 vezes (10:01:00,0 por 0,1 s)", a[0])
        self.assertIn("levou ao mapa 1 de 2 vezes (10:02:00,0 por 0,9 s)", a[1])
        self.assertIn("teto", a[1])
        self.assertNotIn("teto", a[0])

    def test_the_retracted_selection_is_untouched(self):
        a = self.avisos(_tela("10:01:00.000", SELECAO), _olhada("10:01:00.300", "MENU"), _tela("10:01:01.060", "menu (by the picture: the app menu, from apps)"))
        self.assertTrue([x for x in a if "provisório desmentido pela imagem 1 de 1 vezes (10:01:00,0 por 1,1 s)" in x])
        self.assertFalse([x for x in a if "levou ao mapa" in x])


@unittest.skipUnless(os.environ.get("REAU_COLETA"), "REAU_COLETA não definida: sem coleta real para conferir")
class ComUmaColetaReal(unittest.TestCase):
    def test_the_cases_of_08_to_14_11_have_a_wrong_crop_of_at_most_0_3_s(self):
        coleta = os.environ["REAU_COLETA"]
        medidos = []
        for bloco in ler_registro.projecoes(ler_registro.ler([coleta])):
            if not any(f"ride-202611{dia:02d}-" in bloco[0].arquivo for dia in range(8, 15)):
                continue
            ao_mapa, _ = ler_registro._apps_ate_o_mapa(bloco)
            medidos += [(x.hora, abertura, recorte) for x, abertura, recorte, _ in ao_mapa if recorte is not None]
        self.assertTrue(medidos, "nenhuma escolha no menu levou ao mapa com as olhadas nesta coleta")
        for hora, abertura, recorte in medidos:
            self.assertLessEqual(recorte, 0.3, f"{hora}: recorte {recorte} s (abertura {abertura} s)")
            self.assertGreater(abertura, 0)
        self.assertEqual([], [p for p in ler_registro.problemas(ler_registro.ler([coleta])) if "recorte dos apps sobre o mapa" in p])


if __name__ == "__main__":
    unittest.main()
