"""Tests of the mutation tooling itself: the list is well formed and its snippets are still in the code (no Gradle needed), the slices
split it exactly, and a file left mutated by a killed run is put back."""
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mutacoes  # noqa: E402
import mutate  # noqa: E402


class ListaTest(unittest.TestCase):
    def test_every_entry_has_five_parts_and_a_unique_name(self):
        names = [m[0] for m in mutacoes.MUTATIONS]
        for m in mutacoes.MUTATIONS:
            self.assertEqual(5, len(m), m[0])
            self.assertTrue(m[4] and all(isinstance(c, str) for c in m[4]), f"{m[0]}: needs the test classes")
            self.assertNotEqual(m[2], m[3], f"{m[0]}: the mutation changes nothing")
        self.assertEqual([], sorted({n for n in names if names.count(n) > 1}), "names must be unique (the filter picks by name)")

    def test_every_snippet_is_in_its_file_exactly_once(self):
        stale = []
        for name, rel, old, _new, _classes in mutacoes.MUTATIONS:
            text = (mutate.ROOT / rel).read_text(encoding="utf-8")
            count = max(text.count(old), text.count(old.replace("\n", "\r\n")) if "\n" in old else 0)
            if count != 1:
                stale.append(f"{name}: {count}x in {rel}")
        self.assertEqual([], stale, "update the snippet after a refactor")


class FatiaTest(unittest.TestCase):
    def test_slices_cover_the_list_once(self):
        items = list(range(23))
        for total in (1, 2, 5, 6, 23, 30):
            parts = [mutacoes.fatia(items, i, total) for i in range(1, total + 1)]
            self.assertEqual(items, sorted(x for p in parts for x in p), total)
            self.assertLessEqual(max(map(len, parts)) - min(map(len, parts)), 1, total)

    def test_the_command_line(self):
        self.assertEqual(((2, 6), True, ["Once"]), mutacoes.parse(["--fatia", "2/6", "--lista", "Once"]))
        self.assertEqual((None, False, []), mutacoes.parse([]))
        with self.assertRaises(SystemExit):
            mutacoes.parse(["--fatia", "7/6"])


class RecoverTest(unittest.TestCase):
    def test_a_file_left_mutated_is_put_back_and_the_trace_goes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root, pending = Path(tmp), Path(tmp) / "build" / "pendentes"
            pending.mkdir(parents=True)
            target = root / "src" / "A.kt"
            target.parent.mkdir()
            target.write_text("mutated", encoding="utf-8")
            (pending / "src__A.kt.orig").write_bytes(b"original")
            (pending / "src__A.kt.path").write_text("src/A.kt", encoding="utf-8")
            self.assertEqual(1, mutate.recover(root, pending))
            self.assertEqual("original", target.read_text(encoding="utf-8"))
            self.assertEqual([], list(pending.iterdir()))
            self.assertEqual(0, mutate.recover(root, pending), "nothing left to restore")

    def test_a_trace_cut_short_restores_nothing_and_does_not_touch_the_root(self):
        with tempfile.TemporaryDirectory() as tmp:
            root, pending = Path(tmp), Path(tmp) / "build" / "pendentes"
            pending.mkdir(parents=True)
            (root / "keep.txt").write_text("intact", encoding="utf-8")
            (pending / "a.orig").write_bytes(b"")   # the run died writing the backup
            (pending / "a.path").write_text("", encoding="utf-8")
            self.assertEqual(0, mutate.recover(root, pending))
            self.assertEqual("intact", (root / "keep.txt").read_text(encoding="utf-8"))
            self.assertEqual([], list(pending.iterdir()))

    def test_no_pending_folder_is_nothing_to_do(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(0, mutate.recover(Path(tmp), Path(tmp) / "absent"))


if __name__ == "__main__":
    unittest.main()
