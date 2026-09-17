package com.example.multitenant.service;

import com.example.multitenant.context.TenantContext;
import com.example.multitenant.dto.LoginRequestDto;
import com.example.multitenant.dto.LoginResponseDto;
import com.example.multitenant.dto.UserResponseDto;
import com.example.multitenant.entity.User;
import com.example.multitenant.exception.AuthenticationFailedException;
import com.example.multitenant.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public LoginResponseDto login(LoginRequestDto loginRequest) {
        if (!StringUtils.hasText(loginRequest.getTenantId())) {
            throw new AuthenticationFailedException("Tenant ID is required");
        }
        if (!StringUtils.hasText(loginRequest.getUsername()) || !StringUtils.hasText(loginRequest.getPassword())) {
            throw new AuthenticationFailedException("Username and password are required");
        }

        // Set tenant context for this thread
        TenantContext.setCurrentTenant(loginRequest.getTenantId());

        User user = userRepository.findByTenantAndUsernameNative(loginRequest.getTenantId(), loginRequest.getUsername())
                .orElseThrow(() -> new AuthenticationFailedException("Invalid tenant, username, or password"));

        if (!passwordEncoder.matches(loginRequest.getPassword(), user.getPasswordHash())) {
            throw new AuthenticationFailedException("Invalid tenant, username, or password");
        }

        String token = UUID.randomUUID().toString();

        return LoginResponseDto.builder()
                .token(token)
                .tenantId(user.getTenantId())
                .username(user.getUsername())
                .displayName(user.getDisplayName())
                .role(user.getRole())
                .build();
    }

    @Transactional(readOnly = true)
    public UserResponseDto getCurrentUser(String tenantId, String username) {
        TenantContext.setCurrentTenant(tenantId);
        User user = userRepository.findByTenantAndUsernameNative(tenantId, username)
                .orElseThrow(() -> new AuthenticationFailedException("User not found"));

        return UserResponseDto.builder()
                .id(user.getId())
                .tenantId(user.getTenantId())
                .username(user.getUsername())
                .displayName(user.getDisplayName())
                .role(user.getRole())
                .build();
    }
}
