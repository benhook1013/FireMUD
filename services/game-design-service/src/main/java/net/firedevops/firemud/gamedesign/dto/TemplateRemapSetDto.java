package net.firedevops.firemud.gamedesign.dto;

import java.time.LocalDateTime;
import java.util.List;
import net.firedevops.firemud.gamedesign.model.TemplateRemapSetStatus;

public record TemplateRemapSetDto(
    String remapSetId,
    String tenantId,
    long sourceVersionId,
    long targetVersionId,
    TemplateRemapSetStatus status,
    String createdReason,
    String approvalReason,
    LocalDateTime createdAt,
    LocalDateTime approvedAt,
    List<TemplateRemapEntryDto> remapEntries) {
  public TemplateRemapSetDto {
    remapEntries = remapEntries == null ? List.of() : List.copyOf(remapEntries);
  }

  @Override
  public List<TemplateRemapEntryDto> remapEntries() {
    return remapEntries;
  }
}
