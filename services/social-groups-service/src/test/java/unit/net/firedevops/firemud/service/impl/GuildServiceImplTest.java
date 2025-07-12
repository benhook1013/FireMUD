package net.firedevops.firemud.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.dto.AddGuildMemberRequest;
import net.firedevops.firemud.dto.GuildMemberDto;
import net.firedevops.firemud.entity.GuildMember;
import net.firedevops.firemud.mapper.GuildMemberMapper;
import net.firedevops.firemud.repository.GuildMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;

class GuildServiceImplTest {
  private GuildMemberRepository repository;
  private GuildServiceImpl service;

  @BeforeEach
  void setUp() {
    repository = Mockito.mock(GuildMemberRepository.class);
    service =
        new GuildServiceImpl(
            null,
            null,
            null,
            null,
            null,
            null,
            repository,
            Mappers.getMapper(GuildMemberMapper.class),
            null,
            null);
  }

  @Test
  void addMemberReturnsDto() {
    AddGuildMemberRequest request = new AddGuildMemberRequest(1L, 2L, 3L, "member");
    GuildMember saved = new GuildMember();
    saved.setId(1L);
    saved.setTenantId(1L);
    saved.setGuildId(2L);
    saved.setAccountId(3L);
    saved.setRole("member");
    when(repository.save(any(GuildMember.class))).thenReturn(saved);

    GuildMemberDto dto = service.addMember(request);
    assertEquals(3L, dto.accountId());
    assertEquals("member", dto.role());
  }
}
