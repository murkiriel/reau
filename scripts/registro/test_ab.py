"""Testes do teste A/B da 0.4.0 (`ab.py` e o `--ab` do ler_registro.py): as sessões separadas pelo espaçamento do envio e pelo codificador do vídeo, os números de cada uma e as medianas
de cada braço. Registros sintéticos (sem nome de rede, endereço, coordenada ou nome de pessoa).

    python3 -B -m unittest test_ab     (a partir de scripts/registro)
"""
import contextlib
import io
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))
import ab  # noqa: E402
import ler_registro  # noqa: E402
from test_ler_registro import escreve  # noqa: E402
from test_fim_de_viagem import FIRME, linha, perda_do_wifi, projecao, vigia  # noqa: E402

EM_USO_PHONE = "encoder in use: asked=PHONE name=c2.mtk.avc.encoder kind=hw rate=CBR -> VBR (CBR not offered)"
EM_USO_SW = "encoder in use: asked=SOFTWARE name=c2.android.avc.encoder kind=sw rate=CBR -> CBR; software encoder asked: c2.android.avc.encoder first"
EM_USO_SEM = ("encoder in use: asked=SOFTWARE name=c2.mtk.avc.encoder kind=hw rate=CBR -> VBR (CBR not offered); "
              "software encoder asked, none in the phone's list: the phone's own is used")
TROCA = "W: software encoder failed: the phone's own from now on"
FORMA = "video shape: frames=300 keyframes=48 keybytes avg=31200 max=44700 keypackets avg=24.3 max=33 slices=1 peak1s=441kbps/38pkt"
VIDEO = "video fps target=6 sent=5.9 (30frames/5.0s) composed=6.0 kbps=240 set=400k frame=5000B source=30 drawn=30 repeated=0 compose=2.0ms encode=9.0ms late=0 tickwait={t} full=true"
STATS = "dash-stats 5.0s: tx=63 (0501=8) rx=25 (0700=1) acks=22 buttons=0 telemetry=0 heartbeats=17 tx-fail=0 {pace}last-rx=37ms ago | total tx=63 rx=25 acks=22 buttons=0 telemetry=0 tx-fail=0{total}"


def resumo(streaming="8m56s", gap="0.3s", escuro=""):
    return linha("10:08:59.000", "projection", f"resumo duration=9m00s streaming={streaming} drops dash=0 aa=0 watchdogs=0 frames=8500 fps avg=5.9 min=5.0 gap max={gap}{escuro} thermal max=none battery 80→76% (-4) marks=1")


def configuracao(pace=True, enc="PHONE", faltando=False):
    if faltando:
        return ""
    return linha("10:00:02.000", "settings", f"settings in use: density=180 pacePackets={'true' if pace else 'false'} videoEncoder={enc} experimentStartGuard=true")


def sessao(pace=True, enc="PHONE", uso=EM_USO_PHONE, *extras, streaming="8m56s", gap="0.3s", faltando=False, com_resumo=True, com_forma=True):
    meio = [configuracao(pace, enc, faltando)]
    if uso:
        meio.append(linha("10:00:03.000", "stream", uso))
    meio.append(linha("10:00:04.000", "session", "AUTHENTICATING → STREAMING"))
    meio += extras
    if com_forma:
        meio.append(linha("10:08:58.000", "stream", FORMA))
    if com_resumo:
        meio.append(resumo(streaming, gap))
    return projecao(*meio)


def lido(*registros):
    d = Path(tempfile.mkdtemp())
    for k, texto in enumerate(registros):
        escreve(d, f"ride-20261126-{10 + k:02d}0000.log", texto)
    linhas = ler_registro.ler([d])
    ler_registro.marcar_fim_de_viagem([d], linhas)
    return d, ler_registro.sessoes_ab(linhas)


def por_braco(sessoes):
    saida = {}
    for s in sessoes:
        saida.setdefault((s["pace"], s["codificador"]), []).append(s)
    return saida


class OsBracos(unittest.TestCase):
    def test_as_quatro_combinacoes_se_separam_pelo_que_a_linha_de_chaves_diz(self):
        _, sessoes = lido(sessao(True, "PHONE"), sessao(False, "PHONE"), sessao(True, "SOFTWARE", EM_USO_SW), sessao(False, "SOFTWARE", EM_USO_SW))
        self.assertEqual([(ab.PACE_LIGADO, ab.CELULAR), (ab.PACE_DESLIGADO, ab.CELULAR), (ab.PACE_LIGADO, ab.SOFTWARE), (ab.PACE_DESLIGADO, ab.SOFTWARE)],
                         [ab.braco(s) for s in sessoes])

    def test_sem_a_linha_de_chaves_o_pace_vem_do_dash_stats_e_o_codificador_do_asked(self):
        stats = linha("10:00:10.000", "dash", STATS.format(pace="pace=on waited=4.0ms max=2.0ms ", total=" pace-waited=4ms"))
        _, [s] = lido(sessao(True, "SOFTWARE", EM_USO_SW, stats, faltando=True))
        self.assertEqual((ab.PACE_LIGADO, ab.SOFTWARE), ab.braco(s))
        stats = linha("10:00:10.000", "dash", STATS.format(pace="pace=off ", total=""))
        _, [s] = lido(sessao(False, "PHONE", EM_USO_PHONE, stats, faltando=True))
        self.assertEqual((ab.PACE_DESLIGADO, ab.CELULAR), ab.braco(s))

    def test_registro_de_antes_dos_campos_cai_em_sem_registro(self):
        _, [s] = lido(sessao(uso=None, faltando=True))
        self.assertEqual((ab.PACE_SEM, ab.CODIFICADOR_SEM), ab.braco(s))

    def test_a_troca_do_de_software_para_o_do_celular_fica_a_parte(self):
        _, [s] = lido(sessao(True, "SOFTWARE", EM_USO_SW, linha("10:04:21.500", "stream", TROCA)))
        self.assertEqual((ab.PACE_LIGADO, ab.TROCOU), ab.braco(s))
        self.assertTrue(s["trocou"])
        self.assertIn("à parte", ab.rotulo(ab.braco(s)))

    def test_o_de_software_pedido_num_celular_que_nao_lista_um_fica_a_parte(self):
        _, [s] = lido(sessao(True, "SOFTWARE", EM_USO_SEM))
        self.assertEqual((ab.PACE_LIGADO, ab.SEM_O_DE_SOFTWARE), ab.braco(s))

    def test_duas_linhas_de_uso_com_codificadores_diferentes_ficam_a_parte(self):
        _, [s] = lido(sessao(True, "PHONE", EM_USO_PHONE, linha("10:05:00.000", "stream", EM_USO_SW)))
        self.assertEqual((ab.PACE_LIGADO, ab.MISTURADO), ab.braco(s))

    def test_duas_linhas_de_uso_iguais_nao_misturam(self):
        _, [s] = lido(sessao(True, "PHONE", EM_USO_PHONE, linha("10:05:00.000", "stream", EM_USO_PHONE)))
        self.assertEqual((ab.PACE_LIGADO, ab.CELULAR), ab.braco(s))

    def test_so_as_linhas_stream_contam_para_o_codificador(self):
        _, [s] = lido(sessao(True, "PHONE", None, linha("10:00:03.000", "dash", EM_USO_SW), faltando=True))
        self.assertEqual(ab.CODIFICADOR_SEM, s["codificador"])


class OsNumerosDaSessao(unittest.TestCase):
    def test_streaming_silencio_e_quadro_chave(self):
        _, [s] = lido(sessao(streaming="8m56s", gap="1.4s"))
        self.assertEqual(536.0, s["streaming_s"])
        self.assertEqual(1.4, s["silencio_s"])
        self.assertEqual((31200, 44700), (s["chave_medio_b"], s["chave_max_b"]))

    def test_a_sessao_sem_resumo_nem_forma_diz_que_nao_tem_e_nao_inventa(self):
        _, [s] = lido(sessao(com_resumo=False, com_forma=False))
        self.assertIsNone(s["streaming_s"])
        self.assertIsNone(s["silencio_s"])
        self.assertIsNone(s["chave_medio_b"])
        self.assertIn("sem resumo", ab._por_sessao(s))

    def test_pace_waited_soma_o_total_de_cada_conexao(self):
        stats = [linha(f"10:00:{10 + k:02d}.000", "dash", STATS.format(pace="pace=on waited=1.0ms max=1.0ms ", total=f" pace-waited={ms}ms")) for k, ms in enumerate((3, 7, 2, 5))]
        stats.append(linha("10:00:20.000", "dash", STATS.format(pace="pace=on waited=0.0ms max=0.0ms ", total=" pace-waited=6ms") + " (session end)"))
        _, [s] = lido(sessao(True, "PHONE", EM_USO_PHONE, *stats))
        self.assertEqual(7 + 6, s["pace_esperado_ms"])  # a conexão de 3 e 7, depois a de 2, 5 e 6... o contador voltou a zero entre o 7 e o 2

    def test_pace_waited_nao_existe_com_o_espacamento_desligado(self):
        stats = linha("10:00:10.000", "dash", STATS.format(pace="pace=off ", total=""))
        _, [s] = lido(sessao(False, "PHONE", EM_USO_PHONE, stats))
        self.assertIsNone(s["pace_esperado_ms"])

    def test_tickwait_media_das_medias_e_o_maior_maximo(self):
        videos = [linha("10:00:10.000", "stream", VIDEO.format(t="100/200/250ms")), linha("10:00:15.000", "stream", VIDEO.format(t="200/300/400ms")),
                  linha("10:00:20.000", "stream", VIDEO.format(t="-"))]
        _, [s] = lido(sessao(True, "PHONE", EM_USO_PHONE, *videos))
        self.assertEqual(150, s["tickwait_medio_ms"])
        self.assertEqual(400, s["tickwait_max_ms"])

    def test_sem_tickwait_no_registro_nada_e_dito(self):
        _, [s] = lido(sessao())
        self.assertIsNone(s["tickwait_medio_ms"])

    def test_forma_sem_quadro_chave_nao_da_tamanho(self):
        _, [s] = lido(projecao(configuracao(), linha("10:08:58.000", "stream", FORMA.replace("keyframes=48", "keyframes=0")), resumo()))
        self.assertIsNone(s["chave_medio_b"])

    def test_o_dark_do_resumo_e_dito_a_parte(self):
        registro = projecao(configuracao(), resumo(escuro=" dark=2/1m10s (max 1m08s)"))
        _, [s] = lido(registro)
        self.assertEqual(68.0, s["escuro_s"])


class OsReiniciosDoPainel(unittest.TestCase):
    def queda_que_volta(self, canal_antes="bike=-55dBm/2447MHz/58Mbps", canal_depois="bike=-50dBm/2427MHz/72Mbps"):
        olhada = "device battery=80% charging=no temp=31.0C thermal=none screen=on {} [30 s]"
        return projecao(
            configuracao(),
            linha("10:00:30.000", "link", FIRME),
            linha("10:00:50.000", "device", olhada.format(canal_antes)),
            linha("10:01:00.000", "wifi", "W: link lost — reconnecting in 4000ms (after 59 s on 2447 MHz; rssi -55)"),
            linha("10:01:00.003", "system", "bike network lost"),
            linha("10:01:22.000", "wifi", "connected 'RE_****30' in 900ms"),
            linha("10:01:22.000", "system", "bike network available"),
            linha("10:01:42.000", "device", olhada.format(canal_depois)),
            resumo())

    def test_o_wifi_do_painel_que_volta_em_outro_canal_conta_como_reinicio(self):
        _, [s] = lido(self.queda_que_volta())
        self.assertEqual((1, 1, 0, 1), (s["quedas"], s["reiniciou"], s["religou"], s["reinicios"]))

    def test_o_que_volta_no_mesmo_canal_e_queda_mas_nao_reinicio(self):
        _, [s] = lido(self.queda_que_volta(canal_depois="bike=-50dBm/2447MHz/72Mbps"))
        self.assertEqual((1, 0, 0, 0), (s["quedas"], s["reiniciou"], s["religou"], s["reinicios"]))

    def test_o_painel_que_religou_pela_idade_conta_e_a_idade_vem_do_primeiro_pacote(self):
        registro = projecao(configuracao(), linha("10:00:40.000", "link", FIRME), perda_do_wifi("10:01:00.000"), vigia("10:01:04.200"),
                            linha("10:02:30.000", "wifi", "connected 'RE_****34' in 900ms"),
                            linha("10:02:30.100", "auth", "dash heartbeat age 20 s at the first packet"),
                            linha("10:02:30.500", "session", "AUTHENTICATING → STREAMING"), resumo())
        d = Path(tempfile.mkdtemp())
        escreve(d, "ride-20261126-100000.log", registro)
        linhas = ler_registro.ler([d])
        ler_registro.marcar_fim_de_viagem([d], linhas)
        # Sem o log do sistema o Wi-Fi firme que sumiu de uma vez e voltou com a idade menor que a queda é o painel que ligou de novo.
        [s] = ler_registro.sessoes_ab(linhas)
        self.assertEqual((0, 0, 1, 1), (s["quedas"], s["reiniciou"], s["religou"], s["reinicios"]))
        self.assertEqual(20, s["idade_painel_s"])

    def test_a_sessao_sem_queda_nao_reiniciou(self):
        _, [s] = lido(sessao())
        self.assertEqual((0, 0, 0, 0), (s["quedas"], s["reiniciou"], s["religou"], s["reinicios"]))
        self.assertIn("painel não reiniciou", ab._por_sessao(s))

    def test_o_fim_de_viagem_nao_conta(self):
        registro = projecao(configuracao(), linha("10:00:40.000", "link", FIRME), perda_do_wifi("10:01:00.000"), vigia("10:01:04.200"), fim="10:01:13.000")
        _, [s] = lido(registro)
        self.assertEqual((0, 0), (s["quedas"], s["reinicios"]))


class PorBraco(unittest.TestCase):
    def relatorio(self, *registros):
        return "\n".join(ab.texto(lido(*registros)[1]))

    def test_menos_de_dez_sessoes_leva_o_aviso_de_amostra_pequena_e_dez_nao(self):
        t = self.relatorio(*[sessao() for _ in range(9)])
        self.assertIn("9 sessões", t)
        self.assertIn("AVISO: amostra pequena (9 sessões, o teste pede 10 por braço)", t)
        t = self.relatorio(*[sessao() for _ in range(10)])
        self.assertIn("10 sessões", t)
        self.assertNotIn("amostra pequena", t)

    def test_uma_so_sessao_diz_sessao_no_singular(self):
        self.assertIn("amostra pequena (1 sessão,", self.relatorio(sessao()))

    def test_minutos_reinicios_por_hora_e_medianas(self):
        # Três sessões do mesmo braço: 10, 20 e 30 min de STREAMING; só a do meio reiniciou uma vez.
        comum = OsReiniciosDoPainel().queda_que_volta()
        t = self.relatorio(sessao(streaming="10m00s", gap="0.2s"), comum.replace("streaming=8m56s", "streaming=20m00s").replace("gap max=0.3s", "gap max=0.9s"),
                           sessao(streaming="30m00s", gap="0.5s"))
        braco = [x for x in t.splitlines() if x.startswith("  espaçamento ligado + codificador do celular: ")]
        self.assertEqual(1, len(braco), t)
        self.assertIn("3 sessões, 60,0 min em STREAMING", braco[0])
        self.assertIn("1 reinícios do painel (1,00/h; 1 sessões com algum)", braco[0])
        self.assertIn("STREAMING 20,0 min; maior silêncio de vídeo 0,5 s", t)

    def test_cada_braco_tem_a_sua_linha_e_as_principais_vem_primeiro(self):
        t = self.relatorio(sessao(True, "SOFTWARE", EM_USO_SW), sessao(True, "SOFTWARE", EM_USO_SW, linha("10:04:21.500", "stream", TROCA)), sessao(False, "PHONE"), sessao(True, "PHONE"))
        braços = [x.split(":")[0].strip() for x in t.split("POR BRAÇO")[1].splitlines() if x.startswith("  ") and not x.startswith("    ")]
        self.assertEqual(["espaçamento ligado + codificador do celular", "espaçamento desligado + codificador do celular", "espaçamento ligado + codificador de software",
                          f"espaçamento ligado + {ab.TROCOU} — à parte, não serve para comparar os braços"], braços)

    def test_a_sessao_que_trocou_avisa_que_o_quadro_chave_mistura_os_dois(self):
        t = self.relatorio(sessao(True, "SOFTWARE", EM_USO_SW, linha("10:04:21.500", "stream", TROCA)))
        self.assertIn("o `video shape` (quadro-chave) destas sessões mistura os dois codificadores", t)

    def test_sem_nenhuma_sessao_nada_e_dito(self):
        self.assertEqual([], ab.texto([]))

    def test_sessao_sem_resumo_fica_fora_dos_minutos_e_e_dita(self):
        t = self.relatorio(sessao(streaming="10m00s"), sessao(com_resumo=False))
        self.assertIn("2 sessões, 10,0 min em STREAMING (1 sem resumo, fora da conta)", t)


class AOpcaoAb(unittest.TestCase):
    def roda(self, *argv):
        saida, erro = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(saida), contextlib.redirect_stderr(erro):
            codigo = ler_registro.main(list(argv))
        return codigo, saida.getvalue(), erro.getvalue()

    def pasta(self):
        d = Path(tempfile.mkdtemp())
        escreve(d, "ride-20261126-100000.log", sessao(True, "PHONE"))
        escreve(d, "ride-20261126-110000.log", sessao(False, "SOFTWARE", EM_USO_SW))
        return d

    def test_ab_numa_pasta_imprime_so_o_relatorio_e_sai_com_zero(self):
        codigo, saida, _ = self.roda("--ab", str(self.pasta()))
        self.assertEqual(0, codigo)
        self.assertIn("SESSÕES (2,", saida)
        self.assertIn("espaçamento ligado + codificador do celular: 1 sessão", saida)
        self.assertIn("espaçamento desligado + codificador de software: 1 sessão", saida)
        self.assertNotIn("PROBLEMAS", saida)

    def test_ab_depois_dos_caminhos_tambem_vale(self):
        codigo, saida, _ = self.roda(str(self.pasta()), "--ab", "--desde", "2026-11-26")
        self.assertEqual(0, codigo)
        self.assertIn("POR BRAÇO", saida)

    def test_ab_le_um_zip_de_coleta_so_com_o_que_o_leitor_usa(self):
        d = Path(tempfile.mkdtemp())
        zip_ = d / "coleta.zip"
        with zipfile.ZipFile(zip_, "w") as z:
            z.writestr("celular-20261126-1200/diag/ride-20261126-100000.log", sessao(True, "PHONE"))
            z.writestr("celular-20261126-1200/diag/ride-20261126-110000.log", sessao(False, "PHONE"))
            z.writestr("celular-20261126-1200/INFO.txt", "nada que o leitor use")
            z.writestr("../fora.txt", "o nome com caminho não escreve fora")
        codigo, saida, _ = self.roda("--ab", str(zip_))
        self.assertEqual(0, codigo)
        self.assertIn("SESSÕES (2,", saida)
        self.assertIn("espaçamento desligado + codificador do celular: 1 sessão", saida)
        self.assertEqual(["coleta.zip"], [p.name for p in d.iterdir()])

    def test_ab_sem_caminho_ou_com_zip_quebrado_ou_arquivo_que_nao_existe_diz_o_erro(self):
        self.assertEqual(2, self.roda("--ab")[0])
        ruim = Path(tempfile.mkdtemp()) / "ruim.zip"
        ruim.write_bytes(b"isto nao e um zip")
        codigo, _, erro = self.roda("--ab", str(ruim))
        self.assertEqual(2, codigo)
        self.assertIn("não consegui abrir o zip", erro)
        codigo, _, erro = self.roda("--ab", "nao-existe.log")
        self.assertEqual(2, codigo)
        self.assertIn("não existe", erro)

    def test_a_ajuda_fala_do_ab(self):
        codigo, saida, _ = self.roda("--help")
        self.assertEqual(0, codigo)
        self.assertIn("--ab", saida)
        self.assertIn("amostra pequena", saida)

    def test_a_leitura_normal_nao_mudou(self):
        _, saida, _ = self.roda(str(self.pasta()))
        self.assertIn("projeção ligada", saida)
        self.assertNotIn("POR BRAÇO", saida)


if __name__ == "__main__":
    unittest.main()
