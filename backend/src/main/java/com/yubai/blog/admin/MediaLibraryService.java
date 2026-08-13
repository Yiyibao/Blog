package com.yubai.blog.admin;

import com.yubai.blog.admin.ai.AiGeneratedImageEntity;
import com.yubai.blog.admin.ai.AiGeneratedImageRepository;
import com.yubai.blog.admin.ai.AiImageSessionRepository;
import com.yubai.blog.ai.AiArtifactEntity;
import com.yubai.blog.ai.AiArtifactRepository;
import com.yubai.blog.ai.AiTaskPartRepository;
import com.yubai.blog.dish.DishAssetRepository;
import com.yubai.blog.note.NoteAttachmentRepository;
import com.yubai.blog.note.NoteRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class MediaLibraryService {
    private final NoteAttachmentRepository noteAttachments;
    private final NoteRepository notes;
    private final DishAssetRepository dishAssets;
    private final AiGeneratedImageRepository generatedImages;
    private final AiImageSessionRepository imageSessions;
    private final AiArtifactRepository artifacts;
    private final AiTaskPartRepository taskParts;
    private final Clock clock;

    public MediaLibraryService(
            NoteAttachmentRepository noteAttachments,
            NoteRepository notes,
            DishAssetRepository dishAssets,
            AiGeneratedImageRepository generatedImages,
            AiImageSessionRepository imageSessions,
            AiArtifactRepository artifacts,
            AiTaskPartRepository taskParts,
            Clock clock) {
        this.noteAttachments = noteAttachments;
        this.notes = notes;
        this.dishAssets = dishAssets;
        this.generatedImages = generatedImages;
        this.imageSessions = imageSessions;
        this.artifacts = artifacts;
        this.taskParts = taskParts;
        this.clock = clock;
    }

    public List<MediaItem> list(String sourceType, String status) {
        var notesById = new HashMap<Long, NoteRepository.NoteRefRow>();
        for (var note : notes.findAllRefRows()) notesById.put(note.getId(), note);
        var sessionsById = new HashMap<Long, String>();
        imageSessions
                .findAll()
                .forEach(session -> sessionsById.put(session.getId(), session.getOwner()));
        var artifactReferences = new HashMap<java.util.UUID, Integer>();
        for (var row : taskParts.countArtifactReferences()) {
            artifactReferences.put(row.getArtifactId(), Math.toIntExact(row.getReferenceCount()));
        }
        var now = clock.instant();
        var items = new ArrayList<MediaItem>();
        for (var row : noteAttachments.findMediaRows()) {
            var note = notesById.get(row.getNoteId());
            int references =
                    note != null
                                    && note.getMarkdownContent() != null
                                    && note.getMarkdownContent()
                                            .contains(row.getPublicId().toString())
                            ? 1
                            : 0;
            items.add(
                    new MediaItem(
                            "NOTE_ATTACHMENT",
                            row.getPublicId().toString(),
                            row.getCreatedBy(),
                            row.getFileName(),
                            row.getMediaType(),
                            row.getByteSize(),
                            row.getSha256(),
                            row.getAltText(),
                            row.getSourceUrl(),
                            row.getLicense(),
                            references,
                            row.getCreatedBy(),
                            row.getDeletedAt() == null ? "ACTIVE" : "TRASHED",
                            row.getCreatedAt(),
                            row.getStorageKey(),
                            "/api/v1/note-assets/" + row.getPublicId()));
        }
        for (var row : dishAssets.findMediaRows()) {
            var statusValue =
                    row.getDishId() == null
                                    && row.getExpiresAt() != null
                                    && row.getExpiresAt().isBefore(now)
                            ? "EXPIRED"
                            : "ACTIVE";
            items.add(
                    new MediaItem(
                            "DISH_ASSET",
                            row.getPublicId().toString(),
                            row.getOwner(),
                            row.getFileName(),
                            row.getMediaType(),
                            row.getByteSize(),
                            row.getSha256(),
                            row.getAltText(),
                            row.getSourceUrl(),
                            row.getLicense(),
                            row.getDishId() == null ? 0 : 1,
                            row.getCreatedBy(),
                            statusValue,
                            row.getCreatedAt(),
                            row.getStorageKey(),
                            "/api/v1/dish-assets/" + row.getPublicId()));
        }
        for (AiGeneratedImageEntity image : generatedImages.findAllByOrderByCreatedAtDesc()) {
            var owner = sessionsById.getOrDefault(image.getSessionId(), image.getCreatedBy());
            items.add(
                    new MediaItem(
                            "AI_GENERATED_IMAGE",
                            image.getPublicId().toString(),
                            owner,
                            image.getFileName(),
                            image.getMediaType(),
                            image.getByteSize(),
                            image.getSha256(),
                            image.getAltText(),
                            image.getSourceUrl(),
                            image.getLicense(),
                            image.getReferenceCount(),
                            image.getCreatedBy(),
                            "ACTIVE",
                            image.getCreatedAt(),
                            image.getStorageKey(),
                            "/api/v1/admin/ai/images/" + image.getPublicId() + "/content"));
        }
        for (AiArtifactEntity artifact : artifacts.findAllByOrderByCreatedAtDesc()) {
            var references =
                    artifactReferences.getOrDefault(artifact.getId(), artifact.getReferenceCount());
            var statusValue = artifact.getStatus().name();
            if (artifact.getStatus().name().equals("READY")
                    && artifact.getExpiresAt() != null
                    && artifact.getExpiresAt().isBefore(now)) statusValue = "EXPIRED";
            items.add(
                    new MediaItem(
                            "AI_ARTIFACT",
                            artifact.getId().toString(),
                            artifact.getOwner(),
                            artifact.getName(),
                            artifact.getMediaType(),
                            artifact.getSizeBytes(),
                            artifact.getSha256(),
                            artifact.getAltText(),
                            artifact.getSourceUrl(),
                            artifact.getLicense(),
                            references,
                            artifact.getCreatedBy(),
                            statusValue,
                            artifact.getCreatedAt(),
                            artifact.getStorageKey(),
                            "/api/v1/ai/artifacts/" + artifact.getId() + "/download"));
        }
        var source = normalize(sourceType);
        var wantedStatus = normalize(status);
        return items.stream()
                .filter(item -> source == null || item.sourceType().equalsIgnoreCase(source))
                .filter(
                        item ->
                                wantedStatus == null
                                        || item.status().equalsIgnoreCase(wantedStatus))
                .sorted(
                        Comparator.comparing(
                                MediaItem::createdAt,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    public record MediaItem(
            String sourceType,
            String sourceId,
            String owner,
            String fileName,
            String mediaType,
            long byteSize,
            String sha256,
            String altText,
            String sourceUrl,
            String license,
            int referenceCount,
            String createdBy,
            String status,
            Instant createdAt,
            String storageKey,
            String url) {}
}
