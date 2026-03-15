package net.firedevops.firemud.socialgroups.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.socialgroups.dto.AddGuildMemberRequest;
import net.firedevops.firemud.socialgroups.dto.GuildMemberDto;
import net.firedevops.firemud.socialgroups.entity.GuildMember;
import net.firedevops.firemud.socialgroups.mapper.GuildMemberMapper;
import net.firedevops.firemud.socialgroups.repository.GuildMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;

class GuildServiceImplTest {
  private GuildMemberRepository repository;
  private net.firedevops.firemud.socialgroups.client.LoggingAdminClient loggingAdminClient;
  private net.firedevops.firemud.common.saga.SagaRunner sagaRunner;
  private GuildServiceImpl service;

  @BeforeEach
  void setUp() {
    repository = Mockito.mock(GuildMemberRepository.class);
    loggingAdminClient =
        Mockito.mock(net.firedevops.firemud.socialgroups.client.LoggingAdminClient.class);
    sagaRunner = Mockito.mock(net.firedevops.firemud.common.saga.SagaRunner.class);
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
            loggingAdminClient,
            sagaRunner);
  }

  @Test
  void addMemberReturnsDto() throws Exception {
    AddGuildMemberRequest request = new AddGuildMemberRequest(1L, 2L, 3L, "member");
    GuildMember saved = new GuildMember();
    saved.setId(1L);
    saved.setTenantId(1L);
    saved.setGuildId(2L);
    saved.setAccountId(3L);
    saved.setRole("member");
    when(repository.save(any(GuildMember.class))).thenReturn(saved);
    doAnswer(
            inv -> {
              try {
                ((net.firedevops.firemud.common.saga.Saga) inv.getArgument(0)).run();
              } catch (net.firedevops.firemud.common.saga.SagaException e) {
                throw new RuntimeException(e);
              }
              return null;
            })
        .when(sagaRunner)
        .run(any());

    GuildMemberDto dto = service.addMember(request);
    assertEquals(3L, dto.accountId());
    assertEquals("member", dto.role());
  }
}
