package net.firedevops.firemud.gamedesign.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadEvidence;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.gamedesign.CommandSource;
import net.firedevops.firemud.common.gamedesign.SelectedOwnerIntakeSourceProtoCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamedesign.v1.SelectedOwnerIntakeSourceRequest;
import net.firedevops.firemud.gamedesign.v1.SelectedOwnerIntakeSourceResponse;
import org.junit.jupiter.api.Test;

class GameDesignSelectedOwnerIntakeSourceGrpcServiceTest {
  private static final String NAMESPACE = "test";

  @Test
  void authenticatesAccountPeerAndRejectsMalformedOrMismatchedInputBeforeOwnerRead() {
    var owner = mock(GameDesignSelectedOwnerIntakeSourceReadService.class);
    var service = new GameDesignSelectedOwnerIntakeSourceGrpcService(owner, NAMESPACE);
    var valid = request(Owner.ENTITY_MANAGEMENT);
    var malformed = valid.toBuilder().setSchemaVersion(0).build();

    var absentPeer = new Collector();
    service.readSelectedSource(malformed, absentPeer);
    assertThat(absentPeer.error).isEqualTo(Status.Code.UNAUTHENTICATED);

    String[][] identities = {
      {"test", "game-design-service"},
      {"other", "account-service"},
      {"test", "world-management-service"}
    };
    for (String[] identity : identities) {
      var denied = new Collector();
      peer(identity[0], identity[1]).run(() -> service.readSelectedSource(valid, denied));
      assertThat(denied.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    var malformedResult = new Collector();
    peer(NAMESPACE, "account-service")
        .run(() -> service.readSelectedSource(malformed, malformedResult));
    assertThat(malformedResult.error).isEqualTo(Status.Code.INVALID_ARGUMENT);

    var wrongNamespace = new Collector();
    peer(NAMESPACE, "account-service")
        .run(
            () ->
                service.readSelectedSource(
                    valid.toBuilder().setTargetNamespace("other").build(), wrongNamespace));
    assertThat(wrongNamespace.error).isEqualTo(Status.Code.PERMISSION_DENIED);

    SessionContext.setContext("123", List.of(), java.util.Map.of());
    try {
      var endUser = new Collector();
      peer(NAMESPACE, "account-service").run(() -> service.readSelectedSource(valid, endUser));
      assertThat(endUser.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    } finally {
      SessionContext.clear();
    }

    verifyNoInteractions(owner);
  }

  @Test
  void rejectsChangedRequestFieldsUnknownFieldsAndOversizedWireBeforeOwnerRead() {
    var owner = mock(GameDesignSelectedOwnerIntakeSourceReadService.class);
    var service = new GameDesignSelectedOwnerIntakeSourceGrpcService(owner, NAMESPACE);
    var valid = request(Owner.AUTOMATION_SCRIPTING);
    var changedReader = valid.toBuilder().setIntendedReader("spiffe://wrong").build();
    var changedPurpose = valid.toBuilder().setPurpose("OTHER_PURPOSE").build();
    var unknown =
        valid.toBuilder()
            .mergeUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                    .build())
            .build();
    var oversized =
        valid.toBuilder()
            .setPreliminarySourceScope(
                ByteString.copyFrom(new byte[SelectedOwnerIntakeSourceProtoCodec.MAX_WIRE_BYTES]))
            .build();

    for (var invalid : List.of(changedReader, changedPurpose, unknown, oversized)) {
      var result = new Collector();
      peer(NAMESPACE, "account-service").run(() -> service.readSelectedSource(invalid, result));
      assertThat(result.error).isEqualTo(Status.Code.INVALID_ARGUMENT);
      assertThat(result.response).isNull();
    }

    verifyNoInteractions(owner);
  }

  @Test
  void returnsExactEchoAndActualCompleteExportForBothOwnerScopes() {
    var selected =
        SelectedOwnerIntakeSourceExportTest.binding(
            java.util.UUID.fromString("44444444-4444-4444-8444-444444444444"),
            CommandSource.deletePayload("absent-command"));
    for (Owner ownerType : List.of(Owner.ENTITY_MANAGEMENT, Owner.AUTOMATION_SCRIPTING)) {
      var scope = SelectedOwnerIntakeSourceExportTest.scope(ownerType, selected);
      var export =
          SelectedOwnerIntakeSourceExport.create(
              scope, SelectedOwnerIntakeSourceExportTest.sources(selected, "0"));
      var owner = mock(GameDesignSelectedOwnerIntakeSourceReadService.class);
      when(owner.readSource(any())).thenReturn(export);
      var service = new GameDesignSelectedOwnerIntakeSourceGrpcService(owner, NAMESPACE);
      var request = request(scope);
      var result = new Collector();

      peer(NAMESPACE, "account-service").run(() -> service.readSelectedSource(request, result));

      assertThat(result.error).isNull();
      assertThat(result.completed).isTrue();
      assertThat(result.response).isNotNull();
      assertThat(result.response.getRequest()).isEqualTo(request);
      var decodedRequest = SelectedOwnerIntakeSourceProtoCodec.fromRequest(request);
      var content =
          SelectedOwnerIntakeSourceProtoCodec.fromResponse(decodedRequest, result.response);
      assertThat(content.canonicalBytes()).isEqualTo(export.canonicalBytes());
      assertThat(content.digest()).isEqualTo(export.digest());
      verify(owner).readSource(scope);
    }
  }

  @Test
  void deniesAbsentOrMismatchedExportAndSanitizesOwnerFailures() {
    var scope =
        SelectedOwnerIntakeSourceExportTest.scope(
            Owner.ENTITY_MANAGEMENT,
            SelectedOwnerIntakeSourceExportTest.binding(
                java.util.UUID.fromString("44444444-4444-4444-8444-444444444444"),
                CommandSource.deletePayload("absent-command")));
    var request = request(scope);

    var missingOwner = mock(GameDesignSelectedOwnerIntakeSourceReadService.class);
    when(missingOwner.readSource(scope)).thenReturn(null);
    assertFailure(missingOwner, request, Status.Code.FAILED_PRECONDITION);

    var differentScope =
        SelectedOwnerIntakeSourceExportTest.scope(
            Owner.ENTITY_MANAGEMENT,
            SelectedOwnerIntakeSourceExportTest.binding(
                java.util.UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                CommandSource.deletePayload("absent-command")));
    var differentExport =
        SelectedOwnerIntakeSourceExport.create(
            differentScope,
            SelectedOwnerIntakeSourceExportTest.sources(differentScope.selected(), "0"));
    var mismatchedOwner = mock(GameDesignSelectedOwnerIntakeSourceReadService.class);
    when(mismatchedOwner.readSource(scope)).thenReturn(differentExport);
    assertFailure(mismatchedOwner, request, Status.Code.FAILED_PRECONDITION);

    var failingOwner = mock(GameDesignSelectedOwnerIntakeSourceReadService.class);
    when(failingOwner.readSource(scope))
        .thenThrow(Status.UNAVAILABLE.withDescription("private owner detail").asRuntimeException());
    var failure = invoke(failingOwner, request);
    assertThat(failure.error).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(failure.errorDescription).isNull();
    assertThat(failure.response).isNull();
  }

  private static void assertFailure(
      GameDesignSelectedOwnerIntakeSourceReadService owner,
      SelectedOwnerIntakeSourceRequest request,
      Status.Code code) {
    var result = invoke(owner, request);
    assertThat(result.error).isEqualTo(code);
    assertThat(result.response).isNull();
  }

  private static Collector invoke(
      GameDesignSelectedOwnerIntakeSourceReadService owner,
      SelectedOwnerIntakeSourceRequest request) {
    var service = new GameDesignSelectedOwnerIntakeSourceGrpcService(owner, NAMESPACE);
    var result = new Collector();
    peer(NAMESPACE, "account-service").run(() -> service.readSelectedSource(request, result));
    return result;
  }

  private static SelectedOwnerIntakeSourceRequest request(Owner owner) {
    var selected =
        SelectedOwnerIntakeSourceExportTest.binding(
            java.util.UUID.fromString("44444444-4444-4444-8444-444444444444"),
            CommandSource.deletePayload("absent-command"));
    return request(SelectedOwnerIntakeSourceExportTest.scope(owner, selected));
  }

  private static SelectedOwnerIntakeSourceRequest request(
      net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadScope scope) {
    return SelectedOwnerIntakeSourceProtoCodec.toRequest(
        SelectedOwnerIntakeSourceReadEvidence.Request.create(NAMESPACE, scope));
  }

  private static Context peer(String namespace, String service) {
    return Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + service)
                .orElseThrow());
  }

  private static final class Collector
      implements StreamObserver<SelectedOwnerIntakeSourceResponse> {
    Status.Code error;
    String errorDescription;
    SelectedOwnerIntakeSourceResponse response;
    boolean completed;

    @Override
    public void onNext(SelectedOwnerIntakeSourceResponse value) {
      response = value;
    }

    @Override
    public void onError(Throwable value) {
      error = Status.fromThrowable(value).getCode();
      errorDescription = Status.fromThrowable(value).getDescription();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
