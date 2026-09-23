# Video → WebP Android

휴대폰에서 영상을 선택해 **Animated WebP**로 변환하는 Android 앱입니다.

## 현재 기능

- MP4/MOV/WebM 등 Android가 열 수 있는 영상 선택
- 영상 미리보기
- 시작/끝 구간 자르기
- 원본 / 1080 / 720 / 480 / 320 최대 변 해상도
- FPS 5–30 조절
- 품질 10–100 조절
- Lossy / Lossless WebP
- 무한 반복 / 1회 재생
- 변환 진행률과 취소
- 결과 Animated WebP 미리보기
- Android 갤러리의 **Pictures/VideoToWebP** 폴더에 자동 저장
- Android 공유 시트로 바로 공유
- 서버 업로드 없이 기기 내부에서 변환

## 지원 기기

- Android 10(API 29) 이상
- 현재 FFmpeg 네이티브 패키지에 맞춰 **arm64-v8a** 기기를 대상으로 빌드합니다.
  대부분의 최근 Android 휴대폰은 arm64-v8a입니다.

## 휴대폰에서 APK 받기

1. 이 저장소의 **Actions** 탭을 엽니다.
2. **Build Android APK**의 최신 초록색 빌드를 엽니다.
3. 화면 아래 **Artifacts**에서 **VideoToWebP-debug-apk**를 받습니다.
4. ZIP 압축을 풀고 **app-debug.apk**를 실행해 설치합니다.
5. Android가 요청하면 해당 파일 앱/브라우저의 **알 수 없는 앱 설치 허용**을 켭니다.

## 변환 엔진

dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.8 의 FFmpeg/libwebp를 사용합니다.
앱은 선택한 원본을 앱 캐시에 임시 복사하고, 변환이 끝나면 임시 파일을 삭제합니다.
완성된 WebP만 MediaStore를 통해 Pictures/VideoToWebP에 저장합니다.

## 주의

Animated WebP는 긴 영상, 고해상도, 높은 FPS, 높은 품질에서 파일 크기와 변환 시간이 빠르게 증가합니다.
짧은 클립에는 480~720, 10~20 FPS, 품질 70~85를 시작점으로 권장합니다.
