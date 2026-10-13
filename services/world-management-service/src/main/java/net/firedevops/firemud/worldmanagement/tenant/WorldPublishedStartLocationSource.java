package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Objects;

/**
 * Original selector and immutable owner evidence for publication transport. This value grants no
 * current Account permission, complete release proof or runtime admission authority.
 */
public record WorldPublishedStartLocationSource(
    WorldCanonicalFrozenTopology frozenTopology,
    WorldDraftGraphAppliedResult appliedResult,
    WorldDraftStartLocationReceipt selectorReceipt) {
  public WorldPublishedStartLocationSource {
    Objects.requireNonNull(frozenTopology, "frozenTopology");
    Objects.requireNonNull(appliedResult, "appliedResult");
    Objects.requireNonNull(selectorReceipt, "selectorReceipt");
  }
}
