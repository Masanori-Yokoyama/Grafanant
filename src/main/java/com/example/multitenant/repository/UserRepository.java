package com.example.multitenant.repository;

import com.example.multitenant.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    
    @Query(value = "SELECT * FROM users WHERE tenant_id = :tenantId AND username = :username", nativeQuery = true)
    Optional<User> findByTenantAndUsernameNative(@Param("tenantId") String tenantId, @Param("username") String username);

    Optional<User> findByTenantIdAndUsername(String tenantId, String username);

    Optional<User> findByUsername(String username);
}
