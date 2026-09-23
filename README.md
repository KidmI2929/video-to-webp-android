# Video → WebP Android

휴대폰에서 영상을 선택해 **Animated WebP**로 변환하는 Android 앱입니다.

## v0.5 속도 최적화

v0.5는 Termux가 빠른 핵심 이유와 비슷하게, 변환을 네이티브 FFmpeg 파이프라인 안에서 처리합니다.

- Bitmap 프레임 반복 추출 파이프라인 제거
- FFmpeg 내부에서 **디코딩 → FPS 변환 → 스케일링 → libwebp 인코딩**을 연결
- Android SAF URI를 FFmpeg가 직접 읽고/써서 원본 영상 전체 캐시 복사를 제거
- 입력 전에 `-ss`를 적용해 구간 시작점 탐색 비용 절감
- ARM64 / NEON 속도 최적화
- MediaCodec 지원 포함
- `libwebp`를 직접 활성화한 커스텀 FFmpegKit AAR 사용
- 빠름 / 균형 / 최대 압축 3단계, 기본값은 **빠름**
- 변환 소요 시간 표시

GitHub Actions는 FFmpegKit 소스에서 ARM64용 엔진을 직접 빌드하며,
AAR과 최종 APK 양쪽에서 `--enable-libwebp`가 실제 포함됐는지 검사한 뒤에만 APK를 업로드합니다.

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

## 커스텀 FFmpeg 빌드

현재 빌드는 다음 핵심 옵션을 포함합니다.

- `--enable-libwebp`
- `--enable-mediacodec`
- ARM64 `--enable-neon`
- FFmpegKit `--speed` 빌드
- Android API 29 대상

Termux의 소스 코드를 앱에 복사한 것은 아닙니다. 공개된 Termux FFmpeg 빌드 설정을 참고해,
FFmpeg/FFmpegKit/libwebp의 공개 소스로 독립적인 Android 네이티브 빌드를 생성합니다.

## 지원

- Android 10(API 29) 이상
- arm64-v8a

## APK 받기

GitHub **Actions → Build Android APK → 최신 성공 빌드 → Artifacts → VideoToWebP-v0.5-fast-apk** 순서로 받습니다.
ZIP을 풀어 `app-debug.apk`를 설치하면 됩니다.

## 오픈소스

FFmpegKit/FFmpeg와 libwebp를 사용합니다. 자세한 출처와 라이선스 정보는
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)를 확인하세요.
