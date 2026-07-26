package com.yubai.blog.auth;

import jakarta.validation.constraints.NotBlank;

/** FD-25：自助改密——需先验当前口令；强度规则在 Service（与 MemberBootstrap 同源 ≥12）。 */
public record PasswordChangeRequest(
    @NotBlank String currentPassword,
    @NotBlank String newPassword
) {
}
