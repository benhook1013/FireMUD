package unit.net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.firedevops.firemud.common.tenant.GameSessionTenantAssociationEvidence;
import net.firedevops.firemud.gamesession.client.GameDesignRuntimeTenantIdentityClient;
import net.firedevops.firemud.gamesession.client.GameDesignRuntimeTenantIdentityClient.LegacyGameSessionTenantAssociationReceipt;
import net.firedevops.firemud.gamesession.repository.GameSessionRetainedTenantAssociationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionRetainedTenantAssociationRepository.AssociationReceipt;
import net.firedevops.firemud.gamesession.service.GameSessionRetainedTenantAssociationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GameSessionRetainedTenantAssociationServiceTest {
  private static final String NAMESPACE = "firemud";
  private static final UUID REQUEST = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID TENANT = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID APPROVAL_OPERATION =
      UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID OTHER_TENANT = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final String LEGACY_KEY = "42";

  private final GameDesignRuntimeTenantIdentityClient client =
      mock(GameDesignRuntimeTenantIdentityClient.class);
  private final GameSessionRetainedTenantAssociationRepository repository =
      mock(GameSessionRetainedTenantAssociationRepository.class);
  private final PlatformTransactionManager transactionManager =
      mock(PlatformTransactionManager.class);
  private final GameSessionRetainedTenantAssociationService service =
      new GameSessionRetainedTenantAssociationService(
          client, repository, transactionManager, NAMESPACE);

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void authenticatesOwnerReceiptBeforeStartingReadCommittedRequiresNewTransaction() {
    LegacyGameSessionTenantAssociationReceipt ownerReceipt = receipt(NAMESPACE, TENANT, 42L);
    AssociationReceipt localReceipt = mock(AssociationReceipt.class);
    when(client.resolveLegacyGameSessionTenantAssociation(
            eq(TENANT.toString()), eq(APPROVAL_OPERATION.toString()), eq(LEGACY_KEY), anyString()))
        .thenReturn(ownerReceipt);
    when(transactionManager.getTransaction(any(TransactionDefinition.class)))
        .thenReturn(new SimpleTransactionStatus());
    when(repository.register(REQUEST, ownerReceipt)).thenReturn(localReceipt);

    assertThat(
            service.associate(
                REQUEST.toString(), TENANT.toString(), APPROVAL_OPERATION.toString(), LEGACY_KEY))
        .isSameAs(localReceipt);

    InOrder ordered = inOrder(client, transactionManager, repository);
    ordered
        .verify(client)
        .resolveLegacyGameSessionTenantAssociation(
            eq(TENANT.toString()), eq(APPROVAL_OPERATION.toString()), eq(LEGACY_KEY), anyString());
    ArgumentCaptor<TransactionDefinition> definition =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    ordered.verify(transactionManager).getTransaction(definition.capture());
    ordered.verify(repository).register(REQUEST, ownerReceipt);
    ordered.verify(transactionManager).commit(any(TransactionStatus.class));
    assertThat(definition.getValue().getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    assertThat(definition.getValue().getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);

    ArgumentCaptor<String> readRequest = ArgumentCaptor.forClass(String.class);
    verify(client)
        .resolveLegacyGameSessionTenantAssociation(
            eq(TENANT.toString()),
            eq(APPROVAL_OPERATION.toString()),
            eq(LEGACY_KEY),
            readRequest.capture());
    UUID readRequestId = UUID.fromString(readRequest.getValue());
    assertThat(readRequestId.toString()).isEqualTo(readRequest.getValue());
    assertThat(readRequestId).isNotEqualTo(REQUEST).isNotEqualTo(APPROVAL_OPERATION);
  }

  @Test
  void rejectsMalformedCallerBindingBeforeOwnerReadOrTransaction() {
    List<String[]> malformed =
        List.of(
            new String[] {null, TENANT.toString(), APPROVAL_OPERATION.toString(), LEGACY_KEY},
            new String[] {
              new UUID(0L, 0L).toString(),
              TENANT.toString(),
              APPROVAL_OPERATION.toString(),
              LEGACY_KEY
            },
            new String[] {
              "abcdefab-cdef-4abc-8def-abcdefabcdef".toUpperCase(Locale.ROOT),
              TENANT.toString(),
              APPROVAL_OPERATION.toString(),
              LEGACY_KEY
            },
            new String[] {
              REQUEST.toString(), "not-a-uuid", APPROVAL_OPERATION.toString(), LEGACY_KEY
            },
            new String[] {
              REQUEST.toString(),
              new UUID(0L, 0L).toString(),
              APPROVAL_OPERATION.toString(),
              LEGACY_KEY
            },
            new String[] {
              REQUEST.toString(),
              TENANT.toString(),
              "00000000-0000-0000-0000-000000000000",
              LEGACY_KEY
            },
            new String[] {
              REQUEST.toString(), TENANT.toString(), APPROVAL_OPERATION.toString(), "0"
            },
            new String[] {
              REQUEST.toString(), TENANT.toString(), APPROVAL_OPERATION.toString(), "042"
            },
            new String[] {
              REQUEST.toString(),
              TENANT.toString(),
              APPROVAL_OPERATION.toString(),
              "9223372036854775808"
            });

    for (String[] arguments : malformed) {
      assertThatThrownBy(
              () -> service.associate(arguments[0], arguments[1], arguments[2], arguments[3]))
          .isInstanceOf(IllegalArgumentException.class);
    }
    verifyNoInteractions(client, transactionManager, repository);
  }

  @Test
  void rejectsDirectInvocationInsideActualTransactionBeforeSourceRead() {
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(
            () ->
                service.associate(
                    REQUEST.toString(),
                    TENANT.toString(),
                    APPROVAL_OPERATION.toString(),
                    LEGACY_KEY))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("outside an owner transaction");

    verifyNoInteractions(client, transactionManager, repository);
  }

  @Test
  void rejectsContradictoryOwnerReceiptBeforeTransactionStart() {
    when(client.resolveLegacyGameSessionTenantAssociation(
            anyString(), anyString(), anyString(), anyString()))
        .thenReturn(receipt("other-namespace", TENANT, 42L))
        .thenReturn(receipt(NAMESPACE, OTHER_TENANT, 42L))
        .thenReturn(
            receipt(
                NAMESPACE, UUID.fromString("66666666-6666-4666-8666-666666666666"), TENANT, 42L))
        .thenReturn(receipt(NAMESPACE, TENANT, 43L));

    for (int attempt = 0; attempt < 4; attempt++) {
      assertThatThrownBy(
              () ->
                  service.associate(
                      REQUEST.toString(),
                      TENANT.toString(),
                      APPROVAL_OPERATION.toString(),
                      LEGACY_KEY))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("does not match the exact association");
    }

    verify(client, times(4))
        .resolveLegacyGameSessionTenantAssociation(
            eq(TENANT.toString()), eq(APPROVAL_OPERATION.toString()), eq(LEGACY_KEY), anyString());
    verifyNoInteractions(transactionManager, repository);
  }

  @Test
  void sourceFailureDoesNotStartTransactionOrCreateLocalState() {
    when(client.resolveLegacyGameSessionTenantAssociation(
            anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new IllegalStateException("owner unavailable"));

    assertThatThrownBy(
            () ->
                service.associate(
                    REQUEST.toString(),
                    TENANT.toString(),
                    APPROVAL_OPERATION.toString(),
                    LEGACY_KEY))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("owner unavailable");

    verifyNoInteractions(transactionManager, repository);
  }

  @Test
  void associationCandidateIsNotRegisteredAsSpringComponent() {
    assertThat(
            AnnotatedElementUtils.hasAnnotation(
                GameSessionRetainedTenantAssociationService.class, Component.class))
        .isFalse();
    assertThat(
            AnnotatedElementUtils.hasAnnotation(
                GameSessionRetainedTenantAssociationRepository.class, Component.class))
        .isFalse();
  }

  private static LegacyGameSessionTenantAssociationReceipt receipt(
      String namespace, UUID canonicalTenantId, long legacyKey) {
    return receipt(namespace, APPROVAL_OPERATION, canonicalTenantId, legacyKey);
  }

  private static LegacyGameSessionTenantAssociationReceipt receipt(
      String namespace, UUID operationId, UUID canonicalTenantId, long legacyKey) {
    GameSessionTenantAssociationEvidence evidence =
        new GameSessionTenantAssociationEvidence(
            2,
            operationId,
            namespace,
            "fixture-key",
            "approved-by-test",
            "approval-reference-1",
            "2026-01-01T00:00:00Z",
            "2025-12-31T23:00:00Z",
            Long.toString(legacyKey),
            canonicalTenantId,
            "501",
            "source-game-501",
            "RETAINED_GAME_V29",
            "sha256:" + "b".repeat(64),
            "sha256:" + "a".repeat(64));
    String signature = Base64.getEncoder().encodeToString(new byte[64]);
    return new LegacyGameSessionTenantAssociationReceipt(
        evidence, evidence.manifestDigest(), signature);
  }
}
