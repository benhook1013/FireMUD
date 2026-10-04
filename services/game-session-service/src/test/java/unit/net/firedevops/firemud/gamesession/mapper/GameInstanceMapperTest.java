package net.firedevops.firemud.gamesession.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class GameInstanceMapperTest {
  private static final String OWNER_ACCOUNT_UUID = "123e4567-e89b-12d3-a456-426614174000";

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
            OWNER_ACCOUNT_UUID,
            "RUNNING");

    GameInstance mappedEntity = mapper.toEntity(dto);

    assertThat(mappedEntity.getScriptPatchBaseVersionId()).isNull();

    GameInstance persistedEntity = new GameInstance();
    persistedEntity.setScriptPatchBaseVersionId(99L);

    assertThat(mapper.toDto(persistedEntity).scriptPatchBaseVersionId()).isEqualTo(99L);
  }
}
