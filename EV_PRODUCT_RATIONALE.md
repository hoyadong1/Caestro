# 전기차를 위한 Drive Soundtrack — 제품 근거와 개발 기준

작성: 2026-11-03 · 구현 기준: 0.3.0 첫 악기 연동

## 문제와 기회

전기차는 엔진 회전음·변속음이 주행 감각을 전달하던 내연기관 차량과 다르다. 조용함 자체를 결함이라고 단정하지 않는다. 정숙함을 선호하는 사람도 있고, 조작에 반응하는 소리를 통해 더 풍부한 실내 경험을 원하는 사람도 있다. 현대자동차 역시 일부 전기차에서 속도·가속 페달·주행 모드에 반응하는 Active Sound Design을 제공한다. 이 앱은 그 존재를 근거로 **실내의 선택 가능한 소리 경험**을 제안한다.

**제품 가설:** 자동차의 조작 신호를 악기별 연주 방식에 매핑하면, 고정된 배경음악이나 단순 가상 엔진음보다 차와 음악이 함께 움직인다는 느낌을 줄 수 있다. 이 가설은 아직 사용자 평가로 검증되지 않았다. 시연·인터뷰에서 듣는 즐거움, 인과관계 이해, 거슬림, 볼륨/주행 주의 영향 등을 확인한다.

## 기존 가상 엔진음과의 차이

| 기존 접근 | Drive Soundtrack의 제안 |
| --- | --- |
| 엔진음 또는 RPM/변속 느낌을 모사 | EV의 정숙함을 유지하며 원하는 음악 세계를 선택 |
| 속도·페달에 주로 하나의 음색이 반응 | 속도=드럼, 페달/가속도=베이스, 제동=화성, 조향=선율, 와이퍼=질감 |
| 차량이 낼 법한 소리를 재현 | 차량이 **악기 앙상블을 지휘**하는 음악 경험 |

별도의 볼륨·켜기/끄기 선택을 제공한다. 급가속을 보상하는 사운드, 엔진음처럼 오해할 수 있는 위험 경고음, 내비/안전 안내를 가리는 연주를 만들지 않는다.

## 안전과 범위

이 앱의 소리는 **실내 미디어 재생**이다. 보행자가 차량을 인지하도록 밖으로 내는 법정 경고음인 AVAS를 대체하거나 변경하지 않는다. 오디오 포커스 규칙에 따라 안내·통화·안전 관련 소리에 자리를 내주고, 주행 중 화면은 플랫폼 제한을 따른다. 주행 능력이나 안전성이 향상된다고 주장하지 않는다.

## 0.3.0에서 실제 구현한 첫 단계

- 속도에 따른 BPM 변화 폭을 크게 줄이고 드럼 밀도·음악 에너지에 역할을 부여했다.
- Pleos Brake 상태, SteeringWheel 각도, TurnSignal, Wiper의 읽기 전용 리더를 추가했다. 실제 차량/에뮬레이터의 변화 콜백 검증은 별도 단계다.
- Android Automotive 가속 페달 속성을 선택 입력으로 추가했다. 현 Pleos v2.1 AVD는 해당 권한 자체가 없어, 이 AVD에서는 속도 변화로 계산한 추진감 또는 시연 입력을 베이스에 사용한다. **추정 가속도와 실제 페달값을 UI에서 구분한다.**
- 정차 화면에 차량 대신 베이스·드럼·건반·선율의 간단한 악기 무대를 넣었다. [고급 시각 콘셉트](demo/instrument-stage-v2-concept.png)는 향후 디자인 목표이며 현재 앱 캡처가 아니다.

## 다음 검증

1. 에뮬레이터 콘솔의 제동·조향·방향지시등·와이퍼를 바꾸며 앱 표시와 실제 오디오 반응을 각각 확인한다.
2. 일정 속도와 여러 입력 조합을 재생해, 각각의 악기 반응을 청각적으로 구별할 수 있는지 확인한다.
3. 정차 시연 화면의 배치·가독성 및 주행 제한 시 시스템 미디어 UI 전환을 확인한다.
4. 실제 EV 운전자 평가 전에는 만족도·안전 효과를 검증된 사실처럼 표시하지 않는다.

## 근거

- [현대자동차 EV Active Sound Design 설명](https://ownersmanual.hyundai.com/full_webhelp/NE1a/2025/en_US/id2315B40J05Z.html)
- [현대자동차 N Active Sound+ 설명](https://ownersmanual.hyundai.com/full_webhelp/NE1N/2025/en_UK/id2362E0I0BHS.html)
- [전기차 소리와 소비자 만족도의 음향심리 연구](https://www.sciencedirect.com/science/article/pii/S0003682X1830433X)
- [미국 NHTSA의 보행자용 전기차 외부 경고음 설명](https://www.nhtsa.gov/sites/nhtsa.gov/files/documents/812347-minimumsoundrequirements.pdf)
- [Pleos 음악 앱 및 오디오 포커스 정책](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/music-audio-app-developement-guide), [오디오 포커스 지침](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/audio-focus-development-guide/audio-focus-development)
