package net.firedevops.firemud.common.security;

import java.util.ArrayList;
import java.util.List;

public class HttpAuthProperties {
  private boolean enabled;
  private HttpAuthRoleRequirement roleRequirement = HttpAuthRoleRequirement.AUTHENTICATED;
  private List<String> includePathPatterns = new ArrayList<>(List.of("/**"));
  private List<String> publicPathPatterns = new ArrayList<>();

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
    return includePathPatterns;
  }

  public void setIncludePathPatterns(List<String> includePathPatterns) {
    this.includePathPatterns =
        includePathPatterns == null ? new ArrayList<>() : new ArrayList<>(includePathPatterns);
  }

  public List<String> getPublicPathPatterns() {
    return publicPathPatterns;
  }

  public void setPublicPathPatterns(List<String> publicPathPatterns) {
    this.publicPathPatterns =
        publicPathPatterns == null ? new ArrayList<>() : new ArrayList<>(publicPathPatterns);
  }
}
