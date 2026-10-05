package net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.sql.DriverManager;
import java.util.UUID;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

class GameTenantCreationRepositoryTest {
  private static final String NAMESPACE = "test";
  private static final UUID REQUEST_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID OPERATION_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID TENANT_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final String SOURCE_KEY = "new-game-owner-key";
  private static final String NAME = "Fresh Realm";

  @Test
  void readRejectsChangedStoredRequestDigestBeforeReadingGameOwner() throws Exception {
    try (var connection =
        DriverManager.getConnection("jdbc:h2:mem:tenant-creation-digest;DATABASE_TO_LOWER=TRUE")) {
      DSLContext dsl = DSL.using(connection, SQLDialect.H2);
      dsl.execute(
          "CREATE TABLE game_tenant_creation_operations ("
              + "operation_id UUID NOT NULL, schema_version INTEGER NOT NULL, "
              + "target_namespace VARCHAR(63) NOT NULL, creation_request_id UUID NOT NULL, "
              + "request_digest VARCHAR(71) NOT NULL, source_game_tenant_key VARCHAR(36) NOT NULL, "
              + "name VARCHAR(100) NOT NULL, description VARCHAR(255), status VARCHAR(16) NOT NULL, "
              + "canonical_tenant_id UUID, source_game_row_id BIGINT, "
              + "provenance_kind VARCHAR(32), evidence_digest VARCHAR(71))");

      String changedRequestDigest =
          GameTenantCreationDigest.requestDigest(
              NAMESPACE, REQUEST_ID, "different-source-key", NAME, null);
      dsl.execute(
          "INSERT INTO game_tenant_creation_operations ("
              + "operation_id, schema_version, target_namespace, creation_request_id, "
              + "request_digest, source_game_tenant_key, name, description, status, "
              + "canonical_tenant_id, source_game_row_id, provenance_kind, evidence_digest) "
              + "VALUES (?, 1, ?, ?, ?, ?, ?, NULL, 'COMPLETED', ?, 91, 'NEW_GAME_ROW', ?)",
          OPERATION_ID,
          NAMESPACE,
          REQUEST_ID,
          changedRequestDigest,
          SOURCE_KEY,
          NAME,
          TENANT_ID,
          GameTenantCreationDigest.evidenceDigest(
              NAMESPACE,
              REQUEST_ID,
              OPERATION_ID,
              changedRequestDigest,
              TENANT_ID,
              91L,
              SOURCE_KEY,
              "NEW_GAME_ROW"));

      GameRepository gameRepository = mock(GameRepository.class);
      GameTenantCreationRepository repository =
          new GameTenantCreationRepository(dsl, gameRepository);

      assertThatThrownBy(() -> repository.read(REQUEST_ID, NAMESPACE))
          .isInstanceOf(GameTenantCreationRepository.InvalidCreationEvidenceException.class)
          .hasMessageContaining("request digest does not match");
      verifyNoInteractions(gameRepository);
    }
  }
}
