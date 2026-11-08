# Steady Ride

동승자 멀미 저감 주행 보조 앱 · 현대자동차그룹 차량용 앱 개발 공모전 (Pleos Connect)

운전자는 다음 움직임을 알기 때문에 멀미하지 않습니다. Steady Ride는 차량 신호와 내비 경로로 다음 움직임을 예측해
동승자에게 미리 알려주고, 멀미 위험이 높아지면 공조·시트 통풍·실내 조명을 자동으로 조절합니다.

## 주요 기능

| 기능 | 설명 | 사용 API |
|---|---|---|
| 동승자 예고 | "3초 뒤 오른쪽으로 회전", "2초 뒤 과속방지턱" | NaviHelper `onTBTInfo`, `onCautionPointInfo` · Vehicle SDK 조향각·브레이크 |
| 멀미 위험도 | 0~100, MSDV(ISO 2631) 계열 단순화 지표 | AAOS `PERF_VEHICLE_SPEED` · 조향각 |
| 멀미 완화 모드 | 외기 유입, 풍량 증가, 조수석 통풍 | Vehicle SDK HVAC·Seat |
| 앰비언트 신호 | 예고 순간 실내 조명 밝기 변화 | Vehicle SDK Light |
| 운전자 리포트 | 정차 중에만 급조향·급가감속 요약 | — |
| 편안한 경로 | 내비 경로 옵션 `COMFORTABLE`로 재탐색 | NaviHelper `changeRouteOption` |
| 음성 명령 | Gleo AI: 완화 모드 켜기/끄기, 편안한 경로 | `assets/schema.json` |
| 데모 주행 | 우회전 → 과속방지턱 → S커브 → 정지 75초 반복 | 앱 내장 시나리오 |

## 실행 방법

1. Android Studio(최신) 설치
2. Pleos Connect Emulator v2.1.0 설치 및 AVD 생성
   ([설치 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator))
3. 이 저장소를 Android Studio에서 **Open** → Gradle sync
4. 에뮬레이터 실행 후 ▶ Run
5. 앱 상단에서 **데모 주행**을 선택하면 가상 주행으로 모든 기능을 확인할 수 있습니다.
   **실차 신호**에서는 [Virtual Console](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/use-vehicle-emulator)로 조향·브레이크·좌석을 바꿔 확인합니다.

```bash
adb forward tcp:8088 tcp:8088   # 이후 브라우저에서 http://127.0.0.1:8088
```

## 테스트 환경

- Pleos Connect Emulator v2.1.0 (Android 14, API 34, arm64)
- minSdk 28 · Kotlin · Jetpack Compose · Gradle 9.8 · AGP 9.5
- Pleos SDK: Vehicle 2.3.2, NaviHelper 3.0.5

## APK

릴리스 APK는 `apk/팀명_앱이름_release.apk` 또는 GitHub Releases에 올립니다. (제출 전 추가 예정)

## 구조

```
app/src/main/java/ai/steadyride/app/
├── core/ComfortEngine.kt        예측·멀미 위험도 (차량 비의존 순수 로직)
├── vehicle/LiveVehicleSource.kt Pleos SDK + Car API + 내비 + 주행 규제
├── vehicle/DemoDriveSource.kt   데모 주행 시나리오
├── vehicle/ComfortActuator.kt   조명·공조·통풍 제어
├── SteadyRideController.kt      신호 → 엔진 → 제어 연결 (10 Hz)
└── ui/MainScreen.kt             화면
docs/                            기획 검토, Pleos 문서 분석
```

## 협업 규칙

- `main`에 직접 커밋하지 않고 브랜치를 만들어 PR로 합칩니다.
- 클라이언트 시크릿 등 키는 `local.properties`에만 두고 커밋하지 않습니다.
