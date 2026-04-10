package net.firedevops.firemud.entitymanagement.service.impl;

import net.firedevops.firemud.entitymanagement.entity.Item;
import net.firedevops.firemud.entitymanagement.entity.ItemVisibleRefCounter;
import net.firedevops.firemud.entitymanagement.repository.ItemVisibleRefCounterRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Component
public class ItemVisibleRefAllocator {
  private final ItemVisibleRefCounterRepository counterRepository;

  public ItemVisibleRefAllocator(ItemVisibleRefCounterRepository counterRepository) {
    this.counterRepository = counterRepository;
  }

  @Transactional
  public VisibleRef allocate(Long tenantId, Item item) {
    String token = normalizeToken(item == null ? null : item.getName());
    for (int attempt = 0; attempt < 3; attempt++) {
      var existing = counterRepository.findByTenantIdAndVisibleRefToken(tenantId, token);
      if (existing.isPresent()) {
        ItemVisibleRefCounter counter = existing.orElseThrow();
        long sequence = counter.getNextSequence();
        counter.setNextSequence(sequence + 1);
        counterRepository.save(counter);
        return new VisibleRef(token, sequence, token + sequence);
      }
      ItemVisibleRefCounter created = new ItemVisibleRefCounter();
      created.setTenantId(tenantId);
      created.setVisibleRefToken(token);
      created.setNextSequence(2L);
      try {
        counterRepository.saveAndFlush(created);
        return new VisibleRef(token, 1L, token + 1L);
      } catch (DataIntegrityViolationException ignored) {
        // Another writer created the counter first; retry against the locked read path.
      }
    }
    throw new IllegalStateException("Failed to allocate item visible ref");
  }

  static String normalizeToken(String value) {
    if (!StringUtils.hasText(value)) {
      return "item";
    }
    StringBuilder normalized = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char ch = Character.toLowerCase(value.charAt(i));
      if (Character.isLetterOrDigit(ch)) {
        normalized.append(ch);
      }
    }
    return normalized.isEmpty() ? "item" : normalized.toString();
  }

  public record VisibleRef(String token, long sequence, String value) {}
}
