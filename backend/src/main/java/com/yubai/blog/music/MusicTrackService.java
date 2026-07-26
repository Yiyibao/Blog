package com.yubai.blog.music;

import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yubai.blog.config.CacheConfig;

@Service
@Transactional(readOnly = true)
public class MusicTrackService {

    private final MusicTrackRepository repository;

    public MusicTrackService(MusicTrackRepository repository) {
        this.repository = repository;
    }

    /** P1-5：曲目当前无管理写入口，TTL 失效即可（4F 管理端上线时须补 evict）。 */
    @Cacheable(CacheConfig.MUSIC)
    public List<MusicTrackResponse> findAll() {
        return repository.findAllByOrderBySortOrderAscIdAsc().stream()
            .map(MusicTrackResponse::from)
            .toList();
    }
}
