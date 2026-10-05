"""Webcam/MediaPipe worker. Writes only TSV observations to stdout."""
import argparse
import math
import os
import re
import sys
import textwrap
import threading
import time
import unicodedata

import cv2
import mediapipe as mp

TARGET_GESTURES = {"Open_Palm", "Closed_Fist"}
MIN_COMMAND_SCORE = 0.80
WINDOW_TITLE = "Gesture Switch - webcam"
STATUS_SECONDS = 12.0


class HandReleaseTracker:
    """Carry confirmed releases forward so Java cannot lose them while sending commands."""
    def __init__(self):
        self.epoch = 0
        self.since = None
        self.released = False
        self.last_timestamp = None

    def update(self, timestamp, hands):
        if self.last_timestamp is not None and (
                timestamp <= self.last_timestamp or timestamp - self.last_timestamp > 1500):
            self.since = None
        self.last_timestamp = timestamp
        if hands:
            self.since = None
            self.released = False
        elif not self.released:
            if self.since is None:
                self.since = timestamp
            if timestamp - self.since >= 400:
                self.epoch += 1
                self.released = True
        return self.epoch


def ascii_text(text):
    """OpenCV draws only ASCII: drop accents instead of showing '?'."""
    return unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode("ascii")


def parse_status(line):
    """Parse a 'STATUS<TAB>ok|error<TAB>text' line sent by Java; other lines give None."""
    parts = line.rstrip("\r\n").split("\t", 2)
    if len(parts) != 3 or parts[0] != "STATUS":
        return None
    text = re.sub(r"^\[[^\]]*\]\s*", "", ascii_text(parts[2]).strip())
    if not text:
        return None
    return ("error" if parts[1] == "error" else "ok"), text


def status_lines(text, width=62, max_lines=2):
    lines = textwrap.wrap(text, width) or [""]
    if len(lines) > max_lines:
        lines = lines[:max_lines]
        lines[-1] = lines[-1][:width - 3].rstrip() + "..."
    return lines


def start_status_reader(box):
    """Read command results from Java (stdin) without blocking the camera loop.

    Uses os.read on the descriptor: a daemon thread blocked inside sys.stdin's buffered reader
    holds its lock and makes the interpreter abort at shutdown."""
    try:
        descriptor = sys.stdin.fileno() if sys.stdin is not None else None
    except (OSError, ValueError, AttributeError):
        descriptor = None
    if descriptor is None:
        return

    def run():
        pending = b""
        try:
            while True:
                chunk = os.read(descriptor, 4096)
                if not chunk:
                    return
                pending += chunk
                *lines, pending = pending.split(b"\n")
                pending = pending[-4096:]
                for raw in lines:
                    parsed = parse_status(raw.decode("utf-8", "replace"))
                    if parsed:
                        box["status"] = (parsed[0], parsed[1], time.monotonic())
        except (OSError, ValueError):
            pass

    threading.Thread(target=run, name="java-status", daemon=True).start()


def draw_status(frame, status, now):
    """Last command result above the mode bar: green for accepted, red for errors."""
    if not status or now - status[2] > STATUS_SECONDS:
        return
    level, text, _ = status
    height, width = frame.shape[:2]
    lines = status_lines(f"{'ERRO: ' if level == 'error' else ''}{text} ({int(now - status[2])} s)")
    top = height - 72 - 24 * len(lines)
    cv2.rectangle(frame, (8, top), (width - 8, height - 70), (25, 25, 25), -1)
    color = (80, 80, 255) if level == "error" else (120, 230, 120)
    for index, line in enumerate(lines):
        cv2.putText(frame, line, (15, top + 19 + 24 * index), cv2.FONT_HERSHEY_SIMPLEX,
                    0.5, color, 1, cv2.LINE_AA)


def rotation_for_hand(hand_landmarks):
    """Turn the wrist-to-knuckles direction upward in the camera image."""
    wrist = hand_landmarks[0]
    middle_knuckle = hand_landmarks[9]
    dx = middle_knuckle.x - wrist.x
    dy = middle_knuckle.y - wrist.y
    if dx * dx + dy * dy < 0.04 * 0.04:
        return None
    if abs(dx) > abs(dy):
        return (cv2.ROTATE_90_COUNTERCLOCKWISE if dx > 0
                else cv2.ROTATE_90_CLOCKWISE)
    return cv2.ROTATE_180 if dy > 0 else None


def best_category(result):
    if len(result.hand_landmarks) != 1 or not result.gestures or not result.gestures[0]:
        return None
    return result.gestures[0][0]


def finger_extension_count(hand_landmarks):
    """Count straight non-thumb fingers using rotation-independent distances."""
    wrist = hand_landmarks[0]
    palm_size = math.dist((wrist.x, wrist.y),
                          (hand_landmarks[9].x, hand_landmarks[9].y))
    if palm_size < 0.04:
        return 0
    extended = 0
    for mcp_index, pip_index, tip_index in ((5, 6, 8), (9, 10, 12),
                                             (13, 14, 16), (17, 18, 20)):
        mcp = hand_landmarks[mcp_index]
        pip = hand_landmarks[pip_index]
        tip = hand_landmarks[tip_index]
        tip_from_wrist = math.dist((tip.x, tip.y), (wrist.x, wrist.y))
        pip_from_wrist = math.dist((pip.x, pip.y), (wrist.x, wrist.y))
        tip_from_mcp = math.dist((tip.x, tip.y), (mcp.x, mcp.y))
        pip_from_mcp = math.dist((pip.x, pip.y), (mcp.x, mcp.y))
        if (tip_from_wrist > pip_from_wrist + 0.18 * palm_size
                and tip_from_mcp > 1.55 * pip_from_mcp):
            extended += 1
    return extended


def curled_finger_count(hand_landmarks):
    """Count compact non-thumb fingers, independently of the hand's angle."""
    wrist = hand_landmarks[0]
    palm_size = math.dist((wrist.x, wrist.y),
                          (hand_landmarks[9].x, hand_landmarks[9].y))
    if palm_size < 0.04:
        return 0
    curled = 0
    for mcp_index, pip_index, tip_index in ((5, 6, 8), (9, 10, 12),
                                             (13, 14, 16), (17, 18, 20)):
        mcp = hand_landmarks[mcp_index]
        pip = hand_landmarks[pip_index]
        tip = hand_landmarks[tip_index]
        tip_from_mcp = math.dist((tip.x, tip.y), (mcp.x, mcp.y))
        pip_from_mcp = math.dist((pip.x, pip.y), (mcp.x, mcp.y))
        tip_from_wrist = math.dist((tip.x, tip.y), (wrist.x, wrist.y))
        pip_from_wrist = math.dist((pip.x, pip.y), (wrist.x, wrist.y))
        if (tip_from_mcp < 1.35 * pip_from_mcp
                and tip_from_wrist < pip_from_wrist + 0.05 * palm_size):
            curled += 1
    return curled


def raised_fingers(hand_landmarks, category=None):
    """Count one hand's raised fingers without depending on image orientation."""
    wrist = hand_landmarks[0]
    def distance(a, b):
        return math.dist((a.x, a.y, getattr(a, "z", 0.0)),
                         (b.x, b.y, getattr(b, "z", 0.0)))
    palm_size = distance(wrist, hand_landmarks[9])
    if palm_size < 0.04:
        return None
    thumb_mcp = hand_landmarks[2]
    thumb_ip = hand_landmarks[3]
    thumb_tip = hand_landmarks[4]
    index_base = hand_landmarks[5]
    pinky_base = hand_landmarks[17]
    spread = distance(thumb_tip, pinky_base) - distance(thumb_mcp, pinky_base)
    length = distance(thumb_tip, thumb_mcp)
    tip_away_from_index = distance(thumb_tip, index_base) - distance(thumb_ip, index_base)
    thumb_open = (spread > 0.10 * palm_size
                  and length > 1.35 * distance(thumb_ip, thumb_mcp)
                  and tip_away_from_index > 0.02 * palm_size)
    non_thumb = finger_extension_count(hand_landmarks)
    if not thumb_open and non_thumb == 4 and category is not None:
        thumb_open = (category.category_name == "Open_Palm" and category.score >= 0.50
                      and spread > -0.05 * palm_size
                      and tip_away_from_index > -0.05 * palm_size)
    return non_thumb + int(thumb_open)


def button_number(hand_landmarks, gestures=None):
    """Count raised fingers across both hands; zero means turn off selection."""
    if not hand_landmarks:
        return 0
    if len(hand_landmarks) > 2:
        return None
    counts = [raised_fingers(hand, gestures[index][0]
                             if gestures and index < len(gestures) and gestures[index] else None)
              for index, hand in enumerate(hand_landmarks)]
    if any(count is None for count in counts):
        return None
    total = sum(counts)
    if 0 <= total <= 10:
        return total
    return None


def confirm_with_landmarks(category, hand_landmarks):
    """Confirm an open palm or compact fist from rotation-independent geometry."""
    label = category.category_name if category else "None"
    score = category.score if category else 0.0
    if hand_landmarks is None:
        return label, score, False
    extended = finger_extension_count(hand_landmarks)
    curled = curled_finger_count(hand_landmarks)
    if extended == 4 or (extended >= 3 and label == "Open_Palm" and score >= 0.50):
        return "Open_Palm", max(score, 0.85), True
    if extended == 0 and ((curled == 4 and label in ("None", "Closed_Fist")) or (
            curled >= 3 and label == "Closed_Fist" and score >= 0.45)):
        return "Closed_Fist", max(score if label == "Closed_Fist" else 0.0, 0.85), True
    if extended >= 2 and label == "Closed_Fist":
        return "None", 0.0, False
    return label, score, False


def resolve_gesture(frame, result, fallback_recognizer):
    """Retry an unrecognized sideways/upside-down hand in an upright image."""
    category = best_category(result)
    if category and category.category_name in TARGET_GESTURES and category.score >= MIN_COMMAND_SCORE:
        return category, False
    if len(result.hand_landmarks) != 1:
        return category, False
    rotation = rotation_for_hand(result.hand_landmarks[0])
    if rotation is None:
        return category, False
    rotated = cv2.rotate(frame, rotation)
    rgb = cv2.cvtColor(rotated, cv2.COLOR_BGR2RGB)
    image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
    retry = fallback_recognizer.recognize(image)
    retry_category = best_category(retry)
    if retry_category and retry_category.category_name in TARGET_GESTURES and (
            retry_category.score >= MIN_COMMAND_SCORE or not category
            or category.category_name not in TARGET_GESTURES
            or retry_category.score > category.score):
        return retry_category, True
    return category, False


def draw_hand_landmarks(frame, hand_landmarks, color):
    """Draw the 21 detected joints and MediaPipe's hand connections."""
    height, width = frame.shape[:2]
    points = [
        (min(width - 1, max(0, round(point.x * width))),
         min(height - 1, max(0, round(point.y * height))))
        for point in hand_landmarks
    ]
    for connection in mp.tasks.vision.HandLandmarksConnections.HAND_CONNECTIONS:
        cv2.line(frame, points[connection.start], points[connection.end], color, 2,
                 cv2.LINE_AA)
    for index, point in enumerate(points):
        radius = 5 if index in (4, 8, 12, 16, 20) else 3
        cv2.circle(frame, point, radius, (255, 255, 255), -1, cv2.LINE_AA)
        cv2.circle(frame, point, radius, color, 1, cv2.LINE_AA)


def mode_button_rects(width, height):
    """Clickable mode buttons in the bottom bar of the camera frame."""
    top, bottom = height - 56, height - 10
    return {"general": (12, top, 152, bottom),
            "all": (162, top, 342, bottom),
            "buttons": (352, top, 492, bottom)}


def mode_from_click(x, y, width, height):
    for mode, (left, top, right, bottom) in mode_button_rects(width, height).items():
        if left <= x <= right and top <= y <= bottom:
            return mode
    return None


def draw_mode_controls(frame, current_mode):
    height, width = frame.shape[:2]
    cv2.rectangle(frame, (0, height - 66), (width, height), (22, 22, 22), -1)
    for mode, caption in (("general", "GERAL"), ("all", "TODAS LUZES"),
                          ("buttons", "POR LUZ")):
        left, top, right, bottom = mode_button_rects(width, height)[mode]
        active = current_mode == mode
        color = {"general": (155, 105, 28), "all": (38, 134, 48),
                 "buttons": (190, 102, 24)}[mode]
        cv2.rectangle(frame, (left, top), (right, bottom), color if active else (65, 65, 65), -1)
        cv2.rectangle(frame, (left, top), (right, bottom), (245, 245, 245) if active else (120, 120, 120), 2)
        text_width = cv2.getTextSize(caption, cv2.FONT_HERSHEY_SIMPLEX, 0.57, 2)[0][0]
        cv2.putText(frame, caption, (left + (right - left - text_width) // 2, top + 30),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.57, (255, 255, 255), 2, cv2.LINE_AA)
    cv2.putText(frame, "M: trocar", (505, height - 37), cv2.FONT_HERSHEY_SIMPLEX,
                0.49, (220, 220, 220), 1, cv2.LINE_AA)
    cv2.putText(frame, "Q/ESC: sair", (505, height - 17), cv2.FONT_HERSHEY_SIMPLEX,
                0.49, (220, 220, 220), 1, cv2.LINE_AA)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True)
    parser.add_argument("--camera", type=int, default=0)
    parser.add_argument("--max-frames", type=int, default=0)
    parser.add_argument("--headless", action="store_true")
    parser.add_argument("--mode", choices=("general", "all", "buttons"), default="general")
    parser.add_argument("--max-lights", type=int, default=10)
    args = parser.parse_args()
    if not 1 <= args.max_lights <= 10:
        parser.error("--max-lights deve estar entre 1 e 10")

    capture = cv2.VideoCapture(args.camera, cv2.CAP_DSHOW)
    if not capture.isOpened():
        raise RuntimeError(f"Webcam {args.camera} indisponível")
    capture.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
    capture.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)

    options = mp.tasks.vision.GestureRecognizerOptions(
        base_options=mp.tasks.BaseOptions(model_asset_path=args.model),
        running_mode=mp.tasks.vision.RunningMode.VIDEO,
        num_hands=2,
        min_hand_detection_confidence=0.6,
        min_hand_presence_confidence=0.6,
        min_tracking_confidence=0.6,
    )
    fallback_options = mp.tasks.vision.GestureRecognizerOptions(
        base_options=mp.tasks.BaseOptions(model_asset_path=args.model),
        running_mode=mp.tasks.vision.RunningMode.IMAGE,
        num_hands=1,
        min_hand_detection_confidence=0.6,
        min_hand_presence_confidence=0.6,
    )
    started = time.monotonic()
    last_timestamp = -1
    releases = HandReleaseTracker()
    frames = 0
    current_mode = args.mode
    mode_request = {"value": None, "width": 640, "height": 480}
    status_box = {"status": None}
    start_status_reader(status_box)
    if not args.headless:
        cv2.namedWindow(WINDOW_TITLE, cv2.WINDOW_AUTOSIZE)

        def on_mouse(event, x, y, _flags, _userdata):
            if event == cv2.EVENT_LBUTTONUP:
                mode_request["value"] = mode_from_click(
                    x, y, mode_request["width"], mode_request["height"])

        cv2.setMouseCallback(WINDOW_TITLE, on_mouse)
    try:
        with (mp.tasks.vision.GestureRecognizer.create_from_options(options) as recognizer,
              mp.tasks.vision.GestureRecognizer.create_from_options(fallback_options) as fallback_recognizer):
            while True:
                ok, frame = capture.read()
                if not ok:
                    raise RuntimeError("Falha ao ler a webcam")
                frame = cv2.flip(frame, 1)
                rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
                image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
                timestamp = max(last_timestamp + 1, int((time.monotonic() - started) * 1000))
                last_timestamp = timestamp
                result = recognizer.recognize_for_video(image, timestamp)
                hands = len(result.hand_landmarks)
                rotated = False
                geometry = False
                if current_mode in ("buttons", "general"):
                    counted = button_number(result.hand_landmarks, result.gestures)
                    number = counted if counted is not None and counted <= args.max_lights else 0
                    if current_mode == "general" and hands == 1 and counted == 0:
                        category, rotated = resolve_gesture(frame, result, fallback_recognizer)
                        label, score, geometry = confirm_with_landmarks(category, result.hand_landmarks[0])
                        if label != "Closed_Fist":
                            label, score = "None", 0.0
                    elif current_mode == "general" and hands == 2 and counted == 10:
                        label, score = "All_On", 1.0
                    elif number:
                        label, score = f"Button_{number}", 1.0
                    else:
                        label, score = ("Button_0", 1.0) if current_mode == "buttons" else ("None", 0.0)
                else:
                    category, rotated = resolve_gesture(frame, result, fallback_recognizer)
                    label, score, geometry = confirm_with_landmarks(
                        category, result.hand_landmarks[0] if hands == 1 else None)
                release_epoch = releases.update(timestamp, hands)
                print(f"{timestamp}\t{label}\t{score:.4f}\t{hands}\t{current_mode}\t{release_epoch}", flush=True)
                frames += 1
                if not args.headless:
                    mode_request["width"] = frame.shape[1]
                    mode_request["height"] = frame.shape[0]
                    for index, landmarks in enumerate(result.hand_landmarks):
                        gesture = (label if hands == 1 else
                                   result.gestures[index][0].category_name
                                   if index < len(result.gestures) and result.gestures[index] else "None")
                        color = ((0, 210, 0) if gesture in ("Open_Palm", "All_On") or gesture.startswith("Button_") else
                                 (0, 140, 255) if gesture == "Closed_Fist" else (255, 200, 0))
                        draw_hand_landmarks(frame, landmarks, color)
                    cv2.rectangle(frame, (8, 7), (frame.shape[1] - 8, 53), (25, 25, 25), -1)
                    orientation = (" | girada" if rotated else "") + (" | geometria" if geometry else "")
                    if current_mode == "buttons":
                        if counted is not None and counted > args.max_lights:
                            status = f"{counted} dedos | limite: {args.max_lights} luzes"
                        else:
                            status = (f"Luz {number} | dedos: {number} | maos: {hands}" if number
                                      else f"Nenhuma luz | maos: {hands}")
                    elif current_mode == "general":
                        if label == "Closed_Fist":
                            status = "Punho: apagar todas as luzes"
                        elif label == "All_On":
                            status = "10 dedos: ligar todas as luzes"
                        elif number:
                            status = f"Luz {number}: alternar estado"
                        elif counted is not None and counted > args.max_lights:
                            status = f"{counted} dedos | sem comando"
                        else:
                            status = "Sem comando"
                    else:
                        status = f"{label} {score:.2f} | maos: {hands}{orientation}" if hands <= 1 else f"{hands} maos - bloqueado"
                    cv2.putText(frame, status, (15, 38), cv2.FONT_HERSHEY_SIMPLEX,
                                0.75, (255, 255, 255), 2, cv2.LINE_AA)
                    if current_mode == "all" and hands == 1:
                        extended = finger_extension_count(result.hand_landmarks[0])
                        curled = curled_finger_count(result.hand_landmarks[0])
                        cv2.putText(frame, f"Estendidos: {extended} | dobrados: {curled}", (15, 76),
                                    cv2.FONT_HERSHEY_SIMPLEX, 0.57, (255, 255, 255), 2, cv2.LINE_AA)
                    elif current_mode in ("buttons", "general") and hands:
                        per_hand = [raised_fingers(landmarks, result.gestures[index][0]
                                                   if index < len(result.gestures) and result.gestures[index] else None)
                                    for index, landmarks in enumerate(result.hand_landmarks)]
                        details = " | ".join(f"Mao {index + 1}: {value if value is not None else '?'}"
                                             for index, value in enumerate(per_hand))
                        cv2.putText(frame, details, (15, 76), cv2.FONT_HERSHEY_SIMPLEX,
                                    0.57, (255, 255, 255), 2, cv2.LINE_AA)
                    draw_status(frame, status_box["status"], time.monotonic())
                    draw_mode_controls(frame, current_mode)
                    cv2.imshow(WINDOW_TITLE, frame)
                if args.max_frames > 0 and frames >= args.max_frames:
                    break
                if not args.headless:
                    key = cv2.waitKey(1) & 0xFF
                    if key in (ord("q"), 27) or cv2.getWindowProperty(WINDOW_TITLE, cv2.WND_PROP_VISIBLE) < 1:
                        break
                    if key == ord("m"):
                        modes = ("general", "all", "buttons")
                        current_mode = modes[(modes.index(current_mode) + 1) % len(modes)]
                    elif mode_request["value"] is not None:
                        current_mode = mode_request["value"]
                    mode_request["value"] = None
    finally:
        capture.release()
        cv2.destroyAllWindows()


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"Erro no reconhecimento: {exc}", file=sys.stderr)
        sys.exit(1)
