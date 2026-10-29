"""Tests for scripts/emu/run_instrumented.py: which instrumented classes each edition runs, and what it installs.

The full edition runs the shared tests and the ones in src/androidTestCompleta (those that drive the accessibility service);
the lite edition, another application id, only the shared ones.

Run from the repo root:  python -B -m unittest discover -s scripts/emu -p "test_run_instrumented.py" -v
"""
import importlib
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)


def load(edition):
    os.environ["REAU_EMU_SERIAL"] = "emulator-5580"
    if edition:
        os.environ["REAU_EDITION"] = edition
    else:
        os.environ.pop("REAU_EDITION", None)
    import run_instrumented
    return importlib.reload(run_instrumented)


class EditionTest(unittest.TestCase):
    def tearDown(self):
        os.environ.pop("REAU_EDITION", None)

    def test_the_full_edition_is_the_default_and_runs_the_accessibility_tests_too(self):
        run = load(None)
        self.assertEqual("com.reau.app", run.APP_ID)
        self.assertEqual("com.reau.app.test/com.reau.app.KeepAppStateRunner", run.RUNNER)
        self.assertIn("com.reau.app.service.AaServerAccessibilityTest", run.all_classes())
        self.assertEqual("com.reau.app.service.AaServerAccessibilityTest", run.qualified("AaServerAccessibilityTest"))

    def test_the_lite_edition_has_its_own_application_id_apks_and_leaves_the_accessibility_tests_out(self):
        run = load("lite")
        self.assertEqual("com.reau.app.lite", run.APP_ID)
        self.assertEqual("com.reau.app.lite.test/com.reau.app.KeepAppStateRunner", run.RUNNER)
        self.assertTrue(all("-lite-" in apk for apk in run.APKS), run.APKS)
        self.assertNotIn("com.reau.app.service.AaServerAccessibilityTest", run.all_classes())
        with self.assertRaises(SystemExit):
            run.qualified("AaServerAccessibilityTest")

    def test_an_unknown_edition_is_refused(self):
        os.environ["REAU_EDITION"] = "gold"
        with self.assertRaises(SystemExit):
            load("gold")


class OneProcessPerClassTest(unittest.TestCase):
    """The whole suite in a single process was retired (28/10/2026): one process per class is the only way."""

    def test_the_one_process_option_is_refused_before_any_adb_call(self):
        run = load(None)
        old = sys.argv
        sys.argv = ["run_instrumented.py", "--one-process"]
        try:
            with self.assertRaises(SystemExit) as caught:
                run.main()
        finally:
            sys.argv = old
        self.assertIn("--one-process", str(caught.exception))
        self.assertIn("uma classe por processo", str(caught.exception))

    def test_the_docs_of_the_script_do_not_offer_the_mode(self):
        self.assertNotIn("--one-process", load(None).__doc__)


if __name__ == "__main__":
    unittest.main()
