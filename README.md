# Motion WebP — Video → Animated WebP

Android에서 영상을 확인하고 원하는 구간을 잘라 **Animated WebP**로 빠르게 변환하는 로컬 앱입니다.

## v0.6

### 새 UI / 플레이어
- Material 3 기반 새 디자인, 라이트/다크 모드 대응
- AndroidX Media3 / ExoPlayer 기반 영상 미리보기
- 재생 / 일시정지, ±1초 이동
- 선택한 IN/OUT 구간만 반복 확인
- 트림 RangeSlider를 움직이면 플레이어가 해당 위치로 즉시 seek
- 현재 선택 구간의 재생 진행률 표시

### 저장 폴더
- Android 시스템 폴더 선택기(SAF) 사용
- 사용자가 원하는 폴더를 선택해 WebP 저장
- 선택한 폴더 권한을 유지해 다음 실행에서도 재사용
- 기본값은 Pictures/VideoToWebP

### 분할 생성
- 분할 없음
- 개수 기준: 선택 구간을 2~20개의 동일 시간 파트로 생성
- 용량 기준: 파일당 목표 MB를 기준으로 구간 길이를 자동 조정
- 용량 분할은 WebP를 정상적으로 끝까지 인코딩한 뒤 실제 파일 크기를 측정하고 재조정하므로 단순 바이트 절단 방식처럼 깨진 WebP를 만들지 않음
- 콘텐츠 복잡도와 WebP 특성 때문에 목표 MB는 정확한 상한이 아니라 근사 목표값

### 변환
- MP4 / MOV / WebM 등 FFmpeg가 읽을 수 있는 영상
- 원본 / 1080 / 720 / 480 / 320 최대 변 해상도
- FPS 5–30
- 품질 10–100
- Lossy / Lossless
- 무한 반복 / 1회 재생
- 빠름 / 균형 / 최대 압축
- 진행률 / 취소
- 결과 Animated WebP 미리보기
- 여러 파트 결과 목록 및 전체 공유
- 서버 업로드 없이 기기 내부 처리

## 엔진

GitHub Actions에서 앱 전용 ARM64 FFmpegKit AAR을 직접 빌드합니다.

- --enable-libwebp
- --enable-mediacodec
- ARM64 / NEON
- FFmpegKit --speed 빌드
- SAF URI 직접 입출력
- libc++_shared.so 포함 검증
- APK 내부 libwebp 설정 재검증

## 지원

- Android 10 (API 29) 이상
- arm64-v8a
- compileSdk 36 / targetSdk 35

## APK

GitHub **Actions → Build Android APK → 최신 성공 빌드 → Artifacts → VideoToWebP-v0.6-apk** 에서 받을 수 있습니다.
