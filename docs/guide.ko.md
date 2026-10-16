# MyDB IDE 사용 설명서

[English](guide.md) · **한국어**

MyDB IDE로 자주 하는 작업을 차례대로 설명합니다. 단축키는 macOS 기준이고, Windows와 Linux에서는 <kbd>⌘</kbd> 대신 <kbd>Ctrl</kbd>을 씁니다([단축키 표](../README.ko.md#단축키) 참고).

- [1. 프로젝트와 화면 구성](#1-프로젝트와-화면-구성)
- [2. 데이터베이스에 연결하기](#2-데이터베이스에-연결하기)
- [3. SSH 터널과 SSL](#3-ssh-터널과-ssl)
- [4. SQL 작성과 실행](#4-sql-작성과-실행)
- [5. 트랜잭션](#5-트랜잭션)
- [6. 데이터 보기와 편집](#6-데이터-보기와-편집)
- [7. 객체 만들기와 고치기](#7-객체-만들기와-고치기)
- [8. 스키마 비교](#8-스키마-비교)
- [9. 다이어그램과 실행 계획](#9-다이어그램과-실행-계획)
- [10. 가져오기와 내보내기](#10-가져오기와-내보내기)
- [11. 가상 외래 키](#11-가상-외래-키)
- [12. 다른 IDE에서 데이터 소스 가져오기](#12-다른-ide에서-데이터-소스-가져오기)
- [13. 설정](#13-설정)
- [14. 문제 해결](#14-문제-해결)

---

## 1. 프로젝트와 화면 구성

MyDB IDE는 **프로젝트** 단위로 동작합니다. 프로젝트는 데이터 소스, 콘솔, SQL 파일을 함께 보관하는 폴더입니다. **File → New Project…**로 새로 만들거나, **File → Open…**으로 아무 폴더나 열면 됩니다.

메인 창은 세 부분으로 나뉩니다.

| 영역 | 보여 주는 것 |
|---|---|
| **MyDB Explorer**(왼쪽) | 데이터 소스와 그 안의 데이터베이스, 스키마, 테이블, 뷰 등 |
| **에디터**(가운데) | 쿼리 콘솔, SQL 파일, 테이블 데이터, 다이어그램 |
| **MyDB Services**(아래) | 쿼리 결과, 출력 로그, 실행 계획, 열린 세션 |

데이터 소스 설정은 프로젝트 안의 `.idea/mydbDataSources.xml`에 **비밀번호 없이** 저장됩니다. 이 파일을 커밋하면 팀과 연결 설정을 공유할 수 있고, 비밀번호는 각자 입력합니다.

## 2. 데이터베이스에 연결하기

1. <kbd>⌘</kbd> <kbd>;</kbd>를 누릅니다(또는 탐색기 툴바의 **+**).
2. **+**를 누르고 **PostgreSQL**, **MySQL**, **MariaDB**, **SQLite**, **DDL Files** 중에서 고릅니다.
3. **General** 탭에 입력합니다.
   - **Host**, **Port**, **Database**, **User**, **Password**(SQLite는 DB 파일)
   - **URL**은 위 항목으로 자동으로 만들어집니다. URL을 직접 고치면 위 항목이 그 값으로 바뀝니다.
   - **Name**은 직접 바꾸기 전까지 호스트와 DB 이름을 따라갑니다.
4. **Test Connection**을 누르면 성공 시 서버 버전이 나옵니다.
5. **Schemas** 탭에서 읽어 올 스키마를 체크합니다. 체크하지 않은 스키마는 읽지 않으므로 큰 서버에서도 빠릅니다.
6. **OK**를 누르면 새 데이터 소스의 쿼리 콘솔이 열립니다.

다른 탭의 유용한 설정:

| 탭 | 설정 | 쓰임 |
|---|---|---|
| **Options** | **Read-only** | 운영 DB용. 데이터를 바꾸는 문장을 막습니다 |
| **Options** | **Transaction control** | **Auto** 또는 **Manual** 커밋 모드로 시작 |
| **Options** | **Synchronize metadata automatically** | 콘솔에서 DDL을 실행하면 모델을 자동으로 새로고침 |
| **Advanced** | 드라이버 속성 | 타임아웃, `ApplicationName` 같은 JDBC 속성 |

탐색기에서 데이터베이스 옆 배지(`3/12`)는 12개 스키마 중 3개를 읽었다는 뜻입니다. 오른쪽 클릭 → **Schemas…**로 바꾸고, **Refresh**로 바뀐 객체를 다시 읽습니다.

데이터 소스를 오른쪽 클릭 → **Color…**로 색을 정하면 에디터 탭에도 같은 색이 표시되어 운영과 스테이징을 구분하기 쉽습니다.

## 3. SSH 터널과 SSL

### SSH 터널

1. 데이터 소스를 열고(<kbd>⌘</kbd> <kbd>;</kbd>) **SSH/SSL** 탭으로 갑니다.
2. **Use SSH tunnel**을 체크합니다.
3. 기존 **SSH 설정**을 고르거나 새로 만듭니다.
   - **Host**, **Port**, **User**
   - **Authentication**: **Password**, 또는 **Key pair**(**Private key** 파일과 키 비밀번호)
4. **Local port**는 비워 두면 빈 포트를 알아서 씁니다.
5. **Test Connection**을 누릅니다.

SSH 설정은 여러 데이터 소스가 함께 쓰며 **Settings → Tools → SSH Configurations**에서 관리합니다. 처음 접속한 호스트의 키를 기억해 두고, 나중에 키가 바뀌면 연결을 거부합니다.

### SSL

1. **SSH/SSL** 탭에서 **Use SSL**을 체크합니다.
2. **Mode**를 고릅니다: `require`(암호화), `verify-ca`(인증 기관까지 확인), `verify-full`(호스트 이름까지 확인).
3. `verify-ca`와 `verify-full`은 **CA file**이나 **Trust store**를 지정합니다.
4. 서버가 클라이언트 인증서를 요구하면 **Client certificate**, **Client key**, **Key password**를 지정합니다.

비밀번호와 키 비밀번호는 시스템 자격 증명 저장소(macOS 키체인)에 저장됩니다.

## 4. SQL 작성과 실행

탐색기 툴바의 **Query Console**로 콘솔을 열거나, <kbd>⇧</kbd> <kbd>⌘</kbd> <kbd>L</kbd>로 새 콘솔을 만듭니다. 콘솔은 툴바에 표시된 데이터 소스를 대상으로 실행됩니다.

**실행하기**

| 무엇을 | 방법 |
|---|---|
| 커서 위치의 문장 | <kbd>⌘</kbd> <kbd>↵</kbd>. 커서가 문장 사이에 있으면 어느 문장을 실행할지 묻습니다 |
| 선택 영역 | 텍스트를 선택하고 <kbd>⌘</kbd> <kbd>↵</kbd> |
| 스크립트 전체 | 전체 선택 후 <kbd>⌘</kbd> <kbd>↵</kbd>. 결과 집합마다 탭이 하나씩 생깁니다 |
| 취소 | 창 아래쪽의 진행 표시를 눌러 취소 |

결과는 아래 **MyDB Services**에 나옵니다. 큰 결과는 페이지로 나뉩니다(페이지 크기는 설정에 있음). 이전에 실행한 문장은 콘솔 툴바의 **Query History**에 있습니다.

**코드 도움**

- **자동완성**(<kbd>⌃</kbd> <kbd>Space</kbd>): 스키마, 테이블, 컬럼, 별칭, CTE, 키워드. `JOIN … ON` 뒤에서는 외래 키 기반 조건을 먼저 제안합니다.
- **오류와 경고**: 모르는 테이블·컬럼에 밑줄이 그어집니다. <kbd>⌥</kbd> <kbd>↵</kbd>로 빠른 수정과 의도 동작(이름 한정, 조인 좌우 바꾸기, 서브쿼리로 감싸기 등)을 고릅니다.
- **안전장치**: `WHERE` 없는 `DELETE`·`UPDATE`는 실행 전에 확인을 받습니다.
- **이동**: 테이블 이름에서 <kbd>⌘</kbd> <kbd>B</kbd>는 탐색기의 그 테이블로, <kbd>F1</kbd>은 DDL과 주석 보기, <kbd>⌥</kbd> <kbd>F7</kbd>은 사용처 찾기입니다.
- **정렬**: **Code → Reformat Code**.
- **이름 바꾸기**: 테이블, 컬럼, 별칭에서 <kbd>⇧</kbd> <kbd>F6</kbd>. DB 객체 이름을 바꿀 때는 실행 전에 DDL을 보여 줍니다.

**SQL 파일**: 프로젝트의 `.sql` 파일도 콘솔처럼 실행됩니다. 폴더별로 어느 데이터 소스를 쓸지는 **Settings → Database → SQL Resolution Scopes**에서 정합니다. 스크립트 파일을 자주 돌린다면 **SQL Script** 실행 구성을 만드세요(**Run → Edit Configurations…**).

## 5. 트랜잭션

콘솔 툴바의 **Tx** 버튼에 현재 모드가 표시됩니다.

- **Auto**: 문장이 끝날 때마다 커밋됩니다.
- **Manual**: **Commit**이나 **Rollback**(둘 다 툴바에 있음)을 누를 때까지 한 트랜잭션으로 실행됩니다. 커밋 전에는 다른 세션에서 변경이 보이지 않습니다.

같은 메뉴에서 다음 트랜잭션의 **격리 수준**(Read Committed, Repeatable Read, Serializable)을 정합니다. 열린 트랜잭션은 **MyDB Services**의 세션 목록에서도 커밋·롤백할 수 있습니다.

콘솔, 테이블 편집기, 탐색기는 서로 다른 세션을 써서, 콘솔에서 긴 쿼리가 돌아도 테이블 탐색이나 편집이 막히지 않습니다.

## 6. 데이터 보기와 편집

테이블을 고르고 <kbd>⌘</kbd> <kbd>↓</kbd>(또는 더블클릭)로 데이터를 엽니다. 테이블 하나에서 나온 쿼리 결과도 같은 방식으로 편집할 수 있습니다.

**필터와 정렬**

- **WHERE**에 조건(예: `status = 'paid' and total > 100`)을 입력하고 <kbd>↵</kbd>. 입력하는 동안 컬럼 이름이 자동완성됩니다.
- **ORDER BY**에 입력(예: `created_at desc`)하고 <kbd>↵</kbd>.
- 컬럼 머리글을 누르면 새 쿼리 없이 불러온 행만 정렬합니다.
- 값을 오른쪽 클릭하면 그 값으로 걸러 볼 수 있습니다.

**편집**

1. 셀을 더블클릭(또는 <kbd>F2</kbd>)하고 새 값을 입력합니다.
2. **+**로 행을 추가하고 **−**로 삭제합니다. 새 행에는 기본값이 표시됩니다.
3. 바뀐 셀은 강조됩니다. **아직 서버로는 아무것도 가지 않았습니다.**
4. **Preview Pending Changes**로 실제로 보낼 `INSERT`, `UPDATE`, `DELETE`를 확인합니다.
5. **Submit**(<kbd>⌘</kbd> <kbd>↵</kbd>)을 누르면 한 트랜잭션으로 실행됩니다. 하나라도 실패하면 전부 롤백되고, 실패한 행은 서버 메시지와 함께 빨갛게 표시되며, 고친 내용은 그대로 남아 다시 손볼 수 있습니다.
6. **Revert**는 대기 중인 변경을 모두 버립니다.

**그 밖의 그리드 도구**(결과 툴바나 오른쪽 클릭):

- **값 패널**(셀에서 <kbd>⇧</kbd> <kbd>↵</kbd>): 긴 텍스트와 JSON 보기
- **Transpose**: 행 하나를 세로로 보기
- **행 수 세기**: 정확한 전체 행 수
- **Show Chart**: 결과 컬럼으로 차트 그리기
- **Compare Data With…**: 두 결과 집합 비교
- **Show DDL**: 결과의 원본 테이블 DDL

## 7. 객체 만들기와 고치기

**만들기**: 스키마를 오른쪽 클릭 → **New…**(<kbd>⌘</kbd> <kbd>N</kbd>) → **Table**, **View**, **Materialized View**, **Sequence**, **Schema**.

**테이블 고치기**: 오른쪽 클릭 → **Modify Table…**(<kbd>⌘</kbd> <kbd>F6</kbd>).

테이블 대화 상자에는 **Columns**, **Keys**, **Foreign Keys**, **Indexes**, **Checks** 트리가 있습니다.

- **+**는 선택한 폴더에 항목을 추가합니다. 항목을 고르면 오른쪽에서 편집합니다.
- 컬럼: 이름, **Data type**, **Default**, **Not null**, **Identity (serial)**, **Generated** 식, **Comment**.
- 외래 키: **Target table**을 고른 뒤 로컬 컬럼과 대상 컬럼을 짝지웁니다.
- 아래쪽 **DDL 미리보기**가 입력할 때마다 바뀝니다. 실패할 내용은 **Problems Found**에 나옵니다.
- **OK**를 누르면 DDL이 실행됩니다. DDL을 복사해서 직접 실행해도 됩니다.

그 밖의 객체 동작(오른쪽 클릭):

- **SQL Scripts → SQL Generator…**(<kbd>⌥</kbd> <kbd>⌘</kbd> <kbd>G</kbd>): 선택한 객체의 DDL
- **SQL Scripts → SELECT / INSERT / UPDATE / DELETE Statement**: 콘솔에 문장 틀 만들기
- **Object Actions → Truncate…**, **Delete…**: 확인을 거쳐 실행
- **Go to DDL**(<kbd>⌘</kbd> <kbd>B</kbd>), **Copy Reference**, **Quick Documentation**(<kbd>F1</kbd>)

## 8. 스키마 비교

1. 탐색기에서 스키마 두 개(또는 데이터 소스 두 개)를 <kbd>⌘</kbd>-클릭으로 고릅니다.
2. <kbd>⌘</kbd> <kbd>D</kbd>(**Compare Structure…**)를 누릅니다.
3. 트리에 객체마다 추가·삭제·변경·동일 상태가 나옵니다. **Show Identical Items**로 동일한 객체를 보이거나 숨깁니다.
4. 객체를 고르면 **DDL Diff**로 나란히 비교하고, **Object Properties Diff**로 속성을 자세히 봅니다.
5. 체크로 포함할 객체를 고릅니다. **Swap Source and Target**은 방향을 바꿉니다.
6. **Script Preview**에 마이그레이션 스크립트가 나옵니다. **Open in New Query Console**로 열어 검토한 뒤 직접 실행하거나, **Execute**로 바로 실행합니다.

구조가 아니라 **데이터**를 비교하려면 테이블을 오른쪽 클릭 → **Compare Data**, 또는 결과 그리드의 **Compare Data With…**를 씁니다.

## 9. 다이어그램과 실행 계획

### ER 다이어그램

스키마나 테이블 몇 개를 고르고 <kbd>⌥</kbd> <kbd>⇧</kbd> <kbd>⌘</kbd> <kbd>U</kbd>(**Diagrams → Show Diagram**)를 누릅니다. <kbd>⌥</kbd> <kbd>⌘</kbd> <kbd>U</kbd>는 빠른 팝업으로 엽니다.

- 실선은 외래 키, 점선은 뷰 의존 관계와 가상 외래 키입니다.
- **View Options**: **All Columns**, **Key Columns Only**, **No Columns**, **Column Types**, **View Dependencies**.
- **Re-layout**, **Zoom In/Out**, **Fit Content**, **Actual Size**.
- **Export Diagram**: **PNG Image**, **SVG**, **Mermaid**, **PlantUML**, **Graphviz**. **Copy Mermaid**는 문서나 PR에 붙일 Mermaid 텍스트를 클립보드에 복사합니다.

### 실행 계획

1. 쿼리 안에 커서를 둡니다.
2. 콘솔 툴바의 **Explain Plan**을 누르고 **Explain Plan** 또는 **Explain Analyze**를 고릅니다. Explain Analyze는 문장을 실제로 실행하며, 데이터를 바꾸는 문장은 실행 뒤 롤백합니다.
3. **Query Plan** 탭에 계획이 트리로 나옵니다. 노드를 고르면 모든 속성(행 수, 비용, 시간, 버퍼)을 볼 수 있습니다.
4. 다이어그램 보기로 바꾸면 계획을 그래프로 봅니다.

## 10. 가져오기와 내보내기

### 내보내기

- **쿼리 결과**: 결과 툴바의 **Export Data…**
- **테이블 전체**: 오른쪽 클릭 → **Import/Export → Export Data to File…**

**Extractor**(CSV, TSV, JSON, SQL Inserts, Markdown, HTML, Excel 등)와 **Add column header**, **Transpose** 같은 옵션을 고른 뒤 **Export to File** 또는 **Copy to Clipboard**를 누릅니다. CSV 세부 설정(구분자, 따옴표, NULL 표기)은 **Settings → Database → CSV Formats**에 있습니다.

### CSV·TSV 파일 가져오기

1. 스키마나 테이블을 오른쪽 클릭 → **Import/Export → Import Data from File…**
2. 파일을 고르면 **Data Preview**에 읽힌 모양이 나옵니다.
3. 기존 **Table**을 고르거나 새 이름을 입력합니다. 새 테이블이면 컬럼 이름과 형을 고치고, **DDL Preview**에서 `CREATE TABLE`을 확인합니다.
4. 옵션: **Insert values that cannot be converted as NULL**, **Write errors to file**.
5. **Import**를 누릅니다.

### 덤프·복원 도구

| DB | 메뉴 | 도구 |
|---|---|---|
| PostgreSQL | **Import/Export → Export with pg_dump…** | `pg_dump`(SQL, custom, directory, tar) |
| PostgreSQL | **Import/Export → Restore with pg_restore or psql…** | `pg_restore` 또는 `psql` |
| MySQL / MariaDB | **Export with mysqldump…** / **Restore with mysql…** | `mysqldump` / `mysql` |

도구가 설치되어 있어야 하며, 처음에 대화 상자에서 경로를 지정합니다. 비밀번호는 명령줄이 아니라 환경 변수로 넘깁니다. 진행 내용은 **Output** 탭에 나옵니다.

### DDL 데이터 소스

**Import/Export → Dump to DDL Data Source…**는 객체마다 DDL을 `.sql` 파일로 쓰고, 그 파일을 읽는 **DDL Files** 데이터 소스를 추가합니다. 스키마를 Git으로 관리하고 실제 DB와 비교할 때 유용합니다.

### CSV 파일을 표로 편집

`.csv`나 `.tsv` 파일을 열면 텍스트 옆에 **Data** 탭이 생깁니다. 표처럼 편집하고 제출하면 파일에 다시 씁니다.

## 11. 가상 외래 키

외래 키로 선언하지 않은 관계가 있는 DB가 많습니다. MyDB IDE에 선언해 두면 자동완성, 조인 제안, 다이어그램이 그 관계를 압니다.

1. 테이블을 오른쪽 클릭 → **Object Actions → Add Virtual Foreign Key…**
2. 대상 테이블과 컬럼 짝을 고릅니다.
3. 탐색기에는 실제 외래 키처럼, 다이어그램에는 점선으로 표시됩니다.

가상 외래 키는 `.idea/mydbVirtualObjects.xml`에 저장되며 DB는 전혀 바꾸지 않습니다. 지우려면 **Remove Virtual Foreign Key**를 씁니다.

## 12. 다른 IDE에서 데이터 소스 가져오기

다른 IDE 프로젝트(`.idea/dataSources.xml`)에 이미 데이터 소스가 있다면:

1. 탐색기 툴바의 **Import Data Sources from IDE Project…**를 누릅니다.
2. 프로젝트 폴더를 고릅니다. 이 컴퓨터에서 찾은 프로젝트가 제안됩니다.
3. 가져올 데이터 소스를 체크합니다. SSH 설정도 함께 옵니다.
4. 저장된 비밀번호도 복사할 수 있습니다. 이때 시스템이 키체인 접근 허용을 물을 수 있습니다.

지원하지 않는 드라이버의 데이터 소스는 목록에는 나오지만 가져올 수 없습니다.

## 13. 설정

<kbd>⌘</kbd> <kbd>,</kbd>로 **Settings**를 엽니다. DB 관련 설정은 **Database** 아래에 있습니다.

| 페이지 | 주요 설정 |
|---|---|
| **Query Execution** | 커서가 문장 안에 있을 때 실행할 범위, 결과를 새 탭에 열기, 실행 시간 표시, `WHERE` 없는 `DELETE`/`UPDATE` 확인 |
| **Data Editor and Viewer** | 결과 페이지 크기, 테이블 더블클릭 시 여는 것 |
| **CSV Formats** | CSV/TSV 구분자, 따옴표, 머리글 행 |
| **Database Explorer** | 컬럼 형, 키 컬럼, 객체 수 표시 |
| **Query Files and Consoles** | 데이터 소스를 만든 뒤 콘솔 열기 |
| **SQL Resolution Scopes** | 폴더별 SQL 파일이 어느 데이터 소스를 기준으로 해석될지(프로젝트별) |
| **Other** | 만들기·고치기 대화 상자에서 DDL을 실행한 뒤 객체 새로고침 |

SSH 설정은 **Tools → SSH Configurations**에 있습니다.

## 14. 문제 해결

**연결이 거부되거나 시간이 초과됩니다**
호스트와 포트, 서버가 원격 접속을 받는지, 방화벽이나 VPN을 확인하세요. 배스천 호스트 뒤의 DB는 [SSH 터널](#3-ssh-터널과-ssl)을 쓰세요.

**SSL 오류가 납니다**
`verify-ca`와 `verify-full`은 서버의 CA 인증서를 **CA file**이나 trust store에 지정해야 합니다. 빠르게 시험하려면 검증 없이 암호화만 하는 `require`를 써 보세요.

**있는 테이블·컬럼인데 모른다고 밑줄이 그어집니다**
그 스키마를 읽지 않았을 수 있습니다. 데이터베이스를 오른쪽 클릭 → **Schemas…**. 다른 곳에서 방금 만들었다면 **Refresh**.

**macOS나 Windows에서 앱이 열리지 않습니다**
아직 코드 서명 전입니다. [다운로드](../README.ko.md#다운로드)를 참고하세요.

**로그는 어디 있나요?**
**Help → Show Log in Finder**(Windows는 Explorer). [버그를 제보](https://github.com/taeyun1215/mydb-ide/issues/new?template=bug_report.yml)할 때 관련 부분을 붙여 주시되, 호스트 이름과 사용자 이름은 지워 주세요.
