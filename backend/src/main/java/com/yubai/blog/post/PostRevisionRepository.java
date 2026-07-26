package com.yubai.blog.post;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PostRevisionRepository extends JpaRepository<PostRevisionEntity, Long> {

    /** 4C：列表只出摘要列，正文按需经 findOne 拉取。 */
    interface RevisionSummaryRow {
        Long getId();
        String getTitle();
        ContentFormat getContentFormat();
        Instant getCreatedAt();
    }

    @Query("""
        SELECT r.id as id, r.title as title, r.contentFormat as contentFormat, r.createdAt as createdAt
        FROM PostRevisionEntity r WHERE r.postId = :postId
        ORDER BY r.createdAt DESC, r.id DESC
        """)
    List<RevisionSummaryRow> findSummaries(long postId);

    List<PostRevisionEntity> findAllByPostIdOrderByCreatedAtDescIdDesc(long postId);

    Optional<PostRevisionEntity> findByIdAndPostId(long id, long postId);
}
