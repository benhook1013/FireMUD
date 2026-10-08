package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.account.v1.GetCurrentReadinessReceiverMetadataRequest;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessProbeOwnerWorkloadGuard;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessProbeOwnerWorkloadGuard.AuthenticatedCaller;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding.Binding;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ReadinessProbePlan;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.InventorySnapshot;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.ObservationContext;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.ObservationPurpose;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.PodObservation;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.ReplicaSetObservation;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.ValidatorObservation;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.PublicJwksSnapshot;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountJwtReadinessReceiverMetadataServiceTest {
  private static final long NOW = 1_800_000_000L;
  private static final UUID OPERATION_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final String OPERATION_DIGEST = "1".repeat(64);
  private static final String PLAN_DIGEST = "2".repeat(64);
  private static final String MATRIX_DIGEST = "3".repeat(64);
  private static final String INVENTORY_DIGEST = "4".repeat(64);
  private static final String POD_UID = "66666666-6666-4666-8666-666666666666";
  private static final String POD_IP = "10.0.0.7";
  private static final String POD_LEAF = "5".repeat(64);
  private static final String DEPLOYMENT_UID = "55555555-5555-4555-8555-555555555555";
  private static final String REPLICASET_UID = "77777777-7777-4777-8777-777777777777";
  private static final String NAMESPACE = "firemud-prod";
  private static final String PUBLIC_JWKS = "{\"keys\":[]}";
  private static final byte[] PUBLIC_JWKS_BYTES = PUBLIC_JWKS.getBytes(StandardCharsets.UTF_8);

  private AccountJwtReadinessProbeService probeService;
  private AccountJwtValidatorInventorySource inventorySource;
  private AccountJwtJwksTrustedSource trustedJwksSource;
  private AccountJwtReadinessProbeOwnerWorkloadGuard workloadGuard;
  private AccountJwtSignerMaterializerTrustBinding materializerTrustBinding;
  private AccountJwtReadinessProbeOwnerProtoMapper protoMapper;
  private AccountJwtSignerMaterializerTrustBinding.Binding protectedBinding;
  private net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository
          .Binding
      accountBinding;
  private TrustFence trustFence;
  private AuthenticatedCaller caller;
  private ReadinessProbePlan plan;
  private InventorySnapshot inventory;
  private SourceIdentity sourceIdentity;
  private ObservationContext context;
  private AccountJwtReadinessReceiverMetadataService service;

  @BeforeEach
  void setUp() {
    protectedBinding = protectedBinding("trust-r1");
    accountBinding = protectedBinding.accountBinding();
    trustFence = AccountJwtReadinessValidationService.trustFence(protectedBinding);
    context =
        new ObservationContext(ObservationPurpose.STRICT_READY, OPERATION_ID, OPERATION_DIGEST);
    sourceIdentity = sourceIdentity("33333333-3333-4333-8333-333333333333", "account-api-r1");

    probeService = mock(AccountJwtReadinessProbeService.class);
    inventorySource = mock(AccountJwtValidatorInventorySource.class);
    trustedJwksSource = mock(AccountJwtJwksTrustedSource.class);
    workloadGuard = mock(AccountJwtReadinessProbeOwnerWorkloadGuard.class);
    materializerTrustBinding = mock(AccountJwtSignerMaterializerTrustBinding.class);
    protoMapper = new AccountJwtReadinessProbeOwnerProtoMapper();
    caller = mock(AuthenticatedCaller.class);
    plan = plan();
    inventory = inventory(INVENTORY_DIGEST, gameSessionValidator());

    when(caller.accountBinding()).thenReturn(protectedBinding);
    when(workloadGuard.requireGameSessionOwnerReadCaller()).thenReturn(caller);
    when(materializerTrustBinding.current()).thenReturn(Optional.of(protectedBinding));
    when(probeService.readCurrentInventoryObservationContext(accountBinding, trustFence))
        .thenReturn(context);
    when(probeService.readCurrentInventoryObservationContext(
            accountBinding, trustFence, OPERATION_ID))
        .thenReturn(context);
    when(probeService.readCurrentPlan(accountBinding, trustFence, OPERATION_ID)).thenReturn(plan);
    when(inventorySource.observe(context)).thenReturn(inventory);
    when(trustedJwksSource.sourceIdentity()).thenReturn(sourceIdentity);
    when(trustedJwksSource.load())
        .thenAnswer(ignored -> new PublicJwksSnapshot(sourceIdentity, PUBLIC_JWKS_BYTES));
    service =
        new AccountJwtReadinessReceiverMetadataService(
            probeService,
            inventorySource,
            trustedJwksSource,
            workloadGuard,
            materializerTrustBinding,
            protoMapper,
            Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));
  }

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  @Test
  void selectsCurrentProtectedOperationAndReturnsOnlyRecheckedMetadataWithoutCreatingPlan() {
    when(inventorySource.observe(context))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return inventory;
            });
    when(trustedJwksSource.sourceIdentity())
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return sourceIdentity;
            });
    when(trustedJwksSource.load())
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return new PublicJwksSnapshot(sourceIdentity, PUBLIC_JWKS_BYTES);
            });

    var response = service.getCurrentReadinessReceiverMetadata(request().build());

    assertThat(response.getSchemaVersion()).isEqualTo(1);
    assertThat(response.getRotationOperationId()).isEqualTo(OPERATION_ID.toString());
    assertThat(response.getOperationDigest()).isEqualTo(OPERATION_DIGEST);
    assertThat(response.getPlanDigest()).isEqualTo(PLAN_DIGEST);
    assertThat(response.getCurrentIdentity().getValidatorId()).isEqualTo("game-session-service");
    assertThat(response.getCurrentIdentity().getPodUid()).isEqualTo(POD_UID);
    assertThat(response.getCurrentIdentity().getServerLeafSpkiSha256()).isEqualTo(POD_LEAF);
    assertThat(response.getCurrentIdentity().getSourceInventoryRevision())
        .isEqualTo("inventory-r1");
    assertThat(response.getCurrentIdentity().getSourceInventoryDigest())
        .isEqualTo(INVENTORY_DIGEST);
    assertThat(response.getCurrentIdentity().getAccountJwksTrustBindingRevision())
        .isEqualTo(sourceIdentity.bindingRevision());
    assertThat(response.getCurrentIdentity().getAccountPublicJwksSha256())
        .isEqualTo(sha256(PUBLIC_JWKS_BYTES));
    verify(probeService, times(3))
        .readCurrentInventoryObservationContext(accountBinding, trustFence);
    verify(probeService, times(2))
        .readCurrentInventoryObservationContext(accountBinding, trustFence, OPERATION_ID);
    verify(probeService, times(3)).readCurrentPlan(accountBinding, trustFence, OPERATION_ID);
    verify(probeService, never()).planCurrent(any(), any());
    verify(inventorySource, times(2)).observe(context);
    verify(trustedJwksSource, times(4)).sourceIdentity();
    verify(trustedJwksSource, times(2)).load();
  }

  @Test
  void rejectsMalformedRequestBeforeReadingPlanInventoryOrPublicJwks() {
    assertThatThrownBy(
            () ->
                service.getCurrentReadinessReceiverMetadata(
                    GetCurrentReadinessReceiverMetadataRequest.getDefaultInstance()))
        .isInstanceOf(
            AccountJwtReadinessProbeOwnerProtoMapper.InvalidOwnerReadRequestException.class);

    UnknownFieldSet unknown =
        UnknownFieldSet.newBuilder()
            .addField(100, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();
    assertThatThrownBy(
            () ->
                service.getCurrentReadinessReceiverMetadata(
                    request().setUnknownFields(unknown).build()))
        .isInstanceOf(
            AccountJwtReadinessProbeOwnerProtoMapper.InvalidOwnerReadRequestException.class);

    verifyNoInteractions(probeService, inventorySource, trustedJwksSource);
  }

  @Test
  void rejectsWrongWorkloadGuardBeforeAnyOwnerOrNetworkRead() {
    when(workloadGuard.requireGameSessionOwnerReadCaller())
        .thenThrow(new AccountJwtReadinessProbeOwnerWorkloadGuard.OwnerReadDeniedException());

    assertThatThrownBy(() -> service.getCurrentReadinessReceiverMetadata(request().build()))
        .isInstanceOf(AccountJwtReadinessProbeOwnerWorkloadGuard.OwnerReadDeniedException.class);

    verifyNoInteractions(probeService, inventorySource, trustedJwksSource);
  }

  @Test
  void rejectsChangedWorkloadBindingAfterNetworkReads() {
    AuthenticatedCaller changedCaller = mock(AuthenticatedCaller.class);
    when(changedCaller.accountBinding()).thenReturn(protectedBinding("trust-r2"));
    when(workloadGuard.requireGameSessionOwnerReadCaller()).thenReturn(caller, changedCaller);

    assertUnavailable(request().build());

    verify(inventorySource, times(2)).observe(context);
    verify(trustedJwksSource, times(2)).load();
  }

  @Test
  void rejectsChangedProtectedMaterializerBindingAfterNetworkReads() {
    when(materializerTrustBinding.current()).thenReturn(Optional.of(protectedBinding("trust-r2")));

    assertUnavailable(request().build());

    verify(inventorySource, times(2)).observe(context);
    verify(trustedJwksSource, times(2)).load();
  }

  @Test
  void rejectsMissingExpiredAndPartialPlansWithoutCreatingReplacementPlan() {
    when(probeService.readCurrentPlan(accountBinding, trustFence, OPERATION_ID))
        .thenThrow(
            new AccountJwtReadinessProbeRepository.MissingPlanException("test missing plan"));
    assertUnavailable(request().build());
    verify(inventorySource, never()).observe(any());

    setUp();
    ReadinessProbePlan expired = plan(NOW);
    when(probeService.readCurrentPlan(accountBinding, trustFence, OPERATION_ID))
        .thenReturn(expired);
    assertUnavailable(request().build());
    verify(inventorySource, never()).observe(any());

    setUp();
    ReadinessProbePlan partial = plan(NOW + 60, 1, false);
    when(probeService.readCurrentPlan(accountBinding, trustFence, OPERATION_ID))
        .thenReturn(partial);
    assertUnavailable(request().build());
    verify(inventorySource, never()).observe(any());
  }

  @Test
  void rejectsUnknownPodWrongLeafAndBrokenOwnershipOrRuntimeConfiguration() {
    assertUnavailable(request().setProjectedPodUid("88888888-8888-4888-8888-888888888888").build());
    assertUnavailable(request().setServerLeafSpkiSha256("9".repeat(64)).build());

    ValidatorObservation brokenJoin = gameSessionValidatorWithReplicaSetOwner("other-deployment");
    InventorySnapshot brokenJoinInventory = inventory(INVENTORY_DIGEST, brokenJoin);
    when(inventorySource.observe(context)).thenReturn(brokenJoinInventory);
    assertUnavailable(request().build());

    ValidatorObservation brokenConfig =
        gameSessionValidatorWithImage("other-image@sha256:" + "e".repeat(64));
    InventorySnapshot brokenConfigInventory = inventory(INVENTORY_DIGEST, brokenConfig);
    when(inventorySource.observe(context)).thenReturn(brokenConfigInventory);
    assertUnavailable(request().build());

    ValidatorObservation brokenVerifierConfig =
        gameSessionValidatorWithVerifierConfig("b".repeat(64));
    InventorySnapshot brokenVerifierConfigInventory =
        inventory(INVENTORY_DIGEST, brokenVerifierConfig);
    when(inventorySource.observe(context)).thenReturn(brokenVerifierConfigInventory);
    assertUnavailable(request().build());
  }

  @Test
  void rejectsMismatchedJwksSourceOrPublicBytes() {
    when(trustedJwksSource.sourceIdentity())
        .thenReturn(sourceIdentity("33333333-3333-4333-8333-333333333333", "account-api-r1"));
    when(trustedJwksSource.load())
        .thenAnswer(
            ignored ->
                new PublicJwksSnapshot(
                    sourceIdentity("88888888-8888-4888-8888-888888888888", "account-api-r1"),
                    PUBLIC_JWKS_BYTES));
    assertUnavailable(request().build());

    setUp();
    when(trustedJwksSource.load())
        .thenAnswer(
            new org.mockito.stubbing.Answer<>() {
              private int reads;

              @Override
              public PublicJwksSnapshot answer(org.mockito.invocation.InvocationOnMock invocation) {
                reads++;
                return new PublicJwksSnapshot(
                    sourceIdentity,
                    reads == 1
                        ? PUBLIC_JWKS_BYTES
                        : "{\"keys\":[1]}".getBytes(StandardCharsets.UTF_8));
              }
            });
    assertUnavailable(request().build());
  }

  @Test
  void rejectsPlanOrInventoryChangesBetweenProtectedReads() {
    ReadinessProbePlan changedPlan = plan();
    when(changedPlan.planDigest()).thenReturn("9".repeat(64));
    when(probeService.readCurrentPlan(accountBinding, trustFence, OPERATION_ID))
        .thenReturn(plan, changedPlan);
    assertUnavailable(request().build());

    setUp();
    InventorySnapshot changedInventory = inventory("8".repeat(64), gameSessionValidator());
    when(inventorySource.observe(context)).thenReturn(inventory, changedInventory);
    assertUnavailable(request().build());

    setUp();
    ReadinessProbePlan changedMatrix = plan();
    when(changedMatrix.applicabilityMatrixDigest()).thenReturn("9".repeat(64));
    when(probeService.readCurrentPlan(accountBinding, trustFence, OPERATION_ID))
        .thenReturn(plan, changedMatrix);
    assertUnavailable(request().build());
  }

  @Test
  void rejectsSourceIdentityChangeDuringSecondPublicJwksRead() {
    SourceIdentity changedIdentity =
        sourceIdentity("88888888-8888-4888-8888-888888888888", "account-api-r2");
    when(trustedJwksSource.sourceIdentity())
        .thenReturn(sourceIdentity, sourceIdentity, sourceIdentity, changedIdentity);

    assertUnavailable(request().build());

    verify(inventorySource, times(2)).observe(context);
    verify(trustedJwksSource, times(2)).load();
  }

  @Test
  void rejectsOperationThatAdvancesDuringSecondInventoryAndJwksRead() {
    ObservationContext rotatedContext =
        new ObservationContext(
            ObservationPurpose.STRICT_READY,
            UUID.fromString("99999999-9999-4999-8999-999999999999"),
            "9".repeat(64));
    AtomicInteger inventoryReads = new AtomicInteger();
    AtomicBoolean secondNetworkPhaseComplete = new AtomicBoolean();
    when(inventorySource.observe(context))
        .thenAnswer(
            ignored -> {
              if (inventoryReads.incrementAndGet() == 2) {
                secondNetworkPhaseComplete.set(true);
              }
              return inventory;
            });
    when(probeService.readCurrentInventoryObservationContext(accountBinding, trustFence))
        .thenAnswer(ignored -> secondNetworkPhaseComplete.get() ? rotatedContext : context);

    assertUnavailable(request().build());

    assertThat(inventoryReads).hasValue(2);
    verify(probeService, times(2)).readCurrentPlan(accountBinding, trustFence, OPERATION_ID);
  }

  @Test
  void rejectsAmbientSqlTransactionBeforeAuthenticationOrExternalIo() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertUnavailable(request().build());
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    verifyNoInteractions(workloadGuard, probeService, inventorySource, trustedJwksSource);
  }

  private void assertUnavailable(GetCurrentReadinessReceiverMetadataRequest request) {
    assertThatThrownBy(() -> service.getCurrentReadinessReceiverMetadata(request))
        .isInstanceOf(AccountJwtReadinessReceiverInvocationPort.ReceiverUnavailableException.class);
  }

  private GetCurrentReadinessReceiverMetadataRequest.Builder request() {
    return GetCurrentReadinessReceiverMetadataRequest.newBuilder()
        .setSchemaVersion(1)
        .setProjectedPodUid(POD_UID)
        .setServerLeafSpkiSha256(POD_LEAF);
  }

  private ReadinessProbePlan plan() {
    return plan(NOW + 120, AccountJwtReadinessProbeRepository.INVENTORY_PLAN_VERSION, true);
  }

  private ReadinessProbePlan plan(long expiresAtEpochSecond) {
    return plan(
        expiresAtEpochSecond, AccountJwtReadinessProbeRepository.INVENTORY_PLAN_VERSION, true);
  }

  private ReadinessProbePlan plan(long expiresAtEpochSecond, int version, boolean complete) {
    ReadinessProbePlan result = mock(ReadinessProbePlan.class);
    when(result.operationId()).thenReturn(OPERATION_ID);
    when(result.operationDigest()).thenReturn(OPERATION_DIGEST);
    when(result.planDigest()).thenReturn(PLAN_DIGEST);
    when(result.applicabilityMatrixDigest()).thenReturn(MATRIX_DIGEST);
    when(result.inventorySnapshotDigest()).thenReturn(Optional.of(INVENTORY_DIGEST));
    when(result.binding()).thenReturn(accountBinding);
    when(result.trustFence()).thenReturn(trustFence);
    when(result.planVersion()).thenReturn(version);
    when(result.validatorInventoryComplete()).thenReturn(complete);
    when(result.expiresAtEpochSecond()).thenReturn(expiresAtEpochSecond);
    return result;
  }

  private InventorySnapshot inventory(String digest, ValidatorObservation validator) {
    InventorySnapshot result = mock(InventorySnapshot.class);
    when(result.observationContext()).thenReturn(Optional.of(context));
    when(result.digest()).thenReturn(digest);
    when(result.environmentId()).thenReturn("prod");
    when(result.clusterId()).thenReturn("cluster-a");
    when(result.clusterIncarnationUid()).thenReturn("11111111-1111-4111-8111-111111111111");
    when(result.namespace()).thenReturn(NAMESPACE);
    when(result.namespaceUid()).thenReturn("22222222-2222-4222-8222-222222222222");
    when(result.inventoryBindingRevision()).thenReturn("inventory-r1");
    when(result.inventoryBindingDigest()).thenReturn("a".repeat(64));
    when(result.validators()).thenReturn(List.of(validator));
    return result;
  }

  private static ValidatorObservation gameSessionValidator() {
    return gameSessionValidator(REPLICASET_UID, DEPLOYMENT_UID, null);
  }

  private static ValidatorObservation gameSessionValidatorWithReplicaSetOwner(
      String deploymentName) {
    return gameSessionValidator(REPLICASET_UID, DEPLOYMENT_UID, deploymentName);
  }

  private static ValidatorObservation gameSessionValidatorWithImage(String image) {
    ValidatorObservation validator = gameSessionValidator();
    return new ValidatorObservation(
        validator.validatorId(),
        validator.deploymentName(),
        validator.deploymentUid(),
        validator.deploymentGeneration(),
        validator.deploymentResourceVersion(),
        validator.replicas(),
        image,
        validator.verifierConfigSha256(),
        validator.maxCacheAgeSeconds(),
        validator.profiles(),
        validator.pods(),
        validator.replicaSets());
  }

  private static ValidatorObservation gameSessionValidatorWithVerifierConfig(
      String verifierConfig) {
    ValidatorObservation validator = gameSessionValidator();
    return new ValidatorObservation(
        validator.validatorId(),
        validator.deploymentName(),
        validator.deploymentUid(),
        validator.deploymentGeneration(),
        validator.deploymentResourceVersion(),
        validator.replicas(),
        validator.image(),
        verifierConfig,
        validator.maxCacheAgeSeconds(),
        validator.profiles(),
        validator.pods(),
        validator.replicaSets());
  }

  private static ValidatorObservation gameSessionValidator(
      String replicaSetUid, String replicaSetDeploymentUid, String replicaSetDeploymentName) {
    String image = "registry.example/game-session@sha256:" + "e".repeat(64);
    String verifierConfig = "f".repeat(64);
    String deploymentName = "game-session-validator";
    ReplicaSetObservation replicaSet =
        new ReplicaSetObservation(
            "game-session-validator-abcde",
            replicaSetUid,
            "21",
            1,
            1,
            replicaSetDeploymentName == null ? deploymentName : replicaSetDeploymentName,
            replicaSetDeploymentUid,
            "abcde",
            image,
            verifierConfig);
    PodObservation pod =
        new PodObservation(
            "game-session-validator-abcde-xyz",
            POD_UID,
            "22",
            replicaSet.name(),
            replicaSet.uid(),
            "abcde",
            image,
            verifierConfig,
            POD_IP,
            URI.create("grpcs://" + POD_IP + ":9443"),
            "spiffe://firemud/ns/" + NAMESPACE + "/sa/game-session-jwt-validator",
            POD_LEAF);
    return new ValidatorObservation(
        "game-session-service",
        deploymentName,
        DEPLOYMENT_UID,
        1,
        "20",
        1,
        image,
        verifierConfig,
        60,
        List.of(),
        List.of(pod),
        List.of(replicaSet));
  }

  private static Binding protectedBinding(String revision) {
    String clusterUid = "11111111-1111-4111-8111-111111111111";
    String namespaceUid = "22222222-2222-4222-8222-222222222222";
    String peer = "spiffe://firemud/ns/" + NAMESPACE + "/sa/jwt-signer-materializer";
    List<String> pins = List.of("a".repeat(64));
    String digest =
        AccountJwtSignerMaterializerTrustBinding.computeBindingDigest(
            revision, "prod", "cluster-a", NAMESPACE, clusterUid, namespaceUid, peer, pins);
    return new Binding(
        "prod", "cluster-a", NAMESPACE, clusterUid, namespaceUid, peer, pins, revision, digest);
  }

  private static SourceIdentity sourceIdentity(String configMapUid, String bindingRevision) {
    return new SourceIdentity(
        "prod",
        "cluster-a",
        "11111111-1111-4111-8111-111111111111",
        NAMESPACE,
        "22222222-2222-4222-8222-222222222222",
        configMapUid,
        bindingRevision,
        "https://kubernetes.example:6443",
        "9".repeat(64));
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }
}
