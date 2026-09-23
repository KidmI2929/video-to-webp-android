# Motion WebP — Windows

Windows 10/11 x64 portable companion to the Android Motion WebP project.

## Features
- Korean desktop UI; system/dark/light appearance
- Qt video player with seek slider, detailed timecode, ±1 frame and ±1 second controls
- IN/OUT trim and looping selected region during preview
- Custom output folder, size/FPS/quality/lossless/loop/speed settings
- Output aspect ratios: source, 1:1, 4:5, 9:16, 3:4, 16:9
- Click the video preview to set a *fixed* crop focus point (face/body/other)
- Split by count or approximate target MB (complete WebP files only)
- Saved settings in %APPDATA%/MotionWebP/preferences.json
- Converts locally using bundled native ffmpeg.exe and ffprobe.exe

The focus point is **fixed in image coordinates**, not AI face tracking.
Source-frame indices and frame-step are calculated from FFprobe's reported
average FPS. For variable-frame-rate media the displayed frame index is an
estimate and the Windows player may seek to a nearby decodable frame.

## Download & run
GitHub → Actions → **Build Windows Motion WebP** → latest successful run →
**MotionWebP-Windows-portable**. Download the artifact, extract it, then open
`MotionWebP/MotionWebP.exe`. Do not move only the EXE; the `_internal`
folder contains the Qt and FFmpeg libraries.

Windows SmartScreen may show an unrecognized publisher warning because
the debug/portable builds are not code signed.

## Build from source
Run on Windows with Python 3.12 or newer:

```powershell
py -m pip install -r windows/requirements.txt
py windows/app.py
```

Without the portable bundle, `ffmpeg` and `ffprobe` must be available in
PATH or in `windows/bin`. The automated Windows build installs and tests them.

The FFmpeg release essentials binary is GPLv3. See LICENSE_NOTICES.md and the
corresponding upstream sources before redistributing.
