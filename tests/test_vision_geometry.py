import math
import unittest
from types import SimpleNamespace

from vision.gesture_camera import (button_number, confirm_with_landmarks,
                                   curled_finger_count, finger_extension_count,
                                   raised_fingers)


def point(x, y):
    return SimpleNamespace(x=x, y=y)


def hand(open_fingers=True, angle=0):
    coordinates = [(0.50, 0.90)] * 21
    for mcp, pip, tip, x in ((5, 6, 8, 0.34), (9, 10, 12, 0.45),
                              (13, 14, 16, 0.56), (17, 18, 20, 0.67)):
        coordinates[mcp] = (x, 0.60)
        coordinates[pip] = (x, 0.43)
        coordinates[tip] = (x, 0.17 if open_fingers else 0.70)
    rotated = []
    for x, y in coordinates:
        dx, dy = x - .5, y - .5
        rotated.append(point(.5 + dx * math.cos(angle) - dy * math.sin(angle),
                             .5 + dx * math.sin(angle) + dy * math.cos(angle)))
    return rotated


def hand_with_count(count, angle=0):
    coordinates = [(0.50, 0.90)] * 21
    for finger, (mcp, pip, tip, x) in enumerate(((5, 6, 8, 0.34), (9, 10, 12, 0.45),
                                                   (13, 14, 16, 0.56), (17, 18, 20, 0.67))):
        coordinates[mcp] = (x, 0.60)
        coordinates[pip] = (x, 0.43)
        coordinates[tip] = (x, 0.17 if finger < count else 0.70)
    coordinates[2] = (0.35, 0.72)
    coordinates[3] = (0.28, 0.69) if count == 5 else (0.43, 0.69)
    coordinates[4] = (0.13, 0.52) if count == 5 else (0.43, 0.63)
    rotated = []
    for x, y in coordinates:
        dx, dy = x - .5, y - .5
        rotated.append(point(.5 + dx * math.cos(angle) - dy * math.sin(angle),
                             .5 + dx * math.sin(angle) + dy * math.cos(angle)))
    return rotated


class GeometryTest(unittest.TestCase):
    def test_open_hand_all_orientations(self):
        for angle in (0, math.pi / 2, math.pi, -math.pi / 2):
            with self.subTest(angle=angle):
                self.assertEqual(finger_extension_count(hand(True, angle)), 4)
                label, score, geometry = confirm_with_landmarks(
                    SimpleNamespace(category_name="Open_Palm", score=.55), hand(True, angle))
                self.assertEqual(label, "Open_Palm")
                self.assertGreaterEqual(score, .80)
                self.assertTrue(geometry)

    def test_fist_does_not_become_open(self):
        self.assertEqual(finger_extension_count(hand(False)), 0)
        label, score, geometry = confirm_with_landmarks(
            SimpleNamespace(category_name="Closed_Fist", score=.86), hand(False))
        self.assertEqual((label, score, geometry), ("Closed_Fist", .86, True))

    def test_fist_from_geometry_in_all_orientations(self):
        for angle in (0, math.pi / 2, math.pi, -math.pi / 2):
            with self.subTest(angle=angle):
                self.assertEqual(curled_finger_count(hand(False, angle)), 4)
                label, score, geometry = confirm_with_landmarks(
                    SimpleNamespace(category_name="None", score=.70), hand(False, angle))
                self.assertEqual(label, "Closed_Fist")
                self.assertGreaterEqual(score, .80)
                self.assertTrue(geometry)

    def test_open_hand_is_not_fist(self):
        self.assertEqual(curled_finger_count(hand(True)), 0)
        label, _, _ = confirm_with_landmarks(
            SimpleNamespace(category_name="Open_Palm", score=.55), hand(True))
        self.assertEqual(label, "Open_Palm")

    def test_one_raised_finger_does_not_trigger_fist(self):
        landmarks = hand_with_count(1)
        self.assertEqual(finger_extension_count(landmarks), 1)
        label, score, geometry = confirm_with_landmarks(
            SimpleNamespace(category_name="Closed_Fist", score=.55), landmarks)
        self.assertEqual((label, score, geometry), ("Closed_Fist", .55, False))

    def test_button_numbers_with_rotated_hands(self):
        for number in range(1, 11):
            for angle in (0, math.pi / 2, math.pi, -math.pi / 2):
                with self.subTest(number=number, angle=angle):
                    hands = ([hand_with_count(number, angle)] if number <= 5 else
                             [hand_with_count(5, angle), hand_with_count(number - 5, angle)])
                    self.assertEqual(button_number(hands), number)

    def test_fists_do_not_select_a_button(self):
        self.assertEqual(raised_fingers(hand_with_count(0)), 0)
        self.assertEqual(button_number([]), 0)
        self.assertEqual(button_number([hand_with_count(0)]), 0)
        self.assertEqual(button_number([hand_with_count(2), hand_with_count(2)]), 4)

    def test_open_palm_model_recovers_partly_occluded_thumb(self):
        category = SimpleNamespace(category_name="Open_Palm", score=.60)
        hand_one = hand_with_count(5)
        hand_one[4] = point(.35, .50)
        self.assertEqual(raised_fingers(hand_one, category), 5)
        self.assertEqual(raised_fingers(hand_with_count(4), category), 4)
        self.assertEqual(button_number([hand_one, hand_one], [[category], [category]]), 10)


if __name__ == "__main__":
    unittest.main()
