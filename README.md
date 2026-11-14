# Steady Ride

동승자 멀미 저감 주행 보조 앱 · 현대자동차그룹 차량용 앱 개발 공모전 (Pleos Connect)

운전자는 다음 움직임을 알기 때문에 멀미하지 않습니다. Steady Ride는 차량 신호와 내비 경로로 다음 움직임을 예측해
동승자에게 미리 알려주고, 멀미 위험이 높아지면 공조·시트 통풍·실내 조명을 자동으로 조절합니다.

## 주요 기능

| 기능 | 설명 | 사용 API |
|---|---|---|
| 동승자 예고 | "3초 뒤 오른쪽 회전", "2초 뒤 과속방지턱" | NaviHelper `onTBTInfo`, `onCautionPointInfo` · Vehicle SDK 조향각·브레이크 |
| 멀미 위험도 | 0~100, MSDV(ISO 2631) 계열 단순화 지표 | AAOS `PERF_VEHICLE_SPEED` · 조향각 |
| 스테디 플로우 | 가장자리 입자가 가속·제동·회전 방향으로 흐름, 정지 시 숨쉬기. 계절 테마(꽃잎·물방울·단풍·눈) | 화면 (동승자 디스플레이 전용) |
| 앰비언트 모션 라이트 | 실내 조명 색·밝기로 움직임 표현(제동 앰버·가속 청록·회전 보라), 기동 2초 전 선반영 | Vehicle SDK Light (밝기 0~100, RGB) |
| 공간 사운드 | 회전 1초 전 소리가 반대편에서 회전 쪽으로 이동(ITD·도플러), 실시간 합성 | Android `AudioTrack` · 오디오 포커스 |
| 100Hz 사운드 | 출발 시 100Hz 순음 1분 (Kagawa 외 2025) | Android `AudioTrack` |
| 멀미 경로 분석 | 내비가 안내 중인 경로의 회전·급회전·유턴·과속방지턱으로 멀미 지수 계산. 내비에서 고른 다른 경로도 자동 분석해 비교하고, 허용 지연(+5~20분) 안에서 가장 편한 경로를 추천 | NaviHelper `getTBTInfo`, `onCautionPointInfo`, `getDestinationInfo`, `getRouteStateInfo`, `requestRoute` |
| 운전 코칭 | 급제동·급출발·페달 잦은 전환·과속 회전 분석 → 맞춤 문구 | — |
| 멀미 완화 모드 | 외기 유입, 풍량 증가, 조수석 통풍 (원래 설정 복원) | Vehicle SDK HVAC·Seat |
| 음성 명령 | Gleo AI: 완화 모드 켜기/끄기, 편안한 경로 | `assets/schema.json` |
| 남양연구소 시나리오 | 내비 현재 위치 → 현대자동차 남양연구소 실제 경로 요청·확인 후 회전 안내를 멀미 케어에 연결 (`docs/NAMYANG_SCENARIO.md`) | NaviHelper `requestRoute`, `getRouteStateInfo`, `getDestinationInfo` |
| 분할 화면 | 내비와 1:1 분할(창 크기가 바뀌어도 주행 상태 유지), 좁은 창 전용 레이아웃 | Pleos 멀티 윈도 |
| 데모 주행 | 우회전 → 과속방지턱 → S커브 → 정체 구간 → 정지 반복, 경로 옵션 4종 비교 시연 | 앱 내장 시나리오 |

예고음 미리 듣기: `docs/sounds/*.wav`

### 멀미 경로 분석 사용법 (시험차 신호)

1. 내비에서 목적지를 설정하거나 **남양연구소로 안내 시작**을 누르면 현재 경로가 자동으로 분석됩니다.
2. 다른 경로와 비교하려면 **내비 열기** → 설정(⚙)에서 경로 옵션(유료도로 우회 등)을 바꾼 뒤,
   앱에서 **바뀐 설정으로 다시 탐색**을 누릅니다. 새 경로가 비교 목록에 추가됩니다.
3. 경로가 2개 이상이면 허용 지연(기본 +10분) 기준으로 가장 편한 경로에 **추천**이 표시됩니다.
   경로 선택은 운전자가 내비에서 직접 합니다.

에뮬레이터 실측 예 (현재 위치 → 남양연구소): 추천 경로 61 km·53분·회전 18 → 멀미 지수 61,
유료도로 우회 51 km·66분·회전 33 → 멀미 지수 106. 짧은 길이 항상 편한 길은 아닙니다.

## 실행 방법

1. Android Studio(최신) 설치
2. Pleos Connect Emulator v2.1.0 설치 및 AVD 생성
   ([설치 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator))
3. 이 저장소를 Android Studio에서 **Open** → Gradle sync
4. 에뮬레이터 실행 후 ▶ Run
5. 앱 상단에서 **데모 주행**을 선택하면 가상 주행으로 모든 기능을 확인할 수 있습니다.
   **시험차 신호**에서는 [Virtual Console](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/use-vehicle-emulator)로 조향·브레이크·좌석을 바꿔 확인합니다.

```bash
adb forward tcp:8088 tcp:8088   # 이후 브라우저에서 http://127.0.0.1:8088
```

## 테스트 환경

- Pleos Connect Emulator v2.1.0 (Android 14, API 34, arm64)
- minSdk 28 · Kotlin · Jetpack Compose · Gradle 9.8 · AGP 9.5
- Pleos SDK: Vehicle 2.3.2, NaviHelper 3.0.5

## 확인된 플랫폼 동작 (에뮬레이터 v2.1.0)

- 에뮬레이터 내비는 앱이 보낸 경로 옵션(`changeRouteOption`, `RouteInfo.routeOption`)을 반영하지 않고
  내비 자체 설정으로만 경로를 계산합니다. 그래서 시험차 모드는 "앱이 옵션을 바꾸는" 대신
  "내비가 준 경로를 분석·비교"합니다. 옵션 4종 자동 비교는 데모 주행에서 시연합니다.
- `RouteStateInfo.isRouting`은 "안내 중"이 아니라 "경로 계산 중"을 뜻합니다.
- 경로를 요청하면 내비가 앞으로 나오고 Android 14가 뒤로 간 앱을 정지(freeze)합니다.
  내비 작업 중에는 포그라운드 서비스(`NaviTaskService`)로 앱을 유지합니다.
- 내비 설정 변경만으로는 안내 중인 경로가 바뀌지 않아 같은 목적지를 다시 요청합니다.

## APK

릴리스 APK는 `apk/팀명_앱이름_release.apk` 또는 GitHub Releases에 올립니다. (제출 전 추가 예정)

## 구조

```
app/src/main/java/ai/steadyride/app/
├── core/          차량 비의존 순수 로직
│   ├── ComfortEngine.kt   예측·멀미 위험도
│   ├── DrivingCoach.kt    운전 패턴 분석·코칭 문구
│   ├── RoutePlanner.kt    경로 멀미 지수·허용 지연 내 추천
│   ├── NamyangScenario.kt 남양연구소 목적지·경로 확인 규칙
│   └── AmbientMood.kt     움직임 → 조명 색·밝기
├── vehicle/       Pleos SDK·Car API 연동(LiveVehicleSource, ScenarioNaviGateway), 데모 시나리오, 공조·조명 제어
├── audio/         공간 사운드·100Hz 합성(CueSynth)과 재생(CueSound)
├── ui/            화면(MainScreen), 스테디 플로우(SteadyFlow)
├── NaviTaskService.kt        내비 작업 중 앱 정지 방지
└── SteadyRideController.kt   신호 → 엔진 → 출력 연결 (10 Hz)
app/src/main/res/font/  현대하모니 폰트 (현대자동차그룹 저작권, 저장소 비공개 유지)
app/src/androidTest/    남양 시나리오 실기 검사(ScenarioProbe)
tests/                  남양 시나리오 정책 검사(SDK 비의존)
tools/                  에뮬레이터 위치 설정 스크립트(선택)
docs/                   기획 검토, Pleos 문서 분석, 남양 시나리오, 예고음 샘플
```

## 협업 규칙

- `main`에 직접 커밋하지 않고 브랜치를 만들어 PR로 합칩니다. zip 대신 브랜치로 주고받습니다.
- 클라이언트 시크릿 등 키는 `local.properties`에만 두고 커밋하지 않습니다.
