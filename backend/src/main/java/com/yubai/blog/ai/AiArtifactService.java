package com.yubai.blog.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yubai.blog.admin.ai.AiImageService;
import com.yubai.blog.admin.ai.AiServiceException;
import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.config.AiPlatformProperties;
import com.yubai.blog.storage.StorageService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiArtifactService {
    private final AiArtifactRepository repository;
    private final AiTaskService taskService;
    private final AiTaskPartRepository partRepository;
    private final AiImageService imageService;
    private final AiTaskEventService eventService;
    private final StorageService storage;
    private final AiPlatformProperties properties;
    private final ObjectMapper objectMapper;
    private final AiDocumentRenderer documentRenderer;

    @Autowired
    public AiArtifactService(
            AiArtifactRepository repository,
            AiTaskService taskService,
            AiTaskPartRepository partRepository,
            AiImageService imageService,
            AiTaskEventService eventService,
            StorageService storage,
            AiPlatformProperties properties,
            ObjectMapper objectMapper,
            AiDocumentRenderer documentRenderer) {
        this.repository = repository;
        this.taskService = taskService;
        this.partRepository = partRepository;
        this.imageService = imageService;
        this.eventService = eventService;
        this.storage = storage;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.documentRenderer = documentRenderer;
    }

    /** Source-compatible constructor for focused lifecycle tests and older integrations. */
    public AiArtifactService(
            AiArtifactRepository repository,
            AiTaskService taskService,
            AiTaskPartRepository partRepository,
            AiImageService imageService,
            AiTaskEventService eventService,
            StorageService storage,
            AiPlatformProperties properties,
            ObjectMapper objectMapper) {
        this(
                repository,
                taskService,
                partRepository,
                imageService,
                eventService,
                storage,
                properties,
                objectMapper,
                new AiDocumentRenderer(objectMapper));
    }

    @Transactional
    public AiArtifactResponse create(UUID taskId, String owner, AiArtifactCreateRequest request) {
        taskService.requireOwned(taskId, owner);
        repository.lockOwnerQuota(owner);
        var excluded = List.of(AiArtifactStatus.DELETED, AiArtifactStatus.EXPIRED);
        if (repository.countByOwnerAndStatusNotIn(owner, excluded)
                >= Math.max(1, properties.getMaxOwnerArtifacts())) {
            throw new AiServiceException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "AI artifact count quota exceeded");
        }
        var name = normalizeName(request.name(), request.format());
        var materialized = materialize(taskId, owner, request);
        if (materialized.bytes().length > Math.max(1, properties.getMaxArtifactBytes())) {
            throw new AiServiceException(HttpStatus.PAYLOAD_TOO_LARGE, "AI artifact is too large");
        }
        if (repository.sumRetainedBytes(owner, excluded) + materialized.bytes().length
                > Math.max(1, properties.getMaxOwnerArtifactBytes())) {
            throw new AiServiceException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "AI artifact quota exceeded");
        }
        var hash = AiFileService.sha256(materialized.bytes());
        var existing = repository.findByTaskIdAndName(taskId, name);
        if (existing.isPresent()) {
            if (existing.get().getSha256().equals(hash)
                    && existing.get().getStatus() == AiArtifactStatus.READY) {
                return AiArtifactResponse.from(existing.get());
            }
            throw new AiServiceException(
                    HttpStatus.CONFLICT, "Artifact name already exists for this task");
        }
        var id = UUID.randomUUID();
        var storageKey = "ai-artifacts/" + id + extension(name, materialized.mediaType());
        storage.store(storageKey, materialized.bytes());
        try {
            var entity =
                    AiArtifactEntity.ready(
                            id,
                            owner,
                            taskId,
                            storageKey,
                            name,
                            materialized.mediaType(),
                            materialized.bytes().length,
                            hash,
                            Instant.now()
                                    .plus(
                                            Math.max(1, properties.getArtifactRetentionDays()),
                                            ChronoUnit.DAYS));
            var saved = repository.saveAndFlush(entity);
            eventService.append(
                    taskId,
                    "artifact.created",
                    Map.of(
                            "artifactId",
                            saved.getId().toString(),
                            "name",
                            saved.getName(),
                            "mediaType",
                            saved.getMediaType()));
            var sequence = Math.toIntExact(partRepository.countByTaskId(taskId) + 1);
            partRepository.save(
                    AiTaskPartEntity.create(
                            taskId,
                            sequence,
                            AiPartRole.ASSISTANT,
                            AiPartKind.ARTIFACT_REF,
                            null,
                            null,
                            null,
                            saved.getId(),
                            "artifact:" + saved.getId()));
            return AiArtifactResponse.from(saved);
        } catch (RuntimeException exception) {
            storage.delete(storageKey);
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public List<AiArtifactResponse> list(String owner) {
        return repository
                .findByOwnerAndStatusNotOrderByCreatedAtDesc(owner, AiArtifactStatus.DELETED)
                .stream()
                .map(AiArtifactResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AiArtifactResponse> listForTask(UUID taskId, String owner) {
        taskService.requireOwned(taskId, owner);
        return repository.findByTaskIdAndOwnerOrderByCreatedAtAsc(taskId, owner).stream()
                .filter(entity -> entity.getStatus() != AiArtifactStatus.DELETED)
                .map(AiArtifactResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public AiArtifactContent read(UUID id, String owner) {
        var artifact = requireOwned(id, owner);
        if (artifact.getStatus() != AiArtifactStatus.READY
                || (artifact.getExpiresAt() != null
                        && artifact.getExpiresAt().isBefore(Instant.now()))) {
            throw new NotFoundException("AI artifact is unavailable");
        }
        var bytes = storage.read(artifact.getStorageKey());
        if (bytes.length != artifact.getSizeBytes()
                || !AiFileService.sha256(bytes).equals(artifact.getSha256())) {
            throw new AiServiceException(HttpStatus.CONFLICT, "AI artifact integrity check failed");
        }
        return new AiArtifactContent(artifact, bytes);
    }

    @Transactional
    public void delete(UUID id, String owner) {
        var artifact = requireOwned(id, owner);
        if (artifact.getStatus() == AiArtifactStatus.DELETED) return;
        var totalReferences = partRepository.countByArtifactId(id);
        var ownReferences = partRepository.countByArtifactIdAndTaskId(id, artifact.getTaskId());
        if (totalReferences > ownReferences) {
            throw new AiServiceException(
                    HttpStatus.CONFLICT, "AI artifact is still referenced by another task");
        }
        partRepository.deleteByArtifactIdAndTaskId(id, artifact.getTaskId());
        storage.delete(artifact.getStorageKey());
        artifact.delete();
        repository.save(artifact);
    }

    private AiArtifactEntity requireOwned(UUID id, String owner) {
        return repository
                .findByIdAndOwner(id, owner)
                .orElseThrow(() -> new NotFoundException("AI artifact does not exist"));
    }

    private MaterializedArtifact materialize(
            UUID taskId, String owner, AiArtifactCreateRequest request) {
        if (request.format() == AiArtifactFormat.IMAGE) {
            if (request.sourceImageId() == null) {
                throw new AiServiceException(
                        HttpStatus.BAD_REQUEST, "IMAGE artifact requires sourceImageId");
            }
            var entity = imageService.find(request.sourceImageId(), owner);
            var bytes = imageService.read(request.sourceImageId(), owner);
            return new MaterializedArtifact(entity.getMediaType(), bytes);
        }
        var content = request.content();
        if (content == null) content = latestAssistantText(taskId);
        return switch (request.format()) {
            case MARKDOWN, TEXT ->
                    new MaterializedArtifact(
                            request.format().mediaType(), content.getBytes(StandardCharsets.UTF_8));
            case JSON ->
                    new MaterializedArtifact(request.format().mediaType(), normalizeJson(content));
            case CSV ->
                    new MaterializedArtifact(request.format().mediaType(), sanitizeCsv(content));
            case PDF, DOCX, XLSX -> {
                var rendered =
                        documentRenderer.render(
                                request.format(), nameWithoutExtension(request.name()), content);
                yield new MaterializedArtifact(rendered.mediaType(), rendered.bytes());
            }
            case IMAGE -> throw new IllegalStateException("handled above");
        };
    }

    private String latestAssistantText(UUID taskId) {
        return partRepository.findByTaskIdOrderBySequenceAsc(taskId).stream()
                .filter(
                        part ->
                                part.getRole() == AiPartRole.ASSISTANT
                                        && part.getKind() == AiPartKind.TEXT)
                .reduce((first, second) -> second)
                .map(AiTaskPartEntity::getTextContent)
                .orElseThrow(
                        () ->
                                new AiServiceException(
                                        HttpStatus.CONFLICT,
                                        "Task has no assistant text to materialize"));
    }

    private byte[] normalizeJson(String content) {
        try {
            var node = objectMapper.readTree(content);
            return objectMapper
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(node)
                    .getBytes(StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new AiServiceException(HttpStatus.BAD_REQUEST, "Artifact JSON is invalid");
        }
    }

    private static byte[] sanitizeCsv(String content) {
        var rows = parseCsv(content);
        var output = new StringBuilder();
        for (var row : rows) {
            if (!output.isEmpty()) output.append("\r\n");
            for (int index = 0; index < row.size(); index++) {
                if (index > 0) output.append(',');
                var cell = row.get(index);
                if (!cell.isEmpty() && "=+-@".indexOf(cell.charAt(0)) >= 0) cell = "'" + cell;
                output.append('"').append(cell.replace("\"", "\"\"")).append('"');
            }
        }
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static List<List<String>> parseCsv(String text) {
        var rows = new ArrayList<List<String>>();
        var row = new ArrayList<String>();
        var cell = new StringBuilder();
        var quoted = false;
        for (int index = 0; index < text.length(); index++) {
            var value = text.charAt(index);
            if (value == '"') {
                if (quoted && index + 1 < text.length() && text.charAt(index + 1) == '"') {
                    cell.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (value == ',' && !quoted) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if ((value == '\n' || value == '\r') && !quoted) {
                if (value == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n')
                    index++;
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(List.copyOf(row));
                row.clear();
            } else {
                cell.append(value);
            }
        }
        if (quoted) {
            throw new AiServiceException(
                    HttpStatus.BAD_REQUEST, "Artifact CSV has an unterminated quote");
        }
        if (!cell.isEmpty() || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(List.copyOf(row));
        }
        return rows;
    }

    private static String normalizeName(String raw, AiArtifactFormat format) {
        var value = raw.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (value.isBlank())
            throw new AiServiceException(HttpStatus.BAD_REQUEST, "Artifact name is empty");
        if (format != AiArtifactFormat.IMAGE
                && !value.toLowerCase(Locale.ROOT).endsWith(format.extension())) {
            value += format.extension();
        }
        if (value.length() > 255) {
            throw new AiServiceException(HttpStatus.BAD_REQUEST, "Artifact name is too long");
        }
        return value;
    }

    private static String extension(String name, String mediaType) {
        var index = name.lastIndexOf('.');
        if (index >= 0 && name.substring(index).matches("\\.[A-Za-z0-9]{1,10}")) {
            return name.substring(index).toLowerCase(Locale.ROOT);
        }
        return switch (mediaType) {
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            case "image/png" -> ".png";
            case "application/pdf" -> ".pdf";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                    ".docx";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx";
            default -> ".bin";
        };
    }

    private static String nameWithoutExtension(String name) {
        var index = name.lastIndexOf('.');
        return index > 0 ? name.substring(0, index) : name;
    }

    private record MaterializedArtifact(String mediaType, byte[] bytes) {
        MaterializedArtifact {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public record AiArtifactContent(AiArtifactEntity metadata, byte[] bytes) {
        public AiArtifactContent {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
