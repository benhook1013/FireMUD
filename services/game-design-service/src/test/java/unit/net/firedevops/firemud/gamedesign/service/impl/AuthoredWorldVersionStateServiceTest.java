package net.firedevops.firemud.gamedesign.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository.AuthoredWorldVersionStateSnapshot;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class AuthoredWorldVersionStateServiceTest {
  private static final String NAMESPACE = "test";
  private static final UUID READ_REQUEST_ID = uuid("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT_ID = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID SOURCE_OPERATION_ID = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID REGISTRATION_REQUEST_ID = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID CANONICAL_VERSION_ID = uuid("55555555-5555-4555-8555-555555555555");

  @Test
  void readsCompleteSourceAndVersionInsideFreshReadOnlyRepeatableReadTransaction() {
    RecordingTransactionManager transactionManager = new RecordingTransactionManager();
    GameAuthoredWorldSourceRepository repository = mock(GameAuthoredWorldSourceRepository.class);
    AuthoredWorldSourceEvidence source = sourceEvidence();
    when(repository.readVersionStateSnapshot(
            NAMESPACE,
            READ_REQUEST_ID,
            TENANT_ID,
            "cafe-coast",
            SOURCE_OPERATION_ID,
            source.evidenceDigest(),
            19L))
        .thenReturn(
            Optional.of(
                new AuthoredWorldVersionStateSnapshot(
                    source,
                    CANONICAL_VERSION_ID,
                    net.firedevops.firemud.gamedesign.model.VersionLifecycleState.PUBLISHED,
                    7L)));
    AuthoredWorldVersionStateService service =
        new AuthoredWorldVersionStateService(transactionManager, repository);

    AuthoredWorldVersionStateEvidence evidence = service.read(request(source.evidenceDigest()));

    assertThat(evidence.sourceEvidence()).isEqualTo(source);
    assertThat(evidence.canonicalVersionId()).isEqualTo(CANONICAL_VERSION_ID);
    assertThat(evidence.versionState())
        .isEqualTo(VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED);
    assertThat(evidence.versionStateEpoch()).isEqualTo(7L);
    assertThat(transactionManager.definition).isNotNull();
    assertThat(transactionManager.definition.getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    assertThat(transactionManager.definition.getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    assertThat(transactionManager.definition.isReadOnly()).isTrue();
    verify(repository)
        .readVersionStateSnapshot(
            NAMESPACE,
            READ_REQUEST_ID,
            TENANT_ID,
            "cafe-coast",
            SOURCE_OPERATION_ID,
            source.evidenceDigest(),
            19L);
  }

  @Test
  void missingExactSourceOrVersionFailsClosed() {
    RecordingTransactionManager transactionManager = new RecordingTransactionManager();
    GameAuthoredWorldSourceRepository repository = mock(GameAuthoredWorldSourceRepository.class);
    when(repository.readVersionStateSnapshot(
            eq(NAMESPACE),
            eq(READ_REQUEST_ID),
            eq(TENANT_ID),
            eq("cafe-coast"),
            eq(SOURCE_OPERATION_ID),
            any(String.class),
            eq(19L)))
        .thenReturn(Optional.empty());
    AuthoredWorldVersionStateService service =
        new AuthoredWorldVersionStateService(transactionManager, repository);

    assertThatThrownBy(() -> service.read(request("sha256:" + "a".repeat(64))))
        .isInstanceOf(AuthoredWorldVersionStateService.NotFoundException.class)
        .hasMessageContaining("exact requested owner tuple");
  }

  @Test
  void repositoryOwnerConsistencyFailuresDoNotBecomeEvidence() {
    RecordingTransactionManager transactionManager = new RecordingTransactionManager();
    GameAuthoredWorldSourceRepository repository = mock(GameAuthoredWorldSourceRepository.class);
    when(repository.readVersionStateSnapshot(
            eq(NAMESPACE),
            eq(READ_REQUEST_ID),
            eq(TENANT_ID),
            eq("cafe-coast"),
            eq(SOURCE_OPERATION_ID),
            any(String.class),
            eq(19L)))
        .thenThrow(
            new GameAuthoredWorldSourceRepository.InvalidSourceEvidenceException(
                "source binding changed"));
    AuthoredWorldVersionStateService service =
        new AuthoredWorldVersionStateService(transactionManager, repository);

    assertThatThrownBy(() -> service.read(request("sha256:" + "a".repeat(64))))
        .isInstanceOf(GameAuthoredWorldSourceRepository.InvalidSourceEvidenceException.class)
        .hasMessageContaining("source binding changed");
  }

  @Test
  void missingOrNilPersistedCanonicalVersionIdFailsClosed() {
    for (UUID canonicalVersionId : new UUID[] {null, new UUID(0L, 0L)}) {
      RecordingTransactionManager transactionManager = new RecordingTransactionManager();
      GameAuthoredWorldSourceRepository repository = mock(GameAuthoredWorldSourceRepository.class);
      AuthoredWorldSourceEvidence source = sourceEvidence();
      when(repository.readVersionStateSnapshot(
              NAMESPACE,
              READ_REQUEST_ID,
              TENANT_ID,
              "cafe-coast",
              SOURCE_OPERATION_ID,
              source.evidenceDigest(),
              19L))
          .thenReturn(
              Optional.of(
                  new AuthoredWorldVersionStateSnapshot(
                      source,
                      canonicalVersionId,
                      net.firedevops.firemud.gamedesign.model.VersionLifecycleState.PUBLISHED,
                      7L)));
      AuthoredWorldVersionStateService service =
          new AuthoredWorldVersionStateService(transactionManager, repository);

      if (canonicalVersionId == null) {
        assertThatThrownBy(() -> service.read(request(source.evidenceDigest())))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("canonicalVersionId");
      } else {
        assertThatThrownBy(() -> service.read(request(source.evidenceDigest())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("non-nil UUID");
      }
    }
  }

  private static AuthoredWorldVersionStateEvidence.Request request(String sourceDigest) {
    return new AuthoredWorldVersionStateEvidence.Request(
        1,
        NAMESPACE,
        READ_REQUEST_ID,
        TENANT_ID,
        "cafe-coast",
        SOURCE_OPERATION_ID,
        sourceDigest,
        19L);
  }

  private static AuthoredWorldSourceEvidence sourceEvidence() {
    String displayName = "Café 🐉";
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, REGISTRATION_REQUEST_ID, TENANT_ID, "tenant-one", "cafe-coast", displayName);
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        REGISTRATION_REQUEST_ID,
        SOURCE_OPERATION_ID,
        requestDigest,
        TENANT_ID,
        "tenant-one",
        "cafe-coast",
        displayName,
        42L,
        "game-owner-tenant",
        "NEW_GAME_ROW",
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            REGISTRATION_REQUEST_ID,
            SOURCE_OPERATION_ID,
            requestDigest,
            TENANT_ID,
            "tenant-one",
            "cafe-coast",
            displayName,
            42L,
            "game-owner-tenant",
            "NEW_GAME_ROW"));
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static final class RecordingTransactionManager
      extends AbstractPlatformTransactionManager {
    private TransactionDefinition definition;

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition transactionDefinition) {
      definition = transactionDefinition;
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }
}
