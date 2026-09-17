package com.example.multitenant.controller;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Slf4j
@Controller
public class GrafanaProxyController {

    private final String grafanaInternalUrl;
    private final HttpClient httpClient;
    private final com.example.multitenant.service.GrafanaSyncService grafanaSyncService;

    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "te", "trailers", "transfer-encoding", "upgrade", "host"
    );

    public GrafanaProxyController(
            @Value("${grafana.internal-url:http://localhost:3000}") String grafanaInternalUrl,
            com.example.multitenant.service.GrafanaSyncService grafanaSyncService) {
        this.grafanaInternalUrl = grafanaInternalUrl.replaceAll("/+$", "");
        this.grafanaSyncService = grafanaSyncService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @RequestMapping(value = "/grafana/**")
    public void proxyGrafana(HttpServletRequest request, HttpServletResponse response) throws Exception {
        String requestUri = request.getRequestURI();
        String queryString = request.getQueryString();

        // 1. テナントID、ユーザー名、ロールの解決 (Cookie, Header, Query Parameter)
        String tenantId = resolveTenantId(request);
        String username = resolveUsername(request);
        String role = resolveUserRole(request);

        boolean isPublicAsset = requestUri.startsWith("/grafana/public/")
                || requestUri.startsWith("/grafana/avatar/")
                || requestUri.endsWith(".js")
                || requestUri.endsWith(".css")
                || requestUri.endsWith(".png")
                || requestUri.endsWith(".svg")
                || requestUri.endsWith(".woff2")
                || requestUri.endsWith(".woff");

        // 2. 認証チェック (静的アセット以外で未認証の場合は 401)
        if (!isPublicAsset && !StringUtils.hasText(tenantId)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\": \"Unauthorized: Please login to access Grafana dashboards.\"}");
            return;
        }

        // 3. テナント隔離検証 (他テナントのダッシュボードへのアクセス遮断)
        if (StringUtils.hasText(tenantId)) {
            if (requestUri.contains("/d/tenant-") || requestUri.contains("/dashboards/uid/tenant-")) {
                String normalizedTenant = tenantId.toLowerCase().startsWith("tenant-")
                        ? tenantId.toLowerCase()
                        : "tenant-" + tenantId.toLowerCase();
                if (!requestUri.toLowerCase().contains(normalizedTenant)) {
                    log.warn("テナントアクセス違反検知: ユーザー所属テナント={}, 要求URI={}", tenantId, requestUri);
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"error\": \"Forbidden: You cannot access other tenant's dashboard.\"}");
                    return;
                }
            }
        }

        // 4. Grafana Organization & Role の同期
        Long orgId = null;
        if (StringUtils.hasText(tenantId) && !isPublicAsset) {
            orgId = grafanaSyncService.syncTenantAndUser(tenantId, username, role);
        }

        // 5. 転送先URLの構築
        String targetUrl = grafanaInternalUrl + requestUri;
        if (StringUtils.hasText(queryString)) {
            targetUrl += "?" + queryString;
        }

        // 6. リクエストの作成
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .timeout(Duration.ofSeconds(30));

        // ヘッダーの転送
        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames != null) {
            while (headerNames.hasMoreElements()) {
                String headerName = headerNames.nextElement();
                if (!HOP_BY_HOP_HEADERS.contains(headerName.toLowerCase())) {
                    Enumeration<String> headerValues = request.getHeaders(headerName);
                    while (headerValues.hasMoreElements()) {
                        String headerValue = headerValues.nextElement();
                        try {
                            requestBuilder.header(headerName, headerValue);
                        } catch (IllegalArgumentException ignored) {
                            // 一部の制限ヘッダーはスキップ
                        }
                    }
                }
            }
        }

        // Auth Proxy 用ヘッダーの注入
        if (StringUtils.hasText(tenantId) && StringUtils.hasText(username)) {
            // テナント間で同名ユーザーが重複してもGrafana内でユニークになるよう tenant:username 形式で渡す
            String authUser = tenantId + ":" + username;
            requestBuilder.header("X-WEBAUTH-USER", authUser);
        }

        // テナント専用 Organization コンテキストの注入
        if (orgId != null) {
            requestBuilder.header("X-Grafana-Org-Id", String.valueOf(orgId));
        }

        // ボディの転送
        String method = request.getMethod();
        if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)) {
            byte[] bodyBytes = StreamUtils.copyToByteArray(request.getInputStream());
            requestBuilder.method(method, HttpRequest.BodyPublishers.ofByteArray(bodyBytes));
        } else {
            requestBuilder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        // 6. Grafana へのリクエスト実行 & レスポンス返却
        try {
            HttpResponse<InputStream> grafanaResponse = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());

            response.setStatus(grafanaResponse.statusCode());

            grafanaResponse.headers().map().forEach((headerName, headerValues) -> {
                if (!HOP_BY_HOP_HEADERS.contains(headerName.toLowerCase())) {
                    for (String value : headerValues) {
                        response.addHeader(headerName, value);
                    }
                }
            });

            try (InputStream in = grafanaResponse.body(); OutputStream out = response.getOutputStream()) {
                in.transferTo(out);
                out.flush();
            }
        } catch (Exception ex) {
            log.error("Grafana プロキシ転送エラー: url={}, error={}", targetUrl, ex.getMessage());
            response.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\": \"Failed to connect to Grafana server. Please verify Grafana container is running.\"}");
        }
    }

    private String resolveTenantId(HttpServletRequest request) {
        String tenantId = request.getHeader("X-Tenant-ID");
        if (StringUtils.hasText(tenantId)) return tenantId;

        if (request.getCookies() != null) {
            for (Cookie c : request.getCookies()) {
                if ("X_TENANT_ID".equalsIgnoreCase(c.getName()) && StringUtils.hasText(c.getValue())) {
                    return c.getValue();
                }
            }
        }

        return request.getParameter("tenantId");
    }

    private String resolveUsername(HttpServletRequest request) {
        String username = request.getHeader("X-User-Name");
        if (StringUtils.hasText(username)) return username;

        if (request.getCookies() != null) {
            for (Cookie c : request.getCookies()) {
                if ("X_USER_NAME".equalsIgnoreCase(c.getName()) && StringUtils.hasText(c.getValue())) {
                    return c.getValue();
                }
            }
        }

        return request.getParameter("username");
    }

    private String resolveUserRole(HttpServletRequest request) {
        String role = request.getHeader("X-User-Role");
        if (StringUtils.hasText(role)) return role;

        if (request.getCookies() != null) {
            for (Cookie c : request.getCookies()) {
                if ("X_USER_ROLE".equalsIgnoreCase(c.getName()) && StringUtils.hasText(c.getValue())) {
                    return c.getValue();
                }
            }
        }

        return request.getParameter("role");
    }
}
