import unittest

from vision.gesture_camera import HandReleaseTracker


class HandReleasesTest(unittest.TestCase):
    def test_release_survives_skipped_observations(self):
        tracker = HandReleaseTracker()
        self.assertEqual(tracker.update(0, 1), 0)
        for timestamp in (100, 200, 300, 400):
            self.assertEqual(tracker.update(timestamp, 0), 0)
        self.assertEqual(tracker.update(500, 0), 1)
        # Java may only see this next frame, after dropping the earlier ones.
        self.assertEqual(tracker.update(600, 1), 1)
        self.assertEqual(tracker.update(700, 1), 1)
        for timestamp in range(800, 1300, 100):
            tracker.update(timestamp, 0)
        self.assertEqual(tracker.update(1300, 1), 2)

    def test_brief_loss_or_camera_gap_does_not_release(self):
        tracker = HandReleaseTracker()
        tracker.update(0, 1)
        tracker.update(100, 0)
        tracker.update(300, 0)
        self.assertEqual(tracker.update(400, 1), 0)
        tracker.update(500, 0)
        self.assertEqual(tracker.update(5000, 0), 0)
        self.assertEqual(tracker.update(5300, 0), 0)
        self.assertEqual(tracker.update(5400, 0), 1)

    def test_continuous_absence_counts_once(self):
        tracker = HandReleaseTracker()
        for timestamp in range(0, 2000, 100):
            tracker.update(timestamp, 0)
        self.assertEqual(tracker.epoch, 1)
