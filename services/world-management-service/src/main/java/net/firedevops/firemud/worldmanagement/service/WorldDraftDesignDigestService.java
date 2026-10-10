package net.firedevops.firemud.worldmanagement.service;

import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTopologyInputGraph.InboundSourceClosureDeclaration;

public interface WorldDraftDesignDigestService {
  WorldDraftDesignDigest getDraftDesignDigest(String tenantId, String versionId);

  /** Selected-publication participant digest, bound to the exact retained source declaration. */
  WorldDraftDesignDigest getSelectedDraftDesignDigest(
      String tenantId, String versionId, InboundSourceClosureDeclaration inboundSourceClosure);

  record WorldDraftDesignDigest(
      String tenantId,
      String scopeValue,
      String appliedCommitId,
      String contentDigest,
      int digestSchemaVersion) {}
}
