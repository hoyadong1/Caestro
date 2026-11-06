# 프로젝트 가이드

Drive Soundtrack의 구조, 폴더별 역할, 작업 방식, 버전별 변화와 산출물을 정리한 문서입니다. 실행 방법은 [README](../README.md)를 먼저 보세요.

## 1. 한눈에 보기

- **무엇:** 차량의 주행 신호(속도·가속·제동·조향·방향지시등·와이퍼)가 여섯 악기를 연주하는 Android Automotive 음악 앱. 음원 파일 없이 실시간 합성합니다.
- **어디서:** Pleos Connect(Android Automotive 기반) 차량과 에뮬레이터. 파워트레인과 관계없이 동작합니다.
- **기술:** Kotlin, Jetpack Compose, Android Car API, Pleos Vehicle SDK 2.3.2, AudioTrack, MediaBrowserService/MediaSession.
- **현재 버전:** 0.7.1 (versionCode 19)

## 2. 아키텍처

```
 차량                     앱 프로세스
┌────────────┐   ┌───────────────────────────────────────────────────────────┐
│ Car API    │──▶│ vehicle/CarSpeedReader ─┐                                 │
│ (속도·페달) │   │ vehicle/CarPedalReader ─┤                                 │
│            │   │                         ▼                                 │
│ Pleos SDK  │──▶│ vehicle/PleosBandReader ──▶ audio/SoundModel ──▶ audio/SoundRenderer
│ (제동·조향· │   │ vehicle/PleosDriveModeReader   (신호 → 음악 상태)   (악기 합성, PCM)
│ 방향·와이퍼) │   │                         │                       │         │
│            │   │                         │                       ▼         │
│ UX 제한    │──▶│ vehicle/DriveRestrictionsReader       audio/SynthEngine ──▶ AudioTrack ──▶ 스피커
└────────────┘   │                         │              (재생 스레드)                 │
                 │                         ▼                                         │
                 │               SoundtrackService (MediaBrowserService)             │
                 │               재생·오디오 포커스·MediaSession·상태 브로드캐스트     │
                 │                         │  STATUS 브로드캐스트                      │
                 │                         ▼                                         │
                 │               MainActivity ──▶ ui/MainScreen (Compose)            │
                 │                                 ui/InstrumentStage (악기 무대)      │
                 └───────────────────────────────────────────────────────────────────┘
```

### 계층별 역할

| 계층 | 파일 | 역할 |
|---|---|---|
| 서비스 | `SoundtrackService.kt` | 앱의 중심. 차량 리더를 시작하고, 신호를 `SoundModel`에 넣고, `SynthEngine`으로 재생합니다. `MediaSession`으로 차량 미디어 버튼과 연결하고, 오디오 포커스를 잃으면 멈춥니다. 상태를 `STATUS` 브로드캐스트로 화면에 보냅니다. 정차 시연 시나리오도 여기서 켜고 끕니다. |
| 화면 | `MainActivity.kt` | 서비스 상태를 받아 Compose 화면에 넘기고, 화면의 조작을 서비스 인텐트로 바꿉니다. 차량 속도 권한 요청도 담당합니다. |
| | `ui/MainScreen.kt` | 헤더, 시나리오 버튼, 악기 무대, 속도·템포·레이어 지표, 시연 조작, 사운드스케이프 카드. 주행 중에는 선택과 조작을 잠급니다. |
| | `ui/InstrumentStage.kt` | 활성 악기 수에 따라 1~6칸으로 나뉘는 악기 무대를 Canvas로 그립니다. 각 칸은 신호(가속·제동·조향·방향지시등)에 따라 모양이 바뀝니다. |
| | `ui/SoundscapeGlyph.kt`, `ui/Theme.kt`, `ui/PlayerStatus.kt` | 사운드스케이프 아이콘, 색상, 서비스 상태 데이터와 화면 액션 인터페이스. |
| 음악 | `audio/SoundModel.kt` | 차량 신호를 음악 제어값으로 바꿉니다. 값을 부드럽게 보간하고, 모르는 신호는 "모름"으로 유지합니다. 속도에 따른 악기 합류(18·38·58·82·105 km/h)와 이탈(12·31·50·73·94 km/h)을 결정합니다. |
| | `audio/SoundRenderer.kt` | 다섯 사운드스케이프와 여섯 악기를 합성해 16비트 스테레오 PCM을 만듭니다. 마디 단위로 악기를 섞어 넣고 뺍니다. |
| | `audio/SynthEngine.kt` | 재생 스레드에서 `SoundRenderer`를 돌려 `AudioTrack`에 씁니다. 디버그 빌드에서는 같은 PCM을 WAV로 저장할 수 있습니다(시연 영상 녹음용). |
| | `audio/Soundscapes.kt` | Pulse·Night·Coast·Horizon·Circuit의 템포, 화성, 리듬 패턴, 색. |
| 차량 | `vehicle/CarSpeedReader.kt` | Android `PERF_VEHICLE_SPEED`. 차량 서비스가 재시작되면 다시 연결합니다. |
| | `vehicle/CarPedalReader.kt` | 가속 페달 값(지원 기기만). 없으면 "없음"으로 보고합니다. |
| | `vehicle/PleosBandReader.kt` | Pleos SDK의 제동·조향각·방향지시등·와이퍼. 신호마다 독립적으로 실패해도 나머지는 동작합니다. |
| | `vehicle/PleosDriveModeReader.kt` | Pleos 주행 모드. |
| | `vehicle/DriveRestrictionsReader.kt` | 차량 UX 제한(주행 중 조작 제한) 상태. |

### 설계 원칙

- **파워트레인 중립:** 모든 차에 있는 신호만 핵심 악기에 씁니다. 엔진 RPM이나 배터리 흐름처럼 특정 차종에만 있는 신호는 쓰지 않습니다. ([제품 근거](design/PRODUCT_RATIONALE.md))
- **모르는 값은 지어내지 않음:** 신호가 없으면 "모름"으로 두고 소리는 중립으로 돌아갑니다. 가속 페달이 없으면 속도 변화로 추정하고, 실제 값과 구분합니다.
- **안전 우선:** 실내 미디어 재생만 합니다. 내비 안내·통화·경고음이 오면 양보합니다(오디오 포커스). 주행 중에는 화면 조작을 잠급니다.
- **오프라인:** 네트워크와 음원 파일을 쓰지 않습니다. 차량 데이터를 저장하거나 보내지 않습니다.

### 주요 서비스 액션

화면과 녹화 스크립트는 `SoundtrackService`에 인텐트 액션으로 명령합니다.

| 액션 | 의미 | 빌드 |
|---|---|---|
| `PLAY`, `PAUSE` | 재생·일시정지 | 모두 |
| `PRESET` | 사운드스케이프 변경(주행 중에는 무시) | 모두 |
| `SCENARIO_TOGGLE` | 정차 시연 시나리오(0→130→0 km/h) 시작·종료. Pleos 에뮬레이터에서만 동작 | 모두 |
| `DEMO_SPEED`, `DEMO_BAND` | 차량 신호가 없을 때의 시연 조작 | 모두 |
| `ARM_DEBUG_CAPTURE` | 다음 재생의 PCM을 `files/live-demo.wav`로 저장 | 디버그 전용 |
| `DEBUG_*` | 베이스 솔로, 속도 고정 등 실험용 | 디버그 전용 |

## 3. 폴더와 파일

```
drive-soundtrack/
├─ README.md                  앱 소개, 실행 방법, 검증 범위 (공모전 GitHub 요건)
├─ app/
│  ├─ build.gradle            버전, Compose, Pleos SDK, 서명 설정
│  └─ src/
│     ├─ main/AndroidManifest.xml   권한, distractionOptimized(주행 중 화면 표시)
│     ├─ main/kotlin/.../app/       위 2장의 소스
│     └─ test/kotlin/.../audio/     합성 엔진 JUnit 테스트 4종 + PreviewWav(미리듣기 WAV 생성 도구)
├─ apk/홍원택_DriveSoundtrack_release.apk   서명된 제출용 APK (공모전 이름 규칙)
├─ docs/                      GitHub Pages 쇼케이스 (https://hoyadong1.github.io/DriveSoundtrack/)
│  ├─ index.html              쇼케이스 페이지
│  ├─ *.mp4, *.mp3, *.png     시연 영상·음원·포스터 (페이지가 직접 참조하므로 위치를 바꾸지 않음)
│  ├─ readme-thumbnail.png    README 맨 위 썸네일
│  ├─ PROJECT_GUIDE.md        이 문서
│  ├─ design/                 기획·알고리즘 문서
│  │  ├─ PRODUCT_RATIONALE.md            제품 근거, 파워트레인 중립 설계
│  │  ├─ DRIVING_SCORE_ALGORITHM.md      신호별 음악 매핑 상세
│  │  ├─ PRODUCT_V2_INSTRUMENT_CONCEPT.md 악기 중심 기획
│  │  ├─ PRODUCT_V2_PLAN.md              초기 Journey Score 기획
│  │  └─ *.png                           시각 콘셉트, 초기 신호 시험 화면
│  └─ history/                0.4.x 베이스 실험 기록 (현재 방식으로 대체됨)
├─ demo/
│  ├─ start-interactive.ps1   Pleos 에뮬레이터 실행 + 최신 APK 설치
│  ├─ start-live-drive.ps1    일반 재생 시작 (콘솔로 실제 속도 시험)
│  ├─ stop-interactive.ps1    정리 후 에뮬레이터 종료
│  ├─ console-drive/          D단 콘솔 주행 녹화 도구 (README 포함)
│  └─ archive/                0.4~0.6 시절 녹화·분석 스크립트 (참고용)
├─ build.gradle, settings.gradle, gradle/, gradlew*   Gradle 설정 (Pleos Maven 저장소 포함)
└─ (저장소에 없음) local.properties, signing.properties, release/   SDK 경로와 서명키. 개인 보관
```

## 4. 작업 방식

### 개발 순환

1. 코드 수정
2. `.\gradlew.bat :app:testDebugUnitTest`로 합성 엔진 검사
3. `.\gradlew.bat :app:assembleDebug`로 빌드해 Pleos 에뮬레이터(사용자 10)에 설치
4. Virtual Console(`http://127.0.0.1:8088`, `adb forward tcp:8088 tcp:8088`)에서 기어·속도·와이퍼 등을 바꾸며 확인
5. 버전을 올리고 `assembleRelease`로 서명 APK를 만든 뒤 `apk/`의 파일을 교체
6. 커밋·푸시. `docs/`가 바뀌면 GitHub Pages가 자동 배포됩니다.

### 에뮬레이터 주의점

- 앱은 Pleos의 **사용자 10**에 설치·실행합니다(`--user 10`).
- 에뮬레이터는 창 없이(`-no-window`) 실행할 수 있습니다. 녹화는 호스트 측 `adb emu screenrecord`를 써야 앱 오디오가 느려지지 않습니다.
- Virtual Console 페이지는 자체 물리 엔진으로 속도를 계속 덮어씁니다. 속도를 정밀하게 제어하려면 페이지의 페달을 조작해야 합니다(`demo/console-drive/driver.js`).
- 에뮬레이터는 오디오를 실시간보다 느리게(약 0.8배) 만들기 때문에, 시연 영상은 소리를 기준으로 화면을 맞춥니다.

### 시연 영상 만드는 법

`demo/console-drive/README.md`를 보세요. 디버그 APK로 앱 PCM을 받고, 에뮬레이터 화면과 콘솔 화면을 나란히 합성합니다.

## 5. 버전별 변화

| 버전 | 날짜 | 주요 변화 |
|---|---|---|
| 0.1 | 10/29 | 첫 프로토타입(Java). 속도에 반응하는 전자음악, 세 프리셋 |
| 0.2 | 10/30~11/02 | Kotlin 전환, 다섯 사운드스케이프, 차량 속도 권한 검증, 주행 중 프리셋 잠금, GitHub Pages 영상 |
| 0.3 | 11/03 | 여러 차량 신호를 악기에 매핑(제동·조향·방향지시등·와이퍼), Journey Score 기획 |
| 0.4 | 11/04 | 베이스 실험: 속도별 음높이·음색, 화음에 맞춘 베이스 ([기록](history/)) |
| 0.5 | 11/04 | 속도에 따른 악기 층, 방향지시등 응답음 |
| 0.6 | 11/05 | 여섯 악기·여섯 칸 악기 무대, 화음 정리, 원터치 정차 시나리오 |
| 0.6.6 | 11/06 | 시나리오 중 D단 전환 시 재생 유지 |
| 0.7.0 | 11/06 | Jetpack Compose 전환, 패키지 분리, 주행 중 화면 표시(distractionOptimized), JUnit 테스트, 문서 정리 |
| 0.7.1 | 11/06 | 와이퍼 권한 수정, 시나리오 중 차량이 움직이면 실제 속도로 이어받기 |

## 6. 산출물

| 산출물 | 위치 |
|---|---|
| 서명된 release APK | [`apk/`](../apk/) |
| 쇼케이스 페이지 | https://hoyadong1.github.io/DriveSoundtrack/ |
| D단 콘솔 주행 영상 (앱 화면 포함) | [`docs/console-d-drive-app.mp4`](console-d-drive-app.mp4) |
| 정차 시나리오 영상 | [`docs/pulse-harmony-live-demo.mp4`](pulse-harmony-live-demo.mp4) |
| 이전 실험 영상 | `docs/bass-*.mp4`, `docs/speed-pitch-journey.mp4`, `docs/musical-speed-journey.mp4` |
| 사운드 샘플 | `docs/*.mp3` |
| 기획·알고리즘 문서 | [`docs/design/`](design/) |
| 녹화 도구 | [`demo/console-drive/`](../demo/console-drive/) |

## 7. 알려진 한계

- 실제 차량 주행은 검증하지 않았습니다. 모든 검증은 Pleos Connect v2.1 에뮬레이터 기준입니다.
- 이 에뮬레이터에는 가속 페달 권한이 없어 실제 페달 값은 검증하지 못했습니다(속도 변화로 추정).
- 제동·조향·방향지시등 콜백의 에뮬레이터 개별 검증, Pleos 주행 모드 변경, 차량 서비스 재연결은 아직 확인하지 않았습니다.
- 주행 중 화면 표시가 실제 차량·스토어 심사에서 허용되는지는 Pleos 심사 결과에 따릅니다.
