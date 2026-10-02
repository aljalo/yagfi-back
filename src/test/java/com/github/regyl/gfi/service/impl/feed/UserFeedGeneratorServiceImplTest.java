package com.github.regyl.gfi.service.impl.feed;

import com.github.regyl.gfi.annotation.DefaultUnitTest;
import com.github.regyl.gfi.controller.dto.cyclonedx.sbom.SbomResponseDto;
import com.github.regyl.gfi.controller.dto.github.repos.UserDataGraphQlResponseDto;
import com.github.regyl.gfi.entity.UserFeedRequestEntity;
import com.github.regyl.gfi.model.SbomModel;
import com.github.regyl.gfi.model.UserFeedRequestStatuses;
import com.github.regyl.gfi.repository.UserFeedRequestRepository;
import com.github.regyl.gfi.service.email.EmailService;
import com.github.regyl.gfi.service.feed.CycloneDxService;
import com.github.regyl.gfi.service.github.GithubClientService;
import org.apache.hc.core5.http.HttpHost;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DefaultUnitTest
class UserFeedGeneratorServiceImplTest {

    @Mock
    GithubClientService<String, UserDataGraphQlResponseDto> githubClient;
    @Mock
    UserFeedRequestRepository repository;
    @Mock
    CycloneDxService cycloneDx;
    @Mock
    BiConsumer<SbomModel, Throwable> consumer;
    @Mock
    EmailService email;
    @Mock
    UserDataGraphQlResponseDto repos;

    UserFeedGeneratorServiceImpl service;
    UserFeedRequestEntity request;
    HttpHost host = new HttpHost("localhost", 9090);

    @BeforeEach
    void setUp() {
        request = UserFeedRequestEntity.builder().id(1L).nickname("ali").email("test@example.org").build();
        service = new UserFeedGeneratorServiceImpl(githubClient, repository, cycloneDx, consumer, email);
        when(cycloneDx.isFree()).thenReturn(true);
        when(repository.findOldestByStatus("waiting-for-process")).thenReturn(Optional.of(request));
        when(githubClient.execute("ali")).thenReturn(repos);
        when(repos.getRepoUrls()).thenReturn(List.of("https://github.com/ali/example"));
        when(cycloneDx.getFreeHosts()).thenAnswer(invocation -> new ArrayDeque<>(List.of(host)));
    }

    @Test
    void waitsForResultProcessingBeforeSuccess() throws Exception {
        CompletableFuture<SbomResponseDto> pending = new CompletableFuture<>();
        when(cycloneDx.getSbom(any(), eq(host))).thenReturn(pending);
        CompletableFuture<Void> execution = CompletableFuture.runAsync(service::schedule);
        verify(repository, org.mockito.Mockito.timeout(2000)).updateStatusById(1L, UserFeedRequestStatuses.PROCESSING);
        verify(repository, never()).updateStatusById(1L, UserFeedRequestStatuses.PROCESSED);
        verify(email, never()).send(any());
        pending.complete(new SbomResponseDto());
        execution.get(5, java.util.concurrent.TimeUnit.SECONDS);
        var order = inOrder(consumer, repository, email);
        order.verify(consumer).accept(any(), eq(null));
        order.verify(repository).updateStatusById(1L, UserFeedRequestStatuses.PROCESSED);
        order.verify(email).send(any());
    }

    @Test
    void sbomFailureDoesNotSendSuccess() {
        when(cycloneDx.getSbom(any(), eq(host)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("unavailable")));
        service.schedule();
        verify(repository).updateStatusById(1L, UserFeedRequestStatuses.FAILED);
        verify(repository, never()).updateStatusById(1L, UserFeedRequestStatuses.PROCESSED);
        verify(email, never()).send(any());
    }

    @Test
    void persistenceFailureDoesNotSendSuccess() {
        when(cycloneDx.getSbom(any(), eq(host))).thenReturn(CompletableFuture.completedFuture(new SbomResponseDto()));
        doThrow(new IllegalStateException("database unavailable")).when(consumer).accept(any(), eq(null));
        service.schedule();
        verify(repository).updateStatusById(1L, UserFeedRequestStatuses.FAILED);
        verify(email, never()).send(any());
    }

    @Test
    void emailFailureKeepsCompletedFeed() {
        when(cycloneDx.getSbom(any(), eq(host))).thenReturn(CompletableFuture.completedFuture(new SbomResponseDto()));
        doThrow(new IllegalStateException("smtp unavailable")).when(email).send(any());
        service.schedule();
        verify(repository).updateStatusById(1L, UserFeedRequestStatuses.PROCESSED);
        verify(repository, never()).updateStatusById(1L, UserFeedRequestStatuses.FAILED);
    }
}
