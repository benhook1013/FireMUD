package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.RetainedTargetSelector;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterOwnerReadEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleClient;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalGameInstanceLaunchAssociation;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionLaunchTarget;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.security.CanonicalGameplayAdmissionDecisionReadWorkloadGuard;
import net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService;
import net.firedevops.firemud.gamesession.service.CanonicalPublishedPlayerRouteReadService;
import net.firedevops.firemud.gamesession.service.impl.CanonicalGameplayAdmissionDecisionReadGrpcService;
import net.firedevops.firemud.gamesession.v1.GetPublishedRealmAdmissionOwnerReadRequest;
import net.firedevops.firemud.gamesession.v1.GetPublishedRealmAdmissionOwnerReadResponse;
import net.firedevops.firemud.gamesession.v1.ResolvePublishedRealmAdmissionTargetRequest;
import net.firedevops.firemud.gamesession.v1.ResolvePublishedRealmAdmissionTargetResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Controlled collaborator/codec tests; source-content and physical owner proof remain separate. */
class CanonicalGameplayAdmissionPublishedReadGrpcServiceTest {
  private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");

  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void certificateDenialPrecedesBothParsersAndEveryOwnerRead() {
    Fixture f = new Fixture();
    doThrow(Status.PERMISSION_DENIED.asRuntimeException()).when(f.guard).requireAccountCaller();
    try (var codec = mockStatic(CanonicalGameplayAdmissionDecisionReadGrpcCodec.class)) {
      f.service.resolvePublishedRealmAdmissionTarget(
          ResolvePublishedRealmAdmissionTargetRequest.getDefaultInstance(), f.resolverObserver);
      f.service.getPublishedRealmAdmissionOwnerRead(
          GetPublishedRealmAdmissionOwnerReadRequest.getDefaultInstance(), f.publishedObserver);
      codec.verifyNoInteractions();
    }
    assertFailure(f.resolverObserver, Status.Code.PERMISSION_DENIED);
    assertFailure(f.publishedObserver, Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(f.routes, f.published, f.inventory);
  }

  @Test
  void synchronizationRefusalPrecedesBothParsersAndEveryOwnerRead() {
    Fixture f = new Fixture();
    TransactionSynchronizationManager.initSynchronization();
    try (var codec = mockStatic(CanonicalGameplayAdmissionDecisionReadGrpcCodec.class)) {
      f.service.resolvePublishedRealmAdmissionTarget(
          ResolvePublishedRealmAdmissionTargetRequest.getDefaultInstance(), f.resolverObserver);
      f.service.getPublishedRealmAdmissionOwnerRead(
          GetPublishedRealmAdmissionOwnerReadRequest.getDefaultInstance(), f.publishedObserver);
      codec.verifyNoInteractions();
    }
    assertFailure(f.resolverObserver, Status.Code.FAILED_PRECONDITION);
    assertFailure(f.publishedObserver, Status.Code.FAILED_PRECONDITION);
    verifyNoInteractions(f.routes, f.published, f.inventory);
  }

  @Test
  void resolverReadsActualTenantOwnerTwiceAndPassesItsUnchangedTargetToClosedEncoder() {
    Fixture f = new Fixture();
    var request = ResolvePublishedRealmAdmissionTargetRequest.getDefaultInstance();
    var response =
        ResolvePublishedRealmAdmissionTargetResponse.newBuilder().addSchemaVersion(1).build();
    var route = route();
    when(f.routes.readCurrentTarget(TENANT)).thenReturn(route);
    try (var codec = mockStatic(CanonicalGameplayAdmissionDecisionReadGrpcCodec.class)) {
      codec
          .when(
              () ->
                  CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseTargetResolutionRequest(
                      request))
          .thenReturn(f.selector);
      codec
          .when(
              () ->
                  CanonicalGameplayAdmissionDecisionReadGrpcCodec.toTargetResolutionResponse(
                      eq(f.selector), any()))
          .thenReturn(response);
      f.service.resolvePublishedRealmAdmissionTarget(request, f.resolverObserver);
      var encoded =
          org.mockito.ArgumentCaptor.forClass(
              CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target.class);
      codec.verify(
          () ->
              CanonicalGameplayAdmissionDecisionReadGrpcCodec.toTargetResolutionResponse(
                  eq(f.selector), encoded.capture()));
      assertThat(encoded.getValue())
          .usingRecursiveComparison()
          .withComparatorForFields(
              java.util.Comparator.comparing(Object::toString),
              "initialAdmissionOriginKind",
              "ownerProofOutcome")
          .isEqualTo(route);
      codec.verify(
          () ->
              CanonicalGameplayAdmissionDecisionReadGrpcCodec.requireRetainedTargetMatches(
                  eq(f.selector), eq(encoded.getValue())));
    }
    verify(f.routes, times(2)).readCurrentTarget(TENANT);
    verify(f.resolverObserver).onNext(response);
    verify(f.resolverObserver).onCompleted();
    verify(f.resolverObserver, never()).onError(any());
    verifyNoInteractions(f.published, f.inventory);
  }

  @Test
  void resolverTargetChangeAndUnavailableStorageReturnNoSelectionEvidence() {
    for (boolean unavailable : new boolean[] {false, true}) {
      Fixture f = new Fixture();
      var request =
          CanonicalGameplayAdmissionDecisionReadGrpcCodec.toTargetResolutionRequest(f.selector);
      if (unavailable) {
        when(f.routes.readCurrentTarget(TENANT))
            .thenThrow(
                new CanonicalPlayerRouteReadService.ReadUnavailableException("private failure"));
      } else {
        var before = route();
        var changed = route();
        when(f.routes.readCurrentTarget(TENANT)).thenReturn(before, changed);
      }
      try (var codec = mockStatic(CanonicalGameplayAdmissionDecisionReadGrpcCodec.class)) {
        codec
            .when(
                () ->
                    CanonicalGameplayAdmissionDecisionReadGrpcCodec.parseTargetResolutionRequest(
                        request))
            .thenReturn(f.selector);
        f.service.resolvePublishedRealmAdmissionTarget(request, f.resolverObserver);
      }
      assertFailure(
          f.resolverObserver,
          unavailable ? Status.Code.UNAVAILABLE : Status.Code.FAILED_PRECONDITION);
      verifyNoInteractions(f.published, f.inventory);
    }
  }

  @Test
  void strictReadUsesPublishedOwnerBetweenCompleteRouteReadsAndReturnsItsExactSource() {
    Fixture f = new Fixture();
    var request = GetPublishedRealmAdmissionOwnerReadRequest.getDefaultInstance();
    var parsed = mock(CanonicalGameplayAdmissionDecisionReadGrpcCodec.PublishedRealmRequest.class);
    var selector = strictSelector(f.selector);
    when(parsed.selector()).thenReturn(selector);
    var source = mock(CanonicalGameplayRosterOwnerReadEvidence.class);
    var response =
        GetPublishedRealmAdmissionOwnerReadResponse.newBuilder().addSchemaVersion(1).build();
    var route = f.configureStrictRoute(selector);
    when(f.published.readCurrent(selector)).thenReturn(source);
    var worldResult = new AtomicReference<WorldCanonicalInstanceLifecycleEvidence>();
    doAnswer(
            invocation -> {
              var evidence = f.worldEvidence(invocation.getArgument(0), "ACTIVE", 7);
              worldResult.set(evidence);
              return evidence;
            })
        .when(f.world)
        .read(any());
    var encodedTarget =
        ArgumentCaptor.forClass(CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target.class);
    var encodedWorld = ArgumentCaptor.forClass(WorldCanonicalInstanceLifecycleEvidence.class);
    try (var codec = mockStatic(CanonicalGameplayAdmissionDecisionReadGrpcCodec.class)) {
      codec
          .when(
              () ->
                  CanonicalGameplayAdmissionDecisionReadGrpcCodec.parsePublishedRealmRequest(
                      request))
          .thenReturn(parsed);
      codec
          .when(
              () ->
                  CanonicalGameplayAdmissionDecisionReadGrpcCodec.toPublishedRealmResponse(
                      eq(parsed), eq(source), any(), any()))
          .thenReturn(response);
      f.service.getPublishedRealmAdmissionOwnerRead(request, f.publishedObserver);
      codec.verify(
          () ->
              CanonicalGameplayAdmissionDecisionReadGrpcCodec.toPublishedRealmResponse(
                  eq(parsed), eq(source), encodedTarget.capture(), encodedWorld.capture()));
    }
    assertThat(encodedTarget.getValue())
        .usingRecursiveComparison()
        .withComparatorForFields(
            java.util.Comparator.comparing(Object::toString),
            "initialAdmissionOriginKind",
            "ownerProofOutcome")
        .isEqualTo(route);
    assertThat(encodedWorld.getValue()).isSameAs(worldResult.get());
    var worldRequest =
        ArgumentCaptor.forClass(WorldCanonicalInstanceLifecycleEvidence.Request.class);
    var order = org.mockito.Mockito.inOrder(f.routes, f.published, f.world);
    order.verify(f.routes).readCurrentTarget(TENANT);
    order.verify(f.published).readCurrent(selector);
    order.verify(f.world).read(worldRequest.capture());
    order.verify(f.published).readCurrent(selector);
    order.verify(f.routes).readCurrentTarget(TENANT);
    assertThat(worldRequest.getValue())
        .usingRecursiveComparison()
        .isEqualTo(encodedWorld.getValue().request());
    assertThat(worldRequest.getValue().readRequestId()).isEqualTo(selector.requestUuid());
    assertThat(worldRequest.getValue().targetNamespace()).isEqualTo("test");
    assertThat(worldRequest.getValue().canonicalTenantId()).isEqualTo(TENANT);
    assertThat(worldRequest.getValue().worldSlug()).isEqualTo(selector.worldSlug());
    assertThat(worldRequest.getValue().canonicalGameInstanceId())
        .isEqualTo(selector.canonicalGameInstanceUuid());
    assertThat(worldRequest.getValue().playableStateNamespaceId())
        .isEqualTo(selector.playableStateNamespaceUuid());
    assertThat(worldRequest.getValue().playableStateScope()).isEqualTo("SHARED");
    assertThat(worldRequest.getValue().canonicalVersionId())
        .isEqualTo(selector.canonicalVersionUuid());
    assertThat(worldRequest.getValue().publicProduction()).isTrue();
    assertThat(encodedWorld.getValue().lifecycleStatus()).isEqualTo("ACTIVE");
    assertThat(encodedWorld.getValue().lifecycleEpoch()).isEqualTo(7);
    verify(f.publishedObserver).onNext(response);
    verify(f.publishedObserver).onCompleted();
    verifyNoInteractions(f.inventory);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "namespace",
        "tenant",
        "world",
        "realm",
        "realm-slug",
        "playable-namespace",
        "scope",
        "instance",
        "version",
        "catalog",
        "pointer",
        "epoch"
      })
  void staleRouteRefusesBeforeLaunchPublicationAndWorldEvenWhenDependencyIsUnavailable(
      String changed) {
    Fixture f = new Fixture();
    var selector = strictSelector(f.selector);
    var route = f.configureStrictRoute(selector);
    switch (changed) {
      case "namespace" -> when(route.targetNamespace()).thenReturn("other");
      case "tenant" -> when(route.canonicalTenantId()).thenReturn(UUID.randomUUID());
      case "world" -> when(route.worldSlug()).thenReturn("other");
      case "realm" -> when(route.realmId()).thenReturn(UUID.randomUUID());
      case "realm-slug" -> when(route.realmSlug()).thenReturn("other");
      case "playable-namespace" ->
          when(route.playableStateNamespaceId()).thenReturn(UUID.randomUUID());
      case "scope" -> when(route.playableStateScope()).thenReturn("ISOLATED");
      case "instance" -> when(route.canonicalGameInstanceId()).thenReturn(UUID.randomUUID());
      case "version" -> when(route.canonicalVersionId()).thenReturn(UUID.randomUUID());
      case "catalog" ->
          when(route.catalogRevision()).thenReturn(selector.expectedCatalogRevision() + 1);
      case "pointer" ->
          when(route.pointerVersion()).thenReturn(selector.expectedPointerVersion() + 1);
      case "epoch" ->
          when(route.activeWorldEpoch()).thenReturn(selector.expectedActiveWorldEpoch() + 1);
      default -> throw new IllegalArgumentException(changed);
    }
    when(f.published.readCurrent(selector))
        .thenThrow(
            new CanonicalPublishedPlayerRouteReadService.StorageUnavailableException(
                "private source"));
    f.service.getPublishedRealmAdmissionOwnerRead(
        CanonicalGameplayAdmissionDecisionReadGrpcCodec.toPublishedRealmRequest(selector),
        f.publishedObserver);
    assertFailure(f.publishedObserver, Status.Code.FAILED_PRECONDITION);
    verify(f.routes).readCurrentTarget(TENANT);
    verifyNoInteractions(f.launches, f.published, f.world, f.inventory);
  }

  @Test
  void strictReadPreservesUnavailableAndRejectsChangedRouteWithoutReturningSource() {
    for (boolean unavailable : new boolean[] {false, true}) {
      Fixture f = new Fixture();
      var request = GetPublishedRealmAdmissionOwnerReadRequest.getDefaultInstance();
      var parsed =
          mock(CanonicalGameplayAdmissionDecisionReadGrpcCodec.PublishedRealmRequest.class);
      var selector = strictSelector(f.selector);
      when(parsed.selector()).thenReturn(selector);
      var before = f.configureStrictRoute(selector);
      var changed = route();
      when(f.routes.readCurrentTarget(TENANT)).thenReturn(before, changed);
      if (unavailable) {
        when(f.published.readCurrent(selector))
            .thenThrow(
                new CanonicalPublishedPlayerRouteReadService.StorageUnavailableException(
                    "private source"));
      }
      try (var codec = mockStatic(CanonicalGameplayAdmissionDecisionReadGrpcCodec.class)) {
        codec
            .when(
                () ->
                    CanonicalGameplayAdmissionDecisionReadGrpcCodec.parsePublishedRealmRequest(
                        request))
            .thenReturn(parsed);
        f.service.getPublishedRealmAdmissionOwnerRead(request, f.publishedObserver);
      }
      assertFailure(
          f.publishedObserver,
          unavailable ? Status.Code.UNAVAILABLE : Status.Code.FAILED_PRECONDITION);
    }
  }

  @Test
  void strictReadRejectsMissingInactiveWrongEpochAndUnavailableWorldWithoutPositiveEvidence() {
    for (String outcome :
        java.util.List.of(
            "MISSING",
            "TERMINATING",
            "WRONG_EPOCH",
            "WRONG_REQUEST",
            "WRONG_BINDING",
            "UNAVAILABLE")) {
      Fixture f = new Fixture();
      var request = GetPublishedRealmAdmissionOwnerReadRequest.getDefaultInstance();
      var parsed =
          mock(CanonicalGameplayAdmissionDecisionReadGrpcCodec.PublishedRealmRequest.class);
      var selector = strictSelector(f.selector);
      when(parsed.selector()).thenReturn(selector);
      f.configureStrictRoute(selector);
      if ("UNAVAILABLE".equals(outcome)) {
        doThrow(Status.UNAVAILABLE.asRuntimeException()).when(f.world).read(any());
      } else if ("MISSING".equals(outcome)) {
        doReturn(null).when(f.world).read(any());
      } else {
        doAnswer(
                invocation -> {
                  var evidence =
                      f.worldEvidence(
                          invocation.getArgument(0),
                          "TERMINATING".equals(outcome) ? "TERMINATING" : "ACTIVE",
                          "WRONG_EPOCH".equals(outcome) ? 8 : 7);
                  if ("WRONG_REQUEST".equals(outcome))
                    when(evidence.request())
                        .thenReturn(mock(WorldCanonicalInstanceLifecycleEvidence.Request.class));
                  if ("WRONG_BINDING".equals(outcome))
                    when(evidence.launchBinding())
                        .thenReturn(mock(CompleteLaunchBindingEvidence.class));
                  return evidence;
                })
            .when(f.world)
            .read(any());
      }
      try (var codec = mockStatic(CanonicalGameplayAdmissionDecisionReadGrpcCodec.class)) {
        codec
            .when(
                () ->
                    CanonicalGameplayAdmissionDecisionReadGrpcCodec.parsePublishedRealmRequest(
                        request))
            .thenReturn(parsed);
        f.service.getPublishedRealmAdmissionOwnerRead(request, f.publishedObserver);
      }
      assertFailure(
          f.publishedObserver,
          "UNAVAILABLE".equals(outcome)
              ? Status.Code.UNAVAILABLE
              : Status.Code.FAILED_PRECONDITION);
      verify(f.published, times(1)).readCurrent(selector);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "absent",
        "namespace",
        "tenant",
        "realm",
        "world",
        "instance-uuid",
        "state-namespace",
        "state-scope",
        "non-production",
        "stopped",
        "version",
        "instance-id",
        "runtime-version",
        "numeric-tenant",
        "changed-after"
      })
  void strictReadRefusesAbsentMismatchedOrChangedLaunchAuthority(String change) {
    Fixture f = new Fixture();
    var request = GetPublishedRealmAdmissionOwnerReadRequest.getDefaultInstance();
    var parsed = mock(CanonicalGameplayAdmissionDecisionReadGrpcCodec.PublishedRealmRequest.class);
    var selector = strictSelector(f.selector);
    when(parsed.selector()).thenReturn(selector);
    f.configureStrictRoute(selector);
    var launch =
        f.launches
            .readForInitialAdmission(
                "test", TENANT, selector.realmUuid(), selector.canonicalGameInstanceUuid())
            .orElseThrow();
    var association = launch.association();
    switch (change) {
      case "absent" ->
          when(f.launches.readForInitialAdmission(
                  "test", TENANT, selector.realmUuid(), selector.canonicalGameInstanceUuid()))
              .thenReturn(Optional.empty());
      case "namespace" -> when(association.targetNamespace()).thenReturn("other");
      case "tenant" -> when(association.canonicalTenantId()).thenReturn(UUID.randomUUID());
      case "realm" -> when(launch.realmId()).thenReturn(UUID.randomUUID());
      case "world" -> when(association.worldSlug()).thenReturn("other");
      case "instance-uuid" -> when(association.gameInstanceUuid()).thenReturn(UUID.randomUUID());
      case "state-namespace" ->
          when(association.playableStateNamespaceId()).thenReturn(UUID.randomUUID());
      case "state-scope" ->
          when(association.playableStateScope()).thenReturn(RealmEntryPolicy.StateScope.ISOLATED);
      case "non-production" -> when(association.publicProduction()).thenReturn(false);
      case "stopped" ->
          when(association.currentGameInstanceStatus())
              .thenReturn(CanonicalGameInstanceLaunchAssociation.CurrentGameInstanceStatus.STOPPED);
      case "version" -> when(launch.canonicalVersionId()).thenReturn(UUID.randomUUID());
      case "instance-id" -> when(launch.gameInstanceId()).thenReturn(72L);
      case "runtime-version" -> when(launch.runtimeVersionId()).thenReturn(10L);
      case "numeric-tenant" -> when(association.gameSessionTenantId()).thenReturn(12L);
      case "changed-after" -> {
        var changed = mock(CanonicalInitialAdmissionLaunchTarget.class);
        when(association.currentRowVersion()).thenReturn(1L);
        var changedAssociation =
            mock(CanonicalGameInstanceLaunchAssociation.class, delegatesTo(association));
        when(changedAssociation.currentRowVersion()).thenReturn(2L);
        when(changed.association()).thenReturn(changedAssociation);
        doReturn(launch.realmId()).when(changed).realmId();
        doReturn(launch.canonicalVersionId()).when(changed).canonicalVersionId();
        doReturn(launch.gameInstanceId()).when(changed).gameInstanceId();
        doReturn(launch.runtimeVersionId()).when(changed).runtimeVersionId();
        when(f.launches.readForInitialAdmission(
                "test", TENANT, selector.realmUuid(), selector.canonicalGameInstanceUuid()))
            .thenReturn(Optional.of(launch), Optional.of(changed));
      }
      default -> throw new AssertionError(change);
    }
    try (var codec = mockStatic(CanonicalGameplayAdmissionDecisionReadGrpcCodec.class)) {
      codec
          .when(
              () ->
                  CanonicalGameplayAdmissionDecisionReadGrpcCodec.parsePublishedRealmRequest(
                      request))
          .thenReturn(parsed);
      f.service.getPublishedRealmAdmissionOwnerRead(request, f.publishedObserver);
    }
    assertFailure(f.publishedObserver, Status.Code.FAILED_PRECONDITION);
    if ("changed-after".equals(change)) {
      verify(f.world).read(any());
      verify(f.published, times(2)).readCurrent(selector);
    } else {
      verifyNoInteractions(f.world, f.published);
    }
    verifyNoInteractions(f.inventory);
  }

  @Test
  void strictReadRefusesPublicationChangeAfterTheHealthyWorldRead() {
    Fixture f = new Fixture();
    var request = GetPublishedRealmAdmissionOwnerReadRequest.getDefaultInstance();
    var parsed = mock(CanonicalGameplayAdmissionDecisionReadGrpcCodec.PublishedRealmRequest.class);
    var selector = strictSelector(f.selector);
    when(parsed.selector()).thenReturn(selector);
    f.configureStrictRoute(selector);
    when(f.published.readCurrent(selector))
        .thenReturn(
            mock(CanonicalGameplayRosterOwnerReadEvidence.class),
            mock(CanonicalGameplayRosterOwnerReadEvidence.class));
    try (var codec = mockStatic(CanonicalGameplayAdmissionDecisionReadGrpcCodec.class)) {
      codec
          .when(
              () ->
                  CanonicalGameplayAdmissionDecisionReadGrpcCodec.parsePublishedRealmRequest(
                      request))
          .thenReturn(parsed);
      f.service.getPublishedRealmAdmissionOwnerRead(request, f.publishedObserver);
    }
    verify(f.world).read(any());
    verify(f.published, times(2)).readCurrent(selector);
    assertFailure(f.publishedObserver, Status.Code.FAILED_PRECONDITION);
  }

  @Test
  void closedInvalidRequestAndAbsentCompositionFailWithoutSourceReads() {
    Fixture f = new Fixture();
    f.service.resolvePublishedRealmAdmissionTarget(
        ResolvePublishedRealmAdmissionTargetRequest.getDefaultInstance(), f.resolverObserver);
    assertFailure(f.resolverObserver, Status.Code.INVALID_ARGUMENT);
    var observer = resolverObserver();
    new CanonicalGameplayAdmissionDecisionReadGrpcService(f.inventory, f.guard, "test")
        .resolvePublishedRealmAdmissionTarget(
            CanonicalGameplayAdmissionDecisionReadGrpcCodec.toTargetResolutionRequest(f.selector),
            observer);
    assertFailure(observer, Status.Code.UNAVAILABLE);
    verifyNoInteractions(f.routes, f.published, f.inventory);
  }

  private static CanonicalGameplayRosterOwnerReadEvidence.Request strictSelector(
      RetainedTargetSelector selector) {
    return new CanonicalGameplayRosterOwnerReadEvidence.Request(
        selector.readRequestId(),
        selector.accountId(),
        "test",
        TENANT,
        selector.worldSlug(),
        selector.realmId(),
        selector.realmSlug(),
        selector.playableStateNamespaceId(),
        "SHARED",
        selector.gameInstanceId(),
        UUID.randomUUID(),
        selector.catalogRevision(),
        selector.pointerVersion(),
        7);
  }

  private static CanonicalPlayableTarget route() {
    var route = mock(CanonicalPlayableTarget.class);
    when(route.initialAdmissionOriginKind())
        .thenReturn(CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER);
    when(route.ownerProofOutcome())
        .thenReturn(CanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED);
    return route;
  }

  private static void assertFailure(StreamObserver<?> observer, Status.Code code) {
    var error = ArgumentCaptor.forClass(Throwable.class);
    verify(observer).onError(error.capture());
    assertThat(Status.fromThrowable(error.getValue()).getCode()).isEqualTo(code);
    assertThat(error.getValue()).hasMessageNotContaining("private");
    verify(observer, never()).onNext(any());
    verify(observer, never()).onCompleted();
  }

  @SuppressWarnings("unchecked")
  private static StreamObserver<ResolvePublishedRealmAdmissionTargetResponse> resolverObserver() {
    return mock(StreamObserver.class);
  }

  private static final class Fixture {
    private final CanonicalGameplayBindingInventoryRepository inventory =
        mock(CanonicalGameplayBindingInventoryRepository.class);
    private final CanonicalGameplayAdmissionDecisionReadWorkloadGuard guard =
        mock(CanonicalGameplayAdmissionDecisionReadWorkloadGuard.class);
    private final CanonicalPlayerRouteReadService routes =
        mock(CanonicalPlayerRouteReadService.class);
    private final CanonicalPublishedPlayerRouteReadService published =
        mock(CanonicalPublishedPlayerRouteReadService.class);
    private final CanonicalGameInstanceLaunchAssociationRepository launches =
        mock(CanonicalGameInstanceLaunchAssociationRepository.class);
    private final WorldCanonicalInstanceLifecycleClient world =
        mock(WorldCanonicalInstanceLifecycleClient.class);
    private final CompleteLaunchBindingEvidence binding = mock(CompleteLaunchBindingEvidence.class);
    private final RetainedTargetSelector selector =
        new RetainedTargetSelector(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "test",
            TENANT,
            "tenant",
            "earth",
            UUID.randomUUID(),
            "main",
            UUID.randomUUID(),
            "SHARED",
            UUID.randomUUID(),
            3,
            5);
    private final StreamObserver<ResolvePublishedRealmAdmissionTargetResponse> resolverObserver =
        resolverObserver();

    @SuppressWarnings("unchecked")
    private final StreamObserver<GetPublishedRealmAdmissionOwnerReadResponse> publishedObserver =
        mock(StreamObserver.class);

    private final CanonicalGameplayAdmissionDecisionReadGrpcService service =
        new CanonicalGameplayAdmissionDecisionReadGrpcService(
            inventory, guard, "test", null, routes, published, launches, world);

    private CanonicalPlayableTarget configureStrictRoute(
        CanonicalGameplayRosterOwnerReadEvidence.Request selector) {
      var current = route();
      when(current.targetNamespace()).thenReturn("test");
      when(current.canonicalTenantId()).thenReturn(TENANT);
      when(current.realmId()).thenReturn(selector.realmUuid());
      when(current.realmSlug()).thenReturn(selector.realmSlug());
      when(current.worldSlug()).thenReturn(selector.worldSlug());
      when(current.canonicalGameInstanceId()).thenReturn(selector.canonicalGameInstanceUuid());
      when(current.playableStateNamespaceId()).thenReturn(selector.playableStateNamespaceUuid());
      when(current.playableStateScope()).thenReturn("SHARED");
      when(current.canonicalVersionId()).thenReturn(selector.canonicalVersionUuid());
      when(current.catalogRevision()).thenReturn(selector.expectedCatalogRevision());
      when(current.pointerVersion()).thenReturn(selector.expectedPointerVersion());
      when(current.activeWorldEpoch()).thenReturn(7L);
      when(current.gameSessionTenantId()).thenReturn(11L);
      when(current.gameInstanceId()).thenReturn(71L);
      when(current.runtimeVersionId()).thenReturn(9L);
      when(routes.readCurrentTarget(TENANT)).thenReturn(current);
      var target = mock(CanonicalInitialAdmissionLaunchTarget.class);
      var association = mock(CanonicalGameInstanceLaunchAssociation.class);
      when(target.association()).thenReturn(association);
      when(target.realmId()).thenReturn(selector.realmUuid());
      when(target.canonicalVersionId()).thenReturn(selector.canonicalVersionUuid());
      when(target.gameInstanceId()).thenReturn(71L);
      when(target.runtimeVersionId()).thenReturn(9L);
      when(association.targetNamespace()).thenReturn("test");
      when(association.canonicalTenantId()).thenReturn(TENANT);
      when(association.worldSlug()).thenReturn(selector.worldSlug());
      when(association.gameInstanceUuid()).thenReturn(selector.canonicalGameInstanceUuid());
      when(association.playableStateNamespaceId())
          .thenReturn(selector.playableStateNamespaceUuid());
      when(association.playableStateScope()).thenReturn(RealmEntryPolicy.StateScope.SHARED);
      when(association.publicProduction()).thenReturn(true);
      when(association.currentGameInstanceStatus())
          .thenReturn(CanonicalGameInstanceLaunchAssociation.CurrentGameInstanceStatus.RUNNING);
      when(association.gameSessionTenantId()).thenReturn(11L);
      when(association.controlPlaneRequestId()).thenReturn("controlled-launch");
      when(association.launchBindingEvidence()).thenReturn(binding);
      var descriptor = mock(AuthoredWorldLaunchDescriptorEvidence.class);
      var release = mock(AuthoredWorldReleaseAttestationEvidence.class);
      when(binding.descriptor()).thenReturn(descriptor);
      when(binding.releaseAttestation()).thenReturn(release);
      when(descriptor.requestDigest()).thenReturn("sha256:" + "a".repeat(64));
      when(descriptor.resultDigest()).thenReturn("sha256:" + "b".repeat(64));
      when(release.evidenceDigest()).thenReturn("sha256:" + "c".repeat(64));
      when(launches.readForInitialAdmission(
              "test", TENANT, selector.realmUuid(), selector.canonicalGameInstanceUuid()))
          .thenReturn(Optional.of(target));
      when(published.readCurrent(selector))
          .thenReturn(mock(CanonicalGameplayRosterOwnerReadEvidence.class));
      when(world.read(any()))
          .thenAnswer(
              invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
                return worldEvidence(invocation.getArgument(0), "ACTIVE", 7);
              });
      return current;
    }

    private WorldCanonicalInstanceLifecycleEvidence worldEvidence(
        WorldCanonicalInstanceLifecycleEvidence.Request request, String status, long epoch) {
      var evidence = mock(WorldCanonicalInstanceLifecycleEvidence.class);
      when(evidence.request()).thenReturn(request);
      when(evidence.launchBinding()).thenReturn(binding);
      when(evidence.lifecycleStatus()).thenReturn(status);
      when(evidence.lifecycleEpoch()).thenReturn(epoch);
      return evidence;
    }
  }
}
