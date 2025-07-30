package net.firedevops.firemud.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.dto.RevisionDto;
import net.firedevops.firemud.entity.Game;
import net.firedevops.firemud.entity.Revision;
import net.firedevops.firemud.mapper.RevisionMapper;
import net.firedevops.firemud.repository.GameRepository;
import net.firedevops.firemud.repository.RevisionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class RevisionServiceImplTest {
  @Mock private RevisionRepository revisionRepository;
  @Mock private GameRepository gameRepository;

  private RevisionServiceImpl service;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
    RevisionMapper mapper = Mappers.getMapper(RevisionMapper.class);
    service = new RevisionServiceImpl(revisionRepository, gameRepository, mapper);
  }

  @Test
  void saveRevisionPersistsEntity() {
    Game game = new Game();
    game.setId(1L);
    game.setTenantId("1");
    when(gameRepository.findByTenantId("1")).thenReturn(game);
    Revision saved = new Revision();
    saved.setId(10L);
    saved.setGame(game);
    when(revisionRepository.save(any(Revision.class))).thenReturn(saved);

    RevisionDto dto = new RevisionDto(null, "1", 3L, "{}", null);
    RevisionDto result = service.saveRevision(dto);

    assertEquals(10L, result.id());
  }
}
