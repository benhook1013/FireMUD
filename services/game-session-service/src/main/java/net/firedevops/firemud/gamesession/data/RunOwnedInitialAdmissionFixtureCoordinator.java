package net.firedevops.firemud.gamesession.data;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.client.WorldManagementClient;
import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;
import net.firedevops.firemud.gamesession.entity.InitialAdmissionBindAttempt;
import net.firedevops.firemud.gamesession.entity.InitialAdmissionBindCatalog;
import net.firedevops.firemud.gamesession.service.GameInstanceService;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindCatalogDescriptor;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindHoldBinding;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindOwnerProof;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindOwnerProof.Outcome;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindOwnerService;
import net.firedevops.firemud.gamesession.service.InitialAdmissionBindRequest;
import net.firedevops.firemud.gamesession.service.RunOwnedInitialLaunchResult;
import net.firedevops.firemud.worldmanagement.v1.AcquireInitialAdmissionBindHoldResponse;
import net.firedevops.firemud.worldmanagement.v1.InitialAdmissionBindHold;
import net.firedevops.firemud.worldmanagement.v1.InitialAdmissionBindHoldStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded, internal-only bootstrap for an explicitly claimed run-owned Compose fixture. */
@Component
public final class RunOwnedInitialAdmissionFixtureCoordinator {
  private static final Logger logger =
      LoggerFactory.getLogger(RunOwnedInitialAdmissionFixtureCoordinator.class);
  private static final int MAX_ATTEMPTS = 12;
  private static final long RETRY_INTERVAL_MS = 5_000L;
  private static final String RUNNING = "RUNNING";
  private static final String DIGEST_DOMAIN = "firemud.run-owned-initial-admission-bind.v1";

  private final boolean enabled;
  private final String capabilityPath;
  private final String runId;
  private final String composeProjectName;
  private final CommonGrpcClientProperties grpcProperties;
  private final GameInstanceService gameInstanceService;
  private final InitialAdmissionBindOwnerService ownerService;
  private final WorldManagementClient worldManagementClient;
  private final AtomicInteger attempts = new AtomicInteger();
  private final AtomicBoolean inProgress = new AtomicBoolean();
  private final AtomicBoolean finished = new AtomicBoolean();
  private final AtomicReference<RunOwnedInitialAdmissionFixtureCapability> acceptedCapability =
      new AtomicReference<>();

  public RunOwnedInitialAdmissionFixtureCoordinator(
      @Value("${FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED:false}") boolean enabled,
      @Value("${FIREMUD_SMOKE_INITIAL_ADMISSION_CAPABILITY_PATH:}") String capabilityPath,
      @Value("${FIREMUD_SMOKE_RUN_ID:}") String runId,
      @Value("${FIREMUD_SMOKE_COMPOSE_PROJECT_NAME:}") String composeProjectName,
      CommonGrpcClientProperties grpcProperties,
      GameInstanceService gameInstanceService,
      InitialAdmissionBindOwnerService ownerService,
      WorldManagementClient worldManagementClient) {
    this.enabled = enabled;
    this.capabilityPath = capabilityPath;
    this.runId = runId;
    this.composeProjectName = composeProjectName;
    this.grpcProperties = Objects.requireNonNull(grpcProperties, "grpcProperties");
    this.gameInstanceService = Objects.requireNonNull(gameInstanceService, "gameInstanceService");
    this.ownerService = Objects.requireNonNull(ownerService, "ownerService");
    this.worldManagementClient =
        Objects.requireNonNull(worldManagementClient, "worldManagementClient");
  }

  @Scheduled(initialDelay = RETRY_INTERVAL_MS, fixedDelay = RETRY_INTERVAL_MS)
  void retryRunOwnedFixtureBootstrap() {
    if (!enabled || finished.get() || !inProgress.compareAndSet(false, true)) {
      return;
    }
    int attempt = attempts.incrementAndGet();
    if (attempt > MAX_ATTEMPTS) {
      finished.set(true);
      inProgress.set(false);
      return;
    }

    try {
      RunOwnedInitialAdmissionFixtureCapability capability =
          RunOwnedInitialAdmissionFixtureCapability.load(
              declaredCapabilityPath(), runId, composeProjectName, grpcProperties);
      RunOwnedInitialAdmissionFixtureCapability firstCapability =
          acceptedCapability.updateAndGet(existing -> existing == null ? capability : existing);
      if (!firstCapability.equals(capability)) {
        throw unresolved();
      }
      BootstrapResult result = coordinate(capability);
      if (result == BootstrapResult.COMMITTED) {
        finished.set(true);
        logger.info(
            "Run-owned initial-admission fixture committed; World hold reconciliation remains"
                + " asynchronous.");
      } else if (result == BootstrapResult.ABORTED) {
        finished.set(true);
        logger.warn(
            "Run-owned initial-admission fixture has a durable abort; World retains ownership of"
                + " hold reconciliation.");
      }
    } catch (RuntimeException exception) {
      if (attempt >= MAX_ATTEMPTS) {
        finished.set(true);
        logger.error(
            "Run-owned initial-admission fixture did not reach a verified terminal result after"
                + " the bounded retry budget; World hold state remains owner-controlled.");
      } else {
        logger.warn(
            "Run-owned initial-admission fixture attempt {}/{} remains unresolved; the same"
                + " stable operation identity will be retried.",
            attempt,
            MAX_ATTEMPTS);
      }
    } finally {
      inProgress.set(false);
    }
  }

  BootstrapResult coordinate(RunOwnedInitialAdmissionFixtureCapability capability) {
    Objects.requireNonNull(capability, "capability");
    RunOwnedInitialLaunchResult launch =
        gameInstanceService.startRunOwnedInitialLaunch(
            new StartSessionRequest(
                capability.tenantId(),
                capability.gameTemplateId(),
                capability.operationId(),
                capability.ownerAccountId()));
    GameInstanceDto target = requireExactRunningTarget(launch, capability);

    InitialAdmissionBindCatalogDescriptor descriptor =
        new InitialAdmissionBindCatalogDescriptor(
            capability.tenantId(),
            capability.gameTemplateId(),
            capability.worldSlug(),
            capability.worldDisplayName(),
            capability.realmSlug(),
            capability.realmDisplayName(),
            capability.requiresCharacterSelection());
    InitialAdmissionBindCatalog catalog =
        ownerService.registerPublicSharedFixtureCatalog(descriptor);
    requireExactCatalog(catalog, descriptor);

    long gameInstanceId = target.id();
    long versionId = target.versionId();
    long activeLifecycleEpoch = launch.activeLifecycleEpoch();
    String requestDigest =
        requestDigest(capability, catalog, gameInstanceId, versionId, activeLifecycleEpoch);
    InitialAdmissionBindRequest request =
        new InitialAdmissionBindRequest(
            capability.tenantId(),
            capability.worldSlug(),
            capability.realmSlug(),
            capability.operationId(),
            requestDigest,
            gameInstanceId,
            versionId,
            activeLifecycleEpoch);
    InitialAdmissionBindAttempt attempt = ownerService.beginIntent(request);
    requireExactAttempt(attempt, request, catalog, null, null);

    AcquireInitialAdmissionBindHoldResponse response =
        worldManagementClient.acquireInitialAdmissionBindHold(
            capability.tenantId(),
            gameInstanceId,
            versionId,
            activeLifecycleEpoch,
            capability.operationId(),
            requestDigest,
            catalog.realmId(),
            catalog.playableStateNamespaceId(),
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
            catalog.catalogRevision());
    if (response == null || response.hasError() || !response.hasHold()) {
      throw unresolved();
    }
    InitialAdmissionBindHold hold = response.getHold();
    InitialAdmissionBindHoldBinding binding =
        requireExactHold(hold, request, catalog, gameInstanceId, versionId, activeLifecycleEpoch);

    if (isTerminalHoldStatus(hold.getStatus())) {
      return resultFromProof(
          ownerService.read(binding), binding, attempt, request.initialAdmissionRequestId());
    }
    if (!isRetryableHoldStatus(hold.getStatus())) {
      throw unresolved();
    }

    InitialAdmissionBindAttempt attached = ownerService.attachHold(binding);
    requireExactAttempt(attached, request, catalog, binding.holdId(), binding.holdFence());
    InitialAdmissionBindOwnerProof proof =
        attached.status() == InitialAdmissionBindAttempt.Status.PENDING
            ? ownerService.commit(binding)
            : ownerService.read(binding);
    return resultFromProof(proof, binding, attached, request.initialAdmissionRequestId());
  }

  private Path declaredCapabilityPath() {
    if (capabilityPath == null || capabilityPath.isBlank()) {
      throw unresolved();
    }
    try {
      return Path.of(capabilityPath);
    } catch (RuntimeException exception) {
      throw unresolved();
    }
  }

  private static GameInstanceDto requireExactRunningTarget(
      RunOwnedInitialLaunchResult launch, RunOwnedInitialAdmissionFixtureCapability capability) {
    if (launch == null || launch.gameInstance() == null || launch.activeLifecycleEpoch() <= 0L) {
      throw unresolved();
    }
    GameInstanceDto target = launch.gameInstance();
    if (target.id() == null
        || target.id() <= 0L
        || target.tenantId() == null
        || target.tenantId() != capability.tenantId()
        || target.gameTemplateId() == null
        || target.gameTemplateId() != capability.gameTemplateId()
        || target.ownerAccountId() == null
        || target.ownerAccountId() != capability.ownerAccountId()
        || target.versionId() == null
        || target.versionId() <= 0L
        || target.launchDescriptorId() == null
        || target.launchDescriptorId().isBlank()
        || !RUNNING.equals(target.status())) {
      throw unresolved();
    }
    return target;
  }

  private static void requireExactCatalog(
      InitialAdmissionBindCatalog catalog, InitialAdmissionBindCatalogDescriptor descriptor) {
    if (catalog == null
        || catalog.realmId() == null
        || catalog.playableStateNamespaceId() == null
        || catalog.tenantId() != descriptor.tenantId()
        || catalog.gameTemplateId() != descriptor.gameTemplateId()
        || !descriptor.worldSlug().equals(catalog.worldSlug())
        || !descriptor.worldDisplayName().equals(catalog.worldDisplayName())
        || !descriptor.realmSlug().equals(catalog.realmSlug())
        || !descriptor.realmDisplayName().equals(catalog.realmDisplayName())
        || catalog.catalogRevision() <= 0L
        || !catalog.visible()
        || !catalog.publicProductionRealm()
        || catalog.requiresCharacterSelection() != descriptor.requiresCharacterSelection()
        || !RunOwnedInitialAdmissionFixtureCapability.STATE_SCOPE.equals(catalog.stateScope())
        || !RunOwnedInitialAdmissionFixtureCapability.CHARACTER_CREATION_POLICY.equals(
            catalog.characterCreationPolicy())) {
      throw unresolved();
    }
  }

  private static void requireExactAttempt(
      InitialAdmissionBindAttempt attempt,
      InitialAdmissionBindRequest request,
      InitialAdmissionBindCatalog catalog,
      String holdId,
      String holdFence) {
    if (attempt == null
        || attempt.attemptId() == null
        || attempt.tenantId() != request.tenantId()
        || !request.initialAdmissionRequestId().equals(attempt.initialAdmissionRequestId())
        || !request.requestDigest().equals(attempt.requestDigest())
        || !catalog.realmId().equals(attempt.realmId())
        || !catalog.playableStateNamespaceId().equals(attempt.playableStateNamespaceId())
        || !RunOwnedInitialAdmissionFixtureCapability.STATE_SCOPE.equals(
            attempt.playableStateScope())
        || !attempt.expectedNoPriorPointer()
        || attempt.catalogRevision() != catalog.catalogRevision()
        || attempt.gameInstanceId() != request.gameInstanceId()
        || attempt.versionId() != request.versionId()
        || attempt.activeLifecycleEpoch() != request.activeLifecycleEpoch()
        || (holdId != null && !holdId.equals(asCanonicalUuid(attempt.holdId())))
        || (holdFence != null && !holdFence.equals(asCanonicalUuid(attempt.holdFence())))) {
      throw unresolved();
    }
  }

  private static InitialAdmissionBindHoldBinding requireExactHold(
      InitialAdmissionBindHold hold,
      InitialAdmissionBindRequest request,
      InitialAdmissionBindCatalog catalog,
      long gameInstanceId,
      long versionId,
      long activeLifecycleEpoch) {
    if (hold == null
        || !isCanonicalUuid(hold.getHoldId())
        || !isCanonicalUuid(hold.getHoldFence())
        || !Long.toString(request.tenantId()).equals(hold.getTenantId())
        || !catalog.realmId().toString().equals(hold.getRealmUuid())
        || !catalog
            .playableStateNamespaceId()
            .toString()
            .equals(hold.getPlayableStateNamespaceUuid())
        || hold.getPlayableStateScope() != PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED
        || !Long.toString(gameInstanceId).equals(hold.getGameInstanceId())
        || !Long.toString(versionId).equals(hold.getVersionId())
        || hold.getActiveLifecycleEpoch() != activeLifecycleEpoch
        || !request.initialAdmissionRequestId().equals(hold.getInitialAdmissionRequestId())
        || !request.requestDigest().equals(hold.getRequestDigest())
        || !hold.getExpectedNoPriorPointer()
        || hold.getExpectedCatalogRevision() != catalog.catalogRevision()
        || hold.getStatus()
            == InitialAdmissionBindHoldStatus.INITIAL_ADMISSION_BIND_HOLD_STATUS_UNSPECIFIED) {
      throw unresolved();
    }
    return new InitialAdmissionBindHoldBinding(
        hold.getHoldId(),
        hold.getHoldFence(),
        request.tenantId(),
        hold.getRealmUuid(),
        hold.getPlayableStateNamespaceUuid(),
        RunOwnedInitialAdmissionFixtureCapability.STATE_SCOPE,
        gameInstanceId,
        versionId,
        activeLifecycleEpoch,
        request.initialAdmissionRequestId(),
        request.requestDigest(),
        true,
        catalog.catalogRevision());
  }

  private static BootstrapResult resultFromProof(
      InitialAdmissionBindOwnerProof proof,
      InitialAdmissionBindHoldBinding binding,
      InitialAdmissionBindAttempt attempt,
      String expectedRequestId) {
    if (!matchesExactProof(proof, binding, attempt, expectedRequestId)) {
      throw unresolved();
    }
    if (proof.outcome() == Outcome.COMMITTED) {
      return BootstrapResult.COMMITTED;
    }
    if (proof.outcome() == Outcome.ABORTED && proof.futureCommitPrevented()) {
      return BootstrapResult.ABORTED;
    }
    throw unresolved();
  }

  private static boolean matchesExactProof(
      InitialAdmissionBindOwnerProof proof,
      InitialAdmissionBindHoldBinding binding,
      InitialAdmissionBindAttempt attempt,
      String expectedRequestId) {
    if (proof == null
        || proof.holdId() == null
        || !binding.holdId().equals(proof.holdId())
        || !binding.holdFence().equals(proof.holdFence())
        || proof.tenantId() != binding.tenantId()
        || !binding.realmUuid().equals(proof.realmUuid())
        || !binding.playableStateNamespaceUuid().equals(proof.playableStateNamespaceUuid())
        || !binding.playableStateScope().equals(proof.playableStateScope())
        || proof.gameInstanceId() != binding.gameInstanceId()
        || proof.versionId() != binding.versionId()
        || proof.activeLifecycleEpoch() != binding.activeLifecycleEpoch()
        || !expectedRequestId.equals(proof.initialAdmissionRequestId())
        || !binding.requestDigest().equals(proof.requestDigest())
        || proof.expectedNoPriorPointer() != binding.expectedNoPriorPointer()
        || proof.expectedCatalogRevision() != binding.expectedCatalogRevision()
        || attempt == null
        || !attempt.attemptId().toString().equals(proof.ownerProofId())) {
      return false;
    }

    if (proof.outcome() == Outcome.COMMITTED) {
      return proof.pointerAuditId() != null
          && isCanonicalPositiveLong(proof.pointerAuditId())
          && proof.pointerVersion() == 1L
          && binding.requestDigest().equals(proof.pointerAuditRequestDigest())
          && !proof.futureCommitPrevented();
    }
    if (proof.outcome() == Outcome.ABORTED) {
      return proof.pointerAuditId() == null
          && proof.pointerVersion() == 0L
          && proof.pointerAuditRequestDigest() == null
          && proof.futureCommitPrevented();
    }
    return false;
  }

  private static boolean isTerminalHoldStatus(InitialAdmissionBindHoldStatus status) {
    return status == InitialAdmissionBindHoldStatus.INITIAL_ADMISSION_BIND_HOLD_STATUS_COMMITTED
        || status == InitialAdmissionBindHoldStatus.INITIAL_ADMISSION_BIND_HOLD_STATUS_ABORTED;
  }

  private static boolean isRetryableHoldStatus(InitialAdmissionBindHoldStatus status) {
    return status == InitialAdmissionBindHoldStatus.INITIAL_ADMISSION_BIND_HOLD_STATUS_PENDING
        || status
            == InitialAdmissionBindHoldStatus
                .INITIAL_ADMISSION_BIND_HOLD_STATUS_RECONCILIATION_REQUIRED;
  }

  private static String requestDigest(
      RunOwnedInitialAdmissionFixtureCapability capability,
      InitialAdmissionBindCatalog catalog,
      long gameInstanceId,
      long versionId,
      long activeLifecycleEpoch) {
    StringBuilder canonical = new StringBuilder();
    appendDigestField(canonical, "domain", DIGEST_DOMAIN);
    appendDigestField(canonical, "operationId", capability.operationId());
    appendDigestField(canonical, "tenantId", Long.toString(capability.tenantId()));
    appendDigestField(canonical, "gameTemplateId", Long.toString(capability.gameTemplateId()));
    appendDigestField(canonical, "ownerAccountId", Long.toString(capability.ownerAccountId()));
    appendDigestField(canonical, "worldSlug", capability.worldSlug());
    appendDigestField(canonical, "worldDisplayName", capability.worldDisplayName());
    appendDigestField(canonical, "realmSlug", capability.realmSlug());
    appendDigestField(canonical, "realmDisplayName", capability.realmDisplayName());
    appendDigestField(
        canonical,
        "requiresCharacterSelection",
        Boolean.toString(capability.requiresCharacterSelection()));
    appendDigestField(canonical, "realmId", catalog.realmId().toString());
    appendDigestField(
        canonical, "playableStateNamespaceId", catalog.playableStateNamespaceId().toString());
    appendDigestField(
        canonical, "playableStateScope", RunOwnedInitialAdmissionFixtureCapability.STATE_SCOPE);
    appendDigestField(canonical, "expectedNoPriorPointer", "true");
    appendDigestField(canonical, "gameInstanceId", Long.toString(gameInstanceId));
    appendDigestField(canonical, "versionId", Long.toString(versionId));
    appendDigestField(canonical, "activeLifecycleEpoch", Long.toString(activeLifecycleEpoch));
    appendDigestField(canonical, "catalogRevision", Long.toString(catalog.catalogRevision()));
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void appendDigestField(StringBuilder canonical, String name, String value) {
    int nameBytes = name.getBytes(StandardCharsets.UTF_8).length;
    int valueBytes = value.getBytes(StandardCharsets.UTF_8).length;
    canonical
        .append(nameBytes)
        .append(':')
        .append(name)
        .append(valueBytes)
        .append(':')
        .append(value);
  }

  private static String asCanonicalUuid(UUID value) {
    return value == null ? null : value.toString();
  }

  private static boolean isCanonicalUuid(String value) {
    try {
      return UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException | NullPointerException exception) {
      return false;
    }
  }

  private static boolean isCanonicalPositiveLong(String value) {
    try {
      long parsed = Long.parseLong(value);
      return parsed > 0L && Long.toString(parsed).equals(value);
    } catch (NumberFormatException | NullPointerException exception) {
      return false;
    }
  }

  private static RuntimeException unresolved() {
    return new IllegalStateException("Run-owned initial-admission fixture remains unresolved");
  }

  enum BootstrapResult {
    COMMITTED,
    ABORTED
  }
}
