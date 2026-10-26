package net.firedevops.firemud.gamesession.service;

import static net.firedevops.firemud.gamesession.repository.AccountInitialAdmissionProtectionBindingRepositoryTest.NAMESPACE;
import static net.firedevops.firemud.gamesession.repository.AccountInitialAdmissionProtectionBindingRepositoryTest.OPERATION;
import static net.firedevops.firemud.gamesession.repository.AccountInitialAdmissionProtectionBindingRepositoryTest.TERMINAL;
import static net.firedevops.firemud.gamesession.repository.AccountInitialAdmissionProtectionBindingRepositoryTest.fields;
import static net.firedevops.firemud.gamesession.repository.AccountInitialAdmissionProtectionBindingRepositoryTest.protection;
import static net.firedevops.firemud.gamesession.repository.AccountInitialAdmissionProtectionBindingRepositoryTest.record;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.account.startsession.AccountInitialAdmissionSettlementReadInput;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.repository.AccountInitialAdmissionProtectionBindingRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalAdmissionPointerRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Real owner read composition over controlled row projections; not physical or issuer proof. */
class AccountInitialAdmissionSettlementReadServiceTest {
  @Test
  void realOriginalAndAdmissionReadersReturnOnlyExactProtectedFencedAbortAndReplay() {
    var p = protection();
    var request = p.request();
    var w = request.worldAdmissionHoldIdentity().request();
    var terminal = fields(p, "ABORTED");
    terminal.put("game_session_tenant_id", 1L);
    terminal.put("game_instance_id", 2L);
    terminal.put("runtime_version_id", 3L);
    terminal.put("created_at", TERMINAL.minusSeconds(20).atOffset(ZoneOffset.UTC));
    terminal.put("updated_at", TERMINAL.atOffset(ZoneOffset.UTC));
    var terminalDsl = mock(DSLContext.class);
    var terminalRow = record(terminal);
    var noPointerRow = record(Map.of("represented", false));
    when(terminalDsl.fetchOne(anyString(), eq(NAMESPACE), eq(OPERATION))).thenReturn(terminalRow);
    when(terminalDsl.fetchOne(
            anyString(), eq(NAMESPACE), eq(OPERATION), eq(NAMESPACE), eq(OPERATION)))
        .thenReturn(noPointerRow);
    var originalFields = new HashMap<String, Object>();
    originalFields.put("target_namespace", NAMESPACE);
    originalFields.put("control_plane_request_id", request.originalTuple().controlPlaneRequestId());
    originalFields.put("canonical_tenant_id", w.canonicalTenantId());
    originalFields.put("owner_attempt_id", request.gameSessionOwnerAttemptId());
    originalFields.put("owner_mutation_id", request.gameSessionOwnerMutationId());
    originalFields.put("owner_fence", request.gameSessionOwnerFence());
    originalFields.put(
        "post_authorization_execution_tuple", request.originalPostAuthorizationTupleBytes());
    originalFields.put("account_redemption_projection", request.accountRedemptionProjectionBytes());
    originalFields.put("mutation_digest", request.originalTuple().mutationDigest());
    originalFields.put(
        "authorization_reference_fingerprint",
        request.originalTuple().authorizationReferenceFingerprint());
    originalFields.put("phase_state", "OWNER_EXECUTION_PENDING");
    originalFields.put("created_at", TERMINAL.minusSeconds(60).atOffset(ZoneOffset.UTC));
    originalFields.put(
        "lease_expires_at", request.originalLeaseExpiresAt().atOffset(ZoneOffset.UTC));
    var originalDsl = mock(DSLContext.class);
    var originalRow = record(originalFields);
    when(originalDsl.fetchOne(
            anyString(), eq(NAMESPACE), eq(request.originalTuple().controlPlaneRequestId())))
        .thenReturn(originalRow);
    var catalog = mock(CanonicalRealmCatalogSnapshot.class);
    when(catalog.targetNamespace()).thenReturn(NAMESPACE);
    when(catalog.tenantId()).thenReturn(w.canonicalTenantId());
    when(catalog.worldSlug()).thenReturn(w.worldSlug());
    when(catalog.realmId()).thenReturn(w.realmId());
    when(catalog.playableStateNamespaceId()).thenReturn(w.playableStateNamespaceId());
    when(catalog.stateScope()).thenReturn(w.playableStateScope());
    when(catalog.catalogRevision()).thenReturn(w.expectedCatalogRevision());
    when(catalog.visible()).thenReturn(true);
    when(catalog.publicProduction()).thenReturn(true);
    var catalogs = mock(GameSessionCanonicalRealmCatalogRepository.class);
    when(catalogs.readByRealm(NAMESPACE, w.canonicalTenantId(), w.realmId()))
        .thenReturn(Optional.of(catalog));
    var owner =
        new AccountInitialAdmissionSettlementReadService(
            new AccountInitialAdmissionProtectionBindingRepository(terminalDsl),
            new GameSessionStartSessionOperatorAttemptRepository(
                originalDsl, Duration.ofMinutes(1)),
            new CanonicalInitialAdmissionRepository(
                terminalDsl, catalogs, mock(GameSessionCanonicalAdmissionPointerRepository.class)),
            NAMESPACE);
    var input = new AccountInitialAdmissionSettlementReadInput(UUID.randomUUID(), p);
    var first = owner.read(input);
    var replay = owner.read(input);
    assertThat(first.terminal().outcome())
        .isEqualTo(GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.ABORTED);
    assertThat(first.terminal().positiveDurableAbort()).isTrue();
    assertThat(first.protection().canonicalBytes()).containsExactly(p.canonicalBytes());
    assertThat(replay.terminal()).isEqualTo(first.terminal());
    assertThat(replay.protection().canonicalBytes())
        .containsExactly(first.protection().canonicalBytes());
    originalFields.put("lease_expires_at", TERMINAL.atOffset(ZoneOffset.UTC));
    assertThatThrownBy(() -> owner.read(input)).isInstanceOf(IllegalStateException.class);
    originalFields.put(
        "lease_expires_at", request.originalLeaseExpiresAt().atOffset(ZoneOffset.UTC));
    terminal.put("abort_proof_digest", "sha256:" + "a".repeat(64));
    assertThatThrownBy(() -> owner.read(input)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void noLegacyMissingPartialOrAmbientFactsBecomeSettlement() {
    var owner =
        new AccountInitialAdmissionSettlementReadService(
            new AccountInitialAdmissionProtectionBindingRepository(mock(DSLContext.class)),
            mock(GameSessionStartSessionOperatorAttemptRepository.class),
            mock(CanonicalInitialAdmissionRepository.class),
            NAMESPACE);
    var input = new AccountInitialAdmissionSettlementReadInput(UUID.randomUUID(), protection());
    assertThatThrownBy(() -> owner.read(input))
        .isInstanceOf(
            AccountInitialAdmissionSettlementReadService.SettlementUnavailableException.class);
    TransactionSynchronizationManager.initSynchronization();
    try {
      assertThatThrownBy(() -> owner.read(input))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("independently committed");
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> owner.read(input))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("independently committed");
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    var foreign =
        new AccountInitialAdmissionSettlementReadService(
            new AccountInitialAdmissionProtectionBindingRepository(mock(DSLContext.class)),
            mock(GameSessionStartSessionOperatorAttemptRepository.class),
            mock(CanonicalInitialAdmissionRepository.class),
            "foreign");
    assertThatThrownBy(() -> foreign.read(input)).isInstanceOf(IllegalArgumentException.class);
  }
}
