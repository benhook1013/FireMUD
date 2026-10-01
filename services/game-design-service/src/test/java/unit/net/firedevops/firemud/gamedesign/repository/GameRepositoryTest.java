package net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

class GameRepositoryTest {
  private static final Table<?> GAME = DSL.table(DSL.name("game"));
  private static final org.jooq.Field<Long> ID = DSL.field(DSL.name("id"), Long.class);
  private static final org.jooq.Field<String> TENANT_ID =
      DSL.field(DSL.name("tenant_id"), String.class);
  private static final org.jooq.Field<UUID> CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final org.jooq.Field<String> PROVENANCE_KIND =
      DSL.field(DSL.name("tenant_identity_provenance_kind"), String.class);
  private static final org.jooq.Field<Long> SOURCE_GAME_ID =
      DSL.field(DSL.name("tenant_identity_source_game_id"), Long.class);
  private static final org.jooq.Field<String> SOURCE_TENANT_ID =
      DSL.field(DSL.name("tenant_identity_source_legacy_tenant_id"), String.class);

  private static final UUID NEW_CANONICAL_TENANT_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");

  @Test
  void findsNewAndRetainedIdentitiesByCanonicalUuidUsingTheirActualSourceRows() throws Exception {
    try (Fixture fixture = fixture()) {
      insert(
          fixture.dsl,
          7L,
          "new-game-key-7",
          NEW_CANONICAL_TENANT_ID,
          "NEW_GAME_ROW",
          7L,
          "new-game-key-7");
      insert(
          fixture.dsl,
          8L,
          "retained-game-key-8",
          UUID.fromString("22222222-2222-4222-8222-222222222222"),
          "RETAINED_GAME_V30",
          8L,
          "retained-game-key-8");

      Optional<GameTenantIdentity> newIdentity =
          fixture.repository.findRuntimeTenantIdentityByCanonicalTenantId(NEW_CANONICAL_TENANT_ID);
      Optional<GameTenantIdentity> retainedIdentity =
          fixture.repository.findRuntimeTenantIdentityByCanonicalTenantId(
              UUID.fromString("22222222-2222-4222-8222-222222222222"));

      assertThat(newIdentity)
          .contains(
              new GameTenantIdentity(
                  NEW_CANONICAL_TENANT_ID,
                  GameTenantIdentity.ProvenanceKind.NEW_GAME_ROW,
                  7L,
                  "new-game-key-7"));
      assertThat(retainedIdentity)
          .contains(
              new GameTenantIdentity(
                  UUID.fromString("22222222-2222-4222-8222-222222222222"),
                  GameTenantIdentity.ProvenanceKind.RETAINED_GAME_V30,
                  8L,
                  "retained-game-key-8"));
    }
  }

  @Test
  void missingCanonicalUuidReturnsEmptyAndNullRequestIsRejected() throws Exception {
    try (Fixture fixture = fixture()) {
      assertThat(
              fixture.repository.findRuntimeTenantIdentityByCanonicalTenantId(
                  UUID.fromString("33333333-3333-4333-8333-333333333333")))
          .isEmpty();
      assertThatThrownBy(
              () -> fixture.repository.findRuntimeTenantIdentityByCanonicalTenantId(null))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void rejectsSourceRowAndKeyContradictions() throws Exception {
    try (Fixture rowFixture = fixture()) {
      insert(
          rowFixture.dsl,
          7L,
          "game-key-7",
          NEW_CANONICAL_TENANT_ID,
          "NEW_GAME_ROW",
          9L,
          "game-key-7");
      assertThatThrownBy(
              () ->
                  rowFixture.repository.findRuntimeTenantIdentityByCanonicalTenantId(
                      NEW_CANONICAL_TENANT_ID))
          .isInstanceOf(IllegalStateException.class);
    }

    try (Fixture keyFixture = fixture()) {
      insert(
          keyFixture.dsl,
          7L,
          "actual-game-key-7",
          NEW_CANONICAL_TENANT_ID,
          "RETAINED_GAME_V30",
          7L,
          "different-source-key");
      assertThatThrownBy(
              () ->
                  keyFixture.repository.findRuntimeTenantIdentityByCanonicalTenantId(
                      NEW_CANONICAL_TENANT_ID))
          .isInstanceOf(IllegalStateException.class);
    }

    try (Fixture kindFixture = fixture()) {
      insert(
          kindFixture.dsl,
          7L,
          "game-key-7",
          NEW_CANONICAL_TENANT_ID,
          "UNRECOGNIZED_KIND",
          7L,
          "game-key-7");
      assertThatThrownBy(
              () ->
                  kindFixture.repository.findRuntimeTenantIdentityByCanonicalTenantId(
                      NEW_CANONICAL_TENANT_ID))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void rejectsAmbiguousCanonicalUuidLookup() throws Exception {
    try (Fixture fixture = fixture()) {
      insert(
          fixture.dsl,
          7L,
          "first-game-key",
          NEW_CANONICAL_TENANT_ID,
          "NEW_GAME_ROW",
          7L,
          "first-game-key");
      insert(
          fixture.dsl,
          8L,
          "second-game-key",
          NEW_CANONICAL_TENANT_ID,
          "NEW_GAME_ROW",
          8L,
          "second-game-key");

      assertThatThrownBy(
              () ->
                  fixture.repository.findRuntimeTenantIdentityByCanonicalTenantId(
                      NEW_CANONICAL_TENANT_ID))
          .isInstanceOf(org.jooq.exception.TooManyRowsException.class);
    }
  }

  private static Fixture fixture() throws Exception {
    var connection =
        DriverManager.getConnection(
            "jdbc:h2:mem:game-repository-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    DSLContext dsl = DSL.using(connection, SQLDialect.H2);
    dsl.execute(
        "CREATE TABLE \"game\" ("
            + "\"id\" BIGINT PRIMARY KEY, "
            + "\"tenant_id\" VARCHAR(36) NOT NULL, "
            + "\"canonical_tenant_id\" UUID NOT NULL, "
            + "\"tenant_identity_provenance_kind\" VARCHAR(32), "
            + "\"tenant_identity_source_game_id\" BIGINT, "
            + "\"tenant_identity_source_legacy_tenant_id\" VARCHAR(36))");
    return new Fixture(connection, dsl, new GameRepository(dsl));
  }

  private static void insert(
      DSLContext dsl,
      long rowId,
      String tenantKey,
      UUID canonicalTenantId,
      String provenanceKind,
      long sourceGameId,
      String sourceTenantKey) {
    dsl.insertInto(GAME)
        .set(ID, rowId)
        .set(TENANT_ID, tenantKey)
        .set(CANONICAL_TENANT_ID, canonicalTenantId)
        .set(PROVENANCE_KIND, provenanceKind)
        .set(SOURCE_GAME_ID, sourceGameId)
        .set(SOURCE_TENANT_ID, sourceTenantKey)
        .execute();
  }

  private record Fixture(java.sql.Connection connection, DSLContext dsl, GameRepository repository)
      implements AutoCloseable {
    @Override
    public void close() throws Exception {
      connection.close();
    }
  }
}
