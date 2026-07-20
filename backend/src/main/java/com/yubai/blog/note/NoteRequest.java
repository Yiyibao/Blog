package com.yubai.blog.note;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record NoteRequest(
    @NotBlank @Size(max = 200) String title,
    @NotNull @Size(max = 2_000_000) String markdownContent,
    @NotBlank @Size(max = 100) String folder,
    @NotNull NoteStatus status,
    @NotNull @Size(max = 20) List<@NotBlank @Size(max = 80) String> tags,
    @NotNull Long version
) {}
