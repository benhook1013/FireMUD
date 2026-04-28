package net.firedevops.firemud.common.security;

import java.util.ArrayList;
import java.util.List;

public class GrpcAuthProperties {
  private boolean interceptorEnabled = true;
  private List<String> publicMethods = new ArrayList<>();

  public boolean isInterceptorEnabled() {
    return interceptorEnabled;
  }

  public void setInterceptorEnabled(boolean interceptorEnabled) {
    this.interceptorEnabled = interceptorEnabled;
  }

  public List<String> getPublicMethods() {
    return List.copyOf(publicMethods);
  }

  public void setPublicMethods(List<String> publicMethods) {
    this.publicMethods = publicMethods == null ? new ArrayList<>() : new ArrayList<>(publicMethods);
  }
}
