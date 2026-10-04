package net.firedevops.firemud.socialgroups.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import net.firedevops.firemud.socialgroups.dto.AddGuildMemberRequest;
import net.firedevops.firemud.socialgroups.dto.GuildMemberDto;
import net.firedevops.firemud.socialgroups.dto.UpdateGuildMemberRoleRequest;
import net.firedevops.firemud.socialgroups.entity.GuildMember;
import net.firedevops.firemud.socialgroups.mapper.GuildMemberMapper;
import net.firedevops.firemud.socialgroups.repository.GuildMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;

class GuildServiceImplTest {
  private static final String ACCOUNT_ID = "00000000-0000-4000-8000-000000000003";
  private static final UUID ACCOUNT_UUID = UUID.fromString(ACCOUNT_ID);
  private GuildMemberRepository repository;
  private net.firedevops.firemud.socialgroups.client.LoggingAdminClient loggingAdminClient;
  private GuildServiceImpl service;

  @BeforeEach
  void setUp() {
    repository = Mockito.mock(GuildMemberRepository.class);
    loggingAdminClient =
        Mockito.mock(net.firedevops.firemud.socialgroups.client.LoggingAdminClient.class);
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
            loggingAdminClient);
  }

  @Test
  void addMemberReturnsDto() throws Exception {
    AddGuildMemberRequest request = new AddGuildMemberRequest(1L, 2L, ACCOUNT_ID, "member");
    GuildMember saved = new GuildMember();
    saved.setId(1L);
    saved.setTenantId(1L);
    saved.setGuildId(2L);
    saved.setAccountId(ACCOUNT_UUID);
    saved.setRole("member");
    when(repository.save(any(GuildMember.class))).thenReturn(saved);

    GuildMemberDto dto = service.addMember(request);
    assertEquals(ACCOUNT_ID, dto.accountId());
    assertEquals("member", dto.role());
    verify(loggingAdminClient).reportChatViolation(1L, ACCOUNT_ID, "Joined guild 2");
  }

  @Test
  void updateMemberRoleUsesRepositoryLookup() throws Exception {
    GuildMember member = new GuildMember();
    member.setId(5L);
    member.setTenantId(1L);
    member.setGuildId(2L);
    member.setAccountId(ACCOUNT_UUID);
    member.setRole("member");
    when(repository.findFirstByTenantIdAndGuildIdAndAccountId(1L, 2L, ACCOUNT_UUID))
        .thenReturn(java.util.Optional.of(member));

    GuildMemberDto dto =
        service.updateMemberRole(new UpdateGuildMemberRoleRequest(1L, 2L, ACCOUNT_ID, "officer"));

    assertEquals("officer", dto.role());
    verify(repository).findFirstByTenantIdAndGuildIdAndAccountId(1L, 2L, ACCOUNT_UUID);
    verify(loggingAdminClient).reportChatViolation(1L, ACCOUNT_ID, "Updated guild role to officer");
  }

  @Test
  void removeMemberUsesRepositoryLookup() throws Exception {
    GuildMember member = new GuildMember();
    member.setId(5L);
    member.setTenantId(1L);
    member.setGuildId(2L);
    member.setAccountId(ACCOUNT_UUID);
    member.setRole("member");
    when(repository.findFirstByTenantIdAndGuildIdAndAccountId(1L, 2L, ACCOUNT_UUID))
        .thenReturn(java.util.Optional.of(member));

    service.removeMember(1L, 2L, ACCOUNT_ID);

    verify(repository).findFirstByTenantIdAndGuildIdAndAccountId(1L, 2L, ACCOUNT_UUID);
    verify(repository).delete(member);
    verify(loggingAdminClient).reportChatViolation(1L, ACCOUNT_ID, "Left guild 2");
  }
}
