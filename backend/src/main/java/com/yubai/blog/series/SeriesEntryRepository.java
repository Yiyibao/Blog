package com.yubai.blog.series;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeriesEntryRepository extends JpaRepository<SeriesEntryEntity, Long> {

    List<SeriesEntryEntity> findAllBySeriesIdOrderBySortOrderAscIdAsc(long seriesId);

    List<SeriesEntryEntity> findAllBySeriesIdInOrderBySortOrderAscIdAsc(Collection<Long> seriesIds);

    List<SeriesEntryEntity> findAllByContentTypeAndContentId(String contentType, long contentId);

    long countBySeriesId(long seriesId);

    @Modifying
    @Query("DELETE FROM SeriesEntryEntity e WHERE e.seriesId = :seriesId")
    void deleteAllBySeriesId(@Param("seriesId") long seriesId);

    /** 4B：内容删除钩子——清掉所有合集里对该内容的引用。 */
    @Modifying
    @Query("DELETE FROM SeriesEntryEntity e WHERE e.contentType = :contentType AND e.contentId = :contentId")
    void deleteAllByContent(@Param("contentType") String contentType, @Param("contentId") long contentId);
}
