package com.yubai.blog.note;

import jakarta.validation.constraints.NotNull;

public record NoteStatusChangeRequest(@NotNull Long version) {
}
