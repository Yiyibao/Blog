package com.yubai.blog.kitchen;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

public interface ShoppingListItemRepository extends JpaRepository<ShoppingListItemEntity, UUID> {
    List<ShoppingListItemEntity> findAllByListIdOrderBySortOrderAscIdAsc(UUID listId);

    @Modifying
    int deleteAllByListId(UUID listId);
}
