# Fork development

## Ownership and scope

This is `aljalo/yagfi-back`, a fork of [Regyl/yagfi-back](https://github.com/Regyl/yagfi-back).
Preserve the upstream license, credits, and contributor list. The upstream website
is not a deployment of this fork. Initial fork work focuses on accurate setup
documentation, working contribution links, and repeatable verification.

At the 2026-10-02 audit, fork `master` was at
`552ce2cb6882ba5192480351df6d669afffe51b5`, with no fork-only commits and 38
commits behind upstream `master` (`f3d6f392c00db715bd28a04f06491369878be99d`).
These are a dated snapshot, not a permanent synchronization status.

## Requirements and tests

- JDK 25, as specified by `pom.xml` and the existing Maven CI workflow.
- Maven; the repository does not include a Maven wrapper.
- A running Docker daemon for PostgreSQL Testcontainers integration tests.

From the repository root:

```bash
mvn --batch-mode --update-snapshots verify
```

This runs Checkstyle validation, compilation, and tests. The integration test
starts PostgreSQL 15.3 through Testcontainers, overrides datasource properties,
and applies Flyway migrations. The test profile disables background ingestion,
metadata loading, feed generation, and request logging. It uses placeholder
GitHub and email values; live credentials are not required by this test profile.

For individual groups, see [CONTRIBUTING.md](CONTRIBUTING.md). Unit tests alone
do not verify SQL migrations or database behavior.

The existing [Maven workflow](../.github/workflows/maven.yml) runs for pushes
and pull requests targeting `master`. It also supports manual runs once this
change is on the default branch. Fork Actions may need enabling in GitHub.
A workflow file is not proof of a successful run: record the run URL and commit
SHA before marking build verification complete.

## Local application

The supplied Compose file defines PostgreSQL and five cdxgen services. For
read-only issue API exploration, start only PostgreSQL:

```bash
docker compose -f gfi-devops/docker-compose.yaml up -d postgres
```

Use the local profile and explicitly disable feed generation for this restricted
mode. The local profile itself enables feed generation, which otherwise requires
the cdxgen services. Supply a placeholder GitHub token for startup; it cannot
perform real GitHub ingestion.

```bash
mvn spring-boot:run '-Dspring-boot.run.arguments=--spring.profiles.active=local --spring.properties.github.token=local-placeholder --spring.properties.feature-enabled.feed-generation=false'
```

The API base URL is `http://localhost:8080/api`. Example read requests:

```bash
curl --fail http://localhost:8080/api/issues/languages
curl --fail http://localhost:8080/api/events
```

A new database has no ingested issue data. This mode does not demonstrate live
ingestion, personalized feed generation, or notification delivery. For those
flows, configure GitHub access, email, and cdxgen deliberately and test them
separately. The Compose database password is a development example.

## Implementation audit and remaining work

- GitHub ingestion builds label queries, runs asynchronous loaders, switches
  issue/repository views between two table sets, and clears caches after refresh.
- GitLab ingestion remains unsupported. Its placeholder publishes no completion event.
- Refresh selects the inactive tables from the current view dependencies (including
  when the active dataset is empty). Both views, expired-table truncation, and sequence
  resets execute in one database transaction. Caches clear and completion events publish
  only after the transaction commits. Upload or switch failure publishes no success event.
- Feed generation processes bounded batches and waits for every SBOM callback,
  including dependency persistence, before setting `PROCESSED` and sending email.
  SBOM, persistence, repository lookup, or unavailable-host errors set `FAILED`.
  Notification failure is logged while preserving a successfully generated feed.
- `FAILED` requests are retained for inspection; no automatic retry or failure email
  is implemented. GitHub, cdxgen, and SMTP credentials are needed to verify live flows.
- Regression tests cover completion ordering, SBOM/persistence failures, SMTP failures,
  unsupported GitLab events, and view-switch commit/rollback ordering. Full database
  tests require Docker. Unit tests can run with `mvn -Dgroups=Unit verify`.
- Compared current upstream versions of the feed generator and issue loader before
  this contribution; both still contained the ordering issues. Upstream changes also
  reorganize DTO packages. Bulk upstream synchronization is intentionally separate
  from this focused fork contribution.

The upstream roadmap lists Android notifications, label discovery through AI,
OpenSSF work, and personalization research. Those are upstream roadmap items,
not features completed by this fork.

## Contribution delivery

Before changing runtime behavior, compare with current upstream to avoid
duplicating fixes. Keep upstream synchronization separate from a focused
contribution, open a fork-local issue and PR, and run the full verification
command on the resulting commit. Read the upstream contribution policy before
submitting anything to its maintainers.

Do not publish using the inherited Jib Docker Hub destination; it points to the
upstream `regyl/yagfi-back` image. Configure a destination owned by the fork
maintainer before image publication. Maven `verify` does not invoke the
commented-out Jib publishing execution.
