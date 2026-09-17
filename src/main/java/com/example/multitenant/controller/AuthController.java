package com.example.multitenant.controller;

import com.example.multitenant.context.TenantContext;
import com.example.multitenant.dto.LoginRequestDto;
import com.example.multitenant.dto.LoginResponseDto;
import com.example.multitenant.dto.UserResponseDto;
import com.example.multitenant.service.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    @Autowired
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponseDto> login(
            @RequestBody LoginRequestDto loginRequest,
            jakarta.servlet.http.HttpServletResponse servletResponse) {
        if (loginRequest != null && loginRequest.getTenantId() != null) {
            TenantContext.setCurrentTenant(loginRequest.getTenantId());
        }
        LoginResponseDto response = authService.login(loginRequest);

        // Set auth cookies for seamless Grafana Auth Proxy & reverse proxy integration
        jakarta.servlet.http.Cookie tenantCookie = new jakarta.servlet.http.Cookie("X_TENANT_ID", response.getTenantId());
        tenantCookie.setPath("/");
        tenantCookie.setHttpOnly(false);
        tenantCookie.setMaxAge(86400);

        jakarta.servlet.http.Cookie userCookie = new jakarta.servlet.http.Cookie("X_USER_NAME", response.getUsername());
        userCookie.setPath("/");
        userCookie.setHttpOnly(false);
        userCookie.setMaxAge(86400);

        jakarta.servlet.http.Cookie roleCookie = new jakarta.servlet.http.Cookie("X_USER_ROLE", response.getRole() != null ? response.getRole() : "USER");
        roleCookie.setPath("/");
        roleCookie.setHttpOnly(false);
        roleCookie.setMaxAge(86400);

        servletResponse.addCookie(tenantCookie);
        servletResponse.addCookie(userCookie);
        servletResponse.addCookie(roleCookie);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponseDto> getCurrentUser(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestHeader("X-User-Name") String username) {
        TenantContext.setCurrentTenant(tenantId);
        UserResponseDto response = authService.getCurrentUser(tenantId, username);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(jakarta.servlet.http.HttpServletResponse servletResponse) {
        TenantContext.clear();

        jakarta.servlet.http.Cookie tenantCookie = new jakarta.servlet.http.Cookie("X_TENANT_ID", "");
        tenantCookie.setPath("/");
        tenantCookie.setMaxAge(0);

        jakarta.servlet.http.Cookie userCookie = new jakarta.servlet.http.Cookie("X_USER_NAME", "");
        userCookie.setPath("/");
        userCookie.setMaxAge(0);

        jakarta.servlet.http.Cookie roleCookie = new jakarta.servlet.http.Cookie("X_USER_ROLE", "");
        roleCookie.setPath("/");
        roleCookie.setMaxAge(0);

        servletResponse.addCookie(tenantCookie);
        servletResponse.addCookie(userCookie);
        servletResponse.addCookie(roleCookie);

        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }
}
