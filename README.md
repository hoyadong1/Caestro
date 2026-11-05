# Drive Soundtrack

[![Drive Soundtrack 시연 페이지 열기: D단 130 km/h에서 악기 6개가 연주되는 앱 화면](docs/readme-thumbnail.png)](https://hoyadong1.github.io/DriveSoundtrack/)

차량의 주행 신호가 악기를 연주하는 Android Automotive용 오리지널 음악 앱입니다. 속도·가속·제동·조향·방향지시등처럼 모든 차가 가진 신호만 쓰기 때문에 내연기관·하이브리드·전기차 어디서나 같은 경험을 줍니다. 엔진 소리가 사라지는 전기차가 늘어날수록, 주행 감각을 소리로 되돌려 주는 이 경험의 가치는 더 커집니다.

파란 Pulse가 기본 모드이며 속도에 따라 베이스·드럼·화음·아르페지오·선율·고역 질감이 차례로 합류합니다. 악기 화면도 한 칸에서 최대 여섯 칸으로 나뉩니다. 음악 편곡에는 기어 단수를 사용하지 않습니다. 다섯 사운드스케이프를 음원 파일이나 네트워크 없이 실시간 합성합니다. **앱은 모두 Kotlin**이며, 화면은 Jetpack Compose로 구현했습니다.

[주행 음악 알고리즘과 신호별 매핑](docs/design/DRIVING_SCORE_ALGORITHM.md) · [제품 근거와 파워트레인 중립 설계](docs/design/PRODUCT_RATIONALE.md) · [악기 중심 기획](docs/design/PRODUCT_V2_INSTRUMENT_CONCEPT.md) · [시각 콘셉트](docs/design/instrument-stage-v2-concept.png)

## 시연 영상

[**쇼케이스 페이지**](https://hoyadong1.github.io/DriveSoundtrack/) · [서명된 APK](apk/홍원택_DriveSoundtrack_release.apk)

- [**D단 실제 속도 주행 (앱 화면 포함)**](https://hoyadong1.github.io/DriveSoundtrack/console-d-drive-app.mp4): Pleos Virtual Console의 ACCEL/BRAKE 페달로 0 → 30 → 50 → 70 → 95 → 130 → 0 km/h를 주행했습니다. 앱은 시나리오 없이 일반 차량 속도 신호를 받아 악기를 1개에서 6개까지 더하고 뺍니다. 왼쪽은 Pleos 화면, 오른쪽은 콘솔입니다. 녹화 방법은 [`demo/console-drive`](demo/console-drive/README.md)에 있습니다.
- [정차 중 앱 내부 시나리오](docs/pulse-harmony-live-demo.mp4): 같은 속도 곡선을 앱 내부 시연 입력으로 재생한 영상입니다.

두 영상 모두 소리는 녹화 중 앱이 `AudioTrack.write()`에 전달한 PCM입니다. 에뮬레이터가 오디오를 실시간보다 느리게 만들기 때문에 화면 프레임을 소리에 맞춰 정렬했습니다. 실제 차량 주행 영상은 아닙니다.

## 기능

- Pulse(일렉트로), Night(퓨처 개러지), Coast(앰비언트 하우스), Horizon(시네마틱), Circuit(멜로딕 드라이브)의 다섯 사운드스케이프
- 베이스는 항상 유지하고 드럼 18, 화음 38, 아르페지오 58, 선율 82, 고역 질감 105 km/h에서 차례로 합류. 감속 시 각각 12·31·50·73·94 km/h에서 빠지고 마디 시작에서 전환해 1.15초 동안 소리를 섞음
- 각 모드의 16단계 리듬 패턴, 4마디 화성 순환, 선율 변화와 스테레오 딜레이. 속도의 BPM 영향은 최대 6 BPM으로 제한
- 속도는 Circuit에서 화성에 맞춘 베이스의 기본음·옥타브 비율과 리듬 층, 가속은 베이스와 추진 질감, 제동은 화음 감쇠, 조향각은 좌우 선율·연속 음높이, 좌·우 방향지시등은 서로 반대 방향의 2음 응답에 연결. [수치·조합·제약 상세 설명](docs/design/DRIVING_SCORE_ALGORITHM.md)
- Android Automotive `PERF_VEHICLE_SPEED`, 선택적 `ACCELERATOR_PEDAL_COMPRESSION_PERCENTAGE`, Pleos Vehicle SDK Brake·SteeringWheel·TurnSignal·Wiper·DrivingMode 읽기 전용 입력
- 가속 페달 속성이 없는 기기에서는 **속도 변화로 추정한 추진감**을 베이스에 사용하며 실제 페달값과 구분. 차량 속도 입력이 없으면 정차 중 속도·가속·제동·조향·방향지시등·와이퍼 시연 조작 제공
- 정차 시 베이스 한 화면에서 시작해 활성 악기 수에 따라 2~6개 화면으로 부드럽게 분할되는 악기 무대
- `MediaBrowserService`와 `MediaSession`을 통한 재생·일시정지·프리셋 제어, 백그라운드 재생 및 오디오 포커스 처리
- `MainActivity`에 `distractionOptimized`를 선언해 주행 중에도 앱 화면을 표시. 차량 UX 제한이 활성화되거나 실제 차량 속도가 5 km/h 이상이면 사운드스케이프 선택·시연 조작을 잠그고 악기 무대 애니메이션을 멈춤
- 차량 서비스가 재시작되면 속도·UX 제한 구독을 정리하고 재연결을 시도

## 프로젝트 구조

```
app/src/main/kotlin/com/drivesoundtrack/app/
  MainActivity.kt          Compose 화면 호스트, 서비스 상태 수신
  SoundtrackService.kt     MediaBrowserService: 재생, 오디오 포커스, 차량 신호 연결
  audio/                   SoundModel(신호 → 음악 상태), SoundRenderer(합성), SynthEngine(AudioTrack), Soundscapes
  vehicle/                 차량 속도·페달·UX 제한(Android Car API), Pleos Vehicle SDK 입력
  ui/                      MainScreen, InstrumentStage(악기 무대 Canvas), SoundscapeGlyph, 색상
app/src/test/kotlin/...    합성 엔진 JUnit 검사
docs/                      GitHub Pages 쇼케이스(index.html과 영상)
  design/                  제품·알고리즘 설계 문서
  history/                 0.4.x 베이스 실험 기록
demo/                      Pleos 에뮬레이터 실행·시연 스크립트
  console-drive/           콘솔 주행 녹화 도구
  archive/                 이전 버전 녹화·분석 스크립트
apk/                       서명된 제출용 APK
```

## Pleos에서 직접 비교하기 (Windows)

PowerShell에서 저장소 폴더로 이동한 뒤 아래 명령을 실행합니다. 스크립트는 Pleos Connect 2.1 에뮬레이터를 호스트 GPU 모드로 열고 최신 APK를 설치합니다.

```powershell
.\demo\start-interactive.ps1
```

**정차 시나리오:** 앱 상단의 **주행 시나리오 시작**을 누르면 0 → 30 → 50 → 70 → 95 → 130 → 0 km/h의 약 100초 음악 시나리오가 시작됩니다. 오른쪽의 Pulse, Night, Coast, Horizon, Circuit 카드를 누르면 재생 중에도 모드를 비교할 수 있습니다. 이 속도는 앱의 합성 엔진에 주입되는 시연 값으로, 실제 차량이나 Pleos 계기판 속도를 바꾸지 않습니다. 시나리오 버튼은 Pleos 에뮬레이터가 정지 상태일 때만 표시됩니다.

**D단 실제 속도:** 시나리오 버튼을 누르지 않고 아래 명령으로 일반 재생을 시작한 뒤, Pleos Virtual Console(`http://127.0.0.1:8088/`)에서 D를 선택하고 ACCEL/BRAKE로 주행합니다. 앱은 차량 속도 신호에 맞춰 악기를 더하고 빼며, 주행 중에도 앱 화면이 표시됩니다. 디버그 APK에서는 `adb logcat -s DriveSoundtrack:I`로 수신 속도를 확인할 수 있습니다.

```powershell
.\demo\start-live-drive.ps1
```

테스트가 끝나면 `.\demo\stop-interactive.ps1`로 에뮬레이터를 닫습니다. 기본 명령의 `-gpu host`는 PC GPU 드라이버를 사용합니다. 호환성 문제가 있으면 `-Gpu auto`로 실행할 수 있습니다.

**주의:** 주행 중 화면 표시는 `distractionOptimized` 선언으로 Pleos 에뮬레이터에서 확인했습니다. 실제 차량과 스토어 배포에서 이 선언이 허용되는지는 Pleos 심사 결과에 따릅니다. 주행 중 애니메이션은 차량 앱 품질 지침과 충돌할 수 있습니다. 참고: [Pleos 주행 제한 정책](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-planning-guide/driving-restiction-policy).

## 실행

1. [`apk/홍원택_DriveSoundtrack_release.apk`](apk/홍원택_DriveSoundtrack_release.apk)를 Pleos Connect 또는 호환 Android Automotive 기기에 설치합니다.
2. 실제 차량에서는 앱을 열어 프리셋을 고르고 차량 미디어 제어로 재생합니다. 차량 속도 권한 요청이 보이면 허용해야 실제 속도 신호를 읽을 수 있습니다. 가속 페달 권한은 지원하는 시스템에서만 요청합니다. 앱 내부의 시나리오 버튼은 Pleos 개발용 에뮬레이터에서만 나타납니다.
3. 차량 속도 연결이 불가능한 환경에서는 **시연 속도·가속 페달** 슬라이더와 세 가지 신호 버튼을 사용합니다. 차량 신호가 연결되면 시연 조작은 숨겨집니다.

차량 속도 권한은 시스템에서 위치 정보로 표시될 수 있습니다. 앱은 GPS 위치를 읽지 않으며 속도 값을 저장하거나 전송하지 않습니다.

## 소스 빌드와 테스트

- Android Studio, Android SDK API 37의 `android.car.jar`, JDK 17 이상이 필요합니다. 최소 실행 API는 28입니다.
- `local.properties`에 `sdk.dir`을 지정하거나 `ANDROID_SDK_ROOT`를 설정합니다.
- Pleos Vehicle SDK 2.3.2는 [`settings.gradle`](settings.gradle)에 등록된 Pleos Maven 저장소에서 가져옵니다.

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

macOS/Linux에서는 `./gradlew`를 사용합니다. 단위 테스트는 기기 없이 합성 엔진을 검사합니다. `SoundModelCheckTest`는 신호별 상태와 값 범위를, `InstrumentAudioCheckTest`는 각 악기 입력이 실제 PCM 출력을 바꾸는지, `HarmonyCheckTest`는 다섯 모드의 화음과 1~6개 악기 합주, 긴 속도 시나리오를, `BassSpeedCheckTest`는 속도별 베이스 음색을 검사합니다.

제출용 release APK를 다시 빌드하려면 저장소에 포함되지 않은 `signing.properties`와 서명키가 필요합니다. 제공된 APK는 서명 검증을 마쳤으며 서명키·비밀번호는 저장소에 없습니다.

## 검증 범위와 남은 항목

0.7.1 디버그·릴리스 빌드와 단위 테스트, 릴리스 APK 서명 검증을 마쳤습니다. Pleos 에뮬레이터에서 Virtual Console의 D단 속도가 앱에 전달되고, 속도에 따라 악기가 더해지며, 주행 중에도 화면과 재생이 유지되는 것을 확인했습니다. 콘솔의 앞 와이퍼 켜기·끄기가 앱의 와이퍼 콜백으로 전달되는 것도 확인했습니다. 정차 시나리오 재생 중 실제 차량이 5 km/h 이상으로 움직이면 음악을 멈추지 않고 실제 속도로 이어받습니다. 실제 차량 센서 시험은 아닙니다. 청취자가 모든 속도에서 편곡을 선호하는지, 소리만으로 속도대를 알아맞히는지는 아직 평가하지 않았습니다.

Pleos v2.1 AVD에는 `android.car.permission.READ_CAR_PEDALS` 권한이 없어 **실제 가속 페달값은 검증되지 않았습니다**. 스피커 출력 오디오의 루프백 녹음, Pleos `DrivingMode` 변경, 실제 차량 주행, 차량 서비스 재연결도 아직 검증되지 않았습니다.
