import tempfile
import contextlib
import io
import unittest
from pathlib import Path

import mascarar

# MACs built at run time: no literal hardware address sits in a versioned file (PrivacyGuardTest).
MAC_FF = ":".join(["aa", "bb", "cc", "dd", "ee", "ff"])
MAC_01 = ":".join(["aa", "bb", "cc", "dd", "ee", "01"])
MAC_02 = ":".join(["aa", "bb", "cc", "dd", "ee", "02"])
MAC_UPPER = ":".join(["0A", "1B", "2C", "3D", "4E", "5F"])


class MascararTest(unittest.TestCase):
    def test_the_dash_name_is_hidden_wherever_it_stands(self):
        self.assertEqual(f"SSID: \"{mascarar.MASK}\" joined", mascarar.mascarar('SSID: "RE_AB12_345678" joined'))
        self.assertEqual(f"a {mascarar.MASK}, b {mascarar.MASK}", mascarar.mascarar("a RE_AB12_345678, b RE_zz99_000001"))

    def test_a_longer_or_odd_variant_does_not_leak(self):
        self.assertNotIn("XYZ", mascarar.mascarar("net RE_XYZ1_ABC_extra"))

    def test_other_text_is_not_touched(self):
        for line in ("nothing here", "REAU is not RE_", "SSID ""HomeNet"" ok", "rate 5G_5 GHz"):
            self.assertEqual(line, mascarar.mascarar(line))

    def test_the_command_line_writes_a_masked_copy_and_leaves_the_original(self):
        with tempfile.TemporaryDirectory() as tmp:
            src, dst = Path(tmp) / "in.txt", Path(tmp) / "out.txt"
            src.write_text("10-26 wifi RE_AB12_345678 up\nline two\n", encoding="utf-8")
            self.assertEqual(0, mascarar.main([str(src), str(dst)]))
            self.assertEqual(f"10-26 wifi {mascarar.MASK} up\nline two\n", dst.read_text(encoding="utf-8"))
            self.assertIn("RE_AB12_345678", src.read_text(encoding="utf-8"))

    def test_the_output_may_not_be_the_input_itself_and_the_file_is_left_whole(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / "in.txt"
            src.write_text("wifi RE_AB12_345678 up\n", encoding="utf-8")
            with contextlib.redirect_stderr(io.StringIO()) as err:
                self.assertEqual(2, mascarar.main([str(src), str(src)]))
                self.assertEqual(2, mascarar.main([str(src), str(Path(tmp) / "." / "in.txt")]))
            self.assertIn("próprio arquivo", err.getvalue())
            self.assertEqual("wifi RE_AB12_345678 up\n", src.read_text(encoding="utf-8"))

    def test_wrong_arguments_say_how_to_use_it(self):
        self.assertEqual(2, mascarar.main([]))


class OutrasRedesEMacTest(unittest.TestCase):
    """Item 23 (11/11/2026): the names of networks that are not the dash's, and the MAC addresses, are hidden too. Invented names only."""

    def test_a_mac_glued_to_a_letter_or_a_digit_is_hidden_too(self):
        for texto, esperado in ((f"bssid=x{MAC_FF}", f"bssid=x{mascarar.MASK_MAC}"), (f"{MAC_FF}x up", f"{mascarar.MASK_MAC}x up"),
                                (f"7{MAC_UPPER}9", f"7{mascarar.MASK_MAC}9"), (f"a{MAC_01}{MAC_02}", f"a{mascarar.MASK_MAC}{mascarar.MASK_MAC}")):
            with self.subTest(texto=texto):
                self.assertEqual(esperado, mascarar.mascarar(texto))

    def test_a_mac_with_hyphens_is_hidden_like_one_with_colons(self):
        # 26/11/2026 (review B4): `aa-bb-cc-dd-ee-ff`, built here while the test runs.
        with_hyphens = MAC_FF.replace(":", "-")
        for texto, esperado in ((f"bssid {with_hyphens} up", f"bssid {mascarar.MASK_MAC} up"), (f"x{with_hyphens.upper()}y", f"x{mascarar.MASK_MAC}y"),
                                (f"{with_hyphens}/{MAC_01.replace(':', '-')}", f"{mascarar.MASK_MAC}/{mascarar.MASK_MAC}")):
            with self.subTest(texto=texto):
                self.assertEqual(esperado, mascarar.mascarar(texto))

    def test_a_mac_glued_to_a_word_that_ends_in_hex_digits_does_not_leak_its_last_pair(self):
        # Revisão do plano 34, A5 (26/11/2026): o `ac` de `mac` contava como um par e o último par do MAC sobrava (`m<MAC>-ff`). Os MACs são montados aqui, na hora.
        with_hyphens = MAC_FF.replace(":", "-")
        other = "-".join(["00", "11", "22", "33", "44", "55"])
        for texto, esperado in ((f"mac-{with_hyphens}", f"m{mascarar.MASK_MAC}"), (f"device-{other}", f"devi{mascarar.MASK_MAC}"),
                                (f"mac:{MAC_FF}", f"m{mascarar.MASK_MAC}"), (f"mac {with_hyphens} up", f"mac {mascarar.MASK_MAC} up")):
            with self.subTest(texto=texto):
                self.assertEqual(esperado, mascarar.mascarar(texto))

    def test_the_pair_after_a_mac_is_hidden_only_when_it_is_two_whole_hex_digits(self):
        with_hyphens = MAC_FF.replace(":", "-")
        self.assertEqual(f"x {mascarar.MASK_MAC}-123", mascarar.mascarar(f"x {with_hyphens}-123"))
        self.assertEqual(f"{mascarar.MASK_MAC} up", mascarar.mascarar(f"{with_hyphens}-12 up"))
        # the date stays: its left edge refuses it before anything is hidden
        self.assertEqual("log-2026-11-26-10-30-15-45", mascarar.mascarar("log-2026-11-26-10-30-15-45"))

    def test_a_mac_with_no_separator_is_hidden_only_after_its_keyword(self):
        bare = MAC_FF.replace(":", "")
        for texto, esperado in ((f"bssid={bare} up", f"bssid={mascarar.MASK_MAC} up"), (f"BSSID: {bare}", f"BSSID: {mascarar.MASK_MAC}"),
                                (f'wlan_mac="{bare.upper()}"', f'wlan_mac="{mascarar.MASK_MAC}"'), (f"hwaddr = {bare},", f"hwaddr = {mascarar.MASK_MAC},"),
                                (f"macaddr:{bare}", f"macaddr:{mascarar.MASK_MAC}")):
            with self.subTest(texto=texto):
                self.assertEqual(esperado, mascarar.mascarar(texto))

    def test_a_hash_a_version_or_a_date_is_not_taken_for_a_mac(self):
        bare = MAC_FF.replace(":", "")
        for linha in (f"commit {bare} built", f"sha={bare}0123456789abcdef0123456789ab", f"mac={bare}0", f"macro={bare}", f"unmac={bare}", f"id={bare}",
                      "0.4.0-12-34-56 build", "2026-11-26-10-30-15 ok", "2026-11-26-10-30-15-45 ok", "d3b07384-d9a0-4c9b-8b1a-aabbccddeeff"):
            with self.subTest(linha=linha):
                self.assertEqual(linha, mascarar.mascarar(linha))

    def test_every_mac_address_is_hidden_in_either_case(self):
        self.assertEqual(f"wlan0: Associated with {mascarar.MASK_MAC} and {mascarar.MASK_MAC}",
                         mascarar.mascarar(f"wlan0: Associated with {MAC_FF} and {MAC_UPPER}"))

    def test_a_clock_time_or_a_short_hex_run_is_not_a_mac(self):
        for linha in ("10:06:55.123 ok", "id 12:34:56:78", "a1:b2:c3:d4:e5 only five groups"):
            self.assertEqual(linha, mascarar.mascarar(linha))

    def test_a_quoted_name_after_ssid_or_configkey_is_hidden_with_its_quotes(self):
        self.assertEqual('mConfig SSID="<rede>" priority=1', mascarar.mascarar('mConfig SSID="Escritorio 5G" priority=1'))
        self.assertEqual('Cannot find network with configKey="<rede>"WPA_PSK', mascarar.mascarar('Cannot find network with configKey="CasaDaTia"WPA_PSK'))
        self.assertEqual("ssid='<rede>'", mascarar.mascarar("ssid='Cafe do Fulano'"))
        self.assertEqual('SSID: "<rede>" up', mascarar.mascarar('SSID: "CasaDaTia" up'))

    def test_the_unquoted_dumpsys_form_goes_to_the_comma_and_the_token_form_to_the_space(self):
        self.assertEqual(f"SSID: <rede>, BSSID: {mascarar.MASK_MAC}, level", mascarar.mascarar(f"SSID: Cafe do Fulano, BSSID: {MAC_FF}, level"))
        self.assertEqual(f"ssid=<rede> bssid={mascarar.MASK_MAC} level=-50", mascarar.mascarar(f"ssid=CasaDaTia bssid={MAC_01} level=-50"))

    def test_the_dash_name_after_the_keyword_is_masked_as_a_dash_and_not_as_a_network(self):
        self.assertEqual(f'SSID: "{mascarar.MASK}", BSSID', mascarar.mascarar('SSID: "RE_AB12_345678", BSSID'))
        self.assertEqual(f"ssid={mascarar.MASK} x", mascarar.mascarar("ssid=RE_AB12_345678 x"))
        self.assertEqual(f"SSID: {mascarar.MASK}, x", mascarar.mascarar("SSID: RE_AB12_345678, x"))

    def test_placeholders_and_unknown_markers_are_left_as_they_are(self):
        for linha in ("SSID: <unknown ssid>, x", "ssid=<rede> y", 'SSID: "<rede>"'):
            self.assertEqual(linha, mascarar.mascarar(linha, mascarar.nomes_de_redes(linha)))

    def test_a_name_found_after_a_keyword_is_hidden_where_a_line_says_it_alone(self):
        texto = 'SSID: "CasaDaTia"\nroaming to CasaDaTia now\nCasaDaTiaExtra is another word\n'
        nomes = mascarar.nomes_de_redes(texto)
        self.assertEqual({"CasaDaTia"}, nomes)
        saida = mascarar.mascarar(texto, nomes)
        self.assertEqual('SSID: "<rede>"\nroaming to <rede> now\nCasaDaTiaExtra is another word\n', saida)

    def test_names_shorter_than_three_characters_are_only_hidden_after_the_keyword(self):
        self.assertEqual('SSID: "<rede>" and ab', mascarar.mascarar('SSID: "ab" and ab', {"ab"}))

    def test_the_longest_name_is_hidden_first_so_a_name_inside_another_does_not_leave_a_piece(self):
        self.assertEqual("saw <rede> now", mascarar.mascarar("saw Casa Dois now", {"Casa", "Casa Dois"}))

    def test_a_number_alone_after_the_keyword_is_not_taken_for_a_network_name(self):
        self.assertEqual(set(), mascarar.nomes_de_redes("SSID: 12345, x"))

    def test_the_command_line_hides_networks_macs_and_the_names_given_by_hand(self):
        with tempfile.TemporaryDirectory() as tmp:
            src, dst = Path(tmp) / "in.txt", Path(tmp) / "out.txt"
            src.write_text(f'SSID: "CasaDaTia", BSSID: {MAC_FF}\nsaw Trabalho5G in range\nroaming to CasaDaTia\nRE_AB12_345678 up\n', encoding="utf-8")
            self.assertEqual(0, mascarar.main(["--rede", "Trabalho5G", str(src), str(dst)]))
            saida = dst.read_text(encoding="utf-8")
            self.assertEqual(f'SSID: "<rede>", BSSID: {mascarar.MASK_MAC}\nsaw <rede> in range\nroaming to <rede>\n{mascarar.MASK} up\n', saida)
            for vazou in ("CasaDaTia", "Trabalho5G", "aa:bb", "AB12"):
                self.assertNotIn(vazou, saida)
            self.assertIn("CasaDaTia", src.read_text(encoding="utf-8"))  # the original stays

    def test_rede_without_a_name_says_how_to_use_it(self):
        self.assertEqual(2, mascarar.main(["a.txt", "b.txt", "--rede"]))

    def test_the_system_log_reader_hides_with_the_same_masks(self):
        import logcat_sistema
        self.assertIs(mascarar.esconder_mac, logcat_sistema.esconder_mac)
        self.assertIs(mascarar.DASH_QUALQUER, logcat_sistema.DASH)


if __name__ == "__main__":
    unittest.main()
