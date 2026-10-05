from pathlib import Path
import subprocess
import sys
import unittest

from vision.gesture_camera import mode_from_click, parse_status, status_lines

ROOT = Path(__file__).resolve().parents[1]


class CameraControlsTest(unittest.TestCase):
    def test_clicks_select_three_modes(self):
        self.assertEqual(mode_from_click(100, 448, 640, 480), "general")
        self.assertEqual(mode_from_click(250, 448, 640, 480), "all")
        self.assertEqual(mode_from_click(420, 448, 640, 480), "buttons")
        self.assertIsNone(mode_from_click(550, 448, 640, 480))
        self.assertIsNone(mode_from_click(300, 200, 640, 480))


class JavaStatusTest(unittest.TestCase):
    def test_status_lines_from_java_are_ascii_without_prefix(self):
        self.assertEqual(parse_status("STATUS\tok\t[SELEÇÃO] Luz 3\n"), ("ok", "Luz 3"))
        self.assertEqual(parse_status("STATUS\terror\tFalha nos alvos: token inválido\r\n"),
                         ("error", "Falha nos alvos: token invalido"))
        self.assertEqual(parse_status("STATUS\tqualquer\tTexto"), ("ok", "Texto"))

    def test_other_lines_are_ignored(self):
        self.assertIsNone(parse_status("texto digitado no terminal"))
        self.assertIsNone(parse_status("STATUS\tok\t   "))
        self.assertIsNone(parse_status(""))

    def test_reader_receives_status_and_exits_cleanly_with_open_stdin(self):
        script = (
            "import sys, time\n"
            "from vision.gesture_camera import start_status_reader\n"
            "box = {'status': None}\n"
            "start_status_reader(box)\n"
            "deadline = time.monotonic() + 5\n"
            "while box['status'] is None and time.monotonic() < deadline: time.sleep(0.02)\n"
            "print(box['status'][:2] if box['status'] else None, flush=True)\n")
        process = subprocess.Popen([sys.executable, "-c", script], cwd=ROOT, stdin=subprocess.PIPE,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            process.stdin.write("STATUS\terror\tFalha na luz 3: token inválido\n".encode("utf-8"))
            process.stdin.flush()
            out = process.stdout.readline()
            # stdin stays open, like Java's pipe while the camera window closes.
            returncode = process.wait(timeout=60)
            err = process.stderr.read()
        finally:
            process.kill()
            for stream in (process.stdin, process.stdout, process.stderr):
                stream.close()
        self.assertEqual(out.decode().strip(), "('error', 'Falha na luz 3: token invalido')")
        self.assertEqual(returncode, 0, err.decode(errors="replace"))

    def test_long_status_is_wrapped_and_truncated(self):
        lines = status_lines("palavra " * 40, width=30, max_lines=2)
        self.assertEqual(len(lines), 2)
        self.assertTrue(lines[-1].endswith("..."))
        self.assertTrue(all(len(line) <= 30 for line in lines))


if __name__ == "__main__":
    unittest.main()
