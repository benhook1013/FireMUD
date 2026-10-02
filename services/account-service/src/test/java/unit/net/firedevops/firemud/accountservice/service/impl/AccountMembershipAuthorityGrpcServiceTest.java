package net.firedevops.firemud.accountservice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeRequest;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.accountservice.dto.RuntimeMembershipSnapshotDto;
import net.firedevops.firemud.accountservice.dto.RuntimeMembershipSnapshotDto.MembershipBaseline;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountMembershipAuthorityGrpcServiceTest {
  private static final String NAMESPACE = "firemud-test";
  private static final String GAME_SESSION_URI =
      "spiffe://firemud/ns/" + NAMESPACE + "/sa/game-session-service";
  private static final String ACCOUNT_UUID = "04ef66b4-c0ad-3d5b-b3b2-0e8510e72001";
  private static final String TENANT_UUID = "04ef66b4-c0ad-3d5b-b3b2-0e8510e72002";
  private static final String REALM_UUID = "4c4b57d8-e3a2-48fe-9977-e7df0fdce901";
  private static final String PLAYABLE_NAMESPACE_UUID = "04ef66b4-c0ad-3d5b-b3b2-0e8510e72005";
  private static final String GAME_INSTANCE_UUID = "04ef66b4-c0ad-3d5b-b3b2-0e8510e72006";
  private static final GrpcPeerIdentity GAME_SESSION_PEER =
      new GrpcPeerIdentity(GAME_SESSION_URI, NAMESPACE, "game-session-service");

  @Test
  void constructorRequiresProducerTransactionManagerAndValidatedNamespace() {
    TestTransactionManager transactionManager = new TestTransactionManager();
    assertThatThrownBy(
            () -> new AccountMembershipAuthorityGrpcService(null, transactionManager, NAMESPACE))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("producer");
    assertThatThrownBy(
            () ->
                new AccountMembershipAuthorityGrpcService(
                    mockProducer(), transactionManager, "Invalid_Namespace"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("namespace");
  }

  @Test
  void exactCertificateDerivedGameSessionPeerIsRequiredBeforeRequestValidationOrProducerAccess() {
    AccountMembershipAuthorityEventProducer producer = mockProducer();
    AccountMembershipAuthorityGrpcService service = newService(producer);
    GetTenantMembershipForRuntimeRequest malformed =
        GetTenantMembershipForRuntimeRequest.getDefaultInstance();

    List<Outcome<GetTenantMembershipForRuntimeResponse>> rejected =
        List.of(
            invokeWithoutPeer(service, malformed),
            invoke(
                service,
                malformed,
                new GrpcPeerIdentity(
                    "spiffe://firemud/ns/" + NAMESPACE + "/sa/account-service",
                    NAMESPACE,
                    "account-service")),
            invoke(
                service,
                malformed,
                new GrpcPeerIdentity(
                    "spiffe://firemud/ns/other-namespace/sa/game-session-service",
                    "other-namespace",
                    "game-session-service")));

    for (Outcome<GetTenantMembershipForRuntimeResponse> outcome : rejected) {
      assertFailure(outcome, Status.Code.PERMISSION_DENIED);
    }
    verifyNoInteractions(producer);
  }

  @Test
  void malformedCrossboundAndUnknownRequestOrContextFieldsFailBeforeProducerAccess() {
    AccountMembershipAuthorityEventProducer producer = mockProducer();
    AccountMembershipAuthorityGrpcService service = newService(producer);
    GetTenantMembershipForRuntimeRequest valid = validRequest();
    PlayerExecutionContext context = valid.getPlayerContext();

    List<GetTenantMembershipForRuntimeRequest> invalidRequests =
        List.of(
            GetTenantMembershipForRuntimeRequest.getDefaultInstance(),
            valid.toBuilder()
                .setUnknownFields(
                    unknownLengthDelimitedFields(
                        Map.of(1, ACCOUNT_UUID, 2, TENANT_UUID, 3, "crossbound-request")))
                .build(),
            valid.toBuilder()
                .setPlayerContext(
                    context.toBuilder().setUnknownFields(unknownVarintField(99, 1L)).build())
                .build(),
            valid.toBuilder()
                .setPlayerContext(context.toBuilder().setAccountId("10").build())
                .build(),
            valid.toBuilder()
                .setPlayerContext(
                    context.toBuilder()
                        .setAccountId("00000000-0000-0000-0000-000000000000")
                        .build())
                .build(),
            valid.toBuilder()
                .setPlayerContext(
                    context.toBuilder().setTenantId(TENANT_UUID.toUpperCase()).build())
                .build(),
            valid.toBuilder()
                .setPlayerContext(context.toBuilder().setRealmId("10").build())
                .build(),
            valid.toBuilder()
                .setPlayerContext(
                    context.toBuilder().setPlayableStateNamespaceId("numeric-namespace").build())
                .build(),
            valid.toBuilder()
                .setPlayerContext(context.toBuilder().setGameInstanceId("40").build())
                .build(),
            valid.toBuilder()
                .setPlayerContext(context.toBuilder().setPlayableStateScope("realm:10").build())
                .build(),
            valid.toBuilder()
                .setPlayerContext(context.toBuilder().setSessionId("01").build())
                .build(),
            valid.toBuilder()
                .setPlayerContext(context.toBuilder().setSessionId("0").build())
                .build(),
            valid.toBuilder()
                .setPlayerContext(context.toBuilder().setRequestId(" ").build())
                .build());

    for (GetTenantMembershipForRuntimeRequest invalidRequest : invalidRequests) {
      Outcome<GetTenantMembershipForRuntimeResponse> outcome =
          invoke(service, invalidRequest, GAME_SESSION_PEER);
      assertFailure(outcome, Status.Code.INVALID_ARGUMENT);
    }
    verifyNoInteractions(producer);
  }

  @Test
  void validRequestReturnsExactCanonicalTargetAndRequestEchoesFromOneProducerRead() {
    AccountMembershipAuthorityEventProducer producer = mockProducer();
    RuntimeMembershipSnapshotDto snapshot = neverJoinedSnapshot();
    when(producer.readRuntimeMembershipSnapshot(
            UUID.fromString(ACCOUNT_UUID), UUID.fromString(TENANT_UUID)))
        .thenReturn(snapshot);
    AccountMembershipAuthorityGrpcService service = newService(producer);

    Outcome<GetTenantMembershipForRuntimeResponse> outcome =
        invoke(service, validRequest(), GAME_SESSION_PEER);

    GetTenantMembershipForRuntimeResponse response = assertSuccess(outcome);
    assertThat(response.getAccountId()).isEqualTo(ACCOUNT_UUID);
    assertThat(response.getTenantId()).isEqualTo(TENANT_UUID);
    assertThat(response.getRequestAccountId()).isEqualTo(ACCOUNT_UUID);
    assertThat(response.getRequestTenantId()).isEqualTo(TENANT_UUID);
    assertThat(response.getRequestId()).isEqualTo("membership-read-1");
    assertThat(response.getAuthorityAvailability()).isEqualTo("AVAILABLE");
    assertThat(response.getMembershipExists()).isFalse();
    assertThat(response.getGameplayAdmissionAllowed()).isFalse();
    verify(producer)
        .readRuntimeMembershipSnapshot(UUID.fromString(ACCOUNT_UUID), UUID.fromString(TENANT_UUID));
  }

  @Test
  void producerAndActualCandidateEncodingRunInsideWritableOwnerTransaction() {
    AccountMembershipAuthorityEventProducer producer = mockProducer();
    TestTransactionManager transactionManager = new TestTransactionManager();
    RuntimeMembershipSnapshotDto snapshot = neverJoinedSnapshot();
    AtomicBoolean producerSawTransaction = new AtomicBoolean();
    AtomicBoolean encoderSawTransaction = new AtomicBoolean();
    when(producer.readRuntimeMembershipSnapshot(
            UUID.fromString(ACCOUNT_UUID), UUID.fromString(TENANT_UUID)))
        .thenAnswer(
            invocation -> {
              producerSawTransaction.set(
                  TransactionSynchronizationManager.isActualTransactionActive());
              return snapshot;
            });
    AccountMembershipAuthorityGrpcService service =
        new AccountMembershipAuthorityGrpcService(
            producer,
            transactionManager,
            NAMESPACE,
            (playerContext, candidate) -> {
              encoderSawTransaction.set(
                  TransactionSynchronizationManager.isActualTransactionActive());
              return AccountGrpcService.encodeRuntimeMembershipCandidate(playerContext, candidate);
            });

    Outcome<GetTenantMembershipForRuntimeResponse> outcome =
        invoke(service, validRequest(), GAME_SESSION_PEER);

    assertSuccess(outcome);
    assertThat(producerSawTransaction).isTrue();
    assertThat(encoderSawTransaction).isTrue();
    assertThat(transactionManager.lastDefinition.isReadOnly()).isFalse();
    assertThat(transactionManager.lastDefinition.getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
    assertThat(outcome.observerSawActiveTransaction()).isFalse();
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
  }

  @Test
  void contradictoryOrUnavailableSourceAndFailedCommitNeverEmitResponse() {
    AccountMembershipAuthorityEventProducer contradictoryProducer = mockProducer();
    when(contradictoryProducer.readRuntimeMembershipSnapshot(
            UUID.fromString(ACCOUNT_UUID), UUID.fromString(TENANT_UUID)))
        .thenThrow(new IllegalStateException("contradictory source internals"));
    Outcome<GetTenantMembershipForRuntimeResponse> contradictory =
        invoke(newService(contradictoryProducer), validRequest(), GAME_SESSION_PEER);
    assertFailure(contradictory, Status.Code.FAILED_PRECONDITION);
    assertThat(contradictory.error().getDescription())
        .doesNotContain("contradictory source internals");

    AccountMembershipAuthorityEventProducer unavailableProducer = mockProducer();
    when(unavailableProducer.readRuntimeMembershipSnapshot(
            UUID.fromString(ACCOUNT_UUID), UUID.fromString(TENANT_UUID)))
        .thenThrow(new TransientDataAccessResourceException("database details"));
    Outcome<GetTenantMembershipForRuntimeResponse> unavailable =
        invoke(newService(unavailableProducer), validRequest(), GAME_SESSION_PEER);
    assertFailure(unavailable, Status.Code.UNAVAILABLE);
    assertThat(unavailable.error().getDescription()).doesNotContain("database details");

    AccountMembershipAuthorityEventProducer commitProducer = mockProducer();
    when(commitProducer.readRuntimeMembershipSnapshot(
            UUID.fromString(ACCOUNT_UUID), UUID.fromString(TENANT_UUID)))
        .thenReturn(neverJoinedSnapshot());
    TestTransactionManager failedCommitManager = new TestTransactionManager();
    failedCommitManager.failCommit = true;
    Outcome<GetTenantMembershipForRuntimeResponse> failedCommit =
        invoke(
            new AccountMembershipAuthorityGrpcService(
                commitProducer, failedCommitManager, NAMESPACE),
            validRequest(),
            GAME_SESSION_PEER);
    assertFailure(failedCommit, Status.Code.FAILED_PRECONDITION);
  }

  private static AccountMembershipAuthorityGrpcService newService(
      AccountMembershipAuthorityEventProducer producer) {
    return new AccountMembershipAuthorityGrpcService(
        producer, new TestTransactionManager(), NAMESPACE);
  }

  private static AccountMembershipAuthorityEventProducer mockProducer() {
    return mock(AccountMembershipAuthorityEventProducer.class);
  }

  private static GetTenantMembershipForRuntimeRequest validRequest() {
    return GetTenantMembershipForRuntimeRequest.newBuilder()
        .setPlayerContext(
            PlayerExecutionContext.newBuilder()
                .setAccountId(ACCOUNT_UUID)
                .setTenantId(TENANT_UUID)
                .setRealmId(REALM_UUID)
                .setPlayableStateNamespaceId(PLAYABLE_NAMESPACE_UUID)
                .setPlayableStateScope("SHARED")
                .setGameInstanceId(GAME_INSTANCE_UUID)
                .setSessionId("50")
                .setRequestId("membership-read-1"))
        .build();
  }

  private static RuntimeMembershipSnapshotDto neverJoinedSnapshot() {
    String membershipStream =
        "account:auth-authority:v1:membership/" + ACCOUNT_UUID + "/" + TENANT_UUID;
    return new RuntimeMembershipSnapshotDto(
        ACCOUNT_UUID,
        TENANT_UUID,
        ACCOUNT_UUID,
        TENANT_UUID,
        false,
        false,
        new MembershipBaseline("MISSING", Map.of(TENANT_UUID, "1"), "1"),
        List.of(),
        new AuthorityTuple(
            "1",
            "1",
            Map.of(TENANT_UUID, "1"),
            Map.of(TENANT_UUID, "1"),
            List.of(),
            Optional.empty(),
            Optional.empty()),
        "7",
        Instant.parse("2026-09-27T00:00:00Z"),
        List.of(
            new OutboxCheckpointEntry("account:auth-authority:v1:account/" + ACCOUNT_UUID, "0"),
            new OutboxCheckpointEntry(
                "account:auth-authority:v1:issuer/" + AccountServiceImpl.ACCOUNT_JWT_ISSUER, "0"),
            new OutboxCheckpointEntry(membershipStream, "0"),
            new OutboxCheckpointEntry("account:auth-authority:v1:tenant/" + TENANT_UUID, "0")),
        List.of(),
        null);
  }

  private static UnknownFieldSet unknownVarintField(int fieldNumber, long value) {
    return UnknownFieldSet.newBuilder()
        .addField(fieldNumber, UnknownFieldSet.Field.newBuilder().addVarint(value).build())
        .build();
  }

  private static UnknownFieldSet unknownLengthDelimitedFields(Map<Integer, String> values) {
    UnknownFieldSet.Builder fields = UnknownFieldSet.newBuilder();
    values.forEach(
        (number, value) ->
            fields.addField(
                number,
                UnknownFieldSet.Field.newBuilder()
                    .addLengthDelimited(ByteString.copyFromUtf8(value))
                    .build()));
    return fields.build();
  }

  private static Outcome<GetTenantMembershipForRuntimeResponse> invoke(
      AccountMembershipAuthorityGrpcService service,
      GetTenantMembershipForRuntimeRequest request,
      GrpcPeerIdentity peerIdentity) {
    Context peerContext = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peerIdentity);
    Context previous = peerContext.attach();
    try {
      RecordingObserver<GetTenantMembershipForRuntimeResponse> observer = new RecordingObserver<>();
      service.getTenantMembershipForRuntime(request, observer);
      return observer.outcome();
    } finally {
      peerContext.detach(previous);
    }
  }

  private static Outcome<GetTenantMembershipForRuntimeResponse> invokeWithoutPeer(
      AccountMembershipAuthorityGrpcService service, GetTenantMembershipForRuntimeRequest request) {
    RecordingObserver<GetTenantMembershipForRuntimeResponse> observer = new RecordingObserver<>();
    service.getTenantMembershipForRuntime(request, observer);
    return observer.outcome();
  }

  private static <T> T assertSuccess(Outcome<T> outcome) {
    assertThat(outcome.error()).isNull();
    assertThat(outcome.completed()).isTrue();
    assertThat(outcome.responses()).hasSize(1);
    return outcome.responses().get(0);
  }

  private static void assertFailure(Outcome<?> outcome, Status.Code expectedCode) {
    assertThat(outcome.error()).isNotNull();
    assertThat(outcome.error().getCode()).isEqualTo(expectedCode);
    assertThat(outcome.responses()).isEmpty();
    assertThat(outcome.completed()).isFalse();
  }

  private record Outcome<T>(
      List<T> responses, Status error, boolean completed, boolean observerSawActiveTransaction) {}

  private static final class RecordingObserver<T> implements StreamObserver<T> {
    private final List<T> responses = new ArrayList<>();
    private Status error;
    private boolean completed;
    private boolean observerSawActiveTransaction;

    @Override
    public void onNext(T value) {
      responses.add(value);
      observerSawActiveTransaction = TransactionSynchronizationManager.isActualTransactionActive();
    }

    @Override
    public void onError(Throwable throwable) {
      error = Status.fromThrowable(throwable);
    }

    @Override
    public void onCompleted() {
      completed = true;
    }

    private Outcome<T> outcome() {
      return new Outcome<>(List.copyOf(responses), error, completed, observerSawActiveTransaction);
    }
  }

  private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
    private TransactionDefinition lastDefinition;
    private boolean failCommit;

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected boolean isExistingTransaction(Object transaction) {
      return false;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      lastDefinition = definition;
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      if (failCommit) {
        throw new IllegalStateException("synthetic commit failure");
      }
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }
}
