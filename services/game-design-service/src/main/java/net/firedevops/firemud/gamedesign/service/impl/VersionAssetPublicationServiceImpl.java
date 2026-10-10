package net.firedevops.firemud.gamedesign.service.impl;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository.AssetSelection;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository.ExportSnapshot;
import net.firedevops.firemud.gamedesign.service.VersionAssetPublicationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Commits durable Version asset selections independently before exposing verified readback. */
@Service
public final class VersionAssetPublicationServiceImpl implements VersionAssetPublicationService {
  private final VersionAssetPublicationRepository repository;
  private final TransactionTemplate ownerWrite;
  private final TransactionTemplate ownerRead;

  public VersionAssetPublicationServiceImpl(
      PlatformTransactionManager transactionManager, VersionAssetPublicationRepository repository) {
    this.repository = Objects.requireNonNull(repository, "repository");
    PlatformTransactionManager requiredTransactionManager =
        Objects.requireNonNull(transactionManager, "transactionManager");
    this.ownerWrite = new TransactionTemplate(requiredTransactionManager);
    this.ownerWrite.setName("version-asset-publication-write");
    this.ownerWrite.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.ownerWrite.setTimeout(15);

    this.ownerRead = new TransactionTemplate(requiredTransactionManager);
    this.ownerRead.setName("version-asset-publication-readback");
    this.ownerRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.ownerRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    this.ownerRead.setReadOnly(true);
    this.ownerRead.setTimeout(15);
  }

  @Override
  public void associateDraftAsset(String tenantId, long versionId, long assetId, String usageType) {
    requireTenantId(tenantId);
    if (versionId <= 0 || assetId <= 0) {
      throw new IllegalArgumentException("Version and asset owner keys must be positive");
    }
    ownerWrite.executeWithoutResult(
        status -> repository.associateDraftAsset(tenantId, versionId, assetId, usageType));
  }

  @Override
  public ExportSnapshot freezeOrReadSnapshot(String tenantId, int versionNumber) {
    requireTenantId(tenantId);
    if (versionNumber <= 0) {
      throw new IllegalArgumentException("Version number must be positive");
    }
    ExportSnapshot committed =
        ownerWrite.execute(status -> repository.freezeOrReadSnapshot(tenantId, versionNumber));
    if (committed == null) {
      throw new IllegalStateException("Version asset snapshot transaction returned no proof");
    }

    ExportSnapshot readback = readFrozenSnapshot(tenantId, versionNumber);
    if (!sameSnapshot(committed, readback)) {
      throw new IllegalStateException(
          "Committed Version asset snapshot did not match independent readback");
    }
    return readback;
  }

  @Override
  public ExportSnapshot readFrozenSnapshot(String tenantId, int versionNumber) {
    requireTenantId(tenantId);
    if (versionNumber <= 0) {
      throw new IllegalArgumentException("Version number must be positive");
    }
    ExportSnapshot result =
        ownerRead.execute(status -> repository.readFrozenSnapshot(tenantId, versionNumber));
    if (result == null) {
      throw new IllegalStateException(
          "Independent Version asset snapshot readback returned no proof");
    }
    return result;
  }

  private static boolean sameSnapshot(ExportSnapshot left, ExportSnapshot right) {
    if (!Objects.equals(left.tenantId(), right.tenantId())
        || left.versionId() != right.versionId()
        || left.versionNumber() != right.versionNumber()
        || left.capturedVersionStateEpoch() != right.capturedVersionStateEpoch()
        || !Objects.equals(left.canonicalTenantId(), right.canonicalTenantId())
        || !Objects.equals(left.canonicalVersionId(), right.canonicalVersionId())) {
      return false;
    }
    List<AssetSelection> leftItems = left.items();
    List<AssetSelection> rightItems = right.items();
    if (leftItems.size() != rightItems.size()) {
      return false;
    }
    for (int index = 0; index < leftItems.size(); index++) {
      AssetSelection leftItem = leftItems.get(index);
      AssetSelection rightItem = rightItems.get(index);
      if (!Objects.equals(leftItem.usageKey(), rightItem.usageKey())
          || leftItem.assetId() != rightItem.assetId()
          || !Objects.equals(leftItem.contentType(), rightItem.contentType())
          || !Objects.equals(leftItem.contentDigest(), rightItem.contentDigest())
          || !Arrays.equals(leftItem.bytes(), rightItem.bytes())) {
        return false;
      }
    }
    return true;
  }

  private static void requireTenantId(String tenantId) {
    if (tenantId == null || tenantId.isBlank()) {
      throw new IllegalArgumentException("Owner-local tenant key is required");
    }
  }
}
