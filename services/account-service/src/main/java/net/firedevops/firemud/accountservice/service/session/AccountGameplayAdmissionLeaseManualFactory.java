package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.TlsServerCredentials;
import java.util.List;
import java.util.Objects;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import net.firedevops.firemud.accountservice.repository.AccountGameplayAdmissionLeaseOwner;
import net.firedevops.firemud.accountservice.repository.AccountOriginalNewBoundGameplayCredentialOwner;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec;
import net.firedevops.firemud.common.grpc.BoundedGrpcServerFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.grpc.GrpcRequestBytePolicy;

/** Explicit construction path for the unregistered Account NEW lease receiver. */
public final class AccountGameplayAdmissionLeaseManualFactory {
  private AccountGameplayAdmissionLeaseManualFactory() {}

  public static AccountGameplayAdmissionLeaseGrpcService createUnregisteredReceiver(
      AccountGameplayAdmissionLeaseOwner owner, String workloadNamespace) {
    return new AccountGameplayAdmissionLeaseGrpcService(
        Objects.requireNonNull(owner, "Account gameplay admission lease owner required"),
        new AccountGameplayAdmissionLeaseWorkloadGuard(workloadNamespace));
  }

  /** Explicit receiver composition that enables only the exact post-COMMITTED credential method. */
  public static AccountGameplayAdmissionLeaseGrpcService createUnregisteredReceiver(
      AccountGameplayAdmissionLeaseOwner owner,
      AccountOriginalNewBoundGameplayCredentialOwner originalNewCredentialOwner,
      String workloadNamespace) {
    return new AccountGameplayAdmissionLeaseGrpcService(
        Objects.requireNonNull(owner, "Account gameplay admission lease owner required"),
        Objects.requireNonNull(
            originalNewCredentialOwner, "Account original-new credential owner required"),
        new AccountGameplayAdmissionLeaseWorkloadGuard(workloadNamespace));
  }

  /** Creates an unstarted TLS server with a finite ceiling and no production registration. */
  public static Server createUnregisteredServer(
      String address,
      KeyManagerFactory keys,
      TrustManagerFactory trust,
      AccountGameplayAdmissionLeaseOwner owner,
      String workloadNamespace) {
    return server(
        address,
        keys,
        trust,
        createUnregisteredReceiver(owner, workloadNamespace),
        workloadNamespace);
  }

  /** Creates an unstarted, method-guarded receiver with the ORIGINAL-NEW credential owner wired. */
  public static Server createUnregisteredServer(
      String address,
      KeyManagerFactory keys,
      TrustManagerFactory trust,
      AccountGameplayAdmissionLeaseOwner owner,
      AccountOriginalNewBoundGameplayCredentialOwner originalNewCredentialOwner,
      String workloadNamespace) {
    return server(
        address,
        keys,
        trust,
        createUnregisteredReceiver(owner, originalNewCredentialOwner, workloadNamespace),
        workloadNamespace);
  }

  private static Server server(
      String address,
      KeyManagerFactory keys,
      TrustManagerFactory trust,
      AccountGameplayAdmissionLeaseGrpcService receiver,
      String workloadNamespace) {
    Objects.requireNonNull(address, "gRPC server address required");
    Objects.requireNonNull(keys, "gRPC server key manager required");
    Objects.requireNonNull(trust, "gRPC server trust manager required");
    int transportMaximum =
        Math.max(
            AccountGameplayAdmissionLeaseGrpcCodec.MAX_ACQUIRE_REQUEST_WIRE_BYTES,
            AccountGameplayAdmissionLeaseGrpcCodec.MAX_ORIGINAL_LEASE_REQUEST_WIRE_BYTES);
    var factory =
        new BoundedGrpcServerFactory(
            address,
            List.of(),
            keys,
            trust,
            TlsServerCredentials.ClientAuth.REQUIRE,
            GrpcRequestBytePolicy.ordinary(),
            transportMaximum);
    factory.addService(
        ServerInterceptors.interceptForward(
            receiver,
            new GrpcPeerIdentityInterceptor(),
            new AccountGameplayAdmissionLeaseWorkloadGuard(workloadNamespace)));
    return factory.createServer();
  }
}
