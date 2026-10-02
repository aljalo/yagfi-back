package com.github.regyl.gfi.service.impl.issueload.issuesource.gitlab;

import com.github.regyl.gfi.model.IssueTables;
import com.github.regyl.gfi.service.issueload.IssueSourceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
public class GitlabIssueSourceServiceImpl implements IssueSourceService {

    @Override
    public Collection<CompletableFuture<Void>> upload(IssueTables table) {
        //https://docs.gitlab.com/api/graphql/
        //https://gitlab.com/gitlab-org/gitlab-development-kit/-/issues/1822
        return Collections.emptyList();
    }

    @Override
    public void raiseUploadEvent() {
        log.debug("GitLab ingestion is not implemented; no completion event published");
    }
}
