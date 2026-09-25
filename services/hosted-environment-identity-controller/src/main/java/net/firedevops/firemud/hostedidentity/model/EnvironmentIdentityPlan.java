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
    Map<String, String> grpcPublicationCertificateNames,
    Map<String, String> grpcPublicationSecretNames,
    Map<String, String> grpcPublicationSourceSecretNames) {
  public EnvironmentIdentityPlan {
    grpcConsumers = List.copyOf(grpcConsumers);
    grpcPublicationCertificateNames = Map.copyOf(grpcPublicationCertificateNames);
    grpcPublicationSecretNames = Map.copyOf(grpcPublicationSecretNames);
    grpcPublicationSourceSecretNames = Map.copyOf(grpcPublicationSourceSecretNames);
  }

  public String secretName(String role) {
    return switch (role) {
      case HostedIdentityContract.INGRESS_ROLE -> ingressSecretName;
      case HostedIdentityContract.TELNET_ROLE -> telnetSecretName;
      case HostedIdentityContract.GATEWAY_INTERNAL_WS_ROLE -> gatewayInternalWsSecretName;
      case HostedIdentityContract.TCP_PROXY_BRIDGE_ROLE -> tcpProxyBridgeSecretName;
      case HostedIdentityContract.GRPC_ROLE -> grpcSecretName;
      default -> {
        if (HostedIdentityContract.isGrpcPublicationRole(role)) {
          String secretName = grpcPublicationSecretNames.get(role);
          if (secretName != null) {
            yield secretName;
          }
        }
        throw new IllegalArgumentException("unsupported identity role: " + role);
      }
    };
  }

  public String sourceSecretName(String role) {
    if (HostedIdentityContract.isGrpcPublicationRole(role)) {
      return grpcPublicationSourceSecretNames.get(role);
    }
    return secretName(role);
  }

  public String grpcPublicationCertificateName(String workload) {
    return grpcPublicationCertificateNames.get(
        HostedIdentityContract.grpcPublicationRole(workload));
  }

  public String grpcPublicationSecretName(String workload) {
    return grpcPublicationSecretNames.get(HostedIdentityContract.grpcPublicationRole(workload));
  }

  public String grpcPublicationSourceSecretName(String workload) {
    return grpcPublicationSourceSecretNames.get(
        HostedIdentityContract.grpcPublicationRole(workload));
  }

  public String grpcPublicationUriSan(String workload) {
    return "spiffe://firemud/ns/" + runtimeNamespace + "/sa/" + workload;
  }

  public List<String> grpcPublicationDnsNames(String workload) {
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
        grpcPublicationCertificateNames,
        grpcPublicationSecretNames,
        grpcPublicationSourceSecretNames);
  }
}
