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
import net.firedevops.firemud.account.v1.ReadHeldSelectedOwnerIntakeAuthorizationRequest;
import net.firedevops.firemud.account.v1.ReadHeldSelectedOwnerIntakeAuthorizationResponse;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationReadEvidence;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationReadGrpcCodec;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Synthetic handler tests only; they do not establish mTLS or PostgreSQL owner proof. */
class AccountSelectedOwnerIntakeAuthorizationReadGrpcServiceTest {
  @AfterEach
  void clearCallerContext() {
    SessionContext.clear();
  }

  @Test
  void authenticatesOwnerAndRejectsEndUserBeforeParsingOrOwnerAccess() {
    var owner = mock(AccountSelectedOwnerIntakeAuthorizationReadService.class);
    var service = new AccountSelectedOwnerIntakeAuthorizationReadGrpcService(owner, "test");
    var malformed =
        ReadHeldSelectedOwnerIntakeAuthorizationRequest.newBuilder()
            .setTargetNamespace("test")
            .setSchemaVersion(-1)
            .build();

    var noPeer = new Collector();
    service.readHeldSelectedOwnerIntakeAuthorization(malformed, noPeer);
    assertThat(noPeer.error).isEqualTo(Status.Code.UNAUTHENTICATED);

    for (String workload :
        List.of(
            "account-service",
            "game-design-service",
            "game-logic-service",
            "world-management-service")) {
      var denied = new Collector();
      peer("test", workload)
          .run(() -> service.readHeldSelectedOwnerIntakeAuthorization(malformed, denied));
      assertThat(denied.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    }
    var otherNamespace = new Collector();
    peer("other", "entity-management-service")
        .run(() -> service.readHeldSelectedOwnerIntakeAuthorization(malformed, otherNamespace));
    assertThat(otherNamespace.error).isEqualTo(Status.Code.PERMISSION_DENIED);

    SessionContext.setContext("44", List.of(), java.util.Map.of());
    try {
      var endUser = new Collector();
      peer("test", "entity-management-service")
          .run(() -> service.readHeldSelectedOwnerIntakeAuthorization(malformed, endUser));
      assertThat(endUser.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    } finally {
      SessionContext.clear();
    }

    var allowedPeerMalformed = new Collector();
    peer("test", "entity-management-service")
        .run(
            () ->
                service.readHeldSelectedOwnerIntakeAuthorization(malformed, allowedPeerMalformed));
    assertThat(allowedPeerMalformed.error).isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(owner);
  }

  @Test
  void returnsExactEchoOnlyForMatchingEntityAndAutomationReader() {
    var owner = mock(AccountSelectedOwnerIntakeAuthorizationReadService.class);
    var service = new AccountSelectedOwnerIntakeAuthorizationReadGrpcService(owner, "test");

    for (Owner domain : List.of(Owner.ENTITY_MANAGEMENT, Owner.AUTOMATION_SCRIPTING)) {
      var binding = AccountSelectedOwnerIntakeAuthorizationReadServiceTest.binding(domain);
      var request = SelectedOwnerIntakeAuthorizationReadEvidence.Request.create("test", binding);
      var wire = SelectedOwnerIntakeAuthorizationReadGrpcCodec.toRequest(request);
      var result = new Collector();
      peerFromUri(binding.intendedReader())
          .run(() -> service.readHeldSelectedOwnerIntakeAuthorization(wire, result));

      assertThat(result.error).isNull();
      assertThat(result.response).isNotNull();
      assertThat(result.response.getRequest()).isEqualTo(wire);
      assertThat(result.response.getHeld()).isTrue();
    }
    verify(owner, times(2)).requireHeld(any());
  }

  @Test
  void entityCannotReadAutomationAndAutomationCannotReadEntity() {
    var owner = mock(AccountSelectedOwnerIntakeAuthorizationReadService.class);
    var service = new AccountSelectedOwnerIntakeAuthorizationReadGrpcService(owner, "test");

    for (Owner bindingOwner : List.of(Owner.ENTITY_MANAGEMENT, Owner.AUTOMATION_SCRIPTING)) {
      var binding = AccountSelectedOwnerIntakeAuthorizationReadServiceTest.binding(bindingOwner);
      var request =
          SelectedOwnerIntakeAuthorizationReadGrpcCodec.toRequest(
              SelectedOwnerIntakeAuthorizationReadEvidence.Request.create("test", binding));
      String wrongReader =
          bindingOwner == Owner.ENTITY_MANAGEMENT
              ? "automation-scripting-service"
              : "entity-management-service";
      var result = new Collector();
      peer("test", wrongReader)
          .run(() -> service.readHeldSelectedOwnerIntakeAuthorization(request, result));
      assertThat(result.error).isEqualTo(Status.Code.PERMISSION_DENIED);
      assertThat(result.response).isNull();
    }
    verifyNoInteractions(owner);
  }

  @Test
  void rejectsWrongNamespaceClosedFieldsDigestUnknownFieldsAndMalformedSizes() {
    var owner = mock(AccountSelectedOwnerIntakeAuthorizationReadService.class);
    var service = new AccountSelectedOwnerIntakeAuthorizationReadGrpcService(owner, "test");
    var binding =
        AccountSelectedOwnerIntakeAuthorizationReadServiceTest.binding(Owner.ENTITY_MANAGEMENT);
    var valid =
        SelectedOwnerIntakeAuthorizationReadGrpcCodec.toRequest(
            SelectedOwnerIntakeAuthorizationReadEvidence.Request.create("test", binding));

    var wrongNamespace = new Collector();
    peer("test", "entity-management-service")
        .run(
            () ->
                service.readHeldSelectedOwnerIntakeAuthorization(
                    valid.toBuilder().setTargetNamespace("other").build(), wrongNamespace));
    assertThat(wrongNamespace.error).isEqualTo(Status.Code.PERMISSION_DENIED);

    var changedReader =
        valid.toBuilder().setIntendedReader("spiffe://firemud/ns/test/sa/account-service").build();
    var changedPurpose = valid.toBuilder().setRetentionPurpose("ENTITY_INTAKE_SOURCE").build();
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
    for (var malformed :
        List.of(
            changedReader,
            changedPurpose,
            changedDigest,
            unknown,
            malformedBinding,
            oversizedBinding)) {
      var result = new Collector();
      peerFromUri(binding.intendedReader())
          .run(() -> service.readHeldSelectedOwnerIntakeAuthorization(malformed, result));
      assertThat(result.error).isEqualTo(Status.Code.INVALID_ARGUMENT);
      assertThat(result.response).isNull();
    }
    verifyNoInteractions(owner);
  }

  @Test
  void mapsOwnerReadFailureToNoHeldResponse() {
    var owner = mock(AccountSelectedOwnerIntakeAuthorizationReadService.class);
    org.mockito.Mockito.doThrow(Status.FAILED_PRECONDITION.asRuntimeException())
        .when(owner)
        .requireHeld(any());
    var service = new AccountSelectedOwnerIntakeAuthorizationReadGrpcService(owner, "test");
    var binding =
        AccountSelectedOwnerIntakeAuthorizationReadServiceTest.binding(Owner.ENTITY_MANAGEMENT);
    var wire =
        SelectedOwnerIntakeAuthorizationReadGrpcCodec.toRequest(
            SelectedOwnerIntakeAuthorizationReadEvidence.Request.create("test", binding));
    var result = new Collector();

    peerFromUri(binding.intendedReader())
        .run(() -> service.readHeldSelectedOwnerIntakeAuthorization(wire, result));

    assertThat(result.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(result.response).isNull();
  }

  private static Context peer(String namespace, String workload) {
    return peerFromUri("spiffe://firemud/ns/" + namespace + "/sa/" + workload);
  }

  private static Context peerFromUri(String uri) {
    return Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(uri).orElseThrow());
  }

  private static final class Collector
      implements StreamObserver<ReadHeldSelectedOwnerIntakeAuthorizationResponse> {
    private ReadHeldSelectedOwnerIntakeAuthorizationResponse response;
    private Status.Code error;

    @Override
    public void onNext(ReadHeldSelectedOwnerIntakeAuthorizationResponse value) {
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
