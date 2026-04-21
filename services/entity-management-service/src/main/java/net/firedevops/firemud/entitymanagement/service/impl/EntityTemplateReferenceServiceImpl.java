package net.firedevops.firemud.entitymanagement.service.impl;

import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.entitymanagement.repository.ItemRepository;
import net.firedevops.firemud.entitymanagement.repository.NpcRepository;
import net.firedevops.firemud.entitymanagement.service.EntityTemplateReferenceService;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EntityTemplateReferenceServiceImpl implements EntityTemplateReferenceService {
  private final ItemRepository itemRepository;
  private final NpcRepository npcRepository;

  @Override
  public boolean exists(String tenantId, String versionId, String templateType, String templateId) {
    long tenantKey = Long.parseLong(tenantId);
    long versionKey = Long.parseLong(versionId);
    long templateKey = Long.parseLong(templateId);
    return switch (templateType) {
      case "ITEM" ->
          itemRepository
              .findByTenantIdAndVersionIdAndId(tenantKey, versionKey, templateKey)
              .isPresent();
      case "NPC" ->
          npcRepository
              .findByTenantIdAndVersionIdAndId(tenantKey, versionKey, templateKey)
              .isPresent();
      default -> throw new IllegalArgumentException("unsupported templateType " + templateType);
    };
  }
}
