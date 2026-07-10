package net.firedevops.firemud.gamedesign.service;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamedesign.dto.HelpTopicDto;

public interface GameAuthoredHelpTopicService {
  Optional<HelpTopicDto> resolvePublishedTopic(String tenantId, long gameTemplateId, String topic);

  HelpTopicDto putTopic(String tenantId, long gameTemplateId, HelpTopicDto topic);

  List<HelpTopicDto> listTopics(String tenantId, long gameTemplateId);

  void deleteTopic(String tenantId, long gameTemplateId, String canonicalTopicId);
}
