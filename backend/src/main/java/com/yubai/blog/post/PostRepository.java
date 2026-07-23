package com.yubai.blog.post;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PostRepository extends JpaRepository<PostEntity, Long> {
    Page<PostEntity> findAllByOrderByDateDesc(Pageable pageable);

    Page<PostEntity> findAllByStatusOrderByDateDesc(PostStatus status, Pageable pageable);

    Optional<PostEntity> findBySlugAndStatus(String slug, PostStatus status);

    Optional<PostEntity> findBySlug(String slug);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, long id);

    @Query("select distinct p.category from PostEntity p where p.status = com.yubai.blog.post.PostStatus.PUBLISHED order by p.category")
    List<String> findDistinctPublishedCategories();
}
