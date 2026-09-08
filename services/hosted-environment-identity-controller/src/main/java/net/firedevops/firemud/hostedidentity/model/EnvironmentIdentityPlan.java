package net.firedevops.firemud.hostedidentity.model;

import java.util.List;

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
}
