package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class FreshTenantIdentityAssociationRepositoryTest {
  private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID OPERATION_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID TENANT_UUID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final String REQUEST_DIGEST = "sha256:" + "a".repeat(64);
  private static final String NAMESPACE = "test";
  private static final long SOURCE_ROW_ID = 42L;
  private static final String SOURCE_KEY = "game-tenant-42";

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  @Test
  void exactOwnerEvidenceIsInsertedAndRequiresExactClaimReadback() {
    FreshTenantCreationEvidence evidence = evidence(SOURCE_ROW_ID, SOURCE_KEY);
    RepositoryFixture fixture = repositoryFixture(evidence, 1);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    FreshTenantCreationEvidence imported = fixture.repository().importVerified(evidence);

    assertThat(imported).isEqualTo(evidence);
    verify(fixture.dsl())
        .execute(
            startsWith("INSERT INTO account_fresh_tenant_identity_associations"),
            any(Object[].class));
    verify(fixture.dsl()).fetchOne(startsWith("SELECT fresh."), any(Object[].class));
  }

  @Test
  void exactVerifiedRetryAcceptsAnExistingIdenticalImmutableClaim() {
    FreshTenantCreationEvidence evidence = evidence(SOURCE_ROW_ID, SOURCE_KEY);
    RepositoryFixture fixture = repositoryFixture(evidence, 0);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThat(fixture.repository().importVerified(evidence)).isEqualTo(evidence);
  }

  @Test
  void corruptOwnerReadbackRejectsImportInsteadOfAcceptingTheInsertCount() {
    FreshTenantCreationEvidence evidence = evidence(SOURCE_ROW_ID, SOURCE_KEY);
    RepositoryFixture fixture = repositoryFixture(evidence, 1);
    fixture.values().put("source_game_row_id", SOURCE_ROW_ID + 1L);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(() -> fixture.repository().importVerified(evidence))
        .isInstanceOf(
            FreshTenantIdentityAssociationRepository.InvalidTenantIdentityEvidenceException.class)
        .hasMessageContaining("invalid owner evidence");
    verify(fixture.dsl())
        .execute(
            startsWith("INSERT INTO account_fresh_tenant_identity_associations"),
            any(Object[].class));
  }

  @Test
  void importRequiresOwnerTransactionAndConfiguredNamespaceBeforeStorageAccess() {
    DSLContext dsl = mock(DSLContext.class);
    FreshTenantIdentityAssociationRepository repository =
        new FreshTenantIdentityAssociationRepository(dsl, NAMESPACE);
    FreshTenantCreationEvidence evidence = evidence(SOURCE_ROW_ID, SOURCE_KEY);

    assertThatThrownBy(() -> repository.importVerified(evidence))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active owner transaction");
    verifyNoInteractions(dsl);

    TransactionSynchronizationManager.setActualTransactionActive(true);
    FreshTenantIdentityAssociationRepository wrongNamespace =
        new FreshTenantIdentityAssociationRepository(dsl, "other");
    assertThatThrownBy(() -> wrongNamespace.importVerified(evidence))
        .isInstanceOf(
            FreshTenantIdentityAssociationRepository.InvalidTenantIdentityEvidenceException.class)
        .hasMessageContaining("different workload namespace");
    verifyNoInteractions(dsl);
  }

  private static RepositoryFixture repositoryFixture(
      FreshTenantCreationEvidence evidence, int insertedRows) {
    DSLContext dsl = mock(DSLContext.class);
    Record row = mock(Record.class);
    Map<String, Object> values = evidenceRow(evidence);
    doAnswer(
            invocation -> {
              String name = invocation.getArgument(0);
              Class<?> type = invocation.getArgument(1);
              Object value = values.get(name);
              return value == null ? null : type.cast(value);
            })
        .when(row)
        .get(anyString(), any(Class.class));
    when(dsl.execute(
            startsWith("INSERT INTO account_fresh_tenant_identity_associations"),
            any(Object[].class)))
        .thenReturn(insertedRows);
    doReturn(row).when(dsl).fetchOne(startsWith("SELECT fresh."), any(Object[].class));
    return new RepositoryFixture(
        new FreshTenantIdentityAssociationRepository(dsl, NAMESPACE), dsl, values);
  }

  private static Map<String, Object> evidenceRow(FreshTenantCreationEvidence evidence) {
    Map<String, Object> values = new HashMap<>();
    values.put("schema_version", evidence.schemaVersion());
    values.put("target_namespace", evidence.targetNamespace());
    values.put("creation_request_id", evidence.creationRequestId());
    values.put("operation_id", evidence.operationId());
    values.put("request_digest", evidence.requestDigest());
    values.put("canonical_tenant_id", evidence.canonicalTenantId());
    values.put("source_game_row_id", evidence.sourceGameRowId());
    values.put("source_game_tenant_key", evidence.sourceGameTenantKey());
    values.put("provenance_kind", evidence.provenanceKind());
    values.put("evidence_digest", evidence.evidenceDigest());
    values.put("claim_canonical_tenant_id", evidence.canonicalTenantId());
    values.put("claim_identity_kind", "FRESH_GAME_DESIGN");
    values.put("claim_source_operation_id", evidence.operationId());
    values.put("claim_target_namespace", evidence.targetNamespace());
    values.put("claim_creation_request_id", evidence.creationRequestId());
    values.put("claim_request_digest", evidence.requestDigest());
    values.put("claim_game_row_id", evidence.sourceGameRowId());
    values.put("claim_game_tenant_key", evidence.sourceGameTenantKey());
    values.put("claim_provenance_kind", evidence.provenanceKind());
    values.put("claim_evidence_digest", evidence.evidenceDigest());
    return values;
  }

  private static FreshTenantCreationEvidence evidence(long sourceRowId, String sourceKey) {
    return new FreshTenantCreationEvidence(
        1,
        NAMESPACE,
        REQUEST_ID,
        OPERATION_ID,
        REQUEST_DIGEST,
        TENANT_UUID,
        sourceRowId,
        sourceKey,
        "NEW_GAME_ROW",
        GameTenantCreationDigest.evidenceDigest(
            NAMESPACE,
            REQUEST_ID,
            OPERATION_ID,
            REQUEST_DIGEST,
            TENANT_UUID,
            sourceRowId,
            sourceKey,
            "NEW_GAME_ROW"));
  }

  private record RepositoryFixture(
      FreshTenantIdentityAssociationRepository repository,
      DSLContext dsl,
      Map<String, Object> values) {}
}
