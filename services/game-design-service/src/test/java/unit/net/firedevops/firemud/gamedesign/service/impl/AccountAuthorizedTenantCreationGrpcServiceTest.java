package unit.net.firedevops.firemud.gamedesign.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.service.impl.AccountAuthorizedTenantCreationGrpcService;
import net.firedevops.firemud.gamedesign.service.impl.AccountAuthorizedTenantCreationService;
import net.firedevops.firemud.gamedesign.v1.CreateAuthorizedTenantRequest;
import net.firedevops.firemud.gamedesign.v1.CreateAuthorizedTenantResponse;
import net.firedevops.firemud.gamedesign.v1.OriginalTenantCreationOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AccountAuthorizedTenantCreationGrpcServiceTest {
  private static final String NAMESPACE = "creation-receiver-test";
  private static final String ACCOUNT_URI =
      "spiffe://firemud/ns/creation-receiver-test/sa/account-service";
  private static final String OTHER_URI =
      "spiffe://firemud/ns/creation-receiver-test/sa/entity-management-service";
  private static final UUID CREATE_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID REQUEST_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID ACCOUNT_OPERATION_ID =
      UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID ACCOUNT_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID CREATION_OPERATION_ID =
      UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID TENANT_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final String SOURCE_KEY = "fresh-owner-key-91";
  private static final String NAME = "Fresh Realm";
  private static final String DESCRIPTION = "Private creation receiver fixture";
  private static final String ACCOUNT_DIGEST = "sha256:" + "b".repeat(64);
  private static final String REQUEST_DIGEST =
      GameTenantCreationDigest.requestDigest(NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, DESCRIPTION);

  private final AccountAuthorizedTenantCreationService owner =
      mock(AccountAuthorizedTenantCreationService.class);
  private final AccountAuthorizedTenantCreationGrpcService service =
      new AccountAuthorizedTenantCreationGrpcService(owner, NAMESPACE);

  @AfterEach
  void clearSession() {
    SessionContext.clear();
  }

  @Test
  void requiresExactSameNamespaceAccountPeerBeforeOwnerAccess() {
    Dispatch denied = call(request().build(), OTHER_URI);
    assertThat(denied.failure()).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(denied.response()).isNull();
    verifyNoInteractions(owner);
  }

  @Test
  void rejectsAuthenticatedCallerContextEvenWithAccountPeer() {
    SessionContext.setContext("authenticated-user", java.util.List.of(), java.util.Map.of());

    Dispatch denied = call(request().build(), ACCOUNT_URI);

    assertThat(denied.failure()).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(owner);
  }

  @Test
  void returnsFullExactReceiptAndPreservesAbsentDescription() {
    String absentDescriptionDigest =
        GameTenantCreationDigest.requestDigest(NAMESPACE, REQUEST_ID, SOURCE_KEY, NAME, null);
    FreshTenantCreationEvidence receipt = receipt(absentDescriptionDigest);
    when(owner.create(org.mockito.ArgumentMatchers.any())).thenReturn(receipt);

    Dispatch result =
        call(
            request().setCreationRequestDigest(absentDescriptionDigest).clearDescription().build(),
            ACCOUNT_URI);

    assertThat(result.failure()).isNull();
    assertThat(result.completed()).isTrue();
    CreateAuthorizedTenantResponse response = result.response();
    assertThat(response.getCreateRequestId()).isEqualTo(CREATE_ID.toString());
    assertThat(response.getCreationRequestId()).isEqualTo(REQUEST_ID.toString());
    assertThat(response.getStatus())
        .isEqualTo(OriginalTenantCreationOutcome.ORIGINAL_TENANT_CREATION_OUTCOME_CREATED);
    assertThat(response.hasCreatedReceipt()).isTrue();
    assertThat(response.getCreatedReceipt().getOperationId())
        .isEqualTo(CREATION_OPERATION_ID.toString());
    assertThat(response.getCreatedReceipt().getCanonicalTenantId()).isEqualTo(TENANT_ID.toString());
    assertThat(response.getCreatedReceipt().getSourceGameRowId()).isEqualTo(91L);
    assertThat(response.getCreatedReceipt().getSourceGameTenantKey()).isEqualTo(SOURCE_KEY);
    assertThat(response.getCreatedReceipt().getProvenanceKind()).isEqualTo("NEW_GAME_ROW");
    assertThat(response.getCreatedReceipt().getEvidenceDigest())
        .isEqualTo(receipt.evidenceDigest());
    var captured =
        org.mockito.ArgumentCaptor.forClass(
            AccountAuthorizedTenantCreationService.CandidateRequest.class);
    verify(owner).create(captured.capture());
    assertThat(captured.getValue().createRequestId()).isEqualTo(CREATE_ID);
    assertThat(captured.getValue().description()).isNull();
  }

  @Test
  void rejectsUnknownFieldsAndMalformedBindingBeforeOwnerAccess() {
    var unknown =
        request()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                    .build())
            .build();
    var malformed = request().setCreationRequestId("not-a-uuid").build();

    assertThat(call(unknown, ACCOUNT_URI).failure()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(call(malformed, ACCOUNT_URI).failure()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(owner);
  }

  private static CreateAuthorizedTenantRequest.Builder request() {
    return CreateAuthorizedTenantRequest.newBuilder()
        .setSchemaVersion(1)
        .setCreateRequestId(CREATE_ID.toString())
        .setTargetNamespace(NAMESPACE)
        .setCreationRequestId(REQUEST_ID.toString())
        .setAccountAuthorizationOperationId(ACCOUNT_OPERATION_ID.toString())
        .setInitiatingAccountId(ACCOUNT_ID.toString())
        .setCreationRequestDigest(REQUEST_DIGEST)
        .setAccountAuthorizationDigest(ACCOUNT_DIGEST)
        .setSourceGameTenantKey(SOURCE_KEY)
        .setName(NAME)
        .setDescription(DESCRIPTION);
  }

  private static FreshTenantCreationEvidence receipt(String requestDigest) {
    return new FreshTenantCreationEvidence(
        1,
        NAMESPACE,
        REQUEST_ID,
        CREATION_OPERATION_ID,
        requestDigest,
        TENANT_ID,
        91L,
        SOURCE_KEY,
        "NEW_GAME_ROW",
        GameTenantCreationDigest.evidenceDigest(
            NAMESPACE,
            REQUEST_ID,
            CREATION_OPERATION_ID,
            requestDigest,
            TENANT_ID,
            91L,
            SOURCE_KEY,
            "NEW_GAME_ROW"));
  }

  private Dispatch call(CreateAuthorizedTenantRequest request, String peerUri) {
    Observer observer = new Observer();
    Runnable invocation = () -> service.createAuthorizedTenant(request, observer);
    if (peerUri == null) {
      invocation.run();
    } else {
      GrpcPeerIdentity peer = GrpcPeerIdentity.parseUri(peerUri).orElseThrow();
      Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer).run(invocation);
    }
    return new Dispatch(observer.response.get(), observer.failure, observer.completed);
  }

  private record Dispatch(
      CreateAuthorizedTenantResponse response, Status.Code failure, boolean completed) {}

  private static final class Observer implements StreamObserver<CreateAuthorizedTenantResponse> {
    private final AtomicReference<CreateAuthorizedTenantResponse> response =
        new AtomicReference<>();
    private Status.Code failure;
    private boolean completed;

    @Override
    public void onNext(CreateAuthorizedTenantResponse value) {
      response.set(value);
    }

    @Override
    public void onError(Throwable throwable) {
      failure = Status.fromThrowable(throwable).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
