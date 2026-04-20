package net.firedevops.firemud.gamedesign.service.impl;

import io.micrometer.core.annotation.Timed;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.gamedesign.dto.TemplateRemapEntryDto;
import net.firedevops.firemud.gamedesign.dto.TemplateRemapSetDto;
import net.firedevops.firemud.gamedesign.entity.VersionTemplateRemapEntry;
import net.firedevops.firemud.gamedesign.entity.VersionTemplateRemapSet;
import net.firedevops.firemud.gamedesign.model.TemplateRemapSetStatus;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.repository.VersionTemplateRemapSetRepository;
import net.firedevops.firemud.gamedesign.service.TemplateRemapSetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TemplateRemapSetServiceImpl implements TemplateRemapSetService {
  private final VersionTemplateRemapSetRepository remapSetRepository;
  private final VersionRepository versionRepository;

  @Override
  @Transactional
  @Timed(value = "gamedesign.templateRemapSet.create")
  public TemplateRemapSetDto createTemplateRemapSet(
      String tenantId,
      long sourceVersionId,
      long targetVersionId,
      String createdReason,
      List<TemplateRemapEntryDto> remapEntries) {
    validateVersionPair(tenantId, sourceVersionId, targetVersionId);
    if (isBlank(createdReason)) {
      throw new IllegalArgumentException("INVALID_ARGUMENT: createdReason is required");
    }
    VersionTemplateRemapSet remapSet = new VersionTemplateRemapSet();
    remapSet.setRemapSetId("remap-" + UUID.randomUUID());
    remapSet.setTenantId(tenantId);
    remapSet.setSourceVersionId(sourceVersionId);
    remapSet.setTargetVersionId(targetVersionId);
    remapSet.setStatus(TemplateRemapSetStatus.DRAFT);
    remapSet.setCreatedReason(createdReason);
    for (TemplateRemapEntryDto entry :
        remapEntries == null ? List.<TemplateRemapEntryDto>of() : remapEntries) {
      remapSet.addRemapEntry(toEntity(entry));
    }
    return toDto(remapSetRepository.save(remapSet));
  }

  @Override
  @Transactional
  @Timed(value = "gamedesign.templateRemapSet.approve")
  public TemplateRemapSetDto approveTemplateRemapSet(
      String tenantId, String remapSetId, String approvalReason) {
    if (isBlank(approvalReason)) {
      throw new IllegalArgumentException("INVALID_ARGUMENT: approvalReason is required");
    }
    VersionTemplateRemapSet remapSet = requireRemapSet(tenantId, remapSetId);
    if (remapSet.getStatus() == TemplateRemapSetStatus.APPROVED) {
      return toDto(remapSet);
    }
    remapSet.setStatus(TemplateRemapSetStatus.APPROVED);
    remapSet.setApprovalReason(approvalReason);
    remapSet.setApprovedAt(LocalDateTime.now());
    return toDto(remapSetRepository.save(remapSet));
  }

  @Override
  @Transactional(readOnly = true)
  public TemplateRemapSetDto getTemplateRemapSet(String tenantId, String remapSetId) {
    return toDto(requireRemapSet(tenantId, remapSetId));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<TemplateRemapSetDto> findApprovedTemplateRemapSet(
      String tenantId, long sourceVersionId, long targetVersionId) {
    List<VersionTemplateRemapSet> approved =
        remapSetRepository
            .findAllByTenantIdAndSourceVersionIdAndTargetVersionIdAndStatusOrderByCreatedAtAsc(
                tenantId, sourceVersionId, targetVersionId, TemplateRemapSetStatus.APPROVED);
    if (approved.isEmpty()) {
      return Optional.empty();
    }
    if (approved.size() > 1) {
      throw new IllegalArgumentException(
          "INVALID_TEMPLATE_CONFIGURATION: multiple approved remap sets exist for the version pair");
    }
    return Optional.of(toDto(approved.get(0)));
  }

  private VersionTemplateRemapSet requireRemapSet(String tenantId, String remapSetId) {
    if (isBlank(remapSetId)) {
      throw new IllegalArgumentException("INVALID_ARGUMENT: remapSetId is required");
    }
    return remapSetRepository
        .findByTenantIdAndRemapSetId(tenantId, remapSetId)
        .orElseThrow(() -> new IllegalArgumentException("NOT_FOUND: template remap set not found"));
  }

  private void validateVersionPair(String tenantId, long sourceVersionId, long targetVersionId) {
    if (sourceVersionId <= 0L || targetVersionId <= 0L) {
      throw new IllegalArgumentException(
          "INVALID_ARGUMENT: sourceVersionId and targetVersionId are required");
    }
    if (sourceVersionId == targetVersionId) {
      throw new IllegalArgumentException(
          "INVALID_ARGUMENT: remap sets require distinct source and target versions");
    }
    versionRepository
        .findById(sourceVersionId)
        .filter(version -> tenantId.equals(version.getTenantId()))
        .orElseThrow(
            () -> new IllegalArgumentException("INVALID_ARGUMENT: source version not found"));
    versionRepository
        .findById(targetVersionId)
        .filter(version -> tenantId.equals(version.getTenantId()))
        .orElseThrow(
            () -> new IllegalArgumentException("INVALID_ARGUMENT: target version not found"));
  }

  private VersionTemplateRemapEntry toEntity(TemplateRemapEntryDto entry) {
    if (entry == null
        || isBlank(entry.mappingDomain())
        || isBlank(entry.mappingType())
        || isBlank(entry.sourceTemplateKey())
        || isBlank(entry.targetTemplateKey())) {
      throw new IllegalArgumentException(
          "INVALID_ARGUMENT: remap entries require domain, type, source key, and target key");
    }
    VersionTemplateRemapEntry entity = new VersionTemplateRemapEntry();
    entity.setMappingDomain(entry.mappingDomain());
    entity.setMappingType(entry.mappingType());
    entity.setSourceTemplateKey(entry.sourceTemplateKey());
    entity.setTargetTemplateKey(entry.targetTemplateKey());
    return entity;
  }

  private TemplateRemapSetDto toDto(VersionTemplateRemapSet remapSet) {
    return new TemplateRemapSetDto(
        remapSet.getRemapSetId(),
        remapSet.getTenantId(),
        remapSet.getSourceVersionId(),
        remapSet.getTargetVersionId(),
        remapSet.getStatus(),
        remapSet.getCreatedReason(),
        remapSet.getApprovalReason(),
        remapSet.getCreatedAt(),
        remapSet.getApprovedAt(),
        remapSet.getRemapEntries().stream()
            .map(
                entry ->
                    new TemplateRemapEntryDto(
                        entry.getMappingDomain(),
                        entry.getMappingType(),
                        entry.getSourceTemplateKey(),
                        entry.getTargetTemplateKey()))
            .toList());
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
