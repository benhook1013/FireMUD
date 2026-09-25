package net.firedevops.firemud.hostedidentity.model;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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

    Set<String> expectedGrpcPublicationRoles =
        HostedIdentityContract.GRPC_PUBLICATION_WORKLOADS.stream()
            .map(HostedIdentityContract::grpcPublicationRole)
            .collect(Collectors.toUnmodifiableSet());
    if (!grpcPublicationCertificateNames.keySet().equals(expectedGrpcPublicationRoles)
        || !grpcPublicationSecretNames.keySet().equals(expectedGrpcPublicationRoles)
        || !grpcPublicationSourceSecretNames.keySet().equals(expectedGrpcPublicationRoles)) {
      throw new IllegalArgumentException(
          "gRPC publication certificate, runtime Secret, and source Secret maps must contain exactly the supported roles");
    }
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
      String sourceSecretName = grpcPublicationSourceSecretNames.get(role);
      if (sourceSecretName == null) {
        throw new IllegalArgumentException(
            "missing source Secret for gRPC publication role: " + role);
      }
      return sourceSecretName;
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
