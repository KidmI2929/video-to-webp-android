# Third-party notices

This application uses open-source multimedia components. The repository's GitHub Actions workflow
builds the native multimedia engine from public source.

## FFmpegKit maintained fork

Source: https://github.com/ffmpegkit-maintained/ffmpeg

The Android wrapper and related FFmpegKit components are distributed under the GNU Lesser General
Public License as described by the upstream project. The custom build used by this app is produced
by the workflow in `.github/workflows/build-apk.yml`.

## FFmpeg

Source: https://ffmpeg.org/ and https://github.com/FFmpeg/FFmpeg

The custom configuration used by this app does not enable FFmpeg's GPL option. It enables libwebp,
Android MediaCodec support, shared libraries, and ARM64/NEON optimizations.

## libwebp

Source: https://chromium.googlesource.com/webm/libwebp/ and https://github.com/webmproject/libwebp

libwebp is the WebP codec library used for Animated WebP encoding.

## Termux

Reference source: https://github.com/termux/termux-packages

The Termux FFmpeg package build script was consulted as a public reference for Android FFmpeg build
configuration and performance-oriented features. Termux source code is not bundled into this app.
