package net.firedevops.firemud.gamesession.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.gamesession.entity.GameplayAdmissionPointer;
import net.firedevops.firemud.gamesession.entity.GameplayAdmissionPointerEvent;
import net.firedevops.firemud.gamesession.service.AdmissionPointerVersionMismatchException;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

class GameplayAdmissionPointerRepositoryTest {
  @Test
  void tenantScopedListFiltersRowsInTheDatabaseBeforeMapping() throws Exception {
    try (Connection connection =
        DriverManager.getConnection(
            "jdbc:h2:mem:gameplay-pointer-tenant-list;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")) {
      DSLContext dsl = DSL.using(connection, SQLDialect.H2);
      createSchema(dsl);
      GameplayAdmissionPointerRepository repository = new GameplayAdmissionPointerRepository(dsl);
      GameplayAdmissionPointer target = repository.save(pointer(7L, 44L, "SHARED", "production"));
      GameplayAdmissionPointer unrelated =
          repository.save(pointer(8L, 55L, "SHARED", "production"));
      dsl.execute(
          "UPDATE gameplay_admission_pointer SET realm_id = NULL, "
              + "playable_state_namespace_id = NULL WHERE id = ?",
          unrelated.getId());

      var scopedRows = repository.findAllByTenantIdOrderByWorldSlugAscRealmSlugAsc(7L);

      assertEquals(1, scopedRows.size());
      assertEquals(target.getId(), scopedRows.getFirst().getId());
      assertEquals(7L, scopedRows.getFirst().getTenantId());
      assertNotNull(scopedRows.getFirst().getRealmId());
    }
  }

  @Test
  void stableRealmAndNamespaceIdentitySurviveRuntimeReplacement() throws Exception {
    try (Connection connection =
        DriverManager.getConnection(
            "jdbc:h2:mem:gameplay-pointer-identity;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")) {
      DSLContext dsl = DSL.using(connection, SQLDialect.H2);
      createSchema(dsl);
      GameplayAdmissionPointerRepository repository = new GameplayAdmissionPointerRepository(dsl);

      GameplayAdmissionPointer shared = pointer(7L, 44L, "SHARED", "production");
      GameplayAdmissionPointer createdShared = repository.save(shared);
      UUID sharedRealmId = createdShared.getRealmId();
      UUID sharedNamespaceId = createdShared.getPlayableStateNamespaceId();
      GameplayAdmissionPointer sharedReadback =
          repository.findByTenantIdAndWorldSlugAndRealmSlug(7L, "demo", "production").orElseThrow();

      GameplayAdmissionPointer secondShared =
          repository.save(pointer(7L, 45L, "SHARED", "seasonal"));
      GameplayAdmissionPointer isolated = repository.save(pointer(7L, 46L, "ISOLATED", "playtest"));

      assertNotNull(sharedRealmId);
      assertNotNull(sharedNamespaceId);
      assertEquals(sharedRealmId, sharedReadback.getRealmId());
      assertEquals(sharedNamespaceId, sharedReadback.getPlayableStateNamespaceId());
      assertNotNull(secondShared.getRealmId());
      assertNotNull(isolated.getRealmId());
      assertEquals(sharedNamespaceId, secondShared.getPlayableStateNamespaceId());
      assertNotEquals(sharedRealmId, secondShared.getRealmId());
      assertNotEquals(sharedNamespaceId, isolated.getPlayableStateNamespaceId());

      createdShared.setGameInstanceId(99L);
      createdShared.setPointerVersion(2L);
      createdShared.setCatalogRevision(1L);
      GameplayAdmissionPointer replaced = repository.updateExisting(createdShared, 1L, 1L);

      assertEquals(99L, replaced.getGameInstanceId());
      assertEquals(sharedRealmId, replaced.getRealmId());
      assertEquals(sharedNamespaceId, replaced.getPlayableStateNamespaceId());
    }
  }

  @Test
  void tenantScopedPointerListExcludesOtherTenants() throws Exception {
    try (Connection connection =
        DriverManager.getConnection(
            "jdbc:h2:mem:gameplay-pointer-tenant-list;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")) {
      DSLContext dsl = DSL.using(connection, SQLDialect.H2);
      createSchema(dsl);
      GameplayAdmissionPointerRepository repository = new GameplayAdmissionPointerRepository(dsl);
      repository.save(pointer(7L, 44L, "SHARED", "production"));
      repository.save(pointer(8L, 55L, "SHARED", "production"));

      var scoped = repository.findAllByTenantIdInOrderByWorldSlugAscRealmSlugAsc(List.of(8L));

      assertEquals(1, scoped.size());
      assertEquals(8L, scoped.getFirst().getTenantId());
      assertEquals(2, repository.findAllByOrderByWorldSlugAscRealmSlugAsc().size());
      assertEquals(
          List.of(), repository.findAllByTenantIdInOrderByWorldSlugAscRealmSlugAsc(List.of()));
    }
  }

  @Test
  void repositoryRejectsAttemptToReplaceStableRealmIdentity() throws Exception {
    try (Connection connection =
        DriverManager.getConnection(
            "jdbc:h2:mem:gameplay-pointer-identity-immutable;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")) {
      DSLContext dsl = DSL.using(connection, SQLDialect.H2);
      createSchema(dsl);
      GameplayAdmissionPointerRepository repository = new GameplayAdmissionPointerRepository(dsl);
      GameplayAdmissionPointer created = repository.save(pointer(7L, 44L, "ISOLATED", "fork"));

      created.setRealmId(UUID.randomUUID());

      IllegalStateException exception =
          assertThrows(
              IllegalStateException.class, () -> repository.updateExisting(created, 1L, 1L));
      assertEquals(
          "Admission pointer update cannot replace durable realm or playable-state identity",
          exception.getMessage());
    }
  }

  @Test
  void catalogOnlyUpdatePreservesPointerVersionAndFencesStaleCatalogRevision() throws Exception {
    try (Connection connection =
        DriverManager.getConnection(
            "jdbc:h2:mem:gameplay-pointer-catalog-cas;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")) {
      DSLContext dsl = DSL.using(connection, SQLDialect.H2);
      createSchema(dsl);
      GameplayAdmissionPointerRepository repository = new GameplayAdmissionPointerRepository(dsl);
      repository.save(pointer(7L, 44L, "SHARED", "production"));

      GameplayAdmissionPointer stale =
          repository.findByTenantIdAndWorldSlugAndRealmSlug(7L, "demo", "production").orElseThrow();
      GameplayAdmissionPointer catalogUpdate =
          repository.findByTenantIdAndWorldSlugAndRealmSlug(7L, "demo", "production").orElseThrow();
      catalogUpdate.setWorldDisplayName("Renamed Demo World");
      catalogUpdate.setPointerVersion(1L);
      catalogUpdate.setCatalogRevision(2L);

      GameplayAdmissionPointer updated = repository.updateExisting(catalogUpdate, 1L, 1L);

      assertEquals(1L, updated.getPointerVersion());
      assertEquals(2L, updated.getCatalogRevision());

      stale.setRealmDisplayName("Stale Realm Display");
      stale.setPointerVersion(1L);
      stale.setCatalogRevision(2L);
      assertThrows(
          AdmissionPointerVersionMismatchException.class,
          () -> repository.updateExisting(stale, 1L, 1L));
    }
  }

  @Test
  void rejectsSecondConcurrentRequestWhenWinnerCommittedTheSameTransition() throws Exception {
    try (Connection connection =
        DriverManager.getConnection(
            "jdbc:h2:mem:gameplay-pointer-convergent-update;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")) {
      DSLContext dsl = DSL.using(connection, SQLDialect.H2);
      createSchema(dsl);
      GameplayAdmissionPointerRepository repository = new GameplayAdmissionPointerRepository(dsl);
      repository.save(pointer(7L, 44L, "SHARED", "production"));

      GameplayAdmissionPointer winner =
          repository.findByTenantIdAndWorldSlugAndRealmSlug(7L, "demo", "production").orElseThrow();
      GameplayAdmissionPointer staleConcurrentRequest =
          repository.findByTenantIdAndWorldSlugAndRealmSlug(7L, "demo", "production").orElseThrow();
      for (GameplayAdmissionPointer request : List.of(winner, staleConcurrentRequest)) {
        request.setWorldDisplayName("Renamed Demo World");
        request.setGameInstanceId(99L);
        request.setPointerVersion(2L);
        request.setCatalogRevision(2L);
      }

      GameplayAdmissionPointer committed = repository.updateExisting(winner, 1L, 1L);

      assertEquals(2L, committed.getPointerVersion());
      assertEquals(2L, committed.getCatalogRevision());
      assertThrows(
          AdmissionPointerVersionMismatchException.class,
          () -> repository.updateExisting(staleConcurrentRequest, 1L, 1L));
      GameplayAdmissionPointer persisted =
          repository.findByTenantIdAndWorldSlugAndRealmSlug(7L, "demo", "production").orElseThrow();
      assertEquals(99L, persisted.getGameInstanceId());
      assertEquals(2L, persisted.getPointerVersion());
      assertEquals(2L, persisted.getCatalogRevision());
      assertEquals("Renamed Demo World", persisted.getWorldDisplayName());
    }
  }

  @Test
  void pointerAuditOrdersByEventIdWhenPodTimestampMovesBackward() throws Exception {
    try (Connection connection =
        DriverManager.getConnection(
            "jdbc:h2:mem:gameplay-pointer-audit-order;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")) {
      DSLContext dsl = DSL.using(connection, SQLDialect.H2);
      createAuditSchema(dsl);
      GameplayAdmissionPointerEventRepository repository =
          new GameplayAdmissionPointerEventRepository(dsl);

      GameplayAdmissionPointerEvent earlierWrite = pointerEvent("2026-09-29T10:00:00Z");
      GameplayAdmissionPointerEvent firstSaved = repository.save(earlierWrite);
      GameplayAdmissionPointerEvent laterWriteWithSkewedClock =
          pointerEvent("2026-09-29T09:00:00Z");
      GameplayAdmissionPointerEvent secondSaved = repository.save(laterWriteWithSkewedClock);
      GameplayAdmissionPointerEvent otherTenantWrite = pointerEvent("2026-09-29T11:00:00Z");
      otherTenantWrite.setTenantId(8L);
      repository.save(otherTenantWrite);

      var audit =
          repository.findByTenantIdAndWorldSlugAndRealmSlugOrderByIdDesc(7L, "demo", "production");
      var latest =
          repository
              .findLatestByTenantIdAndWorldSlugAndRealmSlug(7L, "demo", "production")
              .orElseThrow();

      assertEquals(2, audit.size());
      assertEquals(secondSaved.getId(), audit.getFirst().getId());
      assertEquals(firstSaved.getId(), audit.get(1).getId());
      assertEquals(Instant.parse("2026-09-29T09:00:00Z"), audit.getFirst().getOccurredAt());
      assertEquals(secondSaved.getId(), latest.getId());
      assertEquals(Instant.parse("2026-09-29T09:00:00Z"), latest.getOccurredAt());
    }
  }

  @Test
  void h2FixtureEnforcesProductionCatalogAndRealmIdentityConstraints() throws Exception {
    try (Connection connection =
        DriverManager.getConnection(
            "jdbc:h2:mem:gameplay-pointer-production-constraints;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")) {
      DSLContext dsl = DSL.using(connection, SQLDialect.H2);
      createSchema(dsl);
      GameplayAdmissionPointerRepository repository = new GameplayAdmissionPointerRepository(dsl);
      GameplayAdmissionPointer production =
          repository.save(pointer(7L, 44L, "SHARED", "production"));
      GameplayAdmissionPointer seasonal = repository.save(pointer(7L, 45L, "ISOLATED", "seasonal"));

      assertThrows(
          DataAccessException.class,
          () ->
              dsl.execute(
                  "UPDATE gameplay_admission_pointer SET catalog_revision = 0 WHERE id = ?",
                  production.getId()));
      assertThrows(
          DataAccessException.class,
          () ->
              dsl.execute(
                  "UPDATE gameplay_admission_pointer SET playable_state_namespace_id = NULL "
                      + "WHERE id = ?",
                  production.getId()));
      assertThrows(
          DataAccessException.class,
          () ->
              dsl.execute(
                  "UPDATE gameplay_admission_pointer SET realm_id = ? WHERE id = ?",
                  production.getRealmId(),
                  seasonal.getId()));
    }
  }

  private static GameplayAdmissionPointer pointer(
      long tenantId, long gameInstanceId, String stateScope, String realmSlug) {
    GameplayAdmissionPointer pointer = new GameplayAdmissionPointer();
    pointer.setWorldSlug("demo");
    pointer.setWorldDisplayName("Demo World");
    pointer.setRealmSlug(realmSlug);
    pointer.setRealmDisplayName(realmSlug);
    pointer.setTenantId(tenantId);
    pointer.setGameInstanceId(gameInstanceId);
    pointer.setPointerVersion(1L);
    pointer.setCatalogRevision(1L);
    pointer.setVisible(true);
    pointer.setPublicProductionRealm("production".equals(realmSlug));
    pointer.setRequiresCharacterSelection(false);
    pointer.setStateScope(stateScope);
    pointer.setCharacterCreationPolicy("ALLOW_NEW");
    pointer.setLastUpdatedBy("test");
    pointer.setLastUpdateReason("test");
    pointer.setCreatedAt(Instant.parse("2026-09-24T00:00:00Z"));
    pointer.setUpdatedAt(Instant.parse("2026-09-24T00:00:00Z"));
    return pointer;
  }

  private static void createSchema(DSLContext dsl) {
    dsl.execute(
        """
        CREATE TABLE gameplay_tenant_shared_playable_state_namespace (
          tenant_id BIGINT PRIMARY KEY,
          playable_state_namespace_id UUID NOT NULL UNIQUE,
          allocated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        )
        """);
    dsl.execute(
        """
        CREATE TABLE gameplay_admission_pointer (
          id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
          world_slug VARCHAR(120) NOT NULL,
          world_display_name VARCHAR(200) NOT NULL,
          realm_slug VARCHAR(120) NOT NULL,
          realm_display_name VARCHAR(200) NOT NULL,
          tenant_id BIGINT NOT NULL,
          game_instance_id BIGINT NOT NULL,
          pointer_version BIGINT NOT NULL,
          catalog_revision BIGINT NOT NULL,
          realm_id UUID,
          playable_state_namespace_id UUID,
          visible BOOLEAN NOT NULL,
          public_production_realm BOOLEAN NOT NULL,
          requires_character_selection BOOLEAN NOT NULL,
          state_scope VARCHAR(32) NOT NULL,
          character_creation_policy VARCHAR(32) NOT NULL,
          last_updated_by VARCHAR(200) NOT NULL,
          last_update_reason VARCHAR(500) NOT NULL,
          created_at TIMESTAMP NOT NULL,
          updated_at TIMESTAMP NOT NULL,
          CONSTRAINT uq_pointer_tenant_world_realm UNIQUE (tenant_id, world_slug, realm_slug),
          CONSTRAINT uq_pointer_tenant_runtime UNIQUE (tenant_id, game_instance_id),
          CONSTRAINT gameplay_admission_pointer_catalog_revision_positive
            CHECK (catalog_revision > 0),
          CONSTRAINT gameplay_admission_pointer_identity_pair_complete
            CHECK ((realm_id IS NULL) = (playable_state_namespace_id IS NULL))
        )
        """);
    // H2 cannot express this partial-index predicate; its nullable unique index exercises
    // populated realm IDs, while the migration integration test proves the PostgreSQL index.
    dsl.execute(
        "CREATE UNIQUE INDEX uq_gameplay_admission_pointer_realm_id "
            + "ON gameplay_admission_pointer (realm_id)");
  }

  private static GameplayAdmissionPointerEvent pointerEvent(String occurredAt) {
    GameplayAdmissionPointerEvent event = new GameplayAdmissionPointerEvent();
    event.setWorldSlug("demo");
    event.setRealmSlug("production");
    event.setWorldDisplayName("Demo World");
    event.setRealmDisplayName("Production");
    event.setTenantId(7L);
    event.setGameInstanceId(44L);
    event.setPointerVersion(1L);
    event.setCatalogRevision(1L);
    event.setVisible(true);
    event.setPublicProductionRealm(true);
    event.setRequiresCharacterSelection(false);
    event.setStateScope("SHARED");
    event.setCharacterCreationPolicy("ALLOW_NEW");
    event.setActorPrincipal("tester");
    event.setReason("test");
    event.setControlPlaneRequestId(UUID.randomUUID().toString());
    event.setOccurredAt(Instant.parse(occurredAt));
    return event;
  }

  private static void createAuditSchema(DSLContext dsl) {
    dsl.execute(
        """
        CREATE TABLE gameplay_admission_pointer_event (
          id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
          world_slug VARCHAR(120) NOT NULL,
          realm_slug VARCHAR(120) NOT NULL,
          world_display_name VARCHAR(200) NOT NULL,
          realm_display_name VARCHAR(200) NOT NULL,
          tenant_id BIGINT NOT NULL,
          game_instance_id BIGINT NOT NULL,
          pointer_version BIGINT NOT NULL,
          catalog_revision BIGINT,
          realm_id UUID,
          playable_state_namespace_id UUID,
          visible BOOLEAN NOT NULL,
          public_production_realm BOOLEAN NOT NULL,
          requires_character_selection BOOLEAN NOT NULL,
          state_scope VARCHAR(32) NOT NULL,
          character_creation_policy VARCHAR(32) NOT NULL,
          actor_principal VARCHAR(200) NOT NULL,
          reason VARCHAR(500) NOT NULL,
          control_plane_request_id VARCHAR(120) NOT NULL,
          prepared_version_upgrade_id VARCHAR(64),
          occurred_at TIMESTAMP NOT NULL
        )
        """);
  }
}
