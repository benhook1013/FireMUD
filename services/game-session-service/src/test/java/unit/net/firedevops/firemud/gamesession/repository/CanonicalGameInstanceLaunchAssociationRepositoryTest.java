package unit.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.gamesession.HistoricalOriginalStartSessionOwnerEvidence;
import net.firedevops.firemud.common.tenant.RuntimeTenantIdentityEvidence;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.service.FreshGameSessionTenantAssociation;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CanonicalGameInstanceLaunchAssociationRepositoryTest {
  private static final String NAMESPACE = "launch-owner";

  @AfterEach
  void clearTransactionContext() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void captureRequiresAWritableOwnerTransactionBeforeReadingAnyRuntimeRow() {
    DSLContext dsl = mock(DSLContext.class);
    var repository = new CanonicalGameInstanceLaunchAssociationRepository(dsl, NAMESPACE);
    var tenant = freshTenant();

    assertThatThrownBy(() -> repository.capture(tenant, 92L, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writable owner transaction");

    verifyNoInteractions(dsl);
  }

  @Test
  void committedReadCannotBeComposedInsideAnotherOwnerTransaction() {
    DSLContext dsl = mock(DSLContext.class);
    var repository = new CanonicalGameInstanceLaunchAssociationRepository(dsl, NAMESPACE);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(() -> repository.read("launch-request-1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("committed owner read");

    verifyNoInteractions(dsl);
  }

  @Test
  void originalStartSessionOwnerReadAlsoRequiresOneCommittedOwnerSnapshot() {
    DSLContext dsl = mock(DSLContext.class);
    var repository = new CanonicalGameInstanceLaunchAssociationRepository(dsl, NAMESPACE);
    var selector =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
            NAMESPACE,
            UUID.fromString("55555555-5555-4555-8555-555555555555"),
            "world",
            UUID.fromString("66666666-6666-4666-8666-666666666666"),
            "launch-request-1",
            "descriptor-1",
            "sha256:" + "a".repeat(64),
            "sha256:" + "b".repeat(64),
            "sha256:" + "c".repeat(64));
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(() -> repository.readOriginalStartSessionAssociation(selector))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("committed owner read");

    verifyNoInteractions(dsl);
  }

  @Test
  void historicalReadUsesOneImmutableSnapshotWithoutCurrentStatusOrLeaseAuthority() {
    DSLContext dsl = mock(DSLContext.class);
    var repository = new CanonicalGameInstanceLaunchAssociationRepository(dsl, NAMESPACE);
    var request = historicalRequest(NAMESPACE);
    AtomicReference<String> sql = new AtomicReference<>();
    AtomicReference<List<Object>> bindValues = new AtomicReference<>();
    doAnswer(
            invocation -> {
              Object[] rawArguments = invocation.getRawArguments();
              sql.set((String) rawArguments[0]);
              bindValues.set(List.copyOf(Arrays.asList((Object[]) rawArguments[1])));
              return null;
            })
        .when(dsl)
        .fetchOne(anyString(), any(Object[].class));

    assertThat(repository.readHistoricalOriginalStartSessionOwnerEvidence(request)).isEmpty();

    assertThat(sql.get())
        .contains("FROM game_session_canonical_instance_launch association")
        .contains("JOIN game_session_start_session_operator_attempt attempt")
        .contains("JOIN game_session_start_session_template_association_pin association_pin")
        .contains("JOIN game_session_start_session_launch_descriptor_pin descriptor_pin")
        .doesNotContain("game_instances")
        .doesNotContain("lease_expires_at")
        .doesNotContain("clock_timestamp")
        .doesNotContain("phase_state")
        .doesNotContain("FOR UPDATE")
        .doesNotContain("INSERT ")
        .doesNotContain("UPDATE ")
        .doesNotContain("DELETE ");
    assertThat(bindValues.get())
        .containsExactly(
            request.expectedOwnerAttemptId(),
            request.expectedOwnerFence(),
            NAMESPACE,
            request.associationSelector().canonicalTenantId(),
            request.associationSelector().worldSlug(),
            request.associationSelector().gameInstanceUuid(),
            request.associationSelector().controlPlaneRequestId(),
            request.associationSelector().launchDescriptorId());
  }

  @Test
  void historicalReadRejectsWrongNamespaceAndAmbientTransactionsBeforeSql() {
    DSLContext dsl = mock(DSLContext.class);
    var repository = new CanonicalGameInstanceLaunchAssociationRepository(dsl, NAMESPACE);

    assertThatThrownBy(
            () ->
                repository.readHistoricalOriginalStartSessionOwnerEvidence(
                    historicalRequest("other")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("another Game Session namespace");
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(
            () ->
                repository.readHistoricalOriginalStartSessionOwnerEvidence(
                    historicalRequest(NAMESPACE)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("committed owner read");

    verifyNoInteractions(dsl);
  }

  private static HistoricalOriginalStartSessionOwnerEvidence.Request historicalRequest(
      String namespace) {
    return new HistoricalOriginalStartSessionOwnerEvidence.Request(
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            namespace,
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            "world",
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            "start-session-request",
            "launch-descriptor",
            "sha256:" + "a".repeat(64),
            "sha256:" + "b".repeat(64),
            "sha256:" + "c".repeat(64)),
        UUID.fromString("44444444-4444-4444-8444-444444444444"),
        9L);
  }

  private static FreshGameSessionTenantAssociation freshTenant() {
    RuntimeTenantIdentityEvidence source =
        new RuntimeTenantIdentityEvidence(
            1,
            NAMESPACE,
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            1L,
            "source-game-tenant-key",
            "NEW_GAME_ROW");
    return new FreshGameSessionTenantAssociation(
        UUID.fromString("33333333-3333-4333-8333-333333333333"), 701L, source);
  }
}
