"""A5 (26/11/2026): cada morte «o sistema fechou o REau» traz o tempo desde a tela apagar, o de projeção, o desde a retomada, a descrição inteira e a origem do «Forçar parada»;
duas ou mais mortes com a mesma espera de tela apagada dão a linha do padrão. Só registros sintéticos (nenhum nome de rede, endereço ou coordenada).

    python3 -B -m unittest test_mortes     (from scripts/registro)
"""
import sys
import unittest
from collections import namedtuple
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))
import ler_registro  # noqa: E402
import mortes  # noqa: E402
import tempo  # noqa: E402
from test_a2_saida_e_calor import olhada  # noqa: E402
from test_a8_leitor import LIGAR, cabecalho, linha, lido, pasta_de  # noqa: E402

Entrada = namedtuple("Entrada", "mes dia hora pid tid nivel tag texto")


def saida(quando, descricao='[UNKNOWN] o-kill(6)', motivo="other", codigo=13, hora="11:00:00.100"):
    return linha(hora, "exit", f'past exit: {motivo} ({codigo}) at 2026-10-04 {quando}, process com.reau.app pid 4880, was foreground service (125), pss 0 MB, rss 99 MB, "{descricao}"')


def morte(*meio, quando="10:08:00.000", **saida_):
    """Um processo que projeta e morre na hora `quando`, e o que o processo seguinte diz dele; devolve as linhas «o sistema fechou o REau» dos problemas."""
    morreu = cabecalho() + LIGAR + "".join(meio)
    voltou = cabecalho("11:00:00.000") + saida(quando, **saida_)
    return [p for p in ler_registro.problemas(lido(pasta_de(morreu, voltou))) if "o sistema fechou o REau" in p]


TELA_APAGOU_AS_10_03 = olhada("10:02:00.000", 30, tela="on") + olhada("10:03:00.000", 30, tela="off") + olhada("10:05:00.000", 30, tela="off")


class TempoDesdeATelaApagarTest(unittest.TestCase):
    def test_o_tempo_vai_da_primeira_olhada_apagada_depois_da_ultima_acesa_ate_a_morte(self):
        [achado] = morte(TELA_APAGOU_AS_10_03)
        self.assertIn("a tela apagou 300,0 s antes da morte (às 10:03:00,0)", achado)

    def test_a_tela_que_acendeu_e_apagou_de_novo_conta_da_ultima_vez(self):
        [achado] = morte(TELA_APAGOU_AS_10_03, olhada("10:06:00.000", 30, tela="on"), olhada("10:07:00.000", 30, tela="off"))
        self.assertIn("a tela apagou 60,0 s antes da morte (às 10:07:00,0)", achado)

    def test_a_linha_do_sistema_tambem_conta(self):
        [achado] = morte(linha("10:02:00.000", "system", "screen on"), linha("10:04:00.000", "system", "screen off"))
        self.assertIn("a tela apagou 240,0 s antes da morte (às 10:04:00,0)", achado)

    def test_a_tela_acesa_na_ultima_olhada_nao_tem_intervalo(self):
        [achado] = morte(olhada("10:03:00.000", 30, tela="off"), olhada("10:07:00.000", 30, tela="on"))
        self.assertIn("a tela estava acesa na última olhada (60,0 s antes da morte)", achado)
        self.assertNotIn("padrão", achado)

    def test_o_processo_que_ja_comecou_com_a_tela_apagada_so_da_o_minimo(self):
        [achado] = morte(olhada("10:00:30.000", 30, tela="off"), olhada("10:05:00.000", 30, tela="off"))
        self.assertIn("a tela já estava apagada quando o processo começou (primeira olhada às 10:00:30,0): pelo menos 450,0 s de tela apagada até a morte", achado)

    def test_sem_leitura_da_tela_o_registro_diz_isso(self):
        [achado] = morte(linha("10:03:00.000", "aa", "[AA] Android Auto conectado"))
        self.assertIn("sem leitura da tela no registro do processo", achado)

    def test_a_olhada_depois_da_morte_nao_conta(self):
        [achado] = morte(TELA_APAGOU_AS_10_03, olhada("10:09:00.000", 30, tela="on"))
        self.assertIn("a tela apagou 300,0 s antes da morte", achado)


class ProjecaoERetomadaTest(unittest.TestCase):
    def test_o_tempo_de_projecao_vai_da_marca_de_ligar_ate_a_morte(self):
        [achado] = morte(TELA_APAGOU_AS_10_03)
        self.assertIn("projetava havia 7 min 59 s", achado)

    def test_a_projecao_desligada_antes_da_morte_nao_conta(self):
        [achado] = morte(TELA_APAGOU_AS_10_03, "10:06:00.000  +  1000ms  ---- projeção desligada: projection ----\n")
        self.assertIn("sem projeção ligada na hora da morte", achado)
        self.assertNotIn("projetava havia", achado)

    def test_o_tempo_desde_a_retomada_e_a_tentativa(self):
        [achado] = morte(linha("10:02:00.000", "projection", "resumed after the system ended the process (attempt 2 of 3)"), TELA_APAGOU_AS_10_03)
        self.assertIn("o processo veio da retomada (tentativa 2 de 3), 360,0 s antes da morte", achado)

    def test_a_retomada_com_nota_tambem_vale(self):
        [achado] = morte(linha("10:02:00.000", "projection", "resumed after the system ended the process (attempt 1 of 3; the task was swiped)"))
        self.assertIn("o processo veio da retomada (tentativa 1 de 3)", achado)

    def test_sem_retomada_nao_diz_nada_dela(self):
        [achado] = morte(TELA_APAGOU_AS_10_03)
        self.assertNotIn("retomada", achado)

    def test_a_morte_sem_o_registro_do_processo_diz_que_nao_o_tem(self):
        voltou = cabecalho("11:00:00.000") + saida("10:08:00.000")
        [achado] = [p for p in ler_registro.problemas(lido(pasta_de(voltou))) if "o sistema fechou o REau" in p]
        self.assertIn("o registro do processo que morreu não está nos arquivos lidos", achado)

    def test_o_processo_certo_e_o_que_comecou_antes_da_morte_e_nao_o_ultimo(self):
        # Two processes before the one that tells the death; the death is of the first (the second began after it).
        primeiro = cabecalho("10:00:00.000") + LIGAR + TELA_APAGOU_AS_10_03
        segundo = cabecalho("10:20:00.000") + olhada("10:20:05.000", 30, tela="on")
        terceiro = cabecalho("11:00:00.000") + saida("10:08:00.000")
        [achado] = [p for p in ler_registro.problemas(lido(pasta_de(primeiro, segundo, terceiro))) if "o sistema fechou o REau" in p]
        self.assertIn("a tela apagou 300,0 s antes da morte", achado)


class DescricaoEOrigemTest(unittest.TestCase):
    def test_a_descricao_inteira_da_saida_entra_na_linha(self):
        [achado] = morte(TELA_APAGOU_AS_10_03, descricao="[UNKNOWN] o-kill(6)")
        self.assertIn("descrição inteira da saída: «[UNKNOWN] o-kill(6)»", achado)

    def test_forcar_parada_com_a_razao_do_gerenciador_e_do_sistema(self):
        texto = "[FORCE STOP] stop com.reau.app due to UserDefined:The system loading is too high and ANR is occurring."
        [achado] = morte(TELA_APAGOU_AS_10_03, descricao=texto, motivo="user requested", codigo=10)
        self.assertIn(f"descrição inteira da saída: «{texto}»", achado)
        self.assertIn("origem pelo texto (suposição): o texto traz a razão que o próprio sistema deu («The system loading is too high and ANR is occurring.»)", achado)

    def test_forcar_parada_sem_a_razao_pode_ser_o_piloto(self):
        [achado] = morte(TELA_APAGOU_AS_10_03, descricao="[FORCE STOP] stop com.reau.app", motivo="user requested", codigo=10)
        self.assertIn("pode ser o piloto nos Ajustes", achado)

    def test_so_o_forcar_parada_tem_origem(self):
        [achado] = morte(TELA_APAGOU_AS_10_03, descricao="[UNKNOWN] o-kill(6)")
        self.assertNotIn("origem pelo texto", achado)
        [achado] = morte(TELA_APAGOU_AS_10_03, descricao="[UNKNOWN] force stop?", motivo="other", codigo=13)
        self.assertNotIn("origem pelo texto", achado)

    def test_a_saida_sem_descricao_diz_que_nao_tem(self):
        [achado] = morte(TELA_APAGOU_AS_10_03, descricao="")
        self.assertIn("a saída não traz descrição", achado)


class PadraoTest(unittest.TestCase):
    def processos(self, esperas, descricao="[UNKNOWN] o-kill(6)", motivo="other", codigo=13):
        """Um arquivo por morte: o processo que apaga a tela `espera` s antes de morrer e, no começo do arquivo seguinte, a linha que conta a morte."""
        arquivos, contar = [], ""
        for k, espera in enumerate(esperas):
            apagou = 480 - espera
            texto = (cabecalho(f"{10 + k}:00:00.000") + contar + f"{10 + k}:00:01.000  +  1000ms  ---- projeção ligada: projection ----\n"
                     + olhada(f"{10 + k}:00:30.000", 30, tela="on") + olhada(f"{10 + k}:{int(apagou // 60):02d}:{int(apagou % 60):02d}.000", 30, tela="off"))
            arquivos.append(texto)
            contar = saida(f"{10 + k}:08:00.000", descricao, motivo, codigo, hora=f"{11 + k}:00:00.100")
        arquivos.append(cabecalho(f"{10 + len(esperas)}:00:00.000") + contar)
        return [p for p in ler_registro.problemas(lido(pasta_de(*arquivos))) if "o sistema fechou o REau" in p or "as mortes vieram" in p]

    def test_dois_intervalos_parecidos_dao_a_linha_do_padrao(self):
        achados = self.processos([299, 303])
        self.assertEqual(3, len(achados), achados)
        self.assertTrue(achados[-1].startswith("as mortes vieram 299 a 303 s depois de a tela apagar (padrão): 2 de 2 mortes"), achados[-1])

    def test_um_so_intervalo_nao_e_padrao(self):
        self.assertFalse([p for p in self.processos([299]) if "padrão" in p])

    def test_intervalos_longe_um_do_outro_nao_sao_padrao(self):
        self.assertFalse([p for p in self.processos([100, 299]) if "padrão" in p])

    def test_o_padrao_vale_o_maior_grupo_e_diz_de_quantas(self):
        achados = self.processos([298, 100, 303, 302])
        self.assertTrue(achados[-1].startswith("as mortes vieram 298 a 303 s depois de a tela apagar (padrão): 3 de 4 mortes"), achados[-1])

    def test_o_forcar_parada_que_pode_ser_do_piloto_fica_fora_do_padrao(self):
        achados = self.processos([299, 303], descricao="[FORCE STOP] stop com.reau.app", motivo="user requested", codigo=10)
        self.assertFalse([p for p in achados if "padrão" in p])

    def test_o_intervalo_so_minimo_nao_entra_no_padrao(self):
        # Two deaths whose processes began with the screen already off: the counts are lower bounds, not a clock.
        arquivos = []
        contar = ""
        for k in range(2):
            arquivos.append(cabecalho(f"{10 + k}:00:00.000") + contar + f"{10 + k}:00:01.000  +  1000ms  ---- projeção ligada: projection ----\n"
                            + olhada(f"{10 + k}:03:00.000", 30, tela="off"))
            contar = saida(f"{10 + k}:08:00.000", hora=f"{11 + k}:00:00.100")
        arquivos.append(cabecalho("12:00:00.000") + contar)
        achados = [p for p in ler_registro.problemas(lido(pasta_de(*arquivos))) if "o sistema fechou o REau" in p or "as mortes vieram" in p]
        self.assertEqual(2, len(achados))
        self.assertFalse([p for p in achados if "padrão" in p])


class PurasTest(unittest.TestCase):
    def test_padrao_com_um_numero_so_diz_um_numero(self):
        self.assertIn("as mortes vieram 300 s depois", mortes.padrao([(300.0, "a"), (300.2, "b")]))

    def test_padrao_vazio_com_menos_de_dois(self):
        self.assertEqual("", mortes.padrao([]))
        self.assertEqual("", mortes.padrao([(300.0, "a")]))

    def test_padrao_com_folga_de_15_s_vale_e_16_nao(self):
        self.assertNotEqual("", mortes.padrao([(290.0, "a"), (305.0, "b")]))
        self.assertEqual("", mortes.padrao([(290.0, "a"), (306.0, "b")]))

    def test_origem_so_para_forcar_parada_dos_motivos_10_e_11(self):
        self.assertEqual("", mortes.origem_da_parada(13, "[FORCE STOP] x")[0])
        self.assertEqual("", mortes.origem_da_parada(10, "[REMOVE TASK] remove task")[0])
        self.assertTrue(mortes.origem_da_parada(11, "[FORCE STOP] x")[1])
        self.assertFalse(mortes.origem_da_parada(10, "[FORCE STOP] x due to UserDefined:why")[1])

    def test_a_razao_do_gerenciador_e_cortada_em_80_caracteres(self):
        frase, _ = mortes.origem_da_parada(10, "[FORCE STOP] x due to UserDefined:" + "Z" * 200)
        self.assertEqual(80, frase.count("Z"))


def tela(hora, texto, pid=100, mes=11, dia=25):
    return Entrada(mes, dia, hora, pid, pid, "I", "VRI[MainActivity]", texto)


class DisplayDoLogcatTest(unittest.TestCase):
    MORTE = mortes.segundos_do_dia("14:48:36.277")

    def ler(self, entradas, pid=100):
        return mortes.display_do_logcat(entradas, pid, 11, 25, self.MORTE)

    def test_apagou_e_depois_do_modo_de_espera_ficou_apagado_de_vez(self):
        # The Xiaomi of 25/11, 14:46:59.649, 14:47:02.607 and 14:47:14.131.
        apagou, de_vez = self.ler([tela("14:46:50.478", "DisplayState - old: 3, new: 2"), tela("14:46:59.649", "DisplayState - old: 2, new: 1"),
                                   tela("14:47:02.607", "DisplayState - old: 1, new: 3"), tela("14:47:14.131", "DisplayState - old: 3, new: 1")])
        self.assertEqual(("14:46:59,6", "14:47:14,1"), (tempo.hora_ms(apagou), tempo.hora_ms(de_vez)))

    def test_o_display_em_modo_de_espera_na_hora_da_morte_nao_esta_apagado_de_vez(self):
        apagou, de_vez = self.ler([tela("14:46:59.649", "DisplayState - old: 2, new: 1"), tela("14:47:02.607", "DisplayState - old: 1, new: 3")])
        self.assertIsNotNone(apagou)
        self.assertIsNone(de_vez)

    def test_o_display_aceso_zera_a_conta(self):
        self.assertEqual((None, None), self.ler([tela("14:40:00.000", "DisplayState - old: 2, new: 1"), tela("14:41:00.000", "DisplayState - old: 1, new: 2")]))

    def test_so_o_pid_e_o_dia_da_morte_valem_e_nada_depois_dela(self):
        self.assertEqual((None, None), self.ler([tela("14:46:59.649", "DisplayState - old: 2, new: 1", pid=7)]))
        self.assertEqual((None, None), self.ler([tela("14:46:59.649", "DisplayState - old: 2, new: 1", dia=24)]))
        self.assertEqual((None, None), self.ler([tela("14:49:00.000", "DisplayState - old: 2, new: 1")]))

    def test_so_a_etiqueta_da_janela_do_app_vale(self):
        outra = Entrada(11, 25, "14:46:59.649", 100, 100, "I", "OutraCoisa", "DisplayState - old: 2, new: 1")
        self.assertEqual((None, None), self.ler([outra]))

    def test_a_linha_traz_as_duas_horas_quando_ha_logcat(self):
        texto = mortes.texto_do_display(self.MORTE, mortes.segundos_do_dia("14:46:59.649"), mortes.segundos_do_dia("14:47:14.131"))
        self.assertIn("o display apagou 96,6 s antes da morte (às 14:46:59,6) e ficou apagado de vez 82,1 s antes (às 14:47:14,1, depois do modo de espera)", texto)
        self.assertEqual("", mortes.texto_do_display(self.MORTE, None, None))


if __name__ == "__main__":
    unittest.main()
