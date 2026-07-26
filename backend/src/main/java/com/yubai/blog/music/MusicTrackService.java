package com.yubai.blog.music;

import java.util.List;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.config.CacheConfig;

@Service
@Transactional(readOnly = true)
public class MusicTrackService {

    private final MusicTrackRepository repository;

    public MusicTrackService(MusicTrackRepository repository) {
        this.repository = repository;
    }

    /** P1-5：公开列表缓存；4F 管理写入口已补 evict（TTL 兜底不变）。 */
    @Cacheable(CacheConfig.MUSIC)
    public List<MusicTrackResponse> findAll() {
        return repository.findAllByOrderBySortOrderAscIdAsc().stream()
            .map(MusicTrackResponse::from)
            .toList();
    }

    // ── 4F：管理端 CRUD（不改迁移即可增删曲目）────────────────────────────────

    public List<AdminMusicTrackResponse> findAdmin() {
        return repository.findAllByOrderBySortOrderAscIdAsc().stream()
            .map(AdminMusicTrackResponse::from)
            .toList();
    }

    @Transactional
    @CacheEvict(cacheNames = CacheConfig.MUSIC, allEntries = true)
    public AdminMusicTrackResponse create(MusicTrackRequest request) {
        requireUniqueTrackId(request.trackId(), null);
        return AdminMusicTrackResponse.from(repository.save(MusicTrackEntity.create(request)));
    }

    @Transactional
    @CacheEvict(cacheNames = CacheConfig.MUSIC, allEntries = true)
    public AdminMusicTrackResponse update(long id, MusicTrackRequest request) {
        var track = repository.findById(id).orElseThrow(() -> new NotFoundException("曲目不存在：" + id));
        requireUniqueTrackId(request.trackId(), id);
        track.update(request);
        return AdminMusicTrackResponse.from(track);
    }

    @Transactional
    @CacheEvict(cacheNames = CacheConfig.MUSIC, allEntries = true)
    public void delete(long id) {
        if (!repository.existsById(id)) throw new NotFoundException("曲目不存在：" + id);
        repository.deleteById(id);
    }

    private void requireUniqueTrackId(String trackId, Long id) {
        var existing = repository.findByTrackId(trackId.trim());
        if (existing.isPresent() && (id == null || !existing.get().getId().equals(id))) {
            throw new DataIntegrityViolationException("曲目 trackId 已存在");
        }
    }
}
