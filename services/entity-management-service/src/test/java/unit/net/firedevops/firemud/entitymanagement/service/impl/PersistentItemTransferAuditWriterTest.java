package net.firedevops.firemud.entitymanagement.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import net.firedevops.firemud.entitymanagement.entity.Item;
import net.firedevops.firemud.entitymanagement.entity.ItemTransferAudit;
import net.firedevops.firemud.entitymanagement.repository.ItemTransferAuditRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class PersistentItemTransferAuditWriterTest {
  private final ItemTransferAuditRepository repository =
      Mockito.mock(ItemTransferAuditRepository.class);
  private final PersistentItemTransferAuditWriter writer =
      new PersistentItemTransferAuditWriter(repository, new ItemTransferSupport());

  @Test
  void recordStackTransferBuildsDeterministicCorrelationKey() {
    Item arrows = new Item();
    arrows.setId(7L);
    ItemTransferSupport transferSupport = new ItemTransferSupport();

    writer.recordStackTransfer(
        1L,
        arrows,
        3,
        "ammo/iron",
        transferSupport.inventoryHolder(1L, 11L),
        transferSupport.roomHolder(1L, "realm-live", "R-1"),
        transferSupport.audit("DROP", 11L));
    writer.recordStackTransfer(
        1L,
        arrows,
        3,
        "ammo/iron",
        transferSupport.inventoryHolder(1L, 11L),
        transferSupport.roomHolder(1L, "realm-live", "R-1"),
        transferSupport.audit("DROP", 11L));

    ArgumentCaptor<ItemTransferAudit> captor = ArgumentCaptor.forClass(ItemTransferAudit.class);
    verify(repository, Mockito.times(2)).save(captor.capture());
    assertThat(captor.getAllValues().get(0).getCorrelationKey())
        .isEqualTo(captor.getAllValues().get(1).getCorrelationKey());
    assertThat(captor.getAllValues().get(0).getVerb()).isEqualTo("DROP");
    assertThat(captor.getAllValues().get(0).getSourceHolderKind()).isEqualTo("INVENTORY");
    assertThat(captor.getAllValues().get(0).getDestinationHolderKind()).isEqualTo("ROOM_GROUND");
  }
}
