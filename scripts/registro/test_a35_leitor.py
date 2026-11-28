"""Plan 35, step A, of the run of 25/11/2026: three defects of the reader, with synthetic logs only (no network name, address or coordinate).

1. A `[exit] past exit: low memory (3) … was foreground service (125)` (the system killing the REau while it projected) was only "o app recomeçou: falta de
   memória" on the timeline: it is now a problem, "o sistema fechou o REau", with the time of the death and the reason, by the rule of `SystemClosedRide.kt`
   (the same reasons, importance 1..125, and not the rider's own "remove task").
2. The rise of the battery was measured from the lowest temperature of the whole stretch, even across a new process or a long hole between two looks in use
   (the battery cooled 38 → 34 °C in 17 min with the screen off, and the next lit look was compared with the 30 of 45 min before). A new process and a hole
   of 15 min or more now close the stretch; a real rise inside a run still alarms.
3. A recording is tied to the projection that was running at the instant of its name; the 60 s of slack only counts when no projection was (25/11, 08:28:51:
   the recording of the projection in course went to the one that began 25 s later, "227 %").

    python3 -B -m unittest test_a35_leitor     (from scripts/registro)
"""
import sys
import unittest
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))
import gravacoes  # noqa: E402
import ler_registro  # noqa: E402
from test_a2_saida_e_calor import olhada, projecao_com  # noqa: E402
from test_a8_leitor import LIGAR, cabecalho, linha, lido, pasta_de  # noqa: E402

ULTIMA = linha("10:04:00.000", "aa", "[AA] Android Auto conectado")
MORREU = cabecalho() + LIGAR + ULTIMA


def saida(motivo, codigo, importancia="foreground service (125)", quando="2026-10-04 10:04:01.000", descricao="", extra=""):
    """The line the next process writes about the exit before it (as ExitReasons.describe writes it)."""
    nome, grau = importancia.rsplit(" (", 1)
    depois = f', "{descricao}"' if descricao else ""
    return linha("11:00:00.100", "exit", f"past exit: {motivo} ({codigo}) at {quando}, process com.reau.app pid 4880, was {nome} ({grau.rstrip(')')}){extra}, "
                                         f"pss 62 MB, rss 162 MB{depois}")


def fechou(*linhas_de_saida):
    """The problems "o sistema fechou o REau" the reader finds in a log whose projection ended by a new process that wrote [linhas_de_saida]."""
    voltou = cabecalho("11:00:00.000") + "".join(linhas_de_saida)
    return [p for p in ler_registro.problemas(lido(pasta_de(MORREU, voltou))) if "o sistema fechou o REau" in p]


class SistemaFechouOReauTest(unittest.TestCase):
    def test_a_falta_de_memoria_com_o_servico_em_primeiro_plano_e_problema_com_a_hora_da_morte_e_o_motivo(self):
        # 25/11, 16:10:18: low memory (3), was foreground service (125).
        achados = fechou(saida("low memory", 3, quando="2026-10-04 10:04:01.350"))
        self.assertEqual(1, len(achados), achados)
        self.assertTrue(achados[0].startswith("10:04:01: o sistema fechou o REau — falta de memória (3)"), achados[0])
        self.assertIn("serviço em primeiro plano (125)", achados[0])
        self.assertIn("às 11:00:00", achados[0])

    def test_cada_motivo_do_sistema_vale_e_os_outros_nao(self):
        for codigo in (2, 3, 9, 10, 11, 12, 13, 14):
            self.assertEqual(1, len(fechou(saida("x", codigo))), codigo)
        for codigo in (0, 1, 4, 5, 6, 7, 8, 15, 16):
            self.assertEqual([], fechou(saida("x", codigo)), codigo)

    def test_a_importancia_decide_so_do_1_ao_125(self):
        self.assertEqual(1, len(fechou(saida("low memory", 3, "foreground (100)"))))
        self.assertEqual([], fechou(saida("low memory", 3, "visible (200)")))
        self.assertEqual([], fechou(saida("low memory", 3, "cached (400)")))
        self.assertEqual([], fechou(saida("low memory", 3, "importance 0 (0)")))

    def test_o_piloto_que_tirou_o_app_dos_recentes_nao_e_o_sistema(self):
        # 25/11, 15:56:13: user requested (10), cached (400), "[REMOVE TASK] remove task": the rider's own act.
        self.assertEqual([], fechou(saida("user requested", 10, "foreground service (125)", descricao="[REMOVE TASK] remove task")))
        self.assertEqual([], fechou(saida("user requested", 10, "foreground service (125)", descricao="stop com.reau.app due to swipeUpClean")))
        gerenciador = saida("user requested", 10, "foreground service (125)", descricao="stop com.reau.app due to a maker's battery manager")
        self.assertEqual(1, len(fechou(gerenciador)))

    def test_a_sequencia_de_saidas_do_mesmo_motivo_vale_pela_hora_da_ultima(self):
        achados = fechou(linha("11:00:00.100", "exit", "past exit: user stopped (11) ×2 from 2026-10-04 09:10:00.000 to 2026-10-04 10:04:01.000, "
                                                      "process com.reau.app pid 4880, was foreground service (125)"))
        self.assertEqual(1, len(achados), achados)
        self.assertTrue(achados[0].startswith("10:04:01: o sistema fechou o REau — parado pelo usuário (11)"), achados[0])

    def test_a_mesma_saida_contada_em_dois_arquivos_vale_uma_vez(self):
        voltou = cabecalho("11:00:00.000") + saida("low memory", 3)
        outra = cabecalho("12:00:00.000") + saida("low memory", 3)
        achados = [p for p in ler_registro.problemas(lido(pasta_de(MORREU, voltou, outra))) if "o sistema fechou o REau" in p]
        self.assertEqual(1, len(achados), achados)

    def test_a_linha_sem_a_importancia_nao_vale_e_o_motivo_sem_nome_conhecido_diz_o_numero(self):
        self.assertEqual([], fechou(linha("11:00:00.100", "exit", "past exit: low memory (3) at 2026-10-04 10:04:01.000, process com.reau.app pid 1")))
        achados = fechou(linha("11:00:00.100", "exit", "past exit: x (13) at 2026-10-04 10:04:01.000, process com.reau.app pid 1, "
                                                      "was foreground service (125)"))
        self.assertIn("outro motivo (13)", achados[0])

    def test_o_problema_entra_no_codigo_de_saida_e_a_linha_do_tempo_continua_dizendo_o_motivo(self):
        voltou = cabecalho("11:00:00.000") + saida("low memory", 3)
        d = pasta_de(MORREU, voltou)
        self.assertTrue([p for p in ler_registro.problemas(lido(d)) if "o sistema fechou o REau" in p])
        self.assertIn("(o app recomeçou: falta de memória)", "\n".join(ler_registro.linha_do_tempo(lido(d))))


class TrechoDeUsoTest(unittest.TestCase):
    def graus(self, *meio):
        """The (rise problems, informative rises) of a projection with [meio]."""
        d = pasta_de(projecao_com(*meio))
        linhas = lido(d)
        calor = lambda lista: [x for x in lista if "temperatura" in x]
        return calor(ler_registro.problemas(linhas)), calor(ler_registro.avisos_da_leitura([d], linhas))

    def test_a_subida_de_verdade_dentro_de_uma_corrida_continua_acusada(self):
        problemas, _ = self.graus(olhada("10:01:00.000", "30.0", "-55dBm/2412MHz/72Mbps"), olhada("10:08:00.000", "34.0", "-55dBm/2412MHz/72Mbps"),
                                  olhada("10:14:00.000", "38.0", "-55dBm/2412MHz/72Mbps"))
        self.assertEqual(1, len(problemas), problemas)

    def test_o_buraco_de_15_min_entre_olhadas_em_uso_fecha_o_trecho(self):
        # 25/11 06:42 to 06:59: screen off for 17 min, the battery cooled; the 38 °C of the next lit look is not a rise from the 30 °C of 45 min before.
        buraco = (olhada("10:01:00.000", "30.0", "none"), olhada("10:02:00.000", "31.0", "none"), olhada("10:03:00.000", "34.0", "none"),
                  olhada("10:20:00.000", "38.0", "none"))
        problemas, avisos = self.graus(*buraco)
        self.assertEqual(([], []), (problemas, avisos))
        # the same looks without the hole (14 min, under the limit) are still one stretch and still told
        junto = (buraco[0], buraco[1], buraco[2], olhada("10:17:00.000", "38.0", "none"))
        self.assertEqual(1, len(self.graus(*junto)[1]))

    def test_o_buraco_conta_so_entre_olhadas_em_uso_a_tela_apagada_no_meio_tambem_o_abre(self):
        meio = [olhada(f"10:{m:02d}:00.000", "34.0", tela="off") for m in range(5, 20)]
        problemas, avisos = self.graus(olhada("10:01:00.000", "30.0", "none"), olhada("10:03:00.000", "34.0", "none"), *meio,
                                       olhada("10:22:00.000", "38.0", "none"))
        self.assertEqual(([], []), (problemas, avisos))

    def test_um_processo_novo_fecha_o_trecho(self):
        texto = (cabecalho("10:00:00.000") + olhada("10:01:00.000", "30.0", "none") + olhada("10:02:00.000", "31.0", "none")
                 + cabecalho("10:03:00.000") + olhada("10:04:00.000", "38.0", "none"))
        d = pasta_de(texto)
        linhas = ler_registro.ler([d])
        self.assertEqual([[(30.0, True), (31.0, True)], [(38.0, True)]], ler_registro._trechos_de_uso(linhas))
        # control: the same looks with no new process are one stretch
        sem = ler_registro.ler([pasta_de(cabecalho("10:00:00.000") + olhada("10:01:00.000", "30.0", "none") + olhada("10:02:00.000", "31.0", "none")
                                         + olhada("10:04:00.000", "38.0", "none"))])
        self.assertEqual([[(30.0, True), (31.0, True), (38.0, True)]], ler_registro._trechos_de_uso(sem))

    def test_o_calor_da_carga_continua_fora_mesmo_depois_de_um_processo_novo(self):
        texto = (cabecalho("10:00:00.000") + linha("10:00:30.000", "system", "charger out") + cabecalho("10:02:00.000")
                 + olhada("10:03:00.000", "40.0", "none") + olhada("10:20:00.000", "34.0", "none"))
        linhas = ler_registro.ler([pasta_de(texto)])
        self.assertEqual([[(34.0, True)]], ler_registro._trechos_de_uso(linhas))


class GravacaoDaProjecaoEmCursoTest(unittest.TestCase):
    def blocos(self, *janelas):
        texto = cabecalho("08:00:00.000") + "".join(
            f"{a}  +  1000ms  ---- projeção ligada: projection ----\n{linha(a[:-3] + '500', 'aa', '[AA] Android Auto conectado')}"
            f"{b}  +  1000ms  ---- projeção desligada: projection ----\n" for a, b in janelas)
        return ler_registro.projecoes(lido(pasta_de(texto)))

    def nomes(self, blocos, *nomes):
        dado = gravacoes.atribuir([{"nome": n} for n in nomes], blocos)
        return [[g["nome"] for g in dado[id(b)]] for b in blocos]

    def test_a_gravacao_da_projecao_em_curso_nao_vai_para_a_que_comeca_25_s_depois(self):
        # 25/11: projections at 08:28:51 (until 08:28:59) and 08:29:16; recordings of 08:28:51 and 08:29:17.
        blocos = self.blocos(("08:28:51.312", "08:28:59.290"), ("08:29:16.448", "08:29:21.371"))
        achado = self.nomes(blocos, "reau_20261004_082851_377.aasession", "reau_20261004_082917_209.aasession")
        self.assertEqual([["reau_20261004_082851_377.aasession"], ["reau_20261004_082917_209.aasession"]], achado)

    def test_a_projecao_que_continua_e_mais_nova_ganha_quando_as_duas_contem_o_instante(self):
        blocos = self.blocos(("09:00:00.000", "09:30:00.000"), ("09:10:00.000", "09:20:00.000"))
        self.assertEqual([[], ["reau_20261004_091500_000.h264"]], self.nomes(blocos, "reau_20261004_091500_000.h264"))

    def test_sem_nenhuma_em_curso_vale_a_folga_de_60_s_e_a_mais_proxima_ganha(self):
        blocos = self.blocos(("10:00:20.000", "10:05:00.000"), ("10:00:50.000", "10:06:00.000"))
        self.assertEqual([["reau_20261004_100000_000.aasession"], []], self.nomes(blocos, "reau_20261004_100000_000.aasession"))

    def test_alem_da_folga_ou_depois_do_fim_nao_vai_para_nenhuma(self):
        blocos = self.blocos(("10:02:00.000", "10:05:00.000"))
        self.assertEqual([[]], self.nomes(blocos, "reau_20261004_100000_000.aasession", "reau_20261004_100600_000.aasession"))


if __name__ == "__main__":
    unittest.main()
