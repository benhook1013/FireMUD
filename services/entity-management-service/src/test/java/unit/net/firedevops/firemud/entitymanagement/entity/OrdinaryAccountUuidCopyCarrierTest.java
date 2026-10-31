package net.firedevops.firemud.entitymanagement.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OrdinaryAccountUuidCopyCarrierTest {
  private static final UUID FIRST_ACCOUNT_UUID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID SECOND_ACCOUNT_UUID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final long PRIVATE_ACCOUNT_ID = 41L;

  private static Stream<Arguments> characterCarriers() {
    CharacterEquipmentEntry equipmentEntry = new CharacterEquipmentEntry();
    ContainerInstance containerInstance = new ContainerInstance();
    InventoryEntry inventoryEntry = new InventoryEntry();
    return Stream.of(
        Arguments.of(
            new CharacterCarrier(
                "CharacterEquipmentEntry",
                equipmentEntry::setCharacter,
                equipmentEntry::getCharacter)),
        Arguments.of(
            new CharacterCarrier(
                "ContainerInstance",
                containerInstance::setCharacter,
                containerInstance::getCharacter)),
        Arguments.of(
            new CharacterCarrier(
                "InventoryEntry", inventoryEntry::setCharacter, inventoryEntry::getCharacter)));
  }

  private static Character character(long characterId, UUID accountUuid) {
    Character character = new Character();
    character.setId(characterId);
    character.setTenantId(17L);
    character.setAccountId(PRIVATE_ACCOUNT_ID);
    character.setAccountUuid(accountUuid);
    character.setName("copy-owner");
    return character;
  }

  @ParameterizedTest
  @MethodSource("characterCarriers")
  void defensiveCharacterCopiesPreservePersistedAccountUuid(CharacterCarrier carrier) {
    Character setterInput = character(101L, FIRST_ACCOUNT_UUID);
    carrier.setter().accept(setterInput);

    setterInput.setId(201L);
    setterInput.setAccountId(99L);
    setterInput.setAccountUuid(SECOND_ACCOUNT_UUID);
    Character firstRead = carrier.getter().get();
    assertEquals(101L, firstRead.getId(), carrier.name());
    assertEquals(PRIVATE_ACCOUNT_ID, firstRead.getAccountId(), carrier.name());
    assertEquals(FIRST_ACCOUNT_UUID, firstRead.getAccountUuid(), carrier.name());

    firstRead.setId(202L);
    firstRead.setAccountId(100L);
    firstRead.setAccountUuid(SECOND_ACCOUNT_UUID);
    Character unchangedRead = carrier.getter().get();
    assertEquals(101L, unchangedRead.getId(), carrier.name());
    assertEquals(PRIVATE_ACCOUNT_ID, unchangedRead.getAccountId(), carrier.name());
    assertEquals(FIRST_ACCOUNT_UUID, unchangedRead.getAccountUuid(), carrier.name());

    Character secondOwner = character(102L, SECOND_ACCOUNT_UUID);
    carrier.setter().accept(secondOwner);
    Character secondRead = carrier.getter().get();
    assertEquals(102L, secondRead.getId(), carrier.name());
    assertEquals(PRIVATE_ACCOUNT_ID, secondRead.getAccountId(), carrier.name());
    assertEquals(SECOND_ACCOUNT_UUID, secondRead.getAccountUuid(), carrier.name());
    assertNotEquals(FIRST_ACCOUNT_UUID, secondRead.getAccountUuid(), carrier.name());

    carrier.setter().accept(character(103L, null));
    Character unresolvedOwner = carrier.getter().get();
    assertEquals(103L, unresolvedOwner.getId(), carrier.name());
    assertEquals(PRIVATE_ACCOUNT_ID, unresolvedOwner.getAccountId(), carrier.name());
    assertNull(unresolvedOwner.getAccountUuid(), carrier.name());

    carrier.setter().accept(null);
    assertNull(carrier.getter().get(), carrier.name());
  }

  private record CharacterCarrier(
      String name, Consumer<Character> setter, Supplier<Character> getter) {}
}
