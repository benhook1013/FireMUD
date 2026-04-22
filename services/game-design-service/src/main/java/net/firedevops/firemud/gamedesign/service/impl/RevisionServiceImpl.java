package net.firedevops.firemud.gamedesign.service.impl;

import io.micrometer.core.annotation.Timed;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamedesign.client.WorldManagementClient;
import net.firedevops.firemud.gamedesign.dto.AppliedWorldDesignMutationDto;
import net.firedevops.firemud.gamedesign.dto.RevisionDto;
import net.firedevops.firemud.gamedesign.dto.WorldDesignMutationRevisionDto;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Revision;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.mapper.RevisionMapper;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.RevisionRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.RevisionService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RevisionServiceImpl implements RevisionService {
  private static final Logger logger = LoggingUtil.getLogger(RevisionServiceImpl.class);

  private final RevisionRepository revisionRepository;
  private final GameRepository gameRepository;
  private final VersionRepository versionRepository;
  private final RevisionMapper revisionMapper;
  private final WorldManagementClient worldManagementClient;

  @Override
  @Transactional
  @Timed(value = "gamedesign.revision.save")
  public RevisionDto saveRevision(RevisionDto dto) {
    logger.info(
        "Saving revision for tenant {} version {} kind {}",
        dto.tenantId(),
        dto.versionId(),
        dto.revisionKind());
    Game game =
        Optional.ofNullable(gameRepository.findByTenantId(dto.tenantId()))
            .orElseThrow(() -> new IllegalArgumentException("game not found"));
    Version version =
        versionRepository
            .findByTenantIdAndId(dto.tenantId(), dto.versionId())
            .orElseThrow(() -> new IllegalArgumentException("version not found"));
    AppliedWorldDesignMutationDto appliedWorldDesignMutation =
        applyWorldDesignMutationIfPresent(dto);
    Revision entity = revisionMapper.toEntity(dto);
    entity.setTenantId(game.getTenantId());
    entity.setVersionId(version.getId());
    entity.setData(canonicalRevisionData(dto));
    entity = revisionRepository.save(entity);
    return new RevisionDto(
        entity.getId(),
        entity.getTenantId(),
        entity.getVersionId(),
        entity.getAuthorAccountId(),
        entity.getData(),
        entity.getRevisionKind(),
        entity.getLogicalRevisionId(),
        dto.worldDesignMutation(),
        appliedWorldDesignMutation,
        entity.getCreatedAt());
  }

  private AppliedWorldDesignMutationDto applyWorldDesignMutationIfPresent(RevisionDto dto) {
    if (dto.worldDesignMutation() == null) {
      return null;
    }
    validateWorldMutationRequest(dto);
    return worldManagementClient.applyWorldDesignMutation(
        dto.tenantId(), dto.versionId(), dto.worldDesignMutation());
  }

  private void validateWorldMutationRequest(RevisionDto dto) {
    WorldDesignMutationRevisionDto mutation = dto.worldDesignMutation();
    if (!"WORLD_DESIGN_MUTATION".equals(dto.revisionKind())) {
      throw new IllegalArgumentException(
          "INVALID_ARGUMENT: worldDesignMutation requires revisionKind WORLD_DESIGN_MUTATION");
    }
    if (mutation.logicalRevisionId() == null || mutation.logicalRevisionId().isBlank()) {
      throw new IllegalArgumentException(
          "INVALID_ARGUMENT: worldDesignMutation.logicalRevisionId is required");
    }
    if (mutation.commitId() == null || mutation.commitId().isBlank()) {
      throw new IllegalArgumentException(
          "INVALID_ARGUMENT: worldDesignMutation.commitId is required");
    }
  }

  private String canonicalRevisionData(RevisionDto dto) {
    if (dto.data() != null && !dto.data().isBlank()) {
      return dto.data();
    }
    if (dto.worldDesignMutation() == null) {
      throw new IllegalArgumentException("INVALID_ARGUMENT: revision data is required");
    }
    return "{"
        + "\"revisionKind\":\""
        + escapeJson(dto.revisionKind())
        + "\","
        + "\"versionId\":"
        + dto.versionId()
        + ","
        + "\"logicalRevisionId\":\""
        + escapeJson(dto.worldDesignMutation().logicalRevisionId())
        + "\","
        + "\"commitId\":\""
        + escapeJson(dto.worldDesignMutation().commitId())
        + "\","
        + "\"operation\":\""
        + escapeJson(dto.worldDesignMutation().operation())
        + "\","
        + "\"aggregateType\":\""
        + escapeJson(dto.worldDesignMutation().aggregateType())
        + "\","
        + "\"aggregateId\":\""
        + escapeJson(dto.worldDesignMutation().aggregateId())
        + "\""
        + "}";
  }

  private String escapeJson(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
