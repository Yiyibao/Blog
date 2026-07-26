package com.yubai.blog.admin;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yubai.blog.admin.ai.AiUsageService;
import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.dish.DishRepository;
import com.yubai.blog.note.NoteAttachmentRepository;
import com.yubai.blog.note.NoteRepository;
import com.yubai.blog.post.PostRepository;
import com.yubai.blog.post.PostStatus;
import com.yubai.blog.stats.ViewDailyService;

/** 4D：仪表盘统计——计数 + 30 天浏览趋势 + TOP5 热文 + 附件容量 + AI 用量卡片。 */
@RestController
@RequestMapping("/api/v1/admin/stats")
public class AdminDashboardController {

    private final PostRepository postRepository;
    private final DishRepository dishRepository;
    private final NoteRepository noteRepository;
    private final NoteAttachmentRepository attachmentRepository;
    private final ViewDailyService viewDailyService;
    private final AiUsageService aiUsageService;

    public AdminDashboardController(PostRepository postRepository, DishRepository dishRepository,
                                    NoteRepository noteRepository, NoteAttachmentRepository attachmentRepository,
                                    ViewDailyService viewDailyService, AiUsageService aiUsageService) {
        this.postRepository = postRepository;
        this.dishRepository = dishRepository;
        this.noteRepository = noteRepository;
        this.attachmentRepository = attachmentRepository;
        this.viewDailyService = viewDailyService;
        this.aiUsageService = aiUsageService;
    }

    @GetMapping
    public ApiResponse<Stats> getStats() {
        var storage = attachmentRepository.aggregateStorage();
        var aiRows = aiUsageService.summarize(30);
        long aiRequests = 0;
        long aiTokens = 0;
        for (var row : aiRows) {
            aiRequests += row.getRequests();
            aiTokens += row.getPromptTokens() + row.getCompletionTokens();
        }
        return ApiResponse.ok(new Stats(
            postRepository.count(),
            dishRepository.count(),
            noteRepository.count(),
            postRepository.countByStatus(PostStatus.PUBLISHED),
            postRepository.countByStatus(PostStatus.DRAFT),
            storage.getCnt(),
            storage.getBytes(),
            viewDailyService.trend(),
            postRepository.findTopViewed(PageRequest.of(0, 5)).stream()
                .map(row -> new TopPost(row.getTitle(), row.getSlug(), row.getViewsCount(), row.getLikeCount()))
                .toList(),
            new AiUsage(aiRequests, aiTokens)
        ));
    }

    public record TopPost(String title, String slug, int viewsCount, int likeCount) {}

    /** 30 天窗口的 AI 用量汇总（详单在 /admin/ai/usage）。 */
    public record AiUsage(long requests, long tokens) {}

    public record Stats(long posts, long dishes, long notes,
                        long publishedPosts, long draftPosts,
                        long attachmentCount, long attachmentBytes,
                        List<ViewDailyService.DayViews> viewTrend,
                        List<TopPost> topPosts,
                        AiUsage aiUsage) {}
}
