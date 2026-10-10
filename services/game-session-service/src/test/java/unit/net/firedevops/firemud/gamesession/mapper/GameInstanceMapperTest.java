package net.firedevops.firemud.gamesession.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class GameInstanceMapperTest {
  private final GameInstanceMapper mapper = Mappers.getMapper(GameInstanceMapper.class);

  @Test
  void serverControlledScriptPatchBaseIsIgnoredOnEntityMappingAndExposedOnDtoMapping() {
    GameInstanceDto dto =
        new GameInstanceDto(
            1L,
            2L,
            "runtime-1",
            "patch-1",
            77L,
            3L,
            "pin-request-1",
            4L,
            "launch-1",
            5L,
            6L,
            7L,
            "generation-1",
            null,
            8L,
            "RUNNING");

    GameInstance mappedEntity = mapper.toEntity(dto);

    assertThat(mappedEntity.getId()).isEqualTo(1L);
    assertThat(mappedEntity.getTenantId()).isEqualTo(2L);
    assertThat(mappedEntity.getRuntimeVersion()).isEqualTo("runtime-1");
    assertThat(mappedEntity.getScriptPinEpoch()).isNull();
    assertThat(mappedEntity.getScriptPatchPinnedControlPlaneRequestId()).isNull();
    assertThat(mappedEntity.getScriptPatchBaseVersionId()).isNull();
    assertThat(mappedEntity.getRunOwnedStartRequestId()).isNull();
    assertThat(mappedEntity.getRunOwnedStartRequestDigest()).isNull();
    assertThat(mappedEntity.getRunOwnedStartPublishedReleaseBundleRef()).isNull();
    assertThat(mappedEntity.getRunOwnedStartPreparingEpoch()).isNull();
    assertThat(mappedEntity.getRunOwnedStartActiveEpoch()).isNull();

    GameInstance persistedEntity = new GameInstance();
    persistedEntity.setScriptPatchBaseVersionId(99L);

    assertThat(mapper.toDto(persistedEntity).scriptPatchBaseVersionId()).isEqualTo(99L);
  }
}
