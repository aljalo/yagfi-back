package com.github.regyl.gfi.service.impl.issueload.issuesource.gitlab;

import com.github.regyl.gfi.annotation.DefaultUnitTest;
import com.github.regyl.gfi.model.IssueTables;
import com.github.regyl.gfi.model.event.IssueSyncCompletedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DefaultUnitTest
class GitlabIssueSourceServiceImplTest {

    @Test
    void placeholderDoesNotAdvertiseSuccessfulSync() {
        List<IssueSyncCompletedEvent> events = new ArrayList<>();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(GitlabIssueSourceServiceImpl.class);
            context.addApplicationListener(event -> {
                if (event instanceof org.springframework.context.PayloadApplicationEvent<?> payload
                        && payload.getPayload() instanceof IssueSyncCompletedEvent completed) {
                    events.add(completed);
                }
            });
            context.refresh();
            var service = context.getBean(GitlabIssueSourceServiceImpl.class);
            assertThat(service.upload(IssueTables.FIRST)).isEmpty();
            service.raiseUploadEvent();
            assertThat(events).isEmpty();
        }
    }
}
