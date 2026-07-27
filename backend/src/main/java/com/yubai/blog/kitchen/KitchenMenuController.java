package com.yubai.blog.kitchen;

import java.time.Duration;
import java.time.LocalDate;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.common.PageResponse;
import com.yubai.blog.common.RateLimiter;
import com.yubai.blog.common.TooManyRequestsException;
import com.yubai.blog.kitchen.KitchenDtos.DailyMenuRequest;
import com.yubai.blog.kitchen.KitchenDtos.DailyMenuResponse;
import com.yubai.blog.kitchen.KitchenDtos.DailyMenuSummary;
import com.yubai.blog.kitchen.KitchenDtos.MenuItemRequest;

import jakarta.validation.Valid;

/**
 * FD-10：今日菜单（两人私有，hasAnyRole(ADMIN,PARTNER) 由 SecurityConfiguration 把守）。
 * 限流键用 uid 而非 IP——两人多半共用家庭 IP，按 IP 会互相吃额度（破例有注：全站其他公开端点仍按 IP）。
 */
@RestController
@RequestMapping("/api/v1/kitchen")
public class KitchenMenuController {
    static final int WRITE_LIMIT = 30;
    static final Duration WRITE_WINDOW = Duration.ofMinutes(1);

    private final DailyMenuService service;
    private final MealLogService mealLogService;
    private final RateLimiter rateLimiter;

    public KitchenMenuController(DailyMenuService service, MealLogService mealLogService, RateLimiter rateLimiter) {
        this.service = service;
        this.mealLogService = mealLogService;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/menus")
    public ApiResponse<DailyMenuResponse> getMenu(@RequestParam String date) {
        return ApiResponse.ok(service.getMenu(DailyMenuService.parseDate(date)));
    }

    @GetMapping("/menus/history")
    public ApiResponse<PageResponse<DailyMenuSummary>> history(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to) {
        LocalDate fromDate = from == null ? null : DailyMenuService.parseDate(from);
        LocalDate toDate = to == null ? null : DailyMenuService.parseDate(to);
        return ApiResponse.ok(service.history(page, size, fromDate, toDate));
    }

    @PostMapping("/menus/items")
    public ResponseEntity<ApiResponse<DailyMenuResponse>> appendItem(
        @RequestParam String date,
        @Valid @RequestBody MenuItemRequest request,
        Authentication authentication) {
        var actor = actorOf(authentication);
        throttleWrites(actor);
        var response = service.appendItem(DailyMenuService.parseDate(date), request, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PutMapping("/menus")
    public ApiResponse<DailyMenuResponse> putMenu(
        @RequestParam String date,
        @Valid @RequestBody DailyMenuRequest request,
        Authentication authentication) {
        var actor = actorOf(authentication);
        throttleWrites(actor);
        return ApiResponse.ok(service.putMenu(DailyMenuService.parseDate(date), request, actor));
    }

    @DeleteMapping("/menus/items/{id}")
    public ApiResponse<DailyMenuResponse> deleteItem(@PathVariable long id, Authentication authentication) {
        var actor = actorOf(authentication);
        throttleWrites(actor);
        return ApiResponse.ok(service.deleteItem(id, actor));
    }

    // ---- FD-15：打卡（meal-logs）----

    @PostMapping("/meal-logs")
    public ResponseEntity<ApiResponse<KitchenDtos.MealLogResponse>> createLog(
        @Valid @RequestBody KitchenDtos.MealLogRequest request, Authentication authentication) {
        var actor = actorOf(authentication);
        throttleLogs(actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(mealLogService.create(request, actor)));
    }

    /** FD-18 一键打卡：整桌菜各记一笔（同日同名同餐次幂等跳过）。 */
    @PostMapping("/menus/check-in")
    public ResponseEntity<ApiResponse<java.util.List<KitchenDtos.MealLogResponse>>> checkIn(
        @RequestParam String date, Authentication authentication) {
        var actor = actorOf(authentication);
        throttleLogs(actor);
        var created = mealLogService.checkInMenu(DailyMenuService.parseDate(date), actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    @GetMapping("/meal-logs")
    public ApiResponse<PageResponse<KitchenDtos.MealLogResponse>> timeline(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to,
        @RequestParam(required = false) String dishSlug) {
        var fromDate = from == null ? null : DailyMenuService.parseDate(from);
        var toDate = to == null ? null : DailyMenuService.parseDate(to);
        return ApiResponse.ok(mealLogService.timeline(page, size, fromDate, toDate, dishSlug));
    }

    @DeleteMapping("/meal-logs/{id}")
    public ResponseEntity<Void> deleteLog(@PathVariable long id, Authentication authentication) {
        var actor = actorOf(authentication);
        throttleLogs(actor);
        mealLogService.delete(id, actor);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/dish-stats")
    public ApiResponse<java.util.List<KitchenDtos.DishCookStat>> dishStats() {
        return ApiResponse.ok(mealLogService.cookStats());
    }

    private void throttleLogs(DailyMenuService.Actor actor) {
        if (!rateLimiter.tryAcquire("meallog:" + actor.id(), WRITE_LIMIT, WRITE_WINDOW)) {
            throw new TooManyRequestsException("操作太频繁啦，休息一分钟再来");
        }
    }

    private void throttleWrites(DailyMenuService.Actor actor) {
        if (!rateLimiter.tryAcquire("kitchen:" + actor.id(), WRITE_LIMIT, WRITE_WINDOW)) {
            throw new TooManyRequestsException("操作太频繁啦，休息一分钟再来");
        }
    }

    /** 身份只信 JWT claims（FD-6 签发 uid/name/roles），DTO 不收署名分量。 */
    private DailyMenuService.Actor actorOf(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            var jwt = jwtAuth.getToken();
            Long uid = jwt.getClaim("uid");
            var name = jwt.getClaimAsString("name");
            if (uid != null) {
                var admin = jwtAuth.getAuthorities().contains(new SimpleGrantedAuthority(com.yubai.blog.auth.Permissions.KITCHEN_DELETE_ANY));
                return new DailyMenuService.Actor(uid, name == null ? jwt.getSubject() : name, admin);
            }
        }
        // FD-6 之前签发的 token 没有 uid——要求重新登录而不是拿错误身份写数据
        throw new org.springframework.security.access.AccessDeniedException("登录凭据版本过旧，请退出后重新登录");
    }
}
