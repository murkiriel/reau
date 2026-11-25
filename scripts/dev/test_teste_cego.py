"""Minimal tests of the blind-test tools of scripts/dev/teste_cego (item 41, 11/11/2026): extrair.py (video out of a session recording), avaliar_cego.py and avaliar_noite.py (the
rule of the picture against labelled frames) and amostrar_cego.py (contact sheets, needs Pillow and the decoder `dec5`, so it is skipped where Pillow is not installed).
Synthetic recordings and indexes, built here; nothing reads a real one.

    cd scripts/dev && python3 -B -m unittest discover -p "test_*.py"
"""
import json
import os
import stat
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

CEGO = Path(__file__).resolve().parent / "teste_cego"
sys.path.insert(0, str(CEGO))
import extrair  # noqa: E402  (does not run anything on import since 11/11/2026)
import indice_video  # noqa: E402

START = b"\x00\x00\x00\x01"
SPS_PPS = b"\x67\x42\x00\x29\x00\x00\x00\x01\x68\xce"
IDR = b"\x00\x00\x01\x65\x88\x84"       # NAL type 5
P_FRAME = b"\x00\x00\x01\x41\x9a\x24"    # NAL type 1


def msg(t_ms, canal, flags, carga):
    return struct.pack(">iBBi", t_ms, canal, flags, len(carga)) + carga


def video(tipo, corpo):
    return struct.pack(">H", tipo) + (b"\x00" * 8 if tipo == 0 else b"") + corpo


def aasession(inicio_ms, mensagens, assinatura=b"REAA"):
    return assinatura + b"\x01" + struct.pack(">q", inicio_ms) + b"".join(mensagens)


def rodar(script, *args, cwd=None, env=None):
    return subprocess.run([sys.executable, "-I", str(CEGO / script), *map(str, args)], capture_output=True, text=True, cwd=cwd, env=env)


class ExtrairTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)

    def sessao(self, nome, inicio, quadros, config=True):
        mensagens = [msg(0, 2, 3, video(1, SPS_PPS))] if config else []
        mensagens += [msg(t, 2, 3, video(0, corpo)) for t, corpo in quadros]
        arquivo = self.dir / nome
        arquivo.write_bytes(aasession(inicio, mensagens))
        return arquivo

    def test_the_frames_before_the_first_keyframe_are_dropped_and_the_config_goes_before_the_first_kept(self):
        a = self.sessao("a.aasession", 1_000_000, [(100, P_FRAME), (600, IDR), (1100, P_FRAME)])
        feito = rodar("extrair.py", self.dir / "saida", "tag", a)
        self.assertEqual(0, feito.returncode, feito.stderr)
        h264 = (self.dir / "saida" / "tag.h264").read_bytes()
        self.assertEqual(SPS_PPS + IDR + P_FRAME, h264)
        linhas = (self.dir / "saida" / "tag.csv").read_text().splitlines()
        self.assertEqual(indice_video.CABECALHO_NOVO, linhas[0])
        self.assertEqual(["1,600,%d,1" % (len(SPS_PPS) + len(IDR)), "2,1100,%d,0" % len(P_FRAME)], linhas[1:])
        self.assertEqual("1000000", (self.dir / "saida" / "tag.start").read_text())
        self.assertIn("2 quadros", feito.stdout)

    def test_the_csv_sum_is_the_size_of_the_file_so_the_index_reads_without_a_warning(self):
        a = self.sessao("a.aasession", 5, [(0, IDR), (500, P_FRAME)])
        rodar("extrair.py", self.dir / "saida", "tag", a)
        indice = indice_video.ler(self.dir / "saida" / "tag.csv", self.dir / "saida" / "tag.h264")
        self.assertEqual([], indice.avisos)
        self.assertEqual(indice_video.NOVO, indice.formato)

    def test_two_parts_are_joined_in_time_with_the_second_offset_by_its_start(self):
        antes = self.sessao("p1.aasession", 1_000_000, [(0, IDR), (400, P_FRAME)])
        depois = self.sessao("p2.aasession", 1_002_000, [(0, IDR)], config=False)
        feito = rodar("extrair.py", self.dir / "saida", "dois", antes, depois)
        self.assertEqual(0, feito.returncode, feito.stderr)
        pts = [int(l.split(",")[1]) for l in (self.dir / "saida" / "dois.csv").read_text().splitlines()[1:]]
        self.assertEqual([0, 400, 2000], pts)

    def test_a_recording_without_a_config_needs_the_one_given_by_the_environment(self):
        a = self.sessao("a.aasession", 5, [(0, IDR)], config=False)
        self.assertNotEqual(0, rodar("extrair.py", self.dir / "s1", "tag", a).returncode)
        feito = rodar("extrair.py", self.dir / "s2", "tag", a, env={**os.environ, "CFG_HEX": SPS_PPS.hex()})
        self.assertEqual(0, feito.returncode, feito.stderr)
        self.assertEqual(SPS_PPS + IDR, (self.dir / "s2" / "tag.h264").read_bytes())

    def test_a_recording_without_a_keyframe_is_refused(self):
        a = self.sessao("a.aasession", 5, [(0, P_FRAME)])
        self.assertNotEqual(0, rodar("extrair.py", self.dir / "s", "tag", a).returncode)

    def test_a_file_that_is_not_a_session_is_refused_and_the_old_signature_is_read(self):
        ruim = self.dir / "ruim.aasession"
        ruim.write_bytes(b"XXXX" + b"\x00" * 20)
        self.assertNotEqual(0, rodar("extrair.py", self.dir / "s", "tag", ruim).returncode)
        antigo = self.dir / "antigo.aasession"
        antigo.write_bytes(aasession(7, [msg(0, 2, 3, video(1, SPS_PPS)), msg(10, 2, 3, video(0, IDR))], assinatura=b"PGAA"))
        self.assertEqual(0, rodar("extrair.py", self.dir / "s2", "tag", antigo).returncode)

    def test_the_functions_join_the_pieces_of_a_message_and_find_the_keyframe(self):
        pedacos = [(0, 2, 1, b"ab"), (1, 2, 0, b"cd"), (2, 2, 2, b"ef"), (3, 5, 2, b"orfa"), (4, 3, 3, b"xy")]
        self.assertEqual([(2, 2, 1, b"abcdef"), (4, 3, 3, b"xy")], extrair.juntar(pedacos))
        self.assertTrue(extrair.tem_idr(IDR))
        self.assertFalse(extrair.tem_idr(P_FRAME))
        self.assertFalse(extrair.tem_idr(b"\x00\x00\x01"))
        self.assertEqual(0x0001, extrair.tipo(b"\x00\x01rest"))
        self.assertEqual(-1, extrair.tipo(b"\x00"))

    def test_a_cut_message_at_the_end_is_ignored_and_the_start_clock_is_read(self):
        a = self.dir / "cortada.aasession"
        dados = aasession(42, [msg(0, 2, 3, video(0, IDR))]) + struct.pack(">iBBi", 5, 2, 3, 1000) + b"xx"
        a.write_bytes(dados)
        inicio, msgs = extrair.ler(str(a))
        self.assertEqual(42, inicio)
        self.assertEqual(1, len(msgs))


def lit_bin(compartilhamentos):
    """[ (luma>=15, 20, 25, 30, …) contados em 64x40 ] por quadro, como o dec5 grava: 10 inteiros de 16 bits (20 bytes) por quadro."""
    return b"".join(struct.pack("<10H", *c) for c in compartilhamentos)


class AvaliarCegoTest(unittest.TestCase):
    def test_the_rule_is_scored_per_labelled_group_and_the_transition_frames_are_left_out(self):
        with tempfile.TemporaryDirectory() as tmp:
            w = Path(tmp)
            (w / "cego").mkdir()
            (w / "t1").mkdir()
            # frames 1..3 of the sample: a map (lit 2000/2560), a menu (lit 300) and a transition (ignored)
            (w / "t1" / "lit.bin").write_bytes(lit_bin([[2000] * 10, [300] * 10, [1500] * 10]))
            (w / "cego" / "amostra.json").write_text(json.dumps([["t1", 1, 0.0], ["t1", 2, 0.5], ["t1", 3, 1.0]]))
            rotulos = {"rotulos": {"N": [1], "M": [2], "T": [3]}, "grupos": {"waze": [1], "menu": [2]}, "faixas": {},
                       "regra_atual": [30, 0.75, 0.55], "regra_noite": [30, 0.75, 0.55]}
            (w / "rot.json").write_text(json.dumps(rotulos))
            feito = rodar("avaliar_cego.py", w, w / "rot.json")
            self.assertEqual(0, feito.returncode, feito.stderr)
            self.assertIn("REGRA ATUAL DO REAU", feito.stdout)
            self.assertIn("-> 2 quadros: certo 2 (100.0%), errado 0, sem voto 0", feito.stdout)
            self.assertIn("waze", feito.stdout)
            self.assertIn("menu", feito.stdout)

    def test_a_frame_the_rule_gets_wrong_is_counted_as_wrong(self):
        with tempfile.TemporaryDirectory() as tmp:
            w = Path(tmp)
            (w / "cego").mkdir()
            (w / "t1").mkdir()
            (w / "t1" / "lit.bin").write_bytes(lit_bin([[300] * 10]))  # dark, labelled as a map
            (w / "cego" / "amostra.json").write_text(json.dumps([["t1", 1, 0.0]]))
            (w / "rot.json").write_text(json.dumps({"rotulos": {"N": [1]}, "grupos": {"waze": [1]}, "faixas": {}, "regra_atual": [30, 0.75, 0.55], "regra_noite": [30, 0.75, 0.55]}))
            feito = rodar("avaliar_cego.py", w, w / "rot.json")
            self.assertIn("-> 1 quadros: certo 0 (0.0%), errado 1, sem voto 0", feito.stdout)


class AvaliarNoiteTest(unittest.TestCase):
    def test_the_readings_are_labelled_by_the_report_windows_and_scored_for_both_sessions(self):
        with tempfile.TemporaryDirectory() as tmp:
            w, s = Path(tmp) / "dec", Path(tmp) / "runs"
            for tag in ("cal", "tst"):
                (w / tag).mkdir(parents=True)
                base_ms = (3 * 3600 + 5003) * 1000  # the clock of the phone is 3 h ahead of the report's
                (w / f"{tag}.start").write_text(str(base_ms))
                (w / f"{tag}.csv").write_text("frame,pts_ms,bytes_in_file,key\n" + "".join(f"{i + 1},{i * 600},100,0\n" for i in range(8)))
                (w / tag / "lit.bin").write_bytes(lit_bin([[2000] * 10] * 8))
            (s / "seed-1").mkdir(parents=True)
            (s / "seed-1" / "relatorio.json").write_text(json.dumps({"t0": [5000.0, 5010.0], "steps": [{"kind": "open", "app": "waze", "wait": 8}, {"kind": "menu", "wait": 8}]}))
            feito = rodar("avaliar_noite.py", w, s, "cal", "tst")
            self.assertEqual(0, feito.returncode, feito.stderr)
            self.assertIn("leituras estáveis: calibração (cal)", feito.stdout)
            self.assertIn("REGRA ATUAL em cal", feito.stdout)
            self.assertIn("waze", feito.stdout)
            self.assertIn("MELHOR REGRA em tst (teste, nunca visto na busca)", feito.stdout)


def pillow_instalado():
    """Whether Pillow loads the way the scripts are run (`rodar` uses `python3 -I`, which leaves out the user's site-packages): a Pillow
    installed only for the user (`pip install --user`, as on the CI) is there for this process and not for the script."""
    return subprocess.run([sys.executable, "-I", "-c", "import PIL"], capture_output=True).returncode == 0


@unittest.skipUnless(pillow_instalado(), "Pillow não está instalado (scripts/requirements.txt): amostrar_cego.py não roda")
class AmostrarCegoTest(unittest.TestCase):
    def test_it_draws_the_frames_with_a_fixed_seed_and_makes_the_contact_sheets_with_a_fake_decoder(self):
        with tempfile.TemporaryDirectory() as tmp:
            w = Path(tmp)
            (w / "t1.csv").write_text("frame,pts_ms,bytes_in_file,key\n" + "".join(f"{i + 1},{i * 600},100,0\n" for i in range(20)))
            (w / "t1.start").write_text("0")
            decoder = w / "dec5"
            decoder.write_text('#!/usr/bin/env bash\nout="$3"; shift 3\nfor i in "$@"; do printf "P6\\n4 4\\n255\\n" > "$out/f$(printf %05d $i).ppm"; head -c 48 /dev/zero >> "$out/f$(printf %05d $i).ppm"; done\n')
            decoder.chmod(decoder.stat().st_mode | stat.S_IXUSR)
            feito = rodar("amostrar_cego.py", w, "t1:5", cwd=w)
            self.assertEqual(0, feito.returncode, feito.stderr)
            amostra = json.loads((w / "cego" / "amostra.json").read_text())
            self.assertEqual(5, len(amostra))
            self.assertTrue((w / "cego" / "folha_1.png").exists())
            outra = rodar("amostrar_cego.py", w, "t1:5", cwd=w)
            self.assertEqual(amostra, json.loads((w / "cego" / "amostra.json").read_text()))  # the same seed, the same draw
            self.assertEqual(0, outra.returncode)


if __name__ == "__main__":
    unittest.main()
