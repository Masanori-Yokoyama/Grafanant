package com.example.multitenant.entity;

public interface TenantAware {
    String getTenantId();
    void setTenantId(String tenantId);
}
