package net.firedevops.firemud.gamedesign.repository;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamedesign.entity.VersionTemplateRemapSet;
import net.firedevops.firemud.gamedesign.model.TemplateRemapSetStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VersionTemplateRemapSetRepository
    extends JpaRepository<VersionTemplateRemapSet, Long> {
  Optional<VersionTemplateRemapSet> findByTenantIdAndRemapSetId(String tenantId, String remapSetId);

  List<VersionTemplateRemapSet>
      findAllByTenantIdAndSourceVersionIdAndTargetVersionIdAndStatusOrderByCreatedAtAsc(
          String tenantId,
          Long sourceVersionId,
          Long targetVersionId,
          TemplateRemapSetStatus status);
}
