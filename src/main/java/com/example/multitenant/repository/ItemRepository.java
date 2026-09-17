package com.example.multitenant.repository;

import com.example.multitenant.entity.Item;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ItemRepository extends JpaRepository<Item, Long> {
    List<Item> findByTenantId(String tenantId);
    Optional<Item> findByIdAndTenantId(Long id, String tenantId);
}
