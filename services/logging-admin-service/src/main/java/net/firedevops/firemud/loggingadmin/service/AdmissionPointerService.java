package net.firedevops.firemud.loggingadmin.service;

import java.util.List;
import net.firedevops.firemud.loggingadmin.dto.AdmissionPointerDto;
import net.firedevops.firemud.loggingadmin.dto.ExecutePreparedVersionCutoverRequest;
import net.firedevops.firemud.loggingadmin.dto.PrepareVersionUpgradeRequest;
import net.firedevops.firemud.loggingadmin.dto.PreparedVersionUpgradeDto;
import net.firedevops.firemud.loggingadmin.dto.SetAdmissionPointerRequest;

public interface AdmissionPointerService {
  List<AdmissionPointerDto> listPointers();

  List<AdmissionPointerDto> listPointerAudit(String worldSlug, String realmSlug);

  AdmissionPointerDto setPointer(SetAdmissionPointerRequest request);

  AdmissionPointerDto executePreparedVersionCutover(ExecutePreparedVersionCutoverRequest request);

  PreparedVersionUpgradeDto prepareVersionUpgrade(PrepareVersionUpgradeRequest request);

  PreparedVersionUpgradeDto getPreparedVersionUpgrade(long tenantId, String preparationId);
}
