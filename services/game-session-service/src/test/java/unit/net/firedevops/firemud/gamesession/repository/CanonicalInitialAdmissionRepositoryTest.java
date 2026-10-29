package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest.OriginKind;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionWorldProof;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CanonicalInitialAdmissionRepositoryTest {
  @Test
  void completeCreationSourcePreservesActualPointerTimestamp() {
    var fields = completeOpenFields();
    var repository = completeSourceRepository(fields, fields);
    var source = repository.readCurrentOpenSourceForRealm("gameplay", uuid(1), uuid(2));
    assertThat(source.updatedAt()).isEqualTo(Instant.parse("2026-10-24T00:00:00.123456Z"));
    assertThat(source.updatedAt()).isNotEqualTo(source.snapshot().ownerProof().terminalAt());
    assertThat(source.tenantId()).isEqualTo(uuid(1));
    assertThat(source.realmId()).isEqualTo(uuid(2));
    assertThat(source.canonicalGameInstanceId()).isEqualTo(uuid(4));
    assertThat(source.pointerVersion()).isEqualTo(73);
  }

  @Test
  void completeCreationSourceRejectsTimestampOnlyReplacementAcrossQualifiedRead() {
    var before = completeOpenFields();
    var after = completeOpenFields();
    after.put(
        "updated_at",
        java.sql.Timestamp.valueOf(java.time.LocalDateTime.parse("2026-10-24T00:00:00.123457")));
    var repository = completeSourceRepository(before, after);
    assertThatThrownBy(() -> repository.readCurrentOpenSourceForRealm("gameplay", uuid(1), uuid(2)))
        .isInstanceOf(
            CanonicalInitialAdmissionRepository
                .CanonicalInitialAdmissionReconciliationRequiredException.class);
  }

  private static Map<String, Object> completeOpenFields() {
    var fields = validOpenFields();
    fields.put("id", 1L);
    fields.put("tenant_id", 1L);
    fields.put("game_instance_id", 2L);
    fields.put("runtime_version_id", 3L);
    fields.put(
        "updated_at",
        java.sql.Timestamp.valueOf(java.time.LocalDateTime.parse("2026-10-24T00:00:00.123456")));
    return fields;
  }

  private static CanonicalInitialAdmissionRepository completeSourceRepository(
      Map<String, Object> before, Map<String, Object> after) {
    var dsl = mock(DSLContext.class);
    var catalog = mock(GameSessionCanonicalRealmCatalogRepository.class);
    var pointers = mock(GameSessionCanonicalAdmissionPointerRepository.class);
    var repository = spy(new CanonicalInitialAdmissionRepository(dsl, catalog, pointers));
    var first = mock(org.jooq.Record.class);
    var second = mock(org.jooq.Record.class);
    when(first.intoMap()).thenReturn(before);
    when(second.intoMap()).thenReturn(after);
    org.jooq.Result<org.jooq.Record> firstRows =
        org.jooq.impl.DSL.using(org.jooq.SQLDialect.POSTGRES).newResult(new org.jooq.Field<?>[0]);
    org.jooq.Result<org.jooq.Record> secondRows =
        org.jooq.impl.DSL.using(org.jooq.SQLDialect.POSTGRES).newResult(new org.jooq.Field<?>[0]);
    firstRows.add(first);
    secondRows.add(second);
    when(dsl.fetch(anyString(), eq("gameplay"), eq(uuid(1)), eq(uuid(2))))
        .thenReturn(firstRows, secondRows);
    var proof = committedProof();
    String digest =
        CanonicalInitialAdmissionRepository.snapshotDigestOrReconciliation(
            new CanonicalInitialAdmissionRepository.CurrentOpenPointer(before), proof);
    doReturn(new CanonicalInitialAdmissionRepository.CurrentOpenSnapshot(proof, digest))
        .when(repository)
        .readCurrentOpenSnapshotForRealm("gameplay", uuid(1), uuid(2));
    return repository;
  }

  @Test
  void incompleteHumanReserveCommitAndAbortDenyBeforeAnyOwnerStorageInteraction() {
    var dsl = mock(DSLContext.class);
    var catalog = mock(GameSessionCanonicalRealmCatalogRepository.class);
    var pointers = mock(GameSessionCanonicalAdmissionPointerRepository.class);
    var repository = new CanonicalInitialAdmissionRepository(dsl, catalog, pointers);
    var request = canonicalRequest();
    var world = worldProof(request);
    assertThatThrownBy(() -> repository.reserve(request))
        .isInstanceOf(
            CanonicalInitialAdmissionRepository
                .CanonicalHumanAdmissionProtectionUnavailableException.class)
        .hasMessageContaining("Account terminal protection");
    assertThatThrownBy(() -> repository.commit(request, world))
        .isInstanceOf(
            CanonicalInitialAdmissionRepository
                .CanonicalHumanAdmissionProtectionUnavailableException.class)
        .hasMessageContaining("Account terminal protection");
    assertThatThrownBy(() -> repository.abort(request, "Missing protection"))
        .isInstanceOf(
            CanonicalInitialAdmissionRepository
                .CanonicalHumanAdmissionProtectionUnavailableException.class);
    verifyNoInteractions(dsl, catalog, pointers);
  }

  @Test
  void proxiedHumanDenialsDoNotAcquireTransactionOrDatabaseConnection() {
    var dsl = mock(DSLContext.class);
    var catalog = mock(GameSessionCanonicalRealmCatalogRepository.class);
    var pointers = mock(GameSessionCanonicalAdmissionPointerRepository.class);
    var repository = new CanonicalInitialAdmissionRepository(dsl, catalog, pointers);
    var dataSource = new RefusingDataSource();
    var transactionManager = new DataSourceTransactionManager(dataSource);
    var proxyFactory = new ProxyFactory(repository);
    proxyFactory.addAdvice(
        new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
    var proxied = (CanonicalInitialAdmissionRepository) proxyFactory.getProxy();
    var request = canonicalRequest();
    var world = worldProof(request);

    assertThatThrownBy(() -> proxied.reserve(request))
        .isInstanceOf(
            CanonicalInitialAdmissionRepository
                .CanonicalHumanAdmissionProtectionUnavailableException.class);
    assertThatThrownBy(() -> proxied.commit(request, world))
        .isInstanceOf(
            CanonicalInitialAdmissionRepository
                .CanonicalHumanAdmissionProtectionUnavailableException.class);
    assertThatThrownBy(() -> proxied.abort(request, "Missing protection"))
        .isInstanceOf(
            CanonicalInitialAdmissionRepository
                .CanonicalHumanAdmissionProtectionUnavailableException.class);

    assertThat(dataSource.connectionAttempts()).isZero();
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    verifyNoInteractions(dsl, catalog, pointers);
  }

  @Test
  void pointerAndAuditVersionsUseValueEqualityBeyondLongCache() {
    Long attemptVersion = Long.valueOf(129L);
    Long pointerVersion = Long.valueOf(129L);
    Long auditVersion = Long.valueOf(129L);

    assertThat(attemptVersion).isNotSameAs(pointerVersion);
    assertThat(pointerVersion).isNotSameAs(auditVersion);
    assertThat(
            CanonicalInitialAdmissionRepository.pointerVersionMatches(
                attemptVersion, pointerVersion))
        .isTrue();
    assertThat(
            CanonicalInitialAdmissionRepository.pointerVersionMatches(attemptVersion, auditVersion))
        .isTrue();
  }

  @Test
  void pointerAndAuditVersionComparisonRejectsNullMismatch() {
    assertThat(CanonicalInitialAdmissionRepository.pointerVersionMatches(null, 129L)).isFalse();
    assertThat(CanonicalInitialAdmissionRepository.pointerVersionMatches(129L, null)).isFalse();
  }

  @Test
  void malformedPersistedOpenProjectionRequiresReconciliationAtSnapshotBoundary() {
    CanonicalInitialAdmissionOwnerProof proof = committedProof();
    CanonicalInitialAdmissionRepository.CurrentOpenPointer validPointer =
        new CanonicalInitialAdmissionRepository.CurrentOpenPointer(validOpenFields());

    assertThat(
            CanonicalInitialAdmissionRepository.snapshotDigestOrReconciliation(validPointer, proof))
        .matches("[0-9a-f]{64}");

    Map<String, Object> paddedName = validOpenFields();
    paddedName.put("world_display_name", " Demo World ");
    assertMalformedProjection(
        new CanonicalInitialAdmissionRepository.CurrentOpenPointer(paddedName),
        proof,
        IllegalArgumentException.class);

    Map<String, Object> invalidOrigin = validOpenFields();
    invalidOrigin.put("initial_admission_origin_kind", "UNKNOWN_ORIGIN");
    assertMalformedProjection(
        new CanonicalInitialAdmissionRepository.CurrentOpenPointer(invalidOrigin),
        proof,
        IllegalArgumentException.class);

    Map<String, Object> nullOrigin = validOpenFields();
    nullOrigin.put("initial_admission_origin_kind", null);
    assertMalformedProjection(
        new CanonicalInitialAdmissionRepository.CurrentOpenPointer(nullOrigin),
        proof,
        NullPointerException.class);
  }

  private static void assertMalformedProjection(
      CanonicalInitialAdmissionRepository.CurrentOpenPointer pointer,
      CanonicalInitialAdmissionOwnerProof proof,
      Class<? extends Throwable> cause) {
    assertThatThrownBy(
            () ->
                CanonicalInitialAdmissionRepository.snapshotDigestOrReconciliation(pointer, proof))
        .isInstanceOf(
            CanonicalInitialAdmissionRepository
                .CanonicalInitialAdmissionReconciliationRequiredException.class)
        .hasCauseInstanceOf(cause);
  }

  private static Map<String, Object> validOpenFields() {
    Map<String, Object> fields = new HashMap<>();
    fields.put("representation_version", 3);
    fields.put("target_namespace", "gameplay");
    fields.put("canonical_tenant_id", uuid(1));
    fields.put("world_slug", "demo-world");
    fields.put("world_display_name", "Demo World");
    fields.put("realm_id", uuid(2));
    fields.put("realm_slug", "main");
    fields.put("realm_display_name", "Production Main");
    fields.put("playable_state_namespace_id", uuid(3));
    fields.put("state_scope", "SHARED");
    fields.put("catalog_revision", 41L);
    fields.put("pointer_version", 73L);
    fields.put("admission_state", "OPEN");
    fields.put("visible", true);
    fields.put("public_production_realm", true);
    fields.put("requires_character_selection", null);
    fields.put("character_creation_policy", "ALLOW_NEW");
    fields.put("canonical_game_instance_id", uuid(4));
    fields.put("canonical_version_id", uuid(5));
    fields.put("initial_admission_request_id", "initial-admission-request");
    fields.put("initial_admission_request_digest", requestDigest());
    fields.put("initial_admission_origin_kind", "NO_PRIOR_POINTER");
    fields.put("initial_admission_prior_pointer_version", null);
    fields.put("initial_admission_active_epoch", 82L);
    fields.put("initial_admission_hold_id", uuid(6));
    fields.put("initial_admission_hold_fence", uuid(7));
    fields.put("initial_admission_hold_binding_digest", "sha256:" + "b".repeat(64));
    fields.put("last_updated_by", "game-session-canonical-initial-admission");
    fields.put("last_update_reason", "World-held initial admission");
    fields.put("prepared_version_upgrade_id", null);
    return fields;
  }

  private static CanonicalInitialAdmissionRequest canonicalRequest() {
    CanonicalInitialAdmissionOwnerProof proof = committedProof();
    return new CanonicalInitialAdmissionRequest(
        proof.targetNamespace(),
        proof.canonicalTenantId(),
        proof.worldSlug(),
        proof.realmId(),
        proof.playableStateNamespaceId(),
        proof.playableStateScope(),
        proof.canonicalGameInstanceId(),
        proof.canonicalVersionId(),
        proof.activeLifecycleEpoch(),
        proof.expectedCatalogRevision(),
        proof.originKind(),
        proof.expectedPriorPointerVersion(),
        proof.initialAdmissionRequestId(),
        proof.requestDigest(),
        proof.holdId(),
        proof.holdFence(),
        proof.holdBindingDigest());
  }

  private static CanonicalInitialAdmissionWorldProof worldProof(
      CanonicalInitialAdmissionRequest request) {
    return new CanonicalInitialAdmissionWorldProof(
        request.initialAdmissionRequestId(),
        request.requestDigest(),
        request.targetNamespace(),
        request.canonicalTenantId(),
        request.worldSlug(),
        request.realmId(),
        request.playableStateNamespaceId(),
        request.playableStateScope(),
        request.canonicalGameInstanceId(),
        request.canonicalVersionId(),
        "ACTIVE",
        request.activeLifecycleEpoch(),
        request.originKind(),
        request.expectedCatalogRevision(),
        request.expectedPriorPointerVersion(),
        request.holdId(),
        request.holdFence(),
        request.holdBindingDigest());
  }

  private static final class RefusingDataSource extends AbstractDataSource {
    private final AtomicInteger connectionAttempts = new AtomicInteger();

    @Override
    public Connection getConnection() throws SQLException {
      connectionAttempts.incrementAndGet();
      throw new SQLException("Human admission denial must not acquire a database connection");
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return getConnection();
    }

    int connectionAttempts() {
      return connectionAttempts.get();
    }
  }

  private static String requestDigest() {
    return CanonicalInitialAdmissionRequest.computeRequestDigest(
        "gameplay",
        uuid(1),
        "demo-world",
        uuid(2),
        uuid(3),
        "SHARED",
        uuid(4),
        uuid(5),
        82L,
        41L,
        OriginKind.NO_PRIOR_POINTER,
        null,
        "initial-admission-request");
  }

  private static CanonicalInitialAdmissionOwnerProof committedProof() {
    return new CanonicalInitialAdmissionOwnerProof(
        CanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
        "initial-admission-request",
        requestDigest(),
        "gameplay",
        uuid(1),
        "demo-world",
        uuid(2),
        uuid(3),
        "SHARED",
        uuid(4),
        uuid(5),
        82L,
        41L,
        OriginKind.NO_PRIOR_POINTER,
        null,
        uuid(6),
        uuid(7),
        "sha256:" + "b".repeat(64),
        73L,
        27L,
        "sha256:" + "f".repeat(64),
        false,
        Instant.parse("2026-10-07T00:00:00Z"));
  }

  private static UUID uuid(int value) {
    return UUID.fromString(String.format("%08d-1111-4111-8111-111111111111", value));
  }
}
