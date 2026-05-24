package net.firedevops.firemud.common.security;

import java.util.ArrayList;
import java.util.List;

public class HttpAuthProperties {
  private boolean enabled;
  private HttpAuthRoleRequirement roleRequirement = HttpAuthRoleRequirement.AUTHENTICATED;
  private List<String> includePathPatterns = new ArrayList<>(List.of("/**"));
  private List<HttpPublicRoute> publicRoutes = new ArrayList<>();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public HttpAuthRoleRequirement getRoleRequirement() {
    return roleRequirement;
  }

  public void setRoleRequirement(HttpAuthRoleRequirement roleRequirement) {
    this.roleRequirement = roleRequirement;
  }

  public List<String> getIncludePathPatterns() {
    return List.copyOf(includePathPatterns);
  }

  public void setIncludePathPatterns(List<String> includePathPatterns) {
    this.includePathPatterns =
        includePathPatterns == null ? new ArrayList<>() : new ArrayList<>(includePathPatterns);
  }

  public List<HttpPublicRoute> getPublicRoutes() {
    return List.copyOf(publicRoutes);
  }

  public void setPublicRoutes(List<HttpPublicRoute> publicRoutes) {
    this.publicRoutes = publicRoutes == null ? new ArrayList<>() : new ArrayList<>(publicRoutes);
  }

  public static class HttpPublicRoute {
    private String method;
    private String pathPattern;

    public String getMethod() {
      return method;
    }

    public void setMethod(String method) {
      this.method = method;
    }

    public String getPathPattern() {
      return pathPattern;
    }

    public void setPathPattern(String pathPattern) {
      this.pathPattern = pathPattern;
    }
  }
}
