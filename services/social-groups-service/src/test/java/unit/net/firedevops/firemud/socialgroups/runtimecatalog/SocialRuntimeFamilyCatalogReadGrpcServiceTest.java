package unit.net.firedevops.firemud.socialgroups.runtimecatalog;

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
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.social.SocialRuntimeFamilyCatalog;
import net.firedevops.firemud.common.social.SocialRuntimeFamilyCatalog.Evidence;
import net.firedevops.firemud.common.social.SocialRuntimeFamilyCatalog.Request;
import net.firedevops.firemud.common.social.SocialRuntimeFamilyCatalogCodec;
import net.firedevops.firemud.socialgroups.runtimecatalog.SocialRuntimeFamilyCatalogReadGrpcService;
import net.firedevops.firemud.socialgroups.runtimecatalog.SocialRuntimeFamilyCatalogRepository;
import net.firedevops.firemud.socialgroups.v1.ReadSocialRuntimeFamilyCatalogRequest;
import net.firedevops.firemud.socialgroups.v1.ReadSocialRuntimeFamilyCatalogResponse;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Controlled peer identity tests do not qualify physical mTLS; the GS test proves that boundary.
 */
class SocialRuntimeFamilyCatalogReadGrpcServiceTest {
  @Test
  void workloadRefusalPrecedesMalformedDecodeAndOwnerSql() {
    var repository = mock(SocialRuntimeFamilyCatalogRepository.class);
    var service = new SocialRuntimeFamilyCatalogReadGrpcService(repository, "test");
    var malformed =
        ReadSocialRuntimeFamilyCatalogRequest.newBuilder()
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
  void ambientTransactionOrSynchronizationRefusesBeforeDecodeAndSql() {
    var repository = mock(SocialRuntimeFamilyCatalogRepository.class);
    var service = new SocialRuntimeFamilyCatalogReadGrpcService(repository, "test");
    var malformed = ReadSocialRuntimeFamilyCatalogRequest.getDefaultInstance();
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
  void authenticatedMalformedOversizedAndWrongNamespaceRequestsRefuseBeforeSql() {
    var repository = mock(SocialRuntimeFamilyCatalogRepository.class);
    var service = new SocialRuntimeFamilyCatalogReadGrpcService(repository, "test");
    var peer = peer("game-session-service", "test");
    assertThat(
            call(service, ReadSocialRuntimeFamilyCatalogRequest.getDefaultInstance(), peer).error)
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(
            call(
                    service,
                    SocialRuntimeFamilyCatalogCodec.toRequest(
                        new Request(UUID.randomUUID(), "other")),
                    peer)
                .error)
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(
            call(
                    service,
                    ReadSocialRuntimeFamilyCatalogRequest.newBuilder()
                        .addCanonicalRequestBytes(ByteString.copyFrom(new byte[1025]))
                        .build(),
                    peer)
                .error)
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(repository);
  }

  @Test
  void exactAuthenticatedRequestReturnsCompleteEvidenceAndDriftOrOutageNeverBecomesEmptySuccess() {
    var repository = mock(SocialRuntimeFamilyCatalogRepository.class);
    var service = new SocialRuntimeFamilyCatalogReadGrpcService(repository, "test");
    var request = new Request(UUID.randomUUID(), "test");
    var wire = SocialRuntimeFamilyCatalogCodec.toRequest(request);
    var expected = evidence(request);
    when(repository.read(request)).thenReturn(expected);
    Collector success = call(service, wire, peer("game-session-service", "test"));
    assertThat(success.error).isNull();
    assertThat(success.completed).isTrue();
    assertThat(SocialRuntimeFamilyCatalogCodec.fromResponse(request, success.value))
        .isEqualTo(expected);
    verify(repository).read(request);

    doThrow(new SocialRuntimeFamilyCatalogRepository.CatalogConflictException("private details"))
        .when(repository)
        .read(request);
    Collector drift = call(service, wire, peer("game-session-service", "test"));
    assertThat(drift.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(drift.value).isNull();
    assertThat(drift.description).doesNotContain("private details");

    doThrow(
            new org.jooq.exception.DataAccessException(
                "private schema drift", new SQLException("private schema drift", "23514")))
        .when(repository)
        .read(request);
    Collector schemaDrift = call(service, wire, peer("game-session-service", "test"));
    assertThat(schemaDrift.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(schemaDrift.value).isNull();
    assertThat(schemaDrift.description).doesNotContain("private schema drift");

    doThrow(
            new org.jooq.exception.DataAccessException(
                "private SQL", new SQLException("private SQL", "08006")))
        .when(repository)
        .read(request);
    Collector outage = call(service, wire, peer("game-session-service", "test"));
    assertThat(outage.error).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(outage.value).isNull();
    assertThat(outage.description).doesNotContain("private SQL");
  }

  private static Evidence evidence(Request request) {
    String json =
        """
        {"schema":"social-runtime-family-catalog/v1","owner":"social-groups-service","catalogEpoch":"1",
         "families":[{"familyId":"account_friend_links","relation":"account_friend_links","component":"",
          "role":"SOCIAL_RELATIONSHIP","freshDisposition":"ACCOUNT_PAIR_SCOPE_DRIFT","targetOwner":"NONE"},
          {"familyId":"account_friend_links.account_id","relation":"account_friend_links","component":"account_id",
          "role":"ACCOUNT_IDENTITY_CARRIER","freshDisposition":"ACCOUNT_IDENTITY_CARRIER_ONLY","targetOwner":"ACCOUNT_SERVICE"},
          {"familyId":"account_friend_links.friend_account_id","relation":"account_friend_links","component":"friend_account_id",
          "role":"ACCOUNT_IDENTITY_CARRIER","freshDisposition":"ACCOUNT_IDENTITY_CARRIER_ONLY","targetOwner":"ACCOUNT_SERVICE"}],
         "schemaFacts":[{"kind":"COLUMN","identity":"account_friend_links.account_id","definitionDigest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
          {"kind":"COLUMN","identity":"account_friend_links.friend_account_id","definitionDigest":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"},
          {"kind":"TABLE","identity":"account_friend_links","definitionDigest":"sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"}],
         "unimplementedContracts":["CHAT_RESTRICTION_AND_REVISION","DIRECTIONAL_BLOCK_LIFECYCLE",
          "ENTITY_ESCROW_REFERENCE_FAMILIES","GAMEPLAY_WHISPER_NAMESPACE_SCOPE",
          "SAFETY_EVIDENCE_AND_CONTENT_FREE_RECEIPTS","SOCIAL_PLAYER_STANDING_OWNER",
          "SOCIAL_SAGA_ADOPTER_CLASSIFICATION"]}
        """;
    return new Evidence(
        request,
        UUID.randomUUID(),
        SocialRuntimeFamilyCatalog.fromStored(json.getBytes(StandardCharsets.UTF_8)));
  }

  private static Collector call(
      SocialRuntimeFamilyCatalogReadGrpcService service,
      ReadSocialRuntimeFamilyCatalogRequest wire,
      GrpcPeerIdentity peer) {
    var result = new Collector();
    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
        .run(() -> service.readSocialRuntimeFamilyCatalog(wire, result));
    return result;
  }

  private static GrpcPeerIdentity peer(String service, String namespace) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
  }

  private static final class Collector
      implements StreamObserver<ReadSocialRuntimeFamilyCatalogResponse> {
    private Status.Code error;
    private String description;
    private ReadSocialRuntimeFamilyCatalogResponse value;
    private boolean completed;

    @Override
    public void onNext(ReadSocialRuntimeFamilyCatalogResponse next) {
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
