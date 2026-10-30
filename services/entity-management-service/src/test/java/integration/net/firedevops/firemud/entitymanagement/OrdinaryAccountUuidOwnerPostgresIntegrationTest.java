package net.firedevops.firemud.entitymanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.entitymanagement.entity.ActorIdentityStatus;
import net.firedevops.firemud.entitymanagement.entity.Character;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class OrdinaryAccountUuidOwnerPostgresIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void upgradePreservesNumericSourcesAndQueriesOnlyExactOrdinaryUuidWithoutPromotion() {
    var source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    FluentConfiguration configuration =
        Flyway.configure()
            .dataSource(source)
            .locations("classpath:db/migration", "classpath:db/migration/saga")
            .placeholders(Map.of("serviceSchema", "public"));
    configuration
        .getConfigurationExtension(PostgreSQLConfigurationExtension.class)
        .setTransactionalLock(false);
    configuration.target(MigrationVersion.fromVersion("1023")).load().migrate();
    var jdbc = new JdbcTemplate(source);
    jdbc.update(
        "INSERT INTO characters (tenant_id, account_id, name) VALUES (1, 98765, 'retained')");
    List<Map<String, Object>> retained = jdbc.queryForList("SELECT * FROM characters");
    List<Map<String, Object>> history =
        jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
    configuration.target(MigrationVersion.fromVersion("1024")).load().migrate();
    assertThat(jdbc.queryForList("SELECT * FROM characters")).isEqualTo(retained);
    var after = jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
    assertThat(after).hasSize(history.size() + 1);
    assertThat(after.subList(0, history.size())).isEqualTo(history);
    assertThat(after.getLast().get("version")).isEqualTo("1024");
    var repository = new CharacterRepository(DSL.using(source, SQLDialect.POSTGRES));
    UUID owner = UUID.randomUUID();
    Character ordinary = new Character();
    ordinary.setTenantId(1L);
    ordinary.setAccountUuid(owner);
    ordinary.setPlayableStateKey("shared-live");
    ordinary.setName("owned");
    Character saved = repository.save(ordinary);
    assertThat(saved.getAccountId()).isNull();
    assertThat(saved.getAccountUuid()).isEqualTo(owner);
    assertThat(saved.getActorIdentity().accountUuid()).isEqualTo(owner);
    assertThat(saved.getActorIdentity().status()).isEqualTo(ActorIdentityStatus.QUARANTINED);
    assertThat(saved.getActorIdentity().tenantUuid()).isNull();
    assertThat(saved.getActorIdentity().playableStateNamespaceId()).isNull();
    assertThat(
            repository
                .findByTenantIdAndAccountIdAndPlayableStateKey(1L, owner, "shared-live", null)
                .getContent())
        .extracting(Character::getId)
        .containsExactly(saved.getId());
    assertThat(
            repository
                .findByTenantIdAndAccountIdAndPlayableStateKey(2L, owner, "shared-live", null)
                .getContent())
        .isEmpty();
    assertThat(
            repository
                .findByTenantIdAndAccountIdAndPlayableStateKey(
                    1L, UUID.randomUUID(), "shared-live", null)
                .getContent())
        .isEmpty();
    assertThat(jdbc.queryForList("SELECT * FROM characters WHERE name = 'retained'"))
        .isEqualTo(retained);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE characters SET account_uuid = ? WHERE id = ?",
                    new UUID(0, 0),
                    saved.getId()))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }
}
