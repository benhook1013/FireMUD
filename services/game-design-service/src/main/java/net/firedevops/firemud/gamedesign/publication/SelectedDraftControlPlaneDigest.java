package net.firedevops.firemud.gamedesign.publication;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.gamedesign.dto.DesignControlPlaneDigestDto;

/** Schema-2 digest over every exact Game Design source family at one selected Draft commit. */
public record SelectedDraftControlPlaneDigest(
    String tenantId,
    String scopeValue,
    String appliedCommitId,
    String contentDigest,
    int digestSchemaVersion) {
  public static final int SCHEMA_VERSION = 2;
  private static final String DOMAIN = "game-design-selected-draft-control-plane-digest/v2";

  public SelectedDraftControlPlaneDigest {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(scopeValue, "scopeValue");
    Objects.requireNonNull(appliedCommitId, "appliedCommitId");
    if (contentDigest == null
        || !contentDigest.matches("[0-9a-f]{64}")
        || digestSchemaVersion != SCHEMA_VERSION) {
      throw new IllegalArgumentException(
          "Canonical schema-2 selected control-plane digest required");
    }
  }

  /** Publication request identity is echoed separately and is not part of this content digest. */
  public static SelectedDraftControlPlaneDigest compute(
      DraftCommitBinding selectedCommit, GameDesignSourceRepository.SynchronizedSources sources) {
    Objects.requireNonNull(selectedCommit, "selectedCommit");
    Objects.requireNonNull(sources, "sources");
    BrandingSourceSnapshot branding =
        sources
            .branding()
            .orElseThrow(
                () -> new IllegalStateException("Selected branding source snapshot unavailable"));
    TemplateConfigSourceSnapshot templateConfig =
        sources
            .templateConfig()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Selected template-config source snapshot unavailable"));
    List<Snapshot> snapshots =
        List.of(
            new Snapshot(
                "COMMAND", sources.command().binding(), sources.command().canonicalBytes()),
            new Snapshot(
                "REALM_POLICY", sources.policy().binding(), sources.policy().canonicalBytes()),
            new Snapshot("ASSET", sources.asset().binding(), sources.asset().canonicalBytes()),
            new Snapshot(
                "GAMEPLAY_SOURCE",
                sources.gameplay().binding(),
                sources.gameplay().canonicalBytes()),
            new Snapshot("BRANDING", branding.binding(), branding.canonicalBytes()),
            new Snapshot(
                "TEMPLATE_CONFIG", templateConfig.binding(), templateConfig.canonicalBytes()));

    var preimage = new ByteArrayOutputStream();
    frame(preimage, DOMAIN);
    frame(preimage, selectedCommit.canonicalBytes());
    for (Snapshot source : snapshots) {
      if (!selectedCommit.equals(source.binding())) {
        throw new IllegalStateException(
            "Every selected Game Design source family must bind the exact Draft commit");
      }
      frame(preimage, source.family());
      frame(preimage, source.canonicalBytes());
    }
    return new SelectedDraftControlPlaneDigest(
        selectedCommit.target().canonicalTenantId().toString(),
        Long.toString(selectedCommit.target().gameDesignVersionRowId()),
        selectedCommit.commitId().toString(),
        sha256(preimage.toByteArray()),
        SCHEMA_VERSION);
  }

  public DesignControlPlaneDigestDto toDto() {
    return new DesignControlPlaneDigestDto(
        tenantId, scopeValue, appliedCommitId, contentDigest, digestSchemaVersion);
  }

  private static void frame(ByteArrayOutputStream output, String value) {
    frame(output, value.getBytes(StandardCharsets.UTF_8));
  }

  private static void frame(ByteArrayOutputStream output, byte[] value) {
    output.writeBytes(Integer.toString(value.length).getBytes(StandardCharsets.US_ASCII));
    output.write(':');
    output.writeBytes(value);
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError("SHA-256 is required by the Java platform", impossible);
    }
  }

  private record Snapshot(String family, DraftCommitBinding binding, byte[] canonicalBytes) {
    private Snapshot {
      Objects.requireNonNull(family, "family");
      Objects.requireNonNull(binding, "binding");
      canonicalBytes = Objects.requireNonNull(canonicalBytes, "canonicalBytes").clone();
    }

    @Override
    public byte[] canonicalBytes() {
      return canonicalBytes.clone();
    }
  }
}
