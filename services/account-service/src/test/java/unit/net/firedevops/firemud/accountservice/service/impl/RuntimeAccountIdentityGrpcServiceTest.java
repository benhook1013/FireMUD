package net.firedevops.firemud.accountservice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.v1.ResolveRuntimeAccountIdentityRequest;
import net.firedevops.firemud.account.v1.ResolveRuntimeAccountIdentityResponse;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.jooq.exception.TooManyRowsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

class RuntimeAccountIdentityGrpcServiceTest {
  private static final String WORKLOAD_NAMESPACE = "test";
  private static final String GAME_SESSION_URI = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String ENTITY_MANAGEMENT_URI =
      "spiffe://firemud/ns/test/sa/entity-management-service";
  private static final UUID ACCOUNT_ID = UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b");
  private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

  private final AccountRepository repository = mock(AccountRepository.class);
  private final RuntimeAccountIdentityGrpcService service =
      new RuntimeAccountIdentityGrpcService(repository, WORKLOAD_NAMESPACE);

  @ParameterizedTest
  @ValueSource(strings = {GAME_SESSION_URI, ENTITY_MANAGEMENT_URI})
  void resolvesExactPersistedIdentityForEachAllowedRuntimePeer(String peerUri) {
    when(repository.findByAccountUuid(ACCOUNT_ID))
        .thenReturn(
            Optional.of(
                account(ACCOUNT_ID, AccountIdentityProvenance.ACCOUNT_V29_MIGRATION, 42L, 42L)));

    TestObserver observer = call(request(ACCOUNT_ID.toString(), REQUEST_ID.toString()), peerUri);

    assertThat(observer.failure).isNull();
    assertThat(observer.completed).isTrue();
    assertThat(observer.value.getSchemaVersion()).isEqualTo(1);
    assertThat(observer.value.getTargetNamespace()).isEqualTo(WORKLOAD_NAMESPACE);
    assertThat(observer.value.getRequestId()).isEqualTo(REQUEST_ID.toString());
    assertThat(observer.value.getCanonicalAccountId()).isEqualTo(ACCOUNT_ID.toString());
    assertThat(observer.value.getSourceAccountRowId()).isEqualTo(42L);
    assertThat(observer.value.getAccountUuidProvenance()).isEqualTo("ACCOUNT_V29_MIGRATION");
    verify(repository).findByAccountUuid(ACCOUNT_ID);
  }

  @Test
  void deniesMissingWrongNamespaceAndUntrustedPeerIdentitiesBeforeOwnerRead() {
    assertThat(status(call(request(ACCOUNT_ID.toString(), REQUEST_ID.toString()), null)))
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(
            status(
                call(
                    request(ACCOUNT_ID.toString(), REQUEST_ID.toString()),
                    "spiffe://firemud/ns/other/sa/game-session-service")))
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(
            status(
                call(
                    request(ACCOUNT_ID.toString(), REQUEST_ID.toString()),
                    "spiffe://firemud/ns/test/sa/account-service")))
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(
            status(
                call(
                    request(ACCOUNT_ID.toString(), REQUEST_ID.toString()),
                    "spiffe://firemud/ns/test/sa/social-groups-service")))
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(repository);
  }

  @Test
  void leavesRuntimeReadInactiveWhenWorkloadNamespaceIsNotConfigured() {
    RuntimeAccountIdentityGrpcService inactiveService =
        new RuntimeAccountIdentityGrpcService(repository, "");
    TestObserver observer = new TestObserver();
    GrpcPeerIdentity peer = GrpcPeerIdentity.parseUri(GAME_SESSION_URI).orElseThrow();

    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
        .run(
            () ->
                inactiveService.resolveRuntimeAccountIdentity(
                    request(ACCOUNT_ID.toString(), REQUEST_ID.toString()), observer));

    assertThat(status(observer)).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsNoncanonicalNilAndOpenMetadataRequestsBeforeOwnerRead() {
    assertThat(
            status(
                call(
                    request(ACCOUNT_ID.toString().toUpperCase(), REQUEST_ID.toString()),
                    GAME_SESSION_URI)))
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(
            status(
                call(
                    request("00000000-0000-0000-0000-000000000000", REQUEST_ID.toString()),
                    GAME_SESSION_URI)))
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(
            status(
                call(
                    request(ACCOUNT_ID.toString(), "00000000-0000-0000-0000-000000000000"),
                    GAME_SESSION_URI)))
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    ResolveRuntimeAccountIdentityRequest openRequest =
        request(ACCOUNT_ID.toString(), REQUEST_ID.toString()).toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();
    assertThat(status(call(openRequest, GAME_SESSION_URI))).isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(repository);
  }

  @Test
  void returnsNotFoundAndFailsClosedForMalformedAmbiguousOrMismatchedProvenance() {
    when(repository.findByAccountUuid(ACCOUNT_ID))
        .thenReturn(Optional.empty())
        .thenThrow(new IllegalStateException("private malformed provenance detail"))
        .thenThrow(new TooManyRowsException("private duplicate-row detail"))
        .thenReturn(
            Optional.of(
                account(ACCOUNT_ID, AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT, 42L, 43L)))
        .thenReturn(
            Optional.of(
                account(
                    UUID.fromString("a2e1342e-a139-49c6-a460-c8e25f6697ae"),
                    AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT,
                    42L,
                    42L)));

    assertThat(
            status(call(request(ACCOUNT_ID.toString(), REQUEST_ID.toString()), GAME_SESSION_URI)))
        .isEqualTo(Status.Code.NOT_FOUND);
    assertThat(
            status(call(request(ACCOUNT_ID.toString(), REQUEST_ID.toString()), GAME_SESSION_URI)))
        .isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(
            status(call(request(ACCOUNT_ID.toString(), REQUEST_ID.toString()), GAME_SESSION_URI)))
        .isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(
            status(call(request(ACCOUNT_ID.toString(), REQUEST_ID.toString()), GAME_SESSION_URI)))
        .isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(
            status(call(request(ACCOUNT_ID.toString(), REQUEST_ID.toString()), GAME_SESSION_URI)))
        .isEqualTo(Status.Code.FAILED_PRECONDITION);
  }

  @Test
  void distinguishesTemporaryReadFailureFromInternalReadFailureWithoutLeakingDetails() {
    when(repository.findByAccountUuid(ACCOUNT_ID))
        .thenThrow(new DataAccessResourceFailureException("private database endpoint"))
        .thenThrow(new IllegalArgumentException("private persistence detail"));

    TestObserver unavailable =
        call(request(ACCOUNT_ID.toString(), REQUEST_ID.toString()), GAME_SESSION_URI);
    TestObserver internal =
        call(request(ACCOUNT_ID.toString(), REQUEST_ID.toString()), GAME_SESSION_URI);

    assertThat(status(unavailable)).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(unavailable.failure.getDescription())
        .isEqualTo("Account identity is temporarily unavailable");
    assertThat(status(internal)).isEqualTo(Status.Code.INTERNAL);
    assertThat(internal.failure.getDescription()).isEqualTo("Account identity could not be read");
  }

  private static Account account(
      UUID accountUuid, AccountIdentityProvenance provenance, Long rowId, Long sourceNumericRowId) {
    Account account = new Account();
    account.setId(rowId);
    account.setAccountUuid(accountUuid);
    account.setAccountUuidProvenance(provenance);
    account.setAccountUuidSourceNumericId(sourceNumericRowId);
    return account;
  }

  private static ResolveRuntimeAccountIdentityRequest request(String accountId, String requestId) {
    return ResolveRuntimeAccountIdentityRequest.newBuilder()
        .setCanonicalAccountId(accountId)
        .setRequestId(requestId)
        .build();
  }

  private TestObserver call(ResolveRuntimeAccountIdentityRequest request, String peerUri) {
    TestObserver observer = new TestObserver();
    Runnable invocation = () -> service.resolveRuntimeAccountIdentity(request, observer);
    if (peerUri == null) {
      Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, null).run(invocation);
    } else {
      GrpcPeerIdentity peer = GrpcPeerIdentity.parseUri(peerUri).orElseThrow();
      Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer).run(invocation);
    }
    return observer;
  }

  private static Status.Code status(TestObserver observer) {
    assertThat(observer.failure).isNotNull();
    return observer.failure.getCode();
  }

  private static final class TestObserver
      implements StreamObserver<ResolveRuntimeAccountIdentityResponse> {
    private ResolveRuntimeAccountIdentityResponse value;
    private Status failure;
    private boolean completed;

    @Override
    public void onNext(ResolveRuntimeAccountIdentityResponse response) {
      value = response;
    }

    @Override
    public void onError(Throwable throwable) {
      failure = Status.fromThrowable(throwable);
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
