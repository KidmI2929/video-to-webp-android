# Third-party licenses and attribution

Motion WebP's Python application source is provided in this repository.
This Windows portable package bundles additional third-party software:

- **FFmpeg / ffprobe**: Windows release essentials static binaries from
  https://www.gyan.dev/ffmpeg/builds/ . Those builds are GPLv3.
  FFmpeg's project and corresponding source releases:
  https://ffmpeg.org/ and https://ffmpeg.org/download.html .
  The original binary distribution includes its license and build configuration.
  The Windows build workflow verifies that its FFmpeg contains `libwebp`.
- **Qt / PySide6**: https://www.qt.io/licensing/ and
  https://doc.qt.io/qtforpython-6/licenses.html . Bundled libraries are
  covered by their applicable open-source/commercial licenses. Qt
  shared libraries are shipped in their ordinary dynamically linked form.
- **libwebp**: https://chromium.googlesource.com/webm/libwebp/
- **PyInstaller bootloader**: https://pyinstaller.org/en/stable/license.html

This project does not claim ownership of the bundled libraries. Review the
distributions' licenses if you redistribute the Windows build.
