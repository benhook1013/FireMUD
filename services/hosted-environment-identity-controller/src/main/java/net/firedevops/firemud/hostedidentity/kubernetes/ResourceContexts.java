package net.firedevops.firemud.hostedidentity.kubernetes;

import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;

public final class ResourceContexts {
  public static final ResourceDefinitionContext CERTIFICATES =
      new ResourceDefinitionContext.Builder()
          .withGroup("cert-manager.io")
          .withVersion("v1")
          .withPlural("certificates")
          .withNamespaced(true)
          .build();

  public static final ResourceDefinitionContext CERTIFICATE_REQUESTS =
      new ResourceDefinitionContext.Builder()
          .withGroup("cert-manager.io")
          .withVersion("v1")
          .withPlural("certificaterequests")
          .withNamespaced(true)
          .build();

  private ResourceContexts() {}
}
