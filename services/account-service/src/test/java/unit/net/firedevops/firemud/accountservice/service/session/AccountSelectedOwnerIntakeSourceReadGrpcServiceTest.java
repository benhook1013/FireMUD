package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.account.v1.SelectedOwnerIntakeSourcePermissionRequest;
import net.firedevops.firemud.account.v1.SelectedOwnerIntakeSourcePermissionResponse;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadProtoCodec;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadScope;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Synthetic transport tests; these do not establish mutual TLS or PostgreSQL runtime proof. */
class AccountSelectedOwnerIntakeSourceReadGrpcServiceTest {
  private static final int MAX_WIRE_BYTES = 8 * 1024 * 1024;
  private static final int MAX_SCOPE_BYTES = 4 * 1024 * 1024;

  @AfterEach
  void clearCallerContext() {
    SessionContext.clear();
  }

  @Test
  void authenticatesExactGameDesignPeerAndRejectsEndUserBeforeDecodingOrOwnerAccess() {
    var owner = mock(AccountSelectedOwnerIntakeSourceReadService.class);
    var service = new AccountSelectedOwnerIntakeSourceReadGrpcService(owner, "test");
    var malformed =
        SelectedOwnerIntakeSourcePermissionRequest.newBuilder().setTargetNamespace("test").build();

    var missing = new Collector();
    service.readSourceScope(malformed, missing);
    assertThat(missing.error).isEqualTo(Status.Code.UNAUTHENTICATED);

    for (String caller :
        List.of("account-service", "game-logic-service", "world-management-service")) {
      var result = new Collector();
      peer("test", caller).run(() -> service.readSourceScope(malformed, result));
      assertThat(result.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    }
    var otherNamespace = new Collector();
    peer("other", "game-design-service")
        .run(() -> service.readSourceScope(malformed, otherNamespace));
    assertThat(otherNamespace.error).isEqualTo(Status.Code.PERMISSION_DENIED);

    SessionContext.setContext("44", List.of(), java.util.Map.of());
    try {
      var endUser = new Collector();
      peer("test", "game-design-service").run(() -> service.readSourceScope(malformed, endUser));
      assertThat(endUser.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    } finally {
      SessionContext.clear();
    }

    var validPeerMalformed = new Collector();
    peer("test", "game-design-service")
        .run(() -> service.readSourceScope(malformed, validPeerMalformed));
    assertThat(validPeerMalformed.error).isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(owner);
  }

  @Test
  void confirmsEntityAndAutomationScopesAndEchoesTheExactRequest() {
    var owner = mock(AccountSelectedOwnerIntakeSourceReadService.class);
    var service = new AccountSelectedOwnerIntakeSourceReadGrpcService(owner, "test");
    when(owner.readSourceScope(any(), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(0));

    for (var scope :
        List.of(
            scope(DraftCommitBinding.Owner.ENTITY_MANAGEMENT),
            scope(DraftCommitBinding.Owner.AUTOMATION_SCRIPTING))) {
      var request = request(scope);
      var result = new Collector();
      peer("test", "game-design-service").run(() -> service.readSourceScope(request, result));

      assertThat(result.error).isNull();
      assertThat(result.response).isNotNull();
      assertThat(result.response.getPermitted()).isTrue();
      assertThat(result.response.getRequest()).isEqualTo(request);
      verify(owner).readSourceScope(scope, scope.intendedReader(), scope.purpose());
    }
  }

  @Test
  void rejectsWrongNamespaceBeforeOwnerAndRejectsWrongReaderPurposeOrDomain() {
    var owner = mock(AccountSelectedOwnerIntakeSourceReadService.class);
    var service = new AccountSelectedOwnerIntakeSourceReadGrpcService(owner, "test");
    var original = request(scope(DraftCommitBinding.Owner.ENTITY_MANAGEMENT));

    var wrongNamespace = new Collector();
    peer("test", "game-design-service")
        .run(
            () ->
                service.readSourceScope(
                    original.toBuilder().setTargetNamespace("other").build(), wrongNamespace));
    assertThat(wrongNamespace.error).isEqualTo(Status.Code.PERMISSION_DENIED);

    var wrongReader =
        request(scope(DraftCommitBinding.Owner.ENTITY_MANAGEMENT)).toBuilder()
            .setIntendedReader("spiffe://firemud/ns/test/sa/entity-management-service")
            .build();
    var readerResult = new Collector();
    peer("test", "game-design-service")
        .run(() -> service.readSourceScope(wrongReader, readerResult));
    assertDeniedOrMalformed(readerResult);

    var wrongPurpose =
        request(scope(DraftCommitBinding.Owner.ENTITY_MANAGEMENT)).toBuilder()
            .setPurpose("AUTOMATION_INTAKE_SOURCE")
            .build();
    var purposeResult = new Collector();
    peer("test", "game-design-service")
        .run(() -> service.readSourceScope(wrongPurpose, purposeResult));
    assertDeniedOrMalformed(purposeResult);

    var wrongDomain =
        request(scope(DraftCommitBinding.Owner.AUTOMATION_SCRIPTING)).toBuilder()
            .setPurpose("ENTITY_INTAKE_SOURCE")
            .build();
    var domainResult = new Collector();
    peer("test", "game-design-service")
        .run(() -> service.readSourceScope(wrongDomain, domainResult));
    assertDeniedOrMalformed(domainResult);
    verifyNoInteractions(owner);
  }

  @Test
  void rejectsUnknownMalformedTamperedDigestAndOversizedWireRequests() {
    var owner = mock(AccountSelectedOwnerIntakeSourceReadService.class);
    var service = new AccountSelectedOwnerIntakeSourceReadGrpcService(owner, "test");
    var valid = request(scope(DraftCommitBinding.Owner.ENTITY_MANAGEMENT));
    var unknown =
        valid.toBuilder()
            .setPreliminarySourceScope(ByteString.copyFrom(new byte[] {0, 1, 2}))
            .build();
    var badDigest = valid.toBuilder().setPreliminaryScopeDigest("0".repeat(64)).build();
    var oversizedScope =
        valid.toBuilder()
            .setPreliminarySourceScope(ByteString.copyFrom(new byte[MAX_SCOPE_BYTES + 1]))
            .build();
    var oversized =
        valid.toBuilder()
            .setPreliminarySourceScope(ByteString.copyFrom(new byte[MAX_WIRE_BYTES + 1]))
            .build();

    for (var request : List.of(unknown, badDigest, oversizedScope, oversized)) {
      var result = new Collector();
      peer("test", "game-design-service").run(() -> service.readSourceScope(request, result));
      assertThat(result.error).isEqualTo(Status.Code.INVALID_ARGUMENT);
      assertThat(result.response).isNull();
    }
    verifyNoInteractions(owner);
  }

  @Test
  void changedOwnerResponseAndStaleOrMissingEvidenceFailClosedWithSanitizedErrors() {
    var owner = mock(AccountSelectedOwnerIntakeSourceReadService.class);
    var service = new AccountSelectedOwnerIntakeSourceReadGrpcService(owner, "test");
    var original = scope(DraftCommitBinding.Owner.ENTITY_MANAGEMENT);
    var request = request(original);
    var changed =
        new SelectedOwnerIntakeSourceReadScope(
            original.owner(),
            original.targetNamespace(),
            UUID.randomUUID(),
            original.fenceId(),
            original.intakeRequestId(),
            original.actorAccountId(),
            original.selected());
    when(owner.readSourceScope(any(), any(), any())).thenReturn(changed);
    var changedResult = new Collector();
    peer("test", "game-design-service").run(() -> service.readSourceScope(request, changedResult));
    assertThat(changedResult.error).isEqualTo(Status.Code.FAILED_PRECONDITION);

    doReturn(null).when(owner).readSourceScope(any(), any(), any());
    var missingResult = new Collector();
    peer("test", "game-design-service").run(() -> service.readSourceScope(request, missingResult));
    assertThat(missingResult.error).isEqualTo(Status.Code.FAILED_PRECONDITION);

    for (var failure :
        List.of(
            Status.FAILED_PRECONDITION.withDescription("credential-secret").asRuntimeException(),
            new IllegalStateException("credential-secret"))) {
      doThrow(failure).when(owner).readSourceScope(any(), any(), any());
      var result = new Collector();
      peer("test", "game-design-service").run(() -> service.readSourceScope(request, result));
      assertThat(result.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
      assertThat(result.errorDescription).isNull();
    }
  }

  @Test
  void returnsPermissionDenialWithoutLeakingOwnerExceptionDetails() {
    var owner = mock(AccountSelectedOwnerIntakeSourceReadService.class);
    var service = new AccountSelectedOwnerIntakeSourceReadGrpcService(owner, "test");
    var request = request(scope(DraftCommitBinding.Owner.ENTITY_MANAGEMENT));
    when(owner.readSourceScope(any(), any(), any()))
        .thenThrow(
            Status.PERMISSION_DENIED.withDescription("credential-secret").asRuntimeException());

    var result = new Collector();
    peer("test", "game-design-service").run(() -> service.readSourceScope(request, result));
    assertThat(result.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(result.errorDescription).isNull();
  }

  private static void assertDeniedOrMalformed(Collector result) {
    assertThat(result.error).isIn(Status.Code.PERMISSION_DENIED, Status.Code.INVALID_ARGUMENT);
    assertThat(result.response).isNull();
  }

  private static SelectedOwnerIntakeSourcePermissionRequest request(
      SelectedOwnerIntakeSourceReadScope scope) {
    return SelectedOwnerIntakeSourceReadProtoCodec.toRequest(
        net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadEvidence
            .Request.create(scope.targetNamespace(), scope));
  }

  private static SelectedOwnerIntakeSourceReadScope scope(DraftCommitBinding.Owner owner) {
    UUID tenant = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    UUID version = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    var selected =
        DraftCommitBinding.create(
            new DraftCommitBinding.TargetProof(
                tenant, version, 1, "tenant-key", 2, "tenant-key", "NEW_GAME_ROW"),
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
            UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
            "base",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "{}")),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "TEMPLATE_CONFIG",
                    "templates",
                    "TENANT",
                    tenant.toString(),
                    "0")));
    return new SelectedOwnerIntakeSourceReadScope(
        owner,
        "test",
        UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff"),
        UUID.fromString("99999999-9999-4999-8999-999999999999"),
        selected.requestId(),
        UUID.fromString("12121212-1212-4212-8212-121212121212"),
        selected);
  }

  private static Context peer(String namespace, String workload) {
    return Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + workload)
                .orElseThrow());
  }

  private static final class Collector
      implements StreamObserver<SelectedOwnerIntakeSourcePermissionResponse> {
    Status.Code error;
    String errorDescription;
    SelectedOwnerIntakeSourcePermissionResponse response;

    @Override
    public void onNext(SelectedOwnerIntakeSourcePermissionResponse value) {
      response = value;
    }

    @Override
    public void onError(Throwable failure) {
      error = Status.fromThrowable(failure).getCode();
      errorDescription = Status.fromThrowable(failure).getDescription();
    }

    @Override
    public void onCompleted() {}
  }
}
