package unit.net.firedevops.firemud.entitymanagement.runtimecatalog;

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
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.entity.EntityRuntimeFamilyCatalog;
import net.firedevops.firemud.common.entity.EntityRuntimeFamilyCatalog.Evidence;
import net.firedevops.firemud.common.entity.EntityRuntimeFamilyCatalog.Request;
import net.firedevops.firemud.common.entity.EntityRuntimeFamilyCatalogCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.repository.EntityRuntimeFamilyCatalogRepository;
import net.firedevops.firemud.entitymanagement.runtimecatalog.EntityRuntimeFamilyCatalogReadGrpcService;
import net.firedevops.firemud.entitymanagement.v1.ReadEntityRuntimeFamilyCatalogRequest;
import net.firedevops.firemud.entitymanagement.v1.ReadEntityRuntimeFamilyCatalogResponse;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Controlled peer context, not certificate proof. Separate client fixture exercises physical mTLS.
 */
class EntityRuntimeFamilyCatalogReadGrpcServiceTest {
  @Test
  void workloadRefusalPrecedesMalformedDecodeAndSql() {
    var repository = mock(EntityRuntimeFamilyCatalogRepository.class);
    var service = new EntityRuntimeFamilyCatalogReadGrpcService(repository, "test");
    var malformed =
        ReadEntityRuntimeFamilyCatalogRequest.newBuilder()
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
    var repository = mock(EntityRuntimeFamilyCatalogRepository.class);
    var service = new EntityRuntimeFamilyCatalogReadGrpcService(repository, "test");
    var malformed = ReadEntityRuntimeFamilyCatalogRequest.getDefaultInstance();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThat(call(service, malformed, peer("game-session-service", "test")).error)
          .isEqualTo(Status.Code.FAILED_PRECONDITION);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    TransactionSynchronizationManager.initSynchronization();
    try {
      assertThat(call(service, malformed, peer("game-session-service", "test")).error)
          .isEqualTo(Status.Code.FAILED_PRECONDITION);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
    verifyNoInteractions(repository);
  }

  @Test
  void authenticatedMalformedOversizedAndWrongNamespaceRefuseBeforeSql() {
    var repository = mock(EntityRuntimeFamilyCatalogRepository.class);
    var service = new EntityRuntimeFamilyCatalogReadGrpcService(repository, "test");
    var peer = peer("game-session-service", "test");
    assertThat(
            call(service, ReadEntityRuntimeFamilyCatalogRequest.getDefaultInstance(), peer).error)
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(
            call(
                    service,
                    ReadEntityRuntimeFamilyCatalogRequest.newBuilder()
                        .addCanonicalRequestBytes(ByteString.copyFrom(new byte[1025]))
                        .build(),
                    peer)
                .error)
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(
            call(
                    service,
                    EntityRuntimeFamilyCatalogCodec.toRequest(
                        new Request(UUID.randomUUID(), "other")),
                    peer)
                .error)
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(repository);
  }

  @Test
  void completePositiveResponseAndDriftOutageRefusalsPreserveCorrelationWithoutPrivateDetails() {
    var repository = mock(EntityRuntimeFamilyCatalogRepository.class);
    var service = new EntityRuntimeFamilyCatalogReadGrpcService(repository, "test");
    var request = new Request(UUID.randomUUID(), "test");
    var wire = EntityRuntimeFamilyCatalogCodec.toRequest(request);
    String json =
        """
        {"schema":"entity-runtime-family-catalog/v1","owner":"entity-management-service","catalogEpoch":"1",
         "families":[{"familyId":"characters","relation":"characters","component":"","role":"RUNTIME_PAYLOAD",
           "stateClass":"S1","freshDisposition":"PROVISIONAL_NAMESPACE"}],
         "schemaFacts":[{"kind":"TABLE","identity":"characters",
           "definitionDigest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]}
        """;
    var evidence =
        new Evidence(
            request,
            UUID.randomUUID(),
            EntityRuntimeFamilyCatalog.fromStored(json.getBytes(StandardCharsets.UTF_8)));
    when(repository.read(request)).thenReturn(evidence);
    var success = call(service, wire, peer("game-session-service", "test"));
    assertThat(success.error).isNull();
    assertThat(success.completed).isTrue();
    assertThat(EntityRuntimeFamilyCatalogCodec.fromResponse(request, success.value))
        .isEqualTo(evidence);
    verify(repository).read(request);
    doThrow(new EntityRuntimeFamilyCatalogRepository.CatalogConflictException("private details"))
        .when(repository)
        .read(request);
    var drift = call(service, wire, peer("game-session-service", "test"));
    assertThat(drift.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(drift.value).isNull();
    assertThat(drift.description).doesNotContain("private details");
    doThrow(
            new org.jooq.exception.DataAccessException(
                "private SQL", new SQLException("private SQL", "08006")))
        .when(repository)
        .read(request);
    var outage = call(service, wire, peer("game-session-service", "test"));
    assertThat(outage.error).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(outage.value).isNull();
    assertThat(outage.description).doesNotContain("private SQL");
  }

  private static GrpcPeerIdentity peer(String service, String namespace) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
  }

  private static Collector call(
      EntityRuntimeFamilyCatalogReadGrpcService service,
      ReadEntityRuntimeFamilyCatalogRequest wire,
      GrpcPeerIdentity peer) {
    var result = new Collector();
    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
        .run(() -> service.readEntityRuntimeFamilyCatalog(wire, result));
    return result;
  }

  private static final class Collector
      implements StreamObserver<ReadEntityRuntimeFamilyCatalogResponse> {
    private Status.Code error;
    private String description;
    private ReadEntityRuntimeFamilyCatalogResponse value;
    private boolean completed;

    @Override
    public void onNext(ReadEntityRuntimeFamilyCatalogResponse next) {
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
      completed = true;
    }
  }
}
