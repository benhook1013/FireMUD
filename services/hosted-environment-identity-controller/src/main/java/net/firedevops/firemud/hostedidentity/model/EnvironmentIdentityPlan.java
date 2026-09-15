package net.firedevops.firemud.hostedidentity.model;

import java.util.List;
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
    List<String> grpcConsumers) {
  public EnvironmentIdentityPlan {
    grpcConsumers = List.copyOf(grpcConsumers);
  }

  public String secretName(String role) {
    return switch (role) {
      case HostedIdentityContract.INGRESS_ROLE -> ingressSecretName;
      case HostedIdentityContract.TELNET_ROLE -> telnetSecretName;
      case HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE -> gatewayInternalWsSecretName;
      case HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE -> tcpProxyBridgeSecretName;
      case HostedIdentityContract.GRPC_ROLE -> grpcSecretName;
      default -> throw new IllegalArgumentException("unsupported identity role: " + role);
    };
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
        consumers);
  }
}
