package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.TlsServerCredentials;
import java.util.List;
import java.util.Objects;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec;
import net.firedevops.firemud.common.grpc.BoundedGrpcServerFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.grpc.GrpcRequestBytePolicy;

/** Explicit construction path for the unregistered Account admission-protection receiver. */
public final class AccountStartSessionAdmissionProtectionManualFactory {
  private AccountStartSessionAdmissionProtectionManualFactory() {}

  public static AccountStartSessionAdmissionProtectionGrpcService createUnregisteredReceiver(
      AccountStartSessionAdmissionProtectionAcquisitionService owner, String workloadNamespace) {
    return new AccountStartSessionAdmissionProtectionGrpcService(
        Objects.requireNonNull(owner, "Account protection acquisition owner required"),
        new AccountStartSessionAdmissionProtectionWorkloadGuard(workloadNamespace));
  }

  /** Creates an unstarted, method-bounded TLS server without registering a production listener. */
  public static Server createUnregisteredServer(
      String address,
      KeyManagerFactory keys,
      TrustManagerFactory trust,
      AccountStartSessionAdmissionProtectionAcquisitionService owner,
      String workloadNamespace) {
    Objects.requireNonNull(address, "gRPC server address required");
    Objects.requireNonNull(keys, "gRPC server key manager required");
    Objects.requireNonNull(trust, "gRPC server trust manager required");
    var policy = GrpcRequestBytePolicy.accountStartSessionAdmissionProtectionAcquisition();
    var factory =
        new BoundedGrpcServerFactory(
            address,
            List.of(),
            keys,
            trust,
            TlsServerCredentials.ClientAuth.REQUIRE,
            policy,
            AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.MAX_REQUEST_WIRE_BYTES);
    factory.addService(
        ServerInterceptors.intercept(
            createUnregisteredReceiver(owner, workloadNamespace),
            new GrpcPeerIdentityInterceptor()));
    return factory.createServer();
  }
}
