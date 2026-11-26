"""The masking made fast (one regex for all the names of a file) and the dash's name hidden glued to other text (`%22RE_…%22` in an address).

The same table of cases, `CASOS`, is in `LogMaskTest` (Kotlin): the script and the app must hide exactly the same. Only invented names are used; the dash's name
and the MACs are put together while the test runs, so no real-looking one is written in the source. The older tests (test_mascarar.py) are not touched."""
import re
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

import mascarar

DASH = "RE_" + "AB12" + "_" + "345678"
M = mascarar.MASK
MAC_FF = ":".join(["aa", "bb", "cc", "dd", "ee", "ff"])

# (line, what the line becomes). The left edge of "any RE_ name" is the start of a word, a non-word character or a `%XX` escape; the dash's own pattern has no edge.
CASOS = [
    # the dash's name, glued to anything or escaped in an address
    (f"url=%22{DASH}%22", f"url=%22{M}%22"),
    (f"x{DASH}", f"x{M}"),
    (f"{DASH}y", M),  # a name that goes on after the digits is the "odd variant": all of it goes
    (f"x{DASH}y", f"x{M}y"),
    (f"MODE_{DASH}", f"MODE_{M}"),
    (f"a {DASH}, b ({DASH})", f"a {M}, b ({M})"),
    # any other name that starts with RE_: after a non-word character, the start of the line or a URL escape
    ("RE_ABCD up", f"{M} up"),
    ("(RE_ABCDEF)", f"({M})"),
    ("name=RE_ABCDEF", f"name={M}"),
    ("%22RE_ABCDEF%22", f"%22{M}%22"),
    ("%2fRE_ABCDEF", f"%2f{M}"),
    # an ordinary identifier that holds RE_ in the middle stays
    ("MODE_RE_ABCD", "MODE_RE_ABCD"),
    ("PURE_REF", "PURE_REF"),
    ("PURE_RE_ABCD", "PURE_RE_ABCD"),
    ("_RE_ABCD", "_RE_ABCD"),
    ("xRE_ABCD", "xRE_ABCD"),
    ("%2RE_ABCD", "%2RE_ABCD"),
    ("%zzRE_ABCD", "%zzRE_ABCD"),
    ("REAU is not RE_", "REAU is not RE_"),
    # a common network name and a MAC
    ('SSID: "CasaDoZe" up', 'SSID: "<rede>" up'),
    (f"bssid {MAC_FF} ok", f"bssid {mascarar.MASK_MAC} ok"),
    # a MAC glued to a letter or a digit, with hyphens, and with no separator after its keyword; a date, a hash and a longer run stay (26/11/2026)
    (f"bssid=x{MAC_FF}", f"bssid=x{mascarar.MASK_MAC}"),
    (f"{MAC_FF}x up", f"{mascarar.MASK_MAC}x up"),
    (f"bssid {MAC_FF.replace(':', '-')} up", f"bssid {mascarar.MASK_MAC} up"),
    (f"BSSID: {MAC_FF.replace(':', '')}", f"BSSID: {mascarar.MASK_MAC}"),
    (f'wlan_mac="{MAC_FF.replace(":", "").upper()}"', f'wlan_mac="{mascarar.MASK_MAC}"'),
    (f"commit {MAC_FF.replace(':', '')} built", f"commit {MAC_FF.replace(':', '')} built"),
    (f"macro={MAC_FF.replace(':', '')}", f"macro={MAC_FF.replace(':', '')}"),
    (f"mac={MAC_FF.replace(':', '')}0", f"mac={MAC_FF.replace(':', '')}0"),
    ("2026-11-26-10-30-15 ok", "2026-11-26-10-30-15 ok"),
    ("2026-11-26-10-30-15-45 ok", "2026-11-26-10-30-15-45 ok"),
]


def _de_hoje(texto, nomes=()):
    """The masking as it was before this work (the `\\b` of the dash's patterns and one `re.sub` per name), for the lines that already worked."""
    texto = re.sub(r"\bRE_[A-Za-z0-9_]{4,}", M, re.sub(r"\bRE_[A-Za-z0-9]{4}_\d{6}\b", M, texto))
    texto = mascarar.MAC.sub(mascarar.MASK_MAC, texto)

    def entre_aspas(m):
        return m.group(0) if mascarar._e_do_painel(m.group(4)) or m.group(4) in ("", "<rede>") else f"{m.group(1)}{m.group(2)}{m.group(3)}<rede>{m.group(3)}"

    texto = mascarar._ENTRE_ASPAS.sub(entre_aspas, texto)
    texto = mascarar._COM_VIRGULA.sub(lambda m: m.group(0) if mascarar._e_do_painel(m.group(2).strip()) else f"SSID{m.group(1)}<rede>", texto)
    texto = mascarar._UM_TOKEN.sub(lambda m: m.group(0) if mascarar._e_do_painel(m.group(2)) else "ssid=<rede>", texto)
    for nome in sorted((n for n in nomes if len(n) >= 3), key=len, reverse=True):
        texto = re.sub(rf"(?<![\w]){re.escape(nome)}(?![\w])", "<rede>", texto)
    return texto


def _nomes_inventados(quantos):
    """Invented names, a quarter of them with others inside them: `Casa7`, `Casa7 Fundos`, `Casa7 Fundos 5G`, `Sala7`."""
    nomes = []
    for i in range(quantos // 4):
        nomes += [f"Casa{i}", f"Casa{i} Fundos", f"Casa{i} Fundos 5G", f"Sala{i}"]
    return nomes


class PainelColadoTest(unittest.TestCase):
    def test_the_table_of_cases(self):
        for entrada, esperado in CASOS:
            with self.subTest(entrada=entrada):
                self.assertEqual(esperado, mascarar.mascarar(entrada, mascarar.nomes_de_redes(entrada)))

    def test_the_dash_name_glued_to_text_does_not_leak_through_the_command_line(self):
        with tempfile.TemporaryDirectory() as tmp:
            src, dst = Path(tmp) / "in.txt", Path(tmp) / "out.txt"
            src.write_text(f"GET /x?n=%22{DASH}%22&m=1\nxx{DASH}yy\n", encoding="utf-8")
            self.assertEqual(0, mascarar.main([str(src), str(dst)]))
            saida = dst.read_text(encoding="utf-8")
            self.assertEqual(f"GET /x?n=%22{M}%22&m=1\nxx{M}yy\n", saida)
            self.assertNotIn("AB12", saida)


class NomesNumaRegexSoTest(unittest.TestCase):
    def test_a_thousand_invented_names_with_names_inside_others_are_all_hidden_and_none_eats_a_piece_of_another(self):
        nomes = _nomes_inventados(1000)
        self.assertEqual(1000, len(set(nomes)))
        linhas = [f"saw {n} now and {n}." for n in nomes] + ["Casa7 Fundos 5G, Casa7 Fundos, Casa7, Sala7", "Casa7Fundos Casa7x Sala"]
        saida = [mascarar.mascarar(linha, nomes) for linha in linhas]
        self.assertEqual(["saw <rede> now and <rede>."] * 1000, saida[:1000])
        self.assertEqual("<rede>, <rede>, <rede>, <rede>", saida[1000])
        self.assertEqual("Casa7Fundos Casa7x Sala", saida[1001])  # whole words only: a bigger word is not touched

    def test_the_compiled_regex_gives_the_same_as_the_names_and_the_names_longest_first_in_it(self):
        nomes = _nomes_inventados(40)
        rex = mascarar.compilar_nomes(nomes)
        self.assertIsNotNone(rex)
        linha = "Casa3 Fundos 5G and Casa3 Fundos and Casa3 and Sala3"
        self.assertEqual("<rede> and <rede> and <rede> and <rede>", mascarar.mascarar(linha, rex))
        self.assertEqual(mascarar.mascarar(linha, nomes), mascarar.mascarar(linha, rex))
        self.assertIsNone(mascarar.compilar_nomes(["ab", "x", ""]))  # shorter than the minimum: nothing to hide loose

    def test_the_output_is_the_one_of_today_in_a_sample_file_where_it_already_worked(self):
        linhas = []
        for i in range(15):
            linhas += [
                f'WifiConfigManager: mConfig SSID="Casa{i} Fundos" priority=1',
                f"WifiInfo SSID: Sala{i}, BSSID: {MAC_FF}, RSSI: -4{i % 10}",
                f"ssid=Casa{i} bssid={MAC_FF} level=-50",
                f"roaming from Casa{i} Fundos 5G to Casa{i} (Sala{i}).",
                f"Casa{i}Extra and Sala{i}s are other words, Casa{i}x",
                f"connected to {DASH} at {MAC_FF}",
                f"odd RE_XYZW_{i}7 and (RE_ABCDEFG{i})",
                f"MODE_RE_ABCD{i} PURE_REF nothing here {i}",
                'configKey="Cafe Central"WPA_PSK SSID: <unknown ssid>, x',
            ]
        with tempfile.TemporaryDirectory() as tmp:
            src, dst = Path(tmp) / "in.txt", Path(tmp) / "out.txt"
            src.write_text("\n".join(linhas) + "\n", encoding="utf-8")
            self.assertEqual(0, mascarar.main(["--rede", "Cafe Central", str(src), str(dst)]))
            achados = {"Cafe Central"}
            for linha in linhas:
                achados |= mascarar.nomes_de_redes(linha)
            esperado = "".join(_de_hoje(linha + "\n", achados) for linha in linhas)
            self.assertEqual(esperado, dst.read_text(encoding="utf-8"))
        self.assertIn("<rede>", esperado)

    def test_twenty_thousand_lines_and_eight_hundred_names_take_less_than_ten_seconds(self):
        nomes = _nomes_inventados(800)
        with tempfile.TemporaryDirectory() as tmp:
            src, dst = Path(tmp) / "in.txt", Path(tmp) / "out.txt"
            # The names are learned from the file itself (after a keyword), as the collection does; the rest of the lines are logcat-like text.
            cabeca = [f'SSID: "{n}"' for n in nomes]
            corpo = [f"11-10 19:41:{i % 60:02d}.123  1234  1250 I WifiService: roaming to {nomes[i % 800]} level={i} bssid {MAC_FF} url=%22{DASH}%22" for i in range(20_000 - len(cabeca))]
            src.write_text("\n".join(cabeca + corpo) + "\n", encoding="utf-8")
            inicio = time.monotonic()
            # A child process with a limit: a script that goes back to one pattern per name would take minutes, and the test must fail, not wait for it.
            try:
                feito = subprocess.run([sys.executable, "-B", mascarar.__file__, str(src), str(dst)], timeout=10, capture_output=True, check=False)
            except subprocess.TimeoutExpired:
                self.fail("20 000 lines with 800 names took more than 10 s")
            self.assertEqual(0, feito.returncode, feito.stderr)
            self.assertLess(time.monotonic() - inicio, 10)
            saida = dst.read_text(encoding="utf-8")
            self.assertEqual(20_000, saida.count("\n"))
            for vazou in (nomes[0], nomes[799], "AB12", MAC_FF):
                self.assertNotIn(vazou, saida)


if __name__ == "__main__":
    unittest.main()
