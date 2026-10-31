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
    List<Map<String, Object>> retained =
        jdbc.queryForList("SELECT *, xmin::text AS retained_xmin FROM characters");
    List<Map<String, Object>> history =
        jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
    String parentCore =
        java.util.Objects.requireNonNull(
            jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'characters'::regclass AND conname = 'ck_character_created_core'",
                String.class));
    configuration.target(MigrationVersion.fromVersion("1024")).load().migrate();
    String currentCore =
        java.util.Objects.requireNonNull(
            jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'characters'::regclass AND conname = 'ck_character_created_core'",
                String.class));
    assertThat(parentCore).contains("'PLAYER_CREATED'");
    assertThat(currentCore).contains("'PLAYER_CREATED'");
    assertThat(currentCore.substring(currentCore.indexOf("'PLAYER_CREATED'")))
        .isEqualTo(parentCore.substring(parentCore.indexOf("'PLAYER_CREATED'")));

    assertThat(jdbc.queryForList("SELECT *, xmin::text AS retained_xmin FROM characters"))
        .isEqualTo(retained);
    var after = jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
    assertThat(after).hasSize(history.size() + 1);
    assertThat(after.subList(0, history.size())).isEqualTo(history);
    assertThat(after.getLast().get("version")).isEqualTo("1024");
    var repository = new CharacterRepository(DSL.using(source, SQLDialect.POSTGRES));
    UUID owner = UUID.randomUUID();
    UUID attemptedOwner = UUID.randomUUID();
    Character ordinary = new Character();
    ordinary.setTenantId(1L);
    ordinary.setAccountUuid(owner);
    ordinary.setPlayableStateKey("shared-live");
    ordinary.setName("owned");
    Character saved = repository.save(ordinary);
    Character loaded = repository.findById(saved.getId()).orElseThrow();
    assertThat(loaded.getAccountUuid()).isEqualTo(owner);
    assertThat(loaded.getAccountId()).isNull();
    assertThat(saved.getAccountId()).isNull();
    assertThat(saved.getAccountUuid()).isEqualTo(owner);
    assertThat(saved.getActorIdentity().accountUuid()).isEqualTo(owner);
    assertThat(saved.getActorIdentity().status()).isEqualTo(ActorIdentityStatus.QUARANTINED);
    assertThat(saved.getActorIdentity().tenantUuid()).isNull();
    assertThat(saved.getActorIdentity().playableStateNamespaceId()).isNull();
    assertThat(
            repository
                .findByTenantIdAndAccountUuidAndPlayableStateKey(1L, owner, "shared-live", null)
                .getContent())
        .extracting(Character::getId)
        .containsExactly(saved.getId());
    assertThat(
            repository
                .findByTenantIdAndAccountUuidAndPlayableStateKey(2L, owner, "shared-live", null)
                .getContent())
        .isEmpty();
    assertThat(
            repository
                .findByTenantIdAndAccountUuidAndPlayableStateKey(
                    1L, attemptedOwner, "shared-live", null)
                .getContent())
        .isEmpty();
    ordinary.setId(saved.getId());
    ordinary.setName("owned after null UUID update");
    ordinary.setAccountUuid(null);
    Character afterNullUuidUpdate = repository.save(ordinary);
    assertThat(afterNullUuidUpdate.getName()).isEqualTo("owned after null UUID update");
    assertThat(afterNullUuidUpdate.getAccountUuid()).isEqualTo(owner);
    assertThat(afterNullUuidUpdate.getAccountId()).isNull();
    assertThat(
            repository
                .findByTenantIdAndAccountUuidAndPlayableStateKey(1L, owner, "shared-live", null)
                .getContent())
        .extracting(Character::getId)
        .containsExactly(saved.getId());
    assertThat(
            repository
                .findByTenantIdAndAccountUuidAndPlayableStateKey(
                    1L, attemptedOwner, "shared-live", null)
                .getContent())
        .isEmpty();
    ordinary.setName("owned after different UUID update");
    ordinary.setAccountUuid(attemptedOwner);
    Character afterDifferentUuidUpdate = repository.save(ordinary);
    assertThat(afterDifferentUuidUpdate.getName()).isEqualTo("owned after different UUID update");
    assertThat(afterDifferentUuidUpdate.getAccountUuid()).isEqualTo(owner);
    assertThat(afterDifferentUuidUpdate.getAccountId()).isNull();
    Character reloaded = repository.findById(saved.getId()).orElseThrow();
    assertThat(reloaded.getAccountUuid()).isEqualTo(owner);
    assertThat(reloaded.getActorIdentity().accountUuid()).isEqualTo(owner);
    assertThat(reloaded.getActorIdentity().status()).isEqualTo(ActorIdentityStatus.QUARANTINED);
    assertThat(
            repository
                .findByTenantIdAndAccountUuidAndPlayableStateKey(1L, owner, "shared-live", null)
                .getContent())
        .extracting(Character::getId)
        .containsExactly(saved.getId());
    assertThat(
            repository
                .findByTenantIdAndAccountUuidAndPlayableStateKey(
                    1L, attemptedOwner, "shared-live", null)
                .getContent())
        .isEmpty();
    assertThat(
            jdbc.queryForList(
                "SELECT *, xmin::text AS retained_xmin FROM characters WHERE name = 'retained'"))
        .isEqualTo(retained);
    List<Map<String, Object>> ordinaryBeforeRefusals =
        jdbc.queryForList(
            "SELECT *, xmin::text AS retained_xmin FROM characters WHERE id = ?", saved.getId());
    for (String invalidChange :
        List.of(
            "account_uuid = NULL",
            "account_uuid = '00000000-0000-0000-0000-000000000000'::UUID",
            "tenant_uuid = '11111111-1111-4111-8111-111111111111'::UUID",
            "playable_state_namespace_id = '22222222-2222-4222-8222-222222222222'::UUID",
            "playable_state_scope = 'PLAYABLE_STATE_SCOPE_SHARED'",
            "actor_identity_status = 'OWNER_RESOLVED', actor_identity_quarantine_reason = NULL",
            "actor_identity_status = 'OWNER_RESOLVED', actor_identity_quarantine_reason = NULL, "
                + "tenant_uuid = '11111111-1111-4111-8111-111111111111'::UUID, "
                + "playable_state_namespace_id = '22222222-2222-4222-8222-222222222222'::UUID, "
                + "playable_state_scope = 'PLAYABLE_STATE_SCOPE_SHARED'",
            "actor_identity_quarantine_reason = NULL",
            "actor_entry_policy = 'PLAYER_CREATED'",
            "creation_target_namespace = 'dev'",
            "creation_operation_id = '33333333-3333-4333-8333-333333333333'::UUID",
            "authored_initial_core_json = '{}'",
            "creation_descriptor_json = '{}'",
            "creation_descriptor_digest = 'sha256:" + "a".repeat(64) + "'")) {
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "UPDATE characters SET " + invalidChange + " WHERE id = ?", saved.getId()))
          .as(invalidChange)
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
      assertThat(
              jdbc.queryForList(
                  "SELECT *, xmin::text AS retained_xmin FROM characters WHERE id = ?",
                  saved.getId()))
          .isEqualTo(ordinaryBeforeRefusals);
    }
    for (String requiredColumn :
        List.of(
            "playable_state_key",
            "level",
            "experience",
            "strength",
            "agility",
            "intelligence",
            "stamina",
            "health",
            "mana",
            "body_layout_key")) {
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "UPDATE characters SET " + requiredColumn + " = NULL WHERE id = ?",
                      saved.getId()))
          .as(requiredColumn)
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    assertThat(
            jdbc.queryForList(
                "SELECT *, xmin::text AS retained_xmin FROM characters WHERE id = ?",
                saved.getId()))
        .isEqualTo(ordinaryBeforeRefusals);
    assertThat(
            jdbc.queryForList(
                "SELECT *, xmin::text AS retained_xmin FROM characters WHERE name = 'retained'"))
        .isEqualTo(retained);
  }
}
