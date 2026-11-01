# Drive Soundtrack

Android Automotive용 실시간 전자음악 프로토타입입니다. 차량 속도가 템포와 음색에, Pleos 주행 모드가 그루브에 반영됩니다. 음악은 앱이 직접 합성하므로 외부 음원과 네트워크가 필요하지 않습니다.

앱 소스는 Kotlin으로 작성했습니다. 화면은 Jetpack Compose가 아닌 Android View를 코드에서 구성합니다.

## 현재 구현

- Pulse, Night, Coast 프리셋
- AAOS `PERF_VEHICLE_SPEED` 속도 읽기 및 Pleos Vehicle SDK `DrivingMode` 읽기
- `CAR_SPEED` 실행 중 권한 요청과 허용 후 차량 속도 구독 재시작. 시스템에서 위치 정보 권한으로 표시될 수 있으나 GPS는 사용하지 않고 속도 값도 저장·전송하지 않음
- 차량 입력이 없을 때만 사용할 수 있는 속도 시연 슬라이더
- Android `MediaBrowserService`와 `MediaSession`을 통한 재생, 일시정지, 프리셋 이동
- 오디오 포커스를 잃으면 재생 정지, 백그라운드 미디어 재생 서비스
- `CarUxRestrictions`가 주행 제한을 요청하거나 실제 차량 속도가 5 km/h 이상일 때 화면 버튼과 서비스의 프리셋 변경 명령 차단

## 빌드

1. Android SDK API 37과 Android Studio/Gradle 환경을 준비합니다.
2. `local.properties`에 `sdk.dir`을 지정하거나 `ANDROID_SDK_ROOT`를 설정합니다.
3. Windows에서는 `gradlew.bat :app:assembleDebug`, macOS/Linux에서는 `./gradlew :app:assembleDebug`로 디버그 APK를 빌드합니다.

Pleos Vehicle SDK 2.3.2는 공식 Pleos Maven 저장소에서 내려받습니다. 차량 속도 API는 Android SDK의 `android.car.jar`로 컴파일합니다. 최소 Android API는 28입니다.

## 시연 방법

앱을 열고 프리셋을 선택한 뒤 재생을 누릅니다. Pleos/AAOS 차량 속도 신호를 사용할 수 없는 기기에서는 슬라이더로 속도를 바꿔 음악 변화를 시연할 수 있습니다. 차량 신호를 받으면 슬라이더가 숨겨집니다.

## Release APK

공모전 제출용 서명 APK는 [`apk/홍원택_DriveSoundtrack_release.apk`](apk/홍원택_DriveSoundtrack_release.apk)에 있습니다. 다시 빌드하려면 프로젝트 루트의 `signing.properties`에 `storeFile`, `storePassword`, `keyAlias`, `keyPassword`를 지정한 뒤 `gradlew.bat :app:assembleRelease` 또는 `./gradlew :app:assembleRelease`를 실행합니다. 서명키와 비밀번호는 저장소에 올리지 않습니다.

## 현재 검증 범위

Kotlin 전환 후 `clean`, `assembleDebug`, `assembleRelease`가 성공했고 release APK의 서명을 검증했습니다. PC에서 동일 입력으로 20초 WAV를 생성했을 때 기존 Java 버전과 Kotlin 버전의 출력 SHA-256이 일치했습니다. Kotlin APK를 Pleos Connect v2.1.0 에뮬레이터에 설치해 앱 시작과 MediaSession 재생·일시정지 상태를 확인했고, 앱 충돌 로그는 없었습니다. 속도 권한을 임시로 해제해 시연 모드 슬라이더를 조작했을 때 Pulse 표시가 0 km/h·92 BPM에서 98 km/h·116 BPM으로 바뀌었습니다. 해당 화면은 [`demo/Drive_Soundtrack_Kotlin_시연화면.png`](demo/Drive_Soundtrack_Kotlin_시연화면.png)에 있습니다. Pleos Virtual Console에서 D 기어로 가속해 73 km/h를 표시한 뒤 외부 프리셋 변경 명령을 보냈을 때 MediaSession의 Pulse 프리셋은 유지됐습니다. 브레이크로 0 km/h까지 감속하고 P 기어로 바꾼 뒤 같은 명령을 보내자 Night로 변경됐습니다. D 기어·0 km/h에서는 변경이 차단됐으므로, 이 시험만으로 속도 조건과 차량 UX 제한 중 어느 조건이 작동했는지는 구분할 수 없습니다. Kotlin APK에서 실제 차량 속도 신호에 따른 BPM 화면 반응은 아직 별도로 확인하지 않았습니다.

전환 전 Java APK는 일반 Android 14 에뮬레이터에서 화면 실행, 재생·일시정지 미디어 키, 프리셋 이동, 시연 속도에 따른 BPM 변화를 확인했습니다. Pleos Connect v2.1.0 에뮬레이터에서는 `CAR_SPEED` 권한 요청·허용, 차량 속도 서비스 연결, MediaSession 재생 및 프리셋 이동을 확인했습니다. Virtual Console에서 속도를 0→36 km/h로 주입하자 앱의 Pulse 표시가 92→106 BPM으로 바뀌었습니다. 속도 36 km/h에서는 프리셋 버튼이 비활성화되고 0 km/h에서는 다시 변경할 수 있었습니다. `car_service`로 36 km/h 속도 신호를 유지하며 외부 프리셋 변경 명령을 보냈을 때 MediaSession 프리셋은 Night로 유지됐습니다. D 기어·36 km/h에서 Pleos는 앱 활동 화면을 차단했지만 MediaSession은 재생 상태를 유지했습니다. 화면에 속도를 표시하는 검증에는 P 기어에서 36 km/h를 강제로 주입한 테스트 상태를 사용했습니다. 실제 차량, Pleos 주행 모드 변경, 앱의 CarUxRestrictions 콜백과 오디오 포커스 전환은 아직 검증하지 않았습니다. 앱 화면 녹화와 합성 음원을 결합한 시연 영상도 준비했습니다.

## 남은 작업

- Kotlin release APK에서 차량 속도 신호에 따른 BPM 화면 반응 확인
- Pleos Connect 에뮬레이터에서 주행 모드와 오디오 포커스 통합 시험
- Pleos 전용 에뮬레이터에서 앱의 `CarUxRestrictions` 콜백 결과 확인
- 음악 패턴과 전환 품질 조정, 장시간 재생 안정성 확인
