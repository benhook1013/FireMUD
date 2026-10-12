package net.firedevops.firemud.gamedesign.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

/** Candidate evidence together with the approved delivery origin frozen for exact repair. */
public record FrozenAssetExportCandidate(ExportedAssetManifest manifest, String publicBaseUrl) {
  public FrozenAssetExportCandidate {
    Objects.requireNonNull(manifest, "manifest");
    try {
      if (!Objects.equals(publicBaseUrl, normalizePublicBaseUrl(publicBaseUrl))) {
        throw new IllegalArgumentException("frozen origin must be canonical");
      }
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("ASSET_EXPORT_CANDIDATE_CONFLICT", exception);
    }
  }

  /** Canonical syntax shared by initial configuration approval and retained candidate reads. */
  public static String normalizePublicBaseUrl(String value) {
    try {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException("missing public base URL");
      }
      URI uri = new URI(value);
      if (!uri.isAbsolute()
          || !"https".equalsIgnoreCase(uri.getScheme())
          || uri.getHost() == null
          || uri.getPort() == 0
          || uri.getPort() > 65535
          || uri.getRawUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || !("/assets".equals(uri.getRawPath()) || "/assets/".equals(uri.getRawPath()))) {
        throw new IllegalArgumentException("public base URL must be an HTTPS /assets origin");
      }
      return "https://" + uri.getRawAuthority() + "/assets";
    } catch (URISyntaxException exception) {
      throw new IllegalArgumentException("invalid public base URL", exception);
    }
  }
}
