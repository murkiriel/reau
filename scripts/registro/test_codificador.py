"""Testes do ler_registro.py para as linhas do teste do codificador de vídeo (0.4.0, 26/11/2026): o codificador que abriu, a taxa, o SPS e a forma do fluxo (quadro-chave, fatias,
pior segundo). Registros sintéticos (sem nome de rede, endereço, coordenada ou nome de pessoa).

    python3 -B -m unittest test_codificador     (a partir de scripts/registro)
"""
import sys
import tempfile
import unittest
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))
import codificador  # noqa: E402
import ler_registro  # noqa: E402
from test_ler_registro import escreve, linha, projecao  # noqa: E402

EM_USO_MTK = "encoder in use: asked=PHONE name=c2.mtk.avc.encoder kind=hw rate=CBR -> VBR (CBR not offered)"
EM_USO_SW = "encoder in use: asked=SOFTWARE name=c2.android.avc.encoder kind=sw rate=CBR -> CBR; software encoder asked: c2.android.avc.encoder first"
EM_USO_SEM = ("encoder in use: asked=SOFTWARE name=c2.mtk.avc.encoder kind=hw rate=CBR -> VBR (CBR not offered); "
              "software encoder asked, none in the phone's list: the phone's own is used")
TAXA_MTK = "encoder rate: CBR -> VBR (CBR not offered), codec says VBR"
SPS_MTK = "encoder sps: profile=Baseline(66) level=4.1 constraints=0x00; original=67 42 00 29 11; sent=67 42 00 29 11"
SPS_QTI = "encoder sps: profile=Baseline(66) level=4.1 constraints=0x80; original=67 42 80 29 11; sent=67 42 00 29 11"
TROCA = "W: software encoder failed: the phone's own from now on"
FORMA = "video shape: frames=300 keyframes=48 keybytes avg=31200 max=44700 keypackets avg=24.3 max=33 slices=1 peak1s=441kbps/38pkt"


def lido(*meio):
    d = Path(tempfile.mkdtemp())
    escreve(d, "ride-20261126-100000.log", projecao(*meio))
    linhas = ler_registro.ler([d])
    return "\n".join(ler_registro.linha_do_tempo(linhas)), linhas


def stream(hora, texto):
    return linha(hora, "stream", texto)


class LinhasDoCodificador(unittest.TestCase):
    def test_o_codificador_que_abriu_e_a_taxa_pedida_e_enviada_na_linha_do_tempo(self):
        t, _ = lido(stream("10:00:03.000", EM_USO_MTK))
        self.assertIn("10:00:03.000  codificador do vídeo: pediu o do celular, abriu c2.mtk.avc.encoder (hardware); taxa pedida CBR, enviada VBR (CBR não oferecido)", t)

    def test_o_de_software_pedido_e_achado(self):
        t, _ = lido(stream("10:00:03.000", EM_USO_SW))
        self.assertIn("pediu o de software do Android, abriu c2.android.avc.encoder (software); taxa pedida CBR, enviada CBR", t)
        self.assertNotIn("o celular não lista", t)

    def test_o_de_software_pedido_e_nao_achado_cai_no_do_celular_e_diz_na_linha_do_tempo(self):
        t, _ = lido(stream("10:00:03.000", EM_USO_SEM))
        self.assertIn("pediu o de software do Android, abriu c2.mtk.avc.encoder (hardware)", t)
        self.assertIn("o celular não lista um codificador de software do Android: ficou o do celular", t)

    def test_o_modo_de_taxa_que_o_codec_devolveu(self):
        t, _ = lido(stream("10:00:03.100", TAXA_MTK))
        self.assertIn("10:00:03.100  taxa do codificador: pedida CBR, enviada VBR (CBR não oferecido), o codec diz VBR", t)
        t, _ = lido(stream("10:00:03.100", "encoder rate: CBR -> CBR, codec says not echoed"))
        self.assertIn("pedida CBR, enviada CBR, o codec diz not echoed", t)

    def test_a_taxa_recusada_do_ultimo_degrau_vai_em_portugues(self):
        t, _ = lido(stream("10:00:03.000", EM_USO_MTK.replace("CBR -> VBR (CBR not offered)", "CBR -> none (refused)")),
                    stream("10:00:03.100", "encoder rate: CBR -> none (refused), codec says VBR"))
        self.assertIn("taxa pedida CBR, enviada nenhum (recusado)", t)
        self.assertIn("taxa do codificador: pedida CBR, enviada nenhum (recusado), o codec diz VBR", t)
        self.assertNotIn("none (refused)", t)
        self.assertNotIn("refused", t.replace("recusado", ""))

    def test_o_sps_original_e_o_enviado_com_o_perfil_e_o_nivel(self):
        t, _ = lido(stream("10:00:04.000", SPS_QTI))
        self.assertIn("10:00:04.000  SPS do codificador: Baseline(66), nível 4.1, flags 0x80; original 67 42 80 29 11; enviado 67 42 00 29 11", t)
        t, _ = lido(stream("10:00:04.000", SPS_MTK))
        self.assertIn("original 67 42 00 29 11; enviado igual", t)

    def test_o_resumo_da_projecao_junta_tudo_com_a_ultima_forma(self):
        antes = FORMA.replace("frames=300", "frames=150")
        t, _ = lido(stream("10:00:03.000", EM_USO_MTK), stream("10:00:03.100", TAXA_MTK), stream("10:00:04.000", SPS_MTK),
                    stream("10:05:03.000", antes), stream("10:08:59.000", FORMA))
        resumo = [x for x in t.splitlines() if "codificador: c2." in x]
        self.assertEqual(1, len(resumo), t)
        self.assertIn("c2.mtk.avc.encoder (hardware; pedido: o do celular)", resumo[0])
        self.assertIn("taxa pedida CBR, enviada VBR (CBR não oferecido), aceita VBR", resumo[0])
        self.assertIn("SPS Baseline(66) nível 4.1", resumo[0])
        self.assertIn("quadro-chave médio 31200 B em 24,3 pacotes (máx 44700 B em 33)", resumo[0])
        self.assertIn("fatias por quadro 1", resumo[0])
        self.assertIn("pior segundo 441 kbps em 38 pacotes (300 quadros, 48 quadros-chave)", resumo[0])
        self.assertNotIn("150 quadros", resumo[0])

    def test_a_troca_do_de_software_para_o_do_celular_aparece_na_linha_do_tempo(self):
        t, linhas = lido(stream("10:00:03.000", EM_USO_SW), stream("10:04:21.500", TROCA))
        self.assertIn("10:04:21.500  o codificador de software falhou: daqui em diante o do celular, até o fim da sessão", t)
        self.assertEqual([], ler_registro.problemas(linhas))

    def test_a_sessao_com_a_troca_diz_na_linha_resumo_que_a_forma_mistura_os_dois(self):
        t, _ = lido(stream("10:00:03.000", EM_USO_SW), stream("10:04:21.500", TROCA), stream("10:08:59.000", FORMA))
        resumo = [x for x in t.splitlines() if "codificador: c2." in x]
        self.assertEqual(1, len(resumo), t)
        self.assertIn("c2.android.avc.encoder (software; pedido: o de software do Android; caiu para o do celular às 10:04:21)", resumo[0])
        self.assertTrue(resumo[0].endswith("o `video shape` desta sessão mistura os dois codificadores e não serve para comparar"), resumo[0])

    def test_a_sessao_sem_a_troca_nao_leva_o_aviso_e_a_troca_sem_a_linha_de_uso_ainda_marca(self):
        t, _ = lido(stream("10:00:03.000", EM_USO_SW), stream("10:08:59.000", FORMA))
        self.assertNotIn("caiu para o do celular", t)
        self.assertNotIn("mistura os dois", t)
        t, _ = lido(stream("10:04:21.500", TROCA), stream("10:08:59.000", FORMA))
        resumo = [x for x in t.splitlines() if "codificador: o de software caiu" in x]
        self.assertEqual(1, len(resumo), t)
        self.assertIn("caiu para o do celular às 10:04:21", resumo[0])

    def test_a_forma_com_mais_de_uma_fatia_diz_quantos_quadros(self):
        forma = FORMA.replace("slices=1", "slices=3 multi=7/300")
        t, _ = lido(stream("10:08:59.000", forma))
        self.assertIn("fatias por quadro até 3 (7 de 300 quadros com mais de uma)", t)

    def test_sem_nenhuma_linha_do_codificador_nada_e_dito_e_o_registro_antigo_le_como_antes(self):
        t, linhas = lido(stream("10:00:10.000", "video fps target=6 sent=5.9 (30frames/5.0s) composed=6.0 kbps=240 set=400k frame=5000B"))
        self.assertNotIn("codificador", t)
        self.assertIsNone(codificador.texto([x for x in linhas if x.etiqueta == "stream"]))
        self.assertEqual([], codificador.eventos("video fps target=6"))
        self.assertEqual([], ler_registro.problemas(linhas))

    def test_so_as_linhas_stream_contam(self):
        t, _ = lido(linha("10:00:03.000", "dash", EM_USO_MTK), linha("10:08:59.000", "device", FORMA))
        self.assertNotIn("codificador", t)

    def test_nenhuma_linha_vira_problema(self):
        _, linhas = lido(stream("10:00:03.000", EM_USO_SEM), stream("10:00:03.100", TAXA_MTK), stream("10:00:04.000", SPS_QTI), stream("10:08:59.000", FORMA))
        self.assertEqual([], ler_registro.problemas(linhas))


if __name__ == "__main__":
    unittest.main()
