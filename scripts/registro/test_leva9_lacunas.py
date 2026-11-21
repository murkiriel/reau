"""Tests of the three gaps the log reader still had (plan 28, wave 9): the heartbeat's rhythm and bytes, the panel's group 03 warned when it changes between connections, and the
rhythm of the recorded video read from `video/*.csv` (both formats) against the `video fps` lines of the log. The data is made up here; one test reads a real collection when
`REAU_COLETA` points at one, and is skipped without it."""
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))
import detalhes  # noqa: E402
import gravacoes  # noqa: E402
import indice_video  # noqa: E402
import ler_registro  # noqa: E402
import ritmo_video  # noqa: E402
from test_detalhes import arquivos, projecao_com, rx03, tx  # noqa: E402
from test_gravacoes import coleta, csv_novo, registro_com  # noqa: E402
from test_ler_registro import escreve, linha  # noqa: E402


def quadros(pts, chaves=(0,), tamanho=100):
    """Frames as `indice_video.ler` gives them: (frame, pts_ms, bytes_in_file, key)."""
    return [(i + 1, p, tamanho, 1 if i in chaves else 0) for i, p in enumerate(pts)]


def a_cada(passo_ms, n):
    return [i * passo_ms for i in range(n)]


class FpsPorJanelaTest(unittest.TestCase):
    def test_a_steady_four_fps_gives_four_in_every_whole_window_and_the_partial_one_is_left_out(self):
        q = quadros(a_cada(250, 100))  # 24.75 s: two whole windows of 10 s
        self.assertEqual([4.0, 4.0], ritmo_video.fps_por_janela(q))

    def test_a_slow_stretch_shows_in_its_own_window(self):
        pts = a_cada(250, 40) + [10_000 + i * 1000 for i in range(10)] + [20_000 + i * 250 for i in range(41)]
        self.assertEqual([4.0, 1.0, 4.0], ritmo_video.fps_por_janela(quadros(pts))[:3])

    def test_less_than_a_window_or_no_frame_gives_nothing(self):
        self.assertEqual([], ritmo_video.fps_por_janela(quadros(a_cada(250, 20))))
        self.assertEqual([], ritmo_video.fps_por_janela([]))


class ChavesEBuracosTest(unittest.TestCase):
    def test_the_interval_between_keyframes_is_in_ms(self):
        q = quadros(a_cada(250, 13), chaves=(0, 4, 8, 12))
        self.assertEqual([1000, 1000, 1000], ritmo_video.intervalos_de_chave(q))

    def test_a_gap_is_strictly_longer_than_two_seconds(self):
        q = quadros([0, 250, 2250, 2500, 4501])
        self.assertEqual([(2500, 2001)], ritmo_video.buracos(q))  # 2000 ms exactly (250 -> 2250) is not a gap

    def test_the_summary_tells_the_median_the_long_intervals_and_the_gaps(self):
        pts = a_cada(250, 41) + [10_000 + 3000]
        q = quadros(pts, chaves=(0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 41))
        texto = "\n".join(ritmo_video.resumo(q))
        self.assertIn("quadros-chave a cada 1,00 s (mediana; de 1,00 a 3,00 s; alvo 1 s); 1 intervalo acima de 1,5 s", texto)
        self.assertIn("1 buraco de mais de 2 s entre quadros (10.0 s: 3,0 s sem quadro)", texto)

    def test_a_clean_video_says_no_gap(self):
        texto = "\n".join(ritmo_video.resumo(quadros(a_cada(250, 100), chaves=range(0, 100, 4))))
        self.assertIn("nenhum buraco de mais de 2 s entre quadros", texto)
        self.assertIn("ritmo real por janela de 10 s (2 janelas): mediana 4,0 fps", texto)
        self.assertNotIn("acima de", texto)

    def test_one_frame_has_no_summary(self):
        self.assertEqual([], ritmo_video.resumo(quadros([0])))


class SomaTest(unittest.TestCase):
    def test_the_new_format_adds_up_to_the_file_or_says_the_difference(self):
        self.assertEqual("a soma de bytes_in_file (300 B) bate com o .h264", ritmo_video.conferir_soma(indice_video.NOVO, 300, 300))
        self.assertIn("a soma de bytes_in_file é 300 B e o .h264 tem 310 B (+10 B)", ritmo_video.conferir_soma(indice_video.NOVO, 300, 310))

    def test_the_old_format_and_a_missing_file_are_not_checked_here(self):
        self.assertIsNone(ritmo_video.conferir_soma(indice_video.ANTIGO, 300, 300))
        self.assertIsNone(ritmo_video.conferir_soma(indice_video.NOVO, 300, None))


class ComparacaoComOLogTest(unittest.TestCase):
    """The index against the `video fps` windows of the log that fall inside the video."""
    from datetime import datetime
    INICIO = datetime(2026, 11, 8, 10, 0, 6)

    def janelas(self, frames, n=10, segundos=5.0):
        from datetime import timedelta
        return [(self.INICIO + timedelta(seconds=segundos * (i + 1)), segundos, frames) for i in range(n)]

    def test_the_same_pace_in_both_is_told_as_matching_and_is_no_warning(self):
        c = ritmo_video.fps_do_log_e_do_indice(quadros(a_cada(250, 200)), self.INICIO, self.janelas(20))
        self.assertAlmostEqual(4.0, c["fps_indice"], places=1)
        self.assertAlmostEqual(4.0, c["fps_log"], places=1)
        self.assertIsNone(ritmo_video.diferenca_fps(c))
        self.assertTrue(ritmo_video.linha_da_comparacao(c).endswith(": batem"))

    def test_a_big_difference_is_a_warning_with_both_numbers(self):
        c = ritmo_video.fps_do_log_e_do_indice(quadros(a_cada(250, 200)), self.INICIO, self.janelas(10))  # the log says 2 fps, the index has 4
        aviso = ritmo_video.diferenca_fps(c)
        self.assertIn("o índice do vídeo dá 4,00 fps e as 10 linhas `video fps`", aviso)
        self.assertIn("dão 2,00 fps (50 % de diferença)", aviso)
        self.assertNotIn("batem", ritmo_video.linha_da_comparacao(c))

    def test_twenty_percent_exactly_and_a_few_tenths_of_fps_are_not_a_warning(self):
        self.assertIsNone(ritmo_video.diferenca_fps({"janelas": 5, "fps_log": 5.0, "fps_indice": 4.0}))  # 20 % exactly
        self.assertIsNone(ritmo_video.diferenca_fps({"janelas": 5, "fps_log": 2.0, "fps_indice": 1.75}))  # 12 %
        self.assertIsNone(ritmo_video.diferenca_fps({"janelas": 5, "fps_log": 1.0, "fps_indice": 0.75}))  # 25 % but 0.25 fps
        self.assertIsNotNone(ritmo_video.diferenca_fps({"janelas": 5, "fps_log": 5.0, "fps_indice": 3.9}))

    def test_fewer_than_three_windows_in_the_period_do_not_compare(self):
        self.assertIsNone(ritmo_video.fps_do_log_e_do_indice(quadros(a_cada(250, 200)), self.INICIO, self.janelas(10, n=2)))
        self.assertIsNotNone(ritmo_video.fps_do_log_e_do_indice(quadros(a_cada(250, 200)), self.INICIO, self.janelas(20, n=3)))

    def test_a_window_that_ends_after_the_video_does_not_count(self):
        c = ritmo_video.fps_do_log_e_do_indice(quadros(a_cada(250, 40)), self.INICIO, self.janelas(20, n=10))  # the video lasts 9.75 s
        self.assertIsNone(c)

    def test_without_the_start_there_is_no_comparison(self):
        self.assertIsNone(ritmo_video.fps_do_log_e_do_indice(quadros(a_cada(250, 200)), None, self.janelas(20)))
        self.assertIsNone(ritmo_video.diferenca_fps(None))
        self.assertIsNone(ritmo_video.linha_da_comparacao(None))

    def test_the_lines_of_the_log_are_read_with_their_instant(self):
        d = Path(tempfile.mkdtemp())
        escreve(d, "ride-20261108-100000.log", registro_com("10:00:01.000", "10:10:00.000",
                linha("10:00:11.000", "stream", "video fps target=4 sent=3.9 (20frames/5.1s) composed=3.9 kbps=272"),
                linha("10:00:16.000", "stream", "video fps target=4 sent=3.9 (20frames/5.1s) composed=3.9 kbps=272"),
                linha("10:00:17.000", "stream", "outra coisa")))
        j = ritmo_video.janelas_do_log(ler_registro.ler([d]))
        self.assertEqual([(20, 5.1), (20, 5.1)], [(n, s) for _, s, n in j])
        self.assertEqual("10:00:11", j[0][0].strftime("%H:%M:%S"))

    def test_a_part_after_the_first_starts_when_the_previous_one_closed(self):
        d = Path(tempfile.mkdtemp())
        escreve(d, "ride-20261108-100000.log", registro_com("10:00:01.000", "10:10:00.000",
                linha("10:05:00.000", "stream", "video recording part 1 complete, the next follows: 3000 frames, 100 bytes, /d/files/video/reau_20261108_100006_000.h264")))
        linhas = ler_registro.ler([d])
        self.assertEqual("10:05:00", ritmo_video.inicio_da_parte(2, linhas).strftime("%H:%M:%S"))
        self.assertIsNone(ritmo_video.inicio_da_parte(3, linhas))


def video_csv_antigo(pasta, nome, tamanhos, passo=250):
    """The index of the recordings of 20/10 to 03/11/2026: `bytes` leaves the 4 bytes of the start code out."""
    linhas = ["frame,pts_ms,bytes,key"] + [f"{i + 1},{i * passo},{t - 4},{1 if i % 4 == 0 else 0}" for i, t in enumerate(tamanhos)]
    (Path(pasta) / f"{nome}.csv").write_text("\n".join(linhas) + "\n")
    (Path(pasta) / f"{nome}.h264").write_bytes(b"\x00" * sum(tamanhos))


class VideoNaColetaTest(unittest.TestCase):
    """The section of the recordings: the rhythm lines under each video and the warning for the fps."""

    def montar(self, preparar, *meio):
        raiz = coleta()
        escreve(raiz / "diag", "ride-20261108-100000.log", registro_com("10:00:01.000", "10:10:00.000", *meio))
        preparar(raiz)
        linhas = ler_registro.ler([raiz / "diag"])
        return raiz, linhas, ler_registro.projecoes(linhas)

    def janelas_do_log(self, frames, n=10):
        return [linha(f"10:00:{11 + 5 * i:02d}.000", "stream", f"video fps target=4 sent={frames / 5.0:.1f} ({frames}frames/5.0s) composed=4.0 kbps=100") for i in range(n)]

    def novo(self, raiz):
        csv_novo(raiz / "video", "reau_20261108_100006_000", [200] * 240, chaves=range(0, 240, 4))  # 4 fps, 59.75 s

    def test_the_new_format_gets_the_rhythm_the_sum_and_the_comparison(self):
        raiz, linhas, blocos = self.montar(self.novo, *self.janelas_do_log(20))
        s, avisos = gravacoes.analise(raiz, linhas, blocos)
        texto = "\n".join(s)
        self.assertIn("ritmo real por janela de 10 s (5 janelas): mediana 4,0 fps", texto)
        self.assertIn("quadros-chave a cada 1,00 s", texto)
        self.assertIn("nenhum buraco de mais de 2 s entre quadros", texto)
        self.assertIn("a soma de bytes_in_file (48000 B) bate com o .h264", texto)
        self.assertIn("das 10 linhas `video fps` do registro no mesmo período: batem", texto)
        self.assertEqual([], avisos)

    def test_the_old_format_is_read_too_and_its_sum_is_not_checked_here(self):
        def antigo(raiz):
            video_csv_antigo(raiz / "video", "reau_20261108_100006_000", [200] * 240)
        raiz, linhas, blocos = self.montar(antigo, *self.janelas_do_log(20))
        s, avisos = gravacoes.analise(raiz, linhas, blocos)
        texto = "\n".join(s)
        self.assertIn("240 quadros", texto)
        self.assertIn("ritmo real por janela de 10 s (5 janelas): mediana 4,0 fps", texto)
        self.assertIn("nenhum buraco", texto)
        self.assertNotIn("o índice não se lê", texto)
        self.assertNotIn("a soma de bytes_in_file", texto)
        self.assertEqual([], avisos)
        self.assertEqual(indice_video.ANTIGO, gravacoes.ler_video(raiz / "video" / "reau_20261108_100006_000.h264")["formato"])

    def test_a_new_format_sum_that_does_not_match_the_file_is_told(self):
        def torto(raiz):
            self.novo(raiz)
            (raiz / "video" / "reau_20261108_100006_000.h264").write_bytes(b"\x00" * 47990)
        raiz, linhas, blocos = self.montar(torto)
        self.assertIn("a soma de bytes_in_file é 48000 B e o .h264 tem 47990 B (-10 B)", "\n".join(gravacoes.analise(raiz, linhas, blocos)[0]))

    def test_a_gap_in_the_video_is_listed(self):
        def com_buraco(raiz):
            csv_novo(raiz / "video", "reau_20261108_100006_000", [200] * 60, chaves=range(0, 60, 4))
            linhas = (raiz / "video" / "reau_20261108_100006_000.csv").read_text().splitlines()
            # frames 31 on come 5 s later
            novas = [linhas[0]] + [l if i <= 30 else ",".join([l.split(",")[0], str(int(l.split(",")[1]) + 5000), *l.split(",")[2:]]) for i, l in enumerate(linhas[1:], 1)]
            (raiz / "video" / "reau_20261108_100006_000.csv").write_text("\n".join(novas) + "\n")
        raiz, linhas, blocos = self.montar(com_buraco)
        self.assertIn("1 buraco de mais de 2 s entre quadros (7.2 s: 5,2 s sem quadro)", "\n".join(gravacoes.analise(raiz, linhas, blocos)[0]))

    def test_a_log_that_says_half_the_pace_the_index_has_is_a_warning(self):
        raiz, linhas, blocos = self.montar(self.novo, *self.janelas_do_log(10))
        s, avisos = gravacoes.analise(raiz, linhas, blocos)
        self.assertEqual(1, len(avisos), avisos)
        self.assertIn("vídeo reau_20261108_100006_000.h264 (projeção de 10:00:01, ride-20261108-100000.log): o índice do vídeo dá 4,00 fps", avisos[0])
        self.assertIn("dão 2,00 fps", avisos[0])
        self.assertNotIn(": batem", "\n".join(s))

    def test_without_video_fps_lines_there_is_no_comparison_and_no_warning(self):
        raiz, linhas, blocos = self.montar(self.novo)
        s, avisos = gravacoes.analise(raiz, linhas, blocos)
        self.assertEqual([], avisos)
        self.assertNotIn("linhas `video fps`", "\n".join(s))

    def test_secao_keeps_giving_the_lines_alone(self):
        raiz, linhas, blocos = self.montar(self.novo, *self.janelas_do_log(20))
        self.assertEqual(gravacoes.analise(raiz, linhas, blocos)[0], gravacoes.secao(raiz, linhas, blocos))

    def test_the_command_line_prints_the_warning_under_the_avisos(self):
        raiz, _, _ = self.montar(self.novo, *self.janelas_do_log(10))
        feito = subprocess.run([sys.executable, "-I", str(AQUI / "ler_registro.py"), str(raiz), "--tudo"], capture_output=True, text=True, encoding="utf-8")
        depois = feito.stdout.split("AVISOS DA LEITURA", 1)[-1]
        self.assertIn("o índice do vídeo dá 4,00 fps", depois)
        self.assertIn("ritmo real por janela", feito.stdout.split("AVISOS DA LEITURA", 1)[0])


class BatimentoRitmoTest(unittest.TestCase):
    def resumo(self, *linhas):
        _, arqs = arquivos(projecao_com("10:00:00.000", "10:09:00.000", *linhas))
        return detalhes.batimento(detalhes.ler(arqs))

    def test_a_one_hertz_heartbeat_says_every_second_and_the_longest_gap(self):
        r = self.resumo(*[tx(f"10:01:{k:02d}.000") for k in (0, 1, 2, 3, 6, 7)])
        self.assertEqual("a cada 1,00 s (mediana; o maior intervalo 3,0 s) em 7 s", r["frequencia"])

    def test_the_median_is_not_the_mean(self):
        r = self.resumo(*[tx(f"10:01:{k:02d}.000") for k in (0, 1, 2, 3, 4, 40)])
        self.assertIn("a cada 1,00 s (mediana; o maior intervalo 36,0 s)", r["frequencia"])

    def test_one_packet_has_no_rhythm(self):
        self.assertEqual("", self.resumo(tx("10:01:00.000"))["frequencia"])

    def test_the_rhythm_goes_in_the_line_of_the_section_without_moving_the_rest(self):
        d, arqs = arquivos(projecao_com("10:00:01.000", "10:09:00.000", *[tx(f"10:01:{k:02d}.000") for k in range(3)]))
        s = detalhes.secao(arqs, ler_registro.projecoes(ler_registro.ler([d])))
        self.assertIn("batimento de 3 pacotes — bateria do celular: 69 % (fixo em 3)", s[0])
        self.assertTrue(s[0].endswith("frequência: a cada 1,00 s (mediana; o maior intervalo 1,0 s) em 2 s"), s[0])

    def test_the_changing_numeric_field_tells_its_minimum_and_maximum(self):
        c = dict(self.resumo(tx("10:01:00.000", musica=0x18), tx("10:01:01.000", musica=0x16), tx("10:01:02.000", musica=0x18))["campos"])
        self.assertEqual("8/10 ×2, 6/10 ×1 (mínimo 6/10, máximo 8/10), 2 mudanças", c["volume da música"])

    def test_a_key_the_app_does_not_know_is_told_in_hex_with_its_range(self):
        extra = "10:01:0%d.000  +     0ms  [detail] dash tx 0604:A9 0777:%s\n"
        r = self.resumo(extra % (0, "10"), extra % (1, "30"), extra % (2, "10"))
        self.assertEqual("10 ×2, 30 ×1 (mínimo 10, máximo 30), 2 mudanças", dict(r["campos"])["campo 0777"])

    def test_the_icon_code_gets_no_range(self):
        c = dict(self.resumo(tx("10:01:00.000", icone="05"), tx("10:01:01.000", icone="FF"))["campos"])
        self.assertNotIn("mínimo", c["ícone do clima"])


class Grupo03AvisoTest(unittest.TestCase):
    G1 = {"0303": "55", "0306": "10"}

    def test_a_group_that_changed_is_a_warning_with_what_changed(self):
        a = detalhes.avisos03([("10:00:05", self.G1, ""), ("11:00:05", {"0303": "55", "0306": "20"}, "")])
        self.assertEqual(1, len(a), a)
        self.assertIn("mudou de uma conexão para a outra: 11:00:05 contra 10:00:05: 0306 10→20", a[0])

    def test_an_equal_group_is_no_warning(self):
        self.assertEqual([], detalhes.avisos03([("a", self.G1, ""), ("b", dict(self.G1), ""), ("c", dict(self.G1), "")]))

    def test_a_key_that_appears_or_goes_away_is_a_change_too(self):
        a = detalhes.avisos03([("a", self.G1, ""), ("b", {"0303": "55", "0306": "10", "030B": "5555"}, "")])
        self.assertIn("030B -→5555", a[0])
        b = detalhes.avisos03([("a", self.G1, ""), ("b", {"0303": "55"}, "")])
        self.assertIn("0306 10→-", b[0])

    def test_a_connection_without_the_group_is_skipped_and_the_next_is_compared_with_the_last_that_had_it(self):
        a = detalhes.avisos03([("a", self.G1, ""), ("b", {}, ""), ("c", {"0303": "55", "0306": "30"}, "")])
        self.assertEqual(1, len(a))
        self.assertIn("c contra a: 0306 10→30", a[0])

    def test_each_connection_is_compared_with_the_one_before_and_not_with_the_first(self):
        a = detalhes.avisos03([("a", self.G1, ""), ("b", {"0303": "55", "0306": "20"}, ""), ("c", {"0303": "55", "0306": "20"}, "")])
        self.assertEqual(1, len(a))

    def test_a_write_by_the_app_in_the_connection_before_is_told_with_the_warning(self):
        a = detalhes.avisos03([("a", self.G1, "temperature CELSIUS -> FAHRENHEIT"), ("b", {"0303": "55", "0306": "20"}, "")])
        self.assertEqual(1, len(a))
        self.assertIn("o app escreveu no painel na conexão anterior (temperature CELSIUS -> FAHRENHEIT)", a[0])

    def test_one_or_no_connection_gives_nothing(self):
        self.assertEqual([], detalhes.avisos03([]))
        self.assertEqual([], detalhes.avisos03([("a", self.G1, "")]))

    def test_the_analysis_gives_the_warnings_beside_the_lines(self):
        texto = (projecao_com("10:00:01.000", "10:09:00.000", rx03("10:00:05.000"), tx("10:01:00.000")) +
                 projecao_com("11:00:01.000", "11:09:00.000", rx03("11:00:05.000", **{"0306": "20", "0309": "05"}), tx("11:01:00.000")))
        d, arqs = arquivos(texto)
        linhas, avisos = detalhes.analise(arqs, ler_registro.projecoes(ler_registro.ler([d])))
        self.assertTrue(any("MUDOU" in l for l in linhas), linhas)
        self.assertEqual(1, len(avisos))
        self.assertIn("0306 10→20, 0309 04→05", avisos[0])

    def test_the_analysis_of_a_log_without_packets_gives_nothing(self):
        d, arqs = arquivos(projecao_com("10:00:01.000", "10:09:00.000", linha("10:00:05.000", "wifi", "connected")))
        self.assertEqual(([], []), detalhes.analise(arqs, ler_registro.projecoes(ler_registro.ler([d]))))

    def test_the_command_line_puts_the_change_under_the_avisos(self):
        texto = (projecao_com("10:00:01.000", "10:09:00.000", rx03("10:00:05.000"), tx("10:01:00.000")) +
                 projecao_com("11:00:01.000", "11:09:00.000", rx03("11:00:05.000", **{"0306": "20"}), tx("11:01:00.000")))
        d, _ = arquivos(texto)
        feito = subprocess.run([sys.executable, "-I", str(AQUI / "ler_registro.py"), str(d), "--tudo"], capture_output=True, text=True, encoding="utf-8")
        depois = feito.stdout.split("AVISOS DA LEITURA", 1)[-1]
        self.assertIn("o grupo 03 do painel (unidades e preferências) mudou", depois)
        self.assertIn("0306 10→20", depois)

    def test_the_command_line_has_no_group_warning_when_the_group_is_the_same(self):
        texto = (projecao_com("10:00:01.000", "10:09:00.000", rx03("10:00:05.000"), tx("10:01:00.000")) +
                 projecao_com("11:00:01.000", "11:09:00.000", rx03("11:00:05.000"), tx("11:01:00.000")))
        d, _ = arquivos(texto)
        feito = subprocess.run([sys.executable, "-I", str(AQUI / "ler_registro.py"), str(d), "--tudo"], capture_output=True, text=True, encoding="utf-8")
        self.assertNotIn("o grupo 03 do painel (unidades e preferências) mudou", feito.stdout)


@unittest.skipUnless(os.environ.get("REAU_COLETA"), "REAU_COLETA aponta para uma coleta real; sem ela este teste é pulado")
class ColetaRealTest(unittest.TestCase):
    """Reads a real collection: the pure readers over every index of `video/` and the heartbeat of every log of `diag/`."""

    def setUp(self):
        self.raiz = Path(os.environ["REAU_COLETA"])

    def test_every_video_index_is_read_in_its_format_and_its_rhythm_is_plausible(self):
        csvs = sorted((self.raiz / "video").glob("*.csv"))
        if not csvs:
            self.skipTest("a coleta não tem video/*.csv")
        for csv in csvs:
            with self.subTest(csv=csv.name):
                indice = indice_video.ler(csv, csv.with_suffix(".h264"))
                self.assertIn(indice.formato, (indice_video.NOVO, indice_video.ANTIGO, indice_video.ANTIGO_JA_SOMADO))
                texto = "\n".join(ritmo_video.resumo(indice.quadros))
                self.assertIn("quadros-chave", texto)
                self.assertIn("buraco", texto)
                soma = ritmo_video.conferir_soma(indice.formato, indice.soma, indice.tamanho_arquivo)
                if indice.formato == indice_video.NOVO:
                    self.assertTrue(soma.startswith("a soma de bytes_in_file"), soma)
                else:
                    self.assertIsNone(soma)
                if len(indice.quadros) > 200:  # a window or more, and a pace the app can hold
                    fps = ritmo_video.fps_por_janela(indice.quadros)
                    self.assertTrue(fps and 0.5 <= sorted(fps)[len(fps) // 2] <= 12, fps[:5])

    def test_the_heartbeat_of_the_logs_has_a_rhythm_near_one_second(self):
        arqs = sorted((self.raiz / "diag").glob("ride-*.log"))
        achou = False
        for a in arqs:
            pacotes = [p for p in detalhes.ler([a]) if p["direcao"] == "tx"]
            if len(pacotes) < 60:
                continue
            achou = True
            f = detalhes.frequencia([p["segundos"] for p in pacotes])
            self.assertTrue(0.5 <= f["mediana"] <= 2.0, (a.name, f))
        if not achou:
            self.skipTest("nenhum registro da coleta traz o registro de pacotes")

    def test_the_whole_reader_prints_the_rhythm_lines_for_the_collection(self):
        feito = subprocess.run([sys.executable, "-I", str(AQUI / "ler_registro.py"), str(self.raiz)], capture_output=True, text=True, encoding="utf-8")
        if "GRAVAÇÕES DA COLETA" not in feito.stdout:
            self.skipTest("a coleta não tem sessions/ nem video/ nas projeções lidas")
        if (self.raiz / "video").is_dir() and any((self.raiz / "video").glob("*.csv")):
            self.assertIn("ritmo real por janela", feito.stdout)


if __name__ == "__main__":
    unittest.main()
