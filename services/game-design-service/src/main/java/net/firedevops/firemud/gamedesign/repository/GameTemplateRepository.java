package net.firedevops.firemud.gamedesign.repository;

import java.util.Optional;
import net.firedevops.firemud.gamedesign.entity.GameTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface GameTemplateRepository extends JpaRepository<GameTemplate, Long> {
  Page<GameTemplate> findByTenantId(String tenantId, Pageable pageable);

  Optional<GameTemplate> findByTenantIdAndId(String tenantId, Long id);

  @Query(
      """
      select
        gt.id as id,
        gt.tenantId as tenantId,
        gt.defaultVersionId as defaultVersionId,
        gt.defaultScriptPatchVersion as defaultScriptPatchVersion,
        gt.defaultRuntimeFlagsJson as defaultRuntimeFlagsJson,
        gt.templateReferencePhase as templateReferencePhase
      from GameTemplate gt
      where gt.tenantId = :tenantId and gt.id = :id
      """)
  Optional<GameTemplateLaunchConfigView> findLaunchConfigByTenantIdAndId(
      @Param("tenantId") String tenantId, @Param("id") Long id);
}
