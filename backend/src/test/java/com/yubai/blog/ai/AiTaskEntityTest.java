package com.yubai.blog.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AiTaskEntityTest {
    @Test
    void enforcesTerminalStateMachine() {
        var task = AiTaskEntity.create("alice", 1L, "CHAT", 2L, "vision-model", "key");

        task.start("OPENAI_RESPONSES", "vision-model");
        task.complete();
        task.cancel();
        task.fail("LATE", "ignored");

        assertThat(task.getStatus()).isEqualTo(AiTaskStatus.COMPLETED);
        assertThat(task.getFinishedAt()).isNotNull();
        assertThatThrownBy(() -> task.start("OPENAI_RESPONSES", "vision-model"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancellationWinsBeforeCompletion() {
        var task = AiTaskEntity.create("alice", 1L, "CHAT", null, null, "key");
        task.start("OPENAI_RESPONSES", "model");
        task.cancel();

        assertThat(task.getStatus()).isEqualTo(AiTaskStatus.CANCELLED);
        assertThatThrownBy(task::complete).isInstanceOf(IllegalStateException.class);
    }
}
