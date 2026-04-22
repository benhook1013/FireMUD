package net.firedevops.firemud.gamedesign.service;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamedesign.dto.TemplateRemapEntryDto;
import net.firedevops.firemud.gamedesign.dto.TemplateRemapSetDto;

public interface TemplateRemapSetService {
  TemplateRemapSetDto createTemplateRemapSet(
      String tenantId,
      long sourceVersionId,
      long targetVersionId,
      String createdReason,
      List<TemplateRemapEntryDto> remapEntries);

  TemplateRemapSetDto approveTemplateRemapSet(
      String tenantId, String remapSetId, String approvalReason);

  TemplateRemapSetDto getTemplateRemapSet(String tenantId, String remapSetId);

  Optional<TemplateRemapSetDto> findApprovedTemplateRemapSet(
      String tenantId, long sourceVersionId, long targetVersionId);
}
