package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceClient;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceGrpcCodec.ReadRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorldAuthoredSourceIntakeServiceTest {
  private static final String NAMESPACE = "firemud";
  private static final UUID INTAKE_REQUEST =
      UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID TENANT = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID SOURCE_OPERATION =
      UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID READ_REQUEST = UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final String WORLD = "violet-wilds";

  private final AuthoredWorldSourceClient client = mock(AuthoredWorldSourceClient.class);
  private final WorldAuthoredSourceIntakeRepository repository =
      mock(WorldAuthoredSourceIntakeRepository.class);
  private final RecordingTransactionManager transactionManager = new RecordingTransactionManager();
  private final WorldAuthoredSourceIntakeService service =
      new WorldAuthoredSourceIntakeService(client, repository, transactionManager, NAMESPACE);

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void requiresExactAuthenticatedGameDesignPeerBeforeOwnerAccess() {
    assertThatThrownBy(
            () ->
                withoutPeer(
                    () ->
                        service.intake(
                            INTAKE_REQUEST, TENANT, WORLD, SOURCE_OPERATION, digest('a'))))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(
            () ->
                withPeer(
                    peer("firemud", "game-session-service"),
                    () ->
                        service.intake(
                            INTAKE_REQUEST, TENANT, WORLD, SOURCE_OPERATION, digest('a'))))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(
            () ->
                withPeer(
                    peer("other", "game-design-service"),
                    () ->
                        service.intake(
                            INTAKE_REQUEST, TENANT, WORLD, SOURCE_OPERATION, digest('a'))))
        .isInstanceOf(SecurityException.class);

    verifyNoInteractions(client, repository);
  }

  @Test
  void validatesAllRequestSelectorsAndDigestBeforeOwnerAccess() {
    withGameDesign(
        () -> {
          assertThatThrownBy(
                  () -> service.intake(null, TENANT, WORLD, SOURCE_OPERATION, digest('a')))
              .isInstanceOf(IllegalArgumentException.class);
          assertThatThrownBy(
                  () ->
                      service.intake(
                          new UUID(0L, 0L), TENANT, WORLD, SOURCE_OPERATION, digest('a')))
              .isInstanceOf(IllegalArgumentException.class);
          assertThatThrownBy(
                  () -> service.intake(INTAKE_REQUEST, null, WORLD, SOURCE_OPERATION, digest('a')))
              .isInstanceOf(IllegalArgumentException.class);
          assertThatThrownBy(
                  () ->
                      service.intake(
                          INTAKE_REQUEST, new UUID(0L, 0L), WORLD, SOURCE_OPERATION, digest('a')))
              .isInstanceOf(IllegalArgumentException.class);
          assertThatThrownBy(
                  () ->
                      service.intake(
                          INTAKE_REQUEST, TENANT, "Bad-Slug", SOURCE_OPERATION, digest('a')))
              .isInstanceOf(IllegalArgumentException.class);
          assertThatThrownBy(
                  () ->
                      service.intake(INTAKE_REQUEST, TENANT, WORLD, new UUID(0L, 0L), digest('a')))
              .isInstanceOf(IllegalArgumentException.class);
          assertThatThrownBy(
                  () ->
                      service.intake(INTAKE_REQUEST, TENANT, WORLD, SOURCE_OPERATION, "sha256:bad"))
              .isInstanceOf(IllegalArgumentException.class);
          assertThatThrownBy(
                  () ->
                      service.intake(
                          2,
                          NAMESPACE,
                          INTAKE_REQUEST,
                          TENANT,
                          WORLD,
                          SOURCE_OPERATION,
                          digest('a')))
              .isInstanceOf(IllegalArgumentException.class);
          assertThatThrownBy(
                  () ->
                      service.intake(
                          1, "other", INTAKE_REQUEST, TENANT, WORLD, SOURCE_OPERATION, digest('a')))
              .isInstanceOf(SecurityException.class);
          return null;
        });

    verifyNoInteractions(client, repository);
  }

  @Test
  void deniesWritableAmbientTransactionBeforeReadingOrWriting() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);

    assertThatThrownBy(
            () ->
                withGameDesign(
                    () ->
                        service.intake(
                            INTAKE_REQUEST, TENANT, WORLD, SOURCE_OPERATION, digest('a'))))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(client, repository);
  }

  @Test
  void deniesReadOnlyAmbientTransactionBeforeReadingOrWriting() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

    assertThatThrownBy(
            () ->
                withGameDesign(
                    () ->
                        service.intake(
                            INTAKE_REQUEST, TENANT, WORLD, SOURCE_OPERATION, digest('a'))))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(client, repository);
  }

  @Test
  void exactStoredRetryReturnsOriginalReceiptWithoutCallingGameDesign() {
    AuthoredWorldSourceEvidence source = source(NAMESPACE, TENANT, SOURCE_OPERATION, WORLD);
    WorldAuthoredSourceIntakeReceipt receipt = receipt(source, 9001L);
    when(repository.read(NAMESPACE, INTAKE_REQUEST)).thenReturn(Optional.of(receipt));

    WorldAuthoredSourceIntakeReceipt result =
        withGameDesign(
            () ->
                service.intake(
                    INTAKE_REQUEST, TENANT, WORLD, SOURCE_OPERATION, source.evidenceDigest()));

    assertThat(result).isSameAs(receipt);
    verify(client, never()).read(any(ReadRequest.class));
    verify(repository, never()).acceptFresh(any(), any(), any());
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void exactCommittedReadReturnsWorldReceiptWithoutSourceReadOrAllocation() {
    AuthoredWorldSourceEvidence source = source(NAMESPACE, TENANT, SOURCE_OPERATION, WORLD);
    WorldAuthoredSourceIntakeReceipt receipt = receipt(source, 9001L);
    when(repository.read(NAMESPACE, INTAKE_REQUEST)).thenReturn(Optional.of(receipt));

    Optional<WorldAuthoredSourceIntakeReceipt> result =
        withGameDesign(
            () ->
                service.readCommittedReceipt(
                    1,
                    NAMESPACE,
                    READ_REQUEST,
                    INTAKE_REQUEST,
                    TENANT,
                    WORLD,
                    SOURCE_OPERATION,
                    source.evidenceDigest()));

    assertThat(result).containsSame(receipt);
    verify(repository).read(NAMESPACE, INTAKE_REQUEST);
    verify(repository, never()).acceptFresh(any(), any(), any());
    verifyNoInteractions(client);
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void committedReadByIdReturnsFullExactReceiptWithoutSourceReadOrMutation() {
    AuthoredWorldSourceEvidence source = source(NAMESPACE, TENANT, SOURCE_OPERATION, WORLD);
    WorldAuthoredSourceIntakeReceipt receipt = receipt(source, 9001L);
    when(repository.read(NAMESPACE, INTAKE_REQUEST)).thenReturn(Optional.of(receipt));

    Optional<WorldAuthoredSourceIntakeReceipt> result =
        withGameDesign(
            () ->
                service.readCommittedReceiptById(
                    1, NAMESPACE, READ_REQUEST, INTAKE_REQUEST, TENANT));

    assertThat(result).containsSame(receipt);
    verify(repository).read(NAMESPACE, INTAKE_REQUEST);
    verify(repository, never()).acceptFresh(any(), any(), any());
    verifyNoInteractions(client);
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void committedReadByIdRejectsWrongPeerCallerContextAndInvalidScopeBeforeOwnerAccess() {
    assertThatThrownBy(
            () ->
                withoutPeer(
                    () ->
                        service.readCommittedReceiptById(
                            1, NAMESPACE, READ_REQUEST, INTAKE_REQUEST, TENANT)))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(
            () ->
                withPeer(
                    peer(NAMESPACE, "game-session-service"),
                    () ->
                        service.readCommittedReceiptById(
                            1, NAMESPACE, READ_REQUEST, INTAKE_REQUEST, TENANT)))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(
            () ->
                withGameDesign(
                    () ->
                        withCallerContext(
                            () ->
                                service.readCommittedReceiptById(
                                    1, NAMESPACE, READ_REQUEST, INTAKE_REQUEST, TENANT))))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(
            () ->
                withGameDesign(
                    () ->
                        service.readCommittedReceiptById(
                            1, "other", READ_REQUEST, INTAKE_REQUEST, TENANT)))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(
            () ->
                withGameDesign(
                    () ->
                        service.readCommittedReceiptById(
                            2, NAMESPACE, READ_REQUEST, INTAKE_REQUEST, TENANT)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                withGameDesign(
                    () ->
                        service.readCommittedReceiptById(
                            1, NAMESPACE, INTAKE_REQUEST, INTAKE_REQUEST, TENANT)))
        .isInstanceOf(IllegalArgumentException.class);

    verifyNoInteractions(client, repository);
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void committedReadByIdReturnsAbsentAndRejectsSubstitutedTenantWithoutMutation() {
    when(repository.read(NAMESPACE, INTAKE_REQUEST)).thenReturn(Optional.empty());
    Optional<WorldAuthoredSourceIntakeReceipt> missing =
        withGameDesign(
            () ->
                service.readCommittedReceiptById(
                    1, NAMESPACE, READ_REQUEST, INTAKE_REQUEST, TENANT));
    assertThat(missing).isEmpty();

    AuthoredWorldSourceEvidence source = source(NAMESPACE, TENANT, SOURCE_OPERATION, WORLD);
    when(repository.read(NAMESPACE, INTAKE_REQUEST))
        .thenReturn(Optional.of(receipt(source, 9001L)));
    assertThatThrownBy(
            () ->
                withGameDesign(
                    () ->
                        service.readCommittedReceiptById(
                            1,
                            NAMESPACE,
                            READ_REQUEST,
                            INTAKE_REQUEST,
                            UUID.fromString("77777777-7777-4777-8777-777777777777"))))
        .isInstanceOf(WorldAuthoredSourceIntakeRepository.RegistrationConflictException.class);

    verify(repository, org.mockito.Mockito.times(2)).read(NAMESPACE, INTAKE_REQUEST);
    verify(repository, never()).acceptFresh(any(), any(), any());
    verifyNoInteractions(client);
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void exactCommittedReadRejectsWrongPeerNamespaceAndChangedReceiptBindingBeforeOwnerAccess() {
    AuthoredWorldSourceEvidence source = source(NAMESPACE, TENANT, SOURCE_OPERATION, WORLD);
    WorldAuthoredSourceIntakeReceipt receipt = receipt(source, 9001L);
    when(repository.read(NAMESPACE, INTAKE_REQUEST)).thenReturn(Optional.of(receipt));

    assertThatThrownBy(
            () ->
                withoutPeer(
                    () ->
                        service.readCommittedReceipt(
                            1,
                            NAMESPACE,
                            READ_REQUEST,
                            INTAKE_REQUEST,
                            TENANT,
                            WORLD,
                            SOURCE_OPERATION,
                            source.evidenceDigest())))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(
            () ->
                withPeer(
                    peer(NAMESPACE, "game-session-service"),
                    () ->
                        service.readCommittedReceipt(
                            1,
                            NAMESPACE,
                            READ_REQUEST,
                            INTAKE_REQUEST,
                            TENANT,
                            WORLD,
                            SOURCE_OPERATION,
                            source.evidenceDigest())))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(
            () ->
                withGameDesign(
                    () ->
                        service.readCommittedReceipt(
                            1,
                            "other",
                            READ_REQUEST,
                            INTAKE_REQUEST,
                            TENANT,
                            WORLD,
                            SOURCE_OPERATION,
                            source.evidenceDigest())))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(
            () ->
                withGameDesign(
                    () ->
                        service.readCommittedReceipt(
                            1,
                            NAMESPACE,
                            INTAKE_REQUEST,
                            INTAKE_REQUEST,
                            TENANT,
                            WORLD,
                            SOURCE_OPERATION,
                            source.evidenceDigest())))
        .isInstanceOf(IllegalArgumentException.class);

    verifyNoInteractions(client, repository);
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void exactCommittedReadReturnsAbsentWithoutMutationWhenNoReceiptExists() {
    when(repository.read(NAMESPACE, INTAKE_REQUEST)).thenReturn(Optional.empty());

    Optional<WorldAuthoredSourceIntakeReceipt> result =
        withGameDesign(
            () ->
                service.readCommittedReceipt(
                    1,
                    NAMESPACE,
                    READ_REQUEST,
                    INTAKE_REQUEST,
                    TENANT,
                    WORLD,
                    SOURCE_OPERATION,
                    digest('a')));

    assertThat(result).isEmpty();
    verify(repository).read(NAMESPACE, INTAKE_REQUEST);
    verify(repository, never()).acceptFresh(any(), any(), any());
    verifyNoInteractions(client);
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void changedRequestScopeOrExpectedDigestConflictsBeforeRemoteReadOrMutation() {
    AuthoredWorldSourceEvidence source = source(NAMESPACE, TENANT, SOURCE_OPERATION, WORLD);
    when(repository.read(NAMESPACE, INTAKE_REQUEST))
        .thenReturn(Optional.of(receipt(source, 9001L)));

    assertThatThrownBy(
            () ->
                withGameDesign(
                    () ->
                        service.intake(
                            INTAKE_REQUEST,
                            UUID.fromString("66666666-6666-4666-8666-666666666666"),
                            WORLD,
                            SOURCE_OPERATION,
                            source.evidenceDigest())))
        .isInstanceOf(WorldAuthoredSourceIntakeRepository.RegistrationConflictException.class);
    assertThatThrownBy(
            () ->
                withGameDesign(
                    () ->
                        service.intake(
                            INTAKE_REQUEST,
                            TENANT,
                            "amber-coast",
                            SOURCE_OPERATION,
                            source.evidenceDigest())))
        .isInstanceOf(WorldAuthoredSourceIntakeRepository.RegistrationConflictException.class);
    assertThatThrownBy(
            () ->
                withGameDesign(
                    () ->
                        service.intake(
                            INTAKE_REQUEST,
                            TENANT,
                            WORLD,
                            UUID.fromString("77777777-7777-4777-8777-777777777777"),
                            source.evidenceDigest())))
        .isInstanceOf(WorldAuthoredSourceIntakeRepository.RegistrationConflictException.class);
    assertThatThrownBy(
            () ->
                withGameDesign(
                    () ->
                        service.intake(
                            INTAKE_REQUEST, TENANT, WORLD, SOURCE_OPERATION, digest('b'))))
        .isInstanceOf(WorldAuthoredSourceIntakeRepository.RegistrationConflictException.class);

    verify(client, never()).read(any(ReadRequest.class));
    verify(repository, never()).acceptFresh(any(), any(), any());
  }

  @Test
  void sourceUnavailableCannotEnterTheOwnerWriteTransaction() {
    when(repository.read(NAMESPACE, INTAKE_REQUEST)).thenReturn(Optional.empty());
    when(client.read(any(ReadRequest.class)))
        .thenThrow(new IllegalStateException("source unavailable"));

    assertThatThrownBy(
            () ->
                withGameDesign(
                    () ->
                        service.intake(
                            INTAKE_REQUEST, TENANT, WORLD, SOURCE_OPERATION, digest('a'))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("source unavailable");

    verify(repository).read(NAMESPACE, INTAKE_REQUEST);
    verify(repository, never()).acceptFresh(any(), any(), any());
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void changedAuthenticatedSourceCannotEnterTheOwnerWriteTransaction() {
    when(repository.read(NAMESPACE, INTAKE_REQUEST)).thenReturn(Optional.empty());
    AuthoredWorldSourceEvidence[] substitutedSources = {
      source("other", TENANT, SOURCE_OPERATION, WORLD),
      source(
          NAMESPACE,
          UUID.fromString("66666666-6666-4666-8666-666666666666"),
          SOURCE_OPERATION,
          WORLD),
      source(NAMESPACE, TENANT, UUID.fromString("77777777-7777-4777-8777-777777777777"), WORLD),
      source(NAMESPACE, TENANT, SOURCE_OPERATION, "amber-coast")
    };
    for (AuthoredWorldSourceEvidence substituted : substitutedSources) {
      when(client.read(any(ReadRequest.class))).thenReturn(substituted);

      assertThatThrownBy(
              () ->
                  withGameDesign(
                      () ->
                          service.intake(
                              INTAKE_REQUEST,
                              TENANT,
                              WORLD,
                              SOURCE_OPERATION,
                              substituted.evidenceDigest())))
          .isInstanceOf(WorldAuthoredSourceIntakeRepository.InvalidIntakeEvidenceException.class);
    }

    verify(repository, never()).acceptFresh(any(), any(), any());
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void callerDigestMustMatchFreshAuthenticatedEvidenceBeforeOpeningTransaction() {
    AuthoredWorldSourceEvidence source = source(NAMESPACE, TENANT, SOURCE_OPERATION, WORLD);
    when(repository.read(NAMESPACE, INTAKE_REQUEST)).thenReturn(Optional.empty());
    when(client.read(any(ReadRequest.class))).thenReturn(source);

    assertThatThrownBy(
            () ->
                withGameDesign(
                    () ->
                        service.intake(
                            INTAKE_REQUEST, TENANT, WORLD, SOURCE_OPERATION, digest('b'))))
        .isInstanceOf(WorldAuthoredSourceIntakeRepository.InvalidIntakeEvidenceException.class);

    verify(repository, never()).acceptFresh(any(), any(), any());
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void readsFreshSourceBeforeWritableReadCommittedTransactionAndVerifiesFullCommitReadback() {
    AuthoredWorldSourceEvidence source = source(NAMESPACE, TENANT, SOURCE_OPERATION, WORLD);
    WorldAuthoredSourceIntakeReceipt receipt = receipt(source, 9001L);
    AtomicInteger readCount = new AtomicInteger();
    when(repository.read(NAMESPACE, INTAKE_REQUEST))
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return readCount.incrementAndGet() == 1 ? Optional.empty() : Optional.of(receipt);
            });
    when(client.read(any(ReadRequest.class)))
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return source;
            });
    when(repository.acceptFresh(NAMESPACE, INTAKE_REQUEST, source))
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                  .isFalse();
              return receipt;
            });

    WorldAuthoredSourceIntakeReceipt result =
        withGameDesign(
            () ->
                service.intake(
                    INTAKE_REQUEST, TENANT, WORLD, SOURCE_OPERATION, source.evidenceDigest()));

    assertThat(result).isEqualTo(receipt);
    assertThat(readCount.get()).isEqualTo(2);
    assertThat(transactionManager.startedWith.getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
    assertThat(transactionManager.startedWith.isReadOnly()).isFalse();
    assertThat(transactionManager.startedWith.getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    ArgumentCaptor<ReadRequest> readRequest = ArgumentCaptor.forClass(ReadRequest.class);
    verify(client).read(readRequest.capture());
    assertThat(readRequest.getValue().targetNamespace()).isEqualTo(NAMESPACE);
    assertThat(readRequest.getValue().requestId())
        .isNotEqualTo(INTAKE_REQUEST)
        .isNotEqualTo(new UUID(0L, 0L));
    assertThat(readRequest.getValue().operationId()).isEqualTo(SOURCE_OPERATION);
    assertThat(readRequest.getValue().canonicalTenantId()).isEqualTo(TENANT);
    assertThat(readRequest.getValue().worldSlug()).isEqualTo(WORLD);
    verify(repository).acceptFresh(NAMESPACE, INTAKE_REQUEST, source);
  }

  @Test
  void deniesMissingPostCommitReadback() {
    AuthoredWorldSourceEvidence source = source(NAMESPACE, TENANT, SOURCE_OPERATION, WORLD);
    when(repository.read(NAMESPACE, INTAKE_REQUEST)).thenReturn(Optional.empty(), Optional.empty());
    when(client.read(any(ReadRequest.class))).thenReturn(source);
    when(repository.acceptFresh(NAMESPACE, INTAKE_REQUEST, source))
        .thenReturn(receipt(source, 9001L));

    assertThatThrownBy(
            () ->
                withGameDesign(
                    () ->
                        service.intake(
                            INTAKE_REQUEST,
                            TENANT,
                            WORLD,
                            SOURCE_OPERATION,
                            source.evidenceDigest())))
        .isInstanceOf(WorldAuthoredSourceIntakeRepository.InvalidIntakeEvidenceException.class)
        .hasMessageContaining("missing after commit readback");

    verify(repository).acceptFresh(NAMESPACE, INTAKE_REQUEST, source);
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
  }

  @Test
  void deniesChangedPostCommitLocalSelectorReadback() {
    AuthoredWorldSourceEvidence source = source(NAMESPACE, TENANT, SOURCE_OPERATION, WORLD);
    WorldAuthoredSourceIntakeReceipt accepted = receipt(source, 9001L);
    WorldAuthoredSourceIntakeReceipt changed = receipt(source, 9002L);
    when(repository.read(NAMESPACE, INTAKE_REQUEST))
        .thenReturn(Optional.empty(), Optional.of(changed));
    when(client.read(any(ReadRequest.class))).thenReturn(source);
    when(repository.acceptFresh(NAMESPACE, INTAKE_REQUEST, source)).thenReturn(accepted);

    assertThatThrownBy(
            () ->
                withGameDesign(
                    () ->
                        service.intake(
                            INTAKE_REQUEST,
                            TENANT,
                            WORLD,
                            SOURCE_OPERATION,
                            source.evidenceDigest())))
        .isInstanceOf(WorldAuthoredSourceIntakeRepository.InvalidIntakeEvidenceException.class)
        .hasMessageContaining("changed during commit readback");

    verify(repository).acceptFresh(NAMESPACE, INTAKE_REQUEST, source);
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
  }

  private static AuthoredWorldSourceEvidence source(
      String namespace, UUID tenant, UUID operation, String world) {
    UUID registration = UUID.fromString("11111111-1111-4111-8111-111111111111");
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            namespace, registration, tenant, "north-star", world, "Café 🐉");
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            namespace,
            registration,
            operation,
            requestDigest,
            tenant,
            "north-star",
            world,
            "Café 🐉",
            42L,
            "legacy-game-tenant-42",
            "NEW_GAME_ROW");
    return new AuthoredWorldSourceEvidence(
        1,
        namespace,
        registration,
        operation,
        requestDigest,
        tenant,
        "north-star",
        world,
        "Café 🐉",
        42L,
        "legacy-game-tenant-42",
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private static WorldAuthoredSourceIntakeReceipt receipt(
      AuthoredWorldSourceEvidence source, long localTenantKey) {
    String requestDigest =
        WorldAuthoredSourceIntakeDigest.requestDigest(NAMESPACE, INTAKE_REQUEST, source);
    UUID operationId = UUID.fromString("55555555-5555-4555-8555-555555555555");
    return new WorldAuthoredSourceIntakeReceipt(
        1,
        NAMESPACE,
        INTAKE_REQUEST,
        operationId,
        source.canonicalTenantId(),
        source.worldSlug(),
        source.operationId(),
        source.evidenceDigest(),
        requestDigest,
        WorldAuthoredSourceIntakeDigest.receiptDigest(
            NAMESPACE, operationId, requestDigest, source, localTenantKey),
        localTenantKey,
        source);
  }

  private static String digest(char value) {
    return "sha256:" + String.valueOf(value).repeat(64);
  }

  private static GrpcPeerIdentity peer(String namespace, String service) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
  }

  private static <T> T withGameDesign(Supplier<T> action) {
    return withPeer(peer(NAMESPACE, "game-design-service"), action);
  }

  private static <T> T withPeer(GrpcPeerIdentity peer, Supplier<T> action) {
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      return action.get();
    } finally {
      context.detach(previous);
    }
  }

  private static <T> T withoutPeer(Supplier<T> action) {
    Context previous = Context.ROOT.attach();
    try {
      return action.get();
    } finally {
      Context.ROOT.detach(previous);
    }
  }

  private static <T> T withCallerContext(Supplier<T> action) {
    SessionContext.setContext(
        "22222222-2222-4222-8222-222222222222", java.util.List.of(), java.util.Map.of());
    try {
      return action.get();
    } finally {
      SessionContext.clear();
    }
  }

  private static final class RecordingTransactionManager
      implements org.springframework.transaction.PlatformTransactionManager {
    private TransactionDefinition startedWith;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      startedWith = definition;
      TransactionSynchronizationManager.setActualTransactionActive(true);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(definition.isReadOnly());
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      TransactionSynchronizationManager.clear();
    }

    @Override
    public void rollback(TransactionStatus status) {
      TransactionSynchronizationManager.clear();
    }
  }
}
