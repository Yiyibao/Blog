package com.yubai.blog.note;

public class NoteVersionConflictException extends RuntimeException {
    public NoteVersionConflictException() { super("笔记已在其他位置更新，请刷新后重试"); }
}
