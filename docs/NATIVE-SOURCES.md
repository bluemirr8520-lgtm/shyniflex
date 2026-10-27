# 네이티브 대응 소스

shyniflex 1.0.53은 공식 mpv-android **2026-09-17**의 ARM64·ARMv7·x86_64 네이티브 라이브러리를 수정하지 않고 사용합니다. 전체 대응 소스는 APK와 함께 제공하는 `outputs/shyniflex-1.0.53-corresponding-source.zip`이며, 그 안의 `app-source.zip`이 해당 빌드의 앱 소스입니다.

- [APK와 CPU별 설치 파일](https://github.com/bluemirr8520-lgtm/shyniflex/releases/tag/v1.0.53)
- [전체 대응 소스 ZIP](https://github.com/bluemirr8520-lgtm/shyniflex/releases/download/v1.0.53/shyniflex-1.0.53-corresponding-source.zip)
- [앱 소스 태그 v1.0.53](https://github.com/bluemirr8520-lgtm/shyniflex/tree/v1.0.53)

CPU별 APK와 통합 APK는 동일한 앱 소스·버전·서명을 사용합니다. 이 대응 소스 ZIP에 세 CPU의 전체 네이티브 소스를 함께 제공합니다.

## 소스 묶음의 내용

`app-source.zip`은 해당 릴리스의 앱 소스입니다. `source-archives/`에는 원본 mpv-android 래퍼·빌드 스크립트와 주요 네이티브 의존성 14개, 하위 모듈 7개의 소스를 보관합니다. `native-source-manifest.json`에 각 아카이브의 출처·정확한 버전 또는 커밋·SHA-256·복원 위치를 기록하고, 원본 라이선스와 고지를 보존합니다. `jvm-source-jars/`에는 실제 런타임 Maven 의존성의 정확한 소스 JAR 103개와 버전·해시 명세를 제공합니다. 코드가 없는 충돌 방지용 Guava JAR 1개는 빈 아티팩트임을 검증해 명세에 기록했습니다. QR 코드 생성용 `com.google.zxing:core:3.5.4`의 소스 JAR과 Apache-2.0 고지도 포함합니다. 서명키와 계정·서버 자료는 포함하지 않습니다.

상위 저장소의 ZIP에는 Git 하위 모듈의 실제 내용이 포함되지 않습니다. 그래서 FreeType의 dlg와 libplacebo의 glad·Jinja·MarkupSafe·fast_float·Vulkan-Headers·Nuklear를 각각의 고정 커밋으로 함께 제공합니다. Vulkan과 데모가 비활성화된 구성에서도 원본 재귀 체크아웃을 복원할 수 있도록 전체 하위 모듈을 보존합니다.

## 복원

대응 소스 ZIP을 푼 뒤 Python 3.12 이상에서 실행합니다. 목적지는 아직 존재하지 않는 새 디렉터리를 지정합니다.

```sh
python restore-native-sources.py --destination /path/to/new-native-tree
```

복원 스크립트는 모든 네이티브 아카이브의 SHA-256을 검사한 뒤 원본 래퍼와 `buildscripts/deps/`의 의존성·하위 모듈을 복원합니다. 소스 수집 결과의 체크섬 검증이며, 재컴파일한 바이너리가 공식 배포본과 바이트 단위로 같다는 검증은 아닙니다.

## 네이티브 빌드 환경

원본 [mpv-android 빌드 안내](https://github.com/mpv-android/mpv-android/blob/2026-09-17/buildscripts/README.md)는 Linux와 macOS를 지원하며 Windows·WSL은 지원하지 않습니다. 일반 shyniflex 앱 빌드는 README의 PowerShell 절차로 공식 바이너리를 재사용할 수 있습니다.

네이티브 빌드에 기록된 환경은 Android NDK **r30 / 30.0.16248370**, SDK 플랫폼 **36**, Build Tools **36.0.0**, JDK 17과 Meson·Ninja·make·autoconf·pkg-config·libtool·gperf·nasm 등입니다. 도구 설치는 원본 안내를 따르되, 소스는 먼저 위 스크립트로 고정 버전을 복원합니다.

복원된 `mpv-android-2026-09-17/buildscripts/`에서 원본 `buildall.sh`로 각 아키텍처를 빌드할 수 있습니다.

```sh
./buildall.sh --arch armv7l mpv
./buildall.sh --arch arm64 mpv
./buildall.sh --arch x86_64 mpv
./buildall.sh -n
```

마지막 명령은 준비된 의존성을 사용해 원본 JNI·래퍼 앱 빌드 단계를 실행합니다. shyniflex 앱 자체의 빌드는 `app-source.zip` 안의 README와 Android Gradle 프로젝트를 따릅니다.

`buildall.sh`는 소스를 다시 다운로드하지 않습니다. 원본 `include/download-deps.sh`는 일부 구성 요소를 이동하는 브랜치에서 가져오므로, 빈 의존성 디렉터리에서 이를 실행해 이번 런타임의 소스를 대체하지 마세요. `IN_CI=1`의 FFmpeg `n9.0`도 이 릴리스에 기록된 FFmpeg 커밋과 다릅니다. 위 복원으로 고정된 디렉터리를 채우면 별도 소스 패치 없이 원본 빌드 스크립트를 사용할 수 있습니다.

## 고정 버전과 라이선스

정확한 모든 커밋과 아카이브 해시는 소스 묶음의 명세가 기준입니다. 주요 기준은 다음과 같습니다.

| 구성 요소 | 버전 또는 커밋 |
| --- | --- |
| mpv-android 래퍼 | `fdf74f6830c47dbaa8a22ac79726e8303f1db5af` |
| MPV | `0b7ed670f7c353dd3dd4f8ae0fc788a181a15aa6` |
| FFmpeg | `094a2f8a2a5e7fa64736e067de224ce28fdf5979` |
| dav1d | `362bc1138228390a0a521ecf3af18b23c6afeb9e` |
| libass | `b2fe9d8770678a7b5271387d38c20657ebf3429a` |
| libplacebo | `3330a515d62139259c26239014f286e233bd3a5c` |
| FreeType 2.14.3 | `0a0221a1347e2f1e07c395263540026e9a0aa7c7` |
| 기타 주요 의존성 | Mbed TLS 3.6.7, libxml2 2.15.4, fontconfig 2.18.3, FriBidi 1.0.16, HarfBuzz 14.4.0, libunibreak 8.0, Lua 5.2.4, curl 8.21.0 |

FFmpeg는 GPL과 version3 옵션을 켠 구성입니다. 전체 앱의 GPL-3.0-or-later와 각 구성 요소의 원본 라이선스·저작권 고지를 함께 보존합니다. CA 번들은 Mozilla 데이터의 MPL-2.0 고지를, NDK의 `libc++_shared.so`는 해당 런타임의 고지를 유지합니다. 자세한 출처는 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)와 소스 묶음의 라이선스 파일을 참고하세요.
