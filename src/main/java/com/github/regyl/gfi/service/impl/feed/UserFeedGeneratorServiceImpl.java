package com.github.regyl.gfi.service.impl.feed;

import com.github.regyl.gfi.controller.dto.github.repos.UserDataGraphQlResponseDto;
import com.github.regyl.gfi.entity.UserFeedRequestEntity;
import com.github.regyl.gfi.model.SbomModel;
import com.github.regyl.gfi.model.UserFeedRequestStatuses;
import com.github.regyl.gfi.model.smtp.EmailModel;
import com.github.regyl.gfi.repository.UserFeedRequestRepository;
import com.github.regyl.gfi.service.ScheduledService;
import com.github.regyl.gfi.service.email.EmailService;
import com.github.regyl.gfi.service.feed.CycloneDxService;
import com.github.regyl.gfi.service.github.GithubClientService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.core5.http.HttpHost;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Generates one feed at a time, completing it only after every SBOM result is persisted.
 * Failed requests are retained with FAILED status and never receive a success email.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(value = "spring.properties.feature-enabled.feed-generation", havingValue = "true")
public class UserFeedGeneratorServiceImpl implements ScheduledService {

    private final GithubClientService<String, UserDataGraphQlResponseDto> githubClient;
    private final UserFeedRequestRepository repository;
    private final CycloneDxService cycloneDxService;
    private final BiConsumer<SbomModel, Throwable> resultConsumer;
    private final EmailService emailService;
    private final AtomicBoolean processing = new AtomicBoolean();

    /**
     * Scheduled method that processes waiting feed requests.
     * Only starts processing if all CycloneDX services are free to avoid blocking the scheduler.
     * Spring's scheduler has limited core pool size, so we must check availability before starting.
     */
    @Override
    @Scheduled(fixedRate = 60_000, initialDelay = 1_000)
    public void schedule() {
        if (!processing.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!cycloneDxService.isFree()) {
                return;
            }
            Optional<UserFeedRequestEntity> request = repository.findOldestByStatus(
                    UserFeedRequestStatuses.WAITING_FOR_PROCESS.getValue()
            );
            if (request.isEmpty()) {
                return;
            }
            UserFeedRequestEntity entity = request.get();
            repository.updateStatusById(entity.getId(), UserFeedRequestStatuses.PROCESSING);
            try {
                process(entity);
            } catch (Exception e) {
                repository.updateStatusById(entity.getId(), UserFeedRequestStatuses.FAILED);
                log.error("Feed generation failed for nickname {}", entity.getNickname(), e);
            }
        } finally {
            processing.set(false);
        }
    }

    private void process(UserFeedRequestEntity rq) {
        long start = System.nanoTime();
        String nickname = rq.getNickname();
        Queue<String> userRepos = new ArrayDeque<>(getRepos(nickname).getRepoUrls().stream().distinct().toList());

        while (!userRepos.isEmpty()) {
            Queue<HttpHost> hosts = cycloneDxService.getFreeHosts();
            if (hosts.isEmpty()) {
                throw new IllegalStateException("No CycloneDX host available for feed generation");
            }
            List<CompletableFuture<?>> results = new ArrayList<>();
            while (!hosts.isEmpty() && !userRepos.isEmpty()) {
                String url = userRepos.poll();
                HttpHost host = hosts.poll();
                results.add(cycloneDxService.getSbom(url, host).whenComplete((dto, throwable) ->
                        resultConsumer.accept(new SbomModel(rq, dto, url), throwable)));
            }
            CompletableFuture.allOf(results.toArray(new CompletableFuture<?>[0])).join();
        }

        repository.updateStatusById(rq.getId(), UserFeedRequestStatuses.PROCESSED);
        EmailModel emailModel = new EmailModel(
                rq.getEmail(),
                "Your personalized feed generated!",
                "Feed generation completed. Please check yagfi.com/feed/" + nickname
        );
        try {
            emailService.send(emailModel);
        } catch (Exception e) {
            log.error("Feed generated but notification delivery failed for {}", nickname, e);
        }
        long processTime = Duration.ofNanos(System.nanoTime() - start).toMinutes();
        log.info("Finished generating feed for nickname {} in {} minutes", nickname, processTime);
    }

    private UserDataGraphQlResponseDto getRepos(String login) {


        return githubClient.execute(login);
    }
}
