# 들러 (Deulleo · Stop by)

> 폰에서 모아 둔 오늘의 일정과 볼일을, 차에 타는 순간 가게·순서·시간·주차까지 정리된 하나의 경로로 바꾸고,
> 주행 중에는 말 한마디로 다시 짜 주는 차량용 음성 에이전트

현대자동차그룹 차량용 앱 개발 공모전(Pleos Connect) 출품작의 프로토타입입니다.

## 시연 시나리오

| 단계 | 차량 상태 | 들러의 동작 |
|---|---|---|
| 탑승 브리핑 | 시동 후 P단 | 오늘 일정(15:00 미용실)·할 일(꽃 사기)·차량 경고(타이어)를 묶어 계획을 세우고 음성으로 제안 |
| 출발 | "응" / [출발] | NaviHelper로 목적지 + 경유지(최대 2곳) 안내 시작 → 화면이 **주행 모드**로 전환 |
| 도착 | 내비 도착 이벤트·근접 알림 + P단 | **도착 모드**: 주차장에서 가게까지 도보 안내 |
| 재출발 | P → D | 현재 볼일 완료 처리, 다음 구간 안내 |
| 지연 | "40분 늦을 것 같아" | 마감 시간이 안 맞는 가게를 같은 업종의 다른 가게로 바꿔 재계획 |
| 추가 | "케이크도 사야 해" | 동선에 맞는 빵집을 넣고, 이웃한 볼일이면 "주차는 한 번만" 안내 |
| 트렁크 | 볼일 장소에서 트렁크 열림 → 닫힘 | 신선식품이면 완료 처리 + "실내 온도 22도로 낮출까요?" → 공조 제어 |

## 프로젝트 구조

```
deulleo/
├── core/   순수 Kotlin(안드로이드 의존 없음) — JVM에서 단위 테스트
│   ├── DeulleoAgent.kt        에이전트: 브리핑, 발화 처리, 재계획
│   ├── planner/RoutePlanner   순서 최적화(전수 탐색 + 예약·영업시간·신선식품·귀가 시각 제약)
│   ├── planner/PlaceCatalog   장소 검색 인터페이스 + 시연용 샘플(가상 가게)
│   ├── voice/IntentParser     한국어 규칙 기반 의도 파서(→ 서비스 단계에서 LLM 도구 호출로 교체)
│   └── vehicle/VehicleRules   차량 상태 → 차량 관리 할 일, UI 모드 판단
├── app/    Pleos Connect 차량 앱 (Kotlin, Jetpack Compose, minSdk 28)
│   ├── pleos/PleosVehicleSource  Vehicle SDK: Gear·Fuel·EvBattery·Safety(경고등)·Door(트렁크)·HVAC
│   ├── pleos/PleosNavigator      NaviHelper SDK: requestRouteWithWaypoints, 도착 이벤트
│   ├── pleos/PleosVoice          SpeechToText / TextToSpeech SDK (ko-KR)
│   ├── pleos/PleosLocation       FusedLocation SDK: 현재 위치, 근접 알림
│   ├── MainViewModel.kt          차량 이벤트 ↔ 에이전트 ↔ 화면 연결
│   ├── ui/Screens.kt             관리 / 탑승 브리핑 / 주행 / 도착 4가지 화면
│   └── assets/gleo_app_schema.json  Gleo Interface App Schema(함수 3개)
└── apk/    제출용 release APK
```

## 실행 방법

### 1. 개발 환경
- Android Studio 최신 버전, JDK 17
- Android API 28 이상, Gradle 8.0 이상(래퍼: Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.21)
- Pleos Connect SDK는 `settings.gradle.kts`에 등록된 Pleos Maven 저장소에서 자동으로 받습니다.

### 2. Pleos Connect Emulator 준비
1. Android Studio → Tools → SDK Manager → SDK Update Sites에서 `+`
   - Name: `Pleos Connect System Image`
   - URL(Windows/Linux): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
   - URL(Mac): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
2. SDK Platforms → API 34의 **Pleos Connect System Image** 설치 후 Android Studio 재시작
3. Device Manager → Create device → Form Factor **Automotive** → **Pleos Connect v2.1.0** → RAM 4GB, Cold boot

### 3. 빌드·설치
```bash
./gradlew :app:installDebug          # 에뮬레이터에 설치
./gradlew :app:assembleRelease       # release APK → app/build/outputs/apk/release/
./gradlew -Pcore.only :core:test     # 에이전트·경로 계획 단위 테스트(안드로이드 SDK 불필요)
```

### 4. 시연 (Virtual Console)
```bash
adb forward tcp:8088 tcp:8088        # 브라우저에서 http://127.0.0.1:8088/
```
- Virtual Console에서 **기어 P**로 두고 앱을 열면 탑승 브리핑이 나옵니다.
- [출발] 또는 마이크 → "응" → 기어 **D**로 바꾸면 주행 모드로 전환됩니다.
- 정차 중 화면 하단 **시연 도구**: 타이어 경고 스위치, 도착 처리, 발화 직접 입력(에뮬레이터에 마이크가 없을 때)

## 테스트 환경 및 필요한 설정

| 항목 | 값 |
|---|---|
| Pleos Connect Emulator | v2.1.0 (API 34) |
| 화면 규격 | _(제출 전 기재: AVD 설정의 해상도)_ |
| minSdk / targetSdk | 28 / 34 |
| Pleos SDK | Vehicle 2.3.2, NaviHelper 3.0.5, SpeechToText 3.3.1, TextToSpeech 3.3.1, FusedLocation 1.1.3 |

선택 설정 (`local.properties`, git에 올리지 않음):
```properties
pleos.clientId=...       # Pleos Playground My Project 자격 증명
pleos.clientSecret=...   # 있으면 STT/TTS를 HYBRID(서버 우선) 모드로, 없으면 ON_DEVICE 모드로 실행
```

release 서명 키(선택, `keystore.properties`): 없으면 디버그 키로 서명한 설치 가능한 release APK가 만들어집니다.

### 권한
`app/src/main/AndroidManifest.xml`에 선언한 Pleos 권한: `CAR_DRIVE`, `CAR_ENERGY`, `CAR_SAFETY`, `CAR_DOORS`,
`CAR_CLIMATE`, `CONTROL_CAR_CLIMATE`, `NAVI_ROUTE`, `NAVI_ROUTE_SEARCH`, `STT_SERVICE`, `TTS_SERVICE`, `FUSED_LOCATION`.
Pleos 퍼미션은 Pleos App Market과 사전 협의·승인된 범위에서만 사용할 수 있습니다(조회 위주, 제어는 공조만).

## APK 파일 위치
`apk/성명_Deulleo_release.apk` _(제출 전 `assembleRelease` 결과물을 이 이름으로 복사)_

## 현재 범위와 다음 단계
- ✅ 에이전트 핵심 로직(계획·재계획·의도 해석) + 단위 테스트
- ✅ Pleos SDK 5종 연동 코드, 4가지 화면 모드
- ⏳ 장소 검색: 시연용 가상 가게 → 카카오 로컬 API(키워드·카테고리, 주차장 PK6)로 교체
- ⏳ 의도 해석: 규칙 기반 → LLM 도구 호출(function calling)
- ⏳ 폰 앱: 캘린더 연동, 스크린샷 공유 → 일정 자동 인식, 서버 동기화
- ⏳ Gleo Interface 등록(`assets/gleo_app_schema.json`) 및 Action 수신 처리
- ⏳ 기어 조회를 표준 `GEAR_SELECTION` 속성으로 이전(Pleos Gear API deprecated)
