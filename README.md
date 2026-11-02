# Drive Soundtrack

차량 속도와 주행 모드에 맞춰 편곡이 전개되는 Android Automotive용 오리지널 전자음악 앱입니다. 다섯 사운드스케이프의 리듬·베이스·화음·멜로디를 음원 파일이나 네트워크 없이 실시간 합성합니다. **앱과 보조 도구는 모두 Kotlin**이며, 화면은 Android View로 구현했습니다.

## 시연 영상

[![Pleos Connect에서 실행한 Drive Soundtrack 영상 썸네일](demo/Drive_Soundtrack_시연영상_썸네일.png)](https://hoyadong1.github.io/DriveSoundtrack/)

[GitHub Pages에서 영상 바로 재생](https://hoyadong1.github.io/DriveSoundtrack/) · [MP4 파일](demo/Drive_Soundtrack_시연영상.mp4) · [서명된 release APK 다운로드](apk/홍원택_DriveSoundtrack_release.apk)

58초 영상은 **Pleos Connect v2.1.0의 전체 화면(왼쪽 3D 차량, 오른쪽 앱)과 실제 앱 출력 오디오**를 함께 녹화했습니다. 오른쪽 앱의 속도 값은 시연 슬라이더로 입력했으며 왼쪽 차량의 실제 신호와 연동된 값이 아닙니다. 다섯 사운드스케이프의 전환을 볼 수 있습니다. [다섯 모드의 음악만 듣기](demo/Drive_Soundtrack_5모드_음악시연.mp3)

## 기능

- Pulse(일렉트로), Night(퓨처 개러지), Coast(앰비언트 하우스), Horizon(시네마틱), Circuit(미니멀 테크노)의 다섯 사운드스케이프
- 각 모드의 16단계 리듬 패턴, 4마디 화성 순환, 선율 변화와 스테레오 딜레이. 속도·가속 변화에 따라 템포와 악기 층이 부드럽게 변하고 프리셋 전환은 페이드 처리
- Android Automotive `PERF_VEHICLE_SPEED`와 Pleos Vehicle SDK `DrivingMode` 입력 지원
- 차량 속도 입력을 사용할 수 없을 때 속도 슬라이더로 시연
- `MediaBrowserService`와 `MediaSession`을 통한 재생·일시정지·프리셋 제어, 백그라운드 재생 및 오디오 포커스 처리
- 차량 UX 제한이 활성화되거나 실제 차량 속도가 5 km/h 이상일 때 사운드스케이프 선택·시연 조작 제한
- 차량 서비스가 재시작되면 속도·UX 제한 구독을 정리하고 재연결을 시도

## 실행

1. [`apk/홍원택_DriveSoundtrack_release.apk`](apk/홍원택_DriveSoundtrack_release.apk)를 Pleos Connect 또는 호환 Android Automotive 기기에 설치합니다.
2. 앱을 열어 프리셋을 고르고 **재생**을 누릅니다. 차량 속도 권한 요청이 보이면 허용해야 실제 속도 신호를 읽을 수 있습니다.
3. 차량 속도 연결이 불가능한 환경에서는 **시연 속도** 슬라이더를 움직여 음악 변화를 확인합니다. 차량 신호가 연결되면 슬라이더는 숨겨집니다.

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

`test/SoundModelCheck.kt`는 속도·주행 모드에 따른 모델 값을 검사합니다. `test/PreviewWav.kt`는 앱과 동일한 `SoundRenderer`로 40초 WAV(각 모드 8초)를 만듭니다. Kotlin CLI가 있는 환경에서 다음처럼 실행할 수 있습니다.

```powershell
kotlinc app/src/main/kotlin/com/drivesoundtrack/app/Soundscapes.kt app/src/main/kotlin/com/drivesoundtrack/app/SoundModel.kt app/src/main/kotlin/com/drivesoundtrack/app/SoundRenderer.kt test/SoundModelCheck.kt test/PreviewWav.kt -include-runtime -d audio-tools.jar
java -cp audio-tools.jar SoundModelCheckKt
java -cp audio-tools.jar PreviewWavKt preview.wav
```

Kotlin 보조 도구로 모델 검사를 통과하고 다섯 모드의 PCM 샘플을 생성했습니다. 저장소의 MP3는 해당 WAV를 청취용으로 압축한 파일입니다. 모든 음악은 이 저장소의 합성 코드로 생성하며 게임 음원을 사용하지 않습니다.

## 검증 범위와 남은 항목

Kotlin release APK를 Pleos Connect v2.1.0에서 실행해 재생·일시정지, 시연 속도에 따른 BPM·편곡 표시 변화, 다섯 모드 전환을 확인했습니다. 오디오 서비스가 트랙 생성을 거부할 때는 앱이 멈추지 않고 재생을 중지합니다.

실제 차량 주행, Pleos `DrivingMode` 변경, 차량 서비스 재시작 후 자동 재연결, UX 제한 콜백의 실동작은 아직 검증하지 못했습니다. Pleos Virtual Console의 현재 신호 목록에는 `Driving Mode` 항목이 없어 주행 모드 변화는 별도 시험 환경이 필요합니다.
