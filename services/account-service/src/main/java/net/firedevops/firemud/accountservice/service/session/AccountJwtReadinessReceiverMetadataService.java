package net.firedevops.firemud.accountservice.service.session;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.v1.GetCurrentReadinessReceiverMetadataRequest;
import net.firedevops.firemud.account.v1.GetCurrentReadinessReceiverMetadataResponse;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessProbeOwnerWorkloadGuard;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessProbeOwnerWorkloadGuard.AuthenticatedCaller;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtValidatorInventoryBinding;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ReadinessProbePlan;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.Binding;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.InventorySnapshot;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.PodObservation;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.ReplicaSetObservation;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.ValidatorObservation;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.PublicJwksSnapshot;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceIdentity;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Read-only Account producer for protected Game Session readiness receiver metadata.
 *
 * <p>The request UID and TLS leaf digest are selectors only. Account independently joins the
 * selected Pod through its current protected inventory and never treats either request field as
 * proof that the caller is that Pod or that its TLS connection used the selected leaf.
 */
public final class AccountJwtReadinessReceiverMetadataService {
  private static final int SCHEMA_VERSION = 1;
  private static final int INVENTORY_PLAN_VERSION =
      AccountJwtReadinessProbeRepository.INVENTORY_PLAN_VERSION;
  private static final String GAME_SESSION_VALIDATOR = "game-session-service";
  private static final String SPIFFE_PREFIX = "spiffe://firemud/ns/";
  private static final String SHA256_PATTERN = "[0-9a-f]{64}";
  private static final String REVISION_PATTERN = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}";

  private final AccountJwtReadinessProbeService probeService;
  private final AccountJwtValidatorInventorySource inventorySource;
  private final AccountJwtJwksTrustedSource trustedJwksSource;
  private final AccountJwtReadinessProbeOwnerWorkloadGuard workloadGuard;
  private final AccountJwtSignerMaterializerTrustBinding materializerTrustBinding;
  private final AccountJwtReadinessProbeOwnerProtoMapper protoMapper;
  private final Clock clock;

  public AccountJwtReadinessReceiverMetadataService(
      AccountJwtReadinessProbeService probeService,
      AccountJwtValidatorInventorySource inventorySource,
      AccountJwtJwksTrustedSource trustedJwksSource,
      AccountJwtReadinessProbeOwnerWorkloadGuard workloadGuard,
      AccountJwtSignerMaterializerTrustBinding materializerTrustBinding,
      AccountJwtReadinessProbeOwnerProtoMapper protoMapper,
      Clock clock) {
    this.probeService = Objects.requireNonNull(probeService);
    this.inventorySource = Objects.requireNonNull(inventorySource);
    this.trustedJwksSource = Objects.requireNonNull(trustedJwksSource);
    this.workloadGuard = Objects.requireNonNull(workloadGuard);
    this.materializerTrustBinding = Objects.requireNonNull(materializerTrustBinding);
    this.protoMapper = Objects.requireNonNull(protoMapper);
    this.clock = Objects.requireNonNull(clock);
  }

  public GetCurrentReadinessReceiverMetadataResponse getCurrentReadinessReceiverMetadata(
      GetCurrentReadinessReceiverMetadataRequest request) {
    requireNoAmbientTransaction();
    AuthenticatedCaller caller = workloadGuard.requireGameSessionOwnerReadCaller();
    AccountJwtReadinessProbeOwnerProtoMapper.ReceiverMetadataSelector selector =
        protoMapper.parseReceiverMetadataRequest(request);
    try {
      return readCurrent(caller, selector);
    } catch (AccountJwtReadinessReceiverInvocationPort.ReceiverUnavailableException unavailable) {
      throw unavailable;
    } catch (RuntimeException unavailable) {
      // Keep repository, Kubernetes, TLS, and JWKS source details out of this read boundary.
      throw new AccountJwtReadinessReceiverInvocationPort.ReceiverUnavailableException();
    }
  }

  private GetCurrentReadinessReceiverMetadataResponse readCurrent(
      AuthenticatedCaller caller,
      AccountJwtReadinessProbeOwnerProtoMapper.ReceiverMetadataSelector selector) {
    AccountJwtSignerMaterializerTrustBinding.Binding protectedBinding = caller.accountBinding();
    var accountBinding = protectedBinding.accountBinding();
    TrustFence trustFence = AccountJwtReadinessValidationService.trustFence(protectedBinding);

    AccountJwtValidatorInventorySource.ObservationContext context =
        probeService.readCurrentInventoryObservationContext(accountBinding, trustFence);
    ReadinessProbePlan plan =
        probeService.readCurrentPlan(accountBinding, trustFence, context.operationId());
    requireCurrentCompletePlan(plan, context, accountBinding, trustFence);

    InventorySnapshot firstInventory = observe(context);
    requirePlanInventory(plan, firstInventory, context, protectedBinding, trustFence);
    SourceRead firstJwks = readPublicJwks(firstInventory, protectedBinding, trustFence);
    SelectedPod firstPod = selectGameSessionPod(firstInventory, selector);

    // Re-read the exact current operation and immutable plan identity after all first-pass network
    // reads. Plan entry receipts may advance independently and are deliberately not compared.
    requireNoAmbientTransaction();
    var currentContext =
        probeService.readCurrentInventoryObservationContext(accountBinding, trustFence);
    if (!context.equals(currentContext)) {
      throw unavailable();
    }
    var exactContext =
        probeService.readCurrentInventoryObservationContext(
            accountBinding, trustFence, context.operationId());
    if (!context.equals(exactContext)) {
      throw unavailable();
    }
    ReadinessProbePlan currentPlan =
        probeService.readCurrentPlan(accountBinding, trustFence, context.operationId());
    requireCurrentCompletePlan(currentPlan, exactContext, accountBinding, trustFence);
    if (!PlanIdentity.from(plan).equals(PlanIdentity.from(currentPlan))) {
      throw unavailable();
    }

    InventorySnapshot currentInventory = observe(exactContext);
    requirePlanInventory(currentPlan, currentInventory, exactContext, protectedBinding, trustFence);
    if (!firstInventory.digest().equals(currentInventory.digest())) {
      throw unavailable();
    }
    SourceRead currentJwks = readPublicJwks(currentInventory, protectedBinding, trustFence);
    if (!firstJwks.sourceIdentity().equals(currentJwks.sourceIdentity())
        || !firstJwks.sha256().equals(currentJwks.sha256())) {
      throw unavailable();
    }
    SelectedPod currentPod = selectGameSessionPod(currentInventory, selector);
    if (!firstPod.equals(currentPod)) {
      throw unavailable();
    }

    // Fence changes that happen during the second external read phase as well.
    requireNoAmbientTransaction();
    var finalContext =
        probeService.readCurrentInventoryObservationContext(accountBinding, trustFence);
    if (!context.equals(finalContext)) {
      throw unavailable();
    }
    var finalExactContext =
        probeService.readCurrentInventoryObservationContext(
            accountBinding, trustFence, context.operationId());
    if (!context.equals(finalExactContext)) {
      throw unavailable();
    }
    ReadinessProbePlan finalPlan =
        probeService.readCurrentPlan(accountBinding, trustFence, context.operationId());
    requireCurrentCompletePlan(finalPlan, finalExactContext, accountBinding, trustFence);
    if (!PlanIdentity.from(currentPlan).equals(PlanIdentity.from(finalPlan))) {
      throw unavailable();
    }

    AuthenticatedCaller currentCaller = workloadGuard.requireGameSessionOwnerReadCaller();
    Optional<AccountJwtSignerMaterializerTrustBinding.Binding> latestBinding =
        materializerTrustBinding.current();
    if (!protectedBinding.equals(currentCaller.accountBinding())
        || latestBinding.isEmpty()
        || !protectedBinding.equals(latestBinding.orElseThrow())) {
      throw unavailable();
    }

    return protoMapper.toReceiverMetadataResponse(
        currentPlan,
        currentInventory,
        currentPod.validator(),
        currentPod.pod(),
        currentJwks.sourceIdentity(),
        currentJwks.sha256());
  }

  private InventorySnapshot observe(AccountJwtValidatorInventorySource.ObservationContext context) {
    requireNoAmbientTransaction();
    return inventorySource.observe(context);
  }

  private SourceRead readPublicJwks(
      InventorySnapshot inventory,
      AccountJwtSignerMaterializerTrustBinding.Binding binding,
      TrustFence trustFence) {
    requireNoAmbientTransaction();
    SourceIdentity sourceBefore = trustedJwksSource.sourceIdentity();
    requireJwksSourceMatchesInventory(sourceBefore, inventory, binding, trustFence);

    requireNoAmbientTransaction();
    PublicJwksSnapshot snapshot = trustedJwksSource.load();
    byte[] jwksBytes = snapshot.jwksBytes();
    try {
      requireNoAmbientTransaction();
      SourceIdentity sourceAfter = trustedJwksSource.sourceIdentity();
      if (!sourceBefore.equals(snapshot.sourceIdentity())
          || !sourceBefore.equals(sourceAfter)
          || jwksBytes == null
          || jwksBytes.length == 0
          || jwksBytes.length > AccountPublicJwksCache.MAX_JWKS_BYTES) {
        throw unavailable();
      }
      String digest = sha256(jwksBytes);
      if (!digest.matches(SHA256_PATTERN)) {
        throw unavailable();
      }
      return new SourceRead(sourceAfter, digest);
    } finally {
      if (jwksBytes != null) {
        Arrays.fill(jwksBytes, (byte) 0);
      }
    }
  }

  private void requireCurrentCompletePlan(
      ReadinessProbePlan plan,
      AccountJwtValidatorInventorySource.ObservationContext context,
      Binding binding,
      TrustFence trustFence) {
    if (plan == null
        || plan.planVersion() != INVENTORY_PLAN_VERSION
        || !plan.validatorInventoryComplete()
        || plan.inventorySnapshotDigest().isEmpty()
        || !plan.operationId().equals(context.operationId())
        || !plan.operationDigest().equals(context.operationDigest())
        || !plan.binding().equals(binding)
        || !plan.trustFence().equals(trustFence)
        || plan.planDigest() == null
        || !plan.planDigest().matches(SHA256_PATTERN)
        || plan.applicabilityMatrixDigest() == null
        || !plan.applicabilityMatrixDigest().matches(SHA256_PATTERN)
        || plan.expiresAtEpochSecond() <= clock.instant().getEpochSecond()) {
      throw unavailable();
    }
  }

  private void requirePlanInventory(
      ReadinessProbePlan plan,
      InventorySnapshot snapshot,
      AccountJwtValidatorInventorySource.ObservationContext context,
      AccountJwtSignerMaterializerTrustBinding.Binding binding,
      TrustFence trustFence) {
    if (snapshot == null
        || snapshot.observationContext().filter(context::equals).isEmpty()
        || !plan.inventorySnapshotDigest().equals(Optional.of(snapshot.digest()))
        || !snapshot.digest().matches(SHA256_PATTERN)
        || !snapshot.inventoryBindingRevision().matches(REVISION_PATTERN)
        || !snapshot.inventoryBindingDigest().matches(SHA256_PATTERN)
        || !snapshot.environmentId().equals(binding.environmentId())
        || !snapshot.clusterId().equals(binding.clusterId())
        || !snapshot.namespace().equals(binding.namespace())
        || !snapshot.clusterIncarnationUid().equals(trustFence.expectedClusterIncarnationUid())
        || !snapshot.namespaceUid().equals(trustFence.expectedNamespaceUid())) {
      throw unavailable();
    }
  }

  private static void requireJwksSourceMatchesInventory(
      SourceIdentity source,
      InventorySnapshot inventory,
      AccountJwtSignerMaterializerTrustBinding.Binding binding,
      TrustFence trustFence) {
    if (source == null
        || !source.environmentId().equals(inventory.environmentId())
        || !source.clusterId().equals(inventory.clusterId())
        || !source.clusterIncarnationUid().equals(inventory.clusterIncarnationUid())
        || !source.namespace().equals(inventory.namespace())
        || !source.namespaceUid().equals(inventory.namespaceUid())
        || !source.environmentId().equals(binding.environmentId())
        || !source.clusterId().equals(binding.clusterId())
        || !source.namespace().equals(binding.namespace())
        || !source.clusterIncarnationUid().equals(trustFence.expectedClusterIncarnationUid())
        || !source.namespaceUid().equals(trustFence.expectedNamespaceUid())) {
      throw unavailable();
    }
  }

  private static SelectedPod selectGameSessionPod(
      InventorySnapshot inventory,
      AccountJwtReadinessProbeOwnerProtoMapper.ReceiverMetadataSelector selector) {
    List<ValidatorObservation> gameSessionValidators =
        inventory.validators().stream()
            .filter(value -> GAME_SESSION_VALIDATOR.equals(value.validatorId()))
            .toList();
    if (gameSessionValidators.size() != 1) {
      throw unavailable();
    }
    ValidatorObservation validator = gameSessionValidators.getFirst();
    if (inventory.validators().stream()
            .flatMap(value -> value.pods().stream())
            .filter(value -> selector.projectedPodUid().equals(value.uid()))
            .count()
        != 1) {
      throw unavailable();
    }
    List<PodObservation> selected =
        validator.pods().stream()
            .filter(value -> selector.projectedPodUid().equals(value.uid()))
            .toList();
    if (selected.size() != 1) {
      throw unavailable();
    }
    PodObservation pod = selected.getFirst();
    requirePodDeploymentJoin(inventory, validator, pod);
    if (!selector.serverLeafSpkiSha256().equals(pod.leafSpkiSha256())) {
      throw unavailable();
    }
    return new SelectedPod(validator, pod);
  }

  private static void requirePodDeploymentJoin(
      InventorySnapshot inventory, ValidatorObservation validator, PodObservation pod) {
    List<ReplicaSetObservation> ownedReplicaSets =
        validator.replicaSets().stream()
            .filter(
                replicaSet ->
                    replicaSet.uid().equals(pod.ownerUid())
                        && replicaSet.name().equals(pod.ownerName()))
            .toList();
    URI endpoint = pod.endpoint();
    String endpointHost = endpoint == null ? null : endpoint.getHost();
    if (ownedReplicaSets.size() != 1
        || !inventory.namespace().matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
        || !validator
            .deploymentUid()
            .matches("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        || pod.uid() == null
        || !pod.uid()
            .matches("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        || !AccountJwtValidatorInventoryBinding.canonicalPodIp(pod.podIp()).equals(pod.podIp())
        || endpoint == null
        || !"grpcs".equals(endpoint.getScheme())
        || endpoint.getRawUserInfo() != null
        || endpoint.getRawQuery() != null
        || endpoint.getRawFragment() != null
        || endpoint.getPort() < 1
        || endpointHost == null
        || !(endpointHost.equals(pod.podIp()) || endpointHost.equals("[" + pod.podIp() + "]"))
        || pod.image() == null
        || !pod.image().equals(validator.image())
        || pod.verifierConfigSha256() == null
        || !pod.verifierConfigSha256().matches(SHA256_PATTERN)
        || !pod.verifierConfigSha256().equals(validator.verifierConfigSha256())
        || pod.receiverServiceUri() == null
        || !pod.receiverServiceUri().startsWith(SPIFFE_PREFIX + inventory.namespace() + "/sa/")
        || !pod.receiverServiceUri()
            .matches(
                "spiffe://firemud/ns/[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?/sa/[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) {
      throw unavailable();
    }
    ReplicaSetObservation replicaSet = ownedReplicaSets.getFirst();
    if (!replicaSet.ownerDeploymentName().equals(validator.deploymentName())
        || !replicaSet.ownerDeploymentUid().equals(validator.deploymentUid())
        || !replicaSet.image().equals(validator.image())
        || !replicaSet.image().equals(pod.image())
        || !replicaSet.verifierConfigSha256().equals(validator.verifierConfigSha256())
        || !replicaSet.verifierConfigSha256().equals(pod.verifierConfigSha256())
        || !replicaSet.podTemplateHash().equals(pod.podTemplateHash())) {
      throw unavailable();
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException failure) {
      throw unavailable();
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw unavailable();
    }
  }

  private static AccountJwtReadinessReceiverInvocationPort.ReceiverUnavailableException
      unavailable() {
    return new AccountJwtReadinessReceiverInvocationPort.ReceiverUnavailableException();
  }

  private record SourceRead(SourceIdentity sourceIdentity, String sha256) {}

  private record SelectedPod(ValidatorObservation validator, PodObservation pod) {}

  private record PlanIdentity(
      UUID operationId,
      String operationDigest,
      String planDigest,
      String applicabilityMatrixDigest,
      Optional<String> inventorySnapshotDigest,
      Binding binding,
      TrustFence trustFence,
      int planVersion,
      boolean validatorInventoryComplete,
      long expiresAtEpochSecond) {
    static PlanIdentity from(ReadinessProbePlan plan) {
      return new PlanIdentity(
          plan.operationId(),
          plan.operationDigest(),
          plan.planDigest(),
          plan.applicabilityMatrixDigest(),
          plan.inventorySnapshotDigest(),
          plan.binding(),
          plan.trustFence(),
          plan.planVersion(),
          plan.validatorInventoryComplete(),
          plan.expiresAtEpochSecond());
    }
  }
}
