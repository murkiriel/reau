"""Tests for scripts/emu/rota_falsa.py (the fake GPS): reading routes, geometry, interpolation, NMEA and the exact adb commands.
The subprocess is always a fake: no adb, no emulator, no phone.

Run from the repo root:  python3 -B -m unittest discover -s scripts/emu -p "test_rota_falsa.py" -v
"""
import contextlib
import datetime
import io
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import rota_falsa as rf  # noqa: E402

GPX_NS = """<?xml version="1.0"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>
<trkpt lat="10.0" lon="20.0"><ele>5</ele></trkpt><trkpt lat="10.001" lon="20.0"/><trkpt lat="10.002" lon="20.0"/>
</trkseg></trk></gpx>"""
GPX_SEM_NS = """<gpx version="1.0"><trk><trkseg><trkpt lat="10.0" lon="20.0"/><trkpt lat="10.001" lon="20.0"/></trkseg></trk></gpx>"""
GPX_RTE = """<gpx xmlns="http://www.topografix.com/GPX/1/0"><rte><rtept lat="1.0" lon="2.0"/><rtept lat="1.0" lon="2.01"/></rte></gpx>"""
UTC = datetime.timezone.utc
QUANDO = datetime.datetime(2026, 11, 25, 12, 34, 56, 780000, tzinfo=UTC)
BASE = ["adb", "-s", "emulator-5554"]


def reta_norte(metros=1000.0):
    """A straight route going north from (0, 0): one degree of latitude is about 111195 m."""
    return rf.Trajeto([(0.0, 0.0), (metros / 111195.0, 0.0)])


def args(**kw):
    p = rf.montar_parser().parse_args(["rota.gpx"])
    for k, v in kw.items():
        setattr(p, k, v)
    return p


class FakeRun:
    """Stands in for subprocess.run: records every command and answers with the given return code and text."""

    def __init__(self, returncode=0, stdout="OK\n", stderr="", raises=None):
        self.calls = []
        self.returncode, self.stdout, self.stderr, self.raises = returncode, stdout, stderr, raises

    def __call__(self, cmd, **kwargs):
        self.calls.append(list(cmd))
        if self.raises:
            raise self.raises
        return subprocess.CompletedProcess(cmd, self.returncode, self.stdout, self.stderr)


class ReadingTest(unittest.TestCase):
    def test_gpx_with_namespace_trkpt(self):
        self.assertEqual([(10.0, 20.0), (10.001, 20.0), (10.002, 20.0)], rf.ler_gpx(GPX_NS))

    def test_gpx_without_namespace(self):
        self.assertEqual([(10.0, 20.0), (10.001, 20.0)], rf.ler_gpx(GPX_SEM_NS))

    def test_gpx_rtept_when_there_is_no_trkpt(self):
        self.assertEqual([(1.0, 2.0), (1.0, 2.01)], rf.ler_gpx(GPX_RTE))

    def test_trkpt_wins_over_rtept(self):
        texto = '<gpx><rte><rtept lat="1" lon="1"/><rtept lat="2" lon="2"/></rte><trk><trkseg><trkpt lat="5" lon="5"/><trkpt lat="6" lon="6"/></trkseg></trk></gpx>'
        self.assertEqual([(5.0, 5.0), (6.0, 6.0)], rf.ler_gpx(texto))

    def test_json_list(self):
        self.assertEqual([(1.0, 2.0), (3.5, 4.5)], rf.ler_json("[[1, 2], [3.5, 4.5]]"))

    def test_consecutive_duplicates_are_dropped(self):
        self.assertEqual([(1.0, 2.0), (1.0, 3.0)], rf.ler_json("[[1, 2], [1, 2], [1, 3]]"))

    def test_bad_inputs_have_a_clear_error(self):
        for texto, ler in (("<gpx></gpx>", rf.ler_gpx), ("<gpx><trk>", rf.ler_gpx), ("[]", rf.ler_json), ("[[1, 2]]", rf.ler_json),
                           ("[[1, 2], [1, 2]]", rf.ler_json), ('{"a": 1}', rf.ler_json), ("[[1]]", rf.ler_json), ("[[100, 2], [1, 2]]", rf.ler_json),
                           ("[[1, 2], [\"a\", 2]]", rf.ler_json), ("nao e json", rf.ler_json),
                           ('<gpx><trk><trkseg><trkpt lat="x" lon="1"/><trkpt lat="1" lon="1"/></trkseg></trk></gpx>', rf.ler_gpx)):
            with self.subTest(texto=texto), self.assertRaises(rf.RotaInvalida):
                ler(texto)

    def test_one_point_message_says_how_many(self):
        with self.assertRaisesRegex(rf.RotaInvalida, "pelo menos 2 pontos"):
            rf.ler_gpx('<gpx><trk><trkseg><trkpt lat="1" lon="1"/></trkseg></trk></gpx>')

    def test_files_by_extension_and_empty_or_missing_file(self):
        with tempfile.TemporaryDirectory() as d:
            gpx, js, vazio = Path(d, "a.gpx"), Path(d, "b.json"), Path(d, "c.gpx")
            gpx.write_text(GPX_NS, encoding="utf-8")
            js.write_text("[[1, 2], [3, 4]]", encoding="utf-8")
            vazio.write_text("  \n", encoding="utf-8")
            self.assertEqual(3, len(rf.ler_rota(str(gpx))))
            self.assertEqual([(1.0, 2.0), (3.0, 4.0)], rf.ler_rota(str(js)))
            with self.assertRaisesRegex(rf.RotaInvalida, "vazio"):
                rf.ler_rota(str(vazio))
            with self.assertRaisesRegex(rf.RotaInvalida, "não consegui ler"):
                rf.ler_rota(str(Path(d, "nao_existe.gpx")))

    def test_the_sample_routes_load(self):
        for nome in sorted(Path(HERE, "rotas").glob("*.gpx")):
            with self.subTest(rota=nome.name):
                pontos = rf.ler_rota(str(nome))
                total = rf.Trajeto(pontos).total
                self.assertGreater(len(pontos), 2)
                self.assertGreater(total, 1000)


class GeometryTest(unittest.TestCase):
    def test_haversine_known_values(self):
        self.assertEqual(0.0, rf.haversine((10.0, 20.0), (10.0, 20.0)))
        self.assertAlmostEqual(111195.0, rf.haversine((0.0, 0.0), (1.0, 0.0)), delta=10)
        self.assertAlmostEqual(111195.0, rf.haversine((0.0, 0.0), (0.0, 1.0)), delta=10)  # one degree of longitude on the equator
        paris_londres = rf.haversine((48.86, 2.35), (51.51, -0.13))  # city centres, two decimals (the privacy guard flags precise pairs)
        self.assertTrue(342000 < paris_londres < 346000, paris_londres)

    def test_bearing_cardinals(self):
        self.assertAlmostEqual(0.0, rf.rumo((0, 0), (1, 0)), places=6)
        self.assertAlmostEqual(90.0, rf.rumo((0, 0), (0, 1)), places=6)
        self.assertAlmostEqual(180.0, rf.rumo((1, 0), (0, 0)), places=6)
        self.assertAlmostEqual(270.0, rf.rumo((0, 1), (0, 0)), places=6)

    def test_bearing_is_never_negative_or_360(self):
        for destino in ((1, -0.0001), (1, -1), (-1, -1)):
            self.assertTrue(0.0 <= rf.rumo((0, 0), destino) < 360.0)


class InterpolationTest(unittest.TestCase):
    def test_spacing_follows_the_speed(self):
        fixes = list(rf.interpolar(reta_norte(1000), kmh=36.0, intervalo=1.0))  # 10 m per point
        for a, b in zip(fixes, fixes[1:-1]):
            self.assertAlmostEqual(10.0, rf.haversine((a.lat, a.lon), (b.lat, b.lon)), delta=0.05)
        self.assertAlmostEqual(10.0, fixes[1].dist_m - fixes[0].dist_m, places=6)
        self.assertEqual(10.0, fixes[0].vel_ms)

    def test_interval_scales_the_step(self):
        fixes = list(rf.interpolar(reta_norte(1000), kmh=36.0, intervalo=2.0))
        self.assertAlmostEqual(20.0, fixes[1].dist_m, places=6)

    def test_first_and_last_points_are_exact(self):
        t = rf.Trajeto([(0.0, 0.0), (0.001, 0.0), (0.001, 0.002)])
        fixes = list(rf.interpolar(t, kmh=40.0, intervalo=1.0))
        self.assertEqual((0.0, 0.0), (fixes[0].lat, fixes[0].lon))
        self.assertEqual((0.001, 0.002), (fixes[-1].lat, fixes[-1].lon))
        self.assertEqual(t.total, fixes[-1].dist_m)
        self.assertEqual(1, [f.dist_m for f in fixes].count(t.total))

    def test_last_point_not_duplicated_when_the_step_divides_the_route(self):
        fixes = list(rf.interpolar(reta_norte(100), kmh=36.0, intervalo=1.0))  # exactly 10 steps of 10 m
        self.assertEqual(1, [f.dist_m for f in fixes].count(fixes[-1].dist_m))
        self.assertEqual(11, len(fixes))

    def test_course_follows_the_leg(self):
        t = rf.Trajeto([(0.0, 0.0), (0.001, 0.0), (0.001, 0.001)])
        fixes = list(rf.interpolar(t, kmh=36.0, intervalo=1.0))
        self.assertAlmostEqual(0.0, fixes[0].rumo, places=3)
        self.assertAlmostEqual(90.0, fixes[-2].rumo, places=1)

    def test_start_in_metres_and_by_index(self):
        t = rf.Trajeto([(0.0, 0.0), (0.001, 0.0), (0.002, 0.0)])
        self.assertEqual(0.0, rf.resolver_inicio(None, t))
        self.assertEqual(150.0, rf.resolver_inicio("150", t))
        self.assertEqual(150.0, rf.resolver_inicio("150m", t))
        self.assertAlmostEqual(t.acum[1], rf.resolver_inicio("p1", t))
        primeiro = next(rf.interpolar(t, 40.0, 1.0, inicio_m=150.0))
        self.assertAlmostEqual(150.0, rf.haversine((0.0, 0.0), (primeiro.lat, primeiro.lon)), delta=0.1)
        for ruim in ("-5", "999999", "abc", "p9", "p"):
            with self.subTest(ruim=ruim), self.assertRaises(rf.RotaInvalida):
                rf.resolver_inicio(ruim, t)

    def test_repeat_keeps_going_and_wraps(self):
        t = reta_norte(100)
        it = rf.interpolar(t, kmh=36.0, intervalo=1.0, repetir=True)
        dists = [next(it).dist_m for _ in range(25)]
        self.assertTrue(all(0.0 <= d < t.total for d in dists))
        self.assertAlmostEqual(dists[1], dists[11], places=3)
        self.assertAlmostEqual(100.0, dists[10], delta=0.01)

    def test_repeat_closes_an_open_route_with_a_leg_back(self):
        aberta = [(0.0, 0.0), (0.01, 0.0)]
        self.assertEqual(aberta + [(0.0, 0.0)], rf.Trajeto(aberta, repetir=True).pontos)
        self.assertEqual(aberta, rf.Trajeto(aberta, repetir=False).pontos)
        fechada = [(0.0, 0.0), (0.01, 0.0), (0.0, 0.0001)]
        self.assertEqual(fechada, rf.Trajeto(fechada, repetir=True).pontos)


class NmeaTest(unittest.TestCase):
    def test_checksum_against_reference_sentences(self):
        self.assertEqual("47", rf.nmea_checksum("GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"))
        self.assertEqual("6A", rf.nmea_checksum("GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W"))

    def test_sentences_have_the_right_fields_and_a_valid_checksum(self):
        fix = rf.Fix(-23.5, 46.75, 90.0, 10.0, 0.0)  # south and east
        gga, rmc = rf.sentencas_nmea(fix, QUANDO, altitude=12.0)
        self.assertTrue(gga.startswith("$GPGGA,123456.78,2330.0000,S,04645.0000,E,1,08,0.9,12.0,M,"))
        self.assertTrue(rmc.startswith("$GPRMC,123456.78,A,2330.0000,S,04645.0000,E,19.4,90.0,251126,,,A*"))
        for s in (gga, rmc):
            corpo, _, cs = s[1:].partition("*")
            self.assertEqual(rf.nmea_checksum(corpo), cs)

    def test_west_and_no_60_minutes(self):
        fix = rf.Fix(39 - 1e-8, -(10 - 1e-8), 0.0, 0.0, 0.0)  # just under 39 N / 10 W
        gga = rf.sentencas_nmea(fix, QUANDO)[0]
        self.assertIn(",3900.0000,N,01000.0000,W,", gga)


class CommandsTest(unittest.TestCase):
    # Ponto fictício de 2 casas (26/11/2026, revisão do plano 34, A3): a PrivacyGuardTest recusa pares com 4 casas ou mais, e o teste prova o mesmo com um ponto assim.
    LAT = -10.12
    LON = -50.57
    FIX = rf.Fix(LAT, LON, 45.0, 11.1, 0.0)
    # O script escreve 6 casas; o esperado é feito do mesmo ponto (um literal de 6 casas seria o par que a guarda de privacidade recusa).
    LAT_6 = f"{LAT:.6f}"
    LON_6 = f"{LON:.6f}"

    def test_geo_fix_puts_longitude_before_latitude(self):
        self.assertEqual([BASE + ["emu", "geo", "fix", self.LON_6, self.LAT_6]], rf.cmd_emulador(BASE, self.FIX, False, None, QUANDO))

    def test_geo_fix_with_altitude(self):
        self.assertEqual([BASE + ["emu", "geo", "fix", self.LON_6, self.LAT_6, "760.0"]], rf.cmd_emulador(BASE, self.FIX, False, 760.0, QUANDO))

    def test_geo_nmea_sends_gga_then_rmc_as_one_argument_each(self):
        gga, rmc = rf.cmd_emulador(BASE, self.FIX, True, None, QUANDO)
        self.assertEqual(BASE + ["emu", "geo", "nmea"], gga[:6])
        self.assertTrue(gga[6].startswith("$GPGGA,"))
        self.assertTrue(rmc[6].startswith("$GPRMC,"))
        self.assertEqual(7, len(gga))

    def test_phone_commands(self):
        b = ["adb"]
        loc = ["shell", "cmd", "location", "providers"]
        self.assertEqual([b + loc + ["add-test-provider", "gps"], b + loc + ["set-test-provider-enabled", "gps", "true"]], rf.cmd_celular_preparar(b))
        self.assertEqual(b + loc + ["set-test-provider-location", "gps", "--location", f"{self.LAT_6},{self.LON_6}", "--accuracy", "5"],
                         rf.cmd_celular_ponto(b, self.FIX))
        self.assertEqual(b + loc + ["remove-test-provider", "gps"], rf.cmd_celular_remover_provedor(b))
        self.assertEqual(b + ["shell", "appops", "set", "--uid", "2000", "MOCK_LOCATION", "default"], rf.cmd_celular_appop_padrao(b))

    def test_adb_resolution_order(self):
        with mock.patch.dict(os.environ, {"ADB": "/env/adb"}), mock.patch("shutil.which", return_value="/path/adb"):
            self.assertEqual("/arg/adb", rf.achar_adb("/arg/adb", False))
            self.assertEqual("/env/adb", rf.achar_adb(None, False))
        with mock.patch.dict(os.environ, {}, clear=True), mock.patch("shutil.which", return_value="/path/adb"):
            self.assertEqual("/path/adb", rf.achar_adb(None, False))
        with mock.patch.dict(os.environ, {}, clear=True), mock.patch("shutil.which", return_value=None):
            self.assertEqual("adb", rf.achar_adb(None, True))
            with self.assertRaises(rf.ErroAdb):
                rf.achar_adb(None, False)

    def test_base_with_and_without_serial(self):
        self.assertEqual(["adb", "-s", "X"], rf.base_adb("adb", "X"))
        self.assertEqual(["adb"], rf.base_adb("adb", None))


class ExecutorTest(unittest.TestCase):
    def test_errors_become_erroadb_with_the_text(self):
        for fake, trecho in ((FakeRun(returncode=1, stderr="boom"), "boom"), (FakeRun(stdout="KO: x\n"), "KO: x"),
                             (FakeRun(stdout="java.lang.SecurityException: not allowed to mock location"), "MOCK_LOCATION allow"),
                             (FakeRun(raises=FileNotFoundError()), "adb não encontrado"),
                             (FakeRun(raises=subprocess.TimeoutExpired("adb", 1)), "não respondeu")):
            with self.subTest(trecho=trecho), mock.patch("subprocess.run", fake), self.assertRaisesRegex(rf.ErroAdb, trecho):
                rf.Executor().rodar(["adb", "x"])

    def test_tolerated_failure_is_silent(self):
        with mock.patch("subprocess.run", FakeRun(returncode=1, stderr="no such provider")):
            rf.Executor().rodar(["adb", "x"], tolerar=True)

    def test_dry_run_prints_and_never_calls_subprocess(self):
        linhas = []
        with mock.patch("subprocess.run", side_effect=AssertionError("não devia chamar")) as run:
            rf.Executor(seco=True, saida=linhas.append).rodar(["adb", "emu", "geo", "nmea", "$GPGGA,1*00"])
            run.assert_not_called()
        self.assertEqual(["adb emu geo nmea '$GPGGA,1*00'"], linhas)


class RunTest(unittest.TestCase):
    def rodar(self, a, fake, pontos=None, dormir=None, **kw):
        pontos = pontos or [(0.0, 0.0), (0.0004, 0.0)]  # about 44 m
        saida = []
        hora = [0.0]  # a simulated clock that only moves when the code sleeps

        def dorme(s):
            hora[0] += s
            if dormir:
                dormir(s)

        with mock.patch("subprocess.run", fake):
            codigo = rf.percorrer(a, pontos, rf.Executor(a.seco, saida=saida.append), kw.pop("base", BASE), saida=saida.append, dormir=dorme,
                                  relogio=lambda: hora[0], agora=lambda: QUANDO)
        return codigo, saida

    def test_emulator_run_sends_one_geo_fix_per_point_and_ends_on_the_last_point(self):
        fake = FakeRun()
        codigo, saida = self.rodar(args(kmh=36.0), fake)
        self.assertEqual(0, codigo)
        self.assertTrue(all(c[:6] == BASE + ["emu", "geo", "fix"] for c in fake.calls))
        self.assertEqual(["emu", "geo", "fix", "0.000000", "0.000000"], fake.calls[0][3:])
        self.assertEqual(["emu", "geo", "fix", "0.000000", "0.000400"], fake.calls[-1][3:])
        self.assertEqual(6, len(fake.calls))  # 0, 10, 20, 30, 40 m and the end (44.5 m)
        self.assertEqual("Fim da rota.", saida[-1])

    def test_phone_run_prepares_walks_and_cleans_up(self):
        fake = FakeRun()
        b = ["adb"]
        codigo, _ = self.rodar(args(alvo="celular", kmh=36.0), fake, base=b)
        self.assertEqual(0, codigo)
        loc = b + ["shell", "cmd", "location", "providers"]
        self.assertEqual(loc + ["remove-test-provider", "gps"], fake.calls[0])
        self.assertEqual(loc + ["add-test-provider", "gps"], fake.calls[1])
        self.assertEqual(loc + ["set-test-provider-enabled", "gps", "true"], fake.calls[2])
        self.assertEqual(loc + ["set-test-provider-location", "gps", "--location", "0.000000" + "," + "0.000000", "--accuracy", "5"], fake.calls[3])
        self.assertEqual(loc + ["remove-test-provider", "gps"], fake.calls[-1])
        self.assertFalse(any("appops" in c for c in fake.calls))  # the appop is only reset by --limpar

    def test_phone_provider_is_removed_even_on_ctrl_c(self):
        fake = FakeRun()

        def interrompe(_):
            raise KeyboardInterrupt

        codigo, saida = self.rodar(args(alvo="celular"), fake, dormir=interrompe, base=["adb"])
        self.assertEqual(130, codigo)
        self.assertIn("remove-test-provider", fake.calls[-1])
        self.assertEqual("Provedor de teste removido.", saida[-1])

    def test_phone_provider_is_removed_when_adb_fails_midway(self):
        calls = []

        def run(cmd, **kw):
            calls.append(list(cmd))
            falha = "set-test-provider-location" in cmd
            return subprocess.CompletedProcess(cmd, 1 if falha else 0, "", "SecurityException" if falha else "")

        with mock.patch("subprocess.run", run), self.assertRaises(rf.ErroAdb):
            rf.percorrer(args(alvo="celular"), [(0.0, 0.0), (0.001, 0.0)], rf.Executor(), ["adb"], saida=lambda s: None,
                         dormir=lambda s: None, relogio=lambda: 0.0)
        self.assertIn("remove-test-provider", calls[-1])

    def test_keep_flag_skips_the_cleanup(self):
        fake = FakeRun()
        self.rodar(args(alvo="celular", manter=True), fake, base=["adb"])
        self.assertNotIn("remove-test-provider", fake.calls[-1])
        self.assertEqual(1, sum("remove-test-provider" in c for c in fake.calls))  # only the one before add-test-provider

    def test_wait_is_the_interval_divided_by_acelerar_and_never_negative(self):
        esperas = []
        self.rodar(args(kmh=36.0, intervalo=2.0, acelerar=4.0), FakeRun(), dormir=esperas.append)
        self.assertTrue(esperas)
        self.assertTrue(all(abs(e - 0.5) < 1e-9 for e in esperas), esperas)  # the clock moves only by the sleeps, so each wait is the full 0.5 s
        self.assertEqual(0.5, esperas[0])

    def test_dry_run_does_not_call_subprocess_nor_wait(self):
        esperas = []
        with mock.patch("subprocess.run", side_effect=AssertionError("não devia chamar")):
            codigo, saida = self.rodar(args(seco=True, alvo="celular"), FakeRun(), dormir=esperas.append, base=["adb"])
        self.assertEqual(0, codigo)
        self.assertEqual([], esperas)
        self.assertTrue(any("add-test-provider" in linha for linha in saida))

    def test_progress_lines_do_not_carry_coordinates(self):
        _, saida = self.rodar(args(kmh=36.0), FakeRun())
        self.assertTrue(any("km/h" in linha for linha in saida))
        self.assertFalse(any("0.0004" in linha for linha in saida))


class MainTest(unittest.TestCase):
    def main(self, argv, env=None):
        out, err = io.StringIO(), io.StringIO()
        with mock.patch.dict(os.environ, env or {}, clear=True), contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            try:
                codigo = rf.main(argv)
            except SystemExit as e:
                codigo = e.code
        return codigo, out.getvalue(), err.getvalue()

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.gpx = str(Path(self.dir.name, "r.gpx"))
        Path(self.gpx).write_text(GPX_NS, encoding="utf-8")

    def test_dry_run_end_to_end_uses_the_default_serial_and_env_adb(self):
        with mock.patch("subprocess.run", side_effect=AssertionError("não devia chamar")):
            codigo, out, _ = self.main([self.gpx, "--seco"], {"ADB": "/x/adb"})
        self.assertEqual(0, codigo)
        self.assertIn("/x/adb -s emulator-5554 emu geo fix 20.000000 10.000000", out)

    def test_serial_from_env_and_from_flag(self):
        _, out, _ = self.main([self.gpx, "--seco"], {"ADB": "adb", "ANDROID_SERIAL": "emulator-5560"})
        self.assertIn("adb -s emulator-5560 emu geo fix", out)
        _, out, _ = self.main([self.gpx, "--seco", "--serial", "emulator-5570"], {"ADB": "adb", "ANDROID_SERIAL": "emulator-5560"})
        self.assertIn("adb -s emulator-5570 emu geo fix", out)

    def test_phone_without_serial_has_no_dash_s(self):
        _, out, _ = self.main([self.gpx, "--seco", "--alvo", "celular"], {"ADB": "adb"})
        self.assertIn("adb shell cmd location providers add-test-provider gps", out)

    def test_emulator_target_refuses_a_non_emulator_serial(self):
        codigo, _, err = self.main([self.gpx, "--seco", "--serial", "ABC123"], {"ADB": "adb"})
        self.assertEqual(2, codigo)
        self.assertIn("não é de emulador", err)

    def test_usage_errors(self):
        for argv in ([], [self.gpx, "--kmh", "0"], [self.gpx, "--acelerar", "-1"], [self.gpx, "--alvo", "celular", "--nmea"], [self.gpx, "--seco", "--repetir"]):
            with self.subTest(argv=argv):
                self.assertEqual(2, self.main(argv, {"ADB": "adb"})[0])

    def test_clear_phone_removes_provider_and_resets_the_appop(self):
        fake = FakeRun()
        with mock.patch("subprocess.run", fake):
            codigo, _, _ = self.main(["--alvo", "celular", "--limpar"], {"ADB": "adb"})
        self.assertEqual(0, codigo)
        self.assertEqual(["adb", "shell", "cmd", "location", "providers", "remove-test-provider", "gps"], fake.calls[0])
        self.assertEqual(["adb", "shell", "appops", "set", "--uid", "2000", "MOCK_LOCATION", "default"], fake.calls[1])

    def test_clear_on_the_emulator_does_nothing(self):
        with mock.patch("subprocess.run", side_effect=AssertionError("não devia chamar")):
            self.assertEqual(0, self.main(["--limpar"], {"ADB": "adb"})[0])

    def test_empty_file_and_single_point_give_exit_2_with_a_message(self):
        vazio = Path(self.dir.name, "v.gpx")
        vazio.write_text("", encoding="utf-8")
        um = Path(self.dir.name, "u.json")
        um.write_text("[[1, 2]]", encoding="utf-8")
        for caminho, trecho in ((vazio, "vazio"), (um, "pelo menos 2 pontos")):
            with self.subTest(caminho=caminho.name):
                codigo, _, err = self.main([str(caminho), "--seco"], {"ADB": "adb"})
                self.assertEqual(2, codigo)
                self.assertIn(trecho, err)

    def test_adb_failure_is_exit_1(self):
        with mock.patch("subprocess.run", FakeRun(returncode=1, stderr="no devices")):
            codigo, _, err = self.main([self.gpx], {"ADB": "adb"})
        self.assertEqual(1, codigo)
        self.assertIn("no devices", err)


if __name__ == "__main__":
    unittest.main()
