package net.firedevops.firemud.gamedesign.service;

/**
 * Indicates that an immutable object write may have committed but exact readback is unavailable.
 */
public final class AssetExportOutcomePendingException extends IllegalStateException {
  private static final long serialVersionUID = 1L;

  public AssetExportOutcomePendingException(String message, Throwable cause) {
    super("ASSET_EXPORT_OUTCOME_PENDING: " + message, cause);
  }

  public AssetExportOutcomePendingException(String message) {
    super("ASSET_EXPORT_OUTCOME_PENDING: " + message);
  }
}
