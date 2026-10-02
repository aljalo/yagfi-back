package com.github.regyl.gfi.service.impl.issueload;

import com.github.regyl.gfi.annotation.DefaultUnitTest;
import com.github.regyl.gfi.model.IssueTables;
import com.github.regyl.gfi.service.issueload.IssueSourceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DefaultUnitTest
class IssueLoaderServiceImplTest {

    @Mock
    IssueSourceService source;
    @Mock
    JdbcTemplate jdbc;
    @Mock
    CacheManager caches;
    @Mock
    PlatformTransactionManager transactions;

    IssueLoaderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new IssueLoaderServiceImpl(List.of(source), jdbc, caches, transactions);
        when(jdbc.queryForObject(anyString(), eq(String.class))).thenReturn("e_issue_1");
        when(source.upload(IssueTables.SECOND)).thenReturn(List.of(CompletableFuture.completedFuture(null)));
    }

    @Test
    void publishesOnlyAfterViewSwitchCommitsAndCachesClear() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(caches.getCacheNames()).thenReturn(List.of());
        service.schedule();
        var order = inOrder(jdbc, transactions, caches, source);
        order.verify(source).upload(IssueTables.SECOND);
        order.verify(jdbc).execute("CREATE OR REPLACE VIEW gfi.issue_v as select * from gfi.e_issue_2");
        order.verify(jdbc).execute("CREATE OR REPLACE VIEW gfi.repository_v as select * from gfi.e_repository_2");
        order.verify(transactions).commit(any());
        order.verify(caches).getCacheNames();
        order.verify(source).raiseUploadEvent();
    }

    @Test
    void uploadFailurePreservesViewsAndDoesNotPublish() {
        when(source.upload(IssueTables.SECOND))
                .thenReturn(List.of(CompletableFuture.failedFuture(new IllegalStateException("load failed"))));
        assertThatThrownBy(service::schedule).isInstanceOf(java.util.concurrent.CompletionException.class);
        verify(jdbc, never()).execute("CREATE OR REPLACE VIEW gfi.issue_v as select * from gfi.e_issue_2");
        verify(source, never()).raiseUploadEvent();
    }

    @Test
    void switchFailureRollsBackAndDoesNotPublish() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.startsWith("CREATE OR REPLACE VIEW gfi.repository_v")) {
                throw new IllegalStateException("switch failed");
            }
            return null;
        }).when(jdbc).execute(anyString());
        assertThatThrownBy(service::schedule).isInstanceOf(IllegalStateException.class);
        verify(transactions).rollback(any());
        verify(source, never()).raiseUploadEvent();
        verify(caches, never()).getCacheNames();
    }
}
