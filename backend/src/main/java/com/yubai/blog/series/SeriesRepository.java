package com.yubai.blog.series;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SeriesRepository extends JpaRepository<SeriesEntity, Long> {

    Optional<SeriesEntity> findBySlugAndStatus(String slug, SeriesStatus status);

    List<SeriesEntity> findAllByStatusOrderByPublishedAtDesc(SeriesStatus status);

    List<SeriesEntity> findAllByOrderByUpdatedAtDesc();

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, long id);
}
