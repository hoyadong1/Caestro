# Drive Soundtrack

전기차의 조용한 실내에서 차량 조작이 악기를 연주하는 경험을 제안하는 Android Automotive용 오리지널 음악 앱입니다. 속도는 베이스 음색과 드럼, 가속 페달 또는 속도 변화는 베이스의 추진감, 브레이크는 화성, 조향은 선율, 방향지시등은 짧은 응답, 와이퍼는 질감에 반영합니다. 다섯 사운드스케이프를 음원 파일이나 네트워크 없이 실시간 합성합니다. **앱 기능은 모두 Kotlin**이며, 화면은 Android View로 구현했습니다.

[주행 음악 알고리즘과 신호별 매핑](DRIVING_SCORE_ALGORITHM.md) · [전기차 제품 근거와 검증 범위](EV_PRODUCT_RATIONALE.md) · [악기 중심 기획](PRODUCT_V2_INSTRUMENT_CONCEPT.md) · [시각 콘셉트](demo/instrument-stage-v2-concept.png)

## 시연 영상

[![화성에 맞춰 변하는 속도 베이스 시연 영상](docs/musical-speed-poster.png)](https://hoyadong1.github.io/DriveSoundtrack/)

[GitHub Pages에서 영상 바로 재생](https://hoyadong1.github.io/DriveSoundtrack/) · [0.4.0 전체 합주 MP4](docs/drive-soundtrack-demo.mp4) · [서명된 release APK 다운로드](apk/홍원택_DriveSoundtrack_release.apk)

**새 0.4.4 화성 기반 베이스:** [29초 베이스 솔로 영상](docs/musical-speed-journey.mp4) · [속도별 음색과 실제 PCM 검증](MUSICAL_SPEED_JOURNEY.md). 0.4.3의 독립적인 연속 상승음을 제거했습니다. 0→40→80→130→0 km/h에 따라 베이스가 **같은 음의 기본음과 옥타브 성분을 섞는 비율**을 바꿉니다. 앱이 `AudioTrack.write()`에 전달한 단일 PCM을 사용했고, 이번 영상의 속도는 오디오 시간에 맞춘 앱 내부 디버그 시나리오입니다.

**이전 0.4.3 실험:** [연속 상승음 영상](docs/speed-pitch-journey.mp4) · [독립 발진음 실측](SPEED_PITCH_JOURNEY.md). 음악의 화음 사이로 음높이가 지나가며 거슬릴 수 있어 현재 앱에서 교체했습니다.

**이전 0.4.2 차량 신호 시연:** [82초 베이스 솔로 영상](docs/bass-drive-journey.mp4) · [주행 곡선·기어 신호·PCM 검증](BASS_JOURNEY.md). Pleos Console에서 D를 선택하고 0→88→0 km/h를 282번 점진적으로 갱신했습니다. 에뮬레이터의 현재 기어 속성은 P로 남았습니다.

**베이스만 집중 비교:** [0·10·20·35·55·80·110·130 km/h 베이스 솔로 영상](https://hoyadong1.github.io/DriveSoundtrack/bass-speed-study.mp4) · [속도별 측정값과 음색 설계](BASS_STUDY.md). 0.4.1 디버그 빌드의 베이스 솔로 출력만 녹음했습니다. 영상 오른쪽은 실제 앱 PCM의 파형과 RMS를 표시합니다.

**0.4.0 · Pleos Connect v2.1.0 에뮬레이터 시연.** 왼쪽 3D 차량과 오른쪽 악기 무대를 함께 보여 줍니다. P 기어의 Virtual Console에서 정차 → 가속 → 고속 주행 → 제동 → 좌측 조향 → 좌·우 방향지시등 → 속도·조향·제동·지시등의 복합 상태를 짧은 장면으로 녹화했습니다. 마지막은 Pulse 모드입니다. **음악은 화면을 녹화하는 동안 실제 앱이 `AudioTrack.write()`에 전달한 PCM을 디버그 빌드에서 WAV로 기록해 합쳤습니다.** 에뮬레이터의 시스템 오디오 캡처가 무음이어서 앱 재생 경로에서 직접 기록했습니다. 스피커 출력의 루프백 녹음은 아닙니다. [0.4.4 다섯 모드 음악 미리듣기](docs/drive-soundtrack-soundscapes.mp3)

## 기능

- Pulse(일렉트로), Night(퓨처 개러지), Coast(앰비언트 하우스), Horizon(시네마틱), Circuit(멜로딕 드라이브)의 다섯 사운드스케이프
- 각 모드의 16단계 리듬 패턴, 4마디 화성 순환, 선율 변화와 스테레오 딜레이. 속도의 BPM 영향은 최대 6 BPM으로 제한
- 속도는 Circuit에서 화성에 맞춘 베이스의 기본음·옥타브 비율과 리듬 층, 가속은 베이스와 추진 질감, 제동은 화음 감쇠, 조향각은 좌우 선율·연속 음높이, 좌·우 방향지시등은 서로 반대 방향의 2음 응답에 연결. [수치·조합·제약 상세 설명](DRIVING_SCORE_ALGORITHM.md)
- Android Automotive `PERF_VEHICLE_SPEED`, 선택적 `ACCELERATOR_PEDAL_COMPRESSION_PERCENTAGE`, Pleos Vehicle SDK Brake·SteeringWheel·TurnSignal·Wiper·DrivingMode 읽기 전용 입력
- Android Automotive `GEAR_SELECTION`과 `CURRENT_GEAR`를 읽기 전용으로 구분해 수신. [에뮬레이터에서 D 선택 신호와 현재 기어 P가 동시에 관측된 결과](BASS_JOURNEY.md) 기록
- 가속 페달 속성이 없는 기기에서는 **속도 변화로 추정한 추진감**을 베이스에 사용하며 실제 페달값과 구분. 차량 속도 입력이 없으면 정차 중 속도·가속·제동·조향·방향지시등·와이퍼 시연 조작 제공
- 정차 시연 화면에 베이스·드럼·건반·선율의 악기 무대와 신호별 반응 이유 표시
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

`test/SoundModelCheck.kt`는 신호별 상태와 안전한 값 범위를, `test/InstrumentAudioCheck.kt`는 각 악기 입력이 실제 PCM 출력을 바꾸는지 검사합니다. `test/PreviewWav.kt`는 앱과 동일한 `SoundRenderer`로 40초 WAV(각 모드 8초)를 만듭니다. Kotlin CLI가 있는 환경에서 다음처럼 실행할 수 있습니다.

```powershell
kotlinc app/src/main/kotlin/com/drivesoundtrack/app/Soundscapes.kt app/src/main/kotlin/com/drivesoundtrack/app/SoundModel.kt app/src/main/kotlin/com/drivesoundtrack/app/SoundRenderer.kt test/SoundModelCheck.kt test/InstrumentAudioCheck.kt test/PreviewWav.kt -include-runtime -d audio-tools.jar
java -cp audio-tools.jar SoundModelCheckKt
java -cp audio-tools.jar InstrumentAudioCheckKt
java -cp audio-tools.jar PreviewWavKt preview.wav
```

Kotlin 보조 도구로 모델 검사와 악기별·방향별 PCM 차이, 연속 조향, 복합 신호 검사를 통과했습니다. `test/BassSpeedCheck.kt`는 Circuit 베이스를 8개 정속 상태에서 따로 검증합니다. 웹 페이지의 40초 MP3는 0.4.4 `SoundRenderer`가 생성한 다섯 모드 미리듣기입니다. 시연 영상의 오디오는 이 MP3를 사용하지 않고 녹화 당시 앱의 PCM에서 가져왔습니다. 모든 음악은 이 저장소의 합성 코드로 생성하며 게임 음원을 사용하지 않습니다.

## 검증 범위와 남은 항목

0.4.4 디버그·릴리스 빌드와 릴리스 APK 서명 검증을 마쳤습니다. 새 베이스 영상은 앱 내부 디버그 입력을 사용하므로 차량 센서 시험은 아닙니다. 실제 청취자가 모든 속도대에서 음악을 선호하는지, 소리만으로 속도대를 알아맞히는지는 아직 평가하지 않았습니다. 기존 0.4.0 전체 합주 영상은 P 기어에서 값을 강제 주입한 **에뮬레이터 시험**이며 실제 도로 주행은 아닙니다. 0.4.2 베이스 영상은 P에서 D를 선택하고 속도를 연속으로 주입했지만 에뮬레이터의 현재 기어 속성은 P로 남아 있어 실제 변속기 작동을 입증하지 않습니다. Kotlin PCM 검사에서 가속·제동·조향·방향지시등·와이퍼 각각이 음원 샘플을 변경하고, 좌·우 지시등이 다른 음형과 스테레오 위치를 만들며 복합 입력도 소리를 바꾸는 것을 확인했습니다.

[0.3.0 악기 화면과 Pleos 방향지시등 신호 시험](demo/v03-signal-test2.png) · [0.2.1 속도 신호 시험](demo/Drive_Soundtrack_차량신호_검증.png)

Pleos v2.1 AVD에는 `android.car.permission.READ_CAR_PEDALS` 권한이 없어 **실제 가속 페달값은 검증되지 않았습니다**. 와이퍼 콜백/스위치의 앱 표시와 스피커 출력 오디오의 루프백 녹음, Pleos `DrivingMode` 변경, 실제 차량 주행, 차량 서비스 재연결, UX 제한 콜백의 실동작도 아직 검증되지 않았습니다.
