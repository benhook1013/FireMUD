package net.firedevops.firemud.entitymanagement.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.firedevops.firemud.entitymanagement.entity.EntityMutationEffect;
import net.firedevops.firemud.entitymanagement.repository.EntityMutationEffectRepository;
import net.firedevops.firemud.entitymanagement.v1.InventoryItem;
import net.firedevops.firemud.entitymanagement.v1.PickupItemFromRoomResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class EntityMutationEffectReplayServiceTest {
  private final EntityMutationEffectRepository repository =
      Mockito.mock(EntityMutationEffectRepository.class);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final EntityMutationEffectReplayService service =
      new EntityMutationEffectReplayService(repository, meterRegistry);

  @Test
  void executeWithoutEffectIdBypassesReplayLedger() {
    PickupItemFromRoomResponse expected = pickupResponse("Torch");

    PickupItemFromRoomResponse response =
        service.execute(
            1L, null, "PickupItemFromRoom", () -> expected, PickupItemFromRoomResponse::parseFrom);

    assertThat(response).isSameAs(expected);
    verify(repository, never()).findByTenantIdAndEffectId(Mockito.anyLong(), Mockito.anyString());
  }

  @Test
  void executeReplaysStoredAppliedResponseWithoutCallingMutation() {
    EntityMutationEffect effect = new EntityMutationEffect();
    effect.setTenantId(1L);
    effect.setEffectId("effect-1");
    effect.setOperationName("PickupItemFromRoom");
    effect.setStatus("APPLIED");
    effect.setResponsePayload(pickupResponse("Torch").toByteArray());
    when(repository.findByTenantIdAndEffectId(1L, "effect-1")).thenReturn(Optional.of(effect));
    AtomicBoolean mutationCalled = new AtomicBoolean(false);
    Supplier<PickupItemFromRoomResponse> mutation =
        () -> {
          mutationCalled.set(true);
          return pickupResponse("Wrong");
        };

    PickupItemFromRoomResponse response =
        service.execute(
            1L, "effect-1", "PickupItemFromRoom", mutation, PickupItemFromRoomResponse::parseFrom);

    assertThat(response.getInventoryItem().getItemName()).isEqualTo("Torch");
    assertThat(mutationCalled).isFalse();
    assertThat(
            meterRegistry
                .get("entitymanagement.mutation.effect.execution")
                .tag("operation", "PickupItemFromRoom")
                .tag("effect_status", "REPLAY_NOOP")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void executeStoresFirstAppliedResponse() {
    EntityMutationEffect effect = new EntityMutationEffect();
    effect.setTenantId(1L);
    effect.setEffectId("effect-1");
    effect.setOperationName("PickupItemFromRoom");
    effect.setStatus("IN_PROGRESS");
    when(repository.findByTenantIdAndEffectId(1L, "effect-1"))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(effect));
    when(repository.insertInProgress(1L, "effect-1", "PickupItemFromRoom")).thenReturn(1);
    PickupItemFromRoomResponse expected = pickupResponse("Lantern");

    PickupItemFromRoomResponse response =
        service.execute(
            1L,
            "effect-1",
            "PickupItemFromRoom",
            () -> expected,
            PickupItemFromRoomResponse::parseFrom);

    assertThat(response).isSameAs(expected);
    ArgumentCaptor<EntityMutationEffect> captor =
        ArgumentCaptor.forClass(EntityMutationEffect.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo("APPLIED");
    assertThat(captor.getValue().getResponsePayload()).isEqualTo(expected.toByteArray());
    assertThat(
            meterRegistry
                .get("entitymanagement.mutation.effect.execution")
                .tag("operation", "PickupItemFromRoom")
                .tag("effect_status", "APPLIED")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  private PickupItemFromRoomResponse pickupResponse(String itemName) {
    return PickupItemFromRoomResponse.newBuilder()
        .setInventoryItem(InventoryItem.newBuilder().setItemId("7").setItemName(itemName).build())
        .build();
  }
}
