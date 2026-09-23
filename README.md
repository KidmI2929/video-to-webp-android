# Video → WebP Android

휴대폰에서 영상을 선택해 **Animated WebP**로 변환하는 Android 앱입니다.

## v0.4 속도 최적화

v0.4는 Termux의 FFmpeg 처리 방식을 참고해 변환 구조를 바꿨습니다.

- Bitmap을 한 장씩 추출/전달하던 v0.3 파이프라인 제거
- FFmpeg 내부에서 **디코딩 → FPS 변환 → 스케일링 → libwebp 인코딩**을 한 번에 처리
- Android SAF URI를 FFmpeg가 직접 읽고 써서 원본 영상의 앱 캐시 복사 제거
- `-ss`를 입력 전에 적용해 구간 시작점 탐색 비용 절감
- ARM64 네이티브 FFmpeg 사용
- 빠름 / 균형 / 최대 압축 3단계
- 결과 화면에 실제 변환 소요 시간 표시

Termux의 현재 ffmpeg 패키지도 Android에서 libwebp와 MediaCodec 등을 활성화한 네이티브 FFmpeg를 사용합니다.
앱은 Termux 코드를 복사하지 않고 같은 핵심 구조인 네이티브 FFmpeg 파이프라인을 사용합니다.

## 기능

- MP4/MOV/WebM 등 FFmpeg/Android가 읽을 수 있는 영상 선택
- 영상 미리보기
- 시작/끝 구간 자르기
- 원본 / 1080 / 720 / 480 / 320 최대 변 해상도
- FPS 5–30
- 품질 10–100
- Lossy / Lossless WebP
- 무한 반복 / 1회 재생
- 빠름 / 균형 / 최대 압축
- 변환 진행률 / 취소
- 변환 소요 시간 표시
- 결과 Animated WebP 미리보기
- Pictures/VideoToWebP 자동 저장
- Android 공유
- 서버 업로드 없이 기기 내부 변환

## 엔진

`dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.8`을 사용합니다.
이 패키지의 full 빌드는 libwebp를 지원하며, 앱 시작 후 변환 시 실제 FFmpeg 빌드 설정에
libwebp가 없으면 변환을 시작하지 않고 오류를 표시하도록 방어 코드도 포함했습니다.

## 지원

- Android 10(API 29) 이상
- arm64-v8a

## APK 받기

GitHub **Actions → Build Android APK → 최신 성공 빌드 → Artifacts → VideoToWebP-debug-apk** 순서로 받습니다.
ZIP을 풀어 `app-debug.apk`를 설치하면 됩니다.
