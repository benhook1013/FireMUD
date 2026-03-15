package net.firedevops.firemud.socialgroups.service.impl;

import io.micrometer.core.annotation.Timed;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.socialgroups.dto.AddFriendRequest;
import net.firedevops.firemud.socialgroups.dto.FriendLinkDto;
import net.firedevops.firemud.socialgroups.entity.AccountFriendLink;
import net.firedevops.firemud.socialgroups.entity.FriendLink;
import net.firedevops.firemud.socialgroups.mapper.FriendLinkMapper;
import net.firedevops.firemud.socialgroups.repository.AccountFriendLinkRepository;
import net.firedevops.firemud.socialgroups.repository.FriendLinkRepository;
import net.firedevops.firemud.socialgroups.service.FriendService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FriendServiceImpl implements FriendService {
  private static final Logger logger = LoggingUtil.getLogger(FriendServiceImpl.class);

  private final FriendLinkRepository friendLinkRepository;
  private final AccountFriendLinkRepository accountFriendLinkRepository;
  private final FriendLinkMapper friendLinkMapper;

  @Override
  @Transactional
  @Timed(value = "friend.add")
  public FriendLinkDto addFriend(AddFriendRequest request) {
    logger.info(
        "Adding friend {} -> {} (accountLevel={})",
        request.accountId(),
        request.friendAccountId(),
        request.accountLevel());
    if (request.accountLevel()) {
      AccountFriendLink afl = new AccountFriendLink();
      afl.setAccountId(request.accountId());
      afl.setFriendAccountId(request.friendAccountId());
      afl.setStatus("active");
      afl.setCreatedAt(Instant.now());
      accountFriendLinkRepository.save(afl);
      // Map to existing DTO for simplicity
      FriendLink dto = new FriendLink();
      dto.setId(afl.getId());
      dto.setTenantId(request.tenantId());
      dto.setAccountId(afl.getAccountId());
      dto.setFriendAccountId(afl.getFriendAccountId());
      dto.setStatus(afl.getStatus());
      dto.setCreatedAt(afl.getCreatedAt());
      return friendLinkMapper.toDto(dto);
    } else {
      FriendLink link = new FriendLink();
      link.setTenantId(request.tenantId());
      link.setAccountId(request.accountId());
      link.setFriendAccountId(request.friendAccountId());
      link.setStatus("active");
      link.setCreatedAt(Instant.now());
      return friendLinkMapper.toDto(friendLinkRepository.save(link));
    }
  }
}
