# MyDB IDE에 기여하기

[English](CONTRIBUTING.md) · 한국어

관심 가져 주셔서 고맙습니다. 버그 제보, 아이디어, 문서, 코드 모두 환영합니다.

- [기여하는 방법](#기여하는-방법)
- [개발 환경](#개발-환경)
- [프로젝트 구조](#프로젝트-구조)
- [PR 보내기](#pr-보내기)
- [커밋 메시지](#커밋-메시지)
- [릴리스](#릴리스)

## 기여하는 방법

- **버그 제보**: [버그 제보 양식](https://github.com/taeyun1215/mydb-ide/issues/new?template=bug_report.yml)을 써 주세요. 로그와 스크린샷에서 호스트 이름, 사용자 이름 등 개인 정보는 지워 주세요.
- **기능 제안**: [기능 제안 양식](https://github.com/taeyun1215/mydb-ide/issues/new?template=feature_request.yml)을 쓰거나, 아직 다듬어지지 않은 생각이면 [토론](https://github.com/taeyun1215/mydb-ide/discussions)에서 먼저 이야기해 주세요.
- **직접 고치기**: [`good first issue`](https://github.com/taeyun1215/mydb-ide/labels/good%20first%20issue) 라벨이 붙은 이슈부터 시작하기 좋습니다. 다른 사람과 겹치지 않도록 이슈에 댓글을 남겨 주세요.
- **취약점 제보**: [SECURITY.ko.md](SECURITY.ko.md)에 따라 비공개로 알려 주세요.

## 개발 환경

필요한 것:

- Gradle 실행용 JDK 21 이상(컴파일에 쓰는 JDK 25는 빌드가 알아서 받습니다)
- Docker(Testcontainers가 PostgreSQL 17, MySQL 8.4, MariaDB 11.4, sshd를 띄웁니다)
- 앱 전체 빌드에만: 디스크 30 GB 정도와 플랫폼 소스(README 참고)

시험 실행:

```sh
src/scripts-test.sh                               # DB 계층
src/plugin-test.sh                                # IDE 기능
src/plugin-test.sh 'dev.teo.mydb.plugin.sql.*'    # 일부만
```

`src/core`와 `src/plugin`의 Gradle 프로젝트가 `src/product`의 소스를 컴파일하므로 가장 빠르게 반복할 수 있습니다. 앱 전체 빌드는 직접 써 봐야 할 때만 하고, 평소 설정을 건드리지 않도록 `tools/mydb-run.sh`로 실행하세요.

## 프로젝트 구조

| 바꾸려는 것 | 모듈 | 시험 |
|---|---|---|
| 연결, 메타데이터 읽기, 실행, DDL, 비교, 가져오기·내보내기(UI 없음) | `src/product/mydb.core` | `src/core` |
| 탐색기, SQL 언어, 콘솔, 그리드, 대화 상자, 다이어그램 | `src/product/mydb.plugin` | `src/plugin` |
| 제품 브랜딩, IDE 사용자 정의 | `src/product/mydb.resources` | – |
| 제품 빌드 | `src/product/build` | 전체 빌드 |

플랫폼 자체를 고치는 건 최후의 수단입니다. 꼭 필요하면 `src/product/patches/`를 고치고 PR에 이유를 적어 주세요.

## PR 보내기

1. 작은 수정보다 큰 변경은 먼저 이슈를 열어 방향을 맞춥니다.
2. 포크한 뒤 `main`에서 브랜치를 만듭니다(`fix/grid-null-sort`, `feat/mysql-compare`).
3. 시험을 추가하거나 고칩니다. 시험은 `testdb/seed`의 시드 스키마로 돌아가고, 데이터를 바꾸는 시험은 새 DB를 씁니다.
4. 주변 코드와 맞춥니다: Kotlin, 들여쓰기 4칸, 공개 클래스에 짧은 KDoc, "왜"를 설명하는 주석.
5. 사용자에게 보이는 변경은 [CHANGELOG.md](CHANGELOG.md)의 **Unreleased**에 한 줄 추가합니다.
6. 시험 두 가지를 모두 돌린 뒤 PR을 열고 양식을 채웁니다.

모든 PR에서 CI가 시험 두 가지를 돌립니다. 메인테이너가 리뷰한 뒤 squash로 합칩니다.

PR을 보내면 기여한 내용이 [Apache License 2.0](LICENSE)을 따르는 데 동의한 것으로 봅니다.

## 커밋 메시지

짧은 영역 접두어와 명령형으로 씁니다(영어).

```
grid: keep pending edits when the page is reloaded
mysql: read generated columns
docs: explain SSH key passphrases
```

## 릴리스

버전은 플랫폼 연도와 릴리스를 따릅니다(`2026.2.3`). `main`에서 릴리스를 만들고 `vX.Y.Z` 태그를 붙이며, 설치 파일은 SHA-256 체크섬과 함께 [GitHub 릴리스](https://github.com/taeyun1215/mydb-ide/releases)에 올립니다. 변경 내용은 [CHANGELOG.md](CHANGELOG.md)에 정리합니다.

모든 프로젝트 공간에서 [행동 강령](CODE_OF_CONDUCT.ko.md)을 지켜 주세요.
