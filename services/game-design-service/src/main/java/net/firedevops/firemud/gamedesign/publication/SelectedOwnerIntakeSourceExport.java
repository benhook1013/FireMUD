package net.firedevops.firemud.gamedesign.publication;

import java.io.ByteArrayOutputStream;
import java.util.Objects;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadScope;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;

/**
 * Complete selected six-family Game Design source content for a distinct owner intake read.
 *
 * <p>This value proves only that the supplied immutable snapshots are complete and bound to the
 * exact selected Draft scope. Constructing it does not authenticate a caller, establish an Account
 * reservation or finalization, or prove owner emptiness, owner receipt, or retention authority.
 */
public final class SelectedOwnerIntakeSourceExport {
  public static final String DOMAIN = "game-design-selected-owner-intake-source/v1";
  public static final int MAX_BYTES = 8 * 1024 * 1024;

  private final SelectedOwnerIntakeSourceReadScope scope;
  private final GameDesignSourceRepository.SynchronizedSources sources;
  private final byte[] canonicalBytes;
  private final String digest;

  private SelectedOwnerIntakeSourceExport(
      SelectedOwnerIntakeSourceReadScope scope,
      GameDesignSourceRepository.SynchronizedSources sources,
      byte[] canonicalBytes) {
    this.scope = scope;
    this.sources = sources;
    this.canonicalBytes = canonicalBytes.clone();
    this.digest = DraftAuthorizationFenceBinding.digest(this.canonicalBytes);
  }

  public static SelectedOwnerIntakeSourceExport create(
      SelectedOwnerIntakeSourceReadScope scope,
      GameDesignSourceRepository.SynchronizedSources sources) {
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(sources, "sources");

    DraftCommitBinding selected = scope.selected();
    requireSelected(selected, sources.command().binding(), "COMMAND");
    requireSelected(selected, sources.policy().binding(), "REALM_POLICY");
    requireSelected(selected, sources.asset().binding(), "ASSET");
    requireSelected(selected, sources.gameplay().binding(), "GAMEPLAY_RULE");
    var branding =
        sources
            .branding()
            .orElseThrow(() -> new IllegalStateException("Selected branding source is incomplete"));
    var templateConfig =
        sources
            .templateConfig()
            .orElseThrow(
                () -> new IllegalStateException("Selected template-config source is incomplete"));
    requireSelected(selected, branding.binding(), "BRANDING");
    requireSelected(selected, templateConfig.binding(), "TEMPLATE_CONFIG");

    var out = new ByteArrayOutputStream();
    frame(out, DOMAIN);
    frame(out, scope.canonicalBytes());
    frame(out, scope.digest());
    addFamily(out, "COMMAND", sources.command().canonicalBytes());
    addFamily(out, "REALM_POLICY", sources.policy().canonicalBytes());
    addFamily(out, "ASSET", sources.asset().canonicalBytes());
    addFamily(out, "GAMEPLAY_RULE", sources.gameplay().canonicalBytes());
    addFamily(out, "BRANDING", branding.canonicalBytes());
    addFamily(out, "TEMPLATE_CONFIG", templateConfig.canonicalBytes());
    return new SelectedOwnerIntakeSourceExport(scope, sources, out.toByteArray());
  }

  public SelectedOwnerIntakeSourceReadScope scope() {
    return scope;
  }

  /** The immutable family snapshot values used to construct this export. */
  public GameDesignSourceRepository.SynchronizedSources sources() {
    return sources;
  }

  public byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  public String digest() {
    return digest;
  }

  private static void addFamily(ByteArrayOutputStream out, String family, byte[] bytes) {
    frame(out, family);
    frame(out, bytes);
    frame(out, DraftAuthorizationFenceBinding.digest(bytes));
  }

  private static void frame(ByteArrayOutputStream out, byte[] bytes) {
    DraftAuthorizationFenceBinding.frame(out, bytes);
    requireWithinLimit(out);
  }

  private static void frame(ByteArrayOutputStream out, String value) {
    DraftAuthorizationFenceBinding.frame(out, value);
    requireWithinLimit(out);
  }

  private static void requireWithinLimit(ByteArrayOutputStream out) {
    if (out.size() > MAX_BYTES) {
      throw new IllegalArgumentException("Selected owner intake source exceeds 8 MiB");
    }
  }

  private static void requireSelected(
      DraftCommitBinding selected, DraftCommitBinding family, String familyName) {
    if (!selected.equals(family)) {
      throw new IllegalStateException(
          "Selected " + familyName + " source differs from the original Draft binding");
    }
  }
}
