# ShyniFlex for LG webOS

ShyniFlex webOS는 Shyni 서버의 영화와 시리즈를 LG TV 리모컨으로 탐색하고 재생하는 패키지형 웹 앱입니다.

- [ShyniFlex webOS 1.0.2 설치 파일과 SHA-256](https://github.com/bluemirr8520-lgtm/shyniflex/releases/tag/webos-v1.0.2)

## 주요 기능

- Shyni 아이디·비밀번호 로그인, 2단계 인증, 로그인 토큰 유지
- 홈 이어보기·최근 추가, 여러 라이브러리 검색, 영화/시리즈/시즌/회차 탐색
- 고정 사이드바와 별도 콘텐츠 영역, 맨 위 새로고침, 빠른 길게 누르기 이동
- 8열 포스터 격자, 4열 16:9 회차 격자, 뚜렷한 흰색·민트색 초점
- 회차에만 시청 완료 표시; 시리즈·시즌은 전체 회차 완료가 확인된 경우에만 표시
- Shyni 재생 결정·티켓·진행률 API, 구형 TV용 최대 1080p 서버 호환 변환
- 5~60초 앞뒤 건너뛰기, 이어보기, 화면 맞춤/채우기
- 서버 텍스트 자막, 가로/세로쓰기, 크기·싱크·줄 간격·위치·외부 폰트
- 화면을 떠날 때 포스터/배경 Blob URL을 해제하고, 화면에 보이는 이미지부터 소량 로드

## 서버 요구사항

패키지 앱은 `file://`에서 실행되므로 Shyni 서버가 JSON `token`을 반환해야 합니다. 쿠키 전용 로그인은 사용할 수 없습니다. 서버는 앱 origin의 CORS 요청과 다음 헤더를 허용해야 합니다.

- `Authorization`
- `Content-Type`
- `X-Device-Id`

HTTPS 사용을 권장합니다. 영상 변환에는 Shyni 서버의 변환 권한과 인코더가 필요합니다.

## 빌드

Node.js와 pnpm이 있는 PowerShell에서 실행합니다.

```powershell
./webos/scripts/build.ps1
```

스크립트는 의존성 설치, 테스트, webOS 패키지 검사, SHA-256 생성을 순서대로 수행합니다. 결과는 기본적으로 `outputs/shyniflex-webos-tv.ipk`에 저장됩니다.

## 설치

LG TV 개발자 모드와 webOS CLI 장치가 설정된 후 다음 명령으로 설치하고 실행할 수 있습니다.

```powershell
ares-install -d LGTV ./outputs/shyniflex-webos-tv.ipk
ares-launch -d LGTV io.mirr.shyniflex.webos
```

앱 식별자는 `io.mirr.shyniflex.webos`, 현재 webOS 버전은 `1.0.2`입니다. 계정 비밀번호와 일회용 인증 코드는 저장하지 않습니다. 서버 주소, 로그인 토큰, 앱 설정은 다음 실행에도 로그인을 유지할 수 있도록 TV 저장소에 보관합니다.
