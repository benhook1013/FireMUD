package net.firedevops.firemud.hostedidentity.contract;

import java.util.Map;

/** Stable names and labels shared by the controller, manifests, and hosted workflows. */
public final class HostedIdentityContract {
  public static final String API_GROUP = "platform.firemud.dev";
  public static final String API_VERSION = "v1alpha1";
  public static final String API_VERSION_NAME = API_GROUP + "/" + API_VERSION;
  public static final String KIND = "HostedEnvironmentIdentity";
  public static final String PLURAL = "hostedenvironmentidentities";
  public static final String FINALIZER = API_GROUP + "/hosted-environment-identity";

  public static final String MANAGED_BY_LABEL = "firemud.dev/managed-by";
  public static final String ENVIRONMENT_LABEL = "firemud.dev/identity-name";
  public static final String ROLE_LABEL = "firemud.dev/role";
  public static final String RUNTIME_FOR_LABEL = "firemud.dev/runtime-for";
  public static final String RETENTION_LABEL = "firemud.dev/retention";
  public static final String RETAINED = "retained";
  public static final String CONTROLLER_NAME = "hosted-identity-controller";

  public static final String REVISION_ANNOTATION = "firemud.dev/revision";
  public static final String SOURCE_GENERATION_ANNOTATION = "firemud.dev/source-generation";
  public static final String SOURCE_OBJECT_GENERATION_ANNOTATION =
      "firemud.dev/source-object-generation";
  public static final String SPKI_SHA256_ANNOTATION = "firemud.dev/spki-sha256";
  public static final String PROVENANCE_ANNOTATION = "firemud.dev/provenance";
  public static final String DIGEST_ANNOTATION = "firemud.dev/digest";
  public static final String ACCEPTED_REVISION_ANNOTATION = "firemud.dev/accepted-revision";
  public static final String ACCEPTED_SOURCE_GENERATION_ANNOTATION =
      "firemud.dev/accepted-source-generation";
  public static final String ACCEPTED_SOURCE_OBJECT_GENERATION_ANNOTATION =
      "firemud.dev/accepted-source-object-generation";
  public static final String ACCEPTED_SPKI_SHA256_ANNOTATION = "firemud.dev/accepted-spki-sha256";
  public static final String ISSUANCE_GENERATION_ANNOTATION = "firemud.dev/issuance-generation";
  public static final String CONVERGENCE_STATE_ANNOTATION = "firemud.dev/convergence-state";
  public static final String TELNET_REVISION_ANNOTATION = "firemud.dev/telnet-revision";
  public static final String GRPC_REVISION_ANNOTATION = "firemud.dev/grpc-revision";

  public static final String INGRESS_ROLE = "ingress";
  public static final String TELNET_ROLE = "telnet";
  public static final String GRPC_ROLE = "grpc";
  public static final String TRANSPORT_PROVENANCE = "hosted-identity-controller-transport-only";

  private HostedIdentityContract() {}

  public static Map<String, String> managedLabels(String environment, String role) {
    return Map.of(
        MANAGED_BY_LABEL, CONTROLLER_NAME,
        ENVIRONMENT_LABEL, environment,
        ROLE_LABEL, role,
        RETENTION_LABEL, RETAINED);
  }
}
