package net.firedevops.firemud.entitymanagement.service;

public class EquipmentSlotIncompatibleException extends RuntimeException {
  public static final String CODE = "SLOT_INCOMPATIBLE";

  public EquipmentSlotIncompatibleException(String message) {
    super(message);
  }
}
