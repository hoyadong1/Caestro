# Drive Soundtrack

Android Automotive용 실시간 전자음악 프로토타입입니다. 차량 속도가 템포와 음색에, Pleos 주행 모드가 그루브에 반영됩니다. 음악은 앱이 직접 합성하므로 외부 음원과 네트워크가 필요하지 않습니다.

## 현재 구현

- Pulse, Night, Coast 프리셋
- AAOS `PERF_VEHICLE_SPEED` 속도 읽기 및 Pleos Vehicle SDK `DrivingMode` 읽기
- 차량 입력이 없을 때만 사용할 수 있는 속도 시연 슬라이더
- Android `MediaBrowserService`와 `MediaSession`을 통한 재생, 일시정지, 프리셋 이동
- 오디오 포커스를 잃으면 재생 정지, 백그라운드 미디어 재생 서비스
- `CarUxRestrictions`가 주행 제한을 요청하거나 실제 차량 속도가 5 km/h 이상일 때 프리셋 변경 버튼 비활성화

## 빌드

1. Android SDK API 37과 Android Studio/Gradle 환경을 준비합니다.
2. `local.properties`에 `sdk.dir`을 지정하거나 `ANDROID_SDK_ROOT`를 설정합니다.
3. `gradle :app:assembleDebug`로 디버그 APK를 빌드합니다.

Pleos Vehicle SDK 2.3.2는 공식 Pleos Maven 저장소에서 내려받습니다. 차량 속도 API는 Android SDK의 `android.car.jar`로 컴파일합니다. 최소 Android API는 28입니다.

## 시연 방법

앱을 열고 프리셋을 선택한 뒤 재생을 누릅니다. Pleos/AAOS 차량 속도 신호를 사용할 수 없는 기기에서는 슬라이더로 속도를 바꿔 음악 변화를 시연할 수 있습니다. 차량 신호를 받으면 슬라이더가 숨겨집니다.

## Release APK

프로젝트 루트의 `signing.properties`에 `storeFile`, `storePassword`, `keyAlias`, `keyPassword`를 지정한 뒤 `gradle :app:assembleRelease`로 빌드합니다. 서명키와 비밀번호는 공개 GitHub 저장소에 올리지 않습니다. 공모전 제출용 APK는 `apk/홍원택_DriveSoundtrack_release.apk` 경로에 둘 수 있습니다.

## 현재 검증 범위

`assembleDebug`와 `assembleRelease` 컴파일, release APK 서명 검증, PC에서 동일 사운드 렌더러로 20초 WAV 생성은 성공했습니다. 일반 Android 14 에뮬레이터에서 release APK 설치와 화면 실행, 재생·일시정지 미디어 키, 프리셋 이동, 시연 속도에 따른 BPM 변화를 확인했습니다. 앱 화면 녹화와 합성 음원을 결합한 시연 영상도 준비했습니다. Pleos 전용 에뮬레이터 및 실제 차량에서 차량 신호·주행 중 UX 정책은 아직 검증하지 않았습니다.

## 남은 작업

- Pleos Connect 에뮬레이터에서 속도·주행 모드와 오디오 포커스 통합 시험
- Pleos 전용 에뮬레이터에서 `CarUxRestrictions` 연동 결과 확인
- 음악 패턴과 전환 품질 조정, 장시간 재생 안정성 확인
