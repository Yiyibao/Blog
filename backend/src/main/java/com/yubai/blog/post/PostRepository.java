package com.yubai.blog.post;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PostRepository extends JpaRepository<PostEntity, Long> {
    List<PostEntity> findAllByOrderByDateDesc();

    Optional<PostEntity> findBySlug(String slug);

    @Query("select distinct p.category from PostEntity p order by p.category")
    List<String> findDistinctCategories();
}
