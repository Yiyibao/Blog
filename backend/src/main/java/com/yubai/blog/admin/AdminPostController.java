package com.yubai.blog.admin;

import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.common.PageResponse;
import com.yubai.blog.post.PostMarkdownConversionService;
import com.yubai.blog.post.PostRequest;
import com.yubai.blog.post.PostResponse;
import com.yubai.blog.post.PostRevisionService;
import com.yubai.blog.post.PostService;
import com.yubai.blog.post.PostStatus;
import com.yubai.blog.post.PostSummary;
import com.yubai.blog.post.PostWorkflowService;
import com.yubai.blog.series.SeriesService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
@RequestMapping("/api/v1/admin/posts")
@Validated
public class AdminPostController {
    private final PostService service;
    private final PostMarkdownConversionService conversionService;
    private final SeriesService seriesService;
    private final PostRevisionService revisionService;
    private final PostWorkflowService workflowService;

    public AdminPostController(
            PostService service,
            PostMarkdownConversionService conversionService,
            SeriesService seriesService,
            PostRevisionService revisionService,
            PostWorkflowService workflowService) {
        this.service = service;
        this.conversionService = conversionService;
        this.seriesService = seriesService;
        this.revisionService = revisionService;
        this.workflowService = workflowService;
    }

    /**
     * 3A-2：存量 HTML→Markdown 一次性转换——只回填 markdown_content（读路径不变），
     * 响应即人工校对清单（含表格/嵌套列表/公式类等高风险标记）。幂等，force=true 覆盖重转。
     */
    @PostMapping("/convert-markdown")
    public ApiResponse<java.util.List<PostMarkdownConversionService.ConversionReport>>
            convertMarkdown(@RequestParam(defaultValue = "false") boolean force) {
        return ApiResponse.ok(conversionService.convertAll(force));
    }

    /** P1-2：列表只出摘要，编辑时前端经 findOne 拉全文。 */
    @GetMapping
    public ApiResponse<PageResponse<PostSummary>> findAll(
            @RequestParam(required = false) PostStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return ApiResponse.ok(service.findAdmin(status, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<PostResponse> findOne(@PathVariable long id) {
        return ApiResponse.ok(service.findOne(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PostResponse> create(@Valid @RequestBody PostRequest request) {
        return ApiResponse.created(service.createWithRevision(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<PostResponse> update(
            @PathVariable long id, @Valid @RequestBody PostRequest request) {
        return ApiResponse.ok(service.updateWithRevision(id, request));
    }

    // 4C：版本历史——列表/查看/恢复（恢复 = 回写正文字段并产生新版本）
    @GetMapping("/{id}/revisions")
    public ApiResponse<java.util.List<PostRevisionService.RevisionSummary>> revisions(
            @PathVariable long id) {
        return ApiResponse.ok(revisionService.list(id));
    }

    @GetMapping("/{id}/revisions/{revisionId}")
    public ApiResponse<PostRevisionService.RevisionDetail> revision(
            @PathVariable long id, @PathVariable long revisionId) {
        return ApiResponse.ok(revisionService.findOne(id, revisionId));
    }

    @PostMapping("/{id}/revisions/{revisionId}/restore")
    public ApiResponse<PostResponse> restoreRevision(
            @PathVariable long id, @PathVariable long revisionId) {
        return ApiResponse.ok(revisionService.restore(id, revisionId));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        // 4B：文章删除后清掉合集成员引用（编排在 Controller 层，避免 post→series 循环依赖）
        service.delete(id);
        seriesService.removeEntriesForPost(id);
    }

    @PostMapping("/{id}/schedule")
    public ApiResponse<PostWorkflowService.WorkflowResult> schedule(
            @PathVariable long id,
            @Valid @RequestBody PostWorkflowService.ScheduleRequest request,
            java.security.Principal principal) {
        return ApiResponse.ok(
                workflowService.schedule(id, request.publishAt(), principal.getName()));
    }

    @DeleteMapping("/{id}/schedule")
    public ApiResponse<PostWorkflowService.WorkflowResult> cancelSchedule(
            @PathVariable long id, java.security.Principal principal) {
        return ApiResponse.ok(workflowService.cancelSchedule(id, principal.getName()));
    }

    @PostMapping("/batch")
    public ApiResponse<java.util.List<PostWorkflowService.WorkflowResult>> batch(
            @Valid @RequestBody PostWorkflowService.BatchRequest request,
            java.security.Principal principal) {
        return ApiResponse.ok(workflowService.batch(request, principal.getName()));
    }

    @GetMapping("/audit")
    public ApiResponse<java.util.List<PostWorkflowService.AuditEntry>> audit() {
        return ApiResponse.ok(workflowService.recentAudit());
    }
}
