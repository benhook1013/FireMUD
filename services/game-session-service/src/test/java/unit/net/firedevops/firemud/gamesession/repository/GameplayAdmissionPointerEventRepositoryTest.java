package net.firedevops.firemud.gamesession.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.gamesession.entity.GameplayAdmissionPointerEvent;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService.PointerAuditKey;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

class GameplayAdmissionPointerEventRepositoryTest {
  @Test
  void savesCatalogRevisionAndStableIdentityAndKeepsUnknownValuesNull() throws Exception {
    try (Connection connection =
        DriverManager.getConnection(
            "jdbc:h2:mem:gameplay-pointer-event-audit;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")) {
      DSLContext dsl = DSL.using(connection, SQLDialect.H2);
      createSchema(dsl);
      GameplayAdmissionPointerEventRepository repository =
          new GameplayAdmissionPointerEventRepository(dsl);
      UUID realmId = UUID.fromString("c214a525-a48e-4734-8927-73eb17c44511");
      UUID namespaceId = UUID.fromString("3d89b194-e8f5-4ea2-9d80-04b8f71f28b6");

      GameplayAdmissionPointerEvent current = event("2026-09-01T00:00:00Z");
      current.setCatalogRevision(12L);
      current.setRealmId(realmId);
      current.setPlayableStateNamespaceId(namespaceId);
      repository.save(current);

      GameplayAdmissionPointerEvent historical = event("2026-08-31T00:00:00Z");
      repository.save(historical);

      List<GameplayAdmissionPointerEvent> events =
          repository.findByTenantIdAndWorldSlugAndRealmSlugOrderByIdDesc(9L, "demo", "production");

      assertEquals(2, events.size());
      assertNull(events.get(0).getCatalogRevision());
      assertNull(events.get(0).getRealmId());
      assertNull(events.get(0).getPlayableStateNamespaceId());
      assertEquals(12L, events.get(1).getCatalogRevision());
      assertEquals(realmId, events.get(1).getRealmId());
      assertEquals(namespaceId, events.get(1).getPlayableStateNamespaceId());
    }
  }

  @Test
  void findsLatestAuditByIdForExactMixedTenantPointerKeys() throws Exception {
    try (Connection connection =
        DriverManager.getConnection(
            "jdbc:h2:mem:gameplay-pointer-event-latest-batch;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")) {
      DSLContext dsl = DSL.using(connection, SQLDialect.H2);
      createSchema(dsl);
      GameplayAdmissionPointerEventRepository repository =
          new GameplayAdmissionPointerEventRepository(dsl);

      GameplayAdmissionPointerEvent newestTenantNine = event("2026-09-10T00:00:00Z");
      repository.save(newestTenantNine);
      GameplayAdmissionPointerEvent tenantTen = event("2026-09-08T00:00:00Z");
      tenantTen.setTenantId(10L);
      repository.save(tenantTen);
      GameplayAdmissionPointerEvent otherRealm = event("2026-09-07T00:00:00Z");
      otherRealm.setRealmSlug("playtest");
      repository.save(otherRealm);
      GameplayAdmissionPointerEvent latestById = event("2026-09-01T00:00:00Z");
      repository.save(latestById);
      GameplayAdmissionPointerEvent outsideSelection = event("2026-09-11T00:00:00Z");
      outsideSelection.setTenantId(11L);
      repository.save(outsideSelection);

      List<GameplayAdmissionPointerEvent> latest =
          repository.findLatestByPointerKeys(
              List.of(
                  new PointerAuditKey(9L, "demo", "production"),
                  new PointerAuditKey(10L, "demo", "production"),
                  new PointerAuditKey(9L, "demo", "playtest")));
      var byKey =
          latest.stream()
              .collect(
                  java.util.stream.Collectors.toMap(
                      audit ->
                          new PointerAuditKey(
                              audit.getTenantId(), audit.getWorldSlug(), audit.getRealmSlug()),
                      GameplayAdmissionPointerEvent::getControlPlaneRequestId));

      assertEquals(3, latest.size());
      assertEquals(
          "request-2026-09-01T00:00:00Z", byKey.get(new PointerAuditKey(9L, "demo", "production")));
      assertEquals(
          "request-2026-09-08T00:00:00Z",
          byKey.get(new PointerAuditKey(10L, "demo", "production")));
      assertEquals(
          "request-2026-09-07T00:00:00Z", byKey.get(new PointerAuditKey(9L, "demo", "playtest")));
    }
  }

  @Test
  void chunksLargePointerKeySelectionsAndGloballyOrdersUniqueLatestEvents() throws Exception {
    try (Connection connection =
        DriverManager.getConnection(
            "jdbc:h2:mem:gameplay-pointer-event-latest-chunks;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")) {
      DSLContext dsl = DSL.using(connection, SQLDialect.H2);
      createSchema(dsl);
      GameplayAdmissionPointerEventRepository repository =
          new GameplayAdmissionPointerEventRepository(dsl);

      List<PointerAuditKey> keys = new ArrayList<>();
      for (int index = 0; index <= 500; index++) {
        keys.add(new PointerAuditKey(9L, "world-" + index, "realm"));
      }
      keys.add(keys.get(0));

      GameplayAdmissionPointerEvent secondChunkFirstId =
          repository.save(eventForKey(keys.get(500), "2026-09-10T00:00:00Z"));
      repository.save(eventForKey(keys.get(0), "2026-09-12T00:00:00Z"));
      GameplayAdmissionPointerEvent lastKeyInFirstChunk =
          repository.save(eventForKey(keys.get(499), "2026-09-11T00:00:00Z"));
      GameplayAdmissionPointerEvent latestFirstKey =
          repository.save(eventForKey(keys.get(0), "2026-09-01T00:00:00Z"));
      GameplayAdmissionPointerEvent unselectedTenant = event("2026-09-13T00:00:00Z");
      unselectedTenant.setTenantId(11L);
      unselectedTenant.setWorldSlug("world-0");
      unselectedTenant.setRealmSlug("realm");
      repository.save(unselectedTenant);

      List<GameplayAdmissionPointerEvent> latest = repository.findLatestByPointerKeys(keys);

      assertEquals(3, latest.size());
      assertEquals(
          List.of(secondChunkFirstId.getId(), lastKeyInFirstChunk.getId(), latestFirstKey.getId()),
          latest.stream().map(GameplayAdmissionPointerEvent::getId).toList());
      assertEquals(
          List.of("world-500", "world-499", "world-0"),
          latest.stream().map(GameplayAdmissionPointerEvent::getWorldSlug).toList());
    }
  }

  private static GameplayAdmissionPointerEvent eventForKey(PointerAuditKey key, String occurredAt) {
    GameplayAdmissionPointerEvent event = event(occurredAt);
    event.setTenantId(key.tenantId());
    event.setWorldSlug(key.worldSlug());
    event.setRealmSlug(key.realmSlug());
    return event;
  }

  private static GameplayAdmissionPointerEvent event(String occurredAt) {
    GameplayAdmissionPointerEvent event = new GameplayAdmissionPointerEvent();
    event.setWorldSlug("demo");
    event.setRealmSlug("production");
    event.setWorldDisplayName("Demo World");
    event.setRealmDisplayName("Production Realm");
    event.setTenantId(9L);
    event.setGameInstanceId(31L);
    event.setPointerVersion(4L);
    event.setVisible(true);
    event.setPublicProductionRealm(true);
    event.setRequiresCharacterSelection(false);
    event.setStateScope("SHARED");
    event.setCharacterCreationPolicy("ALLOW_NEW");
    event.setActorPrincipal("operator");
    event.setReason("catalog update");
    event.setControlPlaneRequestId("request-" + occurredAt);
    event.setOccurredAt(Instant.parse(occurredAt));
    return event;
  }

  private static void createSchema(DSLContext dsl) {
    dsl.execute(
        """
        CREATE TABLE gameplay_admission_pointer_event (
          id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
          representation_version INTEGER NOT NULL DEFAULT 1,
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
          occurred_at TIMESTAMP NOT NULL,
          CONSTRAINT gameplay_admission_pointer_event_catalog_revision_positive
            CHECK (catalog_revision IS NULL OR catalog_revision > 0),
          CONSTRAINT gameplay_admission_pointer_event_identity_pair_complete
            CHECK ((realm_id IS NULL) = (playable_state_namespace_id IS NULL))
        )
        """);
  }
}
