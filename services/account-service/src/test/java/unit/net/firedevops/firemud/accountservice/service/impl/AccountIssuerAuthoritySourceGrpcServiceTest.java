package unit.net.firedevops.firemud.accountservice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.v1.AccountIssuerAuthoritySourceSnapshot;
import net.firedevops.firemud.account.v1.ExactIssuerAuthorityEventReadback;
import net.firedevops.firemud.account.v1.ReadCurrentIssuerAuthoritySourceRequest;
import net.firedevops.firemud.account.v1.ReadExactIssuerAuthorityEventRequest;
import net.firedevops.firemud.accountservice.repository.AccountIssuerSourceSnapshotReadOwner;
import net.firedevops.firemud.accountservice.service.impl.AccountIssuerAuthoritySourceGrpcService;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec;
import net.firedevops.firemud.common.account.authority.AccountIssuerSourceSnapshotEvidence;
import net.firedevops.firemud.common.account.authority.AccountIssuerSourceSnapshotGrpcCodec;
import org.junit.jupiter.api.Test;

class AccountIssuerAuthoritySourceGrpcServiceTest {
  private static final String NAMESPACE = "test";
  private static final String CALLER = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String ISSUER = AccountIssuerSourceSnapshotEvidence.ISSUER_ID;
  private static final String STREAM = AccountIssuerSourceSnapshotEvidence.ISSUER_STREAM_KEY;

  private final AccountIssuerSourceSnapshotReadOwner owner =
      mock(AccountIssuerSourceSnapshotReadOwner.class);
  private final AccountIssuerAuthoritySourceGrpcService service =
      new AccountIssuerAuthoritySourceGrpcService(owner);

  @Test
  void encodesOnlyTheOwnerReturnedClosedSnapshotAndCompletesOnce() {
    UUID operation = UUID.randomUUID();
    var typedRequest =
        new AccountIssuerSourceSnapshotGrpcCodec.ReadRequest(
            operation, NAMESPACE, ISSUER, Optional.empty());
    var request = AccountIssuerSourceSnapshotGrpcCodec.toRequest(typedRequest, NAMESPACE, CALLER);
    var evidence =
        new AccountIssuerSourceSnapshotEvidence(
            operation, NAMESPACE, CALLER, ISSUER, "1", "1", STREAM, "0", Optional.empty());
    when(owner.read(request))
        .thenReturn(
            new AccountIssuerSourceSnapshotReadOwner.ReadResult(typedRequest, CALLER, evidence));
    var observer = new RecordingObserver<AccountIssuerAuthoritySourceSnapshot>();

    service.readCurrentIssuerAuthoritySource(request, observer);

    assertThat(observer.error).isNull();
    assertThat(observer.completed).isTrue();
    assertThat(observer.value).isEqualTo(AccountIssuerSourceSnapshotGrpcCodec.toWire(evidence));
    verify(owner).read(request);
  }

  @Test
  void preservesCanonicalStatusClassesAndSanitizesTheirDescriptions() {
    var observer = new RecordingObserver<AccountIssuerAuthoritySourceSnapshot>();
    when(owner.read(any()))
        .thenThrow(
            Status.FAILED_PRECONDITION
                .withDescription("raw SQL row and event payload")
                .asRuntimeException());

    service.readCurrentIssuerAuthoritySource(
        ReadCurrentIssuerAuthoritySourceRequest.getDefaultInstance(), observer);

    assertThat(observer.error.getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(observer.error.getDescription()).doesNotContain("raw SQL");
    assertThat(observer.completed).isFalse();
  }

  @Test
  void mapsMalformedRequestAndUnexpectedOwnerFailureWithoutLeakingDetails() {
    var invalid = new RecordingObserver<AccountIssuerAuthoritySourceSnapshot>();
    when(owner.read(any()))
        .thenThrow(
            new IllegalArgumentException("raw request data"),
            new IllegalStateException("connection password detail"));
    service.readCurrentIssuerAuthoritySource(
        ReadCurrentIssuerAuthoritySourceRequest.getDefaultInstance(), invalid);
    assertThat(invalid.error.getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(invalid.error.getDescription()).doesNotContain("raw request");

    var unavailable = new RecordingObserver<AccountIssuerAuthoritySourceSnapshot>();
    service.readCurrentIssuerAuthoritySource(
        ReadCurrentIssuerAuthoritySourceRequest.getDefaultInstance(), unavailable);
    assertThat(unavailable.error.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(unavailable.error.getDescription()).doesNotContain("password detail");
  }

  @Test
  void encodesOwnerHistoryAndRejectsAConflictingOwnerResult() {
    UUID operation = UUID.randomUUID();
    var event =
        AccountAuthoritySourceEventV1Codec.sealIssuer(
            new AccountAuthoritySourceEventV1Codec.IssuerPreimage(
                "event-a", "event-a", STREAM, "1", ISSUER, "2", "2", "SIGNER_COMPROMISE"));
    var scope =
        new AccountIssuerSourceSnapshotGrpcCodec.ReadRequest(
            operation, NAMESPACE, ISSUER, Optional.empty());
    var typedRequest =
        new AccountIssuerSourceSnapshotGrpcCodec.ExactEventRequest(
            scope, 1L, event.eventId(), event.eventDigest());
    var request =
        AccountIssuerSourceSnapshotGrpcCodec.toExactEventRequest(typedRequest, NAMESPACE, CALLER);
    var evidence =
        new AccountIssuerSourceSnapshotEvidence(
            operation,
            NAMESPACE,
            CALLER,
            ISSUER,
            "2",
            "2",
            STREAM,
            "1",
            Optional.of(event.canonicalJson()));
    var baseline =
        new AccountIssuerSourceSnapshotEvidence(
            operation, NAMESPACE, CALLER, ISSUER, "1", "1", STREAM, "0", Optional.empty());
    when(owner.readExactEvent(request))
        .thenReturn(
            new AccountIssuerSourceSnapshotReadOwner.ExactEventReadResult(
                typedRequest,
                CALLER,
                new AccountIssuerSourceSnapshotGrpcCodec.ExactEventReadback(evidence, event)),
            new AccountIssuerSourceSnapshotReadOwner.ExactEventReadResult(
                typedRequest,
                CALLER,
                new AccountIssuerSourceSnapshotGrpcCodec.ExactEventReadback(baseline, event)));

    var accepted = new RecordingObserver<ExactIssuerAuthorityEventReadback>();
    service.readExactIssuerAuthorityEvent(request, accepted);
    assertThat(accepted.error).isNull();
    assertThat(accepted.completed).isTrue();
    assertThat(accepted.value.getCurrentSnapshot())
        .isEqualTo(AccountIssuerSourceSnapshotGrpcCodec.toWire(evidence));
    assertThat(accepted.value.getSelectedEvent().toByteArray())
        .isEqualTo(event.canonicalJsonUtf8());

    var denied = new RecordingObserver<ExactIssuerAuthorityEventReadback>();
    service.readExactIssuerAuthorityEvent(request, denied);
    assertThat(denied.error.getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(denied.completed).isFalse();
    assertThat(denied.value).isNull();
  }

  @Test
  void sanitizesExactHistoryOwnerRefusalWithoutAReply() {
    when(owner.readExactEvent(any()))
        .thenThrow(
            Status.FAILED_PRECONDITION
                .withDescription("private retained event")
                .asRuntimeException());
    var observer = new RecordingObserver<ExactIssuerAuthorityEventReadback>();

    service.readExactIssuerAuthorityEvent(
        ReadExactIssuerAuthorityEventRequest.getDefaultInstance(), observer);

    assertThat(observer.error.getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(observer.error.getDescription()).doesNotContain("private retained event");
    assertThat(observer.value).isNull();
    assertThat(observer.completed).isFalse();
  }

  private static final class RecordingObserver<T> implements StreamObserver<T> {
    private T value;
    private Status error;
    private boolean completed;

    @Override
    public void onNext(T next) {
      value = next;
    }

    @Override
    public void onError(Throwable failure) {
      error = Status.fromThrowable(failure);
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
