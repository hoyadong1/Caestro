# Drive Soundtrack

전기차의 조용한 실내에서 차량 조작이 악기를 연주하는 경험을 제안하는 Android Automotive용 오리지널 음악 앱입니다. 파란 Pulse가 기본 모드이며 속도에 따라 베이스·드럼·화음·아르페지오·선율·고역 질감이 차례로 합류합니다. 악기 화면도 한 칸에서 최대 여섯 칸으로 나뉩니다. 음악 편곡에는 기어 단수를 사용하지 않습니다. 다섯 사운드스케이프를 음원 파일이나 네트워크 없이 실시간 합성합니다. **앱 기능은 모두 Kotlin**이며, 화면은 Android View로 구현했습니다.

[주행 음악 알고리즘과 신호별 매핑](DRIVING_SCORE_ALGORITHM.md) · [전기차 제품 근거와 검증 범위](EV_PRODUCT_RATIONALE.md) · [악기 중심 기획](PRODUCT_V2_INSTRUMENT_CONCEPT.md) · [시각 콘셉트](demo/instrument-stage-v2-concept.png)

## 시연 영상

[**최신 앱 화면과 소리 바로 재생**](https://hoyadong1.github.io/DriveSoundtrack/) · [MP4 파일](docs/pulse-harmony-live-demo.mp4) · [서명된 APK](apk/홍원택_DriveSoundtrack_release.apk)

약 100초 동안 정차에서 30·50·70·95·130 km/h를 천천히 거쳐 다시 정차합니다. 감속은 130→0 km/h를 28초에 걸쳐 진행합니다. 악기가 1개에서 6개까지 합류하고 빠지는 모습을 실제 Pleos 앱 화면에서 볼 수 있습니다. 원본은 Pleos 전체 화면이지만 공개 영상은 오른쪽 앱 영역만 잘랐습니다. 소리는 같은 재생 중 앱이 `AudioTrack.write()`에 전달한 PCM입니다. 에뮬레이터가 느리게 작동해 화면 프레임을 표시 속도에 맞춰 정렬했습니다. 속도는 앱 내부 시연 입력이며 실제 차량 주행은 아닙니다.

다섯 음악 모드의 음을 각 화음에 맞게 구성했습니다. [`test/HarmonyCheck.kt`](test/HarmonyCheck.kt)는 조성·화음 공통음·6개 악기 합주의 출력 범위를 확인합니다.

## 기능

- Pulse(일렉트로), Night(퓨처 개러지), Coast(앰비언트 하우스), Horizon(시네마틱), Circuit(멜로딕 드라이브)의 다섯 사운드스케이프
- 베이스는 항상 유지하고 드럼 18, 화음 38, 아르페지오 58, 선율 82, 고역 질감 105 km/h에서 차례로 합류. 감속 시 각각 12·31·50·73·94 km/h에서 빠지고 마디 시작에서 전환해 1.15초 동안 소리를 섞음
- 각 모드의 16단계 리듬 패턴, 4마디 화성 순환, 선율 변화와 스테레오 딜레이. 속도의 BPM 영향은 최대 6 BPM으로 제한
- 속도는 Circuit에서 화성에 맞춘 베이스의 기본음·옥타브 비율과 리듬 층, 가속은 베이스와 추진 질감, 제동은 화음 감쇠, 조향각은 좌우 선율·연속 음높이, 좌·우 방향지시등은 서로 반대 방향의 2음 응답에 연결. [수치·조합·제약 상세 설명](DRIVING_SCORE_ALGORITHM.md)
- Android Automotive `PERF_VEHICLE_SPEED`, 선택적 `ACCELERATOR_PEDAL_COMPRESSION_PERCENTAGE`, Pleos Vehicle SDK Brake·SteeringWheel·TurnSignal·Wiper·DrivingMode 읽기 전용 입력
- 가속 페달 속성이 없는 기기에서는 **속도 변화로 추정한 추진감**을 베이스에 사용하며 실제 페달값과 구분. 차량 속도 입력이 없으면 정차 중 속도·가속·제동·조향·방향지시등·와이퍼 시연 조작 제공
- 정차 시 베이스 한 화면에서 시작해 활성 악기 수에 따라 2~6개 화면으로 부드럽게 분할되는 악기 무대
- `MediaBrowserService`와 `MediaSession`을 통한 재생·일시정지·프리셋 제어, 백그라운드 재생 및 오디오 포커스 처리
- 차량 UX 제한이 활성화되거나 실제 차량 속도가 5 km/h 이상일 때 사운드스케이프 선택·시연 조작과 자체 화면의 애니메이션 제한
- 차량 서비스가 재시작되면 속도·UX 제한 구독을 정리하고 재연결을 시도

## 실행

1. [`apk/홍원택_DriveSoundtrack_release.apk`](apk/홍원택_DriveSoundtrack_release.apk)를 Pleos Connect 또는 호환 Android Automotive 기기에 설치합니다.
2. 앱을 열어 프리셋을 고르고 **재생**을 누릅니다. 차량 속도 권한 요청이 보이면 허용해야 실제 속도 신호를 읽을 수 있습니다. 가속 페달 권한은 지원하는 시스템에서만 요청합니다.
3. 차량 속도 연결이 불가능한 환경에서는 **시연 속도·가속 페달** 슬라이더와 세 가지 신호 버튼을 사용합니다. 차량 신호가 연결되면 시연 조작은 숨겨집니다.

차량 속도 권한은 시스템에서 위치 정보로 표시될 수 있습니다. 앱은 GPS 위치를 읽지 않으며 속도 값을 저장하거나 전송하지 않습니다.

## 소스 빌드

- Android Studio, Android SDK API 37의 `android.car.jar`, JDK 17 이상이 필요합니다. 최소 실행 API는 28입니다.
- `local.properties`에 `sdk.dir`을 지정하거나 `ANDROID_SDK_ROOT`를 설정합니다.
- Pleos Vehicle SDK 2.3.2는 [`settings.gradle`](settings.gradle)에 등록된 Pleos Maven 저장소에서 가져옵니다.

```powershell
.\gradlew.bat :app:assembleDebug
```

macOS/Linux에서는 `./gradlew :app:assembleDebug`를 사용합니다. 제출용 release APK를 다시 빌드하려면 저장소에 포함되지 않은 `signing.properties`와 서명키가 필요합니다. 제공된 APK는 서명 검증을 마쳤으며 서명키·비밀번호는 저장소에 없습니다.

## Kotlin 보조 도구

`test/SoundModelCheck.kt`는 신호별 상태와 값 범위를, `test/InstrumentAudioCheck.kt`는 각 악기 입력이 실제 PCM 출력을 바꾸는지 검사합니다. `test/HarmonyCheck.kt`는 다섯 모드의 화음과 1~6개 악기 합주, 긴 속도 시나리오를 검사합니다. Kotlin CLI가 있는 환경에서 다음처럼 실행할 수 있습니다.

```powershell
kotlinc app/src/main/kotlin/com/drivesoundtrack/app/Soundscapes.kt app/src/main/kotlin/com/drivesoundtrack/app/SoundModel.kt app/src/main/kotlin/com/drivesoundtrack/app/SoundRenderer.kt test/SoundModelCheck.kt test/InstrumentAudioCheck.kt test/HarmonyCheck.kt -include-runtime -d audio-tools.jar
java -cp audio-tools.jar SoundModelCheckKt
java -cp audio-tools.jar InstrumentAudioCheckKt
java -cp audio-tools.jar HarmonyCheckKt
```

시연 영상은 미리 만든 음원을 덧씌우지 않고 녹화 당시 앱의 PCM을 사용합니다. 음악은 이 저장소의 Kotlin 합성 코드에서 생성합니다.

## 검증 범위와 남은 항목

0.6.3 디버그·릴리스 빌드와 릴리스 APK 서명 검증을 마쳤습니다. 최신 영상의 속도는 앱 내부 디버그 입력이며 실제 차량 센서 시험은 아닙니다. 청취자가 모든 속도에서 편곡을 선호하는지, 소리만으로 속도대를 알아맞히는지는 아직 평가하지 않았습니다. Kotlin PCM 검사에서 가속·제동·조향·방향지시등·와이퍼 각각의 입력이 출력을 바꾸는 것은 확인했습니다.

Pleos v2.1 AVD에는 `android.car.permission.READ_CAR_PEDALS` 권한이 없어 **실제 가속 페달값은 검증되지 않았습니다**. 와이퍼 콜백/스위치의 앱 표시와 스피커 출력 오디오의 루프백 녹음, Pleos `DrivingMode` 변경, 실제 차량 주행, 차량 서비스 재연결, UX 제한 콜백의 실동작도 아직 검증되지 않았습니다.
