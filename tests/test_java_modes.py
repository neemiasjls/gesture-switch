import os
from pathlib import Path
import shutil
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(shutil.which("java") and (ROOT / "target/classes").exists(),
                     "Compile o Java com mvn -q package antes deste teste")
class JavaModesTest(unittest.TestCase):
    def run_observations(self, rows, mode="general"):
        result = subprocess.run(
            ["java", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-cp", "target/classes",
             "local.gestureswitch.Main", "--stdin", "--mode", mode],
            input="\n".join(rows) + "\n", capture_output=True, encoding="utf-8",
            cwd=ROOT, env=dict(os.environ, GESTURE_BACKEND="simulation"), timeout=15, check=True)
        self.assertEqual(result.stderr, "")
        return [line for line in result.stdout.splitlines() if line.startswith("[SIMULAÇÃO]")]

    def test_general_retains_lights_and_requires_release(self):
        rows = []

        def frames(start, end, label, hands):
            rows.extend(f"{t}\t{label}\t0.95\t{hands}\tgeneral" for t in range(start, end + 1, 100))

        frames(0, 400, "Button_1", 1)
        frames(5000, 5600, "Button_1", 1)  # A slow cloud response must not repeat a held count.
        frames(5700, 6200, "None", 1)
        frames(6300, 6700, "Button_1", 1)
        frames(6800, 7200, "None", 0)
        frames(7300, 7700, "Button_4", 1)
        frames(7800, 8200, "Button_7", 2)
        frames(8300, 8700, "None", 0)
        frames(8800, 9200, "Button_1", 1)
        frames(9300, 10700, "All_On", 2)
        frames(10800, 11200, "Button_8", 2)  # Lowering ten fingers must not toggle eight.
        frames(11300, 11700, "None", 0)
        frames(11800, 12200, "Button_8", 2)
        frames(13300, 14800, "Closed_Fist", 1)
        self.assertEqual(self.run_observations(rows), [
            "[SIMULAÇÃO] Luz 1 ligada.", "[SIMULAÇÃO] Luz 4 ligada.",
            "[SIMULAÇÃO] Luz 7 ligada.", "[SIMULAÇÃO] Luz 1 desligada.",
            "[SIMULAÇÃO] 3 interruptores da sala: ACENDER TODOS",
            "[SIMULAÇÃO] Luz 8 desligada.",
            "[SIMULAÇÃO] 3 interruptores da sala: APAGAR TODOS",
        ])

    def test_buttons_mode_is_exclusive_and_turns_off_on_exit(self):
        rows = []

        def frames(start, end, label, hands, mode="buttons"):
            rows.extend(f"{t}\t{label}\t1.0\t{hands}\t{mode}" for t in range(start, end + 1, 100))

        frames(0, 400, "Button_6", 2)
        frames(5000, 5600, "Button_6", 2)  # A slow response must not turn the same light off/on.
        frames(5700, 6100, "Button_5", 2)
        frames(6200, 6600, "Button_4", 1)
        frames(6700, 7100, "Button_0", 2)  # Nine/ten fingers arrive as zero: no light selected.
        frames(7200, 7600, "Button_2", 1)
        frames(7700, 8100, "Button_3", 1, "general")  # Leaving turns 2 off; Geral toggles 3.
        frames(8200, 8600, "Button_7", 2)  # Entering again turns everything off first.
        self.assertEqual(self.run_observations(rows, "buttons"), [
            "[SIMULAÇÃO] 3 interruptores da sala: APAGAR TODOS",
            "[SIMULAÇÃO] Luz 6 ligada.",
            "[SIMULAÇÃO] Luz 6 desligada.", "[SIMULAÇÃO] Luz 5 ligada.",
            "[SIMULAÇÃO] Luz 5 desligada.", "[SIMULAÇÃO] Luz 4 ligada.",
            "[SIMULAÇÃO] Luz 4 desligada.",
            "[SIMULAÇÃO] Luz 2 ligada.",
            "[SIMULAÇÃO] Luz 2 desligada.",
            "[SIMULAÇÃO] Luz 3 ligada.",
            "[SIMULAÇÃO] 3 interruptores da sala: APAGAR TODOS",
            "[SIMULAÇÃO] Luz 7 ligada.",
            "[SIMULAÇÃO] Luz 7 desligada.",  # Normal end of input turns the selection off.
        ])

    def test_general_releases_during_commands_are_not_lost(self):
        rows = []

        def frames(start, end, label, hands, epoch):
            rows.extend(f"{t}\t{label}\t0.95\t{hands}\tgeneral\t{epoch}"
                        for t in range(start, end + 1, 100))

        frames(0, 400, "Button_1", 1, 0)
        # No neutral frames arrive: the hands left while Java was waiting for the API.
        frames(1600, 2000, "Button_1", 1, 1)
        frames(3000, 3400, "Button_1", 1, 2)
        frames(3500, 4900, "Closed_Fist", 1, 2)
        frames(5000, 5400, "Button_4", 1, 2)  # Still blocked until a confirmed release.
        frames(6500, 6900, "Button_4", 1, 3)
        frames(7000, 8400, "All_On", 2, 3)
        frames(8500, 8900, "Button_7", 2, 3)  # Lowering fingers must not toggle seven.
        frames(10000, 10400, "Button_7", 2, 4)
        self.assertEqual(self.run_observations(rows), [
            "[SIMULAÇÃO] Luz 1 ligada.",
            "[SIMULAÇÃO] Luz 1 desligada.",
            "[SIMULAÇÃO] Luz 1 ligada.",
            "[SIMULAÇÃO] 3 interruptores da sala: APAGAR TODOS",
            "[SIMULAÇÃO] Luz 4 ligada.",
            "[SIMULAÇÃO] 3 interruptores da sala: ACENDER TODOS",
            "[SIMULAÇÃO] Luz 7 desligada.",
        ])

    def test_all_mode_rejects_two_hands_and_accepts_open_and_fist(self):
        rows = []
        for start, end, label, hands in (
                (0, 1400, "Open_Palm", 2), (1500, 2900, "Open_Palm", 1),
                (3000, 5600, "Open_Palm", 1), (5700, 7100, "Closed_Fist", 1)):
            rows.extend(f"{t}\t{label}\t0.95\t{hands}\tall" for t in range(start, end + 1, 100))
        self.assertEqual(self.run_observations(rows, "all"), [
            "[SIMULAÇÃO] 3 interruptores da sala: ACENDER TODOS",
            "[SIMULAÇÃO] 3 interruptores da sala: APAGAR TODOS",
        ])
