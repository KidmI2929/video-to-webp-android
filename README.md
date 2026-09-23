## v0.8

- 시스템 / 라이트 / 다크 테마 직접 선택 및 자동 저장
- 현재 출력 프리셋 자동 저장: 해상도, FPS, 품질, Lossless, 반복, 속도, 분할, 화면비, 포커스 좌표
- 원본 FPS 메타데이터 표시
- 현재 재생 위치를 밀리초 단위로 표시
- 원본 FPS 기반 프레임 번호 표시 및 ±1프레임 이동
- 출력 화면비: 원본 / 1:1 / 4:5 / 9:16 / 3:4 / 16:9
- 영상 화면을 탭해 고정 포커스 지점 지정
- 중앙 / 얼굴 / 상체 / 전신 빠른 포커스 위치
- FFmpeg crop 필터가 저장된 포커스 좌표를 기준으로 실제 출력 크롭 중심을 계산
- 기존 v0.7 터보 변환, 저장 폴더, 개수/용량 분할 기능 유지

> v0.8의 포커스는 영상 전체에 동일한 위치를 적용하는 **고정 포커스**입니다. 인물의 얼굴을 프레임마다 자동 추적하는 기능은 아직 포함하지 않습니다.


## v0.7

- Motion WebP 전용 adaptive 앱 아이콘 / Android 13 themed icon
- 터보 변환 모드: compression_level 0 + fast_bilinear
- Lossy WebP에 yuv420p 빠른 경로
- 빠른 프리셋: 초고속 / 추천 / 고화질
- 현재 재생 위치를 IN / OUT으로 즉시 지정
- 출력 설정과 분할 설정 자동 저장
- 변환 중 화면 꺼짐 방지
- 결과 파일 열기
- 선택 파트 개별 공유 / 전체 파트 공유

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

GitHub **Actions → Build Android APK → 최신 성공 빌드 → Artifacts → MotionWebP-v0.8-apk** 에서 받을 수 있습니다.
