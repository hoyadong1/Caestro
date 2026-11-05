# 콘솔 주행 녹화

Pleos Virtual Console로 D단 주행을 하면서, 에뮬레이터 화면·콘솔 화면·앱이 실제로 낸 소리를 한 영상으로 녹화합니다.

- `driver.js`를 콘솔 페이지에 주입해 ACCEL/BRAKE 페달을 눌러 앱 시나리오와 같은 속도 곡선(0 → 30 → 50 → 70 → 95 → 130 → 0 km/h)을 따라갑니다. 속도는 콘솔 자체 물리 엔진이 만들고, 앱은 일반 `PERF_VEHICLE_SPEED` 경로로 받습니다.
- `record.ps1`은 D단을 선택하고 헤드리스 Chrome으로 콘솔을 띄워 화면을 스트리밍합니다(`capture-screencast.ps1`). 에뮬레이터 화면은 `adb emu screenrecord`로 30fps 녹화합니다. 호스트 측 녹화라 게스트가 느려지지 않습니다. 앱 소리는 디버그 전용 `ARM_DEBUG_CAPTURE`로 PCM을 받고, `sample-pcm.ps1`이 PCM 크기와 실제 시각을 기록합니다.
- `compose.py`는 오디오를 기준 시계로 씁니다. 30fps 출력 프레임마다 오디오 시각을 실제 시각으로 바꿔 같은 순간의 에뮬레이터·콘솔 프레임을 고릅니다. 출력은 1920×608 H.264/AAC입니다.

준비: 헤드리스로 실행한 Pleos 에뮬레이터, 사용자 10에 설치한 **디버그** APK, `adb forward tcp:8088 tcp:8088`, Chrome, WSL의 `ffmpeg`.

```powershell
.\demo\console-drive\record.ps1 -Out C:\temp\take1 -Duration 100
python .\demo\console-drive\compose.py C:\temp\take1 C:\temp\take1\drive.mp4
```

에뮬레이터가 오디오를 실시간보다 느리게(약 0.8배) 만들기 때문에 영상은 실제 주행보다 짧고, 화면은 실제보다 약간 빠르게 흘러갑니다.
