package com.yubai.blog.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiResponseTest {
    @Test
    void wrapsDataWithTimestamp() {
        var response = ApiResponse.ok("ready");
        assertThat(response.data()).isEqualTo("ready");
        assertThat(response.timestamp()).isNotNull();
    }
}
