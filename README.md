# SF Empire Offline v0.1 — 로컬 AI 구동 확인판

이 저장소의 첫 목표는 **Galaxy S25 Ultra에서 인터넷/API 없이 GGUF 모델을 직접 실행하는 APK 빌드가 가능한지 검증**하는 것입니다.

이 버전은 아직 최종 SF Empire UI가 아닙니다. GitHub Actions가 최신 `llama.cpp`의 공식 Android 데모를 빌드하며, 그 APK에서 휴대폰의 GGUF 모델을 선택해 로컬 추론을 시험합니다. 이 단계가 성공하면 다음 버전에서 SF Empire UI, `rules.txt`, 게임 상태 엔진, 장기 기억을 연결합니다.

## 빌드

1. 이 프로젝트의 파일을 GitHub 저장소에 올립니다.
2. 저장소의 **Actions** 탭을 엽니다.
3. `Build SF Empire Offline Test APK` 작업이 자동 실행됩니다. 안 뜨면 **Run workflow**를 누릅니다.
4. 완료 후 실행 기록 아래 **Artifacts**에서 `SF-Empire-Offline-Test-APK`를 다운로드합니다.
5. ZIP을 풀어 `SF-Empire-Offline-Test.apk`를 설치합니다.

## 테스트

앱에서 휴대폰에 저장된 **Gemma 3 4B GGUF** 파일을 선택합니다. 처음에는 컨텍스트를 크게 잡지 말고 약 4096 수준으로 시작하는 것을 권장합니다.

테스트 프롬프트:

```
너는 SF 우주 제국 전략게임의 GM이다.
현재 수도 행성 1개, 인구 52억, 식량 120, 에너지 90이며 첫 초광속 탐사선을 완성했다.
플레이어가 가장 가까운 항성계로 탐사선을 보냈다.
사건 결과와 다음 선택지 5개를 한국어로 제시해라.
```

## 중요한 점

- APK 빌드에는 GitHub Actions 인터넷이 필요하지만, **완성된 앱의 모델 추론은 휴대폰 안에서 실행**됩니다.
- 모델 파일은 APK에 넣지 않습니다. 수 GB이기 때문에 휴대폰 저장소의 `.gguf` 파일을 선택하는 방식입니다.
- `rules.txt`는 다음 단계의 SF Empire 전용 GM/게임 엔진 연결을 위해 포함되어 있습니다.
- 이번 버전은 '로컬 AI 엔진이 실제 APK에서 구동되는지'만 검증하는 v0.1입니다.
