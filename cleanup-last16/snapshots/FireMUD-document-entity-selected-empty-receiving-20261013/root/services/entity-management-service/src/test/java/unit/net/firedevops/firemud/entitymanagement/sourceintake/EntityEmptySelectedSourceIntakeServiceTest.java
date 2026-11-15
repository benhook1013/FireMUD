package net.firedevops.firemud.entitymanagement.sourceintake;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationReadClient;
import net.firedevops.firemud.common.publication.SelectedOwnerWorldInventoryReadClient;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

class EntityEmptySelectedSourceIntakeServiceTest {
  @Test
  void rejectsCallerSelectedNamespaceBeforeReceiptReplayOrRemoteReads() {
    DSLContext dsl = mock(DSLContext.class);
    SelectedOwnerIntakeAuthorizationReadClient authorizationClient =
        mock(SelectedOwnerIntakeAuthorizationReadClient.class);
    SelectedOwnerWorldInventoryReadClient worldClient =
        mock(SelectedOwnerWorldInventoryReadClient.class);
    var service =
        new EntityEmptySelectedSourceIntakeService(
            "configured",
            new EntityEmptySelectedSourceIntakeRepository(dsl),
            authorizationClient,
            worldClient);

    assertThatThrownBy(() -> service.retain("attacker-selected", null, null))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("configured Entity owner namespace");

    verifyNoInteractions(dsl, authorizationClient, worldClient);
  }

  @Test
  void rejectsInvalidConfiguredNamespaceAtConstruction() {
    DSLContext dsl = mock(DSLContext.class);
    assertThatThrownBy(
            () ->
                new EntityEmptySelectedSourceIntakeService(
                    "Invalid_Namespace",
                    new EntityEmptySelectedSourceIntakeRepository(dsl),
                    mock(SelectedOwnerIntakeAuthorizationReadClient.class),
                    mock(SelectedOwnerWorldInventoryReadClient.class)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Configured Entity namespace");
  }
}
