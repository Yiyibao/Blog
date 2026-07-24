package com.yubai.blog.music;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class MusicTrackService {

    private final MusicTrackRepository repository;

    public MusicTrackService(MusicTrackRepository repository) {
        this.repository = repository;
    }

    public List<MusicTrackResponse> findAll() {
        return repository.findAllByOrderBySortOrderAscIdAsc().stream()
            .map(MusicTrackResponse::from)
            .toList();
    }
}
