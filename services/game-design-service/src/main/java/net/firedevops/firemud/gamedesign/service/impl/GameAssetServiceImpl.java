package net.firedevops.firemud.gamedesign.service.impl;

import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.gamedesign.dto.GameAssetDto;
import net.firedevops.firemud.gamedesign.mapper.GameAssetMapper;
import net.firedevops.firemud.gamedesign.repository.GameAssetRepository;
import net.firedevops.firemud.gamedesign.service.GameAssetService;
import net.firedevops.firemud.gamedesign.service.MutationOwnerProofUnavailableException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class GameAssetServiceImpl implements GameAssetService {
  private final GameAssetRepository repository;
  private final GameAssetMapper mapper;

  @Override
  @Transactional
  @Timed(value = "gamedesign.asset.upload")
  public GameAssetDto uploadAsset(String tenantId, MultipartFile file) {
    return CreatorMutationOwnerProofGuard.denyUntilAccountCommitBoundProof(
        () ->
            new MutationOwnerProofUnavailableException(
                "CREATOR_MUTATION_OWNER_PROOF_UNAVAILABLE",
                "Account commit-bound authority is unavailable for asset upload"));
  }
}
