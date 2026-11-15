package net.firedevops.firemud.common.publication;

/** Authenticated read of the exact retained World inventory for selected owner intake. */
public interface SelectedOwnerWorldInventoryReadClient {
  SelectedOwnerWorldInventoryReadEvidence read(
      SelectedOwnerWorldInventoryReadEvidence.Request request);
}
