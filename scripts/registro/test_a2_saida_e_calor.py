"""A2 of the 0.4.0 round: two things the reader told badly in the run of 25/11/2026 (synthetic logs only).

1. The projection that ends because the app started again (`==== process start:`) said only "o app recomeçou". The next process writes `[exit] past exit: <reason> (<code>) at <time>`
   (the reason Android gave for the end of the one before), and the reader now says it: "o app recomeçou: pacote atualizado".
2. The battery that rises 8 °C with the screen on and no dash (`bike=none`) is the Android Auto decoding on a lit screen, not a defect: it is an informative line in AVISOS DA LEITURA.
   The rise seen with the dash joined (or in a log without `bike=`), and the 44 °C, still alarm.

    python3 -B -m unittest test_a2_saida_e_calor     (from scripts/registro)
"""
import sys
import unittest
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))
import ler_registro  # noqa: E402
from test_a8_leitor import LIGAR, cabecalho, linha, lido, pasta_de  # noqa: E402

ULTIMA = linha("10:04:00.000", "aa", "[AA] Android Auto conectado")


def saida(motivo, codigo, quando="2026-10-04 10:04:01.000"):
    """The line the next process writes: the exit 1 s after the last line of the projection (as in 25/11, 06:02:23.609 and 06:02:24.550)."""
    return linha("11:00:00.100", "exit", f"past exit: {motivo} ({codigo}) at {quando}, process com.reau.app pid 4880, was cached (400), \"stop com.reau.app due to installPackageLI\"")


def sem_fim(*arquivos):
    [primeira, *_] = ler_registro.projecoes(lido(pasta_de(*arquivos)))
    return primeira.sem_fim


class MotivoDoFimDoProcesso(unittest.TestCase):
    MORREU = cabecalho() + LIGAR + ULTIMA

    def test_o_motivo_da_linha_do_processo_seguinte_vai_junto_do_recomecou(self):
        voltou = cabecalho("11:00:00.000") + saida("package updated", 16)
        self.assertEqual("o app recomeçou: pacote atualizado", sem_fim(self.MORREU, voltou))
        tempo = "\n".join(ler_registro.linha_do_tempo(lido(pasta_de(self.MORREU, voltou))))
        self.assertIn("projeção sem fim registrado (o app recomeçou: pacote atualizado): vale até o último evento dela", tempo)

    def test_cada_motivo_sai_com_as_palavras_do_leitor(self):
        for codigo, nome, esperado in ((3, "low memory", "falta de memória"), (4, "crash", "falha"), (10, "user requested", "pedido do usuário"),
                                       (11, "user stopped", "parado pelo usuário"), (6, "ANR", "app sem resposta (ANR)")):
            voltou = cabecalho("11:00:00.000") + saida(nome, codigo)
            self.assertEqual(f"o app recomeçou: {esperado}", sem_fim(self.MORREU, voltou), nome)

    def test_sem_a_linha_a_frase_fica_como_estava(self):
        self.assertEqual("o app recomeçou", sem_fim(self.MORREU, cabecalho("11:00:00.000") + linha("11:00:01.000", "ui", "settings in use: density=180")))

    def test_a_saida_de_um_processo_mais_velho_nao_vale(self):
        # 09:00 is before the last line of the projection (10:04:00): it is an older process; the one at 10:04:01 is this one.
        voltou = (cabecalho("11:00:00.000") + linha("11:00:00.100", "exit", "past exit: user requested (10) at 2026-10-04 09:00:00.000, process com.reau.app pid 1, was cached (400)")
                  + linha("11:00:00.200", "exit", "past exit: low memory (3) at 2026-10-04 10:04:01.000, process com.reau.app pid 2, was cached (400)"))
        self.assertEqual("o app recomeçou: falta de memória", sem_fim(self.MORREU, voltou))

    def test_a_saida_so_de_mais_velho_deixa_a_frase_como_estava(self):
        voltou = cabecalho("11:00:00.000") + linha("11:00:00.100", "exit", "past exit: user requested (10) at 2026-10-04 09:00:00.000, process com.reau.app pid 1, was cached (400)")
        self.assertEqual("o app recomeçou", sem_fim(self.MORREU, voltou))

    def test_uma_sequencia_de_saidas_do_mesmo_motivo_vale_pela_hora_da_ultima(self):
        voltou = cabecalho("11:00:00.000") + linha("11:00:00.100", "exit", "past exit: package updated (16) ×2 from 2026-10-04 09:10:00.000 to 2026-10-04 10:04:01.000, process com.reau.app pid 4880, was cached (400)")
        self.assertEqual("o app recomeçou: pacote atualizado", sem_fim(self.MORREU, voltou))

    def test_uma_saida_depois_do_comeco_do_processo_que_a_conta_nao_vale(self):
        voltou = cabecalho("11:00:00.000") + saida("low memory", 3, quando="2026-10-04 11:30:00.000")
        self.assertEqual("o app recomeçou", sem_fim(self.MORREU, voltou))

    def test_a_linha_de_um_processo_depois_do_seguinte_nao_vale(self):
        # The exit line below belongs to the process that starts at 12:00, not to the one at 11:00 that ended the projection.
        voltou = cabecalho("11:00:00.000") + linha("11:00:01.000", "ui", "settings in use: density=180") + cabecalho("12:00:00.000") + linha(
            "12:00:00.100", "exit", "past exit: user requested (10) at 2026-10-04 10:04:01.000, process com.reau.app pid 1, was cached (400)")
        self.assertEqual("o app recomeçou", sem_fim(self.MORREU, voltou))

    def test_um_codigo_que_o_leitor_nao_conhece_diz_o_numero(self):
        voltou = cabecalho("11:00:00.000") + linha("11:00:00.100", "exit", "past exit: reason 99 (99) at 2026-10-04 10:04:01.000, process com.reau.app pid 1, was cached (400)")
        self.assertEqual("o app recomeçou: motivo 99", sem_fim(self.MORREU, voltou))


def olhada(hora, graus, bike=None, tela="on", carregando="no"):
    campo = "" if bike is None else f" bike={bike}"
    return linha(hora, "device", f"device battery=80% charging={carregando} temp={graus}C thermal=none screen={tela}{campo} [30 s]")


def projecao_com(*meio):
    return cabecalho() + LIGAR + "".join(meio) + "10:30:00.000  +  1000ms  ---- projeção desligada: projection ----\n"


class CalorSemPainel(unittest.TestCase):
    PAINEL = "-55dBm/2412MHz/72Mbps"

    def ler(self, *meio):
        d = pasta_de(projecao_com(*meio))
        linhas = lido(d)
        calor = lambda lista: [x for x in lista if "temperatura" in x]
        return calor(ler_registro.problemas(linhas)), calor(ler_registro.avisos_da_leitura([d], linhas))

    def test_a_subida_com_a_tela_acesa_e_sem_painel_e_so_informacao(self):
        # 25/11, 06:21 to 06:30: 33 to 38 °C with the screen on and `bike=none` (30 °C at the start of the run).
        problemas, avisos = self.ler(olhada("10:01:00.000", "30.0", "none"), olhada("10:10:00.000", "34.0", "none"), olhada("10:15:00.000", "38.0", "none"))
        self.assertEqual([], problemas)
        self.assertEqual(1, len(avisos), avisos)
        self.assertIn("temperatura subiu de 30.0 °C a 38.0 °C", avisos[0])
        self.assertIn("com a tela acesa e sem painel", avisos[0])

    def test_a_subida_com_o_painel_unido_continua_problema(self):
        problemas, avisos = self.ler(olhada("10:01:00.000", "30.0", self.PAINEL), olhada("10:10:00.000", "34.0", self.PAINEL), olhada("10:15:00.000", "38.0", self.PAINEL))
        self.assertEqual(1, len(problemas), problemas)
        self.assertIn("temperatura subindo: bateria de 30.0 °C a 38.0 °C (sem carregador, tela ligada)", problemas[0])
        self.assertEqual([], avisos)

    def test_o_registro_sem_o_campo_bike_continua_problema(self):
        problemas, avisos = self.ler(olhada("10:01:00.000", "30.0"), olhada("10:15:00.000", "38.0"))
        self.assertEqual(1, len(problemas), problemas)
        self.assertEqual([], avisos)

    def test_a_subida_que_se_ve_so_sem_painel_depois_de_um_trecho_com_painel_e_informacao(self):
        # 25/11 06:15 to 06:30: 30 → 33 °C with the dash, then 33 → 38 °C without it, in one stretch.
        problemas, avisos = self.ler(olhada("10:01:00.000", "30.0", self.PAINEL), olhada("10:05:00.000", "33.0", self.PAINEL),
                                     olhada("10:08:00.000", "33.0", "none"), olhada("10:15:00.000", "38.0", "none"))
        self.assertEqual([], problemas)
        self.assertEqual(1, len(avisos), avisos)

    def test_a_subida_que_se_ve_com_o_painel_unido_e_problema_mesmo_com_o_comeco_sem_ele(self):
        problemas, avisos = self.ler(olhada("10:01:00.000", "30.0", "none"), olhada("10:05:00.000", "34.0", "none"), olhada("10:15:00.000", "38.0", self.PAINEL))
        self.assertEqual(1, len(problemas), problemas)
        self.assertEqual([], avisos)

    def test_passar_de_44_graus_sem_painel_continua_problema(self):
        problemas, _ = self.ler(olhada("10:01:00.000", "40.0", "none"), olhada("10:05:00.000", "45.0", "none"))
        self.assertEqual(1, len(problemas), problemas)
        self.assertIn("passou de 44 °C", problemas[0])

    def test_a_subida_pequena_sem_painel_nao_diz_nada(self):
        problemas, avisos = self.ler(olhada("10:01:00.000", "33.0", "none"), olhada("10:15:00.000", "38.0", "none"))
        self.assertEqual(([], []), (problemas, avisos))

    def test_a_linha_informativa_nao_vira_problema_no_codigo_de_saida(self):
        d = pasta_de(projecao_com(olhada("10:01:00.000", "30.0", "none"), olhada("10:15:00.000", "38.0", "none")))
        self.assertEqual([], [p for p in ler_registro.problemas(lido(d)) if "temperatura" in p])


if __name__ == "__main__":
    unittest.main()
