package net.firedevops.firemud.service.impl;

import io.micrometer.core.annotation.Timed;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.dto.AddFriendRequest;
import net.firedevops.firemud.dto.FriendLinkDto;
import net.firedevops.firemud.entity.FriendLink;
import net.firedevops.firemud.mapper.FriendLinkMapper;
import net.firedevops.firemud.repository.FriendLinkRepository;
import net.firedevops.firemud.service.FriendService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FriendServiceImpl implements FriendService {
  private static final Logger logger = LoggingUtil.getLogger(FriendServiceImpl.class);

  private final FriendLinkRepository friendLinkRepository;
  private final FriendLinkMapper friendLinkMapper;

  @Override
  @Transactional
  @Timed(value = "friend.add")
  public FriendLinkDto addFriend(AddFriendRequest request) {
    logger.info("Adding friend {} -> {}", request.accountId(), request.friendAccountId());
    FriendLink link = new FriendLink();
    link.setTenantId(request.tenantId());
    link.setAccountId(request.accountId());
    link.setFriendAccountId(request.friendAccountId());
    link.setStatus("active");
    link.setCreatedAt(Instant.now());
    return friendLinkMapper.toDto(friendLinkRepository.save(link));
  }
}
