package net.firedevops.firemud.automationscripting.sourceintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import java.time.OffsetDateTime;
import java.util.UUID;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationReadClient;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.SelectedOwnerWorldInventoryReadClient;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence;
import net.firedevops.firemud.common.security.SessionContext;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AutomationEmptySelectedSourceIntakeValueTest {
  @BeforeEach
  void clearCallerContext() {
    SessionContext.clear();
  }

  @AfterEach
  void clearCallerAndTransactionContext() {
    SessionContext.clear();
    TransactionSynchronizationManager.setActualTransactionActive(false);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void storedReceiptDecoderRejectsMalformedValues() {
    assertThatThrownBy(() -> AutomationEmptySelectedSourceIntakeReceipt.fromStored(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AutomationEmptySelectedSourceIntakeReceipt.fromStored(new byte[0]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> AutomationEmptySelectedSourceIntakeReceipt.fromStored(new byte[] {1, 2, 3}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void intakePreparationTypesAreNotLiveSpringComponents() {
    assertThat(
            AutomationEmptySelectedSourceIntakeRepository.class.isAnnotationPresent(
                Component.class))
        .isFalse();
    assertThat(
            AutomationEmptySelectedSourceIntakeRepository.class.isAnnotationPresent(
                Repository.class))
        .isFalse();
    assertThat(
            AutomationEmptySelectedSourceIntakeService.class.isAnnotationPresent(Component.class))
        .isFalse();
    assertThat(AutomationEmptySelectedSourceIntakeService.class.isAnnotationPresent(Service.class))
        .isFalse();
  }

  @Test
  void retainRejectsMissingAndWrongSameNamespaceWorkloadPeersBeforeInputOrOwnerRead() {
    AutomationEmptySelectedSourceIntakeRepository repository =
        mock(AutomationEmptySelectedSourceIntakeRepository.class);
    SelectedOwnerIntakeAuthorizationReadClient authorizationReadClient =
        mock(SelectedOwnerIntakeAuthorizationReadClient.class);
    SelectedOwnerWorldInventoryReadClient worldInventoryReadClient =
        mock(SelectedOwnerWorldInventoryReadClient.class);
    AutomationEmptySelectedSourceIntakeService service =
        new AutomationEmptySelectedSourceIntakeService(
            repository, authorizationReadClient, worldInventoryReadClient);

    assertThatThrownBy(() -> service.retain("example", null, null))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("Game Design peer");

    GrpcPeerIdentity wrongPeer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/example/sa/account-service", "example", "account-service");
    Context wrongPeerContext = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, wrongPeer);
    Context previous = wrongPeerContext.attach();
    try {
      assertThatThrownBy(() -> service.retain("example", null, null))
          .isInstanceOf(SecurityException.class)
          .hasMessageContaining("same-namespace Game Design peer");
    } finally {
      wrongPeerContext.detach(previous);
    }
    GrpcPeerIdentity correctPeer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/example/sa/game-design-service", "example", "game-design-service");
    Context correctPeerContext =
        Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, correctPeer);
    previous = correctPeerContext.attach();
    try {
      SessionContext.setContext("1001", java.util.List.of(), java.util.Map.of());
      assertThatThrownBy(() -> service.retain("example", null, null))
          .isInstanceOf(SecurityException.class)
          .hasMessageContaining("same-namespace Game Design peer");
    } finally {
      SessionContext.clear();
      correctPeerContext.detach(previous);
    }
    verifyNoInteractions(repository, authorizationReadClient, worldInventoryReadClient);
  }

  @Test
  void retainRejectsAmbientOwnerSqlBeforeReadingAnyOwnerEvidence() {
    AutomationEmptySelectedSourceIntakeRepository repository =
        mock(AutomationEmptySelectedSourceIntakeRepository.class);
    SelectedOwnerIntakeAuthorizationReadClient authorizationReadClient =
        mock(SelectedOwnerIntakeAuthorizationReadClient.class);
    SelectedOwnerWorldInventoryReadClient worldInventoryReadClient =
        mock(SelectedOwnerWorldInventoryReadClient.class);
    AutomationEmptySelectedSourceIntakeService service =
        new AutomationEmptySelectedSourceIntakeService(
            repository, authorizationReadClient, worldInventoryReadClient);
    GrpcPeerIdentity peer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/example/sa/game-design-service", "example", "game-design-service");
    Context peerContext = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = peerContext.attach();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> service.retain("example", null, null))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("outside Automation owner SQL");
    } finally {
      peerContext.detach(previous);
    }
    verifyNoInteractions(repository, authorizationReadClient, worldInventoryReadClient);
  }

  @Test
  void exactCommittedRequestRetryReturnsReceiptBeforeConsultingSettledAuthority() {
    String namespace = "example";
    UUID requestId = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    String requestDigest = "sha256:" + "b".repeat(64);
    SelectedOwnerIntakeAuthorizationBinding binding =
        mock(SelectedOwnerIntakeAuthorizationBinding.class);
    DraftCommitBinding selectedCommit = mock(DraftCommitBinding.class);
    when(binding.targetNamespace()).thenReturn(namespace);
    when(binding.owner()).thenReturn(Owner.AUTOMATION_SCRIPTING);
    when(binding.schema()).thenReturn("account-automation-intake-authorization/v1");
    when(binding.purpose()).thenReturn("AUTOMATION_INTAKE_RETENTION");
    when(binding.intakeRequestId()).thenReturn(requestId);
    when(binding.tenantId()).thenReturn(tenantId);
    when(binding.versionId()).thenReturn(versionId);
    when(binding.selected()).thenReturn(selectedCommit);

    WorldSelectedDraftPublicationFreezeEvidence freeze =
        mock(WorldSelectedDraftPublicationFreezeEvidence.class);
    WorldSelectedDraftPublicationFreezeEvidence.Request freezeRequest =
        mock(WorldSelectedDraftPublicationFreezeEvidence.Request.class);
    AccountPublicationAuthorizationBinding publicationBinding =
        mock(AccountPublicationAuthorizationBinding.class);
    AccountPublicationAuthorizationBinding.PreallocationInput publicationInput =
        mock(AccountPublicationAuthorizationBinding.PreallocationInput.class);
    AuthoredDraftPublishSelectionBinding selection =
        mock(AuthoredDraftPublishSelectionBinding.class);
    when(freeze.request()).thenReturn(freezeRequest);
    when(freezeRequest.targetNamespace()).thenReturn(namespace);
    when(freezeRequest.canonicalTenantId()).thenReturn(tenantId);
    when(freezeRequest.canonicalVersionId()).thenReturn(versionId);
    when(freezeRequest.accountBinding()).thenReturn(publicationBinding);
    when(publicationBinding.input()).thenReturn(publicationInput);
    when(publicationInput.selection()).thenReturn(selection);
    when(selection.selectedCommit()).thenReturn(selectedCommit);

    AutomationEmptySelectedSourceIntakeRepository repository =
        mock(AutomationEmptySelectedSourceIntakeRepository.class);
    SelectedOwnerIntakeAuthorizationReadClient authorizationReadClient =
        mock(SelectedOwnerIntakeAuthorizationReadClient.class);
    SelectedOwnerWorldInventoryReadClient worldInventoryReadClient =
        mock(SelectedOwnerWorldInventoryReadClient.class);
    AutomationEmptySelectedSourceIntakeReceipt receipt =
        mock(AutomationEmptySelectedSourceIntakeReceipt.class);
    when(repository.read(namespace, requestId)).thenReturn(java.util.Optional.of(receipt));
    AutomationEmptySelectedSourceIntakeService service =
        new AutomationEmptySelectedSourceIntakeService(
            repository, authorizationReadClient, worldInventoryReadClient);
    GrpcPeerIdentity peer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/example/sa/game-design-service", "example", "game-design-service");
    Context peerContext = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = peerContext.attach();
    try (MockedStatic<AutomationEmptySelectedSourceIntakeReceipt> receiptStatic =
        mockStatic(AutomationEmptySelectedSourceIntakeReceipt.class)) {
      receiptStatic
          .when(
              () ->
                  AutomationEmptySelectedSourceIntakeReceipt.requestDigest(
                      namespace, binding, freeze))
          .thenReturn(requestDigest);
      assertThat(service.retain(namespace, binding, freeze)).isSameAs(receipt);
    } finally {
      peerContext.detach(previous);
    }
    verify(repository).read(namespace, requestId);
    verify(receipt).requireSameRequest(namespace, binding, freeze);
    verifyNoInteractions(authorizationReadClient, worldInventoryReadClient);
  }

  @Test
  void committedReceiptLookupReturnsTheExactStoredReceiptBytes() {
    DSLContext dsl = mock(DSLContext.class);
    Record record = mock(Record.class);
    String namespace = "example";
    UUID operationId = UUID.randomUUID();
    UUID fenceId = UUID.randomUUID();
    UUID intakeRequestId = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    UUID selectedCommitId = UUID.randomUUID();
    UUID sourceRevisionId = UUID.randomUUID();
    String digest = "sha256:" + "a".repeat(64);
    OffsetDateTime retainedAt = OffsetDateTime.parse("2026-10-10T00:00:00Z");
    byte[] canonicalBytes = new byte[] {1, 2, 3};
    AutomationEmptySelectedSourceIntakeReceipt receipt =
        mock(AutomationEmptySelectedSourceIntakeReceipt.class);
    when(receipt.targetNamespace()).thenReturn(namespace);
    when(receipt.operationId()).thenReturn(operationId);
    when(receipt.fenceId()).thenReturn(fenceId);
    when(receipt.intakeRequestId()).thenReturn(intakeRequestId);
    when(receipt.canonicalTenantId()).thenReturn(tenantId);
    when(receipt.canonicalVersionId()).thenReturn(versionId);
    when(receipt.selectedCommitId()).thenReturn(selectedCommitId);
    when(receipt.sourceRevisionId()).thenReturn(sourceRevisionId);
    when(receipt.sourceRevisionOrder()).thenReturn("7");
    when(receipt.localTenantKey()).thenReturn(9001L);
    when(receipt.localVersionKey()).thenReturn(9002L);
    when(receipt.requestDigest()).thenReturn(digest);
    when(receipt.authorizationBindingDigest()).thenReturn(digest);
    when(receipt.receiptDigest()).thenReturn(digest);
    when(receipt.retainedAt()).thenReturn(retainedAt);
    when(receipt.canonicalBytes()).thenReturn(canonicalBytes);

    when(record.get(0, String.class)).thenReturn(namespace);
    when(record.get(1, UUID.class)).thenReturn(operationId);
    when(record.get(2, UUID.class)).thenReturn(fenceId);
    when(record.get(3, UUID.class)).thenReturn(intakeRequestId);
    when(record.get(4, UUID.class)).thenReturn(tenantId);
    when(record.get(5, UUID.class)).thenReturn(versionId);
    when(record.get(6, UUID.class)).thenReturn(selectedCommitId);
    when(record.get(7, UUID.class)).thenReturn(sourceRevisionId);
    when(record.get(8, String.class)).thenReturn("7");
    when(record.get(9, Long.class)).thenReturn(9001L);
    when(record.get(10, Long.class)).thenReturn(9002L);
    when(record.get(11, String.class)).thenReturn(digest);
    when(record.get(12, String.class)).thenReturn(digest);
    when(record.get(13, String.class)).thenReturn(digest);
    when(record.get(14, OffsetDateTime.class)).thenReturn(retainedAt);
    when(record.get(15, String.class)).thenReturn(digest);
    when(record.get(16, String.class)).thenReturn(digest);
    when(record.get(17, byte[].class)).thenReturn(canonicalBytes);
    when(record.get(18, OffsetDateTime.class)).thenReturn(retainedAt);
    when(dsl.fetchOne(anyString(), any(Object[].class))).thenReturn(record);

    try (MockedStatic<AutomationEmptySelectedSourceIntakeReceipt> decoder =
        mockStatic(AutomationEmptySelectedSourceIntakeReceipt.class)) {
      decoder
          .when(() -> AutomationEmptySelectedSourceIntakeReceipt.fromStored(canonicalBytes))
          .thenReturn(receipt);
      var result =
          new AutomationEmptySelectedSourceIntakeRepository(dsl).read(namespace, intakeRequestId);
      assertThat(result).isPresent();
      assertThat(result.orElseThrow()).isSameAs(receipt);
    }
  }
}
