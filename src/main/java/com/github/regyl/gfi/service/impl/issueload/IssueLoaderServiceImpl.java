package com.github.regyl.gfi.service.impl.issueload;

import com.github.regyl.gfi.model.IssueTables;
import com.github.regyl.gfi.service.ScheduledService;
import com.github.regyl.gfi.service.issueload.IssueSourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(value = "spring.properties.feature-enabled.auto-upload", havingValue = "true")
public class IssueLoaderServiceImpl implements ScheduledService {

    private final Collection<IssueSourceService> sourceServices;

    private final JdbcTemplate jdbcTemplate;
    private final CacheManager cacheManager;
    private final PlatformTransactionManager transactionManager;

    @Override
    @Scheduled(fixedRateString = "${spring.properties.auto-upload.period-mills}", initialDelay = 1000)
    public void schedule() {
        log.info("Start issue load task");
        IssueTables table = determineTable();
        jdbcTemplate.execute("TRUNCATE TABLE gfi." + table.getRepoTableName() + " RESTART IDENTITY CASCADE");
        Collection<CompletableFuture<Void>> futures = sourceServices.stream()
                .flatMap(service -> service.upload(table).stream())
                .toList();

        //waiting all issues to be done
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> replaceView(table));
        clearCaches();
        sourceServices.forEach(IssueSourceService::raiseUploadEvent);
        log.info("Issue load finished");
    }

    private IssueTables determineTable() {
        String activeTable = jdbcTemplate.queryForObject(
                "select table_name from information_schema.view_table_usage "
                        + "where view_schema = 'gfi' and view_name = 'issue_v' and table_schema = 'gfi'",
                String.class
        );
        for (IssueTables table : IssueTables.values()) {
            if (table.getIssueTableName().equals(activeTable)) {
                return IssueTables.getDifferent(table);
            }
        }
        throw new IllegalStateException("Cannot determine active issue table: " + activeTable);
    }

    private void replaceView(IssueTables table) {
        jdbcTemplate.execute("CREATE OR REPLACE VIEW gfi.issue_v as select * from gfi." + table.getIssueTableName());
        jdbcTemplate.execute("CREATE OR REPLACE VIEW gfi.repository_v as select * from gfi."
                + table.getRepoTableName());

        log.info("Views recreated");

        IssueTables expiredTable = IssueTables.getDifferent(table);
        jdbcTemplate.execute("TRUNCATE TABLE gfi." + expiredTable.getIssueTableName());
        jdbcTemplate.execute(String.format("TRUNCATE TABLE gfi.%s CASCADE", expiredTable.getRepoTableName()));

        log.info("Expired tables truncated");

        jdbcTemplate.execute(String.format("alter sequence gfi.%s_id_seq restart", expiredTable.getIssueTableName()));
        jdbcTemplate.execute(String.format("alter sequence gfi.%s_id_seq restart", expiredTable.getRepoTableName()));
        log.info("Sequences restarted");

    }

    private void clearCaches() {
        cacheManager.getCacheNames().forEach(cacheName -> {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
            }
        });
        log.info("Caches evicted");
    }
}
