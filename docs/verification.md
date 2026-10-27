# 초안 검증 기록

확인일: 2026-10-27 (Asia/Seoul)
대상: 0.1.0-alpha01

## 자동 검증

아래 Gradle 작업이 Windows/JDK 17/Android SDK 35 환경에서 성공했다.

```sh
./gradlew :core:model:test :core:comcigan:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

- JVM 회귀 테스트 **10개 통과**: 파서 9개, 모델의 연도·주 경계 1개.
- 디버그 APK 생성 성공.
- Android Lint 오류 0개. 의존성의 새 버전 제안 경고는 남겨 두었다.
- `app:testDebugUnitTest`에는 별도 UI 테스트가 없으며 NO-SOURCE다.
- Room 데이터베이스 v1 스키마를 저장소에 포함했다.

회귀 항목: 기존/래퍼형 교사 필드, 길이가 바뀐 요청 경로, MS949 검색어 인코딩,
검색 응답 NUL 패딩·동명이교, 세 자리 교사 코드, 과목 분리 값, 변경 전후 방향,
빈 교시 보존, 교시 수 메타데이터 이후의 패딩, 미게시·잘못된 응답·학급.

## Kotlin 실서버 조회

초안의 ComciganClient와 ComciganParser로 아래 명령을 실행했다.

```sh
./gradlew :core:comcigan:run --args="경기북과학고"
```

| 학교·학급 | 주 시작일 | 수업이 있는 교시 | 변경 판정 |
|---|---|---:|---:|
| 경기북과학고등학교 1-1 | 2026-10-26 | 34 | 2 |
| 경기북과학고등학교 1-1 | 2026-11-02 | 34 | 0 |

학교 검색과 두 주의 조회·파싱이 정상 종료됐다. 원본 전체 응답이나 교사명은 로그에 출력하지 않았다.
이전 Python 기준 구현 검증은 [별도 결과](research/python-live-check.json)에 보존했다.

## 아직 확인하지 않은 항목

- 실제 Android 기기·에뮬레이터에서 앱 실행, 화면 배치와 TalkBack 확인
- Android 런타임의 한글 인코딩 및 네트워크 접속
- 앱 화면에서 학교 선택→저장→재실행 및 비행기 모드 캐시 조회의 실제 조작
- 공식 시간표 화면과 모든 교시를 대조하는 검증
- 학기 전환, 다양한 학교·서버 장애에 대한 장기 검증

위 항목은 JVM 실서버 검사나 APK 빌드 성공으로 대체하지 않는다.

## 로컬 빌드 환경

현재 작업 폴더의 한글 경로에서 Gradle 테스트 worker 클래스 로딩 오류가 발생해,
같은 폴더에 임시 ASCII 드라이브 경로를 연결하여 빌드했다.
JDK·SDK·Gradle 캐시·설치 스크립트는 프로젝트 로컬의 무시된 경로에 보관하고 커밋하지 않았다.
다른 개발 환경에서는 README의 일반 SDK 설치·Gradle 명령을 사용한다.
