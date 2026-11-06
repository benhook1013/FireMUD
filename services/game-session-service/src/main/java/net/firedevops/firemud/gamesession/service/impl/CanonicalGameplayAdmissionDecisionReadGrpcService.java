package net.firedevops.firemud.gamesession.service.impl;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionNeverAdmittedGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Candidate;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.OwnerProofOutcome;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleClient;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionDecision;
import net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionLaunchTarget;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.repository.CanonicalGameInstanceLaunchAssociationRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayBindingInventoryConflictException;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayBindingInventoryRepository;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayOriginalNewCandidateClosureRedisOwner;
import net.firedevops.firemud.gamesession.security.CanonicalGameplayAdmissionDecisionReadWorkloadGuard;
import net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService;
import net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayAdmissionDecisionReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayAdmissionDecisionRequest;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayAdmissionDecisionResponse;
import net.firedevops.firemud.gamesession.v1.GetOriginalNewNeverAdmittedDecisionRequest;
import net.firedevops.firemud.gamesession.v1.GetOriginalNewNeverAdmittedDecisionResponse;
import net.firedevops.firemud.gamesession.v1.GetPublishedRealmAdmissionOwnerReadRequest;
import net.firedevops.firemud.gamesession.v1.GetPublishedRealmAdmissionOwnerReadResponse;
import net.firedevops.firemud.gamesession.v1.ListVisiblePublicProductionAdmissionTargetsRequest;
import net.firedevops.firemud.gamesession.v1.ListVisiblePublicProductionAdmissionTargetsResponse;
import net.firedevops.firemud.gamesession.v1.ResolvePublishedRealmAdmissionTargetRequest;
import net.firedevops.firemud.gamesession.v1.ResolvePublishedRealmAdmissionTargetResponse;
import org.jooq.exception.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Unregistered Account-only current-target, published-source and retained-decision reads. */
public final class CanonicalGameplayAdmissionDecisionReadGrpcService
    extends CanonicalGameplayAdmissionDecisionReadServiceGrpc
        .CanonicalGameplayAdmissionDecisionReadServiceImplBase {
  private final CanonicalGameplayBindingInventoryRepository repository;
  private final CanonicalGameplayAdmissionDecisionReadWorkloadGuard workloadGuard;
  private final String workloadNamespace;
  private final CanonicalGameplayOriginalNewCandidateClosureRedisOwner closureOwner;
  private final CanonicalPlayerRouteReadService routeReader;
  private final CanonicalPublishedPlayerRouteReadService publishedReader;
  private final CanonicalGameInstanceLaunchAssociationRepository launchReader;
  private final WorldCanonicalInstanceLifecycleClient world;

  public CanonicalGameplayAdmissionDecisionReadGrpcService(
      CanonicalGameplayBindingInventoryRepository repository,
      CanonicalGameplayAdmissionDecisionReadWorkloadGuard workloadGuard,
      String workloadNamespace) {
    this(repository, workloadGuard, workloadNamespace, null);
  }

  public CanonicalGameplayAdmissionDecisionReadGrpcService(
      CanonicalGameplayBindingInventoryRepository repository,
      CanonicalGameplayAdmissionDecisionReadWorkloadGuard workloadGuard,
      String workloadNamespace,
      CanonicalGameplayOriginalNewCandidateClosureRedisOwner closureOwner) {
    this(repository, workloadGuard, workloadNamespace, closureOwner, null, null, null, null);
  }

  /** Complete strict-source composition; no default registration or World hold is acquired. */
  public CanonicalGameplayAdmissionDecisionReadGrpcService(
      CanonicalGameplayBindingInventoryRepository repository,
      CanonicalGameplayAdmissionDecisionReadWorkloadGuard workloadGuard,
      String workloadNamespace,
      CanonicalGameplayOriginalNewCandidateClosureRedisOwner closureOwner,
      CanonicalPlayerRouteReadService routeReader,
      CanonicalPublishedPlayerRouteReadService publishedReader,
      CanonicalGameInstanceLaunchAssociationRepository launchReader,
      WorldCanonicalInstanceLifecycleClient world) {
    this.repository = Objects.requireNonNull(repository, "Game Session decision owner required");
    this.workloadGuard =
        Objects.requireNonNull(workloadGuard, "Account-only decision-read guard required");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Game Session workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
    this.closureOwner = closureOwner;
    this.routeReader = routeReader;
    this.publishedReader = publishedReader;
    this.launchReader = launchReader;
    this.world = world;
  }

  /** Complete canonical discovery only; this manually composed receiver grants no admission. */
  @Override
  public void listVisiblePublicProductionAdmissionTargets(
      ListVisiblePublicProductionAdmissionTargetsRequest request,
      StreamObserver<ListVisiblePublicProductionAdmissionTargetsResponse> observer) {
    Objects.requireNonNull(observer);
    if (!requireSourceCallerOutsideSql(observer)) return;
    final CanonicalGameplayAdmissionDecisionReadGrpcCodec.DiscoveryRequest parsed;
    try {
      if (request == null) {
        fail(observer, Status.INVALID_ARGUMENT);
        return;
      }
      parsed = CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseDiscoveryRequest(request);
    } catch (IllegalArgumentException malformed) {
      fail(observer, Status.INVALID_ARGUMENT);
      return;
    } catch (RuntimeException unexpectedParserFailure) {
      fail(observer, Status.INTERNAL);
      return;
    }
    if (!workloadNamespace.equals(parsed.targetNamespace())) {
      fail(observer, Status.PERMISSION_DENIED);
      return;
    }
    if (routeReader == null) {
      fail(observer, Status.UNAVAILABLE);
      return;
    }
    final ListVisiblePublicProductionAdmissionTargetsResponse response;
    try {
      var before = readPreseededDiscoveryTargets();
      // Validate the complete first read before treating it as an expected collection.
      CanonicalGameplayAdmissionDecisionReadGrpcCodec.toDiscoveryResponse(parsed, before);
      var after = readPreseededDiscoveryTargets();
      response = CanonicalGameplayAdmissionDecisionReadGrpcCodec.toDiscoveryResponse(parsed, after);
      if (!new HashSet<>(before).equals(new HashSet<>(after))) {
        fail(observer, Status.FAILED_PRECONDITION);
        return;
      }
    } catch (RuntimeException failure) {
      failSource(observer, failure);
      return;
    }
    sendSource(observer, response);
  }

  @Override
  public void resolvePublishedRealmAdmissionTarget(
      ResolvePublishedRealmAdmissionTargetRequest request,
      StreamObserver<ResolvePublishedRealmAdmissionTargetResponse> observer) {
    Objects.requireNonNull(observer);
    if (!requireSourceCallerOutsideSql(observer)) return;
    if (request == null) {
      fail(observer, Status.INVALID_ARGUMENT);
      return;
    }
    final CanonicalGameplayAdmissionDecisionReadGrpcCodec.RetainedTargetSelector selector;
    try {
      selector =
          CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseTargetResolutionRequest(request);
    } catch (IllegalArgumentException malformed) {
      fail(observer, Status.INVALID_ARGUMENT);
      return;
    } catch (RuntimeException unexpectedParserFailure) {
      fail(observer, Status.INTERNAL);
      return;
    }
    if (!workloadNamespace.equals(selector.targetNamespace())) {
      fail(observer, Status.PERMISSION_DENIED);
      return;
    }
    if (routeReader == null) {
      fail(observer, Status.UNAVAILABLE);
      return;
    }
    final ResolvePublishedRealmAdmissionTargetResponse response;
    try {
      var before = routeReader.readCurrentTarget(selector.tenantId());
      CanonicalGameplayAdmissionDecisionReadGrpcCodec.requireRetainedTargetMatches(
          selector, toTarget(before));
      var after = routeReader.readCurrentTarget(selector.tenantId());
      if (!before.equals(after)) {
        fail(observer, Status.FAILED_PRECONDITION);
        return;
      }
      response =
          CanonicalGameplayAdmissionDecisionReadGrpcCodec.toTargetResolutionResponse(
              selector, toTarget(after));
    } catch (RuntimeException failure) {
      failSource(observer, failure);
      return;
    }
    sendSource(observer, response);
  }

  private List<Target> readPreseededDiscoveryTargets() {
    var selected = new ArrayList<Target>();
    var tenants = new HashSet<UUID>();
    var tenantSlugs = new HashSet<String>();
    var realms = new HashSet<UUID>();
    for (var route : routeReader.readVisiblePublicProductionTargets()) {
      // Qualify every source before filtering, so an excluded policy cannot hide bad authority.
      var target =
          CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseTargetEvidence(
              CanonicalGameplayAdmissionDecisionReadGrpcCodec.toTargetEvidence(toTarget(route)));
      if (!workloadNamespace.equals(target.targetNamespace())
          || !"SHARED".equals(target.playableStateScope())
          || target.ownerProofOutcome() != OwnerProofOutcome.COMMITTED
          || target.positiveDurableAbort()
          || !tenants.add(target.canonicalTenantId())
          || !tenantSlugs.add(target.tenantSlug())
          || !realms.add(target.realmId())) {
        throw new IllegalArgumentException("Discovery source has invalid or duplicate authority");
      }
      if ("PRESEEDED_ONLY".equals(target.characterCreationPolicy())) {
        selected.add(target);
      } else if (!"PLAYER_CREATED".equals(target.characterCreationPolicy())) {
        throw new IllegalArgumentException("Discovery source has unsupported character policy");
      }
    }
    return List.copyOf(selected);
  }

  @Override
  public void getPublishedRealmAdmissionOwnerRead(
      GetPublishedRealmAdmissionOwnerReadRequest request,
      StreamObserver<GetPublishedRealmAdmissionOwnerReadResponse> observer) {
    Objects.requireNonNull(observer);
    if (!requireSourceCallerOutsideSql(observer)) return;
    if (request == null) {
      fail(observer, Status.INVALID_ARGUMENT);
      return;
    }
    final CanonicalGameplayAdmissionDecisionReadGrpcCodec.PublishedRealmRequest parsed;
    try {
      parsed = CanonicalGameplayAdmissionDecisionReadGrpcCodec.parsePublishedRealmRequest(request);
    } catch (IllegalArgumentException malformed) {
      fail(observer, Status.INVALID_ARGUMENT);
      return;
    } catch (RuntimeException unexpectedParserFailure) {
      fail(observer, Status.INTERNAL);
      return;
    }
    if (!workloadNamespace.equals(parsed.selector().targetNamespace())) {
      fail(observer, Status.PERMISSION_DENIED);
      return;
    }
    if (routeReader == null || publishedReader == null || launchReader == null || world == null) {
      fail(observer, Status.UNAVAILABLE);
      return;
    }
    final GetPublishedRealmAdmissionOwnerReadResponse response;
    try {
      var before = routeReader.readCurrentTarget(parsed.selector().canonicalTenantUuid());
      if (!matchesPublishedRoute(before, parsed.selector())) {
        fail(observer, Status.FAILED_PRECONDITION);
        return;
      }
      var launchBefore = readCurrentLaunch(before);
      var sourceBefore = publishedReader.readCurrent(parsed.selector());
      var association = launchBefore.association();
      var descriptor = association.launchBindingEvidence().descriptor();
      var lifecycleRequest =
          new WorldCanonicalInstanceLifecycleEvidence.Request(
              1,
              parsed.selector().requestUuid(),
              workloadNamespace,
              before.canonicalTenantId(),
              before.worldSlug(),
              before.canonicalGameInstanceId(),
              before.playableStateNamespaceId(),
              before.playableStateScope(),
              true,
              association.controlPlaneRequestId(),
              before.canonicalVersionId(),
              descriptor.requestDigest(),
              descriptor.resultDigest(),
              association.launchBindingEvidence().releaseAttestation().evidenceDigest());
      requireOutsideSourceSql();
      var lifecycle = readWorld(lifecycleRequest);
      if (lifecycle == null
          || !lifecycleRequest.equals(lifecycle.request())
          || !"ACTIVE".equals(lifecycle.lifecycleStatus())
          || lifecycle.lifecycleEpoch() != before.activeWorldEpoch()
          || !association.launchBindingEvidence().equals(lifecycle.launchBinding())) {
        fail(observer, Status.FAILED_PRECONDITION);
        return;
      }
      var sourceAfter = publishedReader.readCurrent(parsed.selector());
      var after = routeReader.readCurrentTarget(parsed.selector().canonicalTenantUuid());
      if (!before.equals(after)) {
        fail(observer, Status.FAILED_PRECONDITION);
        return;
      }
      var launchAfter = readCurrentLaunch(after);
      if (!launchBefore.equals(launchAfter) || !sourceBefore.equals(sourceAfter)) {
        fail(observer, Status.FAILED_PRECONDITION);
        return;
      }
      response =
          CanonicalGameplayAdmissionDecisionReadGrpcCodec.toPublishedRealmResponse(
              parsed, sourceAfter, toTarget(after), lifecycle);
    } catch (RuntimeException failure) {
      failSource(observer, failure);
      return;
    }
    sendSource(observer, response);
  }

  private static boolean matchesPublishedRoute(
      CanonicalPlayableTarget route, CanonicalGameplayRosterOwnerReadEvidence.Request selector) {
    return selector.targetNamespace().equals(route.targetNamespace())
        && selector.canonicalTenantUuid().equals(route.canonicalTenantId())
        && selector.worldSlug().equals(route.worldSlug())
        && selector.realmUuid().equals(route.realmId())
        && selector.realmSlug().equals(route.realmSlug())
        && selector.playableStateNamespaceUuid().equals(route.playableStateNamespaceId())
        && selector.playableStateScope().equals(route.playableStateScope())
        && selector.canonicalGameInstanceUuid().equals(route.canonicalGameInstanceId())
        && selector.canonicalVersionUuid().equals(route.canonicalVersionId())
        && selector.expectedCatalogRevision() == route.catalogRevision()
        && selector.expectedPointerVersion() == route.pointerVersion()
        && selector.expectedActiveWorldEpoch() == route.activeWorldEpoch();
  }

  private CanonicalInitialAdmissionLaunchTarget readCurrentLaunch(CanonicalPlayableTarget route) {
    var launch =
        launchReader
            .readForInitialAdmission(
                workloadNamespace,
                route.canonicalTenantId(),
                route.realmId(),
                route.canonicalGameInstanceId())
            .orElseThrow(
                () ->
                    new CanonicalPublishedPlayerRouteReadService.StaleAuthorityException(
                        "Current launch source is absent"));
    var association = launch.association();
    if (!workloadNamespace.equals(association.targetNamespace())
        || !route.canonicalTenantId().equals(association.canonicalTenantId())
        || !route.realmId().equals(launch.realmId())
        || !route.worldSlug().equals(association.worldSlug())
        || !route.canonicalGameInstanceId().equals(association.gameInstanceUuid())
        || !route.playableStateNamespaceId().equals(association.playableStateNamespaceId())
        || !route.playableStateScope().equals(association.playableStateScope().name())
        || !association.publicProduction()
        || association.currentGameInstanceStatus()
            != CanonicalGameInstanceLaunchAssociation.CurrentGameInstanceStatus.RUNNING
        || !route.canonicalVersionId().equals(launch.canonicalVersionId())
        || route.gameInstanceId() != launch.gameInstanceId()
        || route.runtimeVersionId() != launch.runtimeVersionId()
        || route.gameSessionTenantId() != association.gameSessionTenantId()) {
      throw new CanonicalPublishedPlayerRouteReadService.StaleAuthorityException(
          "Current launch differs from pointer source");
    }
    return launch;
  }

  private WorldCanonicalInstanceLifecycleEvidence readWorld(
      WorldCanonicalInstanceLifecycleEvidence.Request request) {
    try {
      return world.read(request);
    } catch (IllegalStateException failure) {
      if (failure.getCause() instanceof IllegalArgumentException malformed) {
        throw new CanonicalPublishedPlayerRouteReadService.InvalidAuthorityException(
            "World lifecycle response is invalid", malformed);
      }
      throw new WorldReadUnavailableException(failure);
    }
  }

  private static void requireOutsideSourceSql() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new CanonicalPublishedPlayerRouteReadService.InvalidAuthorityException(
          "Independent World source read requires no SQL");
    }
  }

  private static final class WorldReadUnavailableException extends RuntimeException {
    private WorldReadUnavailableException(Throwable cause) {
      super(cause);
    }
  }

  private boolean requireSourceCallerOutsideSql(StreamObserver<?> observer) {
    try {
      workloadGuard.requireAccountCaller();
    } catch (StatusRuntimeException denied) {
      fail(observer, denied.getStatus());
      return false;
    } catch (RuntimeException unexpectedGuardFailure) {
      fail(observer, Status.INTERNAL);
      return false;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      fail(observer, Status.FAILED_PRECONDITION);
      return false;
    }
    return true;
  }

  private static void failSource(StreamObserver<?> observer, RuntimeException failure) {
    if (failure instanceof CanonicalPlayerRouteReadService.ReadUnavailableException
        || failure instanceof WorldReadUnavailableException
        || failure instanceof CanonicalPublishedPlayerRouteReadService.StorageUnavailableException
        || failure
            instanceof CanonicalPublishedPlayerRouteReadService.GameDesignUnavailableException
        || failure instanceof CannotCreateTransactionException
        || failure instanceof DataAccessResourceFailureException
        || failure instanceof TransientDataAccessException) {
      fail(observer, Status.UNAVAILABLE);
    } else if (failure instanceof StatusRuntimeException transport) {
      var code = transport.getStatus().getCode();
      fail(
          observer,
          switch (code) {
            case UNAVAILABLE, DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED, ABORTED -> Status.UNAVAILABLE;
            case NOT_FOUND,
                FAILED_PRECONDITION,
                INVALID_ARGUMENT,
                PERMISSION_DENIED,
                UNAUTHENTICATED ->
                Status.FAILED_PRECONDITION;
            default -> Status.INTERNAL;
          });
    } else if (failure instanceof CanonicalPlayerRouteReadService.InvalidAuthorityException
        || failure instanceof CanonicalPublishedPlayerRouteReadService.InvalidAuthorityException
        || failure instanceof CanonicalPublishedPlayerRouteReadService.StaleAuthorityException
        || failure instanceof IllegalArgumentException) {
      fail(observer, Status.FAILED_PRECONDITION);
    } else if (failure instanceof DataAccessException
        || failure instanceof org.springframework.dao.DataAccessException) {
      fail(observer, hasConnectionFailureSqlState(failure) ? Status.UNAVAILABLE : Status.INTERNAL);
    } else {
      fail(observer, Status.INTERNAL);
    }
  }

  private static <T> void sendSource(StreamObserver<T> observer, T response) {
    try {
      observer.onNext(response);
    } catch (RuntimeException callbackFailure) {
      fail(observer, Status.INTERNAL);
      return;
    }
    observer.onCompleted();
  }

  @Override
  public void getOriginalNewNeverAdmittedDecision(
      GetOriginalNewNeverAdmittedDecisionRequest request,
      StreamObserver<GetOriginalNewNeverAdmittedDecisionResponse> observer) {
    Objects.requireNonNull(observer);
    try {
      workloadGuard.requireAccountCaller();
    } catch (StatusRuntimeException denied) {
      fail(observer, denied.getStatus());
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      fail(observer, Status.FAILED_PRECONDITION);
      return;
    }
    if (request == null
        || request.getSerializedSize()
            > AccountGameplayAdmissionNeverAdmittedGrpcCodec.MAX_WIRE_BYTES) {
      fail(observer, Status.INVALID_ARGUMENT);
      return;
    }
    final AccountGameplayAdmissionNeverAdmittedGrpcCodec.DecisionRequest selector;
    try {
      selector = AccountGameplayAdmissionNeverAdmittedGrpcCodec.parseDecisionRequest(request);
      var lease = selector.originalLeaseEvidence();
      if (!workloadNamespace.equals(lease.carrier().get("targetNamespace"))
          || !("spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service")
              .equals(lease.carrier().get("callerWorkload"))) {
        fail(observer, Status.PERMISSION_DENIED);
        return;
      }
    } catch (IllegalArgumentException malformed) {
      fail(observer, Status.INVALID_ARGUMENT);
      return;
    } catch (RuntimeException unexpectedParserFailure) {
      fail(observer, Status.INTERNAL);
      return;
    }
    if (closureOwner == null) {
      fail(observer, Status.UNAVAILABLE);
      return;
    }
    final GetOriginalNewNeverAdmittedDecisionResponse response;
    try {
      response =
          closureOwner.withCurrentClosedCandidate(
              selector.transitionId(),
              actual -> {
                var source = repository.readOriginalNewAbandonmentSource(selector.transitionId());
                if (!actual.matches(source)
                    || !selector.abandonmentId().equals(source.abandonmentId())
                    || !selector
                        .originalLeaseEvidence()
                        .canonicalJson()
                        .equals(source.candidate().leaseEvidence().canonicalJson())) {
                  throw new CanonicalGameplayBindingInventoryConflictException(
                      "Negative decision differs from original source");
                }
                String closureDigest = actual.digest();
                try {
                  var candidate = source.candidate();
                  var evidence =
                      new AccountGameplayAdmissionNeverAdmittedGrpcCodec.NegativeDecisionEvidence(
                          candidate.leaseEvidence(),
                          selector.transitionId(),
                          source.abandonmentId(),
                          CanonicalGameplayOriginalNewCandidateClosureRedisOwner.candidateDigest(
                                  candidate)
                              .substring(7),
                          closureDigest.substring(7),
                          toCandidate(candidate.bindingIdentity()),
                          toTarget(candidate.targetEvidence()),
                          candidate.transition().bindingGeneration());
                  return AccountGameplayAdmissionNeverAdmittedGrpcCodec.toDecisionResponse(
                      selector, evidence);
                } catch (RuntimeException responseEncodingFailure) {
                  throw new IllegalStateException(
                      "Negative decision response encoding failed", responseEncodingFailure);
                }
              });
    } catch (CanonicalGameplayOriginalNewCandidateClosureRedisOwner.UnavailableException
        | CannotCreateTransactionException
        | DataAccessResourceFailureException
        | TransientDataAccessException
        | RedisConnectionException
        | RedisCommandTimeoutException unavailable) {
      fail(observer, Status.UNAVAILABLE);
      return;
    } catch (DataAccessException | org.springframework.dao.DataAccessException failure) {
      fail(observer, hasConnectionFailureSqlState(failure) ? Status.UNAVAILABLE : Status.INTERNAL);
      return;
    } catch (CanonicalGameplayBindingInventoryConflictException conflict) {
      fail(observer, Status.FAILED_PRECONDITION);
      return;
    } catch (RuntimeException unexpectedFailure) {
      fail(observer, Status.INTERNAL);
      return;
    }
    try {
      observer.onNext(response);
    } catch (RuntimeException observerFailure) {
      fail(observer, Status.INTERNAL);
      return;
    }
    observer.onCompleted();
  }

  @Override
  public void getCanonicalGameplayAdmissionDecision(
      GetCanonicalGameplayAdmissionDecisionRequest request,
      StreamObserver<GetCanonicalGameplayAdmissionDecisionResponse> observer) {
    Objects.requireNonNull(observer, "response observer required");
    try {
      // Authenticate before parsing or inspecting any request value.
      workloadGuard.requireAccountCaller();
    } catch (StatusRuntimeException denied) {
      fail(observer, denied.getStatus());
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      fail(observer, Status.FAILED_PRECONDITION);
      return;
    }
    if (request == null
        || request.getSerializedSize()
            > CanonicalGameplayAdmissionDecisionReadGrpcCodec.MAX_REQUEST_WIRE_BYTES) {
      fail(observer, Status.INVALID_ARGUMENT);
      return;
    }

    final CanonicalGameplayAdmissionDecisionReadGrpcCodec.RequestEvidence selector;
    try {
      selector = CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseRequest(request);
      var lease = selector.expectedLeaseEvidence();
      Object targetNamespace = lease.carrier().get("targetNamespace");
      Object callerWorkload = lease.carrier().get("callerWorkload");
      String expectedCaller =
          "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service";
      if (lease.leaseKind()
              != net.firedevops.firemud.common.account.admission
                  .AccountGameplayAdmissionLeaseEvidence.LeaseKind.NEW_BINDING
          || !workloadNamespace.equals(targetNamespace)
          || !expectedCaller.equals(callerWorkload)) {
        fail(observer, Status.PERMISSION_DENIED);
        return;
      }
    } catch (IllegalArgumentException malformed) {
      fail(observer, Status.INVALID_ARGUMENT);
      return;
    } catch (RuntimeException unexpectedParserFailure) {
      fail(observer, Status.INTERNAL);
      return;
    }

    final CanonicalGameplayAdmissionDecision decision;
    try {
      decision =
          repository.readAdmissionDecision(
              selector.bindingDecisionId(), selector.expectedLeaseEvidence());
    } catch (CannotCreateTransactionException
        | DataAccessResourceFailureException
        | TransientDataAccessException unavailable) {
      fail(observer, Status.UNAVAILABLE);
      return;
    } catch (DataAccessException | org.springframework.dao.DataAccessException failure) {
      fail(observer, hasConnectionFailureSqlState(failure) ? Status.UNAVAILABLE : Status.INTERNAL);
      return;
    } catch (CanonicalGameplayBindingInventoryConflictException conflict) {
      fail(observer, Status.FAILED_PRECONDITION);
      return;
    } catch (IllegalStateException conflict) {
      fail(observer, Status.FAILED_PRECONDITION);
      return;
    } catch (RuntimeException unexpectedRepositoryFailure) {
      fail(observer, Status.INTERNAL);
      return;
    }

    final boolean newBindingDecision;
    try {
      newBindingDecision =
          decision.leaseKind()
              == net.firedevops.firemud.common.account.admission
                  .AccountGameplayAdmissionLeaseEvidence.LeaseKind.NEW_BINDING;
    } catch (RuntimeException unexpectedDecisionFailure) {
      fail(observer, Status.INTERNAL);
      return;
    }
    if (!newBindingDecision) {
      fail(observer, Status.FAILED_PRECONDITION);
      return;
    }

    final GetCanonicalGameplayAdmissionDecisionResponse response;
    try {
      response =
          CanonicalGameplayAdmissionDecisionReadGrpcCodec.toResponse(
              selector,
              decision.bindingDecisionId(),
              decision.leaseEvidence(),
              decision.bindingGeneration(),
              decision.expectedOldBindingGeneration(),
              decision.resumeEpisodeId(),
              toCandidate(decision),
              toTarget(decision));
    } catch (RuntimeException responseEncodingFailure) {
      fail(observer, Status.INTERNAL);
      return;
    }

    try {
      observer.onNext(response);
    } catch (RuntimeException observerFailure) {
      fail(observer, Status.INTERNAL);
      return;
    }
    // Completion is already terminal: a callback failure must not cause a second terminal signal.
    observer.onCompleted();
  }

  private static Candidate toCandidate(CanonicalGameplayAdmissionDecision decision) {
    return toCandidate(decision.candidate());
  }

  private static Candidate toCandidate(
      net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity candidate) {
    return new Candidate(
        candidate.accountId(),
        candidate.tenantId(),
        candidate.playableStateNamespaceId(),
        candidate.characterId(),
        candidate.playableStateScope(),
        candidate.gameInstanceId(),
        candidate.runtimeGameInstanceId(),
        java.util.UUID.fromString(candidate.sessionId()),
        candidate.regionId(),
        candidate.regionEpoch(),
        candidate.issuerId(),
        candidate.issuerAuthGeneration(),
        candidate.issuerIndexLayoutVersion(),
        candidate.issuerIndexPartitionCount(),
        candidate.issuerIndexPartitionCapacity());
  }

  private static Target toTarget(CanonicalGameplayAdmissionDecision decision) {
    return toTarget(decision.targetEvidence());
  }

  private static Target toTarget(
      net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget target) {
    return new Target(
        target.targetNamespace(),
        target.tenantSlug(),
        target.canonicalTenantId(),
        target.worldSlug(),
        target.worldDisplayName(),
        target.realmId(),
        target.realmSlug(),
        target.realmDisplayName(),
        target.gameSessionTenantId(),
        target.gameInstanceId(),
        target.playableStateNamespaceId(),
        target.playableStateScope(),
        target.canonicalGameInstanceId(),
        target.canonicalVersionId(),
        target.runtimeVersionId(),
        target.catalogRevision(),
        target.pointerVersion(),
        target.admissionPointerSnapshotDigest(),
        target.activeWorldEpoch(),
        target.initialAdmissionRequestId(),
        target.initialAdmissionRequestDigest(),
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.OriginKind.valueOf(
            target.initialAdmissionOriginKind().name()),
        target.expectedPriorPointerVersion(),
        target.holdId(),
        target.holdFence(),
        target.holdBindingDigest(),
        target.auditEventId(),
        target.ownerProofDigest(),
        OwnerProofOutcome.valueOf(target.ownerProofOutcome().name()),
        target.positiveDurableAbort(),
        target.ownerProofTerminalAt(),
        target.characterCreationPolicy());
  }

  private static boolean hasConnectionFailureSqlState(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException exception) {
        String sqlState = exception.getSQLState();
        if (sqlState != null && sqlState.startsWith("08")) return true;
      }
    }
    return false;
  }

  private static void fail(StreamObserver<?> observer, Status status) {
    observer.onError(
        Status.fromCode(status.getCode())
            .withDescription("Canonical Game Session admission decision is unavailable")
            .asRuntimeException());
  }
}
