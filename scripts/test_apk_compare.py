"""Tests for scripts/apk_compare.py: two APKs are the same when they differ only in the signing block; a date, a content or an
order that differs is found, and named.

The APKs here are small synthetic zips with a signing block put in by hand (the layout of the APK Signature Scheme v2: the size, the
pairs, the size again and the 16-byte magic, between the entries and the central directory).

Run from the repo root:  python -m unittest discover -s scripts -p "test_apk_compare.py" -v
"""
import io
import os
import struct
import sys
import tempfile
import unittest
import zipfile
from contextlib import redirect_stderr, redirect_stdout

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import apk_compare  # noqa: E402


def make_zip(entries: list[tuple[str, bytes, tuple]]) -> bytes:
    """A zip of (name, content, date_time) entries, in that order, stored (as the entries of an APK that is aligned)."""
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_STORED) as z:
        for name, content, date_time in entries:
            z.writestr(zipfile.ZipInfo(name, date_time), content)
    return buffer.getvalue()


def sign(apk: bytes, payload: bytes) -> bytes:
    """[apk] with a signing block of [payload] between the entries and the central directory (the end of the zip says where the directory is now)."""
    eocd = apk.rfind(b"PK\x05\x06")
    cd_offset = struct.unpack_from("<I", apk, eocd + 16)[0]
    pair = struct.pack("<QI", len(payload) + 4, 0x7109871A) + payload
    size = len(pair) + 8 + 16
    block = struct.pack("<Q", size) + pair + struct.pack("<Q", size) + apk_compare.SIG_BLOCK_MAGIC
    tail = bytearray(apk[cd_offset:])
    struct.pack_into("<I", tail, eocd - cd_offset + 16, cd_offset + len(block))
    return apk[:cd_offset] + block + bytes(tail)


JAN = (1981, 1, 1, 1, 1, 0)
FEB = (1981, 2, 1, 1, 1, 0)
BASE = [("classes.dex", b"dex-bytes" * 50, JAN), ("AndroidManifest.xml", b"<manifest/>", JAN), ("META-INF/x.version", b"1.0", JAN)]


class ApkCompareTest(unittest.TestCase):
    def test_a_signing_block_is_found_and_taken_out(self):
        plain = make_zip(BASE)
        signed = sign(plain, b"signature-A" * 20)
        entries, block, tail = apk_compare.split(signed)
        self.assertTrue(block.endswith(apk_compare.SIG_BLOCK_MAGIC))
        self.assertEqual(plain[: len(entries)], entries)
        self.assertEqual(plain, apk_compare.without_signature(signed), "taken out, the APK is the zip it was before it was signed")
        self.assertEqual(plain, apk_compare.without_signature(plain), "an APK with no block is left as it is")
        self.assertEqual(b"", apk_compare.split(plain)[1])

    def test_two_signatures_of_the_same_apk_are_the_same_apk(self):
        plain = make_zip(BASE)
        a, b = sign(plain, b"salt-1" * 30), sign(plain, b"another salt, a longer one" * 30)
        self.assertNotEqual(a, b)
        self.assertEqual(apk_compare.sha256_without_signature(a), apk_compare.sha256_without_signature(b))
        self.assertEqual([], apk_compare.differences(a, b))

    def test_a_date_that_differs_is_found_and_the_entry_is_named(self):
        a = sign(make_zip(BASE), b"s")
        dated = [(n, c, FEB if n == "classes.dex" else d) for n, c, d in BASE]
        b = sign(make_zip(dated), b"s")
        self.assertNotEqual(apk_compare.sha256_without_signature(a), apk_compare.sha256_without_signature(b))
        found = apk_compare.differences(a, b)
        self.assertEqual(["data diferente: classes.dex"], found)

    def test_a_content_that_differs_is_found_and_the_entry_is_named(self):
        a = sign(make_zip(BASE), b"s")
        changed = [(n, c + b"!" if n == "AndroidManifest.xml" else c, d) for n, c, d in BASE]
        b = sign(make_zip(changed), b"s")
        self.assertIn("conteúdo diferente: AndroidManifest.xml", apk_compare.differences(a, b))

    def test_an_order_that_differs_and_an_entry_that_is_missing_are_found(self):
        a = sign(make_zip(BASE), b"s")
        found = apk_compare.differences(a, sign(make_zip(list(reversed(BASE))), b"s"))
        self.assertIn("a ordem das entradas é outra", found)
        found = apk_compare.differences(a, sign(make_zip(BASE[:2]), b"s"))
        self.assertIn("só no primeiro: META-INF/x.version", found)

    def test_what_is_not_an_apk_is_an_error_and_not_a_comparison(self):
        with self.assertRaises(apk_compare.NotAnApk):
            apk_compare.without_signature(b"this is no zip at all")
        torn = bytearray(sign(make_zip(BASE), b"s"))
        eocd = bytes(torn).rfind(b"PK\x05\x06")
        cd_offset = struct.unpack_from("<I", torn, eocd + 16)[0]
        struct.pack_into("<Q", torn, cd_offset - 24, 5)  # the second size no longer says what the first does
        with self.assertRaises(apk_compare.NotAnApk):
            apk_compare.split(bytes(torn))

    def run_main(self, first: bytes, second: bytes) -> tuple[int, str]:
        with tempfile.TemporaryDirectory() as folder:
            paths = []
            for name, content in (("a.apk", first), ("b.apk", second)):
                paths.append(os.path.join(folder, name))
                with open(paths[-1], "wb") as f:
                    f.write(content)
            out = io.StringIO()
            with redirect_stdout(out), redirect_stderr(io.StringIO()):
                code = apk_compare.main(paths)
            return code, out.getvalue()

    def test_the_command_answers_0_for_the_same_apk_and_1_for_another_and_says_why(self):
        plain = make_zip(BASE)
        code, said = self.run_main(sign(plain, b"one"), sign(plain, b"two, longer"))
        self.assertEqual(0, code)
        self.assertIn("iguais", said)
        dated = [(n, c, FEB if n == "classes.dex" else d) for n, c, d in BASE]
        code, said = self.run_main(sign(plain, b"one"), sign(make_zip(dated), b"one"))
        self.assertEqual(1, code)
        self.assertIn("DIFERENTES", said)
        self.assertIn("classes.dex", said)

    def test_the_command_answers_2_for_a_missing_file_and_for_a_file_that_is_no_apk(self):
        with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            self.assertEqual(2, apk_compare.main(["/no/such/a.apk", "/no/such/b.apk"]))
            self.assertEqual(2, apk_compare.main(["only-one"]))
        code, _ = self.run_main(b"not a zip", b"not a zip")
        self.assertEqual(2, code)


if __name__ == "__main__":
    unittest.main()
