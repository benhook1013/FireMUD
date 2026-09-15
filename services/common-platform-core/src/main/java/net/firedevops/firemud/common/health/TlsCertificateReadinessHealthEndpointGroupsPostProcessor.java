package net.firedevops.firemud.common.health;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import net.firedevops.firemud.common.LoggingUtil;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.boot.health.actuate.endpoint.AdditionalHealthEndpointPath;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroupsPostProcessor;
import org.springframework.boot.health.actuate.endpoint.HttpCodeStatusMapper;
import org.springframework.boot.health.actuate.endpoint.StatusAggregator;

/** Ensures TLS certificate reload health gates the actuator readiness group. */
public final class TlsCertificateReadinessHealthEndpointGroupsPostProcessor
    implements HealthEndpointGroupsPostProcessor {

  private static final org.slf4j.Logger logger =
      LoggingUtil.getLogger(TlsCertificateReadinessHealthEndpointGroupsPostProcessor.class);
  static final String TCP_PROXY_SERVICE_NAME = "tcp-proxy-service";
  static final String READINESS_GROUP = "readiness";
  public static final String TLS_CERTIFICATE_RELOAD_CONTRIBUTOR = "tlsCertificateReload";

  private final String serviceName;

  public TlsCertificateReadinessHealthEndpointGroupsPostProcessor(String serviceName) {
    this.serviceName = Objects.requireNonNull(serviceName, "serviceName");
  }

  @Override
  public HealthEndpointGroups postProcessHealthEndpointGroups(HealthEndpointGroups groups) {
    if (TCP_PROXY_SERVICE_NAME.equals(serviceName)) {
      return groups;
    }
    HealthEndpointGroup readiness = groups.get(READINESS_GROUP);
    if (readiness == null) {
      logger.warn("Actuator readiness health group is absent; TLS certificate reload is not gated");
      return groups;
    }
    if (readiness.isMember(TLS_CERTIFICATE_RELOAD_CONTRIBUTOR)) {
      return groups;
    }
    return new DelegatingHealthEndpointGroups(
        groups, readiness, new TlsCertificateReadinessGroup(readiness));
  }

  private static final class TlsCertificateReadinessGroup implements HealthEndpointGroup {
    private final HealthEndpointGroup delegate;

    private TlsCertificateReadinessGroup(HealthEndpointGroup delegate) {
      this.delegate = delegate;
    }

    @Override
    public boolean isMember(String name) {
      return TLS_CERTIFICATE_RELOAD_CONTRIBUTOR.equals(name) || delegate.isMember(name);
    }

    @Override
    public boolean showComponents(SecurityContext securityContext) {
      return delegate.showComponents(securityContext);
    }

    @Override
    public boolean showDetails(SecurityContext securityContext) {
      return delegate.showDetails(securityContext);
    }

    @Override
    public StatusAggregator getStatusAggregator() {
      return delegate.getStatusAggregator();
    }

    @Override
    public HttpCodeStatusMapper getHttpCodeStatusMapper() {
      return delegate.getHttpCodeStatusMapper();
    }

    @Override
    public AdditionalHealthEndpointPath getAdditionalPath() {
      return delegate.getAdditionalPath();
    }
  }

  private static final class DelegatingHealthEndpointGroups implements HealthEndpointGroups {
    private final HealthEndpointGroups delegate;
    private final HealthEndpointGroup originalReadiness;
    private final HealthEndpointGroup readiness;

    private DelegatingHealthEndpointGroups(
        HealthEndpointGroups delegate,
        HealthEndpointGroup originalReadiness,
        HealthEndpointGroup readiness) {
      this.delegate = delegate;
      this.originalReadiness = originalReadiness;
      this.readiness = readiness;
    }

    @Override
    public HealthEndpointGroup getPrimary() {
      return delegate.getPrimary();
    }

    @Override
    public Set<String> getNames() {
      return delegate.getNames();
    }

    @Override
    public HealthEndpointGroup get(String name) {
      if (READINESS_GROUP.equals(name)) {
        return readiness;
      }
      return delegate.get(name);
    }

    @Override
    public HealthEndpointGroup get(AdditionalHealthEndpointPath path) {
      HealthEndpointGroup group = delegate.get(path);
      return group == originalReadiness ? readiness : group;
    }

    @Override
    public Set<HealthEndpointGroup> getAllWithAdditionalPath(WebServerNamespace namespace) {
      Set<HealthEndpointGroup> groups = delegate.getAllWithAdditionalPath(namespace);
      if (groups.isEmpty() || !groups.contains(originalReadiness)) {
        return groups;
      }
      Set<HealthEndpointGroup> wrappedGroups = new LinkedHashSet<>();
      for (HealthEndpointGroup group : groups) {
        wrappedGroups.add(group == originalReadiness ? readiness : group);
      }
      return wrappedGroups;
    }
  }
}
