package net.firedevops.firemud.socialgroups.service.impl;

import static net.firedevops.firemud.socialgroups.test.AccountUuidFixtures.accountUuid;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.socialgroups.dto.AddGuildMemberRequest;
import net.firedevops.firemud.socialgroups.dto.CreateGuildRequest;
import net.firedevops.firemud.socialgroups.dto.GuildMemberDto;
import net.firedevops.firemud.socialgroups.dto.UpdateGuildMemberRoleRequest;
import net.firedevops.firemud.socialgroups.entity.Guild;
import net.firedevops.firemud.socialgroups.entity.GuildMember;
import net.firedevops.firemud.socialgroups.mapper.GuildMapper;
import net.firedevops.firemud.socialgroups.mapper.GuildMemberMapper;
import net.firedevops.firemud.socialgroups.repository.GuildMemberRepository;
import net.firedevops.firemud.socialgroups.repository.GuildRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.Mockito;

class GuildServiceImplTest {
  private GuildMemberRepository memberRepository;
  private GuildRepository guildRepository;
  private GuildServiceImpl service;

  @BeforeEach
  void setUp() {
    memberRepository = Mockito.mock(GuildMemberRepository.class);
    guildRepository = Mockito.mock(GuildRepository.class);
    service =
        new GuildServiceImpl(
            null,
            null,
            null,
            null,
            guildRepository,
            Mappers.getMapper(GuildMapper.class),
            memberRepository,
            Mappers.getMapper(GuildMemberMapper.class));
  }

  @Test
  void addMemberReturnsDto() {
    AddGuildMemberRequest request = new AddGuildMemberRequest(1L, 2L, accountUuid(3L), "member");
    GuildMember saved = member("member");
    when(memberRepository.save(any(GuildMember.class))).thenReturn(saved);

    GuildMemberDto dto = service.addMember(request);

    assertEquals(accountUuid(3L), dto.accountId());
    assertEquals("member", dto.role());
  }

  @Test
  void updateMemberRoleUsesRepositoryLookup() {
    GuildMember member = member("member");
    when(memberRepository.findFirstByTenantIdAndGuildIdAndAccountId(1L, 2L, accountUuid(3L)))
        .thenReturn(java.util.Optional.of(member));

    GuildMemberDto dto =
        service.updateMemberRole(
            new UpdateGuildMemberRoleRequest(1L, 2L, accountUuid(3L), "officer"));

    assertEquals("officer", dto.role());
    verify(memberRepository).findFirstByTenantIdAndGuildIdAndAccountId(1L, 2L, accountUuid(3L));
  }

  @Test
  void removeMemberUsesRepositoryLookup() {
    GuildMember member = member("member");
    when(memberRepository.findFirstByTenantIdAndGuildIdAndAccountId(1L, 2L, accountUuid(3L)))
        .thenReturn(java.util.Optional.of(member));

    service.removeMember(1L, 2L, accountUuid(3L));

    verify(memberRepository).findFirstByTenantIdAndGuildIdAndAccountId(1L, 2L, accountUuid(3L));
    verify(memberRepository).delete(member);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "CREATE,true",
    "CREATE,false",
    "ADD_MEMBER,true",
    "ADD_MEMBER,false",
    "UPDATE_MEMBER_ROLE,true",
    "UPDATE_MEMBER_ROLE,false",
    "REMOVE_MEMBER,true",
    "REMOVE_MEMBER,false"
  })
  void lifecycleLoggingHasCommittedContextAndDoesNotRunOnRollback(String action, boolean commit) {
    var logger =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(GuildServiceImpl.class);
    var events =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    events.start();
    logger.addAppender(events);
    org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
    try {
      performLifecycle(action);
      org.junit.jupiter.api.Assertions.assertTrue(
          events.list.stream()
              .noneMatch(
                  event -> event.getFormattedMessage().startsWith("Guild lifecycle committed")));
      var callbacks =
          org.springframework.transaction.support.TransactionSynchronizationManager
              .getSynchronizations();
      if (commit) {
        callbacks.forEach(
            org.springframework.transaction.support.TransactionSynchronization::afterCommit);
      }
      callbacks.forEach(callback -> callback.afterCompletion(commit ? 0 : 1));
      var lifecycle =
          events.list.stream()
              .filter(event -> event.getFormattedMessage().startsWith("Guild lifecycle committed"))
              .toList();
      assertEquals(commit ? 1 : 0, lifecycle.size());
      if (commit) {
        assertEquals(
            "Guild lifecycle committed tenant=1 guild=2 account="
                + accountUuid(3L)
                + " action="
                + action
                + " role="
                + (action.equals("CREATE")
                    ? "null"
                    : action.equals("UPDATE_MEMBER_ROLE") ? "officer" : "member"),
            lifecycle.getFirst().getFormattedMessage());
      }
    } finally {
      org.springframework.transaction.support.TransactionSynchronizationManager
          .clearSynchronization();
      logger.detachAppender(events);
      events.stop();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void throwingLifecycleAppenderCannotFailAlreadyCommittedMutation() {
    var logger =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(GuildServiceImpl.class);
    ch.qos.logback.core.Appender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
        Mockito.mock(ch.qos.logback.core.Appender.class);
    var attempted = new java.util.concurrent.atomic.AtomicBoolean();
    Mockito.doAnswer(
            invocation -> {
              ch.qos.logback.classic.spi.ILoggingEvent event = invocation.getArgument(0);
              if (event.getFormattedMessage().startsWith("Guild lifecycle committed")) {
                attempted.set(true);
                throw new IllegalStateException("controlled logging failure");
              }
              return null;
            })
        .when(appender)
        .doAppend(any(ch.qos.logback.classic.spi.ILoggingEvent.class));
    logger.addAppender(appender);
    org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
    try {
      performLifecycle("ADD_MEMBER");
      verify(memberRepository).save(any(GuildMember.class));
      org.junit.jupiter.api.Assertions.assertFalse(attempted.get());
      org.junit.jupiter.api.Assertions.assertDoesNotThrow(
          () ->
              org.springframework.transaction.support.TransactionSynchronizationManager
                  .getSynchronizations()
                  .forEach(
                      org.springframework.transaction.support.TransactionSynchronization
                          ::afterCommit));
      org.junit.jupiter.api.Assertions.assertTrue(attempted.get());
    } finally {
      org.springframework.transaction.support.TransactionSynchronizationManager
          .clearSynchronization();
      logger.detachAppender(appender);
    }
  }

  private void performLifecycle(String action) {
    GuildMember member = member("member");
    switch (action) {
      case "CREATE" -> {
        when(guildRepository.save(any(Guild.class)))
            .thenAnswer(
                invocation -> {
                  Guild guild = invocation.getArgument(0);
                  guild.setId(2L);
                  return guild;
                });
        service.createGuild(new CreateGuildRequest(1L, accountUuid(3L), "guild"));
      }
      case "ADD_MEMBER" -> {
        when(memberRepository.save(any(GuildMember.class))).thenReturn(member);
        service.addMember(new AddGuildMemberRequest(1L, 2L, accountUuid(3L), "member"));
      }
      case "UPDATE_MEMBER_ROLE" -> {
        when(memberRepository.findFirstByTenantIdAndGuildIdAndAccountId(1L, 2L, accountUuid(3L)))
            .thenReturn(java.util.Optional.of(member));
        service.updateMemberRole(
            new UpdateGuildMemberRoleRequest(1L, 2L, accountUuid(3L), "officer"));
      }
      case "REMOVE_MEMBER" -> {
        when(memberRepository.findFirstByTenantIdAndGuildIdAndAccountId(1L, 2L, accountUuid(3L)))
            .thenReturn(java.util.Optional.of(member));
        service.removeMember(1L, 2L, accountUuid(3L));
      }
      default -> throw new IllegalArgumentException("Unknown lifecycle action");
    }
  }

  private GuildMember member(String role) {
    GuildMember member = new GuildMember();
    member.setId(5L);
    member.setTenantId(1L);
    member.setGuildId(2L);
    member.setAccountId(accountUuid(3L));
    member.setRole(role);
    return member;
  }
}
