package com.github.regyl.gfi.service.impl.issueload;

import com.github.regyl.gfi.annotation.DefaultIntegrationTest;
import com.github.regyl.gfi.model.IssueTables;
import com.github.regyl.gfi.service.issueload.IssueSourceService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

@DefaultIntegrationTest
@Testcontainers
class IssueLoaderDatabaseTest {

    @Container
    static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:15.3");

    JdbcTemplate jdbc;
    DataSourceTransactionManager transactions;
    AtomicBoolean published;
    IssueSourceService source;

    @BeforeEach
    void setUp() {
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(pg.getJdbcUrl());
        dataSource.setUser(pg.getUsername());
        dataSource.setPassword(pg.getPassword());
        var flyway = Flyway.configure().dataSource(dataSource).schemas("gfi").cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = spy(new JdbcTemplate(dataSource));
        transactions = new DataSourceTransactionManager(dataSource);
        published = new AtomicBoolean();
        source = new IssueSourceService() {
            @Override
            public Collection<CompletableFuture<Void>> upload(IssueTables table) {
                jdbc.update("INSERT INTO gfi." + table.getRepoTableName()
                        + " (source_id, title, url, stars) VALUES ('new', 'new', 'https://example.org', 1)");
                return List.of(CompletableFuture.completedFuture(null));
            }

            @Override
            public void raiseUploadEvent() {
                assertThat(jdbc.queryForObject("SELECT title FROM gfi.repository_v", String.class)).isEqualTo("new");
                published.set(true);
            }
        };
    }

    @Test
    void emptyActiveDatasetStillLoadsIntoInactiveTables() {
        var service = new IssueLoaderServiceImpl(List.of(source), jdbc,
                new ConcurrentMapCacheManager(), transactions);
        service.schedule();
        assertThat(published).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gfi.e_repository_2", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gfi.e_repository_1", Long.class)).isZero();
    }

    @Test
    void failedSecondViewSwitchRollsBackFirstView() {
        doThrow(new IllegalStateException("switch failed")).when(jdbc)
                .execute("CREATE OR REPLACE VIEW gfi.repository_v as select * from gfi.e_repository_2");
        var service = new IssueLoaderServiceImpl(List.of(source), jdbc,
                new ConcurrentMapCacheManager(), transactions);
        assertThatThrownBy(service::schedule).isInstanceOf(IllegalStateException.class);
        assertThat(published).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gfi.repository_v", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT table_name FROM information_schema.view_table_usage "
                + "WHERE view_schema = 'gfi' AND view_name = 'issue_v'", String.class)).isEqualTo("e_issue_1");
    }
}
