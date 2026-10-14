package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerCertificateEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.worldmanagement.data.RunOwnedWorldLaunchEvidence;
import net.firedevops.firemud.worldmanagement.data.RunOwnedWorldLaunchEvidenceTestFixtures;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeDigest;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeReceipt;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityReceipt;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorldAuthoredVersionIdentityServiceTest {
  private static final String NAMESPACE = "firemud";
  private static final UUID TENANT = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID SOURCE_OPERATION =
      UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID SOURCE_REGISTRATION =
      UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID INTAKE_REQUEST =
      UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID INTAKE_OPERATION =
      UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID READ_REQUEST = UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final UUID VERSION = UUID.fromString("88888888-8888-4888-8888-888888888888");
  private static final UUID OTHER_VERSION = UUID.fromString("99999999-9999-4999-8999-999999999999");
  private static final String WORLD = "violet-wilds";
  private static final long GAME_DESIGN_VERSION_ID = 42L;

  private final AuthoredWorldVersionStateClient versionStateClient =
      mock(AuthoredWorldVersionStateClient.class);
  private final WorldAuthoredVersionIdentityRepository repository =
      mock(WorldAuthoredVersionIdentityRepository.class);
  private final WorldAuthoredSourceIntakeRepository sourceIntakeRepository =
      mock(WorldAuthoredSourceIntakeRepository.class);
  private final RecordingTransactionManager transactionManager = new RecordingTransactionManager();
  private final WorldAuthoredVersionIdentityService service =
      new WorldAuthoredVersionIdentityService(
          versionStateClient, repository, sourceIntakeRepository, transactionManager, NAMESPACE);

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void fixtureVersionRevalidatesPeerAndCallerBeforeAnyOwnerOrRemoteRead() {
    RunOwnedWorldLaunchEvidence evidence = RunOwnedWorldLaunchEvidenceTestFixtures.create();
    var sourceReceipt = sourceReceipt(evidence.sourceReceipt().sourceEvidence());
    WorldAuthoredVersionIdentityService fixtureService = fixtureService();
    assertThatThrownBy(
            () ->
                withoutPeer(() -> fixtureService.associateRunOwnedFixture(evidence, sourceReceipt)))
        .isInstanceOf(SecurityException.class);
    for (String[] candidate :
        new String[][] {
          {"dev", "world-management-service", "a".repeat(64)},
          {"other", "game-session-service", "a".repeat(64)},
          {"dev", "game-session-service", "d".repeat(64)},
          {"dev", "game-session-service", null}
        }) {
      assertThatThrownBy(
              () ->
                  withFixturePeer(
                      candidate[0],
                      candidate[1],
                      candidate[2],
                      () -> fixtureService.associateRunOwnedFixture(evidence, sourceReceipt)))
          .isInstanceOf(SecurityException.class);
    }
    assertThatThrownBy(
            () ->
                withFixturePeer(
                    "dev",
                    "game-session-service",
                    "a".repeat(64),
                    () -> {
                      SessionContext.setContext(TENANT.toString(), List.of(), java.util.Map.of());
                      try {
                        return fixtureService.associateRunOwnedFixture(evidence, sourceReceipt);
                      } finally {
                        SessionContext.clear();
                      }
                    }))
        .isInstanceOf(SecurityException.class);
    verifyNoInteractions(sourceIntakeRepository, repository, versionStateClient);
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void fixtureVersionConsumesCommittedSourceThenControlledCurrentStateBeforeSqlAndExactRetry() {
    RunOwnedWorldLaunchEvidence evidence = RunOwnedWorldLaunchEvidenceTestFixtures.create();
    var source = evidence.sourceReceipt().sourceEvidence();
    var intake = sourceReceipt(source);
    var canonicalVersion = evidence.gameSessionAssociation().canonicalVersionId();
    long gameDesignVersion = evidence.launchDescriptor().versionId();
    AtomicInteger ownerReads = new AtomicInteger();
    AtomicReference<WorldAuthoredVersionIdentityReceipt> committed = new AtomicReference<>();
    when(sourceIntakeRepository.readBySource(
            "dev",
            source.canonicalTenantId(),
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest()))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return Optional.of(intake);
            });
    when(repository.readByCanonicalVersion(
            "dev", source.canonicalTenantId(), source.worldSlug(), canonicalVersion))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return ownerReads.incrementAndGet() == 1
                  ? Optional.empty()
                  : Optional.of(committed.get());
            });
    when(repository.readByGameDesignVersion(
            "dev", source.canonicalTenantId(), source.worldSlug(), gameDesignVersion))
        .thenReturn(Optional.empty());
    when(versionStateClient.read(any()))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(transactionManager.startedWith).isNull();
              AuthoredWorldVersionStateEvidence.Request request = call.getArgument(0);
              assertThat(request.targetNamespace()).isEqualTo("dev");
              assertThat(request.canonicalTenantId()).isEqualTo(source.canonicalTenantId());
              assertThat(request.versionId()).isEqualTo(gameDesignVersion);
              assertThat(request.readRequestId())
                  .isNotEqualTo(intake.intakeRequestId())
                  .isNotEqualTo(source.operationId());
              return AuthoredWorldVersionStateEvidence.create(
                  request,
                  source,
                  canonicalVersion,
                  VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
                  evidence
                      .sourceReceipt()
                      .launchReadEvidence()
                      .versionState()
                      .getVersionStateEpoch());
            });
    when(repository.acceptFresh(any(), any()))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                  .isFalse();
              assertThat(call.<WorldAuthoredSourceIntakeReceipt>getArgument(0)).isEqualTo(intake);
              committed.set(identityReceipt(intake, call.getArgument(1), 101L));
              return committed.get();
            });
    WorldAuthoredVersionIdentityService fixtureService = fixtureService();
    var result =
        withFixturePeer(
            "dev",
            "game-session-service",
            "a".repeat(64),
            () -> fixtureService.associateRunOwnedFixture(evidence, intake));
    assertThat(result).isSameAs(committed.get());
    assertThat(result.canonicalVersionId()).isEqualTo(canonicalVersion);
    assertThat(result.localVersionKey()).isEqualTo(101L);
    assertThat(result.sourceIntakeReceipt()).isEqualTo(intake);
    InOrder order = inOrder(sourceIntakeRepository, repository, versionStateClient);
    order
        .verify(sourceIntakeRepository)
        .readBySource(
            "dev",
            source.canonicalTenantId(),
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest());
    order
        .verify(repository)
        .readByCanonicalVersion(
            "dev", source.canonicalTenantId(), source.worldSlug(), canonicalVersion);
    order
        .verify(repository)
        .readByGameDesignVersion(
            "dev", source.canonicalTenantId(), source.worldSlug(), gameDesignVersion);
    order.verify(versionStateClient).read(any());
    order.verify(repository).acceptFresh(intake, result.versionStateEvidence());
    order
        .verify(repository)
        .readByCanonicalVersion(
            "dev", source.canonicalTenantId(), source.worldSlug(), canonicalVersion);
    order
        .verify(sourceIntakeRepository)
        .readBySource(
            "dev",
            source.canonicalTenantId(),
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest());
    assertThat(
            withFixturePeer(
                "dev",
                "game-session-service",
                "a".repeat(64),
                () -> fixtureService.associateRunOwnedFixture(evidence, intake)))
        .isSameAs(result);
    verify(versionStateClient, times(1)).read(any());
    verify(repository, times(1)).acceptFresh(any(), any());
    assertThat(transactionManager.startedWith.getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
    assertThat(transactionManager.startedWith.getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Test
  void fixtureVersionRejectsDifferentCommittedSourceBeforeVersionAccessOrSql() {
    RunOwnedWorldLaunchEvidence evidence = RunOwnedWorldLaunchEvidenceTestFixtures.create();
    var source = evidence.sourceReceipt().sourceEvidence();
    var intake = sourceReceipt(source);
    var different =
        new WorldAuthoredSourceIntakeReceipt(
            intake.schemaVersion(),
            intake.targetNamespace(),
            intake.intakeRequestId(),
            intake.operationId(),
            intake.canonicalTenantId(),
            intake.worldSlug(),
            intake.sourceOperationId(),
            intake.sourceEvidenceDigest(),
            intake.requestDigest(),
            WorldAuthoredSourceIntakeDigest.receiptDigest(
                intake.targetNamespace(),
                intake.operationId(),
                intake.requestDigest(),
                source,
                intake.localTenantKey() + 1),
            intake.localTenantKey() + 1,
            source);
    when(sourceIntakeRepository.readBySource(
            "dev",
            source.canonicalTenantId(),
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest()))
        .thenReturn(Optional.of(different));
    assertThatThrownBy(
            () ->
                withFixturePeer(
                    "dev",
                    "game-session-service",
                    "a".repeat(64),
                    () -> fixtureService().associateRunOwnedFixture(evidence, intake)))
        .isInstanceOf(WorldAuthoredVersionIdentityRepository.RegistrationConflictException.class);
    verifyNoInteractions(repository, versionStateClient);
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void fixtureVersionRejectsRemoteStateEpochCanonicalVersionAndRequestSubstitutionBeforeSql() {
    RunOwnedWorldLaunchEvidence evidence = RunOwnedWorldLaunchEvidenceTestFixtures.create();
    var source = evidence.sourceReceipt().sourceEvidence();
    var intake = sourceReceipt(source);
    when(sourceIntakeRepository.readBySource(
            "dev",
            source.canonicalTenantId(),
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest()))
        .thenReturn(Optional.of(intake));
    for (int variation = 0; variation < 4; variation++) {
      int selected = variation;
      doAnswer(
              call -> {
                AuthoredWorldVersionStateEvidence.Request request = call.getArgument(0);
                if (selected == 3) {
                  request =
                      new AuthoredWorldVersionStateEvidence.Request(
                          request.schemaVersion(),
                          request.targetNamespace(),
                          UUID.randomUUID(),
                          request.canonicalTenantId(),
                          request.worldSlug(),
                          request.sourceOperationId(),
                          request.expectedSourceEvidenceDigest(),
                          request.versionId());
                }
                return AuthoredWorldVersionStateEvidence.create(
                    request,
                    source,
                    selected == 2
                        ? OTHER_VERSION
                        : evidence.gameSessionAssociation().canonicalVersionId(),
                    selected == 0
                        ? VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT
                        : VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
                    evidence
                            .sourceReceipt()
                            .launchReadEvidence()
                            .versionState()
                            .getVersionStateEpoch()
                        + (selected == 1 ? 1L : 0L));
              })
          .when(versionStateClient)
          .read(any());
      assertThatThrownBy(
              () ->
                  withFixturePeer(
                      "dev",
                      "game-session-service",
                      "a".repeat(64),
                      () -> fixtureService().associateRunOwnedFixture(evidence, intake)))
          .isInstanceOf(
              WorldAuthoredVersionIdentityRepository.InvalidIdentityEvidenceException.class);
    }
    verify(repository, never()).acceptFresh(any(), any());
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void fixtureVersionRejectsChangedRetainedSnapshotOnExactRetryWithoutRemoteOrSql() {
    RunOwnedWorldLaunchEvidence evidence = RunOwnedWorldLaunchEvidenceTestFixtures.create();
    var source = evidence.sourceReceipt().sourceEvidence();
    var intake = sourceReceipt(source);
    var canonicalVersion = evidence.gameSessionAssociation().canonicalVersionId();
    long gameDesignVersion = evidence.launchDescriptor().versionId();
    AtomicReference<AuthoredWorldVersionStateEvidence> originalState = new AtomicReference<>();
    AtomicReference<WorldAuthoredVersionIdentityReceipt> retained = new AtomicReference<>();
    when(sourceIntakeRepository.readBySource(
            "dev",
            source.canonicalTenantId(),
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest()))
        .thenReturn(Optional.of(intake));
    when(repository.readByCanonicalVersion(
            "dev", source.canonicalTenantId(), source.worldSlug(), canonicalVersion))
        .thenAnswer(call -> Optional.ofNullable(retained.get()));
    when(repository.readByGameDesignVersion(
            "dev", source.canonicalTenantId(), source.worldSlug(), gameDesignVersion))
        .thenReturn(Optional.empty());
    when(versionStateClient.read(any()))
        .thenAnswer(
            call -> {
              AuthoredWorldVersionStateEvidence.Request request = call.getArgument(0);
              assertThat(request.targetNamespace()).isEqualTo("dev");
              assertThat(request.canonicalTenantId()).isEqualTo(source.canonicalTenantId());
              assertThat(request.worldSlug()).isEqualTo(source.worldSlug());
              assertThat(request.sourceOperationId()).isEqualTo(source.operationId());
              assertThat(request.expectedSourceEvidenceDigest()).isEqualTo(source.evidenceDigest());
              assertThat(request.versionId()).isEqualTo(gameDesignVersion);
              var current =
                  AuthoredWorldVersionStateEvidence.create(
                      request,
                      source,
                      canonicalVersion,
                      VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
                      evidence
                          .sourceReceipt()
                          .launchReadEvidence()
                          .versionState()
                          .getVersionStateEpoch());
              originalState.set(current);
              return current;
            });
    when(repository.acceptFresh(any(), any()))
        .thenAnswer(
            call -> {
              var accepted = identityReceipt(intake, call.getArgument(1), 101L);
              retained.set(accepted);
              return accepted;
            });

    WorldAuthoredVersionIdentityService fixtureService = fixtureService();
    var accepted =
        withFixturePeer(
            "dev",
            "game-session-service",
            "a".repeat(64),
            () -> fixtureService.associateRunOwnedFixture(evidence, intake));
    assertThat(accepted).isSameAs(retained.get());
    assertThat(originalState.get()).isNotNull();
    assertThat(accepted.versionStateEvidence()).isSameAs(originalState.get());
    assertThat(accepted.versionStateEvidence().versionState())
        .isEqualTo(VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED);
    assertThat(accepted.versionStateEvidence().versionStateEpoch())
        .isEqualTo(
            evidence.sourceReceipt().launchReadEvidence().versionState().getVersionStateEpoch());
    assertThat(accepted.versionStateEvidence().request()).isEqualTo(originalState.get().request());
    int transactionStartsAfterAcceptance = transactionManager.transactionStarts;

    assertThat(
            withFixturePeer(
                "dev",
                "game-session-service",
                "a".repeat(64),
                () -> fixtureService.associateRunOwnedFixture(evidence, intake)))
        .isSameAs(accepted);
    verify(versionStateClient, times(1)).read(any());
    assertThat(transactionManager.transactionStarts).isEqualTo(transactionStartsAfterAcceptance);

    for (VersionLifecycleState changedState :
        List.of(
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED)) {
      var changed =
          AuthoredWorldVersionStateEvidence.create(
              originalState.get().request(),
              source,
              canonicalVersion,
              changedState,
              evidence.sourceReceipt().launchReadEvidence().versionState().getVersionStateEpoch()
                  + (changedState == VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED
                      ? 1L
                      : 0L));
      retained.set(identityReceipt(intake, changed, 101L));
      assertThatThrownBy(
              () ->
                  withFixturePeer(
                      "dev",
                      "game-session-service",
                      "a".repeat(64),
                      () -> fixtureService().associateRunOwnedFixture(evidence, intake)))
          .isInstanceOf(
              WorldAuthoredVersionIdentityRepository.InvalidIdentityEvidenceException.class);
    }
    verify(versionStateClient, times(1)).read(any());
    verify(repository, times(1)).acceptFresh(any(), any());
    assertThat(transactionManager.transactionStarts).isEqualTo(transactionStartsAfterAcceptance);
  }

  @Test
  void fixtureVersionDeniesMissingOrSubstitutedIndependentVersionReceipt() {
    RunOwnedWorldLaunchEvidence evidence = RunOwnedWorldLaunchEvidenceTestFixtures.create();
    var source = evidence.sourceReceipt().sourceEvidence();
    var intake = sourceReceipt(source);
    var canonicalVersion = evidence.gameSessionAssociation().canonicalVersionId();
    long gameDesignVersion = evidence.launchDescriptor().versionId();
    when(sourceIntakeRepository.readBySource(
            "dev",
            source.canonicalTenantId(),
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest()))
        .thenReturn(Optional.of(intake));
    for (int variation = 0; variation < 2; variation++) {
      int selected = variation;
      AtomicInteger ownerReads = new AtomicInteger();
      AtomicReference<AuthoredWorldVersionStateEvidence> current = new AtomicReference<>();
      when(repository.readByCanonicalVersion(
              "dev", source.canonicalTenantId(), source.worldSlug(), canonicalVersion))
          .thenAnswer(
              call -> {
                if (ownerReads.incrementAndGet() == 1 || selected == 0) return Optional.empty();
                var original = current.get();
                var changed =
                    AuthoredWorldVersionStateEvidence.create(
                        original.request(),
                        source,
                        canonicalVersion,
                        original.versionState(),
                        original.versionStateEpoch() + 1L);
                return Optional.of(identityReceipt(intake, changed, 101L));
              });
      when(repository.readByGameDesignVersion(
              "dev", source.canonicalTenantId(), source.worldSlug(), gameDesignVersion))
          .thenReturn(Optional.empty());
      doAnswer(
              call -> {
                var state =
                    AuthoredWorldVersionStateEvidence.create(
                        call.getArgument(0),
                        source,
                        canonicalVersion,
                        VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
                        evidence
                            .sourceReceipt()
                            .launchReadEvidence()
                            .versionState()
                            .getVersionStateEpoch());
                current.set(state);
                return state;
              })
          .when(versionStateClient)
          .read(any());
      doAnswer(call -> identityReceipt(intake, call.getArgument(1), 101L))
          .when(repository)
          .acceptFresh(any(), any());
      assertThatThrownBy(
              () ->
                  withFixturePeer(
                      "dev",
                      "game-session-service",
                      "a".repeat(64),
                      () -> fixtureService().associateRunOwnedFixture(evidence, intake)))
          .isInstanceOf(
              WorldAuthoredVersionIdentityRepository.InvalidIdentityEvidenceException.class);
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }
    verify(repository, times(2)).acceptFresh(any(), any());
  }

  @Test
  void fixtureVersionDeniesAmbientTransactionBeforeOwnerAccess() {
    RunOwnedWorldLaunchEvidence evidence = RunOwnedWorldLaunchEvidenceTestFixtures.create();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(
            () ->
                withFixturePeer(
                    "dev",
                    "game-session-service",
                    "a".repeat(64),
                    () ->
                        fixtureService()
                            .associateRunOwnedFixture(
                                evidence,
                                sourceReceipt(evidence.sourceReceipt().sourceEvidence()))))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(repository, sourceIntakeRepository, versionStateClient);
    assertThat(transactionManager.startedWith).isNull();
  }

  private WorldAuthoredVersionIdentityService fixtureService() {
    return new WorldAuthoredVersionIdentityService(
        versionStateClient, repository, sourceIntakeRepository, transactionManager, "dev");
  }

  private static <T> T withFixturePeer(
      String namespace, String service, String leaf, Supplier<T> action) {
    Context context =
        Context.ROOT.withValue(GrpcPeerIdentity.CONTEXT_KEY, peer(namespace, service));
    if (leaf != null) {
      GrpcPeerCertificateEvidence certificate = mock(GrpcPeerCertificateEvidence.class);
      when(certificate.leafSha256()).thenReturn(leaf);
      context = context.withValue(GrpcPeerCertificateEvidence.CONTEXT_KEY, certificate);
    }
    Context previous = context.attach();
    try {
      return action.get();
    } finally {
      context.detach(previous);
    }
  }

  @Test
  void requiresExactSameNamespaceGameDesignPeerBeforeAnyOwnerOrRemoteAccess() {
    assertThatThrownBy(() -> withoutPeer(() -> associate(VERSION, GAME_DESIGN_VERSION_ID)))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(
            () ->
                withPeer(
                    peer("other", "game-design-service"),
                    () -> associate(VERSION, GAME_DESIGN_VERSION_ID)))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(
            () ->
                withPeer(
                    peer(NAMESPACE, "game-session-service"),
                    () -> associate(VERSION, GAME_DESIGN_VERSION_ID)))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(
            () ->
                withGameDesign(
                    () ->
                        service.associate(
                            "other",
                            TENANT,
                            WORLD,
                            SOURCE_OPERATION,
                            source().evidenceDigest(),
                            VERSION,
                            GAME_DESIGN_VERSION_ID,
                            READ_REQUEST)))
        .isInstanceOf(SecurityException.class);
    verifyNoInteractions(sourceIntakeRepository, repository, versionStateClient);
  }

  @Test
  void exactRetryReturnsOriginalReceiptWithoutCallingGameDesignAgain() {
    AuthoredWorldSourceEvidence source = source();
    WorldAuthoredSourceIntakeReceipt intake = sourceReceipt(source);
    AuthoredWorldVersionStateEvidence originalState =
        versionState(
            source,
            READ_REQUEST,
            VERSION,
            GAME_DESIGN_VERSION_ID,
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
            7L);
    WorldAuthoredVersionIdentityReceipt original = identityReceipt(intake, originalState, 91L);
    when(sourceIntakeRepository.readBySource(
            NAMESPACE, TENANT, WORLD, SOURCE_OPERATION, source.evidenceDigest()))
        .thenReturn(Optional.of(intake));
    when(repository.readByCanonicalVersion(NAMESPACE, TENANT, WORLD, VERSION))
        .thenReturn(Optional.of(original));

    WorldAuthoredVersionIdentityReceipt result =
        withGameDesign(() -> associate(VERSION, GAME_DESIGN_VERSION_ID));

    assertThat(result).isEqualTo(original);
    verifyNoInteractions(versionStateClient);
    verify(repository, never()).acceptFresh(any(WorldAuthoredSourceIntakeReceipt.class), any());
    verify(repository, never())
        .readByGameDesignVersion(NAMESPACE, TENANT, WORLD, GAME_DESIGN_VERSION_ID);
    verify(sourceIntakeRepository)
        .readBySource(NAMESPACE, TENANT, WORLD, SOURCE_OPERATION, source.evidenceDigest());
  }

  @Test
  void changedCanonicalUuidForAnAlreadyMappedGameDesignSelectorDeniesBeforeRemoteRead() {
    AuthoredWorldSourceEvidence source = source();
    WorldAuthoredSourceIntakeReceipt intake = sourceReceipt(source);
    AuthoredWorldVersionStateEvidence oldState =
        versionState(
            source,
            READ_REQUEST,
            OTHER_VERSION,
            GAME_DESIGN_VERSION_ID,
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
            7L);
    WorldAuthoredVersionIdentityReceipt existing = identityReceipt(intake, oldState, 91L);
    when(sourceIntakeRepository.readBySource(
            NAMESPACE, TENANT, WORLD, SOURCE_OPERATION, source.evidenceDigest()))
        .thenReturn(Optional.of(intake));
    when(repository.readByCanonicalVersion(NAMESPACE, TENANT, WORLD, VERSION))
        .thenReturn(Optional.empty());
    when(repository.readByGameDesignVersion(NAMESPACE, TENANT, WORLD, GAME_DESIGN_VERSION_ID))
        .thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> withGameDesign(() -> associate(VERSION, GAME_DESIGN_VERSION_ID)))
        .isInstanceOf(WorldAuthoredVersionIdentityRepository.RegistrationConflictException.class)
        .hasMessageContaining("selector conflicts");

    verifyNoInteractions(versionStateClient);
    verify(repository, never()).acceptFresh(any(WorldAuthoredSourceIntakeReceipt.class), any());
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void retainsExactSourceQualifiedDraftIdentityInItsOwnReadCommittedTransaction() {
    AuthoredWorldSourceEvidence source = source();
    WorldAuthoredSourceIntakeReceipt intake = sourceReceipt(source);
    AuthoredWorldVersionStateEvidence current =
        versionState(
            source,
            READ_REQUEST,
            VERSION,
            GAME_DESIGN_VERSION_ID,
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
            3L);
    WorldAuthoredVersionIdentityReceipt accepted = identityReceipt(intake, current, 101L);
    when(sourceIntakeRepository.readBySource(
            NAMESPACE, TENANT, WORLD, SOURCE_OPERATION, source.evidenceDigest()))
        .thenReturn(Optional.of(intake));
    when(repository.readByCanonicalVersion(NAMESPACE, TENANT, WORLD, VERSION))
        .thenReturn(Optional.empty(), Optional.of(accepted));
    when(repository.readByGameDesignVersion(NAMESPACE, TENANT, WORLD, GAME_DESIGN_VERSION_ID))
        .thenReturn(Optional.empty());
    when(versionStateClient.read(any())).thenReturn(current);
    when(repository.acceptFresh(intake, current)).thenReturn(accepted);

    WorldAuthoredVersionIdentityReceipt result =
        withGameDesign(() -> associate(VERSION, GAME_DESIGN_VERSION_ID));

    assertThat(result).isEqualTo(accepted);
    assertThat(result.localVersionKey()).isEqualTo(101L);
    assertThat(result.canonicalVersionId()).isEqualTo(VERSION);
    assertThat(result.gameDesignVersionId()).isEqualTo(GAME_DESIGN_VERSION_ID);
    assertThat(result.sourceIntakeReceipt()).isEqualTo(intake);
    assertThat(result.versionStateEvidence()).isEqualTo(current);
    assertThat(transactionManager.startedWith.getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    assertThat(transactionManager.startedWith.getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
    assertThat(transactionManager.startedWith.isReadOnly()).isFalse();

    InOrder order = inOrder(sourceIntakeRepository, repository, versionStateClient);
    order
        .verify(sourceIntakeRepository)
        .readBySource(NAMESPACE, TENANT, WORLD, SOURCE_OPERATION, source.evidenceDigest());
    order.verify(repository).readByCanonicalVersion(NAMESPACE, TENANT, WORLD, VERSION);
    order
        .verify(repository)
        .readByGameDesignVersion(NAMESPACE, TENANT, WORLD, GAME_DESIGN_VERSION_ID);
    order.verify(versionStateClient).read(current.request());
    order.verify(repository).acceptFresh(intake, current);
    order.verify(repository).readByCanonicalVersion(NAMESPACE, TENANT, WORLD, VERSION);
    order
        .verify(sourceIntakeRepository)
        .readBySource(NAMESPACE, TENANT, WORLD, SOURCE_OPERATION, source.evidenceDigest());
  }

  @Test
  void rejectsSubstitutedCanonicalVersionAndUnsupportedLifecycleBeforeMutation() {
    AuthoredWorldSourceEvidence source = source();
    WorldAuthoredSourceIntakeReceipt intake = sourceReceipt(source);
    when(sourceIntakeRepository.readBySource(
            NAMESPACE, TENANT, WORLD, SOURCE_OPERATION, source.evidenceDigest()))
        .thenReturn(Optional.of(intake));
    when(repository.readByCanonicalVersion(NAMESPACE, TENANT, WORLD, VERSION))
        .thenReturn(Optional.empty());
    when(repository.readByGameDesignVersion(NAMESPACE, TENANT, WORLD, GAME_DESIGN_VERSION_ID))
        .thenReturn(Optional.empty());
    when(versionStateClient.read(any()))
        .thenReturn(
            versionState(
                source,
                READ_REQUEST,
                OTHER_VERSION,
                GAME_DESIGN_VERSION_ID,
                VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
                5L));

    assertThatThrownBy(() -> withGameDesign(() -> associate(VERSION, GAME_DESIGN_VERSION_ID)))
        .isInstanceOf(WorldAuthoredVersionIdentityRepository.InvalidIdentityEvidenceException.class)
        .hasMessageContaining("expected UUID");
    verify(repository, never()).acceptFresh(any(WorldAuthoredSourceIntakeReceipt.class), any());

    when(versionStateClient.read(any()))
        .thenReturn(
            versionState(
                source,
                READ_REQUEST,
                VERSION,
                GAME_DESIGN_VERSION_ID,
                VersionLifecycleState.VERSION_LIFECYCLE_STATE_RETIRED,
                6L));
    assertThatThrownBy(() -> withGameDesign(() -> associate(VERSION, GAME_DESIGN_VERSION_ID)))
        .isInstanceOf(WorldAuthoredVersionIdentityRepository.InvalidIdentityEvidenceException.class)
        .hasMessageContaining("must be DRAFT, PUBLISHED, or ACTIVE");
    verify(repository, never()).acceptFresh(any(WorldAuthoredSourceIntakeReceipt.class), any());
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void propagatesTransientVersionStateReadFailuresWithoutOpeningOwnerTransaction() {
    AuthoredWorldSourceEvidence source = source();
    WorldAuthoredSourceIntakeReceipt intake = sourceReceipt(source);
    when(sourceIntakeRepository.readBySource(
            NAMESPACE, TENANT, WORLD, SOURCE_OPERATION, source.evidenceDigest()))
        .thenReturn(Optional.of(intake));
    when(repository.readByCanonicalVersion(NAMESPACE, TENANT, WORLD, VERSION))
        .thenReturn(Optional.empty());
    when(repository.readByGameDesignVersion(NAMESPACE, TENANT, WORLD, GAME_DESIGN_VERSION_ID))
        .thenReturn(Optional.empty());

    for (Status.Code code : List.of(Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED)) {
      StatusRuntimeException failure =
          Status.fromCode(code).withDescription("upstream transport failure").asRuntimeException();
      doThrow(failure).when(versionStateClient).read(any());

      assertThatThrownBy(() -> withGameDesign(() -> associate(VERSION, GAME_DESIGN_VERSION_ID)))
          .isSameAs(failure);
    }

    verify(repository, never()).acceptFresh(any(WorldAuthoredSourceIntakeReceipt.class), any());
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void keepsMalformedVersionStateReadEvidenceTerminalBeforeOwnerMutation() {
    AuthoredWorldSourceEvidence source = source();
    WorldAuthoredSourceIntakeReceipt intake = sourceReceipt(source);
    when(sourceIntakeRepository.readBySource(
            NAMESPACE, TENANT, WORLD, SOURCE_OPERATION, source.evidenceDigest()))
        .thenReturn(Optional.of(intake));
    when(repository.readByCanonicalVersion(NAMESPACE, TENANT, WORLD, VERSION))
        .thenReturn(Optional.empty());
    when(repository.readByGameDesignVersion(NAMESPACE, TENANT, WORLD, GAME_DESIGN_VERSION_ID))
        .thenReturn(Optional.empty());
    when(versionStateClient.read(any()))
        .thenThrow(
            new IllegalStateException(
                "malformed evidence", new IllegalArgumentException("invalid codec evidence")));

    assertThatThrownBy(() -> withGameDesign(() -> associate(VERSION, GAME_DESIGN_VERSION_ID)))
        .isInstanceOf(WorldAuthoredVersionIdentityRepository.InvalidIdentityEvidenceException.class)
        .hasCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseInstanceOf(IllegalArgumentException.class);

    verify(repository, never()).acceptFresh(any(WorldAuthoredSourceIntakeReceipt.class), any());
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void reconcilesLostCommitAcknowledgmentFromTheOriginalStoredReceipt() {
    AuthoredWorldSourceEvidence source = source();
    WorldAuthoredSourceIntakeReceipt intake = sourceReceipt(source);
    AuthoredWorldVersionStateEvidence current =
        versionState(
            source,
            READ_REQUEST,
            VERSION,
            GAME_DESIGN_VERSION_ID,
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
            8L);
    WorldAuthoredVersionIdentityReceipt accepted = identityReceipt(intake, current, 102L);
    when(sourceIntakeRepository.readBySource(
            NAMESPACE, TENANT, WORLD, SOURCE_OPERATION, source.evidenceDigest()))
        .thenReturn(Optional.of(intake));
    when(repository.readByCanonicalVersion(NAMESPACE, TENANT, WORLD, VERSION))
        .thenReturn(Optional.empty(), Optional.of(accepted));
    when(repository.readByGameDesignVersion(NAMESPACE, TENANT, WORLD, GAME_DESIGN_VERSION_ID))
        .thenReturn(Optional.empty());
    when(versionStateClient.read(any())).thenReturn(current);
    when(repository.acceptFresh(intake, current)).thenReturn(accepted);
    transactionManager.loseCommitAcknowledgment = true;

    WorldAuthoredVersionIdentityReceipt result =
        withGameDesign(() -> associate(VERSION, GAME_DESIGN_VERSION_ID));

    assertThat(result).isEqualTo(accepted);
    assertThat(transactionManager.commitFailureThrown).isTrue();
    verify(repository, times(1)).acceptFresh(intake, current);
  }

  @Test
  void missingExactFreshIntakeCannotCallGameDesignOrAllocateAnIdentity() {
    AuthoredWorldSourceEvidence source = source();
    when(sourceIntakeRepository.readBySource(
            NAMESPACE, TENANT, WORLD, SOURCE_OPERATION, source.evidenceDigest()))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> withGameDesign(() -> associate(VERSION, GAME_DESIGN_VERSION_ID)))
        .isInstanceOf(WorldAuthoredVersionIdentityRepository.InvalidIdentityEvidenceException.class)
        .hasMessageContaining("exact committed fresh source intake");

    verifyNoInteractions(repository, versionStateClient);
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void substitutedSourceIntakeCannotCallGameDesignOrAllocateAnIdentity() {
    AuthoredWorldSourceEvidence requested = source();
    AuthoredWorldSourceEvidence substituted =
        source("amber-coast", UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
    when(sourceIntakeRepository.readBySource(
            NAMESPACE, TENANT, WORLD, SOURCE_OPERATION, requested.evidenceDigest()))
        .thenReturn(Optional.of(sourceReceipt(substituted)));

    assertThatThrownBy(() -> withGameDesign(() -> associate(VERSION, GAME_DESIGN_VERSION_ID)))
        .isInstanceOf(WorldAuthoredVersionIdentityRepository.RegistrationConflictException.class)
        .hasMessageContaining("exact fresh NEW_GAME_ROW source intake");

    verifyNoInteractions(repository, versionStateClient);
    assertThat(transactionManager.startedWith).isNull();
  }

  private WorldAuthoredVersionIdentityReceipt associate(UUID canonicalVersionId, long versionId) {
    AuthoredWorldSourceEvidence source = source();
    return service.associate(
        NAMESPACE,
        TENANT,
        WORLD,
        SOURCE_OPERATION,
        source.evidenceDigest(),
        canonicalVersionId,
        versionId,
        READ_REQUEST);
  }

  private static WorldAuthoredVersionIdentityReceipt identityReceipt(
      WorldAuthoredSourceIntakeReceipt intake,
      AuthoredWorldVersionStateEvidence evidence,
      long localVersionKey) {
    return new WorldAuthoredVersionIdentityReceipt(
        1,
        UUID.fromString("77777777-7777-4777-8777-777777777777"),
        localVersionKey,
        intake,
        evidence);
  }

  private static AuthoredWorldVersionStateEvidence versionState(
      AuthoredWorldSourceEvidence source,
      UUID readRequestId,
      UUID canonicalVersionId,
      long gameDesignVersionId,
      VersionLifecycleState state,
      long epoch) {
    AuthoredWorldVersionStateEvidence.Request request =
        new AuthoredWorldVersionStateEvidence.Request(
            1,
            NAMESPACE,
            readRequestId,
            TENANT,
            WORLD,
            SOURCE_OPERATION,
            source.evidenceDigest(),
            gameDesignVersionId);
    return AuthoredWorldVersionStateEvidence.create(
        request, source, canonicalVersionId, state, epoch);
  }

  private static WorldAuthoredSourceIntakeReceipt sourceReceipt(
      AuthoredWorldSourceEvidence source) {
    String requestDigest =
        WorldAuthoredSourceIntakeDigest.requestDigest(
            source.targetNamespace(), INTAKE_REQUEST, source);
    return new WorldAuthoredSourceIntakeReceipt(
        1,
        source.targetNamespace(),
        INTAKE_REQUEST,
        INTAKE_OPERATION,
        source.canonicalTenantId(),
        source.worldSlug(),
        source.operationId(),
        source.evidenceDigest(),
        requestDigest,
        WorldAuthoredSourceIntakeDigest.receiptDigest(
            source.targetNamespace(), INTAKE_OPERATION, requestDigest, source, 90_041L),
        90_041L,
        source);
  }

  private static AuthoredWorldSourceEvidence source() {
    return source(WORLD, SOURCE_OPERATION);
  }

  private static AuthoredWorldSourceEvidence source(String worldSlug, UUID sourceOperationId) {
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, SOURCE_REGISTRATION, TENANT, "north-star", worldSlug, "Café 🐉");
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            SOURCE_REGISTRATION,
            sourceOperationId,
            requestDigest,
            TENANT,
            "north-star",
            worldSlug,
            "Café 🐉",
            42L,
            "legacy-game-tenant-42",
            "NEW_GAME_ROW");
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        SOURCE_REGISTRATION,
        sourceOperationId,
        requestDigest,
        TENANT,
        "north-star",
        worldSlug,
        "Café 🐉",
        42L,
        "legacy-game-tenant-42",
        "NEW_GAME_ROW",
        evidenceDigest);
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

  private static final class RecordingTransactionManager
      implements org.springframework.transaction.PlatformTransactionManager {
    private TransactionDefinition startedWith;
    private int transactionStarts;
    private boolean loseCommitAcknowledgment;
    private boolean commitFailureThrown;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      startedWith = definition;
      transactionStarts++;
      TransactionSynchronizationManager.setActualTransactionActive(true);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(definition.isReadOnly());
      TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
          definition.getIsolationLevel());
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      TransactionSynchronizationManager.clear();
      if (loseCommitAcknowledgment) {
        loseCommitAcknowledgment = false;
        commitFailureThrown = true;
        throw new IllegalStateException("simulated lost commit acknowledgment");
      }
    }

    @Override
    public void rollback(TransactionStatus status) {
      TransactionSynchronizationManager.clear();
    }
  }
}
