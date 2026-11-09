# Drive Soundtrack

[![Drive Soundtrack 시연 페이지 열기: D단 130 km/h에서 악기 6개가 연주되는 앱 화면](docs/readme-thumbnail.png)](https://hoyadong1.github.io/DriveSoundtrack/)

**차량의 주행 신호가 여섯 악기를 연주하는 Android Automotive 음악 앱입니다.**

속도가 오르면 베이스에 드럼·화음·아르페지오·선율·고역 질감이 차례로 합류하고, 가속·제동·조향·방향지시등이 각 악기의 연주를 바꿉니다. 모든 차가 가진 신호만 쓰므로 내연기관·전기차 어디서나 동작하며, 엔진 소리가 사라지는 전기차가 늘수록 그 가치가 커집니다. 음원 파일 없이 Kotlin으로 실시간 합성합니다.

**[쇼케이스](https://hoyadong1.github.io/DriveSoundtrack/)** · **[D단 주행 영상](https://hoyadong1.github.io/DriveSoundtrack/console-d-drive-app.mp4)** · **[APK](apk/홍원택_DriveSoundtrack_release.apk)** · **[📖 위키](https://github.com/hoyadong1/DriveSoundtrack/wiki)**

## 실행

1. [`apk/홍원택_DriveSoundtrack_release.apk`](apk/홍원택_DriveSoundtrack_release.apk)를 Pleos Connect 또는 Android Automotive 기기에 설치하고, 차량 속도 권한을 허용합니다.
2. 사운드스케이프를 고르고 재생한 뒤 주행하면 속도에 따라 악기가 더해집니다.
3. Pleos 에뮬레이터에서는 `.\demo\start-interactive.ps1`로 실행합니다. 앱의 **주행 시나리오 시작** 버튼이나 Virtual Console의 D단 주행으로 확인할 수 있습니다. → [자세히](https://github.com/hoyadong1/DriveSoundtrack/wiki/Development#실행)

## 테스트 환경

- Pleos Connect v2.1 에뮬레이터(Android Automotive), Windows 11
- Android Studio, Android SDK API 37(`android.car.jar`), JDK 17 이상, 최소 실행 API 28
- Pleos Vehicle SDK 2.3.2 (Pleos Maven 저장소, [`settings.gradle`](settings.gradle))

```powershell
.\gradlew.bat :app:assembleDebug        # 빌드
.\gradlew.bat :app:testDebugUnitTest    # 합성 엔진 단위 테스트
```

release APK를 다시 빌드하려면 저장소에 없는 서명키가 필요합니다. → [빌드·테스트와 검증 범위](https://github.com/hoyadong1/DriveSoundtrack/wiki/Development)

## 더 보기

| | |
|---|---|
| [기능과 사운드 설계](https://github.com/hoyadong1/DriveSoundtrack/wiki/Features) | 세 모드, 속도별 악기, 차량 신호별 연주 방식과 수치 |
| [아키텍처](https://github.com/hoyadong1/DriveSoundtrack/wiki/Architecture) | 구조도, 파일별 역할, 저장소 폴더 |
| [개발과 실행](https://github.com/hoyadong1/DriveSoundtrack/wiki/Development) | 빌드·테스트, 기기·에뮬레이터 실행, 녹화, 검증 범위 |
| [제품 근거](https://github.com/hoyadong1/DriveSoundtrack/wiki/Product-Rationale) | 파워트레인 중립 설계와 전기차 시대의 기회 |
| [버전 이력](https://github.com/hoyadong1/DriveSoundtrack/wiki/Version-History) | 0.1부터 현재까지 |

시연 영상의 소리는 녹화 중 앱이 실제로 출력한 PCM이며, 모든 검증은 Pleos 에뮬레이터 기준입니다. 앱은 위치·속도 데이터를 저장하거나 전송하지 않습니다.
