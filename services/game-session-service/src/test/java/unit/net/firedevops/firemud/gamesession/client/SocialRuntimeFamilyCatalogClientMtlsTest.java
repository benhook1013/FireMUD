package unit.net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Context;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsServerCredentials;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BoundedGrpcServerFactory;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.grpc.GrpcRequestBytePolicy;
import net.firedevops.firemud.common.social.SocialRuntimeFamilyCatalog;
import net.firedevops.firemud.common.social.SocialRuntimeFamilyCatalog.Evidence;
import net.firedevops.firemud.common.social.SocialRuntimeFamilyCatalog.Request;
import net.firedevops.firemud.common.social.SocialRuntimeFamilyCatalogCodec;
import net.firedevops.firemud.gamesession.client.SocialRuntimeFamilyCatalogClient;
import net.firedevops.firemud.socialgroups.v1.ReadSocialRuntimeFamilyCatalogRequest;
import net.firedevops.firemud.socialgroups.v1.ReadSocialRuntimeFamilyCatalogResponse;
import net.firedevops.firemud.socialgroups.v1.SocialRuntimeFamilyCatalogReadServiceGrpc;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Physical mTLS client/codec proof; controlled bytes are not installed-schema or FRESH proof. */
@Timeout(60)
class SocialRuntimeFamilyCatalogClientMtlsTest {
  @TempDir static Path directory;
  private static LoopbackMtlsTestPki.Pki pki;
  private static final String NAMESPACE = "catalog-test";

  @BeforeAll
  static void certificates() throws Exception {
    pki =
        LoopbackMtlsTestPki.create(
            directory,
            "Social runtime catalog test CA",
            new LoopbackMtlsTestPki.IdentitySpec(
                "client", uri("game-session-service", NAMESPACE), false),
            new LoopbackMtlsTestPki.IdentitySpec(
                "social", uri("social-groups-service", NAMESPACE), true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "wrong-role", uri("entity-management-service", NAMESPACE), true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "foreign-namespace", uri("social-groups-service", "other"), true));
  }

  @Test
  void actualPeerQualifiedClientPreservesExactEvidenceCorrelationAndFiniteDeadline()
      throws Exception {
    Request request = new Request(UUID.randomUUID(), NAMESPACE);
    Evidence expected = evidence(request);
    var observed = new AtomicReference<Request>();
    var peer = new AtomicReference<String>();
    var remaining = new AtomicLong();
    var service =
        new SocialRuntimeFamilyCatalogReadServiceGrpc
            .SocialRuntimeFamilyCatalogReadServiceImplBase() {
          @Override
          public void readSocialRuntimeFamilyCatalog(
              ReadSocialRuntimeFamilyCatalogRequest wire,
              StreamObserver<ReadSocialRuntimeFamilyCatalogResponse> observer) {
            peer.set(GrpcPeerIdentity.current().uri());
            remaining.set(Context.current().getDeadline().timeRemaining(TimeUnit.MILLISECONDS));
            observed.set(SocialRuntimeFamilyCatalogCodec.fromRequest(wire));
            observer.onNext(SocialRuntimeFamilyCatalogCodec.toResponse(request, expected));
            observer.onCompleted();
          }
        };
    Server server = server("social", service);
    try (var client = client(server)) {
      assertThatThrownBy(() -> client.read(request)).isInstanceOf(IllegalStateException.class);
      client.init();
      Evidence result = client.read(request);
      assertThat(result).isEqualTo(expected);
      assertThat(result.catalog().exactBytes()).isEqualTo(expected.catalog().exactBytes());
      assertThat(result.digest()).isEqualTo(expected.digest());
      assertThat(observed.get()).isEqualTo(request);
      assertThat(peer.get()).isEqualTo(uri("game-session-service", NAMESPACE));
      assertThat(remaining.get()).isBetween(1L, 5000L);
    } finally {
      stop(server);
    }
  }

  @Test
  void sameCaWrongSocialRoleAndForeignNamespaceRefuseBeforeHandler() throws Exception {
    for (String identity : List.of("wrong-role", "foreign-namespace")) {
      var calls = new AtomicInteger();
      Server server =
          server(
              identity,
              new SocialRuntimeFamilyCatalogReadServiceGrpc
                  .SocialRuntimeFamilyCatalogReadServiceImplBase() {
                @Override
                public void readSocialRuntimeFamilyCatalog(
                    ReadSocialRuntimeFamilyCatalogRequest request,
                    StreamObserver<ReadSocialRuntimeFamilyCatalogResponse> observer) {
                  calls.incrementAndGet();
                  observer.onError(Status.INTERNAL.asRuntimeException());
                }
              });
      try (var client = client(server)) {
        client.init();
        assertThatThrownBy(() -> client.read(new Request(UUID.randomUUID(), NAMESPACE)))
            .isInstanceOfSatisfying(
                StatusRuntimeException.class,
                error ->
                    assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
        assertThat(calls).hasValue(0);
      } finally {
        stop(server);
      }
    }
  }

  @Test
  void changedCorrelationDuplicateIncompleteAndOversizedEvidenceNeverBecomeSourceInput()
      throws Exception {
    Request request = new Request(UUID.randomUUID(), NAMESPACE);
    Request other = new Request(UUID.randomUUID(), NAMESPACE);
    var wrong = SocialRuntimeFamilyCatalogCodec.toResponse(other, evidence(other));
    var valid = SocialRuntimeFamilyCatalogCodec.toResponse(request, evidence(request));
    var duplicate =
        valid.toBuilder().addCanonicalResponseBytes(valid.getCanonicalResponseBytes(0)).build();
    var oversized =
        valid.toBuilder()
            .setCanonicalResponseBytes(
                0,
                com.google.protobuf.ByteString.copyFrom(
                    new byte[SocialRuntimeFamilyCatalogCodec.MAX_RESPONSE_BYTES + 1]))
            .build();
    for (ReadSocialRuntimeFamilyCatalogResponse response :
        List.of(
            wrong,
            ReadSocialRuntimeFamilyCatalogResponse.getDefaultInstance(),
            duplicate,
            oversized)) {
      Server server = server("social", returning(response));
      try (var client = client(server)) {
        client.init();
        assertThatThrownBy(() -> client.read(request))
            .isInstanceOfAny(IllegalArgumentException.class, StatusRuntimeException.class);
      } finally {
        stop(server);
      }
    }
  }

  @Test
  void unavailableAndWrongLocalNamespaceAreRefusalNotEmptyCatalog() throws Exception {
    var calls = new AtomicInteger();
    Server server =
        server(
            "social",
            new SocialRuntimeFamilyCatalogReadServiceGrpc
                .SocialRuntimeFamilyCatalogReadServiceImplBase() {
              @Override
              public void readSocialRuntimeFamilyCatalog(
                  ReadSocialRuntimeFamilyCatalogRequest request,
                  StreamObserver<ReadSocialRuntimeFamilyCatalogResponse> observer) {
                calls.incrementAndGet();
                observer.onError(Status.UNAVAILABLE.asRuntimeException());
              }
            });
    try (var client = client(server)) {
      client.init();
      assertThatThrownBy(() -> client.read(new Request(UUID.randomUUID(), "other")))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(calls).hasValue(0);
      TransactionSynchronizationManager.initSynchronization();
      try {
        assertThatThrownBy(() -> client.read(new Request(UUID.randomUUID(), NAMESPACE)))
            .isInstanceOf(IllegalStateException.class);
      } finally {
        TransactionSynchronizationManager.clearSynchronization();
      }
      assertThat(calls).hasValue(0);
      assertThatThrownBy(() -> client.read(new Request(UUID.randomUUID(), NAMESPACE)))
          .isInstanceOfSatisfying(
              StatusRuntimeException.class,
              error -> assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE));
      assertThat(calls).hasValue(1);
    } finally {
      stop(server);
    }
    var tls = new CommonGrpcClientProperties();
    tls.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new SocialRuntimeFamilyCatalogClient(
                    new ServiceEndpointsProperties(), tls, new GrpcChannelFactory(), NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static SocialRuntimeFamilyCatalogClient client(Server server) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setSocialGroupsService("localhost:" + server.getPort());
    return new SocialRuntimeFamilyCatalogClient(
        endpoints,
        pki.clientProperties(directory, pki.identity("client")),
        new GrpcChannelFactory(),
        NAMESPACE);
  }

  private static Server server(
      String identity,
      SocialRuntimeFamilyCatalogReadServiceGrpc.SocialRuntimeFamilyCatalogReadServiceImplBase
          service)
      throws Exception {
    var factory =
        new BoundedGrpcServerFactory(
            "127.0.0.1:0",
            List.of(),
            pki.keyManagerFactory(pki.identity(identity)),
            pki.trustManagerFactory(),
            TlsServerCredentials.ClientAuth.REQUIRE,
            GrpcRequestBytePolicy.ordinary(),
            GrpcRequestBytePolicy.ORDINARY_MAXIMUM_BYTES);
    factory.addService(ServerInterceptors.intercept(service, new GrpcPeerIdentityInterceptor()));
    return factory.createServer().start();
  }

  private static void stop(Server server) throws InterruptedException {
    server.shutdownNow();
    assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
  }

  private static SocialRuntimeFamilyCatalogReadServiceGrpc
          .SocialRuntimeFamilyCatalogReadServiceImplBase
      returning(ReadSocialRuntimeFamilyCatalogResponse response) {
    return new SocialRuntimeFamilyCatalogReadServiceGrpc
        .SocialRuntimeFamilyCatalogReadServiceImplBase() {
      @Override
      public void readSocialRuntimeFamilyCatalog(
          ReadSocialRuntimeFamilyCatalogRequest request,
          StreamObserver<ReadSocialRuntimeFamilyCatalogResponse> observer) {
        observer.onNext(response);
        observer.onCompleted();
      }
    };
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

  private static String uri(String service, String namespace) {
    return "spiffe://firemud/ns/" + namespace + "/sa/" + service;
  }
}
