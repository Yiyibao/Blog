package com.yubai.blog.admin;

import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.dish.DishAssetResponse;
import com.yubai.blog.dish.DishAssetService;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin/dish-assets")
public class AdminDishAssetController {
    private final DishAssetService service;

    public AdminDishAssetController(DishAssetService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DishAssetResponse> upload(
            @RequestParam("file") MultipartFile file, Principal principal) {
        return ApiResponse.created(service.uploadStaged(file, principal.getName()));
    }

    @PostMapping("/{publicId}/attach/{dishId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void attach(
            @PathVariable UUID publicId, @PathVariable long dishId, Principal principal) {
        service.assignToDish(publicId, dishId, principal.getName());
    }

    @DeleteMapping("/{publicId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID publicId, Principal principal) {
        service.deleteStaged(publicId, principal.getName());
    }
}
