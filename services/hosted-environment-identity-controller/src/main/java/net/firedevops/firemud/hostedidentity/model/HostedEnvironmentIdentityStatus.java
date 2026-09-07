package net.firedevops.firemud.hostedidentity.model;

import java.util.ArrayList;
import java.util.List;

public class HostedEnvironmentIdentityStatus {
  private Long observedGeneration;
  private Phase phase;
  private List<HostedCondition> conditions = new ArrayList<>();
  private RoleStatus ingress;
  private RoleStatus telnet;
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
    return conditions;
  }

  public void setConditions(List<HostedCondition> conditions) {
    this.conditions = conditions == null ? new ArrayList<>() : new ArrayList<>(conditions);
  }

  public RoleStatus getIngress() {
    return ingress;
  }

  public void setIngress(RoleStatus ingress) {
    this.ingress = ingress;
  }

  public RoleStatus getTelnet() {
    return telnet;
  }

  public void setTelnet(RoleStatus telnet) {
    this.telnet = telnet;
  }

  public RoleStatus getGrpc() {
    return grpc;
  }

  public void setGrpc(RoleStatus grpc) {
    this.grpc = grpc;
  }

  public RuntimeProfile getProfile() {
    return profile;
  }

  public void setProfile(RuntimeProfile profile) {
    this.profile = profile;
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

    public String getDeployedHeadSha() {
      return deployedHeadSha;
    }

    public void setDeployedHeadSha(String deployedHeadSha) {
      this.deployedHeadSha = deployedHeadSha;
    }
  }
}
