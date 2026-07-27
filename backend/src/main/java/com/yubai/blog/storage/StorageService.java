package com.yubai.blog.storage;

public interface StorageService {
    String store(String storageKey, byte[] data);
    byte[] read(String storageKey);
    void delete(String storageKey);
}
