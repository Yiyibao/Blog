package com.yubai.blog.music;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MusicTrackRepository extends JpaRepository<MusicTrackEntity, Long> {
    List<MusicTrackEntity> findAllByOrderBySortOrderAscIdAsc();
}
