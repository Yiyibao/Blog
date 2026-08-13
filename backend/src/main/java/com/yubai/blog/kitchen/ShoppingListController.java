package com.yubai.blog.kitchen;

import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.kitchen.ShoppingListDtos.ShoppingListResponse;
import com.yubai.blog.kitchen.ShoppingListDtos.UpdateRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/kitchen/shopping-lists")
public class ShoppingListController {
    private final ShoppingListService service;

    public ShoppingListController(ShoppingListService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<ShoppingListResponse> get(
            @RequestParam String weekStart, Authentication authentication) {
        return ApiResponse.ok(
                service.getOrCreate(
                        ownerId(authentication), ShoppingListService.parseWeekStart(weekStart)));
    }

    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<ShoppingListResponse>> generate(
            @RequestParam String weekStart,
            @RequestHeader(value = "Idempotency-Key", required = false) String mutationKey,
            Authentication authentication) {
        var response =
                service.generate(
                        ownerId(authentication),
                        ShoppingListService.parseWeekStart(weekStart),
                        mutationKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(response));
    }

    @PutMapping("/{id}")
    public ApiResponse<ShoppingListResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String mutationKey,
            Authentication authentication) {
        return ApiResponse.ok(service.update(ownerId(authentication), id, request, mutationKey));
    }

    @PostMapping("/{id}/clear-checked")
    public ApiResponse<ShoppingListResponse> clearChecked(
            @PathVariable UUID id,
            @RequestParam long expectedVersion,
            @RequestHeader(value = "Idempotency-Key", required = false) String mutationKey,
            Authentication authentication) {
        return ApiResponse.ok(
                service.clearChecked(ownerId(authentication), id, expectedVersion, mutationKey));
    }

    private long ownerId(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            Long uid = jwtAuthentication.getToken().getClaim("uid");
            if (uid != null) return uid;
        }
        throw new org.springframework.security.access.AccessDeniedException("登录凭据版本过旧，请退出后重新登录");
    }
}
