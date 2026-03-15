package net.firedevops.firemud.socialgroups.service.impl;

import io.micrometer.core.annotation.Timed;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.saga.SagaBuilder;
import net.firedevops.firemud.common.saga.SagaException;
import net.firedevops.firemud.common.saga.SagaRunner;
import net.firedevops.firemud.socialgroups.client.LoggingAdminClient;
import net.firedevops.firemud.socialgroups.dto.AddGuildMemberRequest;
import net.firedevops.firemud.socialgroups.dto.AddGuildStorageItemRequest;
import net.firedevops.firemud.socialgroups.dto.CreateAllianceRequest;
import net.firedevops.firemud.socialgroups.dto.CreateGuildRequest;
import net.firedevops.firemud.socialgroups.dto.GuildAllianceDto;
import net.firedevops.firemud.socialgroups.dto.GuildDto;
import net.firedevops.firemud.socialgroups.dto.GuildMemberDto;
import net.firedevops.firemud.socialgroups.dto.GuildStorageItemDto;
import net.firedevops.firemud.socialgroups.dto.UpdateGuildMemberRoleRequest;
import net.firedevops.firemud.socialgroups.entity.Guild;
import net.firedevops.firemud.socialgroups.entity.GuildAlliance;
import net.firedevops.firemud.socialgroups.entity.GuildMember;
import net.firedevops.firemud.socialgroups.entity.GuildStorageItem;
import net.firedevops.firemud.socialgroups.mapper.GuildAllianceMapper;
import net.firedevops.firemud.socialgroups.mapper.GuildMapper;
import net.firedevops.firemud.socialgroups.mapper.GuildMemberMapper;
import net.firedevops.firemud.socialgroups.mapper.GuildStorageItemMapper;
import net.firedevops.firemud.socialgroups.repository.GuildAllianceRepository;
import net.firedevops.firemud.socialgroups.repository.GuildMemberRepository;
import net.firedevops.firemud.socialgroups.repository.GuildRepository;
import net.firedevops.firemud.socialgroups.repository.GuildStorageItemRepository;
import net.firedevops.firemud.socialgroups.service.GuildService;
import org.slf4j.Logger;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GuildServiceImpl implements GuildService {
  private static final Logger logger = LoggingUtil.getLogger(GuildServiceImpl.class);

  private final GuildStorageItemRepository storageRepo;
  private final GuildStorageItemMapper storageMapper;
  private final GuildAllianceRepository allianceRepo;
  private final GuildAllianceMapper allianceMapper;
  private final GuildRepository guildRepository;
  private final GuildMapper guildMapper;
  private final GuildMemberRepository guildMemberRepository;
  private final GuildMemberMapper guildMemberMapper;
  private final LoggingAdminClient loggingAdminClient;
  @Nullable private final SagaRunner sagaRunner;

  private void runSaga(net.firedevops.firemud.common.saga.Saga saga) throws SagaException {
    if (sagaRunner == null) {
      saga.run();
      return;
    }
    sagaRunner.run(saga);
  }

  @Override
  @Transactional
  @Timed(value = "guild.create")
  public GuildDto createGuild(CreateGuildRequest request) {
    logger.info("Creating guild {}", request.name());
    Guild guild = new Guild();
    guild.setTenantId(request.tenantId());
    guild.setOwnerAccountId(request.ownerAccountId());
    guild.setName(request.name());
    guild.setCreatedAt(Instant.now());

    var saga =
        new SagaBuilder("createGuild")
            .step(
                "persistGuild",
                () -> guildRepository.save(guild),
                () -> guildRepository.delete(guild))
            .step(
                "logCreation",
                () ->
                    loggingAdminClient.reportChatViolation(
                        request.tenantId(),
                        request.ownerAccountId(),
                        "Guild created: " + request.name()))
            .build();
    try {
      runSaga(saga);
    } catch (SagaException e) {
      logger.warn("Guild creation saga failed", e);
      throw new IllegalStateException("Guild creation failed", e);
    }

    return guildMapper.toDto(guild);
  }

  @Override
  @Transactional
  @Timed(value = "guild.addStorageItem")
  public GuildStorageItemDto addStorageItem(AddGuildStorageItemRequest request) {
    logger.info("Adding item {} to guild {}", request.itemName(), request.guildId());
    GuildStorageItem item = new GuildStorageItem();
    item.setTenantId(request.tenantId());
    item.setGuildId(request.guildId());
    item.setItemName(request.itemName());
    item.setQuantity(request.quantity());
    return storageMapper.toDto(storageRepo.save(item));
  }

  @Override
  @Transactional
  @Timed(value = "guild.createAlliance")
  public GuildAllianceDto createAlliance(CreateAllianceRequest request) {
    logger.info("Creating alliance {} -> {}", request.guildId(), request.allyGuildId());
    GuildAlliance alliance = new GuildAlliance();
    alliance.setTenantId(request.tenantId());
    alliance.setGuildId(request.guildId());
    alliance.setAllyGuildId(request.allyGuildId());
    alliance.setCreatedAt(Instant.now());
    return allianceMapper.toDto(allianceRepo.save(alliance));
  }

  @Override
  @Transactional
  @Timed(value = "guild.addMember")
  public GuildMemberDto addMember(AddGuildMemberRequest request) {
    logger.info("Adding member {} to guild {}", request.accountId(), request.guildId());
    GuildMember member = new GuildMember();
    member.setTenantId(request.tenantId());
    member.setGuildId(request.guildId());
    member.setAccountId(request.accountId());
    member.setRole(request.role());

    var saga =
        new SagaBuilder("addGuildMember")
            .step(
                "persistMember",
                () -> {
                  GuildMember saved = guildMemberRepository.save(member);
                  member.setId(saved.getId());
                },
                () -> guildMemberRepository.delete(member))
            .step(
                "logAdd",
                () ->
                    loggingAdminClient.reportChatViolation(
                        request.tenantId(),
                        request.accountId(),
                        "Joined guild " + request.guildId()))
            .build();
    try {
      runSaga(saga);
    } catch (SagaException e) {
      logger.warn("Add member saga failed", e);
      throw new IllegalStateException("Add member failed", e);
    }

    return guildMemberMapper.toDto(member);
  }

  @Override
  @Transactional
  @Timed(value = "guild.updateMemberRole")
  public GuildMemberDto updateMemberRole(UpdateGuildMemberRoleRequest request) {
    logger.info(
        "Updating member {} in guild {} to role {}",
        request.accountId(),
        request.guildId(),
        request.role());
    GuildMember member =
        guildMemberRepository.findAll().stream()
            .filter(
                m ->
                    m.getTenantId().equals(request.tenantId())
                        && m.getGuildId().equals(request.guildId())
                        && m.getAccountId().equals(request.accountId()))
            .findFirst()
            .orElseThrow();
    String originalRole = member.getRole();
    var saga =
        new SagaBuilder("updateGuildMemberRole")
            .step(
                "updateMember",
                () -> {
                  member.setRole(request.role());
                  guildMemberRepository.save(member);
                },
                () -> {
                  member.setRole(originalRole);
                  guildMemberRepository.save(member);
                })
            .step(
                "logUpdate",
                () ->
                    loggingAdminClient.reportChatViolation(
                        request.tenantId(),
                        request.accountId(),
                        "Updated guild role to " + request.role()))
            .build();
    try {
      runSaga(saga);
    } catch (SagaException e) {
      logger.warn("Update member role saga failed", e);
      throw new IllegalStateException("Update member role failed", e);
    }

    return guildMemberMapper.toDto(member);
  }

  @Override
  @Transactional
  @Timed(value = "guild.removeMember")
  public void removeMember(long tenantId, long guildId, long accountId) {
    logger.info("Removing member {} from guild {}", accountId, guildId);
    GuildMember member =
        guildMemberRepository.findAll().stream()
            .filter(
                m ->
                    m.getTenantId().equals(tenantId)
                        && m.getGuildId().equals(guildId)
                        && m.getAccountId().equals(accountId))
            .findFirst()
            .orElse(null);
    if (member == null) {
      return;
    }

    var saga =
        new SagaBuilder("removeGuildMember")
            .step(
                "deleteMember",
                () -> guildMemberRepository.delete(member),
                () -> guildMemberRepository.save(member))
            .step(
                "logRemove",
                () ->
                    loggingAdminClient.reportChatViolation(
                        tenantId, accountId, "Left guild " + guildId))
            .build();
    try {
      runSaga(saga);
    } catch (SagaException e) {
      logger.warn("Remove member saga failed", e);
      throw new IllegalStateException("Remove member failed", e);
    }
  }
}
