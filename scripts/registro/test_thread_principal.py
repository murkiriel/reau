"""Testes do ler_registro.py para as linhas `[mainthread]` do vigia da thread principal (0.4.0): a linha do tempo e os PROBLEMAS a partir de 3 s.

    python -B scripts\\registro\\test_thread_principal.py
"""
import sys
import tempfile
import unittest
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))
import ler_registro  # noqa: E402
import thread_principal  # noqa: E402
from test_ler_registro import escreve, linha, projecao  # noqa: E402

PILHA = "android.os.MessageQueue.nativePollOnce < android.os.Looper.loopOnce < android.os.Looper.loop < android.app.ActivityThread.main < java.lang.reflect.Method.invoke"


def lido(texto):
    d = Path(tempfile.mkdtemp())
    escreve(d, "ride-20261125-100000.log", texto)
    linhas = ler_registro.ler([d])
    return "\n".join(ler_registro.linha_do_tempo(linhas)), ler_registro.problemas(linhas)


def esperando(hora, s, pilha=PILHA):
    return linha(hora, "mainthread", f"main thread not answering for {s} s, at: {pilha}")


def voltou(hora, s):
    return linha(hora, "mainthread", f"main thread answered again after {s} s")


class VigiaDaThreadPrincipal(unittest.TestCase):
    def test_o_travamento_aparece_na_linha_do_tempo_com_a_duracao_e_onde_estava(self):
        t, _ = lido(projecao(esperando("10:02:00.000", "1.0"), esperando("10:02:02.000", "3.1"), voltou("10:02:04.500", "4.6")))
        self.assertIn("10:02:00.000  a thread principal do app está presa há 1.0 s e ainda não respondeu; parada em: android.os.MessageQueue.nativePollOnce < android.os.Looper.loopOnce < android.os.Looper.loop < ...", t)
        self.assertIn("há 3.1 s", t)
        self.assertIn("10:02:04.500  o app travou a thread principal por 4.6 s", t)

    def test_travamento_de_mais_de_3_s_entra_em_problemas_com_a_duracao_final_e_a_pilha(self):
        _, p = lido(projecao(esperando("10:02:00.000", "1.0"), esperando("10:02:02.100", "3.1"), esperando("10:02:04.000", "5.0"), voltou("10:02:06.000", "6.2")))
        self.assertEqual(1, len(p), p)
        self.assertEqual("10:02:00.000: o app travou a thread principal por 6.2 s; parada em: android.os.MessageQueue.nativePollOnce < android.os.Looper.loopOnce < android.os.Looper.loop < ...", p[0])

    def test_travamento_curto_fica_so_na_linha_do_tempo(self):
        t, p = lido(projecao(esperando("10:02:00.000", "1.2"), voltou("10:02:01.800", "1.8"), linha("10:03:00.000", "mainthread", "main thread answered again after 2.9 s")))
        self.assertIn("o app travou a thread principal por 1.8 s", t)
        self.assertIn("o app travou a thread principal por 2.9 s", t)
        self.assertEqual([], p)

    def test_o_de_exatos_3_s_conta_e_o_de_2_9_nao(self):
        self.assertEqual(1, len(thread_principal.problemas(_bloco(voltou("10:00:00.000", "3.0")))))
        self.assertEqual([], thread_principal.problemas(_bloco(voltou("10:00:00.000", "2.9"))))

    def test_sem_a_linha_da_volta_o_problema_diz_que_ela_nao_foi_registrada(self):
        _, p = lido(projecao(esperando("10:02:00.000", "1.0"), esperando("10:02:02.000", "3.2"), esperando("10:02:04.000", "5.1"), desligada=False))
        self.assertEqual(1, len(p), p)
        self.assertIn("por pelo menos 5.1 s e a volta não foi registrada", p[0])
        self.assertIn("android.os.MessageQueue.nativePollOnce", p[0])

    def test_dois_travamentos_sao_dois_problemas_e_o_curto_entre_eles_nao_conta(self):
        _, p = lido(projecao(
            esperando("10:01:00.000", "3.0"), voltou("10:01:03.400", "3.4"),
            esperando("10:02:00.000", "1.0"), voltou("10:02:01.500", "1.5"),
            esperando("10:03:00.000", "5.0"), voltou("10:03:07.000", "7.0")))
        self.assertEqual(2, len(p), p)
        self.assertTrue(p[0].startswith("10:01:00.000") and "3.4 s" in p[0])
        self.assertTrue(p[1].startswith("10:03:00.000") and "7.0 s" in p[1])

    def test_a_linha_de_slow_frame_da_interface_nao_vira_travamento(self):
        t, p = lido(projecao(linha("10:02:00.000", "ui", "slow frame: 450 ms during running")))
        self.assertNotIn("thread principal", t)
        self.assertEqual([], p)

    def test_a_linha_de_outra_etiqueta_com_o_mesmo_texto_nao_conta(self):
        _, p = lido(projecao(linha("10:02:00.000", "wifi", "main thread answered again after 9.0 s")))
        self.assertEqual([], p)


def _bloco(*textos):
    return [LinhaFalsa(t) for t in textos]


class LinhaFalsa:
    """Só o que o módulo lê de uma linha: hora, etiqueta e texto."""

    def __init__(self, bruta):
        _, resto = bruta.split("[", 1)
        self.etiqueta, self.texto = resto.rstrip("\n").split("] ", 1)
        self.hora = bruta.split()[0]


if __name__ == "__main__":
    unittest.main()
