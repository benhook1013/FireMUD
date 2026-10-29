package net.firedevops.firemud.gamesession.repository;

import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.TlsServerCredentials;
import java.util.List;
import java.util.Objects;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec;
import net.firedevops.firemud.common.grpc.BoundedGrpcServerFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.grpc.GrpcRequestBytePolicy;
import net.firedevops.firemud.gamesession.client.AccountGameplayAdmissionLeaseClient;
import net.firedevops.firemud.gamesession.security.CanonicalGameplayAdmissionDecisionReadWorkloadGuard;
import net.firedevops.firemud.gamesession.service.impl.CanonicalGameplayAdmissionDecisionReadGrpcService;
import org.jooq.DSLContext;

/** Explicit unregistered construction paths for the first-NEW abort owner and reverse read. */
public final class CanonicalGameplayAdmissionAbortManualFactory {
  private CanonicalGameplayAdmissionAbortManualFactory() {}

  public static CanonicalGameplayOriginalNewNeverAdmittedAbortOwner
      createUnregisteredNeverAdmittedOwner(
          DSLContext dsl,
          io.lettuce.core.RedisClient redis,
          net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog catalog,
          CanonicalGameplayTenantGameplayTagRepository tags,
          CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource issuerSource,
          CanonicalGameplayOriginalNewClosureQualification qualification,
          AccountGameplayAdmissionLeaseClient accountClient) {
    return new CanonicalGameplayOriginalNewNeverAdmittedAbortOwner(
        dsl, redis, catalog, tags, issuerSource, qualification, accountClient);
  }

  public static CanonicalGameplayAdmissionDecisionReadGrpcService
      createUnregisteredNegativeDecisionReadReceiver(
          DSLContext dsl,
          String workloadNamespace,
          CanonicalGameplayOriginalNewCandidateClosureRedisOwner closure) {
    return new CanonicalGameplayAdmissionDecisionReadGrpcService(
        new CanonicalGameplayBindingInventoryRepository(dsl),
        new CanonicalGameplayAdmissionDecisionReadWorkloadGuard(workloadNamespace),
        workloadNamespace,
        Objects.requireNonNull(closure));
  }

  public static CanonicalGameplayAdmissionAbortOwner createUnregisteredOwner(
      DSLContext dsl, AccountGameplayAdmissionLeaseClient accountClient) {
    return new CanonicalGameplayAdmissionAbortOwner(
        new CanonicalGameplayBindingInventoryRepository(
            Objects.requireNonNull(dsl, "Game Session SQL context required")),
        Objects.requireNonNull(accountClient, "Account gameplay lease client required"));
  }

  public static CanonicalGameplayAdmissionDecisionReadGrpcService
      createUnregisteredDecisionReadReceiver(DSLContext dsl, String workloadNamespace) {
    var repository =
        new CanonicalGameplayBindingInventoryRepository(
            Objects.requireNonNull(dsl, "Game Session SQL context required"));
    var guard = new CanonicalGameplayAdmissionDecisionReadWorkloadGuard(workloadNamespace);
    return new CanonicalGameplayAdmissionDecisionReadGrpcService(
        repository, guard, workloadNamespace);
  }

  /**
   * Creates an unstarted mutual-TLS server; production registration remains an explicit owner
   * decision.
   */
  public static Server createUnregisteredDecisionReadServer(
      String address,
      KeyManagerFactory keys,
      TrustManagerFactory trust,
      DSLContext dsl,
      String workloadNamespace) {
    Objects.requireNonNull(address, "gRPC server address required");
    Objects.requireNonNull(keys, "gRPC server key manager required");
    Objects.requireNonNull(trust, "gRPC server trust manager required");
    var guard = new CanonicalGameplayAdmissionDecisionReadWorkloadGuard(workloadNamespace);
    var receiver =
        new CanonicalGameplayAdmissionDecisionReadGrpcService(
            new CanonicalGameplayBindingInventoryRepository(
                Objects.requireNonNull(dsl, "Game Session SQL context required")),
            guard,
            workloadNamespace);
    var factory =
        new BoundedGrpcServerFactory(
            address,
            List.of(),
            keys,
            trust,
            TlsServerCredentials.ClientAuth.REQUIRE,
            GrpcRequestBytePolicy.ordinary(),
            CanonicalGameplayAdmissionDecisionReadGrpcCodec.MAX_REQUEST_WIRE_BYTES);
    factory.addService(
        ServerInterceptors.interceptForward(receiver, new GrpcPeerIdentityInterceptor(), guard));
    return factory.createServer();
  }
}
