package net.firedevops.firemud.gamedesign.service.impl;

final class PublishedReleaseBundleNotFoundException extends IllegalArgumentException {
  PublishedReleaseBundleNotFoundException(String tenantId, long versionId) {
    super(
        "PUBLISHED_RELEASE_BUNDLE_NOT_FOUND: no published release bundle for tenantId=%s versionId=%d"
            .formatted(tenantId, versionId));
  }
}
