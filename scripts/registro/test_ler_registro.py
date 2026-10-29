"""Testes do ler_registro.py.

    python -B scripts\\registro\\test_ler_registro.py
"""
import sys
import tempfile
import unittest
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))
import ler_registro  # noqa: E402

CABECALHO = "10:00:00.000  +     0ms  ==== process start: app — Google Pixel, Android 16 (API 36) ====\n"


def linha(hora, etiqueta, texto):
    return f"{hora}  +     0ms  [{etiqueta}] {texto}\n"


def projecao(*meio, desligada=True):
    texto = CABECALHO + "10:00:01.000  +  1000ms  ---- projeção ligada: projection ----\n" + "".join(meio)
    if desligada:
        texto += "10:09:00.000  +  1000ms  ---- projeção desligada: projection ----\n"
    return texto


BOA = projecao(
    linha("10:00:02.000", "aa", "[AA] AaReceiver: connected to the head unit server on :5277, starting the handshake"),
    # "Android Auto conectado" comes from the answered handshake, not the dial.
    linha("10:00:02.500", "aa", "[AA] Handshake: Status OK sent (8). Handshake successful."),
    linha("10:00:03.000", "wifi", "connected 'RE_****34' in 900ms"),
    linha("10:00:04.000", "session", "AUTHENTICATING → STREAMING"),
    linha("10:00:05.000", "dash", "dash DECODED first IDR (09 06 55) — video accepted ✓"),
    linha("10:00:10.000", "stream", "video fps target=16 sent=15.9 (80frames/5.0s) composed=16.0 dash=15.0 kbps=900"),
    linha("10:00:15.000", "stream", "video fps target=16 sent=15.9 (80frames/5.0s) composed=16.0 dash=15.0 kbps=900"),
    linha("10:00:20.000", "device", "device battery=80% charging=no temp=31.0C volt=4.100V thermal=none headroom=0.40 [start]"),
    linha("10:05:00.000", "mark", "MARCA DO PILOTO 1"),
    linha("10:08:50.000", "device", "device battery=76% charging=no temp=33.0C volt=4.000V thermal=none headroom=0.45 [30 s]"),
    linha("10:09:00.000", "projection", "resumo duration=9m00s streaming=8m56s drops dash=0 aa=0 watchdogs=0 frames=8500 fps avg=15.9 min=15.0 gap max=0.3s thermal max=none battery 80→76% (-4) marks=1"),
)


class Ler(unittest.TestCase):
    def arquivo(self, texto, nome="ride-20261004-100000.log"):
        d = Path(tempfile.mkdtemp())
        (d / nome).write_text(texto, encoding="utf-8")
        return d / nome

    def problemas(self, texto):
        return ler_registro.problemas(ler_registro.ler([self.arquivo(texto)]))

    def test_uma_projecao_boa_nao_acusa_nada_e_conta_a_historia(self):
        linhas = ler_registro.ler([self.arquivo(BOA)])
        self.assertEqual([], ler_registro.problemas(linhas))
        tempo = "\n".join(ler_registro.linha_do_tempo(linhas))
        for esperado in ("projeção ligada", "Android Auto conectado", "painel no Wi-Fi", "transmitindo",
                         "primeiro quadro confirmado", "marca do piloto 1", "bateria 80% → 76%", "projeção desligada", "resumo"):
            self.assertIn(esperado, tempo)

    def test_transmitindo_sem_confirmacao_do_primeiro_quadro(self):
        texto = BOA.replace("dash DECODED first IDR (09 06 55) — video accepted ✓", "algo outro")
        self.assertTrue(any("sem confirmação do primeiro quadro" in p for p in self.problemas(texto)))

    def test_o_aviso_de_10_s_do_painel_novo_conta_como_aviso_e_nao_como_quadro_decodificado(self):
        novo = "dash notice 09 06 55 at +10.2 s of the burst (the dash's own 10 s mark; not proof of a decoded frame)"
        texto = BOA.replace("dash DECODED first IDR (09 06 55) — video accepted ✓", novo)
        self.assertFalse(any("sem confirmação do primeiro quadro" in p for p in self.problemas(texto)))
        self.assertNotIn("primeiro quadro confirmado pelo painel", "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)]))))
        self.assertIn("aviso de 10 s do painel (09 06 55)", "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)]))))

    def test_o_nivel_no_comeco_do_texto_fica_a_parte_e_nao_atrapalha_nada(self):
        texto = projecao(
            linha("10:01:00.000", "aa", "[AA] AaReceiver: connected to the head unit server on :5277, starting the handshake"),
            linha("10:03:00.000", "wifi", "W: link lost — reconnecting in 4000ms"),
            linha("10:03:04.000", "error", "E: RX watchdog: dash silent 4s → link lost"),
            linha("10:03:05.000", "error", "E: session fail: Dash stopped responding — connection lost"),
            linha("10:03:06.000", "ui", "the rider did something"),
        )
        linhas = ler_registro.ler([self.arquivo(texto)])
        niveis = [(x.etiqueta, x.nivel, x.texto.split(":")[0].split(" — ")[0]) for x in linhas if x.etiqueta in ("wifi", "error", "ui")]
        self.assertEqual([("wifi", "W", "link lost"), ("error", "E", "RX watchdog"), ("error", "E", "session fail"),
                          ("ui", "", "the rider did something")], niveis)
        tempo = "\n".join(ler_registro.linha_do_tempo(linhas))
        self.assertIn("Wi-Fi da moto caiu", tempo)
        self.assertIn("vigia: RX watchdog: dash silent 4s", tempo)

    def test_a_linha_dos_ajustes_em_uso_abre_a_linha_do_tempo_da_projecao(self):
        texto = projecao(
            linha("10:00:01.500", "settings", "settings in use: aaSlowPace=true detailedLog=false reduction=DEFAULT"),
            linha("10:01:00.000", "aa", "[AA] AaReceiver: connected to the head unit server on :5277, starting the handshake"),
        )
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)])))
        self.assertIn("settings in use: aaSlowPace=true detailedLog=false reduction=DEFAULT", tempo)

    # ── quedas, buracos, temperatura e vídeo: o que o leitor já contou errado ─────────────────────────────────────

    def test_uma_queda_do_wifi_e_uma_so_mesmo_com_o_vigia_logo_depois(self):
        # Wi-Fi caiu e perdido e, 4 s depois, o vigia do painel: o leitor contava três "quedas seguidas".
        texto = projecao(
            linha("10:01:00.000", "wifi", "W: link lost — reconnecting in 4000ms"),
            linha("10:01:00.003", "system", "bike network lost"),
            linha("10:01:03.900", "error", "E: RX watchdog: dash silent 4s → link lost"),
        )
        linhas = ler_registro.ler([self.arquivo(texto)])
        self.assertFalse(any("quedas seguidas" in x for x in ler_registro.problemas(linhas)))
        tempo = [x for x in ler_registro.linha_do_tempo(linhas) if "queda (" in x]
        self.assertEqual(1, len(tempo), tempo)
        self.assertIn("Wi-Fi da moto", tempo[0])
        self.assertIn("painel calado", tempo[0])
        self.assertTrue(tempo[0].startswith("10:01:00.000"), tempo[0])

    def test_duas_quedas_separadas_continuam_sendo_seguidas(self):
        texto = projecao(
            linha("10:01:00.000", "wifi", "W: link lost — reconnecting in 4000ms"),
            linha("10:01:03.900", "error", "E: RX watchdog: dash silent 4s → link lost"),
            linha("10:01:40.000", "wifi", "W: link lost — reconnecting in 4000ms"),
        )
        seguidas = [x for x in self.problemas(texto) if "quedas seguidas" in x]
        self.assertEqual(1, len(seguidas), seguidas)
        self.assertIn("36 s depois da anterior", seguidas[0])  # contado do fim da primeira queda (10:01:03.9)

    def test_o_wifi_de_antes_da_queda_entra_na_linha_do_tempo(self):
        texto = projecao(
            linha("10:00:30.000", "link", "rssi=-56 dBm tx=72 rx=72 Mbps 2.4 GHz powersave=off screen=on | last 60 s, every 5 s: rssi -55 -56 | tx 72 72"),
            linha("10:01:00.000", "wifi", "W: link lost — reconnecting in 4000ms"),
        )
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)])))
        # No `bike=` in the registro: the [link] look is the phone's own Wi-Fi, and it is said so.
        self.assertIn("Wi-Fi do celular antes da queda (o do painel não está no registro): rssi=-56 dBm tx=72 rx=72 Mbps 2.4 GHz", tempo)
        self.assertNotIn("Wi-Fi do painel antes da queda", tempo)

    # ── o painel desligando com a moto não é problema ─────────────────────────────────────────────────────────────

    FIRME = "rssi=-58 dBm tx=72 rx=72 Mbps 2.4 GHz powersave=on screen=on | last 60 s, every 5 s: rssi -50 -58 -58 -58 -58 | tx 72 72 72 72 72"

    def sumiu_de_uma_vez(self, firme):
        return projecao(
            linha("10:00:30.000", "link", firme),
            linha("10:01:00.000", "wifi", "W: link lost — reconnecting in 4000ms"),
            linha("10:01:00.003", "system", "bike network lost"),
            linha("10:01:04.200", "error", "E: RX watchdog: dash silent 4s → link lost"),
        )

    def test_wifi_firme_que_some_de_uma_vez_e_o_painel_desligando_e_nao_um_problema(self):
        linhas = ler_registro.ler([self.arquivo(self.sumiu_de_uma_vez(self.FIRME))])
        self.assertEqual([], ler_registro.problemas(linhas))
        tempo = [x for x in ler_registro.linha_do_tempo(linhas) if "painel desligou" in x]
        self.assertEqual(1, len(tempo), tempo)
        self.assertTrue(tempo[0].startswith("10:01:00.000"), tempo[0])

    def test_vigia_depois_de_wifi_fraco_ou_que_oscilava_continua_problema(self):
        for historico in ("rssi -60 -72 -85 -88 | tx 72 40 12 6", "rssi -80 -70 -60 -62 | tx 6 24 52 58"):
            firme = f"rssi=-88 dBm tx=6 rx=6 Mbps 2.4 GHz powersave=on screen=on | last 60 s, every 5 s: {historico}"
            p = ler_registro.problemas(ler_registro.ler([self.arquivo(self.sumiu_de_uma_vez(firme))]))
            self.assertTrue(any("vigia" in x for x in p), (historico, p))

    def test_sem_o_historico_do_sinal_no_registro_nao_se_afirma_que_o_painel_desligou(self):
        texto = projecao(linha("10:01:00.000", "wifi", "W: link lost — reconnecting in 4000ms"),
                         linha("10:01:04.200", "error", "E: RX watchdog: dash silent 4s → link lost"))
        linhas = ler_registro.ler([self.arquivo(texto)])
        self.assertTrue(any("vigia" in x for x in ler_registro.problemas(linhas)))
        self.assertFalse(any("painel desligou" in x for x in ler_registro.linha_do_tempo(linhas)))

    def test_o_aviso_de_sem_09_06_55_que_so_cita_o_vigia_nao_e_queda(self):
        texto = projecao(linha("10:01:00.000", "dash", "W: no 09 06 55 after 15s, but the dash answers: the stream goes on (the RX watchdog watches it)"))
        linhas = ler_registro.ler([self.arquivo(texto)])
        self.assertFalse(any("queda" in x for x in ler_registro.linha_do_tempo(linhas)))

    def test_buraco_nas_linhas_de_5_s_so_com_o_painel_no_ar(self):
        # O painel caiu e voltou 3 minutos depois: sem vídeo no meio não é buraco.
        texto = projecao(
            linha("10:00:10.000", "stream", "video fps target=4 sent=3.9 (20frames/5.1s)"),
            linha("10:00:11.000", "error", "E: session fail: Dash stopped responding — connection lost"),
            linha("10:00:12.000", "stream", "video loop stop (0 frames held back before a keyframe)"),
            linha("10:03:10.000", "session", "→ STREAMING"),
            linha("10:03:15.000", "stream", "video fps target=4 sent=3.9 (20frames/5.1s)"),
        )
        self.assertFalse(any("buraco" in x for x in self.problemas(texto)), self.problemas(texto))

    def test_temperatura_com_carregador_ou_tela_apagada_nao_conta(self):
        texto = projecao(
            linha("10:00:20.000", "device", "device battery=80% charging=yes temp=31.0C thermal=none screen=on [start]"),
            linha("10:04:00.000", "device", "device battery=82% charging=yes temp=39.0C thermal=none screen=on [30 s]"),
            linha("10:06:00.000", "device", "device battery=82% charging=no temp=40.0C thermal=none screen=off [30 s]"),
        )
        self.assertFalse(any("temperatura" in x for x in self.problemas(texto)), self.problemas(texto))

    def test_temperatura_sem_carregador_e_com_a_tela_ligada_conta_e_diz_que_condicao(self):
        texto = projecao(
            linha("10:00:20.000", "device", "device battery=80% charging=no temp=31.0C thermal=none screen=on [start]"),
            linha("10:04:00.000", "device", "device battery=78% charging=no temp=40.0C thermal=none screen=on [30 s]"),
        )
        achados = [x for x in self.problemas(texto) if "temperatura" in x]
        self.assertEqual(1, len(achados), achados)
        self.assertIn("31.0", achados[0])
        self.assertIn("40.0", achados[0])
        self.assertIn("sem carregador, tela ligada", achados[0])

    def test_o_resumo_do_video_diz_a_taxa_o_quadro_o_quadro_chave_a_reducao_e_as_trocas_de_perfil(self):
        texto = projecao(
            linha("10:00:05.000", "stream", "video loop start: 4 fps (ceiling 4), keyframe 1s fixed, 400 kbps (400 chosen) CBR, clock 0% slow, reduction SOFT_SHARP"),
            linha("10:00:10.000", "stream", "video fps target=4 sent=3.9 (20frames/5.1s) composed=3.9 kbps=300 set=400k frame=9000B source=61 drawn=20 repeated=0 compose=3.7ms encode=13.9ms late=0 full=true thermal=OK kf=1s idr=5(max 14000B/12pkt) sync=0 auto=4fps [at ceiling]"),
            linha("10:00:15.000", "stream", "video fps target=2 sent=2.0 (10frames/5.1s) composed=2.0 kbps=100 set=400k frame=6000B source=0 drawn=0 repeated=10 compose=3.0ms encode=12.9ms late=0 full=false thermal=OK kf=1s idr=5(max 9000B/9pkt) sync=0 auto=4fps [idle]"),
            linha("10:00:16.000", "stream", "nav profile: FULL -> REDUCED (data), pace 2 fps, encoder 4 fps"),
            linha("10:00:17.000", "stream", "nav profile: REDUCED -> FULL (data), pace 4 fps, encoder 4 fps"),
            linha("10:00:18.000", "stream", "profile 2 measured 310 kbps, over half of 400: the bitrate is set to half live, no new encoder"),
            linha("10:00:19.000", "dash", "notice 09 06 55 #1, 8,1 s into the stream, STREAMING: answered with 06 11"),
            linha("10:00:20.000", "stream", "video loop stop (0 frames held back before a keyframe, 0 keyframes withheld for lack of SPS/PPS, 31 packets discarded)"),
        )
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)])))
        linha_video = next(x for x in tempo.splitlines() if "vídeo:" in x)
        for parte in ("kbps médio 300 em movimento (pior janela 300)", "quadro médio 7500 B", "quadros-chave 10 (o maior 14000 B em 12 pacotes)",
                      "redução SOFT_SHARP", "2 trocas de perfil", "perfil 2 limitou o bitrate", "31 pacotes descartados",
                      "aviso 09 06 55 #1 aos 8,1 s"):
            self.assertIn(parte, linha_video)

    def test_o_resumo_do_video_so_diz_o_que_o_registro_tem(self):
        texto = projecao(linha("10:00:10.000", "stream", "video fps target=16 sent=15.9 (80frames/5.0s) composed=16.0 dash=15.0 kbps=900 set=1024k frame=7500B full=true"))
        linha_video = next(x for x in ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)])) if "vídeo:" in x)
        self.assertIn("kbps médio 900", linha_video)
        for ausente in ("quadros-chave", "redução", "trocas de perfil", "descartados", "aviso 09 06 55"):
            self.assertNotIn(ausente, linha_video)

    def test_sem_nenhuma_linha_de_video_nao_ha_resumo(self):
        self.assertFalse(any("vídeo:" in x for x in ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(projecao())]))))

    def test_vigias(self):
        texto = projecao(linha("10:00:30.000", "error", "RX watchdog: dash silent 6s → link lost"),
                         linha("10:01:00.000", "aa", "[AA] Decoder restart requested: stalled 3000ms"))
        p = self.problemas(texto)
        self.assertTrue(any("vigia" in x and "RX watchdog" in x for x in p), p)
        self.assertTrue(any("vigia" in x and "Decoder restart" in x for x in p), p)

    def test_o_wifi_no_instante_do_vigia_entra_na_linha_do_tempo_inteiro(self):
        falha = ("link at failure: rssi=-88 dBm tx=6 rx=6 Mbps 5 GHz powersave=off screen=on | "
                 "last 60 s, every 5 s: rssi -58 -60 -61 -70 -78 -85 -88 | tx 72 72 72 58 24 6 6")
        texto = projecao(linha("10:00:29.900", "link", falha),
                         linha("10:00:30.000", "error", "RX watchdog: dash silent 6s → link lost"))
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)])))
        self.assertIn("Wi-Fi na falha: rssi=-88 dBm", tempo)
        self.assertIn("rssi -58 -60 -61 -70 -78 -85 -88 | tx 72 72 72 58 24 6 6", tempo)

    def test_buraco_nas_linhas_de_5_s(self):
        texto = projecao(linha("10:00:10.000", "stream", "video fps target=16 sent=15.9"),
                         linha("10:00:15.000", "stream", "video fps target=16 sent=15.9"),
                         linha("10:00:22.500", "stream", "video fps target=16 sent=15.9"))
        p = self.problemas(texto)
        self.assertTrue(any("buraco" in x and "7.5 s" in x for x in p), p)

    def test_o_resumo_com_painel_sem_imagem_por_mais_de_10_s_e_listado(self):
        resumo = ("resumo duration=50m00s streaming=40m00s drops dash=2 aa=0 retries dash=0 watchdogs=2 (dash-rx=2) frames=9000 "
                  "fps avg=3.7 min=0.0 gap max=0.3s dark=2/3m02s (max 1m08s) thermal max=none battery 80→60% (-20) marks=0")
        texto = BOA.replace("resumo duration=9m00s streaming=8m56s drops dash=0 aa=0 watchdogs=0 frames=8500 fps avg=15.9 min=15.0 gap max=0.3s thermal max=none battery 80→76% (-4) marks=1", resumo)
        self.assertTrue(any("painel sem imagem — 2 interrupções, 3m02s no total, a maior de 1m08s" in p for p in self.problemas(texto)))

    def test_o_resumo_com_interrupcao_curta_ou_sem_dark_nao_acusa(self):
        curto = ("resumo duration=50m00s streaming=40m00s drops dash=1 aa=0 retries dash=0 watchdogs=1 (dash-rx=1) frames=9000 "
                 "fps avg=3.7 min=0.0 gap max=0.3s dark=1/8s (max 8s) thermal max=none battery 80→60% (-20) marks=0")
        texto = BOA.replace("resumo duration=9m00s streaming=8m56s drops dash=0 aa=0 watchdogs=0 frames=8500 fps avg=15.9 min=15.0 gap max=0.3s thermal max=none battery 80→76% (-4) marks=1", curto)
        self.assertFalse(any("painel sem imagem" in p for p in self.problemas(texto)))
        self.assertFalse(any("painel sem imagem" in p for p in self.problemas(BOA)))

    def test_buraco_no_resumo(self):
        texto = BOA.replace("gap max=0.3s", "gap max=2.4s")
        self.assertTrue(any("buraco" in x and "2.4 s" in x for x in self.problemas(texto)))

    def test_quedas_seguidas(self):
        texto = projecao(linha("10:01:00.000", "wifi", "link lost — reconnecting in 2000ms"),
                         linha("10:01:40.000", "error", "RX watchdog: dash silent 6s → link lost"))
        self.assertTrue(any("quedas seguidas" in x for x in self.problemas(texto)))

    def test_quedas_longe_uma_da_outra_nao_sao_seguidas(self):
        texto = projecao(linha("10:01:00.000", "wifi", "link lost — reconnecting in 2000ms"),
                         linha("10:05:00.000", "wifi", "link lost — reconnecting in 2000ms"))
        self.assertFalse(any("quedas seguidas" in x for x in self.problemas(texto)))

    def test_o_fim_da_sessao_que_o_desligamento_provoca_nao_e_queda(self):
        texto = projecao(
            linha("10:08:50.000", "wifi", "link lost — reconnecting in 2000ms"),
            linha("10:08:59.000", "aa", "[AA] AaReceiver: session ended (clean=false, userExit=false)"))
        linhas = ler_registro.ler([self.arquivo(texto)])
        self.assertNotIn("Android Auto caiu", "\n".join(ler_registro.linha_do_tempo(linhas)))
        self.assertFalse(any("quedas seguidas" in x for x in ler_registro.problemas(linhas)))

    def test_o_fim_que_o_reau_pede_com_o_bye_nao_e_queda(self):
        # Uma sessão nova do Android Auto para os ajustes: versões antigas do app registravam o fim como clean=false.
        texto = projecao(
            linha("10:03:00.000", "aa", "[AA] AapTransport stopping and sending byebye"),
            linha("10:03:00.150", "aa", "[AA] AaReceiver: session ended (clean=false, userExit=false)"),
            linha("10:03:30.000", "wifi", "link lost — reconnecting in 2000ms"))
        linhas = ler_registro.ler([self.arquivo(texto)])
        self.assertNotIn("Android Auto caiu", "\n".join(ler_registro.linha_do_tempo(linhas)))
        self.assertFalse(any("quedas seguidas" in x for x in ler_registro.problemas(linhas)))

    def test_o_fim_sem_bye_ainda_e_queda(self):
        texto = projecao(
            linha("10:02:00.000", "aa", "[AA] AapTransport stopping and sending byebye"),
            linha("10:03:00.150", "aa", "[AA] AaReceiver: session ended (clean=false, userExit=false)"))
        linhas = ler_registro.ler([self.arquivo(texto)])
        self.assertIn("Android Auto caiu", "\n".join(ler_registro.linha_do_tempo(linhas)))

    def test_temperatura_subindo(self):
        texto = projecao(
            linha("10:00:20.000", "device", "device battery=80% charging=no temp=31.0C thermal=none [start]"),
            linha("10:04:00.000", "device", "device battery=78% charging=no temp=38.5C thermal=moderate [change: thermal]"))
        p = self.problemas(texto)
        self.assertTrue(any("temperatura subindo" in x and "moderate" in x for x in p), p)
        self.assertTrue(any("temperatura subindo" in x and "31.0" in x and "38.5" in x for x in p), p)

    def test_temperatura_morna_que_subiu_pouco_nao_acusa(self):
        texto = projecao(
            linha("10:00:20.000", "device", "device battery=80% charging=no temp=31.0C thermal=none screen=on [start]"),
            linha("10:04:00.000", "device", "device battery=78% charging=no temp=37.0C thermal=none screen=on [30 s]"))
        self.assertFalse(any("temperatura" in x for x in self.problemas(texto)), self.problemas(texto))

    # ── o Wi-Fi do painel que reinicia, a autenticação, o pedido que expira e a imagem que some (20/10/2026) ─────────────────

    def queda_que_volta(self, depois_s, antes="bike=-55dBm/2447MHz/58Mbps", volta="bike=-50dBm/2427MHz/72Mbps"):
        h = lambda s: f"10:{(1 + int(s) // 60):02d}:{int(s) % 60:02d}.000"
        return projecao(
            linha("10:00:30.000", "link", self.FIRME),
            linha("10:00:50.000", "device", f"device battery=80% charging=no temp=31.0C thermal=none screen=on {antes} [30 s]"),
            linha("10:01:00.000", "wifi", "W: link lost — reconnecting in 4000ms (after 59 s on 2447 MHz; rssi -55)"),
            linha("10:01:00.003", "system", "bike network lost"),
            linha(h(depois_s), "wifi", "connected 'RE_****30' in 900ms"),
            linha(h(depois_s), "system", "bike network available"),
            linha(h(depois_s + 20), "device", f"device battery=80% charging=no temp=31.0C thermal=none screen=on {volta} [30 s]"),
        )

    def test_wifi_do_painel_que_volta_em_menos_de_2_min_e_reinicio_e_nao_moto_desligada(self):
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(self.queda_que_volta(22))])))
        self.assertIn("Wi-Fi do painel reiniciou (voltou em 22 s, 2447 → 2427 MHz)", tempo)
        self.assertIn("Wi-Fi do painel reiniciou", tempo)
        self.assertNotIn("moto desligada", tempo)

    def test_o_canal_de_antes_e_de_depois_vem_do_registro(self):
        texto = self.queda_que_volta(30)
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)])))
        self.assertIn("2447 → 2427 MHz", tempo)
        igual = self.queda_que_volta(30, volta="bike=-50dBm/2447MHz/72Mbps")
        self.assertIn("mesmo canal 2447 MHz", "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(igual)]))))

    def test_a_linha_de_canal_do_app_que_vem_antes_do_connected_e_lida(self):
        texto = projecao(
            linha("10:01:00.000", "wifi", "W: link lost — reconnecting in 4000ms (after 59 s on 2447 MHz; rssi -55)"),
            linha("10:01:00.003", "system", "bike network lost"),
            linha("10:01:22.000", "wifi", "the dash's Wi-Fi is back on another channel (2447 → 2427 MHz): its access point restarted"),
            linha("10:01:22.010", "wifi", "connected 'RE_****30' in 900ms"),
            linha("10:01:22.011", "system", "bike network available"),
        )
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)])))
        self.assertIn("Wi-Fi do painel reiniciou (voltou em 22 s, 2447 → 2427 MHz)", tempo)

    def test_volta_no_mesmo_canal_ou_sem_canal_so_diz_que_voltou(self):
        igual = self.queda_que_volta(30, volta="bike=-50dBm/2447MHz/72Mbps")
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(igual)])))
        self.assertIn("Wi-Fi do painel caiu e voltou (voltou em 30 s, mesmo canal 2447 MHz)", tempo)
        self.assertNotIn("reiniciou", tempo)

    def test_o_vigia_de_silencio_com_o_wifi_de_pe_nao_e_queda_de_wifi_que_voltou(self):
        texto = projecao(
            linha("10:00:30.000", "link", self.FIRME),
            linha("10:01:00.000", "error", "E: RX watchdog: dash silent 4s → link lost"),
            linha("10:01:20.000", "wifi", "connected 'RE_****30' in 900ms"),
        )
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)])))
        self.assertNotIn("reiniciou", tempo)
        self.assertNotIn("caiu e voltou", tempo)

    def test_wifi_do_painel_que_nao_volta_em_2_min_continua_moto_desligada(self):
        texto = projecao(
            linha("10:00:30.000", "link", self.FIRME),
            linha("10:01:00.000", "wifi", "W: link lost — reconnecting in 4000ms"),
            linha("10:01:00.003", "system", "bike network lost"),
            linha("10:03:30.000", "wifi", "connected 'RE_****30' in 900ms"))
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)])))
        self.assertIn("painel desligou (moto desligada?)", tempo)
        self.assertNotIn("reiniciou", tempo)

    def test_autenticacao_que_expira_e_pedido_de_rede_com_o_painel_na_varredura_entram_nos_problemas(self):
        texto = projecao(
            linha("10:01:00.000", "error", "E: session fail: Auth timed out — the dash answered but never accepted (no 07 01 01). Check SSID matches 'RE_****30'."),
            linha("10:02:00.000", "wifi", "W: unavailable after 30079ms (initial connect) — giving up, seenInScan=true quick=0 joined=false"))
        p = self.problemas(texto)
        self.assertTrue(any("autenticação do painel falhou — Auth timed out" in x for x in p), p)
        self.assertTrue(any("pedido de rede expirou com o painel na varredura" in x for x in p), p)

    def test_imagem_que_sumiu_por_mais_de_10_s_sem_o_dark_e_calculada_do_video_parar_ao_voltar(self):
        texto = projecao(
            linha("10:01:00.000", "session", "→ STREAMING"),
            linha("10:02:00.000", "video", "video loop stop"),
            linha("10:03:10.000", "session", "→ STREAMING"),
            linha("10:03:20.000", "video", "video loop stop"),
            linha("10:03:25.000", "session", "→ STREAMING"))
        p = [x for x in self.problemas(texto) if "painel sem imagem" in x]
        self.assertEqual(1, len(p), p)
        self.assertIn("70 s", p[0])

    def test_o_aviso_de_10_s_em_qualquer_estado_nao_e_falta_de_quadro_confirmado(self):
        texto = BOA.replace("dash DECODED first IDR (09 06 55) — video accepted ✓",
                            "dash sent 09 06 55 while AUTHENTICATING, before any video: not the first frame")
        self.assertFalse(any("sem confirmação do primeiro quadro" in p for p in self.problemas(texto)))

    # ── o Android Auto que fecha junto com a tela (12:01:07 e 12:13:36 de 20/10/2026, trechos reais) ───────────────────────

    def fechou_com_a_tela(self):
        return projecao(
            linha("10:01:07.075", "aa", "[AA] AapRead: the link ended after 34 s; the last message came 45 ms before; phone: charger out, screen on, doze off"),
            linha("10:01:07.079", "aa", "[AA] AaReceiver: session ended (clean=false, userExit=false)"),
            linha("10:01:07.605", "system", "screen off"),
            linha("10:01:08.238", "aa", "[AA] AaReceiver: connected to the head unit server on :5277, starting the handshake"),
            linha("10:01:08.754", "aa", "[AA] Handshake: Status OK sent (8). Handshake successful."),
            linha("10:01:08.859", "system", "screen on"),
            linha("10:01:09.907", "aa", "[AA] H.264 SPS parsed: 800x480"),
        )

    def test_android_auto_que_fecha_junto_com_a_tela_nao_e_queda_e_diz_quanto_levou_para_voltar(self):
        linhas = ler_registro.ler([self.arquivo(self.fechou_com_a_tela())])
        tempo = "\n".join(ler_registro.linha_do_tempo(linhas))
        self.assertIn("Android Auto fechou junto com a tela (reconectou em 1,2 s; imagem nova em 2,8 s)", tempo)
        self.assertNotIn("queda (Android Auto caiu)", tempo)
        self.assertEqual([], [p for p in ler_registro.problemas(linhas) if "quedas seguidas" in p or "queda" in p])

    def test_o_fim_com_a_mudanca_de_tela_so_na_olhada_do_celular_tambem_conta(self):
        texto = projecao(
            linha("10:03:36.754", "aa", "[AA] AaReceiver: session ended (clean=false, userExit=false)"),
            linha("10:03:39.064", "device", "device battery=80% charging=no temp=31.0C thermal=none screen=on interactive=yes [change: screen]"),
        )
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)])))
        self.assertIn("Android Auto fechou junto com a tela", tempo)

    def test_android_auto_que_cai_longe_de_qualquer_evento_de_tela_continua_queda(self):
        texto = projecao(
            linha("10:01:07.079", "aa", "[AA] AaReceiver: session ended (clean=false, userExit=false)"),
            linha("10:01:12.000", "system", "screen off"),
        )
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)])))
        self.assertIn("queda (Android Auto caiu)", tempo)
        self.assertNotIn("junto com a tela", tempo)

    def test_a_idade_do_painel_e_o_custo_da_chave_entram_na_linha_do_tempo_e_a_chave_tardia_nos_problemas(self):
        texto = projecao(
            linha("10:01:00.000", "auth", "dash heartbeat age 12 s at the first packet"),
            linha("10:01:15.000", "auth", "key arrived 14.3 s after the first greeting, after 6 resends"),
        )
        linhas = ler_registro.ler([self.arquivo(texto)])
        tempo = "\n".join(ler_registro.linha_do_tempo(linhas))
        self.assertIn("auth: idade do painel: dash heartbeat age 12 s at the first packet", tempo)
        self.assertIn("auth: chave: key arrived 14.3 s after the first greeting, after 6 resends", tempo)
        self.assertTrue(any("só veio depois de 6 reenvios da saudação (14.3 s)" in p for p in ler_registro.problemas(linhas)))

    def test_a_chave_que_veio_logo_nao_e_problema(self):
        texto = projecao(linha("10:01:00.000", "auth", "key arrived 0.2 s after the first greeting, after 0 resends"),
                         linha("10:02:00.000", "auth", "key arrived 3.1 s after the first greeting, after 1 resend"))
        self.assertFalse(any("chave do painel" in p for p in self.problemas(texto)))

    def test_travamento(self):
        texto = projecao(linha("10:02:00.000", "error", "the app crashed: IllegalStateException at X.kt:10 (crash-1.txt)"))
        self.assertTrue(any("travou" in x for x in self.problemas(texto)))
        self.assertIn("o app travou", "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)]))))

    # ── the reader must not say "Android Auto conectado" for a handshake Android Auto never answered ──

    def test_conectado_so_depois_da_negociacao_respondida(self):
        texto = projecao(
            linha("10:00:02.000", "aa", "[AA] AaReceiver: connected to the head unit server on :5277, starting the handshake"),
            linha("10:00:12.000", "aa", "[AA] W: AaReceiver: the handshake failed"),
        )
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)])))
        self.assertNotIn("Android Auto conectado", tempo)
        self.assertIn("Android Auto não respondeu à negociação", tempo)

    def test_tres_negociacoes_seguidas_sem_resposta(self):
        falhas = [linha(f"10:0{i}:00.000", "aa", "[AA] W: AaReceiver: the handshake failed") for i in range(2, 5)]
        p = self.problemas(projecao(*falhas))
        self.assertTrue(any("aceita e não responde" in x and "3" in x for x in p), p)

    def test_duas_sem_resposta_e_uma_boa_nao_acusam(self):
        texto = projecao(
            linha("10:02:00.000", "aa", "[AA] W: AaReceiver: the handshake failed"),
            linha("10:03:00.000", "aa", "[AA] W: AaReceiver: the handshake failed"),
            linha("10:04:00.000", "aa", "[AA] Handshake: Status OK sent (8). Handshake successful."),
            linha("10:05:00.000", "aa", "[AA] W: AaReceiver: the handshake failed"),
        )
        self.assertFalse(any("aceita e não responde" in x for x in self.problemas(texto)))

    def test_android_auto_calado(self):
        texto = projecao(linha("10:02:00.000", "aa", "[AA] W: AapRead: WiFi read timeout (15s) - connection lost."))
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)])))
        self.assertIn("Android Auto calado (15 s sem mandar nada)", tempo)
        self.assertTrue(any("calado" in x for x in self.problemas(texto)))

    def test_uma_pasta_inteira_em_ordem(self):
        d = Path(tempfile.mkdtemp())
        (d / "ride-20261004-100000.log").write_text(BOA, encoding="utf-8")
        (d / "logcat-20261004-100000.txt").write_text("não é registro", encoding="utf-8")
        linhas = ler_registro.ler([d])
        self.assertTrue(linhas)
        self.assertTrue(all(x.arquivo.startswith("ride-") for x in linhas))

    # ── with the detailed log on, ~98% of the lines are [detail] and nothing here reads them ──

    def test_as_linhas_detail_ficam_de_fora_e_as_outras_todas_em_ordem(self):
        detalhe = [linha(f"10:00:0{i}.500", "detail", f"aa rx ch={i} type=7 flags=0x0F size=6") for i in range(2, 6)]
        texto = projecao(
            detalhe[0],
            linha("10:00:03.000", "wifi", "connected 'RE_****34' in 900ms"),
            detalhe[1], detalhe[2],
            linha("10:00:04.000", "session", "AUTHENTICATING → STREAMING"),
            detalhe[3],
            linha("10:05:00.000", "mark", "MARCA DO PILOTO 1"),
        )
        linhas = ler_registro.ler([self.arquivo(texto)])
        self.assertEqual([
            ("", "==== process start: app — Google Pixel, Android 16 (API 36) ===="),
            ("", "---- projeção ligada: projection ----"),
            ("wifi", "connected 'RE_****34' in 900ms"),
            ("session", "AUTHENTICATING → STREAMING"),
            ("mark", "MARCA DO PILOTO 1"),
            ("", "---- projeção desligada: projection ----"),
        ], [(x.etiqueta, x.texto) for x in linhas])
        self.assertFalse(any(x.etiqueta == "detail" for x in linhas))

    # ── how long the Android Auto read thread waits inside the decoder ──

    def test_a_leitura_presa_no_decodificador_mostra_a_pior_janela(self):
        def stats(hora, total, maior):
            return linha(hora, "aa", f"[AA] aa-stats 5.0s: msgs=80 (ch2=75) nav=0 units=75 bytes=50KB key=1 pieces=0 "
                                     f"assembled=0 lost=0 focus=0 sensor=0 decoded=74 decode=6/9ms "
                                     f"readHeld={total}/{maior}ms | total units=75 key=1 assembled=0 lost=0 decoded=74")
        texto = projecao(stats("10:00:10.000", "12.0", "3.1"), stats("10:00:15.000", "140.5", "80.2"),
                         stats("10:00:20.000", "30.0", "95.0"))
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)])))
        self.assertIn("leitura do Android Auto presa no decodificador: pior janela 140.5 ms em 5 s (10:00:15.000), "
                      "pior vez 95.0 ms (10:00:20.000)", tempo)

    def test_sem_readheld_no_registro_nao_diz_nada_da_leitura(self):
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(BOA)])))
        self.assertNotIn("presa no decodificador", tempo)

    def test_um_registro_de_verdade_do_emulador(self):
        amostra = AQUI / "amostra_emulador.txt"
        linhas = ler_registro.ler([amostra])
        tempo = "\n".join(ler_registro.linha_do_tempo(linhas))
        self.assertIn("projeção ligada", tempo)
        self.assertIn("transmitindo", tempo)
        self.assertIn("primeiro quadro confirmado", tempo)
        self.assertIn("resumo", tempo)
        ler_registro.problemas(linhas)  # não quebra num registro real


# Pieces of a real log (19/10/2026, edição Completa, Android Auto 17.8): the walk that turns the server on, from "projeção ligada" on.
SERVIDOR_1944 = """19:44:13.606  ---- projeção ligada: projection ----
19:44:13.683  +    76ms  [aa-server] AaServerStarter: start (the port is closed) on Android Auto 17.8.663874-release; texts from Android Auto: 7 of 7, the table for the rest
19:44:13.683  +    77ms  [aa-server] AaServerStarter: start walk begins (last Stop touched never in this process, last projection end never in this process)
19:44:13.754  +   148ms  [aa-server] AaServerStarter: +60 ms opened the settings
19:44:15.147  +  1541ms  [aa-server] AaServerStarter: +1460 ms clicked more options
19:44:15.698  +  2092ms  [aa-server] AaServerStarter: +2002 ms clicked start
19:44:15.809  +  2203ms  [aa-server] AaServerStarter: +2125 ms back
19:44:15.913  +  2307ms  [aa-server] AaServerStarter: start walk ended: TAPPED_START in 2232 ms (menu: Ajuda e feedback | Configurações do desenvolvedor | Iniciar servidor da unidade principal | Sair do modo de desenvolvedor)
19:44:20.916  +  7310ms  [aa-server] AaServerStarter: tapped start, port still closed after 5 s
19:44:35.341  + 21735ms  [aa-server] rider opened Android Auto settings (installed: true) → ANDROID_AUTO_SETTINGS
19:44:35.552  + 21946ms  [aa] [AA] AaReceiver: connected at last after 9 dial(s) refused in 19.9 s
"""

SERVIDOR_2226 = """22:26:37.274  ---- projeção ligada: projection ----
22:26:37.318  +    44ms  [aa-server] AaServerStarter: start (the port is closed) on Android Auto 17.8.663874-release; texts from Android Auto: 7 of 7, the table for the rest
22:26:37.318  +    44ms  [aa-server] AaServerStarter: start walk begins (last Stop touched 6603306 ms ago, last projection end 6605489 ms ago)
22:26:37.344  +    70ms  [aa-server] AaServerStarter: +14 ms opened the settings
22:26:38.654  +  1380ms  [aa-server] AaServerStarter: +1331 ms clicked more options
22:26:39.812  +  2538ms  [aa-server] AaServerStarter: +2472 ms clicked start
22:26:39.923  +  2649ms  [aa-server] AaServerStarter: +2604 ms back
22:26:40.000  +  2726ms  [aa-server] AaServerStarter: start walk ended: TAPPED_START in 2683 ms (menu: Ajuda e feedback | Configurações do desenvolvedor | Iniciar servidor da unidade principal | Sair do modo de desenvolvedor)
22:26:45.005  +  7731ms  [aa-server] AaServerStarter: tapped start, port still closed after 5 s
22:26:47.433  + 10159ms  [aa-server] AaServerStarter: start (the rider asked) on Android Auto 17.8.663874-release; texts from Android Auto: 7 of 7, the table for the rest
22:26:47.434  + 10160ms  [aa-server] AaServerStarter: start walk begins (last Stop touched 6613422 ms ago, last projection end 6615605 ms ago)
22:26:47.468  + 10194ms  [aa-server] AaServerStarter: +25 ms opened the settings
22:26:48.418  + 11144ms  [aa] [AA] AaReceiver: connected at last after 5 dial(s) refused in 9.1 s
22:26:48.501  + 11227ms  [aa-server] AaServerStarter: +1051 ms clicked more options
22:26:49.018  + 11744ms  [aa-server] AaServerStarter: +1577 ms clicked stop (restart)
22:26:50.527  + 13253ms  [aa-server] AaServerStarter: +3086 ms clicked more options
22:26:51.079  + 13805ms  [aa-server] AaServerStarter: +3628 ms clicked start
22:26:51.491  + 14217ms  [aa-server] AaServerStarter: +4053 ms back
22:26:52.655  + 15381ms  [aa-server] AaServerStarter: +5219 ms back
22:26:52.867  + 15593ms  [aa-server] AaServerStarter: start walk ended: TAPPED_START in 5434 ms, restarted (menu: Ajuda e feedback | Configurações do desenvolvedor | Iniciar servidor da unidade principal | Sair do modo de desenvolvedor)
22:26:52.868  + 15594ms  [aa-server] AaServerStarter: server answering already during the walk
"""


# The 13:00 projection of 19/10/2026: a first walk that found no developer menu (no click), and a second one, minutes later, that clicked.
SERVIDOR_1300 = """13:00:25.197  ---- projeção ligada: projection ----
13:02:44.956  +139759ms  [aa-server] AaServerStarter: the accessibility service connected after a try it had refused; trying again by itself
13:02:44.982  +139785ms  [aa-server] AaServerStarter: start (the accessibility service connected) on Android Auto 17.8.663874-release; texts from Android Auto: 7 of 7, the table for the rest
13:02:44.982  +139785ms  [aa-server] AaServerStarter: start walk begins (last Stop touched never in this process, last projection end never in this process)
13:02:45.061  +139864ms  [aa-server] AaServerStarter: +65 ms opened the settings
13:02:45.947  +140750ms  [aa-server] AaServerStarter: +958 ms clicked more options
13:02:46.479  +141282ms  [aa-server] AaServerStarter: +1494 ms back
13:02:47.061  +141864ms  [aa-server] AaServerStarter: +2075 ms clicked more options
13:02:48.403  +143206ms  [aa-server] AaServerStarter: +3414 ms back
13:02:48.981  +143784ms  [aa-server] AaServerStarter: +3997 ms back
13:02:49.278  +144081ms  [aa-server] AaServerStarter: start walk ended: DEVELOPER_MODE_OFF in 4297 ms (menu: Ajuda e feedback | Enviar feedback)
13:05:06.354  +281157ms  [aa-server] rider opened Android Auto settings (installed: true) → ANDROID_AUTO_SETTINGS
13:06:24.571  +359374ms  [aa-server] AaServerStarter: start (the rider asked) on Android Auto 17.8.663874-release; texts from Android Auto: 7 of 7, the table for the rest
13:06:24.571  +359374ms  [aa-server] AaServerStarter: start walk begins (last Stop touched never in this process, last projection end never in this process)
13:06:24.600  +359403ms  [aa-server] AaServerStarter: +22 ms opened the settings
13:06:25.646  +360449ms  [aa-server] AaServerStarter: +1072 ms clicked more options
13:06:26.174  +360977ms  [aa-server] AaServerStarter: +1592 ms clicked start
13:06:26.281  +361084ms  [aa-server] AaServerStarter: +1708 ms back
13:06:26.377  +361179ms  [aa-server] AaServerStarter: start walk ended: TAPPED_START in 1806 ms (menu: Ajuda e feedback | Configurações do desenvolvedor | Iniciar servidor da unidade principal | Sair do modo de desenvolvedor)
13:06:26.382  +361185ms  [aa] [AA] AaReceiver: connected at last after 180 dial(s) refused in 359.1 s
"""


class ServidorDoAA(unittest.TestCase):
    """O servidor do Android Auto por Ligar: os intervalos da caminhada e a causa."""

    def ler(self, texto):
        d = Path(tempfile.mkdtemp())
        (d / "ride-20261019-192329.log").write_text(CABECALHO + texto, encoding="utf-8")
        return ler_registro.ler([d])

    def tempo(self, texto):
        return "\n".join(ler_registro.linha_do_tempo(self.ler(texto)))

    def test_os_intervalos_da_caminhada_de_1944(self):
        tempo = self.tempo(SERVIDOR_1944)
        self.assertIn("servidor do AA: toque → início da caminhada +77 ms → clique em Iniciar +2015 ms → Voltar +111 ms "
                      "→ fim da caminhada +104 ms → primeira conexão +19639 ms", tempo)

    def test_a_visita_que_subiu_o_servidor_e_a_que_nao_subiu(self):
        base = SERVIDOR_1944.split("19:44:35.341")[0]
        subiu = base + "19:44:21.000  + 7400ms  [aa-server] AaServerStarter: the visit brought the server up\n"
        nao = base + "19:44:21.000  + 7400ms  [aa-server] AaServerStarter: the visit did not bring the port up\n"
        self.assertIn("a visita à tela do AA subiu o servidor", self.tempo(subiu))
        self.assertIn("a visita à tela do AA não subiu o servidor", self.tempo(nao))
        self.assertNotIn("visita", self.tempo(base))

    def test_a_porta_fechada_e_o_servidor_que_so_atendeu_quando_a_tela_do_AA_abriu(self):
        achados = "\n".join(ler_registro.problemas(self.ler(SERVIDOR_1944)))
        self.assertIn("servidor do AA — toque ok, porta fechada em 5 s", achados)
        self.assertIn("servidor do AA — servidor atendeu 211 ms depois de a tela do AA abrir", achados)

    def test_a_segunda_caminhada_que_reabre_a_tela_e_atendida_antes_do_clique(self):
        achados = "\n".join(ler_registro.problemas(self.ler(SERVIDOR_2226)))
        self.assertIn("servidor atendeu 950 ms depois de a tela do AA abrir", achados)
        self.assertIn("Voltar +111 ms", self.tempo(SERVIDOR_2226))

    def test_uma_caminhada_que_deu_certo_nao_acusa_nada(self):
        certo = (
            "20:10:58.000  ---- projeção ligada: projection ----\n"
            "20:10:59.063  + 20890ms  [aa-server] AaServerStarter: start walk begins (last Stop touched 994034 ms ago, last projection end 996027 ms ago)\n"
            "20:10:59.097  + 20924ms  [aa-server] AaServerStarter: +25 ms opened the settings\n"
            "20:11:00.095  + 21922ms  [aa-server] AaServerStarter: +1030 ms clicked more options\n"
            "20:11:02.802  + 24629ms  [aa-server] AaServerStarter: +3727 ms clicked start\n"
            "20:11:02.866  + 24693ms  [aa] [AA] AaReceiver: connected at last after 10 dial(s) refused in 22.6 s\n"
            "20:11:02.949  + 24776ms  [aa-server] AaServerStarter: +3885 ms back\n"
            "20:11:03.725  + 25552ms  [aa-server] AaServerStarter: start walk ended: TAPPED_START in 4663 ms (menu: Ajuda e feedback | Configurações do desenvolvedor | Iniciar servidor da unidade principal | Sair do modo de desenvolvedor)\n"
            "20:30:00.000  ---- projeção desligada: projection ----\n"
        )
        self.assertEqual([], [a for a in ler_registro.problemas(self.ler(certo)) if "servidor do AA" in a])

    def test_menu_lido_pela_metade_e_so_menu_curto(self):
        def com(menu):
            return (
                "12:54:00.000  ---- projeção ligada: projection ----\n"
                "12:54:38.000  [aa-server] AaServerStarter: start walk begins (last Stop touched never in this process)\n"
                f"12:54:43.309  [aa-server] AaServerStarter: start walk ended: DEVELOPER_MODE_OFF in 4954 ms (menu: {menu})\n"
                "12:59:00.000  ---- projeção desligada: projection ----\n"
            )
        curto = "\n".join(ler_registro.problemas(self.ler(com("Ajuda e feedback | Enviar feedback"))))
        self.assertIn("menu pela metade (AA frio?)", curto)
        cheio = "\n".join(ler_registro.problemas(self.ler(com("Ajuda e feedback | Enviar feedback | Mais um | E outro"))))
        self.assertNotIn("menu pela metade", cheio)

    def test_sem_linhas_aa_server_a_edicao_lite_nao_acusa_nada(self):
        lite = (
            "10:00:01.000  ---- projeção ligada: projection ----\n"
            "10:00:02.000  [aa] [AA] AaReceiver: connected at last after 4 dial(s) refused in 6.0 s\n"
            "10:09:00.000  ---- projeção desligada: projection ----\n"
        )
        self.assertEqual("", ler_registro._servidor_aa(ler_registro.projecoes(self.ler(lite))[0])[0])
        self.assertEqual([], [a for a in ler_registro.problemas(self.ler(lite)) if "servidor do AA" in a])
        self.assertNotIn("servidor do AA", self.tempo(lite))

    def test_a_primeira_caminhada_sem_clique_nao_pega_o_clique_de_uma_caminhada_de_minutos_depois(self):
        tempo = self.tempo(SERVIDOR_1300)
        historia = next(l for l in tempo.splitlines() if "servidor do AA:" in l)
        self.assertNotIn("+-", historia)                 # nenhum intervalo negativo
        self.assertNotIn("clique em Iniciar", historia)  # a primeira caminhada saiu sem clicar (menu pela metade)
        self.assertIn("fim da caminhada +", historia)
        self.assertIn("menu pela metade (AA frio?)", historia)


def device(hora, bike):
    return linha(hora, "device", f"device battery=75% charging=no temp=33.0C volt=3.9V thermal=none mem=21/256MB phone=2850MB low=no "
                                 f"screen=on interactive=yes doze=no saver=off exempt=yes bike={bike} data=wifi/ok [30 s]")


class WifiDoPainelEEspera(unittest.TestCase):
    """The dash's Wi-Fi before a fall comes from `bike=`, and a long wait for the dash is named."""

    def tempo(self, texto):
        d = Path(tempfile.mkdtemp())
        (d / "ride-20261004-100000.log").write_text(texto, encoding="utf-8")
        return ler_registro.linha_do_tempo(ler_registro.ler([d])), ler_registro.problemas(ler_registro.ler([d]))

    def test_o_wifi_antes_da_queda_e_o_do_painel_e_nao_o_de_casa_do_celular(self):
        home = "rssi=-61 dBm tx=72 rx=72 Mbps 2.4 GHz powersave=on screen=on | last 60 s, every 5 s: rssi -61 -61 | tx 72 72"
        tempo, _ = self.tempo(projecao(
            linha("10:00:30.000", "link", home),
            device("10:00:48.000", "-74dBm/2427MHz/58Mbps"),
            linha("10:01:00.000", "wifi", "W: link lost — reconnecting in 4000ms"),
        ))
        texto = "\n".join(tempo)
        self.assertIn("Wi-Fi do painel antes da queda: -74 dBm, 2427 MHz, 58 Mbps (12 s antes)", texto)
        self.assertNotIn("-61", texto)  # the home Wi-Fi of the phone is not told as the dash's

    def test_sem_bike_perto_da_queda_diz_que_o_do_painel_nao_esta_no_registro(self):
        tempo, _ = self.tempo(projecao(
            device("10:00:10.000", "-74dBm/2427MHz/58Mbps"),  # more than 90 s before: the dash's signal of then, not "before the fall"
            device("10:01:50.000", "none"),
            linha("10:02:00.000", "wifi", "W: link lost — reconnecting in 4000ms"),
        ))
        texto = "\n".join(tempo)
        self.assertNotIn("Wi-Fi do painel antes da queda", texto)
        self.assertNotIn("-74 dBm", texto)

    def test_o_laco_de_espera_tem_duracao_e_numero_de_pedidos(self):
        pedidos = [linha(f"10:{1 + i // 4:02d}:{(i % 4) * 14:02d}.000", "wifi",
                         f"requestNetwork 'RE_****30' everConnected=true timeout=10000ms; request #{i + 1} (0s since the first)")
                   for i in range(60)]  # 60 requests, 14 s apart: up to 10:15:02
        tempo, problemas = self.tempo(projecao(*pedidos, linha("10:16:00.000", "wifi", "connected 'RE_****30' in 900ms")))
        self.assertTrue(any("espera pelo painel: 60 pedidos de rede em 15 min 00 s" in x for x in tempo), tempo)
        self.assertTrue(any("laço de espera pelo painel — 60 pedidos de rede em 15 min 00 s" in x for x in problemas), problemas)

    def test_uma_espera_curta_nao_e_problema_e_a_muito_curta_nem_aparece(self):
        pedido = lambda hora, n: linha(hora, "wifi", f"requestNetwork 'RE_****30' everConnected=true timeout=10000ms; request #{n} (1s since the first)")
        tempo, problemas = self.tempo(projecao(
            pedido("10:01:00.000", 1), pedido("10:01:14.000", 2), pedido("10:02:28.000", 3),
            linha("10:02:30.000", "wifi", "connected 'RE_****30' in 900ms"),
            pedido("10:05:00.000", 4), pedido("10:05:14.000", 5),
            linha("10:05:16.000", "wifi", "connected 'RE_****30' in 900ms"),
        ))
        self.assertTrue(any("espera pelo painel: 3 pedidos de rede em 1 min 30 s" in x for x in tempo), tempo)
        self.assertFalse(any("2 pedidos" in x for x in tempo), tempo)  # 14 s: nothing to tell
        self.assertEqual([], [p for p in problemas if "laço de espera" in p])

    def test_um_pedido_respondido_com_o_wifi_ja_conectado_fecha_o_laco(self):
        pedido = lambda hora, n: linha(hora, "wifi", f"requestNetwork 'RE_****30' everConnected=true timeout=10000ms; request #{n} (1s since the first)")
        _, problemas = self.tempo(projecao(
            pedido("10:01:00.000", 1), pedido("10:01:14.000", 2),
            linha("10:01:20.000", "wifi", "using already-connected WiFi 'RE_****30'"),
            pedido("10:08:00.000", 3), pedido("10:08:14.000", 4),
            linha("10:08:20.000", "wifi", "using already-connected WiFi 'RE_****30'"),
        ))
        self.assertEqual([], [p for p in problemas if "laço de espera" in p], problemas)

    def test_uma_espera_que_passa_da_meia_noite_tem_duracao_positiva(self):
        d = Path(tempfile.mkdtemp())
        pedidos = "".join(f"23:{57 + i // 4:02d}:{(i % 4) * 14 + 1:02d}.000  +   1ms  [wifi] requestNetwork 'RE_****30' everConnected=true "
                          f"timeout=10000ms; request #{i + 1} (1s since the first)\n" for i in range(8))
        (d / "ride-20261019-235000.log").write_text(
            "23:50:00.000  ---- projeção ligada: projection ----\n" + pedidos
            + "00:04:00.000  +   1ms  [wifi] requestNetwork 'RE_****30' everConnected=true timeout=10000ms; request #9 (1s since the first)\n"
            "00:05:00.000  +   1ms  [wifi] connected 'RE_****30' in 900ms\n00:09:00.000  ---- projeção desligada: projection ----\n", encoding="utf-8")
        problemas = ler_registro.problemas(ler_registro.ler([d]))
        self.assertTrue(any("laço de espera pelo painel — 9 pedidos de rede em 7 min 59 s" in p for p in problemas), problemas)

    def test_o_relogio_que_passa_da_meia_noite_muda_o_dia(self):
        d = Path(tempfile.mkdtemp())
        (d / "ride-20261019-235000.log").write_text(
            "23:59:59.000  +   1ms  [aa] a\n00:00:05.000  +   1ms  [aa] b\n", encoding="utf-8")
        linhas = ler_registro.ler([d])
        self.assertEqual(["2026-10-19", "2026-10-20"], [x.dia for x in linhas])
        self.assertEqual(6.0, (ler_registro.instante(linhas[1]) - ler_registro.instante(linhas[0])).total_seconds())


LOGCAT_ANDROID_AUTO = (
    "--------- beginning of crash\n"
    "10-04 10:05:01.200 26372 26528 E AndroidRuntime: FATAL EXCEPTION: Thread-2\n"
    "10-04 10:05:01.200 26372 26528 E AndroidRuntime: Process: com.google.android.projection.gearhead:projection, PID: 26372\n"
    "10-04 10:05:01.200 26372 26528 E AndroidRuntime: java.lang.NullPointerException: Attempt to invoke virtual method "
    "'boolean java.net.ServerSocket.isClosed()' on a null object reference\n"
    "10-04 10:05:01.200 26372 26528 E AndroidRuntime: \tat xaz.run(SourceFile:489)\n"
    # Another app's crash, and a line with personal data: neither is told.
    "10-04 10:06:00.000  4000  4010 E AndroidRuntime: FATAL EXCEPTION: main\n"
    "10-04 10:06:00.000  4000  4010 E AndroidRuntime: Process: com.example.other, PID: 4000\n"
    "10-04 10:06:00.000  4000  4010 E AndroidRuntime: java.lang.IllegalStateException: boom\n"
    "10-04 10:06:01.000  1000  1001 I SomeTag: account owner@example.invalid signed in to HomeWifi\n"
)
LOGCAT_DO_APP = (
    "10-04 10:03:15.365  1337  1437 I am_cpu  : [26890,10480,com.reau.app,300065,150,30]\n"
    "10-04 10:03:20.000 26890 26890 I Choreographer: Skipped 301 frames!  The application may be doing too much work on its main thread.\n"
    "10-04 10:03:21.000 26890 26890 I Choreographer: Skipped 45 frames!  The application may be doing too much work on its main thread.\n"
    "10-04 10:03:22.000 26890 26890 I Choreographer: Skipped 120 frames!  The application may be doing too much work on its main thread.\n"
    "10-04 10:03:23.500 26890 26912 I HWUI    : Davey! duration=1234ms; Flags=0, FrameTimelineVsyncId=1\n"
    "10-04 10:03:24.500 26890 26912 I HWUI    : Davey! duration=800ms; Flags=0, FrameTimelineVsyncId=2\n"
    # The same lines from another process are not the app's.
    "10-04 10:03:25.000  5555  5555 I Choreographer: Skipped 999 frames!  The application may be doing too much work on its main thread.\n"
    "10-04 10:03:26.000  5555  5560 I HWUI    : Davey! duration=9999ms; Flags=0, FrameTimelineVsyncId=3\n"
)


class LogcatDoSistema(unittest.TestCase):
    def pasta(self, logcat, bye="10:05:00.000"):
        d = Path(tempfile.mkdtemp())
        (d / "ride-20261004-100000.log").write_text(
            projecao(linha(bye, "aa", "[AA] AapTransport stopping and sending byebye")), encoding="utf-8")
        (d / "logcat-sistema-atual.txt").write_text(logcat, encoding="utf-8")
        return d

    def achados(self, logcat, **k):
        d = self.pasta(logcat, **k)
        return ler_registro.problemas(ler_registro.ler([d])) + ler_registro.do_sistema([d], ler_registro.ler([d]))

    def test_o_android_auto_que_travou_vem_com_o_intervalo_desde_o_byebye(self):
        achados = self.achados(LOGCAT_ANDROID_AUTO)
        travou = [a for a in achados if "travou" in a]
        self.assertEqual(1, len(travou), achados)
        self.assertEqual("10:05:01: o Android Auto (gearhead:projection) travou — java.lang.NullPointerException (1,2 s depois do byebye do REau)",
                         travou[0])

    def test_a_mensagem_da_excecao_nao_sai_porque_e_texto_livre(self):
        logcat = LOGCAT_ANDROID_AUTO.replace("Attempt to invoke virtual method", "account owner@example.invalid HomeWifi")
        texto = "\n".join(self.achados(logcat))
        self.assertIn("java.lang.NullPointerException", texto)
        self.assertNotIn("owner@example.invalid", texto)
        self.assertNotIn("HomeWifi", texto)

    def test_o_dia_que_o_ano_nao_tem_nao_derruba_o_leitor_e_dezembro_para_janeiro_acha_o_ano_certo(self):
        # 02-29 in 2026 (not a leap year): the line has no instant, and is told without an interval.
        feb = LOGCAT_ANDROID_AUTO.replace("10-04 10:05:01.200", "02-29 10:05:01.200")
        d = Path(tempfile.mkdtemp())
        # A registro whose name has no day that exists (the filter by day then has nothing to go by) and a logcat line of 02-29.
        (d / "ride-20260229-100000.log").write_text(projecao(linha("10:05:00.000", "aa", "[AA] AapTransport stopping and sending byebye")), encoding="utf-8")
        (d / "logcat-sistema-atual.txt").write_text(feb, encoding="utf-8")
        self.assertTrue(any("sem byebye do REau" in a for a in ler_registro.do_sistema([d], ler_registro.ler([d]))))
        # The registros of 31/12/2026, the crash at 01-01 00:00:01 (2027) a second after the byebye.
        d = Path(tempfile.mkdtemp())
        (d / "ride-20261231-235500.log").write_text(
            "23:55:00.000  ---- projeção ligada: projection ----\n23:59:59.800  +   1ms  [aa] [AA] AapTransport stopping and sending byebye\n"
            "00:00:09.000  ---- projeção desligada: projection ----\n", encoding="utf-8")
        (d / "logcat-sistema.txt").write_text(
            "01-01 00:00:01.000 100 101 E AndroidRuntime: FATAL EXCEPTION: Thread-2\n"
            "01-01 00:00:01.000 100 101 E AndroidRuntime: Process: com.google.android.projection.gearhead:car, PID: 100\n"
            "01-01 00:00:01.000 100 101 E AndroidRuntime: java.lang.IllegalStateException: x\n", encoding="utf-8")
        achados = ler_registro.do_sistema([d], ler_registro.ler([d]))
        self.assertTrue(any("(1,2 s depois do byebye do REau)" in a for a in achados), achados)

    def test_sem_byebye_perto_so_diz_a_hora(self):
        achados = self.achados(LOGCAT_ANDROID_AUTO, bye="10:02:00.000")
        self.assertTrue(any("(sem byebye do REau nos 60 s antes)" in a for a in achados), achados)

    def test_so_o_android_auto_e_o_app_saem_e_nada_pessoal(self):
        texto = "\n".join(self.achados(LOGCAT_ANDROID_AUTO + LOGCAT_DO_APP))
        for pessoal in ("owner@example.invalid", "HomeWifi", "com.example.other", "IllegalStateException"):
            self.assertNotIn(pessoal, texto)

    def test_davey_e_quadros_pulados_so_do_processo_do_app_e_de_100_para_cima(self):
        achados = self.achados(LOGCAT_DO_APP)
        self.assertIn("10:03:23: o app travou quadros (2× Davey!, o pior de 1234 ms)", achados)
        self.assertIn("10:03:20: o app pulou quadros (2× com 100 ou mais, o pior com 301)", achados)

    def test_sem_o_processo_do_app_no_logcat_nao_acusa_o_que_nao_e_dele(self):
        sem_pid = LOGCAT_DO_APP.split("\n", 1)[1]  # without the am_cpu line there is no telling whose Davey it is
        self.assertEqual([], [a for a in self.achados(sem_pid) if "quadros" in a])

    def test_o_logcat_de_uma_pasta_diag_e_achado_na_pasta_de_cima(self):
        raiz = Path(tempfile.mkdtemp())
        (raiz / "diag").mkdir()
        (raiz / "diag" / "ride-20261004-100000.log").write_text(
            projecao(linha("10:05:00.000", "aa", "[AA] AapTransport stopping and sending byebye")), encoding="utf-8")
        (raiz / "logcat-sistema-atual.txt").write_text(LOGCAT_ANDROID_AUTO, encoding="utf-8")
        achados = ler_registro.do_sistema([raiz / "diag"], ler_registro.ler([raiz / "diag"]))
        self.assertTrue(any("travou" in a for a in achados), achados)

    def test_sem_logcat_do_sistema_nao_diz_nada_e_o_logcat_nao_e_lido_como_registro(self):
        d = Path(tempfile.mkdtemp())
        (d / "ride-20261004-100000.log").write_text(BOA, encoding="utf-8")
        self.assertEqual([], ler_registro.do_sistema([d], ler_registro.ler([d])))
        (d / "logcat-sistema-atual.txt").write_text(LOGCAT_ANDROID_AUTO, encoding="utf-8")
        # Given the file itself, the registro reader leaves it alone.
        self.assertEqual([], ler_registro.ler([d / "logcat-sistema-atual.txt"]))


class EsperaPeloTick(unittest.TestCase):
    """The `tickwait=` field of the `video fps` line comes out in the `vídeo:` summary."""

    def video(self, *janelas):
        d = Path(tempfile.mkdtemp())
        (d / "ride-20261004-100000.log").write_text(projecao(*[
            linha(f"10:00:{10 + 5 * i:02d}.000", "stream", "video fps target=4 sent=3.9 (20frames/5.1s) composed=3.9 kbps=300 set=400k "
                  f"frame=9000B source=61 drawn=20 repeated=0 compose=2.7ms encode=12.0ms late=0 {j}full=true thermal=OK")
            for i, j in enumerate(janelas)]), encoding="utf-8")
        return "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([d])))

    def test_a_espera_media_o_pior_p95_e_o_maximo_saem_no_resumo_do_video(self):
        texto = self.video("tickwait=100/200/250ms ", "tickwait=140/300/400ms ", "tickwait=- ")
        self.assertIn("espera pelo tick média 120 ms (pior p95 300, máx 400)", texto)

    def test_sem_o_campo_o_resumo_nao_diz_nada_da_espera(self):
        self.assertNotIn("espera pelo tick", self.video("", ""))


LOGCAT_DO_APP_DIAG = (
    "--------- beginning of main\n"
    "10-04 10:03:00.000  5000  5001 I HWUI    : Davey! duration=900ms; Flags=0, FrameTimelineVsyncId=1\n"
    "10-04 10:04:59.500  5000  5001 I HWUI    : Davey! duration=1500ms; Flags=0, FrameTimelineVsyncId=2\n"
    "10-04 10:05:00.100  5000  5000 I Choreographer: Skipped 120 frames!  The application may be doing too much work on its main thread.\n"
    "10-04 10:05:30.000  5000  5000 I Choreographer: Skipped 50 frames!  The application may be doing too much work on its main thread.\n"
    "10-04 10:06:59.900  5000  5000 I Choreographer: Skipped 301 frames!  The application may be doing too much work on its main thread.\n"
    "10-04 10:06:59.950  5000  5001 I HWUI    : Davey! duration=5040ms; Flags=0, FrameTimelineVsyncId=3\n"
)


class LogcatDoApp(unittest.TestCase):
    """The app's own logcat (diag/logcat-*.txt) is read too, and each stall says the walk it fell in."""

    def pasta(self, logcat=LOGCAT_DO_APP_DIAG):
        raiz = Path(tempfile.mkdtemp())
        (raiz / "diag").mkdir()
        (raiz / "diag" / "ride-20261004-100000.log").write_text(projecao(
            linha("10:05:00.000", "aa-server", "AaServerStarter: start walk ended: TAPPED_START in 2000 ms (menu: x | y)"),
            linha("10:07:00.000", "aa-server", "AaServerStarter: stop walk ended: TAPPED_STOP in 1500 ms"),
        ), encoding="utf-8")
        (raiz / "diag" / "logcat-20261004-100000.txt").write_text(logcat, encoding="utf-8")
        return raiz / "diag"

    def achados(self, **k):
        d = self.pasta(**k)
        return ler_registro.do_sistema([d], ler_registro.ler([d]))

    def test_cada_travada_diz_a_caminhada_em_que_caiu(self):
        achados = self.achados()
        self.assertIn("04/10 10:04:59: o app travou a tela — Davey! de 1500 ms (durante a caminhada de iniciar o servidor)", achados)
        self.assertIn("04/10 10:05:00: o app travou a tela — 120 quadros pulados (durante a caminhada de iniciar o servidor)", achados)
        self.assertIn("04/10 10:06:59: o app travou a tela — 301 quadros pulados (durante a caminhada de parar o servidor)", achados)
        self.assertIn("04/10 10:06:59: o app travou a tela — Davey! de 5040 ms (durante a caminhada de parar o servidor)", achados)

    def test_fora_de_caminhada_sai_sem_fase_e_menos_de_100_quadros_nao_sai(self):
        achados = self.achados()
        self.assertIn("04/10 10:03:00: o app travou a tela — Davey! de 900 ms", achados)  # no phase text at the end
        self.assertFalse(any("50 quadros" in a for a in achados), achados)

    def test_o_logcat_do_sistema_nao_e_lido_como_o_do_app(self):
        d = self.pasta(logcat="")
        (d / "logcat-sistema-atual.txt").write_text(LOGCAT_DO_APP, encoding="utf-8")  # no am_cpu line: not the app's by pid
        self.assertEqual([], [a for a in ler_registro.do_sistema([d], ler_registro.ler([d])) if "travou a tela" in a])

    def test_logcat_do_app_sem_travadas_deixa_o_total_do_logcat_do_sistema(self):
        d = self.pasta(logcat="10-04 10:03:00.000  5000  5001 I SomeTag: nothing to tell\n")
        (d.parent / "logcat-sistema-atual.txt").write_text(LOGCAT_DO_APP, encoding="utf-8")
        achados = ler_registro.do_sistema([d], ler_registro.ler([d]))
        self.assertTrue(any("o app travou quadros (2× Davey!" in a for a in achados), achados)

    def test_so_entram_os_dias_dos_registros_lidos(self):
        outro_dia = LOGCAT_DO_APP_DIAG.replace("10-04 ", "10-03 ")
        d = self.pasta(logcat=outro_dia + LOGCAT_DO_APP_DIAG)
        travadas = [a for a in ler_registro.do_sistema([d], ler_registro.ler([d])) if "travou a tela" in a]
        self.assertTrue(travadas and all(a.startswith("04/10 ") for a in travadas), travadas)
        self.assertEqual(5, len(travadas), travadas)  # the five of the day of the registro; none of the day before

    def test_so_os_primeiros_trinta_e_as_das_caminhadas_primeiro(self):
        muitas = "".join(f"10-04 10:03:{n % 60:02d}.000  5000  5001 I HWUI    : Davey! duration=900ms; Flags=0\n" for n in range(40))
        achados = self.achados(logcat=muitas + LOGCAT_DO_APP_DIAG)
        travadas = [a for a in achados if "travou a tela" in a]
        self.assertEqual(30, len(travadas))
        self.assertTrue(any("(mais " in a for a in achados), achados)
        self.assertEqual(4, sum("durante a caminhada" in a for a in travadas))  # those of the walks are never the ones cut


if __name__ == "__main__":
    unittest.main()
