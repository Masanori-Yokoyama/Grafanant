package com.example.multitenant.service;

import com.example.multitenant.context.TenantContext;
import com.example.multitenant.dto.ItemRequestDto;
import com.example.multitenant.dto.ItemResponseDto;
import com.example.multitenant.entity.Item;
import com.example.multitenant.repository.ItemRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

@Service
@Transactional
public class ItemService {

    private final ItemRepository itemRepository;

    @Autowired
    public ItemService(ItemRepository itemRepository) {
        this.itemRepository = itemRepository;
    }

    public ItemResponseDto createItem(ItemRequestDto requestDto) {
        String currentTenant = TenantContext.getCurrentTenant();
        Item item = Item.builder()
                .tenantId(currentTenant)
                .name(requestDto.getName())
                .description(requestDto.getDescription())
                .build();

        Item saved = itemRepository.save(item);
        return mapToDto(saved);
    }

    @Transactional(readOnly = true)
    public List<ItemResponseDto> getAllItems() {
        String currentTenant = TenantContext.getCurrentTenant();
        return itemRepository.findByTenantId(currentTenant).stream()
                .map(this::mapToDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public ItemResponseDto getItemById(Long id) {
        String currentTenant = TenantContext.getCurrentTenant();
        Item item = itemRepository.findByIdAndTenantId(id, currentTenant)
                .orElseThrow(() -> new NoSuchElementException("Item with ID " + id + " not found for tenant " + currentTenant));
        return mapToDto(item);
    }

    private ItemResponseDto mapToDto(Item item) {
        return ItemResponseDto.builder()
                .id(item.getId())
                .tenantId(item.getTenantId())
                .name(item.getName())
                .description(item.getDescription())
                .createdAt(item.getCreatedAt())
                .updatedAt(item.getUpdatedAt())
                .build();
    }
}
