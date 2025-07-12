package net.firedevops.firemud.service.impl;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.client.LoggingAdminClient;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.saga.SagaBuilder;
import net.firedevops.firemud.common.saga.SagaException;
import net.firedevops.firemud.common.saga.SagaRunner;
import net.firedevops.firemud.dto.AddGuildMemberRequest;
import net.firedevops.firemud.dto.AddGuildStorageItemRequest;
import net.firedevops.firemud.dto.CreateAllianceRequest;
import net.firedevops.firemud.dto.CreateGuildRequest;
import net.firedevops.firemud.dto.GuildAllianceDto;
import net.firedevops.firemud.dto.GuildDto;
import net.firedevops.firemud.dto.GuildMemberDto;
import net.firedevops.firemud.dto.GuildStorageItemDto;
import net.firedevops.firemud.dto.UpdateGuildMemberRoleRequest;
import net.firedevops.firemud.entity.Guild;
import net.firedevops.firemud.entity.GuildAlliance;
import net.firedevops.firemud.entity.GuildMember;
import net.firedevops.firemud.entity.GuildStorageItem;
import net.firedevops.firemud.mapper.GuildAllianceMapper;
import net.firedevops.firemud.mapper.GuildMapper;
import net.firedevops.firemud.mapper.GuildMemberMapper;
import net.firedevops.firemud.mapper.GuildStorageItemMapper;
import net.firedevops.firemud.repository.GuildAllianceRepository;
import net.firedevops.firemud.repository.GuildMemberRepository;
import net.firedevops.firemud.repository.GuildRepository;
import net.firedevops.firemud.repository.GuildStorageItemRepository;
import net.firedevops.firemud.service.GuildService;
import org.slf4j.Logger;
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
  private final SagaRunner sagaRunner;

  @Override
  @Transactional
  public GuildDto createGuild(CreateGuildRequest request) {
    logger.info("Creating guild {}", request.name());
    Guild guild = new Guild();
    guild.setTenantId(request.tenantId());
    guild.setOwnerAccountId(request.ownerAccountId());
    guild.setName(request.name());
    guild.setCreatedAt(Instant.now());

    var saga =
        new SagaBuilder()
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
      sagaRunner.run(saga);
    } catch (SagaException e) {
      logger.warn("Guild creation saga failed", e);
      throw new IllegalStateException("Guild creation failed", e);
    }

    return guildMapper.toDto(guild);
  }

  @Override
  @Transactional
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
  public GuildMemberDto addMember(AddGuildMemberRequest request) {
    logger.info("Adding member {} to guild {}", request.accountId(), request.guildId());
    GuildMember member = new GuildMember();
    member.setTenantId(request.tenantId());
    member.setGuildId(request.guildId());
    member.setAccountId(request.accountId());
    member.setRole(request.role());
    return guildMemberMapper.toDto(guildMemberRepository.save(member));
  }

  @Override
  @Transactional
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
    member.setRole(request.role());
    return guildMemberMapper.toDto(guildMemberRepository.save(member));
  }

  @Override
  @Transactional
  public void removeMember(long tenantId, long guildId, long accountId) {
    logger.info("Removing member {} from guild {}", accountId, guildId);
    guildMemberRepository.findAll().stream()
        .filter(
            m ->
                m.getTenantId().equals(tenantId)
                    && m.getGuildId().equals(guildId)
                    && m.getAccountId().equals(accountId))
        .findFirst()
        .ifPresent(guildMemberRepository::delete);
  }
}
