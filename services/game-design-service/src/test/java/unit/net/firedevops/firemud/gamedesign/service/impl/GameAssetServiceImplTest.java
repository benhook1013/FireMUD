package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import net.firedevops.firemud.gamedesign.mapper.GameAssetMapper;
import net.firedevops.firemud.gamedesign.repository.GameAssetRepository;
import net.firedevops.firemud.gamedesign.service.MutationOwnerProofUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

class GameAssetServiceImplTest {
  @Test
  void uploadRefusesBeforeReadingMultipartFileOrTouchingStorage() {
    GameAssetRepository repository = mock(GameAssetRepository.class);
    GameAssetMapper mapper = mock(GameAssetMapper.class);
    MultipartFile file = mock(MultipartFile.class);
    GameAssetServiceImpl service = new GameAssetServiceImpl(repository, mapper);

    MutationOwnerProofUnavailableException thrown =
        assertThrows(
            MutationOwnerProofUnavailableException.class,
            () -> service.uploadAsset("tenant-1", file));

    assertEquals("CREATOR_MUTATION_OWNER_PROOF_UNAVAILABLE", thrown.errorCode());
    verifyNoInteractions(file, repository, mapper);
  }
}
