package net.firedevops.firemud.hostedidentity.model;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.hostedidentity.contract.HostedIdentityContract;

public record EnvironmentIdentityPlan(
    String name,
    String controlNamespace,
    String identityNamespace,
    String runtimeNamespace,
    String hostname,
    String ingressCertificateName,
    String ingressSecretName,
    String telnetCertificateName,
    String telnetSecretName,
    String gatewayInternalWsCertificateName,
    String gatewayInternalWsSecretName,
    String gatewayInternalWsDnsName,
    String tcpProxyBridgeCertificateName,
    String tcpProxyBridgeSecretName,
    String tcpProxyBridgeUriSan,
    String grpcCertificateName,
    String grpcSecretName,
    String ingressIssuer,
    String telnetIssuer,
    String grpcIssuer,
    String caSecretName,
    List<String> grpcConsumers,
    Map<String, String> grpcWorkloadIdentityCertificateNames,
    Map<String, String> grpcWorkloadIdentitySecretNames,
    Map<String, String> grpcWorkloadIdentitySourceSecretNames) {
  public EnvironmentIdentityPlan {
    grpcConsumers = List.copyOf(grpcConsumers);
    grpcWorkloadIdentityCertificateNames = Map.copyOf(grpcWorkloadIdentityCertificateNames);
    grpcWorkloadIdentitySecretNames = Map.copyOf(grpcWorkloadIdentitySecretNames);
    grpcWorkloadIdentitySourceSecretNames = Map.copyOf(grpcWorkloadIdentitySourceSecretNames);
  }

  public String secretName(String role) {
    return switch (role) {
      case HostedIdentityContract.INGRESS_ROLE -> ingressSecretName;
      case HostedIdentityContract.TELNET_ROLE -> telnetSecretName;
      case HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE -> gatewayInternalWsSecretName;
      case HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE -> tcpProxyBridgeSecretName;
      case HostedIdentityContract.GRPC_ROLE -> grpcSecretName;
      default -> {
        if (HostedIdentityContract.isGrpcWorkloadIdentityRole(role)) {
          String secretName = grpcWorkloadIdentitySecretNames.get(role);
          if (secretName != null) {
            yield secretName;
          }
        }
        throw new IllegalArgumentException("unsupported identity role: " + role);
      }
    };
  }

  public String grpcPublicationCertificateName(String workload) {
    return grpcWorkloadIdentityCertificateNames.get(
        HostedIdentityContract.grpcPublicationRole(workload));
  }

  public String grpcPublicationSecretName(String workload) {
    return grpcWorkloadIdentitySecretNames.get(
        HostedIdentityContract.grpcPublicationRole(workload));
  }

  public String grpcPublicationSourceSecretName(String workload) {
    return grpcWorkloadIdentitySourceSecretNames.get(
        HostedIdentityContract.grpcPublicationRole(workload));
  }

  public String grpcAccountCertificateName() {
    return grpcWorkloadIdentityCertificateNames.get(HostedIdentityContract.GRPC_ACCOUNT_ROLE);
  }

  public String grpcAccountSecretName() {
    return grpcWorkloadIdentitySecretNames.get(HostedIdentityContract.GRPC_ACCOUNT_ROLE);
  }

  public String grpcAccountSourceSecretName() {
    return grpcWorkloadIdentitySourceSecretNames.get(HostedIdentityContract.GRPC_ACCOUNT_ROLE);
  }

  public String grpcGameSessionCertificateName() {
    return grpcWorkloadIdentityCertificateNames.get(HostedIdentityContract.GRPC_GAME_SESSION_ROLE);
  }

  public String grpcGameSessionSecretName() {
    return grpcWorkloadIdentitySecretNames.get(HostedIdentityContract.GRPC_GAME_SESSION_ROLE);
  }

  public String grpcGameSessionSourceSecretName() {
    return grpcWorkloadIdentitySourceSecretNames.get(HostedIdentityContract.GRPC_GAME_SESSION_ROLE);
  }

  public String grpcPublicationUriSan(String workload) {
    return grpcWorkloadIdentityUriSan(workload);
  }

  public List<String> grpcPublicationDnsNames(String workload) {
    return grpcWorkloadIdentityDnsNames(workload);
  }

  public String grpcWorkloadIdentityUriSan(String workload) {
    return "spiffe://firemud/ns/" + runtimeNamespace + "/sa/" + workload;
  }

  public List<String> grpcWorkloadIdentityDnsNames(String workload) {
    return List.of(
        workload,
        workload + "." + runtimeNamespace,
        workload + "." + runtimeNamespace + ".svc",
        workload + "." + runtimeNamespace + ".svc.cluster.local");
  }

  public EnvironmentIdentityPlan withGrpcConsumers(List<String> consumers) {
    return new EnvironmentIdentityPlan(
        name,
        controlNamespace,
        identityNamespace,
        runtimeNamespace,
        hostname,
        ingressCertificateName,
        ingressSecretName,
        telnetCertificateName,
        telnetSecretName,
        gatewayInternalWsCertificateName,
        gatewayInternalWsSecretName,
        gatewayInternalWsDnsName,
        tcpProxyBridgeCertificateName,
        tcpProxyBridgeSecretName,
        tcpProxyBridgeUriSan,
        grpcCertificateName,
        grpcSecretName,
        ingressIssuer,
        telnetIssuer,
        grpcIssuer,
        caSecretName,
        consumers,
        grpcWorkloadIdentityCertificateNames,
        grpcWorkloadIdentitySecretNames,
        grpcWorkloadIdentitySourceSecretNames);
  }
}
