# Video → WebP Android

휴대폰에서 영상을 선택해 **Animated WebP**로 빠르게 변환하는 Android 앱입니다.

## v0.5 Fast

v0.5는 Termux의 FFmpeg 패키지 구성을 참고해, 앱 전용 **ARM64 네이티브 FFmpeg**를 GitHub Actions에서 직접 빌드합니다.

- FFmpeg 네이티브 파이프라인: 디코딩 → FPS 변환 → 스케일링 → libwebp 인코딩
- `--enable-libwebp`
- `--enable-mediacodec`
- ARM64 NEON / ASM 최적화
- FFmpegKit `--speed` 빌드
- Android SAF URI 직접 입출력: 원본 영상을 앱 캐시에 통째로 복사하지 않음
- Bitmap 프레임 반복 추출 방식 제거
- 빠름 / 균형 / 최대 압축 3단계
- 기본값은 **빠름**
- 변환 완료 후 실제 소요 시간 표시

GitHub Actions는 커스텀 FFmpeg AAR을 만든 뒤 `libavcodec.so`의 빌드 설정에서
`--enable-libwebp`를 확인하고, 완성 APK 내부에서도 다시 확인한 뒤에만 APK를 업로드합니다.

## 기능

- MP4 / MOV / WebM 등 FFmpeg가 읽을 수 있는 영상 선택
- 영상 미리보기
- 시작 / 끝 구간 자르기
- 원본 / 1080 / 720 / 480 / 320 최대 변 해상도
- FPS 5–30
- 품질 10–100
- Lossy / Lossless Animated WebP
- 무한 반복 / 1회 재생
- 빠름 / 균형 / 최대 압축
- 진행률 표시 / 변환 취소
- 실제 변환 소요 시간 표시
- 변환 결과 미리보기
- `Pictures/VideoToWebP` 자동 저장
- Android 공유
- 서버 업로드 없이 기기 내부 처리

## 지원

- Android 10 (API 29) 이상
- arm64-v8a

## 빌드 구조

앱 자체는 Kotlin + Jetpack Compose입니다.
FFmpeg / FFmpegKit / libwebp는 오픈소스 구성요소이며, 이 프로젝트의 자동 빌드는
FFmpegKit maintained 소스에서 필요한 기능만 활성화해 커스텀 AAR을 생성합니다.

Termux 앱이나 Termux 코드를 앱에 복사한 것이 아니라,
Termux의 공개 FFmpeg 빌드 설정에서 확인할 수 있는 핵심 최적화 방향을 참고했습니다.

## APK 받기

GitHub **Actions → Build Android APK → 성공 빌드 → Artifacts → VideoToWebP-v0.5-fast-apk** 순서로 받습니다.
ZIP을 풀고 `app-debug.apk`를 설치하면 됩니다.
