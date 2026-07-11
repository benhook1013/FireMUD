package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import net.firedevops.firemud.gamesession.entity.PlayerCommandHistoryEntry;
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
class PlayerCommandHistoryRepositoryIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  private DSLContext dsl;
  private PlayerCommandHistoryRepository repository;

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
    repository = new PlayerCommandHistoryRepository(dsl);
  }

  @BeforeEach
  void cleanTable() {
    dsl.execute("TRUNCATE TABLE player_command_history RESTART IDENTITY");
  }

  @Test
  void storesAndReadsPerCharacterEntriesInDeterministicTimestampAndIdOrder() {
    Instant sameMoment = Instant.parse("2026-07-12T01:00:00Z");
    PlayerCommandHistoryEntry first = historyEntry("first", sameMoment);
    PlayerCommandHistoryEntry second = historyEntry("second", sameMoment);
    PlayerCommandHistoryEntry secondCharacter =
        historyEntry("other-character", sameMoment.plusSeconds(1L));
    secondCharacter.setCharacterId(14L);

    repository.save(first);
    repository.save(second);
    repository.save(secondCharacter);

    List<PlayerCommandHistoryEntry> entries = repository.findByScope(22L, 7L, 13L);

    assertThat(entries)
        .extracting(PlayerCommandHistoryEntry::getCommandText)
        .containsExactly("first", "second");
    assertThat(entries)
        .extracting(PlayerCommandHistoryEntry::getId)
        .isSortedAccordingTo(Long::compareTo);
    assertThat(repository.findByScope(22L, 7L, 14L))
        .extracting(PlayerCommandHistoryEntry::getCommandText)
        .containsExactly("other-character");
  }

  @Test
  void scopeIsolationAcrossTenantAndGamePreservesOnlyRequestedHistory() {
    PlayerCommandHistoryEntry tenantOne =
        historyEntry("tenant-one", Instant.parse("2026-07-12T01:00:00Z"));
    tenantOne.setTenantId(22L);
    PlayerCommandHistoryEntry differentTenant =
        historyEntry("tenant-two", Instant.parse("2026-07-12T01:00:01Z"));
    differentTenant.setTenantId(23L);

    repository.save(tenantOne);
    repository.save(differentTenant);

    assertThat(repository.findByScope(22L, 7L, 13L))
        .extracting(PlayerCommandHistoryEntry::getCommandText)
        .containsExactly("tenant-one");
  }

  private PlayerCommandHistoryEntry historyEntry(String commandText, Instant acceptedAt) {
    PlayerCommandHistoryEntry entry = new PlayerCommandHistoryEntry();
    entry.setTenantId(22L);
    entry.setGameInstanceId(7L);
    entry.setCharacterId(13L);
    entry.setCommandText(commandText);
    entry.setAcceptedAt(acceptedAt);
    return entry;
  }
}
