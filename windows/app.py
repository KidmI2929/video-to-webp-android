"""Motion WebP — Windows desktop GUI (PySide6)."""
from __future__ import annotations

import json
import os
import sys
import traceback
from dataclasses import asdict
from pathlib import Path

from PySide6.QtCore import QObject, Qt, QThread, QTimer, QUrl, Signal, Slot, QRectF
from PySide6.QtGui import QColor, QImage, QPainter, QPen, QPixmap
from PySide6.QtMultimedia import QAudioOutput, QMediaPlayer, QVideoSink
from PySide6.QtWidgets import (
    QApplication, QCheckBox, QComboBox, QDoubleSpinBox, QFileDialog,
    QFormLayout, QFrame, QGridLayout, QHBoxLayout, QLabel, QLineEdit,
    QListWidget, QMainWindow, QMessageBox, QProgressBar, QPushButton,
    QScrollArea, QSlider, QSpinBox, QSplitter, QVBoxLayout, QWidget,
)

from core import Converter, Options, RATIOS, VideoInfo, crop_geometry, probe, timecode


def config_path() -> Path:
    base = Path(os.environ.get("APPDATA", str(Path.home()))) / "MotionWebP"
    base.mkdir(parents=True, exist_ok=True)
    return base / "preferences.json"


def load_settings() -> dict:
    try:
        value = json.loads(config_path().read_text(encoding="utf-8"))
        return value if isinstance(value, dict) else {}
    except (OSError, ValueError):
        return {}


DARK = """
QWidget {background:#10141c; color:#e5eaf4; font-family:'Segoe UI';font-size:12px}
QFrame#card {background:#1b2330;border:1px solid #303d50;border-radius:14px}
QPushButton {background:#294578;color:white;padding:9px 12px;border:1px solid #41649c;border-radius:9px}
QPushButton:hover {background:#365992}
QPushButton:disabled {background:#313744;color:#8e97a8}
QLineEdit,QComboBox,QSpinBox,QDoubleSpinBox,QListWidget {
 background:#17202c;border:1px solid #425168;border-radius:7px;padding:6px;color:#e5eaf4
}
QProgressBar {background:#232f42;border:0;border-radius:7px;height:12px}
QProgressBar::chunk {background:#73b4e8;border-radius:7px}
QSlider::groove:horizontal {height:6px;background:#3d516e;border-radius:3px}
QSlider::handle:horizontal {width:16px;background:#89c5ff;border-radius:8px;margin:-5px 0}
QScrollArea {border:0}
"""
LIGHT = """
QWidget {background:#f6f8fc;color:#182539;font-family:'Segoe UI';font-size:12px}
QFrame#card {background:#fff;border:1px solid #dae1ed;border-radius:14px}
QPushButton {background:#315caa;color:white;padding:9px 12px;border:1px solid #5373ae;border-radius:9px}
QPushButton:hover {background:#426cba}
QPushButton:disabled {background:#e5e8ef;color:#87909c}
QLineEdit,QComboBox,QSpinBox,QDoubleSpinBox,QListWidget {
 background:#fff;border:1px solid #bbc7d9;border-radius:7px;padding:6px;color:#182539
}
QProgressBar {background:#dce3ef;border:0;border-radius:7px;height:12px}
QProgressBar::chunk {background:#386fbb;border-radius:7px}
QSlider::groove:horizontal {height:6px;background:#c7d3e5;border-radius:3px}
QSlider::handle:horizontal {width:16px;background:#386fbb;border-radius:8px;margin:-5px 0}
QScrollArea {border:0}
"""


class Preview(QWidget):
    focus_changed = Signal(float, float)

    def __init__(self):
        super().__init__()
        self.image = QImage()
        self.ratio = "원본"
        self.fx = self.fy = 0.5
        self.setMinimumSize(330, 225)
        self.setMouseTracking(True)

    def set_frame(self, frame):
        if frame.isValid():
            img = frame.toImage()
            if not img.isNull():
                if img.width() > 1280:
                    img = img.scaledToWidth(1280, Qt.TransformationMode.FastTransformation)
                self.image = img
                self.update()

    def image_rect(self) -> QRectF:
        if self.image.isNull():
            return QRectF()
        iw, ih = self.image.width(), self.image.height()
        scale = min(self.width() / iw, self.height() / ih)
        w, h = iw * scale, ih * scale
        return QRectF((self.width() - w) / 2, (self.height() - h) / 2, w, h)

    def paintEvent(self, event):
        painter = QPainter(self)
        painter.fillRect(self.rect(), QColor("#080b11"))
        image_rect = self.image_rect()
        if image_rect.isEmpty():
            painter.setPen(QColor("#a0abc2"))
            painter.drawText(self.rect(), Qt.AlignmentFlag.AlignCenter, "영상을 선택하세요")
            painter.end()
            return
        painter.drawImage(image_rect, self.image)
        target = RATIOS.get(self.ratio)
        if target:
            ratio = image_rect.width() / image_rect.height()
            if ratio > target:
                ch, cw = image_rect.height(), image_rect.height() * target
            else:
                cw, ch = image_rect.width(), image_rect.width() / target
            cx = image_rect.left() + self.fx * image_rect.width()
            cy = image_rect.top() + self.fy * image_rect.height()
            left = max(image_rect.left(), min(image_rect.right() - cw, cx - cw / 2))
            top = max(image_rect.top(), min(image_rect.bottom() - ch, cy - ch / 2))
            crop_rect = QRectF(left, top, cw, ch)
            pen = QPen(QColor("#93cdfb"), 2)
            painter.setPen(pen)
            painter.drawRect(crop_rect)
            painter.setPen(QPen(QColor("#f7c96b"), 2))
            painter.drawLine(int(cx - 10), int(cy), int(cx + 10), int(cy))
            painter.drawLine(int(cx), int(cy - 10), int(cx), int(cy + 10))
        painter.end()

    def mousePressEvent(self, event):
        rect = self.image_rect()
        if self.ratio != "원본" and rect.contains(event.position()):
            self.focus_changed.emit(
                max(0.0, min(1.0, (event.position().x() - rect.left()) / rect.width())),
                max(0.0, min(1.0, (event.position().y() - rect.top()) / rect.height())),
            )


class EncodeWorker(QObject):
    progress = Signal(int)
    status = Signal(str)
    done = Signal(list)
    failed = Signal(str)
    finished = Signal()

    def __init__(self, info: VideoInfo, options: Options, folder: str):
        super().__init__()
        self.info, self.options, self.folder = info, options, folder
        self.converter = Converter(self.status.emit, self.progress.emit)

    @Slot()
    def run(self):
        try:
            paths = self.converter.run(self.info, self.options, Path(self.folder))
            self.done.emit([str(path) for path in paths])
        except Exception as exc:
            if self.converter.cancel_event.is_set():
                self.status.emit("변환이 취소되었습니다.")
            else:
                self.failed.emit(str(exc))
        finally:
            self.finished.emit()


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.settings = load_settings()
        self.info: VideoInfo | None = None
        self.output_paths: list[str] = []
        self.busy = False
        self.thread: QThread | None = None
        self.worker: EncodeWorker | None = None
        self._setting_up = True
        self.setWindowTitle("Motion WebP — Windows")
        self.resize(1260, 900)
        self.setMinimumSize(850, 650)
        self.player = QMediaPlayer(self)
        self.audio = QAudioOutput(self)
        self.player.setAudioOutput(self.audio)
        self.sink = QVideoSink(self)
        self.player.setVideoSink(self.sink)
        self.build_ui()
        self.connect_signals()
        self.restore_settings()
        self._setting_up = False
        self.apply_theme()
        self.timer = QTimer(self)
        self.timer.timeout.connect(self.refresh_time)
        self.timer.start(60)

    def card(self, title: str):
        frame = QFrame()
        frame.setObjectName("card")
        lay = QVBoxLayout(frame)
        lay.setContentsMargins(16, 14, 16, 16)
        lay.setSpacing(10)
        label = QLabel(title)
        label.setStyleSheet("font-size:16px;font-weight:700;")
        lay.addWidget(label)
        return frame, lay

    def row(self, *widgets):
        row = QHBoxLayout()
        for widget in widgets:
            row.addWidget(widget)
        return row

    def combo(self, values: list[str]):
        cb = QComboBox()
        cb.addItems(values)
        return cb

    def build_ui(self):
        splitter = QSplitter(Qt.Orientation.Horizontal)
        self.setCentralWidget(splitter)

        left = QWidget()
        ll = QVBoxLayout(left)
        ll.setContentsMargins(16, 16, 10, 16)
        ll.setSpacing(12)
        top = QLabel("MOTION  WEBP")
        top.setStyleSheet("font-size:25px;font-weight:800;letter-spacing:2px")
        ll.addWidget(top)

        video_card, vl = self.card("영상 미리보기 · 탭해서 크롭 포커스 지정")
        self.preview = Preview()
        vl.addWidget(self.preview, stretch=1)
        self.file_label = QLabel("선택된 영상 없음")
        self.file_label.setWordWrap(True)
        vl.addWidget(self.file_label)
        open_btn = QPushButton("영상 열기…")
        open_btn.clicked.connect(self.pick_video)
        vl.addWidget(open_btn)
        ll.addWidget(video_card, stretch=3)

        timeline_card, tl = self.card("시간 · 프레임")
        self.clock = QLabel("00:00:00.000 / 00:00:00.000 · Frame 0")
        self.clock.setStyleSheet("font-family:Consolas;font-size:13px;font-weight:600")
        tl.addWidget(self.clock)
        self.seek_slider = QSlider(Qt.Orientation.Horizontal)
        self.seek_slider.setRange(0, 1000)
        tl.addWidget(self.seek_slider)
        self.play_btn = QPushButton("▶ 재생")
        self.previous_btn = QPushButton("−1F")
        self.next_btn = QPushButton("+1F")
        back = QPushButton("−1초")
        forward = QPushButton("+1초")
        self.previous_btn.clicked.connect(lambda: self.seek_by_frame(-1))
        self.next_btn.clicked.connect(lambda: self.seek_by_frame(1))
        back.clicked.connect(lambda: self.seek_by(-1000))
        forward.clicked.connect(lambda: self.seek_by(1000))
        self.play_btn.clicked.connect(self.toggle_play)
        tl.addLayout(self.row(back, self.previous_btn, self.play_btn, self.next_btn, forward))
        self.in_time = QDoubleSpinBox()
        self.out_time = QDoubleSpinBox()
        for spin in (self.in_time, self.out_time):
            spin.setRange(0, 720000)
            spin.setDecimals(3)
            spin.setSingleStep(0.033)
            spin.setSuffix(" 초")
        mark_in = QPushButton("현재 → IN")
        mark_out = QPushButton("현재 → OUT")
        mark_in.clicked.connect(lambda: self.in_time.setValue(self.player.position() / 1000))
        mark_out.clicked.connect(lambda: self.out_time.setValue(self.player.position() / 1000))
        tl.addLayout(self.row(QLabel("IN"), self.in_time, mark_in))
        tl.addLayout(self.row(QLabel("OUT"), self.out_time, mark_out))
        self.range_label = QLabel("선택 구간 00:00:00.000")
        tl.addWidget(self.range_label)
        ll.addWidget(timeline_card, stretch=1)

        right_scroll = QScrollArea()
        right_scroll.setWidgetResizable(True)
        right = QWidget()
        rl = QVBoxLayout(right)
        rl.setContentsMargins(8, 16, 16, 16)
        rl.setSpacing(12)
        right_scroll.setWidget(right)
        splitter.addWidget(left)
        splitter.addWidget(right_scroll)
        splitter.setSizes([740, 520])

        appearance, al = self.card("화면 스타일")
        self.theme = self.combo(["시스템", "다크", "라이트"])
        al.addWidget(self.theme)
        rl.addWidget(appearance)

        quality_card, ql = self.card("출력 프리셋 · 변경 즉시 자동 저장")
        self.quick = self.combo(["현재 설정", "터보 소형", "균형 720p", "고화질 1080p"])
        self.quick.activated.connect(self.apply_quick_preset)
        ql.addWidget(self.quick)
        form = QFormLayout()
        self.resolution = self.combo(["원본", "1080", "720", "480", "320"])
        self.fps = QSpinBox()
        self.fps.setRange(5, 30)
        self.quality = QSpinBox()
        self.quality.setRange(1, 100)
        self.speed = self.combo(["터보", "빠름", "균형", "최대 압축"])
        self.lossless = QCheckBox("무손실 WebP")
        self.loop = QCheckBox("무한 반복")
        form.addRow("최대 해상도", self.resolution)
        form.addRow("출력 FPS", self.fps)
        form.addRow("화질", self.quality)
        form.addRow("인코딩", self.speed)
        form.addRow(self.lossless)
        form.addRow(self.loop)
        ql.addLayout(form)
        rl.addWidget(quality_card)

        crop_card, cl = self.card("화면비 · 크롭 · 포커스")
        self.aspect = self.combo(list(RATIOS))
        cl.addWidget(self.aspect)
        cl.addWidget(QLabel("미리보기에서 원하는 얼굴·몸·대상을 탭하면 크롭 중심이 이동합니다."))
        self.focus_presets = self.combo(["중앙", "얼굴 쪽", "상체", "전신"])
        self.focus_presets.activated.connect(self.apply_focus_preset)
        cl.addWidget(self.focus_presets)
        self.fx = QDoubleSpinBox()
        self.fy = QDoubleSpinBox()
        for spin in (self.fx, self.fy):
            spin.setRange(0.0, 1.0)
            spin.setDecimals(3)
            spin.setSingleStep(0.02)
        cl.addLayout(self.row(QLabel("X"), self.fx, QLabel("Y"), self.fy))
        cl.addWidget(QLabel("고정 포커스: 인물을 자동 추적하지는 않습니다."))
        rl.addWidget(crop_card)

        split_card, sl = self.card("분할 생성")
        self.split_mode = self.combo(["없음", "개수", "용량"])
        self.split_count = QSpinBox()
        self.split_count.setRange(2, 20)
        self.target_mb = QSpinBox()
        self.target_mb.setRange(1, 100)
        split_form = QFormLayout()
        split_form.addRow("분할 기준", self.split_mode)
        split_form.addRow("생성 개수", self.split_count)
        split_form.addRow("목표 MB / 파일", self.target_mb)
        sl.addLayout(split_form)
        sl.addWidget(QLabel("용량 분할은 완성 파일을 측정해 조정하므로 목표 용량과 약간 다를 수 있습니다."))
        rl.addWidget(split_card)

        save_card, svl = self.card("저장 폴더")
        self.folder = QLineEdit()
        self.folder.setReadOnly(True)
        folder_btn = QPushButton("폴더 선택…")
        folder_btn.clicked.connect(self.choose_folder)
        svl.addLayout(self.row(self.folder, folder_btn))
        rl.addWidget(save_card)

        export_card, el = self.card("변환")
        self.status = QLabel("영상 파일을 선택하세요.")
        self.status.setWordWrap(True)
        self.progress = QProgressBar()
        self.progress.setRange(0, 100)
        self.export_btn = QPushButton("Animated WebP 만들기")
        self.export_btn.clicked.connect(self.start_export)
        self.cancel_btn = QPushButton("취소")
        self.cancel_btn.clicked.connect(self.cancel_export)
        self.cancel_btn.setEnabled(False)
        el.addWidget(self.status)
        el.addWidget(self.progress)
        el.addLayout(self.row(self.export_btn, self.cancel_btn))
        self.results = QListWidget()
        self.results.setMinimumHeight(80)
        el.addWidget(self.results)
        results_open = QPushButton("선택 결과 열기")
        results_folder = QPushButton("저장 폴더 열기")
        results_open.clicked.connect(self.open_result)
        results_folder.clicked.connect(self.open_folder)
        el.addLayout(self.row(results_open, results_folder))
        rl.addWidget(export_card)
        rl.addStretch(1)

    def connect_signals(self):
        self.sink.videoFrameChanged.connect(self.preview.set_frame)
        self.player.playbackStateChanged.connect(
            lambda _: self.play_btn.setText("Ⅱ 일시정지" if self.player.isPlaying() else "▶ 재생")
        )
        self.seek_slider.sliderMoved.connect(self.seek_slider_moved)
        self.in_time.valueChanged.connect(self.refresh_range)
        self.out_time.valueChanged.connect(self.refresh_range)
        self.aspect.currentTextChanged.connect(self.update_preview_focus)
        self.fx.valueChanged.connect(self.update_preview_focus)
        self.fy.valueChanged.connect(self.update_preview_focus)
        self.preview.focus_changed.connect(self.set_focus)
        self.theme.currentTextChanged.connect(self.apply_theme)
        inputs = [
            self.resolution, self.fps, self.quality, self.speed,
            self.lossless, self.loop, self.aspect, self.fx, self.fy,
            self.split_mode, self.split_count, self.target_mb, self.theme,
        ]
        for control in inputs:
            if isinstance(control, QComboBox):
                control.currentTextChanged.connect(self.save_settings)
            elif isinstance(control, QCheckBox):
                control.toggled.connect(self.save_settings)
            else:
                control.valueChanged.connect(self.save_settings)

    def restore_settings(self):
        s = self.settings
        for control, key, default in [
            (self.theme, "theme", "시스템"),
            (self.resolution, "resolution", "720"),
            (self.speed, "speed", "빠름"),
            (self.aspect, "aspect", "원본"),
            (self.split_mode, "split_mode", "없음"),
        ]:
            text = str(s.get(key, default))
            control.setCurrentText(text if control.findText(text) >= 0 else default)
        for control, key, default in [
            (self.fps, "fps", 15), (self.quality, "quality", 80),
            (self.split_count, "split_count", 2), (self.target_mb, "target_mb", 8),
            (self.fx, "fx", 0.5), (self.fy, "fy", 0.5),
        ]:
            control.setValue(s.get(key, default))
        self.lossless.setChecked(bool(s.get("lossless", False)))
        self.loop.setChecked(bool(s.get("loop", True)))
        self.folder.setText(str(s.get("folder", str(Path.home() / "Pictures" / "MotionWebP"))))
        self.update_preview_focus()

    def save_settings(self, *_):
        if self._setting_up:
            return
        payload = {
            "theme": self.theme.currentText(),
            "resolution": self.resolution.currentText(),
            "fps": self.fps.value(), "quality": self.quality.value(),
            "speed": self.speed.currentText(), "lossless": self.lossless.isChecked(),
            "loop": self.loop.isChecked(), "aspect": self.aspect.currentText(),
            "fx": self.fx.value(), "fy": self.fy.value(),
            "split_mode": self.split_mode.currentText(),
            "split_count": self.split_count.value(),
            "target_mb": self.target_mb.value(), "folder": self.folder.text(),
        }
        try:
            config_path().write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        except OSError:
            pass

    def apply_theme(self, *_):
        mode = self.theme.currentText()
        system_dark = QApplication.styleHints().colorScheme() == Qt.ColorScheme.Dark
        dark = mode == "다크" or (mode == "시스템" and system_dark)
        QApplication.instance().setStyleSheet(DARK if dark else LIGHT)

    def apply_quick_preset(self, index: int):
        presets = {
            1: ("480", 12, 72, "터보"),
            2: ("720", 15, 80, "빠름"),
            3: ("1080", 20, 88, "균형"),
        }
        preset = presets.get(index)
        if preset:
            self.resolution.setCurrentText(preset[0])
            self.fps.setValue(preset[1])
            self.quality.setValue(preset[2])
            self.speed.setCurrentText(preset[3])
            self.lossless.setChecked(False)
        self.quick.setCurrentIndex(0)

    def apply_focus_preset(self, index: int):
        points = [(0.5, 0.5), (0.5, 0.28), (0.5, 0.40), (0.5, 0.55)]
        self.set_focus(*points[index])

    def set_focus(self, x: float, y: float):
        self.fx.setValue(x)
        self.fy.setValue(y)
        self.update_preview_focus()

    def update_preview_focus(self, *_):
        self.preview.ratio = self.aspect.currentText()
        self.preview.fx = self.fx.value()
        self.preview.fy = self.fy.value()
        self.preview.update()

    def pick_video(self):
        path, _ = QFileDialog.getOpenFileName(
            self, "영상 선택", "", "동영상 (*.mp4 *.mov *.mkv *.webm *.avi *.m4v);;전체 파일 (*)"
        )
        if not path:
            return
        try:
            info = probe(path)
        except Exception as exc:
            QMessageBox.critical(self, "파일 오류", str(exc))
            return
        self.info = info
        self.file_label.setText(
            f"{Path(path).name} · {info.width}×{info.height} · {info.fps:.3f} FPS · {timecode(info.duration_ms)}"
        )
        self.in_time.setValue(0)
        self.out_time.setValue(info.duration_ms / 1000)
        self.player.stop()
        self.player.setSource(QUrl.fromLocalFile(path))
        self.player.pause()
        self.status.setText("설정을 확인한 후 변환하세요.")
        self.output_paths.clear()
        self.results.clear()

    def choose_folder(self):
        path = QFileDialog.getExistingDirectory(self, "저장 폴더 선택", self.folder.text())
        if path:
            self.folder.setText(path)
            self.save_settings()

    def refresh_time(self):
        if not self.info:
            return
        pos = self.player.position()
        frame = round(pos / 1000 * self.info.fps)
        self.clock.setText(
            f"{timecode(pos)} / {timecode(self.info.duration_ms)} · Frame {frame} · {self.info.fps:.3f} FPS"
        )
        if not self.seek_slider.isSliderDown():
            self.seek_slider.setValue(min(1000, round(pos / self.info.duration_ms * 1000)))
        if self.player.isPlaying() and pos >= round(self.out_time.value() * 1000):
            self.player.setPosition(round(self.in_time.value() * 1000))

    def seek_slider_moved(self, value):
        if self.info:
            self.player.setPosition(round(value / 1000 * self.info.duration_ms))

    def seek_by(self, delta):
        if self.info:
            self.player.pause()
            self.player.setPosition(max(0, min(self.info.duration_ms, self.player.position() + delta)))

    def seek_by_frame(self, direction):
        if self.info:
            self.seek_by(direction * self.info.frame_ms)

    def toggle_play(self):
        if not self.info:
            return
        if self.player.isPlaying():
            self.player.pause()
        else:
            if self.player.position() >= round(self.out_time.value() * 1000):
                self.player.setPosition(round(self.in_time.value() * 1000))
            self.player.play()

    def refresh_range(self, *_):
        if not self.info:
            return
        start = round(self.in_time.value() * 1000)
        end = round(self.out_time.value() * 1000)
        self.range_label.setText(f"선택 구간 {timecode(max(0, end - start))}")

    def selected_options(self) -> Options:
        if not self.info:
            raise ValueError("먼저 영상을 선택하세요.")
        start = round(self.in_time.value() * 1000)
        end = round(self.out_time.value() * 1000)
        if not (0 <= start < end <= self.info.duration_ms):
            raise ValueError("IN/OUT 시간을 확인하세요. OUT은 IN보다 뒤여야 합니다.")
        return Options(
            start_ms=start, end_ms=end,
            width=0 if self.resolution.currentText() == "원본" else int(self.resolution.currentText()),
            fps=self.fps.value(), quality=self.quality.value(), speed=self.speed.currentText(),
            lossless=self.lossless.isChecked(), loop=self.loop.isChecked(),
            ratio=self.aspect.currentText(), focus_x=self.fx.value(), focus_y=self.fy.value(),
            split_mode=self.split_mode.currentText(), split_count=self.split_count.value(),
            target_mb=self.target_mb.value(),
        )

    def set_busy(self, busy: bool):
        self.busy = busy
        self.export_btn.setEnabled(not busy)
        self.cancel_btn.setEnabled(busy)
        self.in_time.setEnabled(not busy)
        self.out_time.setEnabled(not busy)

    def start_export(self):
        if self.busy:
            return
        try:
            opt = self.selected_options()
            folder = self.folder.text()
            if not folder:
                raise ValueError("저장 폴더를 선택하세요.")
        except Exception as exc:
            QMessageBox.warning(self, "설정 확인", str(exc))
            return
        self.player.pause()
        self.set_busy(True)
        self.progress.setValue(0)
        self.results.clear()
        self.output_paths.clear()
        self.thread = QThread(self)
        self.worker = EncodeWorker(self.info, opt, folder)
        self.worker.moveToThread(self.thread)
        self.thread.started.connect(self.worker.run)
        self.worker.progress.connect(self.progress.setValue)
        self.worker.status.connect(self.status.setText)
        self.worker.done.connect(self.export_done)
        self.worker.failed.connect(self.export_failed)
        self.worker.finished.connect(self.thread.quit)
        self.thread.finished.connect(self.worker.deleteLater)
        self.thread.finished.connect(self.thread.deleteLater)
        self.thread.finished.connect(lambda: self.set_busy(False))
        self.thread.start()

    def cancel_export(self):
        if self.worker:
            self.status.setText("취소 요청 중…")
            self.worker.converter.cancel()

    def export_done(self, paths):
        self.output_paths = paths
        for path in paths:
            self.results.addItem(f"{Path(path).name} · {Path(path).stat().st_size / 1048576:.2f} MB")
        self.status.setText(f"완료 · {len(paths)}개 파일 저장")

    def export_failed(self, detail):
        self.status.setText("변환 실패")
        QMessageBox.critical(self, "변환 오류", detail)

    def open_result(self):
        index = self.results.currentRow()
        if index < 0 and self.output_paths:
            index = 0
        if 0 <= index < len(self.output_paths):
            os.startfile(self.output_paths[index]) if os.name == "nt" else None

    def open_folder(self):
        folder = Path(self.folder.text())
        if folder.is_dir() and os.name == "nt":
            os.startfile(str(folder))

    def closeEvent(self, event):
        if self.busy:
            reply = QMessageBox.question(
                self, "변환 중", "변환을 취소하고 종료할까요?",
                QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
            )
            if reply != QMessageBox.StandardButton.Yes:
                event.ignore()
                return
            self.cancel_export()
            if self.thread and not self.thread.wait(6000):
                event.ignore()
                self.status.setText("변환 프로세스가 종료되는 중입니다.")
                return
        self.save_settings()
        self.player.stop()
        super().closeEvent(event)


def main():
    app = QApplication(sys.argv)
    app.setApplicationName("Motion WebP")
    window = MainWindow()
    window.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
