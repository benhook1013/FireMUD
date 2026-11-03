package unit.net.firedevops.firemud.worldmanagement.tenant;

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
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldRuntimeFamilyCatalog.Request;
import net.firedevops.firemud.common.world.WorldRuntimeFamilyCatalogCodec;
import net.firedevops.firemud.worldmanagement.tenant.WorldRuntimeFamilyCatalogReadGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldRuntimeFamilyCatalogRepository;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldRuntimeFamilyCatalogRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldRuntimeFamilyCatalogResponse;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Identity context is controlled here; physical certificate qualification is a separate fixture.
 */
class WorldRuntimeFamilyCatalogReadGrpcServiceTest {
  @Test
  void workloadRefusalPrecedesMalformedDecodeAndAllSql() {
    var repository = mock(WorldRuntimeFamilyCatalogRepository.class);
    var service = new WorldRuntimeFamilyCatalogReadGrpcService(repository, "test");
    var malformed =
        ReadWorldRuntimeFamilyCatalogRequest.newBuilder()
            .addCanonicalRequestBytes(ByteString.copyFromUtf8("bad frame"))
            .build();
    assertThat(call(service, malformed, null).error).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(call(service, malformed, peer("account-service", "test")).error)
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(call(service, malformed, peer("game-session-service", "other")).error)
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    SessionContext.setContext(UUID.randomUUID().toString(), List.of(), Map.of());
    try {
      assertThat(call(service, malformed, peer("game-session-service", "test")).error)
          .isEqualTo(Status.Code.PERMISSION_DENIED);
    } finally {
      SessionContext.clear();
    }
    SessionContext.setContext(
        null, List.of(), Map.of(), true, "game-session-service", "controlled");
    try {
      assertThat(call(service, malformed, peer("game-session-service", "test")).error)
          .isEqualTo(Status.Code.PERMISSION_DENIED);
    } finally {
      SessionContext.clear();
    }
    verifyNoInteractions(repository);
  }

  @Test
  void ambientSqlAndSynchronizationRefuseBeforeDecode() {
    var repository = mock(WorldRuntimeFamilyCatalogRepository.class);
    var service = new WorldRuntimeFamilyCatalogReadGrpcService(repository, "test");
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThat(
              call(
                      service,
                      ReadWorldRuntimeFamilyCatalogRequest.getDefaultInstance(),
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
                      ReadWorldRuntimeFamilyCatalogRequest.getDefaultInstance(),
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
    var repository = mock(WorldRuntimeFamilyCatalogRepository.class);
    var service = new WorldRuntimeFamilyCatalogReadGrpcService(repository, "test");
    var peer = peer("game-session-service", "test");
    assertThat(call(service, ReadWorldRuntimeFamilyCatalogRequest.getDefaultInstance(), peer).error)
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(
            call(
                    service,
                    WorldRuntimeFamilyCatalogCodec.toRequest(
                        new Request(UUID.randomUUID(), "other")),
                    peer)
                .error)
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(
            call(
                    service,
                    ReadWorldRuntimeFamilyCatalogRequest.newBuilder()
                        .addCanonicalRequestBytes(ByteString.copyFrom(new byte[1025]))
                        .build(),
                    peer)
                .error)
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(repository);
  }

  @Test
  void exactAuthenticatedRequestReachesOwnerAndDriftOrOutageNeverBecomesEmptySuccess() {
    var repository = mock(WorldRuntimeFamilyCatalogRepository.class);
    var service = new WorldRuntimeFamilyCatalogReadGrpcService(repository, "test");
    var read = new Request(UUID.randomUUID(), "test");
    var wire = WorldRuntimeFamilyCatalogCodec.toRequest(read);
    when(repository.read(read))
        .thenThrow(
            new WorldRuntimeFamilyCatalogRepository.CatalogConflictException("private details"));
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
      WorldRuntimeFamilyCatalogReadGrpcService service,
      ReadWorldRuntimeFamilyCatalogRequest request,
      GrpcPeerIdentity peer) {
    var result = new Collector();
    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
        .run(() -> service.readWorldRuntimeFamilyCatalog(request, result));
    return result;
  }

  private static GrpcPeerIdentity peer(String service, String namespace) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
  }

  private static final class Collector
      implements StreamObserver<ReadWorldRuntimeFamilyCatalogResponse> {
    private Status.Code error;
    private String description;
    private ReadWorldRuntimeFamilyCatalogResponse value;

    @Override
    public void onNext(ReadWorldRuntimeFamilyCatalogResponse next) {
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
