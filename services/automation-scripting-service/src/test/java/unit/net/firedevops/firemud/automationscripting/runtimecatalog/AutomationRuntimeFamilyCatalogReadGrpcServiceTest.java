package unit.net.firedevops.firemud.automationscripting.runtimecatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.automationscripting.runtimecatalog.AutomationRuntimeFamilyCatalogReadGrpcService;
import net.firedevops.firemud.automationscripting.runtimecatalog.AutomationRuntimeFamilyCatalogRepository;
import net.firedevops.firemud.automationscripting.v1.ReadAutomationRuntimeFamilyCatalogRequest;
import net.firedevops.firemud.automationscripting.v1.ReadAutomationRuntimeFamilyCatalogResponse;
import net.firedevops.firemud.common.automation.AutomationRuntimeFamilyCatalog.Request;
import net.firedevops.firemud.common.automation.AutomationRuntimeFamilyCatalogCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Controlled identity context proves guard order; physical mTLS is separate client proof. */
class AutomationRuntimeFamilyCatalogReadGrpcServiceTest {
  @Test
  void exactWorkloadGuardPrecedesMalformedDecodeAndAllSqlIncludingAmbientRoles() {
    var repository = mock(AutomationRuntimeFamilyCatalogRepository.class);
    var service = new AutomationRuntimeFamilyCatalogReadGrpcService(repository, "test");
    var malformed =
        ReadAutomationRuntimeFamilyCatalogRequest.newBuilder()
            .addCanonicalRequestBytes(ByteString.copyFromUtf8("bad frame"))
            .build();
    assertThat(call(service, malformed, null).error).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(call(service, malformed, peer("account-service", "test")).error)
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(call(service, malformed, peer("game-session-service", "other")).error)
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    for (int context = 0; context < 4; context++) {
      switch (context) {
        case 0 -> SessionContext.setContext(UUID.randomUUID().toString(), List.of(), Map.of());
        case 1 -> SessionContext.setContext(null, List.of("platformAdmin"), Map.of());
        case 2 -> SessionContext.setContext(null, List.of(), Map.of("42", List.of("tenantAdmin")));
        default ->
            SessionContext.setContext(
                null, List.of(), Map.of(), true, "game-session-service", "controlled");
      }
      try {
        assertThat(call(service, malformed, peer("game-session-service", "test")).error)
            .isEqualTo(Status.Code.PERMISSION_DENIED);
      } finally {
        SessionContext.clear();
      }
    }
    verifyNoInteractions(repository);
  }

  @Test
  void ambientSqlAndSynchronizationRefuseBeforeDecode() {
    var repository = mock(AutomationRuntimeFamilyCatalogRepository.class);
    var service = new AutomationRuntimeFamilyCatalogReadGrpcService(repository, "test");
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThat(
              call(
                      service,
                      ReadAutomationRuntimeFamilyCatalogRequest.getDefaultInstance(),
                      peer("game-session-service", "test"))
                  .error)
          .isEqualTo(Status.Code.FAILED_PRECONDITION);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    TransactionSynchronizationManager.initSynchronization();
    try {
      assertThat(
              call(
                      service,
                      ReadAutomationRuntimeFamilyCatalogRequest.getDefaultInstance(),
                      peer("game-session-service", "test"))
                  .error)
          .isEqualTo(Status.Code.FAILED_PRECONDITION);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
    verifyNoInteractions(repository);
  }

  @Test
  void authenticatedWrongNamespaceMalformedAndOversizedRequestsRefuseBeforeSql() {
    var repository = mock(AutomationRuntimeFamilyCatalogRepository.class);
    var service = new AutomationRuntimeFamilyCatalogReadGrpcService(repository, "test");
    var peer = peer("game-session-service", "test");
    assertThat(
            call(service, ReadAutomationRuntimeFamilyCatalogRequest.getDefaultInstance(), peer)
                .error)
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(
            call(
                    service,
                    AutomationRuntimeFamilyCatalogCodec.toRequest(
                        new Request(UUID.randomUUID(), "other")),
                    peer)
                .error)
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(
            call(
                    service,
                    ReadAutomationRuntimeFamilyCatalogRequest.newBuilder()
                        .addCanonicalRequestBytes(ByteString.copyFrom(new byte[1025]))
                        .build(),
                    peer)
                .error)
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(repository);
  }

  @Test
  void authenticatedRequestReachesOwnerButDriftAndStorageOutageNeverBecomeEmptyEvidence() {
    var repository = mock(AutomationRuntimeFamilyCatalogRepository.class);
    var service = new AutomationRuntimeFamilyCatalogReadGrpcService(repository, "test");
    var read = new Request(UUID.randomUUID(), "test");
    var wire = AutomationRuntimeFamilyCatalogCodec.toRequest(read);
    when(repository.read(read))
        .thenThrow(
            new AutomationRuntimeFamilyCatalogRepository.CatalogConflictException(
                "private details"));
    Collector drift = call(service, wire, peer("game-session-service", "test"));
    assertThat(drift.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(drift.value).isNull();
    assertThat(drift.description).doesNotContain("private details");
    verify(repository).read(read);
    doThrow(
            new org.jooq.exception.DataAccessException(
                "private SQL", new SQLException("private SQL", "08006")))
        .when(repository)
        .read(read);
    Collector outage = call(service, wire, peer("game-session-service", "test"));
    assertThat(outage.error).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(outage.value).isNull();
    assertThat(outage.description).doesNotContain("private SQL");
  }

  private static Collector call(
      AutomationRuntimeFamilyCatalogReadGrpcService service,
      ReadAutomationRuntimeFamilyCatalogRequest request,
      GrpcPeerIdentity peer) {
    var collector = new Collector();
    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
        .run(() -> service.readAutomationRuntimeFamilyCatalog(request, collector));
    return collector;
  }

  private static GrpcPeerIdentity peer(String service, String namespace) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
  }

  private static final class Collector
      implements StreamObserver<ReadAutomationRuntimeFamilyCatalogResponse> {
    private Status.Code error;
    private String description;
    private ReadAutomationRuntimeFamilyCatalogResponse value;

    @Override
    public void onNext(ReadAutomationRuntimeFamilyCatalogResponse next) {
      value = next;
    }

    @Override
    public void onError(Throwable failure) {
      assertThat(value).isNull();
      error = Status.fromThrowable(failure).getCode();
      description = Status.fromThrowable(failure).getDescription();
    }

    @Override
    public void onCompleted() {
      assertThat(value).isNotNull();
    }
  }
}
