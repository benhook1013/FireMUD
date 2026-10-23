package net.firedevops.firemud.gamesession.charactercreate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.ReadCharacterCreatePublicSourceRequest;
import net.firedevops.firemud.gamesession.v1.ReadCharacterCreatePublicSourceResponse;
import net.firedevops.firemud.gamesession.v1.ReadPlayerCreatedRosterSourceRequest;
import net.firedevops.firemud.gamesession.v1.ReadPlayerCreatedRosterSourceResponse;
import net.firedevops.firemud.gamesession.v1.ResolveCharacterCreatePublicTargetRequest;
import net.firedevops.firemud.gamesession.v1.ResolveCharacterCreatePublicTargetResponse;
import org.junit.jupiter.api.Test;

class GameSessionCharacterCreatePublicSourceGrpcServiceTest {
  @Test
  void rosterSourceRequiresExactEntityPeerAndRejectsCallerContextBeforeSourceAccess() {
    var source = mock(GameSessionCharacterCreatePublicSourceService.class);
    var adapter = new GameSessionCharacterCreatePublicSourceGrpcService(source, "firemud");
    var request = GameSessionCharacterCreatePublicSourceServiceTest.rosterRequest();
    assertThat(roster(adapter, null, "firemud", request).code)
        .isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(roster(adapter, "account-service", "firemud", request).code)
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(roster(adapter, "entity-management-service", "other", request).code)
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    SessionContext.setContext(UUID.randomUUID().toString(), List.of(), Map.of());
    try {
      assertThat(roster(adapter, "entity-management-service", "firemud", request).code)
          .isEqualTo(Status.Code.PERMISSION_DENIED);
    } finally {
      SessionContext.clear();
    }
    SessionContext.setContext(null, List.of(), Map.of(), true, "entity-management-service", "test");
    try {
      assertThat(roster(adapter, "entity-management-service", "firemud", request).code)
          .isEqualTo(Status.Code.PERMISSION_DENIED);
    } finally {
      SessionContext.clear();
    }
    verifyNoInteractions(source);
  }

  @Test
  void rosterSourceRejectsMalformedClosedRequestAndAmbientSqlBeforeSourceAccess() {
    var source = mock(GameSessionCharacterCreatePublicSourceService.class);
    var adapter = new GameSessionCharacterCreatePublicSourceGrpcService(source, "firemud");
    var request = GameSessionCharacterCreatePublicSourceServiceTest.rosterRequest();
    for (var invalid :
        List.of(
            request.toBuilder().setSchemaVersion(2).build(),
            request.toBuilder().setReadRequestId("not-uuid").build(),
            request.toBuilder().setTargetNamespace("other").build(),
            request.toBuilder().setPlayableStateScope("ISOLATED").build(),
            request.toBuilder().setExpectedCanonicalVersionId("42").build(),
            request.toBuilder().setActiveWorldEpoch(0).build(),
            request.toBuilder().setCatalogRevision(0).build(),
            request.toBuilder().setPointerVersion(0).build(),
            request.toBuilder().setWorldSlug("W").build(),
            request.toBuilder().setRealmSlug("").build(),
            request.toBuilder().setWorldSlug("w".repeat(17000)).build(),
            request.toBuilder()
                .setUnknownFields(
                    com.google.protobuf.UnknownFieldSet.newBuilder()
                        .addField(
                            88,
                            com.google.protobuf.UnknownFieldSet.Field.newBuilder()
                                .addVarint(1)
                                .build())
                        .build())
                .build())) {
      assertThat(roster(adapter, "entity-management-service", "firemud", invalid).code)
          .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }
    org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
    try {
      assertThat(roster(adapter, "entity-management-service", "firemud", request).code)
          .isEqualTo(Status.Code.FAILED_PRECONDITION);
    } finally {
      org.springframework.transaction.support.TransactionSynchronizationManager
          .clearSynchronization();
    }
    verifyNoInteractions(source);
  }

  @Test
  void rosterSourcePreservesExactResponseOwnerErrorAndRefusesChangedEcho() {
    var source = mock(GameSessionCharacterCreatePublicSourceService.class);
    var adapter = new GameSessionCharacterCreatePublicSourceGrpcService(source, "firemud");
    var request = GameSessionCharacterCreatePublicSourceServiceTest.rosterRequest();
    var response =
        ReadPlayerCreatedRosterSourceResponse.newBuilder()
            .setSchemaVersion(1)
            .setRequest(request)
            .setPublishedPolicySetEvidence(
                com.google.protobuf.ByteString.copyFromUtf8("controlled-original-sealed-set"))
            .build();
    when(source.readPlayerCreatedRosterSource(request))
        .thenReturn(response)
        .thenThrow(Status.UNAVAILABLE.asRuntimeException())
        .thenReturn(
            response.toBuilder().setRequest(request.toBuilder().setActiveWorldEpoch(8)).build());
    assertThat(roster(adapter, "entity-management-service", "firemud", request).value)
        .isEqualTo(response);
    assertThat(roster(adapter, "entity-management-service", "firemud", request).code)
        .isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(roster(adapter, "entity-management-service", "firemud", request).code)
        .isEqualTo(Status.Code.FAILED_PRECONDITION);
  }

  private static RosterCollector roster(
      GameSessionCharacterCreatePublicSourceGrpcService service,
      String peer,
      String namespace,
      ReadPlayerCreatedRosterSourceRequest request) {
    var collector = new RosterCollector();
    var context =
        peer == null
            ? Context.current()
            : Context.current()
                .withValue(
                    GrpcPeerIdentity.CONTEXT_KEY,
                    new GrpcPeerIdentity(
                        "spiffe://firemud/ns/" + namespace + "/sa/" + peer, namespace, peer));
    context.run(() -> service.readPlayerCreatedRosterSource(request, collector));
    return collector;
  }

  private static final class RosterCollector
      implements StreamObserver<ReadPlayerCreatedRosterSourceResponse> {
    ReadPlayerCreatedRosterSourceResponse value;
    Status.Code code = Status.Code.OK;

    @Override
    public void onNext(ReadPlayerCreatedRosterSourceResponse value) {
      this.value = value;
    }

    @Override
    public void onError(Throwable failure) {
      code = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {}
  }

  @Test
  void preScopeResolutionRequiresExactAccountPeerAndCanonicalFriendlySelectors() {
    var source = mock(GameSessionCharacterCreatePublicSourceService.class);
    var adapter = new GameSessionCharacterCreatePublicSourceGrpcService(source, "firemud");
    var request = GameSessionCharacterCreatePublicSourceServiceTest.resolveRequest();
    assertThat(resolve(adapter, null, "firemud", request).code)
        .isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(resolve(adapter, "entity-management-service", "firemud", request).code)
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(resolve(adapter, "account-service", "other", request).code)
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    SessionContext.setContext(UUID.randomUUID().toString(), List.of(), Map.of());
    try {
      assertThat(resolve(adapter, "account-service", "firemud", request).code)
          .isEqualTo(Status.Code.PERMISSION_DENIED);
    } finally {
      SessionContext.clear();
    }
    for (var invalid :
        List.of(
            request.toBuilder().setSchemaVersion(2).build(),
            request.toBuilder().setReadRequestId("not-uuid").build(),
            request.toBuilder().setTargetNamespace("other").build(),
            request.toBuilder().setWorldSlug("World").build(),
            request.toBuilder().setRealmSlug("").build(),
            request.toBuilder().setWorldSlug("w".repeat(17000)).build())) {
      assertThat(resolve(adapter, "account-service", "firemud", invalid).code)
          .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }
    verifyNoInteractions(source);
    var response =
        ResolveCharacterCreatePublicTargetResponse.newBuilder()
            .setSchemaVersion(1)
            .setRequest(request)
            .build();
    when(source.resolve(request)).thenReturn(response);
    assertThat(resolve(adapter, "account-service", "firemud", request).value).isEqualTo(response);
  }

  @Test
  void rejectsMissingWrongAndForeignPeersBeforeSourceAccess() {
    var source = mock(GameSessionCharacterCreatePublicSourceService.class);
    var adapter = new GameSessionCharacterCreatePublicSourceGrpcService(source, "firemud");
    assertThat(call(adapter, null, "firemud", request()).code)
        .isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(call(adapter, "entity-management-service", "firemud", request()).code)
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(call(adapter, "account-service", "other", request()).code)
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(source);
  }

  @Test
  void rejectsBearerCallerContextAndMalformedFieldsBeforeSourceAccess() {
    var source = mock(GameSessionCharacterCreatePublicSourceService.class);
    var adapter = new GameSessionCharacterCreatePublicSourceGrpcService(source, "firemud");
    SessionContext.setContext(UUID.randomUUID().toString(), List.of(), Map.of());
    try {
      assertThat(call(adapter, "account-service", "firemud", request()).code)
          .isEqualTo(Status.Code.PERMISSION_DENIED);
    } finally {
      SessionContext.clear();
    }
    for (var invalid :
        List.of(
            request().toBuilder().setSchemaVersion(2).build(),
            request().toBuilder().setReadRequestId("not-uuid").build(),
            request().toBuilder().setTargetNamespace("other").build(),
            request().toBuilder().setPlayableStateScope("ISOLATED").build(),
            request().toBuilder().setEvaluatedAt("2026-10-24T00:00:00.000Z").build(),
            request().toBuilder().setConnectScopeExpiresAt(request().getEvaluatedAt()).build(),
            request().toBuilder().setWorldSlug("x".repeat(17000)).build())) {
      assertThat(call(adapter, "account-service", "firemud", invalid).code)
          .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }
    verifyNoInteractions(source);
  }

  @Test
  void echoesExactSelectorsAndPreservesOwnerUnavailableWithoutSubstitution() {
    var source = mock(GameSessionCharacterCreatePublicSourceService.class);
    var adapter = new GameSessionCharacterCreatePublicSourceGrpcService(source, "firemud");
    var expected =
        ReadCharacterCreatePublicSourceResponse.newBuilder()
            .setSchemaVersion(1)
            .setRequest(request())
            .build();
    when(source.read(request()))
        .thenReturn(expected)
        .thenThrow(Status.UNAVAILABLE.asRuntimeException());
    assertThat(call(adapter, "account-service", "firemud", request()).value).isEqualTo(expected);
    assertThat(call(adapter, "account-service", "firemud", request()).code)
        .isEqualTo(Status.Code.UNAVAILABLE);
  }

  private static ReadCharacterCreatePublicSourceRequest request() {
    return GameSessionCharacterCreatePublicSourceServiceTest.request();
  }

  private static Collector call(
      GameSessionCharacterCreatePublicSourceGrpcService service,
      String peer,
      String namespace,
      ReadCharacterCreatePublicSourceRequest request) {
    var collector = new Collector();
    var context =
        peer == null
            ? Context.current()
            : Context.current()
                .withValue(
                    GrpcPeerIdentity.CONTEXT_KEY,
                    new GrpcPeerIdentity(
                        "spiffe://firemud/ns/" + namespace + "/sa/" + peer, namespace, peer));
    context.run(() -> service.readCharacterCreatePublicSource(request, collector));
    return collector;
  }

  private static final class Collector
      implements StreamObserver<ReadCharacterCreatePublicSourceResponse> {
    ReadCharacterCreatePublicSourceResponse value;
    Status.Code code = Status.Code.OK;

    @Override
    public void onNext(ReadCharacterCreatePublicSourceResponse value) {
      this.value = value;
    }

    @Override
    public void onError(Throwable failure) {
      code = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {}
  }

  private static ResolveCollector resolve(
      GameSessionCharacterCreatePublicSourceGrpcService service,
      String peer,
      String namespace,
      ResolveCharacterCreatePublicTargetRequest request) {
    var collector = new ResolveCollector();
    var context =
        peer == null
            ? Context.current()
            : Context.current()
                .withValue(
                    GrpcPeerIdentity.CONTEXT_KEY,
                    new GrpcPeerIdentity(
                        "spiffe://firemud/ns/" + namespace + "/sa/" + peer, namespace, peer));
    context.run(() -> service.resolveCharacterCreatePublicTarget(request, collector));
    return collector;
  }

  private static final class ResolveCollector
      implements StreamObserver<ResolveCharacterCreatePublicTargetResponse> {
    ResolveCharacterCreatePublicTargetResponse value;
    Status.Code code = Status.Code.OK;

    @Override
    public void onNext(ResolveCharacterCreatePublicTargetResponse value) {
      this.value = value;
    }

    @Override
    public void onError(Throwable failure) {
      code = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {}
  }
}
