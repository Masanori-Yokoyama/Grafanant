package com.example.multitenant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class GrafanaSyncService {

    private final String grafanaInternalUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final String adminBasicAuthHeader;

    private final String datasourceUrl;
    private final String datasourceUsername;
    private final String datasourcePassword;

    // キャッシュ: tenantId -> orgId
    private final Map<String, Long> tenantOrgIdCache = new ConcurrentHashMap<>();
    // キャッシュ: "tenantId:username" -> 最終同期時刻
    private final Map<String, Long> userSyncCache = new ConcurrentHashMap<>();
    // キャッシュ: orgId -> データソース & ダッシュボードプロビジョニング済みフラグ
    private final Set<Long> provisionedOrgs = ConcurrentHashMap.newKeySet();

    public GrafanaSyncService(
            @Value("${grafana.internal-url:http://localhost:3000}") String grafanaInternalUrl,
            @Value("${spring.datasource.url:jdbc:postgresql://multitenant-postgres:5432/appdb}") String datasourceUrl,
            @Value("${spring.datasource.username:appuser}") String datasourceUsername,
            @Value("${spring.datasource.password:apppassword}") String datasourcePassword,
            ObjectMapper objectMapper,
            ResourceLoader resourceLoader) {
        this.grafanaInternalUrl = grafanaInternalUrl.replaceAll("/+$", "");
        this.datasourceUrl = datasourceUrl;
        this.datasourceUsername = datasourceUsername;
        this.datasourcePassword = datasourcePassword;
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.adminBasicAuthHeader = "Basic " + Base64.getEncoder().encodeToString("admin:admin".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * テナントとユーザーをGrafanaのOrganizationおよび適切なRoleに同期し、orgIdを返却する
     */
    public Long syncTenantAndUser(String tenantId, String username, String appRole) {
        if (!StringUtils.hasText(tenantId)) {
            return null;
        }

        try {
            // 1. Organization の取得または作成
            Long orgId = getOrCreateOrganization(tenantId);
            if (orgId == null) {
                return null;
            }

            // 組織専用のデータソース & ダッシュボードの自動プロビジョニング
            ensureTenantProvisioned(orgId, tenantId);

            if (!StringUtils.hasText(username)) {
                return orgId;
            }

            String authUser = tenantId + ":" + username;
            String grafanaRole = "ADMIN".equalsIgnoreCase(appRole) ? "Admin" : "Viewer";

            // 一定時間（30秒）以内の同一ユーザー同期はスキップ
            Long lastSync = userSyncCache.get(authUser);
            if (lastSync != null && (System.currentTimeMillis() - lastSync) < 30_000) {
                return orgId;
            }

            // 2. Auth Proxy経由のダミーアクセスでGrafana側にユーザーを確実にプロビジョニング
            ensureUserCreatedInGrafana(authUser);

            // 3. ユーザー情報の検索
            JsonNode userNode = lookupUser(authUser);
            if (userNode != null && userNode.has("id")) {
                long userId = userNode.get("id").asLong();

                // 4. ユーザーを該当Organizationに指定ロールで追加/更新
                assignUserToOrg(orgId, userId, authUser, grafanaRole);

                // 5. ユーザーのアクティブOrganizationを切り替え
                switchActiveOrg(authUser, orgId);

                // 6. Main Org (id: 1) から削除して完全隔離
                removeUserFromMainOrg(userId);
            }

            userSyncCache.put(authUser, System.currentTimeMillis());
            return orgId;
        } catch (Exception e) {
            log.error("Grafana Organization/User 同期失敗: tenant={}, user={}, error={}", tenantId, username, e.getMessage());
            return tenantOrgIdCache.get(tenantId);
        }
    }

    public Long getOrganizationId(String tenantId) {
        if (!StringUtils.hasText(tenantId)) return null;
        Long cached = tenantOrgIdCache.get(tenantId);
        if (cached != null) return cached;
        return getOrCreateOrganization(tenantId);
    }

    private Long getOrCreateOrganization(String tenantId) {
        try {
            // 既存のOrganization一覧を検索
            HttpRequest listRequest = HttpRequest.newBuilder()
                    .uri(URI.create(grafanaInternalUrl + "/api/orgs"))
                    .header("Authorization", adminBasicAuthHeader)
                    .GET()
                    .build();

            HttpResponse<String> listResponse = httpClient.send(listRequest, HttpResponse.BodyHandlers.ofString());
            if (listResponse.statusCode() == 200) {
                JsonNode orgs = objectMapper.readTree(listResponse.body());
                if (orgs.isArray()) {
                    for (JsonNode orgNode : orgs) {
                        if (tenantId.equalsIgnoreCase(orgNode.path("name").asText())) {
                            long id = orgNode.get("id").asLong();
                            tenantOrgIdCache.put(tenantId, id);
                            return id;
                        }
                    }
                }
            }

            // 存在しない場合は新規作成
            String payload = objectMapper.writeValueAsString(Map.of("name", tenantId));
            HttpRequest createRequest = HttpRequest.newBuilder()
                    .uri(URI.create(grafanaInternalUrl + "/api/orgs"))
                    .header("Authorization", adminBasicAuthHeader)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> createResponse = httpClient.send(createRequest, HttpResponse.BodyHandlers.ofString());
            if (createResponse.statusCode() == 200) {
                JsonNode created = objectMapper.readTree(createResponse.body());
                long id = created.path("orgId").asLong();
                tenantOrgIdCache.put(tenantId, id);
                log.info("Grafana に新しい Organization を作成しました: name={}, orgId={}", tenantId, id);
                return id;
            }
        } catch (Exception e) {
            log.warn("Organization 取得/作成失敗: tenant={}, error={}", tenantId, e.getMessage());
        }
        return null;
    }

    /**
     * 組織（Org）に PostgreSQL データソースとテナント専用ダッシュボードが存在することを確認し、なければ自動注入する
     */
    private void ensureTenantProvisioned(long orgId, String tenantId) {
        if (provisionedOrgs.contains(orgId)) {
            return;
        }

        try {
            ensureDataSourceConfigured(orgId);
            ensureDashboardConfigured(orgId, tenantId);
            provisionedOrgs.add(orgId);
            log.info("Grafana テナント専用プロビジョニング完了: orgId={}, tenantId={}", orgId, tenantId);
        } catch (Exception e) {
            log.error("Grafana テナント専用プロビジョニングエラー: orgId={}, tenantId={}, error={}", orgId, tenantId, e.getMessage());
        }
    }

    private void ensureDataSourceConfigured(long orgId) {
        try {
            HttpRequest listDsReq = HttpRequest.newBuilder()
                    .uri(URI.create(grafanaInternalUrl + "/api/datasources"))
                    .header("Authorization", adminBasicAuthHeader)
                    .header("X-Grafana-Org-Id", String.valueOf(orgId))
                    .GET()
                    .build();

            HttpResponse<String> listDsRes = httpClient.send(listDsReq, HttpResponse.BodyHandlers.ofString());
            if (listDsRes.statusCode() == 200) {
                JsonNode datasources = objectMapper.readTree(listDsRes.body());
                if (datasources.isArray()) {
                    for (JsonNode ds : datasources) {
                        if ("PostgreSQL-AppDB".equals(ds.path("name").asText()) || "PostgreSQL-AppDB".equals(ds.path("uid").asText())) {
                            return; // 既に存在
                        }
                    }
                }
            }

            // JDBC URL (jdbc:postgresql://multitenant-postgres:5432/appdb) から host:port と database をパース
            String hostPort = "multitenant-postgres:5432";
            String database = "appdb";
            if (datasourceUrl.startsWith("jdbc:postgresql://")) {
                String clean = datasourceUrl.substring("jdbc:postgresql://".length());
                int slashIdx = clean.indexOf('/');
                if (slashIdx > 0) {
                    hostPort = clean.substring(0, slashIdx);
                    String dbPart = clean.substring(slashIdx + 1);
                    int qIdx = dbPart.indexOf('?');
                    database = qIdx > 0 ? dbPart.substring(0, qIdx) : dbPart;
                }
            }

            Map<String, Object> dsPayload = Map.of(
                    "name", "PostgreSQL-AppDB",
                    "uid", "PostgreSQL-AppDB",
                    "type", "postgres",
                    "access", "proxy",
                    "url", hostPort,
                    "database", database,
                    "user", datasourceUsername,
                    "secureJsonData", Map.of("password", datasourcePassword),
                    "jsonData", Map.of("sslmode", "disable", "postgresVersion", 1600),
                    "isDefault", true
            );

            HttpRequest createDsReq = HttpRequest.newBuilder()
                    .uri(URI.create(grafanaInternalUrl + "/api/datasources"))
                    .header("Authorization", adminBasicAuthHeader)
                    .header("X-Grafana-Org-Id", String.valueOf(orgId))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(dsPayload)))
                    .build();

            HttpResponse<String> createDsRes = httpClient.send(createDsReq, HttpResponse.BodyHandlers.ofString());
            if (createDsRes.statusCode() == 200) {
                log.info("Grafana Org (id={}) に PostgreSQL データソースを自動作成しました", orgId);
            } else {
                log.warn("Grafana データソース作成応答: status={}, body={}", createDsRes.statusCode(), createDsRes.body());
            }
        } catch (Exception e) {
            log.error("Grafana データソース自動プロビジョニング失敗: orgId={}, error={}", orgId, e.getMessage());
        }
    }

    private void ensureDashboardConfigured(long orgId, String tenantId) {
        try {
            // ダッシュボードが既に存在するか確認
            HttpRequest getDashReq = HttpRequest.newBuilder()
                    .uri(URI.create(grafanaInternalUrl + "/api/dashboards/uid/" + tenantId))
                    .header("Authorization", adminBasicAuthHeader)
                    .header("X-Grafana-Org-Id", String.valueOf(orgId))
                    .GET()
                    .build();

            HttpResponse<String> getDashRes = httpClient.send(getDashReq, HttpResponse.BodyHandlers.ofString());
            if (getDashRes.statusCode() == 200) {
                return; // 既に存在
            }

            // クラスパスのリソースからダッシュボード JSON を取得
            String resourcePath = "classpath:dashboards/" + tenantId + "-dashboard.json";
            Resource resource = resourceLoader.getResource(resourcePath);
            String dashboardContent;
            if (resource.exists()) {
                try (InputStream is = resource.getInputStream()) {
                    dashboardContent = StreamUtils.copyToString(is, StandardCharsets.UTF_8);
                }
            } else {
                // フォールバック: 汎用ダッシュボード生成
                dashboardContent = buildFallbackDashboardJson(tenantId);
            }

            JsonNode dashboardNode = objectMapper.readTree(dashboardContent);

            Map<String, Object> body = Map.of(
                    "dashboard", dashboardNode,
                    "overwrite", true
            );

            HttpRequest createDashReq = HttpRequest.newBuilder()
                    .uri(URI.create(grafanaInternalUrl + "/api/dashboards/db"))
                    .header("Authorization", adminBasicAuthHeader)
                    .header("X-Grafana-Org-Id", String.valueOf(orgId))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> createDashRes = httpClient.send(createDashReq, HttpResponse.BodyHandlers.ofString());
            if (createDashRes.statusCode() == 200) {
                log.info("Grafana Org (id={}) にテナントダッシュボード ({}) を自動作成しました", orgId, tenantId);
            } else {
                log.warn("Grafana ダッシュボード作成応答: status={}, body={}", createDashRes.statusCode(), createDashRes.body());
            }
        } catch (Exception e) {
            log.error("Grafana ダッシュボード自動プロビジョニング失敗: orgId={}, tenantId={}, error={}", orgId, tenantId, e.getMessage());
        }
    }

    private String buildFallbackDashboardJson(String tenantId) {
        return """
        {
          "title": "Tenant " + tenantId + " Dashboard",
          "uid": "%s",
          "timezone": "browser",
          "schemaVersion": 39,
          "version": 1,
          "refresh": "5s",
          "panels": [
            {
              "id": 1,
              "title": "登録アイテム総数",
              "type": "stat",
              "gridPos": { "h": 6, "w": 12, "x": 0, "y": 0 },
              "targets": [
                {
                  "datasource": { "type": "postgres", "uid": "PostgreSQL-AppDB" },
                  "format": "table",
                  "rawQuery": true,
                  "rawSql": "SELECT count(*) as \\"アイテム総数\\" FROM items WHERE tenant_id = '%s';"
                }
              ]
            }
          ]
        }
        """.formatted(tenantId, tenantId);
    }

    private void ensureUserCreatedInGrafana(String authUser) {
        try {
            HttpRequest triggerRequest = HttpRequest.newBuilder()
                    .uri(URI.create(grafanaInternalUrl + "/api/user"))
                    .header("X-WEBAUTH-USER", authUser)
                    .GET()
                    .build();
            httpClient.send(triggerRequest, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
        }
    }

    private JsonNode lookupUser(String authUser) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(grafanaInternalUrl + "/api/users/lookup?loginOrEmail=" + authUser))
                    .header("Authorization", adminBasicAuthHeader)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return objectMapper.readTree(response.body());
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void assignUserToOrg(long orgId, long userId, String authUser, String role) {
        try {
            // まず追加を試みる
            String addPayload = objectMapper.writeValueAsString(Map.of("loginOrEmail", authUser, "role", role));
            HttpRequest addRequest = HttpRequest.newBuilder()
                    .uri(URI.create(grafanaInternalUrl + "/api/orgs/" + orgId + "/users"))
                    .header("Authorization", adminBasicAuthHeader)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(addPayload))
                    .build();

            HttpResponse<String> addResponse = httpClient.send(addRequest, HttpResponse.BodyHandlers.ofString());
            if (addResponse.statusCode() != 200) {
                // 既に追加済みの場合はロールを更新
                String updatePayload = objectMapper.writeValueAsString(Map.of("role", role));
                HttpRequest updateRequest = HttpRequest.newBuilder()
                        .uri(URI.create(grafanaInternalUrl + "/api/orgs/" + orgId + "/users/" + userId))
                        .header("Authorization", adminBasicAuthHeader)
                        .header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(updatePayload))
                        .build();
                httpClient.send(updateRequest, HttpResponse.BodyHandlers.discarding());
            }
        } catch (Exception e) {
            log.warn("Org へのユーザー割当失敗: orgId={}, user={}, error={}", orgId, authUser, e.getMessage());
        }
    }

    private void switchActiveOrg(String authUser, long orgId) {
        try {
            HttpRequest switchRequest = HttpRequest.newBuilder()
                    .uri(URI.create(grafanaInternalUrl + "/api/user/using/" + orgId))
                    .header("X-WEBAUTH-USER", authUser)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            httpClient.send(switchRequest, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
        }
    }

    private void removeUserFromMainOrg(long userId) {
        try {
            HttpRequest deleteRequest = HttpRequest.newBuilder()
                    .uri(URI.create(grafanaInternalUrl + "/api/orgs/1/users/" + userId))
                    .header("Authorization", adminBasicAuthHeader)
                    .DELETE()
                    .build();
            httpClient.send(deleteRequest, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
        }
    }
}
