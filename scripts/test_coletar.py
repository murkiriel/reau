"""Tests for scripts/coletar_do_celular.sh: the collection script only READS from the phone.

A fake `adb` (an executable on the PATH that writes its arguments to a file) stands for the phone; the script is run against it and
every command it sent is checked. Run from the repo root:  python3 -I -m unittest discover -s scripts -p "test_coletar.py" -v
"""
import os
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "coletar_do_celular.sh"

# What the script may ask of the phone, as the words that start a command after `-s SERIAL` (or `adb` alone).
READ_ONLY = ("devices", "shell getprop", "shell dumpsys", "shell cmd appops get", "shell am get-standby-bucket", "shell settings get",
             "logcat", "exec-out run-as")
FORBIDDEN = ("battery set", "battery reset", "whitelist +", "whitelist -", "logcat -c", "logcat --clear", " -G ", "install", "uninstall", " rm ", "push", "pull", "kill-server", "settings put", " pm ", "shell pm", "reboot", "am force-stop",
             "am start", "appops set", "cmd appops set", "disable", "clear", "tcpip", "root", "remount")


def fake_adb(directory: Path, devices: list[str]) -> Path:
    log = directory / "adb.log"
    listing = "".join(f"{d}\tdevice\n" for d in devices)
    adb = directory / "adb"
    adb.write_text(
        "#!/usr/bin/env bash\n"
        f'echo "$*" >> "{log}"\n'
        f'if [ "$1" = devices ]; then printf "List of devices attached\\n{listing}"; exit 0; fi\n'
        "echo fake\n",
        encoding="utf-8",
    )
    adb.chmod(adb.stat().st_mode | stat.S_IXUSR)
    return log


def run(devices, *args):
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        log = fake_adb(tmp, devices)
        out = tmp / "saida"
        env = {**os.environ, "ADB": str(tmp / "adb"), "REAU_SAIDA": str(out)}
        done = subprocess.run(["bash", str(SCRIPT), *args], env=env, capture_output=True, text=True)
        commands = log.read_text(encoding="utf-8").splitlines() if log.exists() else []
        files = sorted(p.name for p in out.glob("*")) if out.exists() else []
        return done, commands, files


class MaskedCopyTest(unittest.TestCase):
    def test_a_copy_of_the_system_log_without_the_dash_name_is_made_next_to_the_original(self):
        done, commands, files = run(["ABC123"])
        self.assertEqual(0, done.returncode, done.stderr)
        self.assertIn("logcat-sistema.txt", files)
        self.assertIn("logcat-sistema.mascarado.txt", files)


class ReadOnlyTest(unittest.TestCase):
    def test_every_command_sent_to_the_phone_is_a_read(self):
        done, commands, files = run(["ABC123"])
        self.assertEqual(0, done.returncode, done.stderr)
        self.assertTrue(commands)
        for command in commands:
            rest = command.removeprefix("-s ABC123 ")
            self.assertTrue(rest.startswith(READ_ONLY), command)
            padded = f" {command} "
            for bad in FORBIDDEN:
                self.assertNotIn(bad, padded, command)

    def test_dumpsys_and_logcat_are_only_the_reads_the_script_needs(self):
        done, commands, _ = run(["ABC123"])
        self.assertEqual(0, done.returncode, done.stderr)
        for command in commands:
            rest = command.removeprefix("-s ABC123 ")
            if rest.startswith("shell dumpsys"):
                self.assertRegex(rest, r"^shell dumpsys (package \S+|deviceidle whitelist|power|battery)$", command)
            if rest.startswith("logcat"):
                self.assertRegex(rest, r"^logcat -b (all|crash) -d -v threadtime$", command)

    def test_a_folder_that_is_missing_does_not_stop_the_others_and_the_warning_names_the_recordings(self):
        done, _, _ = run(["ABC123"])
        self.assertIn("sessions/", done.stdout)
        self.assertIn("video/", done.stdout)
        script = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("for pasta in diag sessions video", script)  # one folder per tar: the missing one costs only itself
        self.assertIn("dumpsys battery | head -14 || true", script)

    def test_it_writes_the_info_and_the_logcats_and_warns_about_personal_data(self):
        done, _, files = run(["ABC123"], "ABC123")
        self.assertEqual(0, done.returncode, done.stderr)
        self.assertEqual(["INFO.txt", "logcat-crash.txt", "logcat-sistema.mascarado.txt", "logcat-sistema.txt"], files)
        self.assertIn("dado pessoal", done.stdout)

    def test_it_refuses_with_no_device_or_with_two_and_none_of_them_is_asked_anything_else(self):
        done, commands, _ = run([])
        self.assertEqual(2, done.returncode)
        self.assertEqual(["devices"], commands)
        done, commands, _ = run(["AAA", "BBB"])
        self.assertEqual(2, done.returncode)
        self.assertIn("mais de um aparelho", done.stderr)
        self.assertEqual(["devices"], commands)

    def test_a_serial_that_is_not_connected_is_refused(self):
        done, commands, _ = run(["AAA"], "ZZZ")
        self.assertEqual(2, done.returncode)
        self.assertEqual(["devices"], commands)

    def test_with_two_devices_the_one_named_is_the_only_one_asked(self):
        done, commands, _ = run(["AAA", "BBB"], "BBB")
        self.assertEqual(0, done.returncode, done.stderr)
        self.assertTrue(all(c == "devices" or c.startswith("-s BBB ") for c in commands), commands)


if __name__ == "__main__":
    unittest.main()
