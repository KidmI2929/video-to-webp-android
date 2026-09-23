"""Motion WebP Windows conversion core. No Qt dependency; testable headlessly."""
from __future__ import annotations

import json
import math
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable


def binary(name: str) -> str:
    exe = name + (".exe" if os.name == "nt" else "")
    locations = [
        Path(getattr(sys, "_MEIPASS", Path(__file__).parent)) / "bin" / exe,
        Path(sys.executable).resolve().parent / "bin" / exe,
        Path(__file__).resolve().parent / "bin" / exe,
    ]
    for location in locations:
        if location.is_file():
            return str(location)
    return shutil.which(name) or exe


def timecode(ms: int) -> str:
    ms = max(0, int(ms))
    h, m = divmod(ms // 60000, 60)
    s, millis = divmod(ms % 60000, 1000)
    return f"{h:02d}:{m:02d}:{s:02d}.{millis:03d}"


@dataclass(frozen=True)
class VideoInfo:
    path: str
    duration_ms: int
    width: int
    height: int
    fps: float

    @property
    def frame_ms(self) -> int:
        return max(1, round(1000 / self.fps))


def probe(path: str) -> VideoInfo:
    cmd = [
        binary("ffprobe"), "-v", "error", "-print_format", "json",
        "-show_entries", "format=duration:stream=codec_type,width,height,avg_frame_rate,r_frame_rate,tags,side_data_list",
        path,
    ]
    data = json.loads(subprocess.check_output(
        cmd, text=True, encoding="utf-8", errors="replace",
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    ))
    stream = next(s for s in data.get("streams", []) if s.get("codec_type") == "video")

    def parse_fps(value: str) -> float:
        try:
            n, d = value.split("/")
            fps = float(n) / float(d)
            return fps if 1 <= fps <= 240 else 0.0
        except (ValueError, ZeroDivisionError, AttributeError):
            return 0.0

    fps = parse_fps(stream.get("avg_frame_rate", ""))
    fps = fps or parse_fps(stream.get("r_frame_rate", "")) or 30.0
    rotation = float(stream.get("tags", {}).get("rotate", 0) or 0)
    for side in stream.get("side_data_list", []):
        if side.get("rotation") is not None:
            rotation = float(side["rotation"])
    width, height = int(stream["width"]), int(stream["height"])
    if int(abs(rotation)) % 180 == 90:
        width, height = height, width
    duration = max(1, round(float(data.get("format", {}).get("duration", 0)) * 1000))
    return VideoInfo(str(path), duration, width, height, fps)


@dataclass
class Options:
    start_ms: int
    end_ms: int
    width: int = 720  # longest side; 0 = original
    fps: int = 15
    quality: int = 80
    speed: str = "빠름"
    lossless: bool = False
    loop: bool = True
    ratio: str = "원본"
    focus_x: float = 0.5
    focus_y: float = 0.5
    split_mode: str = "없음"
    split_count: int = 2
    target_mb: int = 8


RATIOS = {
    "원본": None,
    "1:1": 1.0,
    "4:5": 4 / 5,
    "9:16": 9 / 16,
    "3:4": 3 / 4,
    "16:9": 16 / 9,
}
METHODS = {"터보": 0, "빠름": 1, "균형": 4, "최대 압축": 6}
SCALERS = {"터보": "fast_bilinear", "빠름": "bilinear",
           "균형": "bicubic", "최대 압축": "lanczos"}


def crop_geometry(video: VideoInfo, opt: Options) -> tuple[int, int, int, int]:
    w, h = video.width, video.height
    target = RATIOS.get(opt.ratio)
    if target is None:
        return w, h, 0, 0
    if w / h > target:
        cw, ch = max(2, min(w, round(h * target / 2) * 2)), h
    else:
        cw, ch = w, max(2, min(h, round(w / target / 2) * 2))
    # Center on tapped coordinate, clamped near the edges.
    x = max(0, min(w - cw, round(w * max(0, min(1, opt.focus_x)) - cw / 2)))
    y = max(0, min(h - ch, round(h * max(0, min(1, opt.focus_y)) - ch / 2)))
    return cw, ch, int(x // 2 * 2), int(y // 2 * 2)


def filters(video: VideoInfo, opt: Options) -> str:
    cw, ch, x, y = crop_geometry(video, opt)
    chain = []
    if opt.ratio != "원본":
        chain.append(f"crop={cw}:{ch}:{x}:{y}")
    chain.append(f"fps={max(1, min(60, int(opt.fps)))}")
    limit = int(opt.width)
    if limit > 0 and max(cw, ch) > limit:
        factor = limit / max(cw, ch)
        w = max(2, round(cw * factor / 2) * 2)
        h = max(2, round(ch * factor / 2) * 2)
        chain.append(f"scale={w}:{h}:flags={SCALERS.get(opt.speed, 'bicubic')}")
    return ",".join(chain)


def command(video: VideoInfo, opt: Options, start_ms: int, end_ms: int, output: Path) -> list[str]:
    cmd = [
        binary("ffmpeg"), "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
        "-progress", "pipe:1", "-nostats",
        "-ss", f"{start_ms / 1000:.3f}", "-i", video.path,
        "-t", f"{(end_ms - start_ms) / 1000:.3f}", "-an",
        "-vf", filters(video, opt),
        "-c:v", "libwebp", "-quality", str(max(1, min(100, opt.quality))),
        "-lossless", "1" if opt.lossless else "0",
        "-compression_level", str(METHODS.get(opt.speed, 1)),
        "-preset", "picture", "-loop", "0" if opt.loop else "1",
        "-threads", "0",
    ]
    if not opt.lossless:
        cmd += ["-pix_fmt", "yuv420p"]
    return cmd + ["-f", "webp", str(output)]


def valid_webp(path: Path) -> bool:
    if not path.is_file() or path.stat().st_size < 20:
        return False
    with path.open("rb") as stream:
        data = stream.read(12)
    return data[:4] == b"RIFF" and data[8:12] == b"WEBP"


class Cancelled(Exception):
    pass


class Converter:
    def __init__(self, on_status: Callable[[str], None] = lambda _: None,
                 on_progress: Callable[[int], None] = lambda _: None):
        self.on_status = on_status
        self.on_progress = on_progress
        self.cancel_event = threading.Event()
        self._lock = threading.Lock()
        self._process: subprocess.Popen | None = None

    def cancel(self):
        self.cancel_event.set()
        with self._lock:
            process = self._process
        if process and process.poll() is None:
            process.terminate()

    def check_cancel(self):
        if self.cancel_event.is_set():
            raise Cancelled()

    def encode(self, video: VideoInfo, opt: Options, start_ms: int, end_ms: int,
               output: Path, progress_base: int, progress_span: int) -> int:
        self.check_cancel()
        args = command(video, opt, start_ms, end_ms, output)
        process = subprocess.Popen(
            args, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            text=True, encoding="utf-8", errors="replace",
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        with self._lock:
            self._process = process
        stderr_parts: list[str] = []
        def consume_error():
            if process.stderr:
                for line in process.stderr:
                    stderr_parts.append(line)
                    if len(stderr_parts) > 40:
                        del stderr_parts[:10]
        error_reader = threading.Thread(target=consume_error, daemon=True)
        error_reader.start()
        duration_ms = max(1, end_ms - start_ms)
        try:
            for line in process.stdout or []:
                self.check_cancel()
                if line.startswith("out_time_ms=") or line.startswith("out_time_us="):
                    try:
                        value = int(line.partition("=")[2]) // 1000
                        fraction = min(0.99, max(0, value / duration_ms))
                        self.on_progress(min(99, progress_base + int(progress_span * fraction)))
                    except ValueError:
                        pass
                elif line.startswith("out_time="):
                    try:
                        stamp = line.partition("=")[2].strip().split(":")
                        value = (float(stamp[0]) * 3600 + float(stamp[1]) * 60 + float(stamp[2])) * 1000
                        self.on_progress(min(99, progress_base + int(progress_span * min(0.99, value / duration_ms))))
                    except (ValueError, IndexError):
                        pass
            code = process.wait()
            error_reader.join(timeout=3)
            self.check_cancel()
            if code != 0:
                raise RuntimeError("FFmpeg 변환 실패:\n" + "".join(stderr_parts[-12:])[-2200:])
            if not valid_webp(output):
                raise RuntimeError("출력 WebP 파일이 유효하지 않습니다.")
            self.on_progress(min(99, progress_base + progress_span))
            return output.stat().st_size
        finally:
            with self._lock:
                if self._process is process:
                    self._process = None
            if process.poll() is None:
                process.kill()
                process.wait()
            if process.stdout:
                process.stdout.close()
            if process.stderr:
                process.stderr.close()

    def run(self, video: VideoInfo, opt: Options, folder: Path) -> list[Path]:
        self.check_cancel()
        folder.mkdir(parents=True, exist_ok=True)
        start, end = max(0, opt.start_ms), min(video.duration_ms, opt.end_ms)
        if end <= start:
            raise ValueError("IN/OUT 시간을 확인하세요.")
        parts: list[Path] = []
        stamp = time.strftime("%Y%m%d_%H%M%S")
        total = end - start
        # Encode to a scratch folder. Only fully assembled valid WebP files are published.
        with tempfile.TemporaryDirectory(prefix="motionwebp_") as scratch:
            temp_dir = Path(scratch)
            try:
                if opt.split_mode == "개수":
                    count = max(2, min(20, int(opt.split_count)))
                    segments = [
                        (start + total * i // count, start + total * (i + 1) // count)
                        for i in range(count)
                    ]
                    if any(b - a < max(30, round(1000 / opt.fps)) for a, b in segments):
                        raise ValueError("영상 구간이 너무 짧습니다. 분할 개수를 줄이세요.")
                elif opt.split_mode == "없음":
                    segments = [(start, end)]
                else:
                    segments = []
                if opt.split_mode != "용량":
                    for index, (a, b) in enumerate(segments, 1):
                        self.check_cancel()
                        self.on_status(f"{index}/{len(segments)} 변환 중")
                        temp = temp_dir / f"part_{index:03d}.webp"
                        self.encode(video, opt, a, b, temp,
                                    (index - 1) * 98 // len(segments), 98 // len(segments))
                        name = f"MotionWebP_{stamp}" + (f"_{index:03d}" if len(segments) > 1 else "") + ".webp"
                        dest = unique_path(folder / name)
                        shutil.copy2(temp, dest)
                        parts.append(dest)
                else:
                    target = max(1, min(100, int(opt.target_mb))) * 1024 * 1024
                    next_ms = start
                    estimate = max(500, opt.target_mb * 700)
                    while next_ms < end:
                        self.check_cancel()
                        index = len(parts) + 1
                        if index > 100:
                            raise RuntimeError("분할 파일 수가 100개를 넘었습니다.")
                        remaining = end - next_ms
                        min_duration = min(remaining, max(90, round(1000 / opt.fps)))
                        candidate = min(remaining, max(min_duration, estimate))
                        for attempt in range(4):
                            self.check_cancel()
                            self.on_status(f"파트 {index} / 목표 {opt.target_mb}MB (시도 {attempt + 1})")
                            temp = temp_dir / f"part_{index:03d}_{attempt}.webp"
                            size = self.encode(
                                video, opt, next_ms, next_ms + candidate, temp,
                                int((next_ms - start) / total * 95), 2,
                            )
                            ratio = target / max(1, size)
                            if attempt < 3 and candidate > min_duration and size > target * 1.05:
                                candidate = max(min_duration, min(remaining, int(candidate * ratio * 0.91)))
                                temp.unlink(missing_ok=True)
                                continue
                            if attempt < 2 and candidate < remaining and size < target * 0.65:
                                candidate = min(remaining, max(candidate + min_duration, int(candidate * ratio * 0.9)))
                                temp.unlink(missing_ok=True)
                                continue
                            break
                        dest = unique_path(folder / f"MotionWebP_{stamp}_{index:03d}.webp")
                        shutil.copy2(temp, dest)
                        parts.append(dest)
                        next_ms += candidate
                        estimate = max(min_duration, int(candidate * target / max(1, size) * 0.88))
                        self.on_progress(min(99, int((next_ms - start) / total * 99)))
                self.on_progress(100)
                self.on_status(f"완료: {len(parts)}개 저장")
                return parts
            except BaseException:
                for path in parts:
                    path.unlink(missing_ok=True)
                raise


def unique_path(path: Path) -> Path:
    if not path.exists():
        return path
    for n in range(1, 10000):
        candidate = path.with_name(f"{path.stem}_{n:03d}{path.suffix}")
        if not candidate.exists():
            return candidate
    raise RuntimeError("출력 파일명 생성 실패")
