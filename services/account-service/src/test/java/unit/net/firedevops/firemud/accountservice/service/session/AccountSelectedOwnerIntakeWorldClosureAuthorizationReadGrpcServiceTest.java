package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import net.firedevops.firemud.account.v1.ReadHeldSelectedOwnerWorldClosureAuthorizationRequest;
import net.firedevops.firemud.account.v1.ReadHeldSelectedOwnerWorldClosureAuthorizationResponse;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeWorldClosureAuthorizationReadGrpcCodec;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Synthetic handler tests only; they do not establish mTLS or PostgreSQL owner proof. */
class AccountSelectedOwnerIntakeWorldClosureAuthorizationReadGrpcServiceTest {
  @AfterEach
  void clearCallerContext() {
    SessionContext.clear();
  }

  @Test
  void authenticatesWorldAndRejectsEndUserBeforeParsingOrOwnerAccess() {
    var owner = mock(AccountSelectedOwnerIntakeWorldClosureAuthorizationReadService.class);
    var service =
        new AccountSelectedOwnerIntakeWorldClosureAuthorizationReadGrpcService(owner, "test");
    var malformed =
        ReadHeldSelectedOwnerWorldClosureAuthorizationRequest.newBuilder()
            .setTargetNamespace("test")
            .setSchemaVersion(-1)
            .build();

    var noPeer = new Collector();
    service.readHeldSelectedOwnerWorldClosureAuthorization(malformed, noPeer);
    assertThat(noPeer.error).isEqualTo(Status.Code.UNAUTHENTICATED);

    for (String workload :
        List.of(
            "account-service",
            "game-design-service",
            "game-logic-service",
            "entity-management-service",
            "automation-scripting-service")) {
      var denied = new Collector();
      peer("test", workload)
          .run(() -> service.readHeldSelectedOwnerWorldClosureAuthorization(malformed, denied));
      assertThat(denied.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    }
    var otherNamespace = new Collector();
    peer("other", "world-management-service")
        .run(
            () ->
                service.readHeldSelectedOwnerWorldClosureAuthorization(malformed, otherNamespace));
    assertThat(otherNamespace.error).isEqualTo(Status.Code.PERMISSION_DENIED);

    SessionContext.setContext("44", List.of(), java.util.Map.of());
    try {
      var endUser = new Collector();
      peer("test", "world-management-service")
          .run(() -> service.readHeldSelectedOwnerWorldClosureAuthorization(malformed, endUser));
      assertThat(endUser.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    } finally {
      SessionContext.clear();
    }

    var allowedPeerMalformed = new Collector();
    peer("test", "world-management-service")
        .run(
            () ->
                service.readHeldSelectedOwnerWorldClosureAuthorization(
                    malformed, allowedPeerMalformed));
    assertThat(allowedPeerMalformed.error).isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(owner);
  }

  @Test
  void returnsExactEchoForEntityAndAutomationButRejectsSubstitutedPurposeOrReader() {
    var owner = mock(AccountSelectedOwnerIntakeWorldClosureAuthorizationReadService.class);
    var service =
        new AccountSelectedOwnerIntakeWorldClosureAuthorizationReadGrpcService(owner, "test");

    for (Owner domain : List.of(Owner.ENTITY_MANAGEMENT, Owner.AUTOMATION_SCRIPTING)) {
      var binding = AccountSelectedOwnerIntakeAuthorizationReadServiceTest.binding(domain);
      var request =
          SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence.Request.create("test", binding);
      var wire = SelectedOwnerIntakeWorldClosureAuthorizationReadGrpcCodec.toRequest(request);
      var result = new Collector();
      peer("test", "world-management-service")
          .run(() -> service.readHeldSelectedOwnerWorldClosureAuthorization(wire, result));

      assertThat(result.error).isNull();
      assertThat(result.response).isNotNull();
      assertThat(result.response.getRequest()).isEqualTo(wire);
      assertThat(result.response.getHeld()).isTrue();

      for (var changed :
          List.of(
              wire.toBuilder()
                  .setIntendedReader("spiffe://firemud/ns/test/sa/entity-management-service")
                  .build(),
              wire.toBuilder().setClosureReadPurpose("ENTITY_INTAKE_RETENTION").build())) {
        var denied = new Collector();
        peer("test", "world-management-service")
            .run(() -> service.readHeldSelectedOwnerWorldClosureAuthorization(changed, denied));
        assertThat(denied.error).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(denied.response).isNull();
      }
    }
    verify(owner, times(2)).requireHeld(any());
  }

  @Test
  void rejectsWrongNamespaceClosedFieldsDigestUnknownFieldsAndMalformedSizes() {
    var owner = mock(AccountSelectedOwnerIntakeWorldClosureAuthorizationReadService.class);
    var service =
        new AccountSelectedOwnerIntakeWorldClosureAuthorizationReadGrpcService(owner, "test");
    var binding =
        AccountSelectedOwnerIntakeAuthorizationReadServiceTest.binding(Owner.ENTITY_MANAGEMENT);
    var valid =
        SelectedOwnerIntakeWorldClosureAuthorizationReadGrpcCodec.toRequest(
            SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence.Request.create(
                "test", binding));

    var wrongNamespace = new Collector();
    peer("test", "world-management-service")
        .run(
            () ->
                service.readHeldSelectedOwnerWorldClosureAuthorization(
                    valid.toBuilder().setTargetNamespace("other").build(), wrongNamespace));
    assertThat(wrongNamespace.error).isEqualTo(Status.Code.PERMISSION_DENIED);

    var changedDigest =
        valid.toBuilder().setIntakeAuthorizationDigest("sha256:" + "0".repeat(64)).build();
    var unknown =
        valid.toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();
    var malformedBinding =
        valid.toBuilder()
            .setOriginalIntakeAuthorizationBinding(ByteString.copyFrom(new byte[] {1, 2, 3}))
            .build();
    var oversizedBinding =
        valid.toBuilder()
            .setOriginalIntakeAuthorizationBinding(
                ByteString.copyFrom(
                    new byte[SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES + 1]))
            .build();
    for (var malformed : List.of(changedDigest, unknown, malformedBinding, oversizedBinding)) {
      var result = new Collector();
      peer("test", "world-management-service")
          .run(() -> service.readHeldSelectedOwnerWorldClosureAuthorization(malformed, result));
      assertThat(result.error).isEqualTo(Status.Code.INVALID_ARGUMENT);
      assertThat(result.response).isNull();
    }
    verifyNoInteractions(owner);
  }

  @Test
  void mapsOwnerReadFailureToNoHeldResponse() {
    var owner = mock(AccountSelectedOwnerIntakeWorldClosureAuthorizationReadService.class);
    org.mockito.Mockito.doThrow(Status.FAILED_PRECONDITION.asRuntimeException())
        .when(owner)
        .requireHeld(any());
    var service =
        new AccountSelectedOwnerIntakeWorldClosureAuthorizationReadGrpcService(owner, "test");
    var binding =
        AccountSelectedOwnerIntakeAuthorizationReadServiceTest.binding(Owner.ENTITY_MANAGEMENT);
    var wire =
        SelectedOwnerIntakeWorldClosureAuthorizationReadGrpcCodec.toRequest(
            SelectedOwnerIntakeWorldClosureAuthorizationReadEvidence.Request.create(
                "test", binding));
    var result = new Collector();

    peer("test", "world-management-service")
        .run(() -> service.readHeldSelectedOwnerWorldClosureAuthorization(wire, result));

    assertThat(result.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(result.response).isNull();
  }

  private static Context peer(String namespace, String workload) {
    return Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + workload)
                .orElseThrow());
  }

  private static final class Collector
      implements StreamObserver<ReadHeldSelectedOwnerWorldClosureAuthorizationResponse> {
    private ReadHeldSelectedOwnerWorldClosureAuthorizationResponse response;
    private Status.Code error;

    @Override
    public void onNext(ReadHeldSelectedOwnerWorldClosureAuthorizationResponse value) {
      response = value;
    }

    @Override
    public void onError(Throwable failure) {
      error = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {}
  }
}
