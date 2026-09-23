# Video → WebP Android

휴대폰에서 영상을 선택해 **Animated WebP**로 변환하는 Android 앱입니다.

## 현재 기능

- MP4/MOV/WebM 등 Android가 디코딩할 수 있는 영상 선택
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

## 변환 엔진

영상 프레임은 Android의 MediaMetadataRetriever로 기기 내부에서 추출하고,
Animated WebP 생성은 **com.aureusapps.android:webp-android:1.1.2**의
native libwebp WebPAnimEncoder를 사용합니다.

FFmpeg 바이너리에 libwebp가 빠진 경우에도 변환 기능이 깨지지 않도록,
v0.3부터 Animated WebP 인코딩을 전용 libwebp JNI 엔진으로 변경했습니다.

## 지원 기기

- Android 10(API 29) 이상
- 현재 APK는 **arm64-v8a** 대상입니다.
  최근 Samsung Galaxy, Pixel 등 대부분의 64비트 Android 휴대폰이 해당됩니다.

## 휴대폰에서 APK 받기

1. 저장소의 **Actions** 탭을 엽니다.
2. **Build Android APK**의 최신 초록색 빌드를 엽니다.
3. 화면 아래 **Artifacts**에서 **VideoToWebP-debug-apk**를 받습니다.
4. ZIP 압축을 풀고 **app-debug.apk**를 실행해 설치합니다.
5. Android가 요청하면 해당 파일 앱/브라우저의 **알 수 없는 앱 설치 허용**을 켭니다.

## 참고

Animated WebP는 긴 영상, 고해상도, 높은 FPS, 높은 품질에서 파일 크기와
변환 시간이 빠르게 증가합니다. 짧은 클립에는 480~720px, 10~20 FPS,
품질 70~85를 시작점으로 권장합니다.
