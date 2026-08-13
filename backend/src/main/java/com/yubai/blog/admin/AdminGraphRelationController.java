package com.yubai.blog.admin;

import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.graph.GraphRelationOrigin;
import com.yubai.blog.graph.GraphRelationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/graph/relations")
@Validated
public class AdminGraphRelationController {
    private final GraphRelationService service;

    public AdminGraphRelationController(GraphRelationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<GraphRelationService.Response>> list(
            @RequestParam(required = false) String sourceId,
            @RequestParam(required = false) String targetId) {
        return ApiResponse.ok(service.list(sourceId, targetId));
    }

    @GetMapping("/{id}/audit")
    public ApiResponse<List<GraphRelationService.AuditResponse>> audit(@PathVariable UUID id) {
        return ApiResponse.ok(service.audits(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<GraphRelationService.Response> create(
            @Valid @RequestBody RelationRequest request, Principal principal) {
        return ApiResponse.created(
                service.create(
                        new GraphRelationService.CreateRequest(
                                request.sourceId(), request.targetId(), request.relationType()),
                        principal.getName(),
                        GraphRelationOrigin.MANUAL));
    }

    @PutMapping("/{id}")
    public ApiResponse<GraphRelationService.Response> update(
            @PathVariable UUID id,
            @RequestParam long version,
            @Valid @RequestBody RelationRequest request,
            Principal principal) {
        return ApiResponse.ok(
                service.update(
                        id,
                        version,
                        new GraphRelationService.UpdateRequest(
                                request.sourceId(), request.targetId(), request.relationType()),
                        principal.getName()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @RequestParam long version, Principal principal) {
        service.delete(id, version, principal.getName());
    }

    @PostMapping("/import-preview")
    public ApiResponse<GraphRelationService.ImportPreview> importPreview(
            @Valid @RequestBody ImportPreviewRequest request) {
        return ApiResponse.ok(service.previewImport(request.payload(), true));
    }

    public record RelationRequest(
            @NotBlank @Size(max = 128) String sourceId,
            @NotBlank @Size(max = 128) String targetId,
            @NotBlank @Size(max = 64) String relationType) {}

    public record ImportPreviewRequest(@NotBlank @Size(max = 1_000_000) String payload) {}
}
