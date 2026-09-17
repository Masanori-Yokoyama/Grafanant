# Grafanant - Java / PostgreSQL マルチテナントWebサービス基盤 & Grafana連携

Windows ホスト環境に Java / Gradle などの開発ツールの事前インストールを一切行わず、`wslc` (WSL Container CLI) のみで完全ビルド・マイグレーション・実行を行う Spring Boot 3.x + PostgreSQL 16+ マルチテナント基盤の実装です。

マルチテナント対応の**ログイン画面**、**認証API**、および**テナント専用ダッシュボード画面**を備えています。

> [!NOTE]
> Grafana 連携の詳細な設計・通信シーケンス・組織分離・権限マッピング・動的プロビジョニング (Self-healing) については、[Grafana 連携 詳細仕様書](docs/grafana_multitenant_specification.md) をご参照ください。

---

## 1. 技術スタック & アーキテクチャ

- **言語 / ランタイム:** Java 21 (Eclipse Temurin)
- **フレームワーク:** Spring Boot 3.2.x, Spring Data JPA, Flyway
- **データベース:** PostgreSQL 16
- **メトリクス・ダッシュボード:** Grafana OSS 10.4.0 (Auth Proxy による透過 SSO 連携 & テナント分離)
- **コンテナ CLI:** `wslc` (WSL Container CLI) / Docker Compose
- **ビルド環境:** Multi-stage Dockerfile (`gradle:8.7-jdk21-alpine` → `eclipse-temurin:21-jre-alpine`)
- **マルチテナント分離:** 共有スキーマ + テナントID識別（行レベル分離）
  - リクエストヘッダー `X-Tenant-ID` より `TenantInterceptor` がテナントIDを抽出し、`TenantContext` (`ThreadLocal`) で管理。
  - Hibernate 6 の `@TenantId` および JPA エンティティリスナー (`TenantEntityListener`) により保存時の自動 ID 設定とクエリ時の自動絞り込みを実施。
- **認証 / セキュリティ:**
  - `pgcrypto` & `spring-security-crypto` (BCrypt) によるテナント別ユーザー認証。
  - 同一ユーザー名（例: `admin`）でもテナントが異なれば別アカウントとして独立管理。
  - **Grafana 連携 (Auth Proxy & 組織分離 & 自己修復プロビジョニング):**
    - Spring Boot がリバースプロキシ（`GrafanaProxyController`）として機能し、認証済みユーザー情報（`X-WEBAUTH-USER`）およびテナント専用組織コンテキスト（`X-Grafana-Org-Id`）を透過注入。
    - `GrafanaSyncService` により、テナント別の Organization（`tenant-alpha`, `tenant-beta` 等）の自動作成、ユーザーの自動所属、および権限マッピング（アプリの `ADMIN` 権限 → Grafana の `Admin` ロール、`USER` 権限 → `Viewer` ロール）を実施。
    - **動的自動プロビジョニング (Self-healing):** テナントアクセス時に該当組織専用の PostgreSQL データソースおよび専用ダッシュボードを Grafana REST API 経由でオンデマンド自動注入。Grafana 本体の初回起動クラッシュ（存在しない組織 ID 読み込みエラー）を防止し、コンテナを何度再作成しても即座に自己修復します。
    - 他テナントのダッシュボード URL への直接アクセスをプロキシ層で `403 Forbidden` として確実に遮断。
- **フロントエンド UI:**
  - Vanilla HTML5 + Modern CSS (Glassmorphism / Ambient Glow / Dark Theme) + JavaScript SPA
  - タブナビゲーションによる「アイテム管理」と「テナント分析ダッシュボード (Grafana)」のシームレスな切り替え。

---

## 2. ディレクトリ構成

```text
.
├── Dockerfile                  # 完全コンテナ内ビルド用 Multi-stage Dockerfile
├── compose.yaml                # Docker Compose 定義 (Postgres + App + Grafana)
├── .dockerignore               # ビルドコンテキスト転送除外設定
├── build.gradle                # Gradle 依存関係設定
├── settings.gradle             # プロジェクト設定
├── README.md                   # 本ドキュメント
├── docs/
│   └── grafana_multitenant_specification.md  # Grafana 連携 詳細仕様書
├── grafana/                    # Grafana 初期構成設定
│   ├── dashboards/             # ホスト側参照用ダッシュボード定義
│   │   ├── alpha/
│   │   │   └── tenant-alpha-dashboard.json
│   │   └── beta/
│   │       └── tenant-beta-dashboard.json
│   └── provisioning/           # コンテナ起動用プロビジョニング設定
│       ├── dashboards/
│       │   └── dashboard-provider.yaml       # 起動クラッシュ防止用空プロバイダ設定
│       └── datasources/
│           └── datasource.yaml               # Main Org (orgId: 1) 向け初期データソース設定
└── src/
    └── main/
        ├── java/
        │   └── com/
        │       └── example/
        │           └── multitenant/
        │               ├── Application.java
        │               ├── config/
        │               │   ├── HeaderTenantIdentifierResolver.java
        │               │   ├── SecurityConfig.java
        │               │   ├── TenantInterceptor.java
        │               │   └── WebMvcConfig.java
        │               ├── context/
        │               │   └── TenantContext.java
        │               ├── controller/
        │               │   ├── AuthController.java
        │               │   ├── GrafanaProxyController.java  # Grafana SSO 透過プロキシ & テナント分離ガード
        │               │   └── ItemController.java
        │               ├── dto/
        │               │   ├── ItemRequestDto.java
        │               │   ├── ItemResponseDto.java
        │               │   ├── LoginRequestDto.java
        │               │   ├── LoginResponseDto.java
        │               │   └── UserResponseDto.java
        │               ├── entity/
        │               │   ├── Item.java
        │               │   ├── TenantAware.java
        │               │   ├── TenantEntityListener.java
        │               │   └── User.java
        │               ├── exception/
        │               │   ├── AuthenticationFailedException.java
        │               │   └── GlobalExceptionHandler.java
        │               ├── repository/
        │               │   ├── ItemRepository.java
        │               │   └── UserRepository.java
        │               └── service/
        │                   ├── AuthService.java
        │                   ├── GrafanaSyncService.java      # Org・DS・Dashboard・Role 動的自動同期
        │                   └── ItemService.java
        └── resources/
            ├── application.yml
            ├── dashboards/                          # アプリケーションバンドル用ダッシュボード定義
            │   ├── tenant-alpha-dashboard.json      # Grafana REST API 経由で自動プロビジョニング
            │   └── tenant-beta-dashboard.json
            ├── db/
            │   └── migration/
            │       ├── V1__init_schema.sql          # items テーブル作成
            │       └── V2__add_users.sql            # users テーブル & 初期ユーザーシード
            └── static/
                ├── index.html                       # ログイン & ダッシュボード (アイテム + Grafana) SPA
                ├── css/
                │   └── styles.css                   # Glassmorphism モダンデザインシステム & タブ UI
                └── js/
                    └── app.js                       # 認証・テナントコンテキスト・Grafana 連動 logic
```

---

## 3. コンテナのビルド・起動手順

### 方法 A: Docker Compose を使用する場合 (推奨・ワンコマンド)

```powershell
docker compose up -d --build
```
PostgreSQL, Grafana OSS, および Spring Boot アプリケーションが自動的にリンク・起動します。

---

### 方法 B: `wslc` (WSL Container CLI) を使用する場合

ホスト側の PowerShell より以下の `wslc` コマンドを順に実行します。

> [!TIP]
> 既に同名のコンテナが存在している場合は、事前に停止・削除してください。
> ```powershell
> wslc rm -f multitenant-app multitenant-grafana multitenant-postgres
> ```

#### 1) カスタムコンテナネットワークの作成
```powershell
wslc network create multitenant-net
```
*(既に作成済みの場合はエラーコード ERROR_ALREADY_EXISTS となりますが、そのままスキップして問題ありません)*

#### 2) PostgreSQL 16 コンテナの起動
```powershell
wslc run -d --name multitenant-postgres --network multitenant-net -p 5432:5432 `
  -e POSTGRES_DB=appdb -e POSTGRES_USER=appuser -e POSTGRES_PASSWORD=apppassword `
  postgres:16-alpine
```

#### 3) Grafana OSS コンテナの起動
```powershell
wslc run -d --name multitenant-grafana --network multitenant-net -p 3000:3000 `
  -e GF_SECURITY_ALLOW_EMBEDDING=true `
  -e GF_AUTH_PROXY_ENABLED=true `
  -e GF_AUTH_PROXY_HEADER_NAME=X-WEBAUTH-USER `
  -e GF_AUTH_PROXY_HEADER_PROPERTY=username `
  -e GF_AUTH_PROXY_AUTO_SIGN_UP=true `
  -e GF_AUTH_PROXY_ENABLE_LOGIN_TOKEN=false `
  -e GF_USERS_ALLOW_SIGN_UP=false `
  -e GF_SERVER_ROOT_URL="%(protocol)s://%(domain)s:%(http_port)s/grafana/" `
  -e GF_SERVER_SERVE_FROM_SUB_PATH=true `
  -v "${PWD}\grafana\provisioning:/etc/grafana/provisioning:ro" `
  -v "${PWD}\grafana\dashboards:/var/lib/grafana/dashboards:ro" `
  grafana/grafana-oss:10.4.0
```

#### 4) アプリケーションコンテナのビルド (Multi-stage)
```powershell
wslc build -t multitenant-app:latest .
```

#### 5) アプリケーションコンテナの起動
```powershell
wslc run -d --name multitenant-app --network multitenant-net -p 8080:8080 `
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://multitenant-postgres:5432/appdb `
  -e SPRING_DATASOURCE_USERNAME=appuser `
  -e SPRING_DATASOURCE_PASSWORD=apppassword `
  -e GRAFANA_INTERNAL_URL=http://multitenant-grafana:3000 `
  multitenant-app:latest
```

#### 6) 稼働状態・ログの確認
```powershell
# コンテナ稼働確認 (3つのコンテナが Up 状態であることを確認)
wslc list

# アプリケーション起動ログの確認
wslc logs -f multitenant-app
```

---

## 4. Web UI による利用手順 (ブラウザ)

Web ブラウザから以下の URL にアクセスします。

**URL:** `http://localhost:8080/`

### 初期検証用アカウント一覧

すべての初期アカウントのパスワードは `password123` です。

| テナント ID | ユーザー名 | パスワード | 表示名 | 権限 (Role) |
|---|---|---|---|---|
| `tenant-alpha` | `admin` | `password123` | Alice (Alpha Admin) | ADMIN |
| `tenant-alpha` | `user1` | `password123` | Bob (Alpha Staff) | USER |
| `tenant-beta` | `admin` | `password123` | Carol (Beta Admin) | ADMIN |
| `tenant-beta` | `user2` | `password123` | Dave (Beta Staff) | USER |
| `tenant-gamma` | `member` | `password123` | Eve (Gamma Member) | USER |

### 主な操作と検証フロー

1. **ログイン:**
   - 画面上の「ワンクリック テストログイン」ボタン（Alice, Bob, Carol, Dave）を押すか、直接テナント ID・ユーザー名・パスワードを入力してサインインします。
2. **ダッシュボード確認:**
   - トップバーに現在接続中のテナント ID バッジと `🔒 行レベル分離中` インジケーターが表示されます。
3. **テナント固有アイテムの登録 (タブ 1):**
   - 「➕ 新規アイテム登録」ボタンをクリックし、モーダルからアイテム名と説明を入力して保存します。
4. **テナント専用 Grafana 分析ダッシュボード (タブ 2):**
   - 「📊 テナント分析ダッシュボード (Grafana)」タブをクリックします。
   - **当アプリ側で実施された認証情報**が Auth Proxy を介して自動連携されるため、Grafana 側のログイン画面を一切挟むことなく、自テナント専用のメトリクス（アイテム総数、登録ユーザー数、時系列登録推移グラフ、最新アイテムテーブル）が表示されます。
   - 初回アクセス時に、`GrafanaSyncService` が該当組織・データソース・ダッシュボードを**全自動でプロビジョニング**します。
   - 右上の「別タブで開く ↗」ボタンにより、全画面での Grafana 表示も可能です。
5. **マルチテナント隔離の検証:**
   - 画面下部の「tenant-beta へ切り替え」ボタンをクリック（またはログアウトして `tenant-beta` でログイン）します。
   - `tenant-alpha` で作成したアイテムが一覧に一切表示されず、Grafana ダッシュボードも `tenant-beta` 専用のものに切り替わります。
   - 他テナントのダッシュボード URL に直接アクセスしようとしても、Spring Boot のプロキシガード（`GrafanaProxyController`）によって `403 Forbidden` として遮断されます。

---

## 5. API 動作検証シナリオ (cURL / REST API)

### 認証 API

#### シナリオ1: ログイン成功 (`POST /api/auth/login`)
```powershell
curl.exe -i -s -X POST http://localhost:8080/api/auth/login `
  -H "Content-Type: application/json" `
  -d '{\"tenantId\":\"tenant-alpha\",\"username\":\"admin\",\"password\":\"password123\"}'
```
**レスポンス例 (HTTP 200):**
```json
{
  "token": "bb7cef12-b3aa-48cb-8fdb-e368c76eba03",
  "tenantId": "tenant-alpha",
  "username": "admin",
  "displayName": "Alice (Alpha Admin)",
  "role": "ADMIN"
}
```

#### シナリオ2: 認証失敗 (不正パスワード時 401 Unauthorized)
```powershell
curl.exe -i -s -X POST http://localhost:8080/api/auth/login `
  -H "Content-Type: application/json" `
  -d '{\"tenantId\":\"tenant-alpha\",\"username\":\"admin\",\"password\":\"wrongpassword\"}'
```
**レスポンス例 (HTTP 401):**
```json
{"status":401,"error":"Unauthorized","message":"Invalid tenant, username, or password"}
```

---

### アイテム管理 & テナント分離 API

#### シナリオ3: ヘッダー未指定時のアクセス拒否 (400 Bad Request)
```powershell
curl.exe -i -s http://localhost:8080/api/v1/items
```
**レスポンス例 (HTTP 400):**
```json
{"error": "Missing required header: X-Tenant-ID"}
```

#### シナリオ4: テナント Alpha のデータ登録 (`X-Tenant-ID: tenant-alpha`)
```powershell
curl.exe -i -s -X POST http://localhost:8080/api/v1/items `
  -H "Content-Type: application/json" `
  -H "X-Tenant-ID: tenant-alpha" `
  -d '{\"name\": \"Alpha Server A\", \"description\": \"Tenant Alpha main server\"}'
```

#### シナリオ5: テナント Beta のデータ一覧取得 (`tenant-beta` には Alpha のデータは一切漏洩しない)
```powershell
curl.exe -i -s http://localhost:8080/api/v1/items -H "X-Tenant-ID: tenant-beta"
```
**レスポンス例 (HTTP 200):**
```json
[]
```

#### シナリオ6: クロステナント直接アクセスの遮断検証 (404 Not Found)
`tenant-beta` のヘッダーを指定して `tenant-alpha` のアイテム ID を参照します。
```powershell
curl.exe -i -s http://localhost:8080/api/v1/items/1 -H "X-Tenant-ID: tenant-beta"
```
**レスポンス例 (HTTP 404):**
```json
{"status":404,"error":"Not Found","message":"Item not found with id: 1"}
```

---

### Grafana 連携 & 組織・権限分離 API

#### シナリオ7: 認証Cookie付きでの Grafana 所属組織・権限確認 (`GET /grafana/api/user/orgs`)
ログイン時に付与された Cookie を指定して Grafana の所属組織一覧を取得します。
```powershell
curl.exe -i -s -b "X_TENANT_ID=tenant-alpha; X_USER_NAME=admin; X_USER_ROLE=ADMIN" http://localhost:8080/grafana/api/user/orgs
```
**レスポンス例 (HTTP 200):**
```json
[{"orgId":2,"name":"tenant-alpha","role":"Admin"}]
```
※ `Main Org.` ではなく `tenant-alpha` に所属し、ロールが `Admin` として自動マッピングされます（一般ユーザーの場合は `Viewer`）。

#### シナリオ8: 自テナントダッシュボードのみ取得の確認 (`GET /grafana/api/search`)
自テナント（`tenant-alpha`）のダッシュボード一覧を取得します。
```powershell
curl.exe -i -s -b "X_TENANT_ID=tenant-alpha; X_USER_NAME=admin; X_USER_ROLE=ADMIN" http://localhost:8080/grafana/api/search
```
**レスポンス例 (HTTP 200):**
```json
[
  {
    "id": 1,
    "uid": "tenant-alpha",
    "title": "Tenant Alpha - サービス分析メトリクス",
    "folderTitle": "Dashboards"
  }
]
```
※ `Tenant Beta` のダッシュボードは一切含まれず、自テナントのものだけが返却されます。

#### シナリオ9: 他テナントダッシュボードへの直接アクセス遮断検証 (403 Forbidden)
`tenant-alpha` の認証情報で `tenant-beta` のダッシュボード URL に直接アクセスします。
```powershell
curl.exe -i -s -b "X_TENANT_ID=tenant-alpha; X_USER_NAME=admin; X_USER_ROLE=ADMIN" http://localhost:8080/grafana/d/tenant-beta
```
**レスポンス例 (HTTP 403):**
```json
{"error": "Forbidden: You cannot access other tenant's dashboard."}
```

---

## 6. コンテナ停止・後片付け手順

### 方法 A: `wslc` (WSL Container CLI) の場合
```powershell
wslc stop multitenant-app multitenant-grafana multitenant-postgres
wslc rm multitenant-app multitenant-grafana multitenant-postgres
wslc network rm multitenant-net
```

### 方法 B: Docker Compose の場合
```powershell
docker compose down -v
```
