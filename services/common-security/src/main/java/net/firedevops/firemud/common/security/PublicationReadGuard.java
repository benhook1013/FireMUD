package net.firedevops.firemud.common.security;

import java.util.Set;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/**
 * Authorization guard for the four owner-to-owner publication digest reads.
 *
 * <p>The guard is intentionally explicit about the method set. It must be called by the four
 * corresponding handlers, rather than installed as a blanket rejection for every gRPC method. The
 * peer certificate is the workload authority for these workload-only reads.
 */
public final class PublicationReadGuard {
  public static final String WORLD_MANAGEMENT_DIGEST_METHOD =
      "world_management.v1.WorldManagementService/GetDraftDesignDigest";
  public static final String ENTITY_MANAGEMENT_DIGEST_METHOD =
      "entity_management.v1.EntityManagementService/GetDraftDesignDigest";
  public static final String GAME_LOGIC_DIGEST_METHOD =
      "game_logic.v1.GameLogicService/GetDraftDesignDigest";
  public static final String AUTOMATION_SCRIPTING_DIGEST_METHOD =
      "automation_scripting.v1.AutomationScriptingService/GetDraftDesignDigest";

  public static final Set<String> PUBLICATION_READ_METHODS =
      Set.of(
          WORLD_MANAGEMENT_DIGEST_METHOD,
          ENTITY_MANAGEMENT_DIGEST_METHOD,
          GAME_LOGIC_DIGEST_METHOD,
          AUTOMATION_SCRIPTING_DIGEST_METHOD);

  private static final String GAME_DESIGN_SERVICE = "game-design-service";
  private final String trustedNamespace;

  public PublicationReadGuard(String trustedNamespace) {
    if (!GrpcPeerIdentity.isValidNamespace(trustedNamespace)) {
      throw new IllegalArgumentException("A valid trusted workload namespace is required");
    }
    this.trustedNamespace = trustedNamespace;
  }

  /** Returns the namespace against which peer identities are compared. */
  public String trustedNamespace() {
    return trustedNamespace;
  }

  /** Returns whether a full gRPC method name is one of the four protected publication reads. */
  public boolean protects(String fullMethodName) {
    return PUBLICATION_READ_METHODS.contains(fullMethodName);
  }

  /**
   * Enforces publication-read authorization for an exact method name.
   *
   * <p>Unrelated methods are deliberately untouched; only the four named publication reads invoke
   * this authorization boundary.
   */
  public void requirePublicationRead(String fullMethodName) {
    if (protects(fullMethodName)) {
      requireGameDesignWorkload();
    }
  }

  /** Enforces the caller predicate for a handler already known to be a publication read. */
  public void requireGameDesignWorkload() {
    GrpcPeerIdentity peerIdentity = GrpcPeerIdentity.current();
    if (peerIdentity == null
        || !peerIdentity.isService(GAME_DESIGN_SERVICE)
        || !peerIdentity.isInNamespace(trustedNamespace)) {
      throw denied();
    }
  }

  private AdminAuthorizationException denied() {
    return new AdminAuthorizationException(
        "Publication read requires the authenticated Game Design workload peer identity");
  }
}
