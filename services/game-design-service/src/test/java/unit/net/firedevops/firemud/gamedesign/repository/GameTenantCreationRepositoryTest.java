package net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.DriverManager;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
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
  private static final String DESCRIPTION = "A new hosted game";

  @Test
  void readReturnsOnlyExactCompleteCreationWithMatchingGameOwnerProvenance() throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:h2:mem:tenant-creation-read")) {
      DSLContext dsl = createOperationsTable(connection);
      String requestDigest = requestDigest(SOURCE_KEY);
      String evidenceDigest =
          evidenceDigest(NAMESPACE, REQUEST_ID, requestDigest, TENANT_ID, 91L, SOURCE_KEY);
      insertOperation(dsl, NAMESPACE, REQUEST_ID, requestDigest, TENANT_ID, 91L, evidenceDigest);
      GameRepository gameRepository = mock(GameRepository.class);
      when(gameRepository.findRuntimeTenantIdentityByTenantKey(SOURCE_KEY))
          .thenReturn(
              Optional.of(
                  new GameTenantIdentity(
                      TENANT_ID, GameTenantIdentity.ProvenanceKind.NEW_GAME_ROW, 91L, SOURCE_KEY)));

      Optional<FreshTenantCreationEvidence> result =
          new GameTenantCreationRepository(dsl, gameRepository).read(REQUEST_ID, NAMESPACE);

      assertThat(result)
          .contains(
              new FreshTenantCreationEvidence(
                  1,
                  NAMESPACE,
                  REQUEST_ID,
                  OPERATION_ID,
                  requestDigest,
                  TENANT_ID,
                  91L,
                  SOURCE_KEY,
                  "NEW_GAME_ROW",
                  evidenceDigest));
      verify(gameRepository).findRuntimeTenantIdentityByTenantKey(SOURCE_KEY);
    }
  }

  @Test
  void readReturnsAbsentForDifferentNamespaceWithoutReadingGameOwner() throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:h2:mem:tenant-creation-namespace")) {
      DSLContext dsl = createOperationsTable(connection);
      String requestDigest = requestDigest(SOURCE_KEY);
      insertOperation(
          dsl,
          NAMESPACE,
          REQUEST_ID,
          requestDigest,
          TENANT_ID,
          91L,
          evidenceDigest(NAMESPACE, REQUEST_ID, requestDigest, TENANT_ID, 91L, SOURCE_KEY));
      GameRepository gameRepository = mock(GameRepository.class);

      assertThat(new GameTenantCreationRepository(dsl, gameRepository).read(REQUEST_ID, "other"))
          .isEmpty();
      verifyNoInteractions(gameRepository);
    }
  }

  @Test
  void readRejectsChangedStoredRequestDigestAsCorruptEvidence() throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:h2:mem:tenant-creation-digest")) {
      DSLContext dsl = createOperationsTable(connection);
      String changedRequestDigest =
          GameTenantCreationDigest.requestDigest(
              NAMESPACE, REQUEST_ID, "changed-owner-key", NAME, DESCRIPTION);
      insertOperation(
          dsl,
          NAMESPACE,
          REQUEST_ID,
          changedRequestDigest,
          TENANT_ID,
          91L,
          evidenceDigest(NAMESPACE, REQUEST_ID, changedRequestDigest, TENANT_ID, 91L, SOURCE_KEY));
      GameRepository gameRepository = mock(GameRepository.class);

      assertThatThrownBy(
              () ->
                  new GameTenantCreationRepository(dsl, gameRepository).read(REQUEST_ID, NAMESPACE))
          .isInstanceOf(GameTenantCreationRepository.InvalidCreationEvidenceException.class)
          .hasMessageContaining("request digest does not match");
      verifyNoInteractions(gameRepository);
    }
  }

  @Test
  void readRejectsGameRowIdentityDriftEvenWhenOperationDigestsMatch() throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:h2:mem:tenant-creation-provenance")) {
      DSLContext dsl = createOperationsTable(connection);
      String requestDigest = requestDigest(SOURCE_KEY);
      insertOperation(
          dsl,
          NAMESPACE,
          REQUEST_ID,
          requestDigest,
          TENANT_ID,
          91L,
          evidenceDigest(NAMESPACE, REQUEST_ID, requestDigest, TENANT_ID, 91L, SOURCE_KEY));
      GameRepository gameRepository = mock(GameRepository.class);
      when(gameRepository.findRuntimeTenantIdentityByTenantKey(SOURCE_KEY))
          .thenReturn(
              Optional.of(
                  new GameTenantIdentity(
                      UUID.fromString("55555555-5555-4555-8555-555555555555"),
                      GameTenantIdentity.ProvenanceKind.NEW_GAME_ROW,
                      91L,
                      SOURCE_KEY)));

      assertThatThrownBy(
              () ->
                  new GameTenantCreationRepository(dsl, gameRepository).read(REQUEST_ID, NAMESPACE))
          .isInstanceOf(GameTenantCreationRepository.InvalidCreationEvidenceException.class)
          .hasMessageContaining("source tuple no longer matches");
      verify(gameRepository).findRuntimeTenantIdentityByTenantKey(SOURCE_KEY);
    }
  }

  @Test
  void readRejectsIncompleteOperationAndMalformedSelectorsBeforeOwnerRead() throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:h2:mem:tenant-creation-incomplete")) {
      DSLContext dsl = createOperationsTable(connection);
      String requestDigest = requestDigest(SOURCE_KEY);
      insertOperation(
          dsl,
          NAMESPACE,
          REQUEST_ID,
          requestDigest,
          TENANT_ID,
          91L,
          evidenceDigest(NAMESPACE, REQUEST_ID, requestDigest, TENANT_ID, 91L, SOURCE_KEY),
          "PENDING");
      GameRepository gameRepository = mock(GameRepository.class);
      GameTenantCreationRepository repository =
          new GameTenantCreationRepository(dsl, gameRepository);

      assertThatThrownBy(() -> repository.read(REQUEST_ID, NAMESPACE))
          .isInstanceOf(GameTenantCreationRepository.InvalidCreationEvidenceException.class)
          .hasMessageContaining("not complete");
      assertThatThrownBy(() -> repository.read(REQUEST_ID, "Test"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("lowercase DNS label");
      assertThatThrownBy(() -> repository.read(new UUID(0L, 0L), NAMESPACE))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must not be nil");
      verifyNoInteractions(gameRepository);
    }
  }

  private static DSLContext createOperationsTable(java.sql.Connection connection) throws Exception {
    DSLContext dsl = DSL.using(connection, SQLDialect.H2);
    dsl.execute(
        "CREATE TABLE \"game_tenant_creation_operations\" ("
            + "\"operation_id\" UUID NOT NULL, "
            + "\"schema_version\" INTEGER NOT NULL, "
            + "\"target_namespace\" VARCHAR(63) NOT NULL, "
            + "\"creation_request_id\" UUID NOT NULL, "
            + "\"request_digest\" VARCHAR(71) NOT NULL, "
            + "\"source_game_tenant_key\" VARCHAR(36) NOT NULL, "
            + "\"name\" VARCHAR(100) NOT NULL, "
            + "\"description\" VARCHAR(255), "
            + "\"status\" VARCHAR(16) NOT NULL, "
            + "\"canonical_tenant_id\" UUID, "
            + "\"source_game_row_id\" BIGINT, "
            + "\"provenance_kind\" VARCHAR(32), "
            + "\"evidence_digest\" VARCHAR(71))");
    return dsl;
  }

  private static void insertOperation(
      DSLContext dsl,
      String namespace,
      UUID requestId,
      String requestDigest,
      UUID tenantId,
      long gameRowId,
      String evidenceDigest) {
    insertOperation(
        dsl, namespace, requestId, requestDigest, tenantId, gameRowId, evidenceDigest, "COMPLETED");
  }

  private static void insertOperation(
      DSLContext dsl,
      String namespace,
      UUID requestId,
      String requestDigest,
      UUID tenantId,
      long gameRowId,
      String evidenceDigest,
      String status) {
    dsl.execute(
        "INSERT INTO \"game_tenant_creation_operations\" ("
            + "\"operation_id\", \"schema_version\", \"target_namespace\", "
            + "\"creation_request_id\", \"request_digest\", \"source_game_tenant_key\", "
            + "\"name\", \"description\", \"status\", \"canonical_tenant_id\", "
            + "\"source_game_row_id\", \"provenance_kind\", \"evidence_digest\") "
            + "VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'NEW_GAME_ROW', ?)",
        OPERATION_ID,
        namespace,
        requestId,
        requestDigest,
        SOURCE_KEY,
        NAME,
        DESCRIPTION,
        status,
        tenantId,
        gameRowId,
        evidenceDigest);
  }

  private static String requestDigest(String sourceKey) {
    return GameTenantCreationDigest.requestDigest(
        NAMESPACE, REQUEST_ID, sourceKey, NAME, DESCRIPTION);
  }

  private static String evidenceDigest(
      String namespace,
      UUID requestId,
      String requestDigest,
      UUID tenantId,
      long gameRowId,
      String sourceKey) {
    return GameTenantCreationDigest.evidenceDigest(
        namespace,
        requestId,
        OPERATION_ID,
        requestDigest,
        tenantId,
        gameRowId,
        sourceKey,
        "NEW_GAME_ROW");
  }
}
