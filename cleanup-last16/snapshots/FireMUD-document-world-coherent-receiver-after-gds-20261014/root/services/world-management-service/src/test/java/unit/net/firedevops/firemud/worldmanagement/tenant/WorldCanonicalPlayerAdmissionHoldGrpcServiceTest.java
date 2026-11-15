package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldCanonicalPlayerAdmissionHoldEvidence;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalPlayerAdmissionHoldGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalPlayerAdmissionHoldService;
import net.firedevops.firemud.worldmanagement.v1.AcquireCanonicalPlayerAdmissionHoldRequest;
import net.firedevops.firemud.worldmanagement.v1.AcquireCanonicalPlayerAdmissionHoldResponse;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalPlayerAdmissionHoldRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadCanonicalPlayerAdmissionHoldResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Adapter-only mocked service/lifecycle fixtures; not authenticated transport or database proof.
 */
class WorldCanonicalPlayerAdmissionHoldGrpcServiceTest {
  private final WorldCanonicalPlayerAdmissionHoldService service =
      mock(WorldCanonicalPlayerAdmissionHoldService.class);
  private final WorldCanonicalPlayerAdmissionHoldGrpcService adapter =
      new WorldCanonicalPlayerAdmissionHoldGrpcService(service, "firemud");

  @AfterEach
  void clearContext() {
    SessionContext.clear();
    TransactionSynchronizationManager.setActualTransactionActive(false);
    if (TransactionSynchronizationManager.isSynchronizationActive())
      TransactionSynchronizationManager.clearSynchronization();
  }

  @Test
  void wrongPeerAndEndUserContextDenyBeforeAnyPrimitiveParsingOrOwnerAccess() {
    for (String peer :
        java.util.List.of(
            "spiffe://firemud/ns/firemud/sa/game-session-service",
            "spiffe://firemud/ns/other/sa/account-service")) {
      peer(peer)
          .run(
              () -> {
                var acquire = new Collector<AcquireCanonicalPlayerAdmissionHoldResponse>();
                adapter.acquireCanonicalPlayerAdmissionHold(null, acquire);
                assertThat(acquire.code()).isEqualTo(Status.Code.PERMISSION_DENIED);
                var read = new Collector<ReadCanonicalPlayerAdmissionHoldResponse>();
                adapter.readCanonicalPlayerAdmissionHold(null, read);
                assertThat(read.code()).isEqualTo(Status.Code.PERMISSION_DENIED);
              });
    }
    SessionContext.setContext(
        WorldCanonicalPlayerAdmissionHoldTest.ACCOUNT, java.util.List.of(), java.util.Map.of());
    account()
        .run(
            () -> {
              var acquire = new Collector<AcquireCanonicalPlayerAdmissionHoldResponse>();
              adapter.acquireCanonicalPlayerAdmissionHold(null, acquire);
              assertThat(acquire.code()).isEqualTo(Status.Code.PERMISSION_DENIED);
              var read = new Collector<ReadCanonicalPlayerAdmissionHoldResponse>();
              adapter.readCanonicalPlayerAdmissionHold(null, read);
              assertThat(read.code()).isEqualTo(Status.Code.PERMISSION_DENIED);
            });
    verifyNoInteractions(service);
  }

  @Test
  void ambientTransactionAndSynchronizationDenyBeforeDecode() {
    account()
        .run(
            () -> {
              TransactionSynchronizationManager.setActualTransactionActive(true);
              var acquire = new Collector<AcquireCanonicalPlayerAdmissionHoldResponse>();
              adapter.acquireCanonicalPlayerAdmissionHold(null, acquire);
              assertThat(acquire.code()).isEqualTo(Status.Code.FAILED_PRECONDITION);
              TransactionSynchronizationManager.setActualTransactionActive(false);
              TransactionSynchronizationManager.initSynchronization();
              var read = new Collector<ReadCanonicalPlayerAdmissionHoldResponse>();
              adapter.readCanonicalPlayerAdmissionHold(null, read);
              assertThat(read.code()).isEqualTo(Status.Code.FAILED_PRECONDITION);
            });
    verifyNoInteractions(service);
  }

  @Test
  void acquireAndReadEchoFreshCorrelationAndExactOriginalBindingWithSeparateOuterDigest() {
    var request = request();
    var held = mockedLifecycleHold(request);
    when(service.acquire(
            request.getOriginalLeaseJson().toStringUtf8(),
            request.getOriginalLeaseSha256(),
            7L,
            8L))
        .thenReturn(held);
    when(service.read(
            request.getOriginalLeaseJson().toStringUtf8(),
            request.getOriginalLeaseSha256(),
            7L,
            8L))
        .thenReturn(Optional.of(held));
    account()
        .run(
            () -> {
              var acquire = new Collector<AcquireCanonicalPlayerAdmissionHoldResponse>();
              adapter.acquireCanonicalPlayerAdmissionHold(request, acquire);
              assertThat(acquire.error).isNull();
              assertThat(acquire.completed).isTrue();
              assertThat(acquire.value.getRequestId()).isEqualTo(request.getRequestId());
              assertThat(acquire.value.getOriginalLeaseSha256())
                  .isEqualTo(request.getOriginalLeaseSha256());
              assertThat(acquire.value.getHoldEvidenceJson().toByteArray())
                  .containsExactly(held.canonicalBytes());
              assertThat(acquire.value.getHoldEvidenceSha256()).isEqualTo(held.sha256());
              String readCorrelation = UUID.randomUUID().toString();
              var readRequest =
                  readRequest(request).toBuilder().setRequestId(readCorrelation).build();
              var read = new Collector<ReadCanonicalPlayerAdmissionHoldResponse>();
              adapter.readCanonicalPlayerAdmissionHold(readRequest, read);
              assertThat(read.error).isNull();
              assertThat(read.completed).isTrue();
              assertThat(read.value.getRequestId()).isEqualTo(readCorrelation);
              assertThat(read.value.getOriginalLeaseSha256())
                  .isEqualTo(request.getOriginalLeaseSha256());
              assertThat(read.value.getHoldEvidenceJson())
                  .isEqualTo(acquire.value.getHoldEvidenceJson());
              assertThat(read.value.getHoldEvidenceSha256())
                  .isEqualTo(acquire.value.getHoldEvidenceSha256());
            });
  }

  @Test
  void missingReadIsUnresolvedAndNeverAnAbsenceOrReleaseProof() {
    when(service.read(anyString(), anyString(), anyLong(), anyLong())).thenReturn(Optional.empty());
    account()
        .run(
            () -> {
              var read = new Collector<ReadCanonicalPlayerAdmissionHoldResponse>();
              adapter.readCanonicalPlayerAdmissionHold(readRequest(request()), read);
              assertThat(read.code()).isEqualTo(Status.Code.FAILED_PRECONDITION);
              assertThat(read.value).isNull();
              assertThat(read.completed).isFalse();
            });
  }

  @Test
  void malformedUnknownAndNoncanonicalPrimitivesDenyWithoutOwnerCalls() {
    var original = request();
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();
    for (var changed :
        java.util.List.of(
            original.toBuilder().setRequestId("invalid").build(),
            original.toBuilder().setExpectedLifecycleEpoch("07").build(),
            original.toBuilder().setExpectedRowVersion("9223372036854775808").build(),
            original.toBuilder().setOriginalLeaseSha256("b".repeat(64)).build(),
            original.toBuilder()
                .setOriginalLeaseJson(ByteString.copyFrom(new byte[] {(byte) 0xc3, 0x28}))
                .build(),
            original.toBuilder()
                .setOriginalLeaseJson(
                    ByteString.copyFrom(
                        new byte
                            [net.firedevops.firemud.common.account.admission
                                    .AccountGameplayAdmissionLeaseEvidence.MAX_EVIDENCE_BYTES
                                + 1]))
                .build(),
            original.toBuilder().setUnknownFields(unknown).build())) {
      account()
          .run(
              () -> {
                var acquire = new Collector<AcquireCanonicalPlayerAdmissionHoldResponse>();
                adapter.acquireCanonicalPlayerAdmissionHold(changed, acquire);
                assertThat(acquire.code()).isEqualTo(Status.Code.INVALID_ARGUMENT);
              });
    }
    var unknownRead = readRequest(original).toBuilder().setUnknownFields(unknown).build();
    account()
        .run(
            () -> {
              var read = new Collector<ReadCanonicalPlayerAdmissionHoldResponse>();
              adapter.readCanonicalPlayerAdmissionHold(unknownRead, read);
              assertThat(read.code()).isEqualTo(Status.Code.INVALID_ARGUMENT);
            });
    verifyNoInteractions(service);
  }

  @Test
  void changedOwnerBindingOrWorldScopeCannotProduceResponse() {
    var request = request();
    var held = mockedLifecycleHold(request);
    var lease = WorldCanonicalPlayerAdmissionHoldTest.lease();
    var changedCarrier = new java.util.LinkedHashMap<>(lease.carrier());
    changedCarrier.put("leaseFence", "2");
    var changedLease =
        net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence
            .fromCarrier(changedCarrier);
    var changed =
        new WorldCanonicalPlayerAdmissionHoldEvidence(
            held.holdId(),
            held.holdFence(),
            new WorldCanonicalPlayerAdmissionHoldEvidence.Request(changedLease, 7L, 8L),
            held.worldEvidence());
    when(service.acquire(anyString(), anyString(), anyLong(), anyLong())).thenReturn(changed);
    account()
        .run(
            () -> {
              var acquire = new Collector<AcquireCanonicalPlayerAdmissionHoldResponse>();
              adapter.acquireCanonicalPlayerAdmissionHold(request, acquire);
              assertThat(acquire.code()).isEqualTo(Status.Code.FAILED_PRECONDITION);
              assertThat(acquire.value).isNull();
            });
    when(service.acquire(anyString(), anyString(), anyLong(), anyLong())).thenReturn(held);
    when(held.worldEvidence().request().worldSlug()).thenReturn("substituted-world");
    account()
        .run(
            () -> {
              var acquire = new Collector<AcquireCanonicalPlayerAdmissionHoldResponse>();
              adapter.acquireCanonicalPlayerAdmissionHold(request, acquire);
              assertThat(acquire.code()).isEqualTo(Status.Code.FAILED_PRECONDITION);
              assertThat(acquire.value).isNull();
            });
  }

  private static AcquireCanonicalPlayerAdmissionHoldRequest request() {
    var lease = WorldCanonicalPlayerAdmissionHoldTest.lease();
    return AcquireCanonicalPlayerAdmissionHoldRequest.newBuilder()
        .setRequestId(UUID.randomUUID().toString())
        .setOriginalLeaseJson(ByteString.copyFromUtf8(lease.canonicalJson()))
        .setOriginalLeaseSha256(lease.sha256())
        .setExpectedLifecycleEpoch("7")
        .setExpectedRowVersion("8")
        .build();
  }

  private static ReadCanonicalPlayerAdmissionHoldRequest readRequest(
      AcquireCanonicalPlayerAdmissionHoldRequest request) {
    return ReadCanonicalPlayerAdmissionHoldRequest.newBuilder()
        .setRequestId(request.getRequestId())
        .setOriginalLeaseJson(request.getOriginalLeaseJson())
        .setOriginalLeaseSha256(request.getOriginalLeaseSha256())
        .setExpectedLifecycleEpoch(request.getExpectedLifecycleEpoch())
        .setExpectedRowVersion(request.getExpectedRowVersion())
        .build();
  }

  private static WorldCanonicalPlayerAdmissionHoldEvidence mockedLifecycleHold(
      AcquireCanonicalPlayerAdmissionHoldRequest request) {
    var lease =
        net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence
            .parseCanonical(request.getOriginalLeaseJson().toStringUtf8());
    var world = WorldCanonicalPlayerAdmissionHoldTest.evidence();
    // Opaque mocked lifecycle bytes test adapter serialization only. The Common test uses real
    // complete nested codecs and independently verifies their canonical roundtrip.
    when(world.canonicalBytes())
        .thenReturn("mocked lifecycle bytes".getBytes(StandardCharsets.UTF_8));
    return new WorldCanonicalPlayerAdmissionHoldEvidence(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new WorldCanonicalPlayerAdmissionHoldEvidence.Request(lease, 7L, 8L),
        world);
  }

  private static Context peer(String uri) {
    return Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(uri).orElseThrow());
  }

  private static Context account() {
    return peer("spiffe://firemud/ns/firemud/sa/account-service");
  }

  private static final class Collector<T> implements StreamObserver<T> {
    private T value;
    private Status error;
    private boolean completed;

    public void onNext(T response) {
      value = response;
    }

    public void onError(Throwable failure) {
      error = Status.fromThrowable(failure);
    }

    public void onCompleted() {
      completed = true;
    }

    private Status.Code code() {
      return error.getCode();
    }
  }
}
