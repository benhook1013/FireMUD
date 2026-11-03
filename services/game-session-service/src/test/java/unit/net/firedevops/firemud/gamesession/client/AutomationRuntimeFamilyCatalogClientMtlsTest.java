package unit.net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
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
import net.firedevops.firemud.automationscripting.v1.AutomationRuntimeFamilyCatalogReadServiceGrpc;
import net.firedevops.firemud.automationscripting.v1.ReadAutomationRuntimeFamilyCatalogRequest;
import net.firedevops.firemud.automationscripting.v1.ReadAutomationRuntimeFamilyCatalogResponse;
import net.firedevops.firemud.common.automation.AutomationRuntimeFamilyCatalog;
import net.firedevops.firemud.common.automation.AutomationRuntimeFamilyCatalog.Evidence;
import net.firedevops.firemud.common.automation.AutomationRuntimeFamilyCatalog.Request;
import net.firedevops.firemud.common.automation.AutomationRuntimeFamilyCatalogCodec;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BoundedGrpcServerFactory;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.grpc.GrpcRequestBytePolicy;
import net.firedevops.firemud.gamesession.client.AutomationRuntimeFamilyCatalogClient;
import net.firedevops.firemud.test.LoopbackMtlsTestPki;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Actual loopback mTLS client with controlled source bytes/upstream and local test trust. Separate
 * PostgreSQL/producer-context fixtures do not establish a deployed combined owner/PKI boundary,
 * live private authority, enrollment, absence, initialized state or FRESH readiness.
 */
@Timeout(60)
class AutomationRuntimeFamilyCatalogClientMtlsTest {
  @TempDir static Path directory;
  private static LoopbackMtlsTestPki.Pki pki;
  private static final String NAMESPACE = "catalog-test";

  @BeforeAll
  static void certificates() throws Exception {
    pki =
        LoopbackMtlsTestPki.create(
            directory,
            "Automation runtime catalog test CA",
            new LoopbackMtlsTestPki.IdentitySpec(
                "client", uri("game-session-service", NAMESPACE), false),
            new LoopbackMtlsTestPki.IdentitySpec(
                "automation", uri("automation-scripting-service", NAMESPACE), true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "wrong-role", uri("entity-management-service", NAMESPACE), true),
            new LoopbackMtlsTestPki.IdentitySpec(
                "foreign-namespace", uri("automation-scripting-service", "other"), true));
  }

  @Test
  void exactPeerClientRetainsCorrelationSnapshotBytesDigestsAndFiniteDeadline() throws Exception {
    Request request = new Request(UUID.randomUUID(), NAMESPACE);
    Evidence expected = evidence(request);
    var observed = new AtomicReference<Request>();
    var peer = new AtomicReference<String>();
    var remaining = new AtomicLong();
    Server server =
        server(
            "automation",
            new AutomationRuntimeFamilyCatalogReadServiceGrpc
                .AutomationRuntimeFamilyCatalogReadServiceImplBase() {
              @Override
              public void readAutomationRuntimeFamilyCatalog(
                  ReadAutomationRuntimeFamilyCatalogRequest wire,
                  StreamObserver<ReadAutomationRuntimeFamilyCatalogResponse> observer) {
                peer.set(GrpcPeerIdentity.current().uri());
                remaining.set(Context.current().getDeadline().timeRemaining(TimeUnit.MILLISECONDS));
                observed.set(AutomationRuntimeFamilyCatalogCodec.fromRequest(wire));
                observer.onNext(AutomationRuntimeFamilyCatalogCodec.toResponse(request, expected));
                observer.onCompleted();
              }
            });
    try (var client = client(server)) {
      assertThatThrownBy(() -> client.read(request)).isInstanceOf(IllegalStateException.class);
      client.init();
      Evidence actual = client.read(request);
      assertThat(actual).isEqualTo(expected);
      assertThat(actual.catalog().exactBytes()).isEqualTo(expected.catalog().exactBytes());
      assertThat(actual.catalog().digest()).isEqualTo(expected.catalog().digest());
      assertThat(actual.digest()).isEqualTo(expected.digest());
      assertThat(observed.get()).isEqualTo(request);
      assertThat(peer.get()).isEqualTo(uri("game-session-service", NAMESPACE));
      assertThat(remaining.get()).isBetween(1L, 5000L);
    } finally {
      stop(server);
    }
  }

  @Test
  void sameCaWrongServiceAndForeignNamespaceRefuseBeforeUpstreamHandler() throws Exception {
    for (String identity : List.of("wrong-role", "foreign-namespace")) {
      var calls = new AtomicInteger();
      Server server =
          server(
              identity,
              new AutomationRuntimeFamilyCatalogReadServiceGrpc
                  .AutomationRuntimeFamilyCatalogReadServiceImplBase() {
                @Override
                public void readAutomationRuntimeFamilyCatalog(
                    ReadAutomationRuntimeFamilyCatalogRequest wire,
                    StreamObserver<ReadAutomationRuntimeFamilyCatalogResponse> observer) {
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
  void changedCorrelationNamespaceIncompleteDuplicateCorruptAndOversizedResponsesRefuse()
      throws Exception {
    Request request = new Request(UUID.randomUUID(), NAMESPACE);
    Request changed = new Request(UUID.randomUUID(), NAMESPACE);
    Request foreign = new Request(request.readRequestId(), "other");
    var valid = AutomationRuntimeFamilyCatalogCodec.toResponse(request, evidence(request));
    byte[] corrupt = valid.getCanonicalResponseBytes(0).toByteArray();
    corrupt[corrupt.length - 1] ^= 1;
    for (var response :
        List.of(
            AutomationRuntimeFamilyCatalogCodec.toResponse(changed, evidence(changed)),
            AutomationRuntimeFamilyCatalogCodec.toResponse(foreign, evidence(foreign)),
            ReadAutomationRuntimeFamilyCatalogResponse.getDefaultInstance(),
            valid.toBuilder().addCanonicalResponseBytes(valid.getCanonicalResponseBytes(0)).build(),
            valid.toBuilder().setCanonicalResponseBytes(0, ByteString.copyFrom(corrupt)).build(),
            valid.toBuilder()
                .setCanonicalResponseBytes(
                    0,
                    ByteString.copyFrom(
                        new byte[AutomationRuntimeFamilyCatalogCodec.MAX_RESPONSE_BYTES + 1]))
                .build())) {
      Server server = server("automation", returning(response));
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
  void wrongLocalNamespaceAndAmbientSqlNeverCallOwnerAndStorageFailureIsNotEmptySource()
      throws Exception {
    var calls = new AtomicInteger();
    Server server =
        server(
            "automation",
            new AutomationRuntimeFamilyCatalogReadServiceGrpc
                .AutomationRuntimeFamilyCatalogReadServiceImplBase() {
              @Override
              public void readAutomationRuntimeFamilyCatalog(
                  ReadAutomationRuntimeFamilyCatalogRequest wire,
                  StreamObserver<ReadAutomationRuntimeFamilyCatalogResponse> observer) {
                calls.incrementAndGet();
                observer.onError(Status.UNAVAILABLE.asRuntimeException());
              }
            });
    try (var client = client(server)) {
      client.init();
      assertThatThrownBy(() -> client.read(new Request(UUID.randomUUID(), "other")))
          .isInstanceOf(IllegalArgumentException.class);
      TransactionSynchronizationManager.initSynchronization();
      try {
        assertThatThrownBy(() -> client.read(new Request(UUID.randomUUID(), NAMESPACE)))
            .isInstanceOf(IllegalStateException.class);
      } finally {
        TransactionSynchronizationManager.clearSynchronization();
      }
      TransactionSynchronizationManager.setActualTransactionActive(true);
      try {
        assertThatThrownBy(() -> client.read(new Request(UUID.randomUUID(), NAMESPACE)))
            .isInstanceOf(IllegalStateException.class);
      } finally {
        TransactionSynchronizationManager.setActualTransactionActive(false);
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
    var plaintext = new CommonGrpcClientProperties();
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new AutomationRuntimeFamilyCatalogClient(
                    new ServiceEndpointsProperties(),
                    plaintext,
                    new GrpcChannelFactory(),
                    NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void stalledOwnerReachesTheRealFiniteDeadlineRatherThanReturningEmptyEvidence() throws Exception {
    var calls = new AtomicInteger();
    Server server =
        server(
            "automation",
            new AutomationRuntimeFamilyCatalogReadServiceGrpc
                .AutomationRuntimeFamilyCatalogReadServiceImplBase() {
              @Override
              public void readAutomationRuntimeFamilyCatalog(
                  ReadAutomationRuntimeFamilyCatalogRequest wire,
                  StreamObserver<ReadAutomationRuntimeFamilyCatalogResponse> observer) {
                calls.incrementAndGet();
                // Intentionally no terminal signal: the real client deadline must end the call.
              }
            });
    try (var client = client(server)) {
      client.init();
      assertThatThrownBy(() -> client.read(new Request(UUID.randomUUID(), NAMESPACE)))
          .isInstanceOfSatisfying(
              StatusRuntimeException.class,
              error ->
                  assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED));
      assertThat(calls).hasValue(1);
    } finally {
      stop(server);
    }
  }

  private static AutomationRuntimeFamilyCatalogClient client(Server server) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setAutomationScriptingService("localhost:" + server.getPort());
    return new AutomationRuntimeFamilyCatalogClient(
        endpoints,
        pki.clientProperties(directory, pki.identity("client")),
        new GrpcChannelFactory(),
        NAMESPACE);
  }

  private static Server server(
      String identity,
      AutomationRuntimeFamilyCatalogReadServiceGrpc
              .AutomationRuntimeFamilyCatalogReadServiceImplBase
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

  private static AutomationRuntimeFamilyCatalogReadServiceGrpc
          .AutomationRuntimeFamilyCatalogReadServiceImplBase
      returning(ReadAutomationRuntimeFamilyCatalogResponse response) {
    return new AutomationRuntimeFamilyCatalogReadServiceGrpc
        .AutomationRuntimeFamilyCatalogReadServiceImplBase() {
      @Override
      public void readAutomationRuntimeFamilyCatalog(
          ReadAutomationRuntimeFamilyCatalogRequest wire,
          StreamObserver<ReadAutomationRuntimeFamilyCatalogResponse> observer) {
        observer.onNext(response);
        observer.onCompleted();
      }
    };
  }

  private static Evidence evidence(Request request) {
    String json =
        """
        {"schema":"automation-runtime-family-catalog/v1","owner":"automation-scripting-service","catalogEpoch":"1",
         "families":[{"familyId":"npc_memory","relation":"npc_memory","component":"",
          "role":"RUNTIME_PAYLOAD","stateClass":"UNKNOWN","freshDisposition":"STATE_CLASS_UNKNOWN_BLOCKING",
          "retentionDisposition":"UNRESOLVED_RETAINED","ownerConsequence":"Controlled fixture remains blocking."}],
         "schemaFacts":[{"kind":"TABLE","identity":"npc_memory",
          "definitionDigest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}],
         "nonSqlGaps":[{"id":"DURABLE_CHARGE_MARKERS","disposition":"UNSUPPORTED_BLOCKING",
          "ownerConsequence":"Controlled limitation; no SQL charge rows or Redis census."}]}
        """;
    return new Evidence(
        request,
        UUID.randomUUID(),
        AutomationRuntimeFamilyCatalog.fromStored(json.getBytes(StandardCharsets.UTF_8)));
  }

  private static String uri(String service, String namespace) {
    return "spiffe://firemud/ns/" + namespace + "/sa/" + service;
  }
}
