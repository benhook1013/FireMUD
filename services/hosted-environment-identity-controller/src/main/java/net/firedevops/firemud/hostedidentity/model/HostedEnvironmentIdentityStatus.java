package net.firedevops.firemud.hostedidentity.model;

import java.util.ArrayList;
import java.util.List;

public class HostedEnvironmentIdentityStatus {
  private Long observedGeneration;
  private Phase phase;
  private List<HostedCondition> conditions = new ArrayList<>();
  private RoleStatus ingress;
  private RoleStatus telnet;
  private RoleStatus gatewayInternalWs;
  private RoleStatus tcpProxyBridge;
  private RoleStatus grpc;
  private RuntimeProfile profile;

  public Long getObservedGeneration() {
    return observedGeneration;
  }

  public void setObservedGeneration(Long observedGeneration) {
    this.observedGeneration = observedGeneration;
  }

  public Phase getPhase() {
    return phase;
  }

  public void setPhase(Phase phase) {
    this.phase = phase;
  }

  public List<HostedCondition> getConditions() {
    return conditions.stream().map(HostedEnvironmentIdentityStatus::copyCondition).toList();
  }

  public void setConditions(List<HostedCondition> conditions) {
    this.conditions =
        conditions == null
            ? new ArrayList<>()
            : conditions.stream()
                .map(HostedEnvironmentIdentityStatus::copyCondition)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
  }

  public RoleStatus getIngress() {
    return copyRole(ingress);
  }

  public void setIngress(RoleStatus ingress) {
    this.ingress = copyRole(ingress);
  }

  public RoleStatus getTelnet() {
    return copyRole(telnet);
  }

  public void setTelnet(RoleStatus telnet) {
    this.telnet = copyRole(telnet);
  }

  public RoleStatus getGatewayInternalWs() {
    return copyRole(gatewayInternalWs);
  }

  public void setGatewayInternalWs(RoleStatus gatewayInternalWs) {
    this.gatewayInternalWs = copyRole(gatewayInternalWs);
  }

  public RoleStatus getTcpProxyBridge() {
    return copyRole(tcpProxyBridge);
  }

  public void setTcpProxyBridge(RoleStatus tcpProxyBridge) {
    this.tcpProxyBridge = copyRole(tcpProxyBridge);
  }

  public RoleStatus getGrpc() {
    return copyRole(grpc);
  }

  public void setGrpc(RoleStatus grpc) {
    this.grpc = copyRole(grpc);
  }

  public RuntimeProfile getProfile() {
    return copyProfile(profile);
  }

  public void setProfile(RuntimeProfile profile) {
    this.profile = copyProfile(profile);
  }

  private static HostedCondition copyCondition(HostedCondition source) {
    if (source == null) {
      return null;
    }
    HostedCondition copy =
        new HostedCondition(
            source.getType(), source.getStatus(), source.getReason(), source.getMessage());
    copy.setLastTransitionTime(source.getLastTransitionTime());
    copy.setObservedGeneration(source.getObservedGeneration());
    return copy;
  }

  private static RoleStatus copyRole(RoleStatus source) {
    if (source == null) {
      return null;
    }
    RoleStatus copy = new RoleStatus();
    copy.setRevision(source.getRevision());
    copy.setSourceGeneration(source.getSourceGeneration());
    copy.setSourceObjectGeneration(source.getSourceObjectGeneration());
    copy.setSpkiSha256(source.getSpkiSha256());
    copy.setProvenance(source.getProvenance());
    copy.setState(source.getState());
    return copy;
  }

  private static RuntimeProfile copyProfile(RuntimeProfile source) {
    if (source == null) {
      return null;
    }
    RuntimeProfile copy = new RuntimeProfile();
    copy.setName(source.getName());
    copy.setEnvironmentClass(source.getEnvironmentClass());
    copy.setIdentityNamespace(source.getIdentityNamespace());
    copy.setRuntimeNamespace(source.getRuntimeNamespace());
    copy.setHostname(source.getHostname());
    copy.setTelnetPort(source.getTelnetPort());
    copy.setRuntimeNamespaceUid(source.getRuntimeNamespaceUid());
    copy.setRequestedHeadSha(source.getRequestedHeadSha());
    copy.setDeployedHeadSha(source.getDeployedHeadSha());
    return copy;
  }

  public enum Phase {
    Pending,
    Provisioning,
    WaitingForCertificate,
    Syncing,
    Verifying,
    Ready,
    Degraded,
    RuntimeAbsent,
    Blocked,
    Retiring,
    Retired
  }

  public static class RoleStatus {
    private String revision;
    private Long sourceGeneration;
    private Long sourceObjectGeneration;
    private String spkiSha256;
    private String provenance;
    private String state;

    public String getRevision() {
      return revision;
    }

    public void setRevision(String revision) {
      this.revision = revision;
    }

    public Long getSourceGeneration() {
      return sourceGeneration;
    }

    public void setSourceGeneration(Long sourceGeneration) {
      this.sourceGeneration = sourceGeneration;
    }

    public Long getSourceObjectGeneration() {
      return sourceObjectGeneration;
    }

    public void setSourceObjectGeneration(Long sourceObjectGeneration) {
      this.sourceObjectGeneration = sourceObjectGeneration;
    }

    public String getSpkiSha256() {
      return spkiSha256;
    }

    public void setSpkiSha256(String spkiSha256) {
      this.spkiSha256 = spkiSha256;
    }

    public String getProvenance() {
      return provenance;
    }

    public void setProvenance(String provenance) {
      this.provenance = provenance;
    }

    public String getState() {
      return state;
    }

    public void setState(String state) {
      this.state = state;
    }
  }

  public static class RuntimeProfile {
    private String name;
    private String environmentClass;
    private String identityNamespace;
    private String runtimeNamespace;
    private String hostname;
    private Integer telnetPort;
    private String runtimeNamespaceUid;
    private String requestedHeadSha;
    private String deployedHeadSha;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getEnvironmentClass() {
      return environmentClass;
    }

    public void setEnvironmentClass(String environmentClass) {
      this.environmentClass = environmentClass;
    }

    public String getIdentityNamespace() {
      return identityNamespace;
    }

    public void setIdentityNamespace(String identityNamespace) {
      this.identityNamespace = identityNamespace;
    }

    public String getRuntimeNamespace() {
      return runtimeNamespace;
    }

    public void setRuntimeNamespace(String runtimeNamespace) {
      this.runtimeNamespace = runtimeNamespace;
    }

    public String getHostname() {
      return hostname;
    }

    public void setHostname(String hostname) {
      this.hostname = hostname;
    }

    public Integer getTelnetPort() {
      return telnetPort;
    }

    public void setTelnetPort(Integer telnetPort) {
      this.telnetPort = telnetPort;
    }

    public String getRuntimeNamespaceUid() {
      return runtimeNamespaceUid;
    }

    public void setRuntimeNamespaceUid(String runtimeNamespaceUid) {
      this.runtimeNamespaceUid = runtimeNamespaceUid;
    }

    public String getRequestedHeadSha() {
      return requestedHeadSha;
    }

    public void setRequestedHeadSha(String requestedHeadSha) {
      this.requestedHeadSha = requestedHeadSha;
    }

    public String getDeployedHeadSha() {
      return deployedHeadSha;
    }

    public void setDeployedHeadSha(String deployedHeadSha) {
      this.deployedHeadSha = deployedHeadSha;
    }
  }
}
