package net.firedevops.firemud.common.health;

import java.util.LinkedHashSet;
import java.util.Set;
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

  static final String READINESS_GROUP = "readiness";
  static final String TLS_CERTIFICATE_RELOAD_CONTRIBUTOR = "tlsCertificateReload";

  @Override
  public HealthEndpointGroups postProcessHealthEndpointGroups(HealthEndpointGroups groups) {
    HealthEndpointGroup readiness = groups.get(READINESS_GROUP);
    if (readiness == null || readiness.isMember(TLS_CERTIFICATE_RELOAD_CONTRIBUTOR)) {
      return groups;
    }
    return new DelegatingHealthEndpointGroups(groups, new TlsCertificateReadinessGroup(readiness));
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
    private final HealthEndpointGroup readiness;

    private DelegatingHealthEndpointGroups(
        HealthEndpointGroups delegate, HealthEndpointGroup readiness) {
      this.delegate = delegate;
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
      return group == delegate.get(READINESS_GROUP) ? readiness : group;
    }

    @Override
    public Set<HealthEndpointGroup> getAllWithAdditionalPath(WebServerNamespace namespace) {
      Set<HealthEndpointGroup> groups = delegate.getAllWithAdditionalPath(namespace);
      if (groups.isEmpty() || !groups.contains(delegate.get(READINESS_GROUP))) {
        return groups;
      }
      Set<HealthEndpointGroup> wrappedGroups = new LinkedHashSet<>();
      for (HealthEndpointGroup group : groups) {
        wrappedGroups.add(group == delegate.get(READINESS_GROUP) ? readiness : group);
      }
      return wrappedGroups;
    }
  }
}
