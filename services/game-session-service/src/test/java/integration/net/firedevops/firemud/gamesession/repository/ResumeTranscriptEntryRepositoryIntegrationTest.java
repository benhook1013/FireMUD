package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.common.config.FiremudReconnectionProperties;
import net.firedevops.firemud.common.config.ReconnectionSettingsResolver;
import net.firedevops.firemud.gamesession.entity.ResumeTranscriptEntry;
import net.firedevops.firemud.gamesession.service.DurableScreenBufferService;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResumeTranscriptEntryRepositoryIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  private DSLContext dsl;
  private ResumeTranscriptEntryRepository repository;

  @BeforeAll
  void setUpRepository() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(postgres.getDriverClassName());
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());

    Flyway.configure()
        .dataSource(dataSource)
        .locations(
            "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize())
        .load()
        .migrate();

    dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    repository = new ResumeTranscriptEntryRepository(dsl);
  }

  @BeforeEach
  void cleanTable() {
    dsl.execute("TRUNCATE TABLE resume_transcript_entry RESTART IDENTITY");
  }

  @Test
  void persistsStructuredEntriesInTranscriptOrderAndScopesDeletes() {
    ResumeTranscriptEntry earlier = entry("Earlier\n", Instant.parse("2026-07-12T01:00:00Z"));
    ResumeTranscriptEntry later = entry("Later\n", Instant.parse("2026-07-12T01:01:00Z"));
    ResumeTranscriptEntry otherCharacter = entry("Other\n", Instant.parse("2026-07-12T01:02:00Z"));
    otherCharacter.setCharacterId(14L);

    repository.saveAll(List.of(later, earlier, otherCharacter));
    List<ResumeTranscriptEntry> entries = repository.findByScope(22L, 7L, 13L);

    assertThat(entries)
        .extracting(ResumeTranscriptEntry::getProtocolText)
        .containsExactly("Earlier\n", "Later\n");
    assertThat(entries.getFirst().getPayloadJson()).isEqualTo("{\"room\":\"R-1\"}");

    repository.deleteExpired(22L, 7L, 13L, Instant.parse("2026-07-12T01:00:30Z"));

    assertThat(repository.findByScope(22L, 7L, 13L))
        .extracting(ResumeTranscriptEntry::getProtocolText)
        .containsExactly("Later\n");
    assertThat(repository.findByScope(22L, 7L, 14L))
        .extracting(ResumeTranscriptEntry::getProtocolText)
        .containsExactly("Other\n");
  }

  @Test
  void rehydratesATranscriptAfterTheDurableServiceIsRecreated() {
    ReconnectionSettingsResolver settingsResolver =
        (tenantId, gameInstanceId) ->
            new FiremudReconnectionProperties(
                new FiremudReconnectionProperties.Policy(180_000L, true),
                new FiremudReconnectionProperties.Buffer(60_000L, 1, 1, 100, 200));
    DurableScreenBufferService initial =
        new DurableScreenBufferService(repository, settingsResolver, emptyHotCache());
    initial.append(
        22L,
        7L,
        13L,
        List.of(ScreenBufferService.BufferedEntry.fromText("Durable reconnect line\n")));

    DurableScreenBufferService recreated =
        new DurableScreenBufferService(repository, settingsResolver, emptyHotCache());

    assertThat(recreated.get(22L, 7L, 13L))
        .map(ScreenBufferService.BufferedScreen::protocolText)
        .contains("Durable reconnect line\n");
  }

  @Test
  void deletesExpiredEntriesGloballyAtTheirExactExpiryWithoutRemovingTtlZeroRows() {
    Instant cutoff = Instant.parse("2026-07-12T01:02:00Z");
    ResumeTranscriptEntry expiredInPrimaryScope = entry("Primary\n", cutoff.minusSeconds(20));
    expiredInPrimaryScope.setExpiresAt(cutoff);
    ResumeTranscriptEntry expiredInOtherScope = entry("Other scope\n", cutoff.minusSeconds(30));
    expiredInOtherScope.setTenantId(23L);
    expiredInOtherScope.setGameInstanceId(8L);
    expiredInOtherScope.setCharacterId(14L);
    expiredInOtherScope.setExpiresAt(cutoff.minusSeconds(1));
    ResumeTranscriptEntry ttlZero = entry("Retained\n", cutoff.minusSeconds(40));
    ttlZero.setCharacterId(15L);
    ttlZero.setExpiresAt(null);
    repository.saveAll(List.of(expiredInPrimaryScope, expiredInOtherScope, ttlZero));

    int deleted = repository.deleteExpiredBefore(cutoff, 1);

    assertThat(deleted).isEqualTo(1);
    assertThat(repository.findByScope(23L, 8L, 14L)).isEmpty();
    assertThat(repository.findByScope(22L, 7L, 13L))
        .extracting(ResumeTranscriptEntry::getProtocolText)
        .containsExactly("Primary\n");

    assertThat(repository.deleteExpiredBefore(cutoff, 10)).isEqualTo(1);
    assertThat(repository.findByScope(22L, 7L, 13L)).isEmpty();
    assertThat(repository.findByScope(22L, 7L, 15L))
        .extracting(ResumeTranscriptEntry::getProtocolText)
        .containsExactly("Retained\n");
  }

  @Test
  void findsOnlyActiveEntriesWithoutDeletingExpiredRowsOnTheReadPath() {
    Instant cutoff = Instant.parse("2026-07-12T01:02:00Z");
    ResumeTranscriptEntry expired = entry("Expired\n", cutoff.minusSeconds(20));
    expired.setExpiresAt(cutoff);
    ResumeTranscriptEntry active = entry("Active\n", cutoff.minusSeconds(10));
    active.setExpiresAt(cutoff.plusSeconds(1));
    ResumeTranscriptEntry noExpiry = entry("Retained\n", cutoff.minusSeconds(5));
    noExpiry.setExpiresAt(null);
    repository.saveAll(List.of(expired, active, noExpiry));

    assertThat(repository.findActiveByScope(22L, 7L, 13L, cutoff))
        .extracting(ResumeTranscriptEntry::getProtocolText)
        .containsExactly("Active\n", "Retained\n");
    assertThat(repository.findByScope(22L, 7L, 13L))
        .extracting(ResumeTranscriptEntry::getProtocolText)
        .containsExactly("Expired\n", "Active\n", "Retained\n");
  }

  @Test
  void refreshesExpiryForOnlyTheRequestedTranscriptScope() {
    ResumeTranscriptEntry first = entry("Earlier\n", Instant.parse("2026-07-12T01:00:00Z"));
    ResumeTranscriptEntry second = entry("Later\n", Instant.parse("2026-07-12T01:01:00Z"));
    ResumeTranscriptEntry otherScope = entry("Other\n", Instant.parse("2026-07-12T01:02:00Z"));
    otherScope.setCharacterId(14L);
    repository.saveAll(List.of(first, second, otherScope));
    Instant refreshedExpiry = Instant.parse("2026-07-12T02:00:00Z");

    assertThat(repository.updateExpiryByScope(22L, 7L, 13L, refreshedExpiry)).isEqualTo(2);

    assertThat(repository.findByScope(22L, 7L, 13L))
        .extracting(ResumeTranscriptEntry::getExpiresAt)
        .containsExactly(refreshedExpiry, refreshedExpiry);
    assertThat(repository.findByScope(22L, 7L, 14L))
        .extracting(ResumeTranscriptEntry::getExpiresAt)
        .containsExactly(Instant.parse("2026-07-12T01:02:20Z"));
  }

  @Test
  void dropsExpiredTranscriptAfterTheDurableServiceIsRecreated() {
    ReconnectionSettingsResolver settingsResolver =
        (tenantId, gameInstanceId) ->
            new FiremudReconnectionProperties(
                new FiremudReconnectionProperties.Policy(180_000L, true),
                new FiremudReconnectionProperties.Buffer(1L, 1, 1, 100, 200));
    DurableScreenBufferService initial =
        new DurableScreenBufferService(repository, settingsResolver, emptyHotCache());
    initial.append(
        22L,
        7L,
        13L,
        List.of(
            new ScreenBufferService.BufferedEntry(
                "Expired reconnect line\n",
                1,
                23,
                Instant.now().minusSeconds(1).toEpochMilli(),
                null,
                null,
                null,
                null,
                null)));

    DurableScreenBufferService recreated =
        new DurableScreenBufferService(repository, settingsResolver, emptyHotCache());

    assertThat(recreated.get(22L, 7L, 13L)).isEmpty();
  }

  private ResumeTranscriptEntry entry(String text, Instant appendedAt) {
    ResumeTranscriptEntry entry = new ResumeTranscriptEntry();
    entry.setTenantId(22L);
    entry.setGameInstanceId(7L);
    entry.setCharacterId(13L);
    entry.setProtocolText(text);
    entry.setLineCount(1);
    entry.setByteSize(text.length());
    entry.setAppendedAt(appendedAt);
    entry.setExpiresAt(appendedAt.plusSeconds(20));
    entry.setOutputKind("VIEW");
    entry.setReplayPolicy("REPLAY");
    entry.setBriefRenderPolicy("FULL");
    entry.setPayloadType("look-view");
    entry.setPayloadJson("{\"room\":\"R-1\"}");
    return entry;
  }

  private ScreenBufferService emptyHotCache() {
    return new ScreenBufferService() {
      @Override
      public void append(
          long tenantId, long gameInstanceId, long characterId, List<BufferedEntry> entries) {}

      @Override
      public Optional<BufferedScreen> get(long tenantId, long gameInstanceId, long characterId) {
        return Optional.empty();
      }

      @Override
      public void clear(long tenantId, long gameInstanceId, long characterId) {}
    };
  }
}
