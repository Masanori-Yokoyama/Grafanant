package com.example.multitenant.entity;

import com.example.multitenant.context.TenantContext;
import jakarta.persistence.PrePersist;

public class TenantEntityListener {

    @PrePersist
    public void setTenantId(Object entity) {
        if (entity instanceof TenantAware tenantAware) {
            if (tenantAware.getTenantId() == null || tenantAware.getTenantId().isBlank()) {
                tenantAware.setTenantId(TenantContext.getCurrentTenant());
            }
        }
    }
}
