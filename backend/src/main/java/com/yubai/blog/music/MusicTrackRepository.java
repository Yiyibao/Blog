package com.yubai.blog.music;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MusicTrackRepository extends JpaRepository<MusicTrackEntity, Long> {
    List<MusicTrackEntity> findAllByOrderBySortOrderAscIdAsc();

    /** 4F：trackId 唯一性校验（业务层友好报错，数据库唯一约束兜底）。 */
    java.util.Optional<MusicTrackEntity> findByTrackId(String trackId);
}
