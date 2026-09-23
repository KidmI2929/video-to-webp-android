"""Core tests also run on the Windows GitHub Actions runner."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from core import Converter, Options, VideoInfo, binary, command, crop_geometry, filters, probe, timecode, valid_webp
import subprocess


class CoreTests(unittest.TestCase):
    def test_timecode(self):
        self.assertEqual(timecode(3723456), "01:02:03.456")
        self.assertEqual(timecode(-5), "00:00:00.000")

    def test_crop(self):
        info = VideoInfo("file.mp4", 2000, 1920, 1080, 30)
        opt = Options(0, 1000, ratio="1:1", focus_x=0.9, focus_y=0.4)
        self.assertEqual(crop_geometry(info, opt), (1080, 1080, 840, 0))
        self.assertIn("crop=1080:1080:840:0", filters(info, opt))

    def test_command(self):
        info = VideoInfo("test video.mp4", 2000, 1920, 1080, 30)
        opt = Options(0, 1000, width=720, ratio="9:16", fps=15)
        args = command(info, opt, 0, 1000, Path("out.webp"))
        self.assertIn("libwebp", args)
        self.assertIn("-progress", args)
        self.assertIn("fps=15", " ".join(args))
        self.assertEqual(args[-3], "-f")

    def test_real_conversion_and_split(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "video.mp4"
            subprocess.run([
                binary("ffmpeg"), "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=8",
                "-t", "2", "-c:v", "mpeg4", str(path)
            ], check=True, timeout=45)
            info = probe(str(path))
            self.assertGreater(info.duration_ms, 1700)
            self.assertEqual(info.width, 320)
            options = Options(
                0, min(1900, info.duration_ms), width=240,
                fps=8, quality=65, ratio="1:1",
                split_mode="개수", split_count=2,
            )
            output = Path(temp) / "export"
            files = Converter().run(info, options, output)
            self.assertEqual(len(files), 2)
            for file in files:
                self.assertTrue(valid_webp(file))
                data = file.read_bytes()
                self.assertIn(b"ANIM", data)
                self.assertIn(b"ANMF", data)
                self.assertGreater(len(data), 100)

if __name__ == "__main__":
    unittest.main()
