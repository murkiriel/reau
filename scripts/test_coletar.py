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
             "logcat", "exec-out run-as", "shell ps -A -o PID,STAT,ETIME,NAME")
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


class SystemWifiTest(unittest.TestCase):
    """Plan 25: what the system says of the dash's Wi-Fi at a drop (an ordinary app cannot read it) is collected by adb, apart, in sistema/."""

    def test_the_wifi_and_connectivity_dumps_the_buffer_sizes_and_the_wifi_lines_come_in_a_folder_of_their_own(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            log = fake_adb(tmp, ["ABC123"])
            out = tmp / "saida"
            env = {**os.environ, "ADB": str(tmp / "adb"), "REAU_SAIDA": str(out)}
            done = subprocess.run(["bash", str(SCRIPT), "ABC123"], env=env, capture_output=True, text=True)
            self.assertEqual(0, done.returncode, done.stderr)
            names = sorted(p.name for p in (out / "sistema").glob("*"))
            self.assertEqual([
                "appops-reau.mascarado.txt", "appops-reau.txt", "dumpsys-connectivity.mascarado.txt", "dumpsys-connectivity.txt",
                "dumpsys-meminfo.txt", "dumpsys-wifi.mascarado.txt", "dumpsys-wifi.txt", "exit-info-gearhead.txt", "exit-info-reau.txt",
                "location-reau.mascarado.txt", "location-reau.txt", "logcat-events.mascarado.txt", "logcat-events.txt",
                "logcat-kernel.mascarado.txt", "logcat-kernel.txt", "logcat-tamanho-dos-buffers.txt", "wifi-linhas.mascarado.txt", "wifi-linhas.txt",
            ], names)
            commands = log.read_text(encoding="utf-8").splitlines()
            self.assertIn("-s ABC123 shell dumpsys wifi", commands)
            self.assertIn("-s ABC123 shell dumpsys connectivity", commands)
            self.assertIn("-s ABC123 logcat -g", commands)
        self.assertIn("sistema/", done.stdout)

    def test_the_wifi_lines_are_taken_from_the_logcat_of_every_buffer_and_the_dash_name_is_masked_in_the_copies(self):
        script = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("wpa_supplicant", script)
        self.assertIn("ConnectivityService", script)
        self.assertIn('for arquivo in dumpsys-wifi dumpsys-connectivity wifi-linhas appops-reau location-reau', script)
        self.assertIn("logcat -b all -d -v threadtime", script)  # every buffer, as before


class LocationAccessTest(unittest.TestCase):
    """Item 15a (iv), 08/11/2026: the app's location permissions and the system's lines about it come in sistema/, read only, filtered to the REau."""

    def test_the_appops_of_the_app_and_the_location_dump_are_asked_and_kept_apart(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            log = fake_adb(tmp, ["ABC123"])
            out = tmp / "saida"
            env = {**os.environ, "ADB": str(tmp / "adb"), "REAU_SAIDA": str(out)}
            done = subprocess.run(["bash", str(SCRIPT), "ABC123"], env=env, capture_output=True, text=True)
            self.assertEqual(0, done.returncode, done.stderr)
            commands = log.read_text(encoding="utf-8").splitlines()
            self.assertIn("-s ABC123 shell cmd appops get com.reau.app", commands)
            self.assertIn("-s ABC123 shell dumpsys location", commands)
            self.assertEqual("fake", (out / "sistema" / "appops-reau.txt").read_text(encoding="utf-8").strip())
            # the fake phone's answer ("fake") has no line about the REau: the filtered file is empty, and the script went on
            self.assertEqual("", (out / "sistema" / "location-reau.txt").read_text(encoding="utf-8"))

    def test_the_location_dump_is_filtered_to_the_lines_of_the_app_before_it_is_written(self):
        script = SCRIPT.read_text(encoding="utf-8")
        self.assertRegex(script, r'dumpsys location 2>&1 \| grep -i "com\\\.reau\\\.app" > "\$OUT/sistema/location-reau\.txt" \|\| true')
        self.assertNotIn("dumpsys-location", script)  # the unfiltered dump (every app's use of the location) is not kept

    def test_the_settings_that_make_android_refuse_a_socket_on_a_network_are_read_into_the_info(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            log = fake_adb(tmp, ["ABC123"])
            out = tmp / "saida"
            env = {**os.environ, "ADB": str(tmp / "adb"), "REAU_SAIDA": str(out)}
            done = subprocess.run(["bash", str(SCRIPT)], env=env, capture_output=True, text=True)
            self.assertEqual(0, done.returncode, done.stderr)
            commands = log.read_text(encoding="utf-8").splitlines()
            self.assertIn("-s ABC123 shell settings get global restricted_networking_mode", commands)
            self.assertIn("-s ABC123 shell settings get secure always_on_vpn_app", commands)
            self.assertIn("-s ABC123 shell settings get secure always_on_vpn_lockdown", commands)
            info = (out / "INFO.txt").read_text(encoding="utf-8")
            for name in ("restricted_networking_mode", "always_on_vpn_app", "always_on_vpn_lockdown"):
                self.assertIn(f"{name}=fake", info)

    def test_it_only_reads(self):
        script = SCRIPT.read_text(encoding="utf-8")
        for line in script.splitlines():
            if "appops" in line and not line.lstrip().startswith("#"):
                self.assertNotIn("appops set", line)
                self.assertNotIn("appops reset", line)


class MemoryAndDeathsTest(unittest.TestCase):
    """Plan 35, step A (25/11/2026): what the system says of the memory and of the deaths of the REau's process (the events and kernel buffers of the logcat,
    `dumpsys meminfo`, `dumpsys activity exit-info` of the REau and of Android Auto) is collected by adb, read only, in sistema/."""

    def test_the_events_and_kernel_buffers_the_memory_and_the_exit_info_of_both_apps_are_asked_and_kept_in_sistema(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            log = fake_adb(tmp, ["ABC123"])
            out = tmp / "saida"
            env = {**os.environ, "ADB": str(tmp / "adb"), "REAU_SAIDA": str(out)}
            done = subprocess.run(["bash", str(SCRIPT), "ABC123"], env=env, capture_output=True, text=True)
            self.assertEqual(0, done.returncode, done.stderr)
            commands = log.read_text(encoding="utf-8").splitlines()
            for asked in ("logcat -b events -d -v threadtime", "logcat -b kernel -d -v threadtime", "shell dumpsys meminfo",
                          "shell dumpsys activity exit-info com.reau.app", "shell dumpsys activity exit-info com.google.android.projection.gearhead"):
                self.assertIn(f"-s ABC123 {asked}", commands)
            for name in ("logcat-events", "logcat-kernel", "dumpsys-meminfo", "exit-info-reau", "exit-info-gearhead"):
                self.assertEqual("fake", (out / "sistema" / f"{name}.txt").read_text(encoding="utf-8").strip(), name)
            # the kernel and the events may carry the Wi-Fi name and the MAC: masked copies; the memory and the exit history speak only of processes
            self.assertTrue((out / "sistema" / "logcat-kernel.mascarado.txt").exists())
            self.assertTrue((out / "sistema" / "logcat-events.mascarado.txt").exists())
            self.assertFalse((out / "sistema" / "dumpsys-meminfo.mascarado.txt").exists())
            self.assertFalse((out / "sistema" / "exit-info-reau.mascarado.txt").exists())

    def test_the_dash_name_and_the_mac_of_the_kernel_log_are_masked_in_the_copy_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            fake_adb(tmp, ["ABC123"])
            adb = tmp / "adb"
            # a fake that answers the kernel buffer with a line carrying a synthetic dash name and a synthetic MAC (put together here: a whole one
            # written in a versioned file trips PrivacyGuardTest)
            mac = ":".join(["aa", "bb", "cc", "dd", "ee", "ff"])
            body = adb.read_text(encoding="utf-8").replace(
                "echo fake", f'case "$*" in *"-b kernel"*) echo "wlan0: connected to RE_AB12_345678 {mac}"; exit 0;; esac\necho fake', 1)
            adb.write_text(body, encoding="utf-8")
            out = tmp / "saida"
            env = {**os.environ, "ADB": str(adb), "REAU_SAIDA": str(out)}
            done = subprocess.run(["bash", str(SCRIPT), "ABC123"], env=env, capture_output=True, text=True)
            self.assertEqual(0, done.returncode, done.stderr)
            original = (out / "sistema" / "logcat-kernel.txt").read_text(encoding="utf-8")
            masked = (out / "sistema" / "logcat-kernel.mascarado.txt").read_text(encoding="utf-8")
            self.assertIn("RE_AB12_345678", original)
            self.assertNotIn("RE_AB12_345678", masked)
            self.assertNotIn(mac, masked)

    def test_the_script_never_changes_the_size_of_the_logcat_buffers(self):
        code = [l for l in SCRIPT.read_text(encoding="utf-8").splitlines() if not l.lstrip().startswith("#")]
        self.assertFalse([l for l in code if " -G " in f" {l} " or "logcat -c" in l], code)


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
                self.assertRegex(rest, r"^shell dumpsys (package \S+|deviceidle whitelist|power|battery|wifi|connectivity|location|meminfo|activity processes"
                                       r"|activity exit-info \S+)$", command)
            if rest.startswith("logcat"):
                self.assertRegex(rest, r"^logcat (-b (all|crash|events|kernel) -d -v threadtime|-b (main|system|crash) -d -v threadtime -m 3|-g)$", command)

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
        self.assertEqual(["INFO.txt", "logcat-crash.txt", "logcat-sistema.mascarado.txt", "logcat-sistema.txt", "sistema"], files)
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


    def test_the_whole_sessions_folder_is_copied_so_the_copies_a_mark_keeps_come_along(self):
        # Plan 22: the rider's mark keeps reau_<date>_<time>_marca[.1].aasession beside the ring's files in sessions/; no filter by name may leave them out.
        script = SCRIPT.read_text(encoding="utf-8")
        self.assertIn('tar c -C files "$pasta"', script)
        code = [l for l in script.split("# As pastas de registro do app")[1].splitlines() if not l.lstrip().startswith("#")]
        self.assertFalse([l for l in code if "aasession" in l or "grep" in l or "--include" in l], code)  # the copy is of the folder, by no name
        self.assertIn("_marca", script)  # the header says what the folder holds

    def test_it_reads_the_app_and_the_old_lite_one_if_it_is_still_installed_apart(self):
        # 0.3.0 made one app (com.reau.app); the old Lite (com.reau.app.lite) may still be on the phone, its logs go to antigo-lite/.
        script = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("for pkg in com.reau.app com.reau.app.lite; do", script)
        self.assertIn("antigo-lite", script)


if __name__ == "__main__":
    unittest.main()
