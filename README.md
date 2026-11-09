# Drive Soundtrack

[![Drive Soundtrack 시연 페이지 열기: D단 130 km/h에서 악기 6개가 연주되는 앱 화면](docs/readme-thumbnail.png)](https://hoyadong1.github.io/DriveSoundtrack/)

**차량의 주행 신호가 오케스트라를 지휘하는 Android Automotive 음악 앱입니다.**

속도가 오르면 베이스에 리듬·화음·분산음·선율·고음이 차례로 합류하고, 가속·제동·조향·방향지시등이 각 악기의 연주를 바꿉니다. 파헬벨 「캐논」, 바흐 「평균율 전주곡」, 사티 「짐노페디」, 베토벤 「환희의 송가」, 그리그 「산왕의 궁전에서」를 실제 악기 소리로 연주하고, 단계마다 악기를 골라 **나만의 악단**을 꾸릴 수 있습니다. 악보와 악기는 차 밖의 웹 제작 플랫폼에서 만들어 클라우드로 공유하고, 차에서는 정차 중 **받기** 탭에서 내려받아 앨범과 악단에서 씁니다. 모든 차가 가진 신호만 쓰므로 내연기관·전기차 어디서나 동작하며, 엔진 소리가 사라지는 전기차가 늘수록 그 가치가 커집니다. 음악은 기기에서 실시간으로 만들어 스트리밍이나 음원 저작권료가 필요 없습니다.

**[쇼케이스](https://hoyadong1.github.io/DriveSoundtrack/)** · **[D단 주행 영상](https://hoyadong1.github.io/DriveSoundtrack/console-d-drive-app.mp4)** · **[클라우드 받기 영상](https://hoyadong1.github.io/DriveSoundtrack/cloud-demo.mp4)** · **[APK](apk/홍원택_DriveSoundtrack_release.apk)** · **[📖 위키](https://github.com/hoyadong1/DriveSoundtrack/wiki)**

## 실행

1. [`apk/홍원택_DriveSoundtrack_release.apk`](apk/홍원택_DriveSoundtrack_release.apk)를 Pleos Connect 또는 Android Automotive 기기에 설치하고, 차량 속도 권한을 허용합니다.
2. 앨범(오리지널·클래식 드라이브·내 앨범·받은 곡)에서 곡을 고르고 재생한 뒤 주행하면 속도에 따라 악기가 더해집니다. **악단** 탭에서 단계마다 악기를 바꿀 수 있고, 고르는 단계는 정차 중에도 바로 들려줍니다. **받기** 탭에서는 클라우드의 악보와 악기를 내려받습니다(정차 중, 인터넷 연결 필요).
3. Pleos 에뮬레이터에서는 `.\demo\start-interactive.ps1`로 실행합니다. 앱의 **시연 주행** 버튼이나 Virtual Console의 D단 주행으로 확인할 수 있습니다. → [자세히](https://github.com/hoyadong1/DriveSoundtrack/wiki/Development#실행)

## 테스트 환경

- Pleos Connect v2.1 에뮬레이터(Android Automotive), Windows 11
- Android Studio, Android SDK API 37(`android.car.jar`), JDK 17 이상, 최소 실행 API 28
- Pleos Vehicle SDK 2.3.2 (Pleos Maven 저장소, [`settings.gradle`](settings.gradle))

```powershell
.\gradlew.bat :app:assembleDebug        # 빌드
.\gradlew.bat :app:testDebugUnitTest    # 단위 테스트 (화음·클릭·조향·악보·악기 음량·앨범·클라우드 받기)
```

release APK를 다시 빌드하려면 저장소에 없는 서명키가 필요합니다. → [빌드·테스트와 검증 범위](https://github.com/hoyadong1/DriveSoundtrack/wiki/Development)

## 클라우드 악보·악기

악보와 악기는 차 밖에서 만들고, 차 앱은 받아서 연주만 합니다. 예시 클라우드는 GitHub Pages의 [`docs/cloud`](docs/cloud)이며 드보르자크 「라르고」 악보, VSCO 2 CE 녹음을 올려 만든 트롬본, 음색을 디자인한 핸드팬이 있습니다. 팩 형식과 만드는 도구는 [`tools/cloud`](tools/cloud/README.md)에 있습니다. [받기 시연 영상](https://hoyadong1.github.io/DriveSoundtrack/cloud-demo.mp4)은 Pleos 에뮬레이터에서 실제로 내려받고 악단에 넣어 연주한 녹화입니다.

## 더 보기

| | |
|---|---|
| [기능과 사운드 설계](https://github.com/hoyadong1/DriveSoundtrack/wiki/Features) | 곡과 앨범, 나만의 악단, 속도별 단계, 차량 신호별 연주 방식과 수치 |
| [아키텍처](https://github.com/hoyadong1/DriveSoundtrack/wiki/Architecture) | 구조도, 파일별 역할, 저장소 폴더 |
| [개발과 실행](https://github.com/hoyadong1/DriveSoundtrack/wiki/Development) | 빌드·테스트, 기기·에뮬레이터 실행, 녹화, 검증 범위 |
| [제품 근거](https://github.com/hoyadong1/DriveSoundtrack/wiki/Product-Rationale) | 파워트레인 중립 설계와 전기차 시대의 기회 |
| [버전 이력](https://github.com/hoyadong1/DriveSoundtrack/wiki/Version-History) | 0.1부터 현재까지 |

시연 영상의 소리는 녹화 중 앱이 실제로 출력한 PCM이며, 모든 검증은 Pleos 에뮬레이터 기준입니다. 앱은 위치·속도 데이터를 저장하거나 전송하지 않습니다.

클래식 곡은 저작권이 끝난 원곡을 악보로 직접 입력했습니다. 실제 악기 샘플은 [VSCO 2 Community Edition](https://github.com/sgossner/VSCO-2-CE)(Versilian Studios, CC0)을 다듬어 썼습니다(`tools/samples/`).
