package com.yubai.blog.post;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PostCategoryRepository extends JpaRepository<PostCategoryEntity, Long> {
    List<PostCategoryEntity> findAllByOrderByNameAsc();
    Optional<PostCategoryEntity> findByName(String name);
    boolean existsByName(String name);
    boolean existsByNameAndIdNot(String name, long id);
    boolean existsBySlug(String slug);
    boolean existsBySlugAndIdNot(String slug, long id);
}
