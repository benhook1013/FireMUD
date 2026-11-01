package unit.net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.firedevops.firemud.gamesession.command.text.DirectTextWorldSelectionService;
import net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalog;
import net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalog.RealmView;
import net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalog.WorldView;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof.Outcome;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest.OriginKind;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService;
import net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService.InvalidAuthorityException;
import net.firedevops.firemud.gamesession.service.CanonicalPlayerRouteReadService.ReadUnavailableException;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class DirectTextWorldSelectionServiceTest {
  private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");
  private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID REALM = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID NAMESPACE = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID INSTANCE = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID VERSION = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID HOLD = UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final UUID FENCE = UUID.fromString("77777777-7777-4777-8777-777777777777");
  private static final UUID OTHER = UUID.fromString("88888888-8888-4888-8888-888888888888");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final CanonicalPlayerRouteReadService reader =
      mock(CanonicalPlayerRouteReadService.class);
  private final MutableClock clock = new MutableClock();

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void historicalEncodingRemainsClockIndependentWhileRestorationAndResolutionRefuseUnliveHandles(
      boolean expired) {
    var first = target("");
    var reads = new AtomicInteger();
    var catalog =
        GameplayWorldCatalog.forWorldSupplier(
            () -> {
              reads.incrementAndGet();
              return List.of(world(first));
            });
    var source = service(catalog, List.of(first));
    var menu = source.browse().orElseThrow();
    byte[] original = source.encodeSnapshot(menu).orElseThrow();
    var foreignOwner = service(catalog, List.of(first));
    assertThat(reads.get()).isEqualTo(2);
    clearInvocations(reader);
    clock.now = expired ? menu.expiresAt() : NOW.minusNanos(1);

    // The new immediate-discovery helper must not change generic historical encoding semantics.
    assertThat(source.encodeSnapshot(menu))
        .hasValueSatisfying(bytes -> assertThat(bytes).isEqualTo(original));
    assertThat(foreignOwner.encodeSnapshot(menu)).isEmpty();
    assertThat(source.encodeSnapshot(null)).isEmpty();
    assertThat(source.restoreSnapshot(original)).isEmpty();
    assertThat(source.resolve(menu, "1")).isEmpty();
    assertThat(reads.get()).isEqualTo(2);
    verifyNoInteractions(reader);
  }

  @Test
  void restoresAcrossInstancesWithOriginalOrderOwnershipAndDeadline() {
    var first = target("");
    var second = target("second");
    var source = service(List.of(world(second), world(first)), List.of(first, second));
    var original = source.browse().orElseThrow();
    byte[] stored = source.encodeSnapshot(original).orElseThrow();
    var replacement = service(List.of(world(second), world(first)), List.of(first, second));
    clock.now = NOW.plusSeconds(299);

    var restored = replacement.restoreSnapshot(stored).orElseThrow();

    assertThat(restored.selectors()).containsExactly("other-tenant/other-world", "studio/forest");
    assertThat(restored.worlds()).containsExactly(world(second), world(first));
    assertThat(restored.expiresAt()).isEqualTo(original.expiresAt());
    assertThat(replacement.encodeSnapshot(restored).orElseThrow()).isEqualTo(stored);
    assertThat(replacement.resolve(restored, "1")).contains(second);
    assertThat(replacement.resolve(restored, "2")).contains(first);
    assertThat(replacement.resolve(restored, "STUDIO/FOREST")).contains(first);
    assertThat(replacement.resolve(restored, "studio")).contains(first);
    clearInvocations(reader);
    assertThat(source.resolve(restored, "1")).isEmpty();
    assertThat(replacement.resolve(original, "1")).isEmpty();
    assertThat(replacement.encodeSnapshot(original)).isEmpty();
    assertThat(replacement.encodeSnapshot(null)).isEmpty();
    verifyNoInteractions(reader);

    clock.now = NOW.plusSeconds(300).minusNanos(1);
    assertThat(replacement.restoreSnapshot(stored)).isPresent();
    assertThat(replacement.resolve(restored, "1")).contains(second);
    clearInvocations(reader);
    clock.now = NOW.plusSeconds(300);
    assertThat(replacement.restoreSnapshot(stored)).isEmpty();
    assertThat(replacement.resolve(restored, "1")).isEmpty();
    verifyNoInteractions(reader);
  }

  @ParameterizedTest
  @ValueSource(strings = {"short", "long", "future", "expired"})
  void rejectsInvalidOriginalTimesBeforeReadingSources(String change) {
    var first = target("");
    var source = service(List.of(world(first)), List.of(first));
    byte[] stored = source.encodeSnapshot(source.browse().orElseThrow()).orElseThrow();
    Instant created = "future".equals(change) ? NOW.plusSeconds(1) : NOW;
    Instant expires = created.plusSeconds("short".equals(change) ? 299 : 300);
    if ("long".equals(change)) {
      expires = expires.plusNanos(1);
    }
    Instant deadline = expires;
    byte[] altered =
        alterSnapshot(
            stored,
            snapshot -> {
              snapshot.set("createdAt", JSON.valueToTree(created));
              snapshot.set("expiresAt", JSON.valueToTree(deadline));
            });
    if ("expired".equals(change)) {
      clock.now = deadline;
    }
    clearInvocations(reader);

    assertThat(source.restoreSnapshot(altered)).isEmpty();
    verifyNoInteractions(reader);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"subset", "reorder", "duplicate", "empty", "fingerprint", "target", "world"})
  void refusesSyntheticMenusEvenWhenTheCatalogFingerprintIsRetained(String change) {
    var first = target("");
    var second = target("second");
    var source = service(List.of(world(first), world(second)), List.of(first, second));
    byte[] stored = source.encodeSnapshot(source.browse().orElseThrow()).orElseThrow();
    byte[] altered =
        alterSnapshot(
            stored,
            snapshot -> {
              ArrayNode choices = (ArrayNode) snapshot.get("choices");
              switch (change) {
                case "subset" -> choices.remove(1);
                case "empty" -> choices.removeAll();
                case "duplicate" -> choices.set(1, choices.get(0));
                case "reorder" -> {
                  var originalFirst = choices.get(0);
                  choices.set(0, choices.get(1));
                  choices.set(1, originalFirst);
                }
                case "fingerprint" -> snapshot.put("fingerprint", "forged");
                case "target" ->
                    ((ObjectNode) choices.get(0))
                        .set("target", JSON.valueToTree(target("owner-namespace")));
                case "world" ->
                    ((ObjectNode) choices.get(0))
                        .set("world", JSON.valueToTree(world(first, true)));
                default -> throw new AssertionError(change);
              }
            });

    assertThat(source.restoreSnapshot(altered)).isEmpty();
    // Decodable but changed evidence was checked against the actual source, not just its digest.
    verify(reader, times(2)).readVisiblePublicProductionTargets();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "unknown-target",
        "unknown-world",
        "missing-target-field",
        "null-realms",
        "coerced-number"
      })
  void refusesMalformedNestedRecordsWithoutConsultingSources(String change) {
    var first = target("");
    var source = service(List.of(world(first)), List.of(first));
    byte[] stored = source.encodeSnapshot(source.browse().orElseThrow()).orElseThrow();
    byte[] altered =
        alterSnapshot(
            stored,
            snapshot -> {
              var choice = snapshot.get("choices").get(0);
              ObjectNode target = (ObjectNode) choice.get("target");
              ObjectNode world = (ObjectNode) choice.get("world");
              switch (change) {
                case "unknown-target" -> target.put("unexpected", true);
                case "unknown-world" -> world.put("unexpected", true);
                case "missing-target-field" -> target.remove("positiveDurableAbort");
                case "null-realms" -> world.putNull("realms");
                case "coerced-number" -> target.put("gameInstanceId", "202");
                default -> throw new AssertionError(change);
              }
            });
    clearInvocations(reader);

    assertThat(source.restoreSnapshot(altered)).isEmpty();
    verifyNoInteractions(reader);
  }

  @ParameterizedTest
  @ValueSource(strings = {"reorder", "changed", "duplicate", "empty"})
  void refusesRestorationAgainstChangedCurrentCatalog(String change) {
    var first = target("");
    var second = target("second");
    AtomicReference<List<WorldView>> worlds =
        new AtomicReference<>(List.of(world(first), world(second)));
    var catalog = GameplayWorldCatalog.forWorldSupplier(worlds::get);
    var source = service(catalog, List.of(first, second));
    byte[] stored = source.encodeSnapshot(source.browse().orElseThrow()).orElseThrow();
    worlds.set(
        switch (change) {
          case "reorder" -> List.of(world(second), world(first));
          case "changed" -> List.of(world(first, true), world(second));
          case "duplicate" -> List.of(world(first), world(first));
          case "empty" -> List.of();
          default -> throw new AssertionError(change);
        });

    assertThat(service(catalog, List.of(first, second)).restoreSnapshot(stored)).isEmpty();
  }

  @Test
  void restorationRetainsFullCanonicalOwnerChecksBeforeAndAfterCreatingTheHandle() {
    var first = target("");
    var source = service(List.of(world(first)), List.of(first));
    byte[] stored = source.encodeSnapshot(source.browse().orElseThrow()).orElseThrow();
    var replacement = service(List.of(world(first)), List.of(first));
    when(reader.readVisiblePublicProductionTargets())
        .thenReturn(List.of(target("owner-namespace")));
    assertThat(replacement.restoreSnapshot(stored)).isEmpty();

    when(reader.readVisiblePublicProductionTargets()).thenReturn(List.of(first));
    var restored = replacement.restoreSnapshot(stored).orElseThrow();
    when(reader.readCurrentTarget(first)).thenReturn(target("owner-namespace"));
    assertThat(replacement.resolve(restored, "1")).isEmpty();
  }

  @Test
  void restorationRechecksOriginalExpiryAfterCanonicalAndCatalogReads() {
    var first = target("");
    AtomicReference<Runnable> readEffect = new AtomicReference<>(() -> {});
    var catalog =
        GameplayWorldCatalog.forWorldSupplier(
            () -> {
              readEffect.get().run();
              return List.of(world(first));
            });
    var source = service(catalog, List.of(first));
    byte[] stored = source.encodeSnapshot(source.browse().orElseThrow()).orElseThrow();
    var replacement = service(catalog, List.of(first));
    when(reader.readVisiblePublicProductionTargets())
        .thenAnswer(
            invocation -> {
              clock.now = NOW.plusSeconds(300);
              return List.of(first);
            });
    assertThat(replacement.restoreSnapshot(stored)).isEmpty();

    clock.now = NOW;
    when(reader.readVisiblePublicProductionTargets()).thenReturn(List.of(first));
    AtomicInteger reads = new AtomicInteger();
    readEffect.set(
        () -> {
          if (reads.incrementAndGet() == 2) {
            clock.now = NOW.plusSeconds(300);
          }
        });
    assertThat(replacement.restoreSnapshot(stored)).isEmpty();
  }

  @Test
  void restorationKeepsParsingRefusalSeparateFromUnavailableAndInvalidSources() {
    var first = target("");
    var source = service(List.of(world(first)), List.of(first));
    byte[] stored = source.encodeSnapshot(source.browse().orElseThrow()).orElseThrow();
    var replacement = service(List.of(world(first)), List.of(first));
    var unavailable = new ReadUnavailableException("owner unreachable");
    when(reader.readVisiblePublicProductionTargets()).thenThrow(unavailable);
    clearInvocations(reader);
    assertThat(replacement.restoreSnapshot(null)).isEmpty();
    assertThat(replacement.restoreSnapshot(new byte[] {'{'})).isEmpty();
    verifyNoInteractions(reader);
    assertThatThrownBy(() -> replacement.restoreSnapshot(stored)).isSameAs(unavailable);
    doThrow(new InvalidAuthorityException("owner invalid"))
        .when(reader)
        .readVisiblePublicProductionTargets();
    assertThat(replacement.restoreSnapshot(stored)).isEmpty();

    var catalogUnavailable =
        new GameplayWorldCatalog.AuthorityPointerUnavailableException("unavailable");
    var unreachableCatalog =
        GameplayWorldCatalog.forWorldSupplier(
            () -> {
              throw catalogUnavailable;
            });
    var catalogReplacement = new DirectTextWorldSelectionService(unreachableCatalog, reader, clock);
    assertThatThrownBy(() -> catalogReplacement.restoreSnapshot(stored))
        .isSameAs(catalogUnavailable);
    var defaultOff =
        new DirectTextWorldSelectionService(
            GameplayWorldCatalog.forWorldViews(List.of(world(first))),
            CanonicalPlayerRouteReadService.unavailable(),
            clock);
    assertThatThrownBy(() -> defaultOff.restoreSnapshot(stored))
        .isInstanceOf(ReadUnavailableException.class);
  }

  @Test
  void restorationDoesNotHideCatalogDriftDuringFreshCompositionOrDuplicateCanonicalSources() {
    var first = target("");
    AtomicReference<List<WorldView>> worlds = new AtomicReference<>(List.of(world(first)));
    var catalog = GameplayWorldCatalog.forWorldSupplier(worlds::get);
    var source = service(catalog, List.of(first));
    byte[] stored = source.encodeSnapshot(source.browse().orElseThrow()).orElseThrow();
    when(reader.readVisiblePublicProductionTargets())
        .thenAnswer(
            invocation -> {
              worlds.set(List.of());
              return List.of(first);
            });
    assertThat(source.restoreSnapshot(stored)).isEmpty();
    worlds.set(List.of(world(first)));
    when(reader.readVisiblePublicProductionTargets()).thenReturn(List.of(first, first));
    assertThat(source.restoreSnapshot(stored)).isEmpty();
  }

  private static byte[] alterSnapshot(byte[] stored, Consumer<ObjectNode> alteration) {
    ObjectNode envelope = (ObjectNode) JSON.readTree(stored);
    alteration.accept((ObjectNode) envelope.get("snapshot"));
    return JSON.writeValueAsBytes(envelope);
  }

  @Test
  void correlatesExactEvidenceInCatalogOrderAndRetainsCompleteTarget() {
    var first = target("");
    var second = target("second");
    var service = service(List.of(world(second), world(first)), List.of(first, second));

    var menu = service.browse().orElseThrow();

    assertThat(menu.selectors()).containsExactly("other-tenant/other-world", "studio/forest");
    assertThat(menu.worlds()).containsExactly(world(second), world(first));
    assertThat(menu.expiresAt()).isEqualTo(NOW.plusSeconds(300));
    assertThat(service.resolve(menu, "1")).containsSame(second);
    assertThat(service.resolve(menu, "2")).containsSame(first);
    verify(reader).readCurrentTarget(first);
    verify(reader).readCurrentTarget(second);
  }

  @Test
  void pairsAreCaseFoldedAndBareTenantRequiresExactlyOneSnapshotWorld() {
    var first = target("");
    // The current real catalog permits only one public realm per numeric tenant. Distinct runtime
    // tenants below deliberately exercise the selection ambiguity guard with valid target records.
    var sameFriendlyTenant = target("same-tenant");
    var service =
        service(
            List.of(world(first), world(sameFriendlyTenant)), List.of(first, sameFriendlyTenant));
    var menu = service.browse().orElseThrow();

    assertThat(service.resolve(menu, "STUDIO/FOREST")).contains(first);
    assertThat(service.resolve(menu, "studio/other-world")).contains(sameFriendlyTenant);
    assertThat(service.resolve(menu, "studio")).isEmpty();
    assertThat(service.resolve(menu, "forest")).isEmpty();
    assertThat(service.resolve(menu, "other-world")).isEmpty();

    var single = service(List.of(world(first)), List.of(first));
    assertThat(single.resolve(single.browse().orElseThrow(), "STUDIO")).contains(first);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        " ",
        "forest",
        "101",
        "202",
        "0",
        "-1",
        "+1",
        "2147483648",
        "studio/forest/extra",
        " studio/forest",
        "studio/forest "
      })
  void invalidSelectorsDoNotReadSources(String selector) {
    var first = target("");
    AtomicInteger catalogReads = new AtomicInteger();
    var catalog =
        GameplayWorldCatalog.forWorldSupplier(
            () -> {
              catalogReads.incrementAndGet();
              return List.of(world(first));
            });
    var service = service(catalog, List.of(first));
    var menu = service.browse().orElseThrow();
    clearInvocations(reader);
    int before = catalogReads.get();

    assertThat(service.resolve(menu, selector)).isEmpty();
    assertThat(service.resolve(menu, null)).isEmpty();
    assertThat(service.resolve(null, "1")).isEmpty();
    assertThat(catalogReads.get()).isEqualTo(before);
    verifyNoInteractions(reader);
  }

  @Test
  void reorderedCatalogNeverReinterpretsAnOldOrdinal() {
    var first = target("");
    var second = target("second");
    AtomicReference<List<WorldView>> worlds =
        new AtomicReference<>(List.of(world(first), world(second)));
    var service =
        service(GameplayWorldCatalog.forWorldSupplier(worlds::get), List.of(first, second));
    var oldMenu = service.browse().orElseThrow();
    worlds.set(List.of(world(second), world(first)));

    assertThat(service.resolve(oldMenu, "1")).isEmpty();
    // Selection came from the old vector, not the current reordered catalog.
    verify(reader).readCurrentTarget(first);
    var refreshed = service.browse().orElseThrow();
    assertThat(service.resolve(refreshed, "1")).containsSame(second);
  }

  @Test
  void changedCatalogDuringBrowseIsRefused() {
    var first = target("");
    AtomicReference<List<WorldView>> worlds = new AtomicReference<>(List.of(world(first)));
    var catalog = GameplayWorldCatalog.forWorldSupplier(worlds::get);
    when(reader.readVisiblePublicProductionTargets())
        .thenAnswer(
            invocation -> {
              worlds.set(List.of());
              return List.of(first);
            });

    assertThat(new DirectTextWorldSelectionService(catalog, reader, clock).browse()).isEmpty();
  }

  @Test
  void originalProjectionAndSelectorOutputsAreImmutableAfterSourceMutation() {
    var first = target("");
    ArrayList<WorldView> worlds = new ArrayList<>(List.of(world(first)));
    var service = service(GameplayWorldCatalog.forWorldSupplier(() -> worlds), List.of(first));
    var menu = service.browse().orElseThrow();
    worlds.clear();

    assertThat(menu.selectors()).containsExactly("studio/forest");
    assertThat(menu.worlds()).containsExactly(world(first));
    assertThatThrownBy(() -> menu.selectors().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> menu.worlds().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> menu.worlds().getFirst().realms().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(service.resolve(menu, "1")).isEmpty();
  }

  @Test
  void expiryIsExclusiveAtFiveMinutesAndExpiredSelectionsHaveNoSourceEffects() {
    var first = target("");
    AtomicInteger catalogReads = new AtomicInteger();
    var service =
        service(
            GameplayWorldCatalog.forWorldSupplier(
                () -> {
                  catalogReads.incrementAndGet();
                  return List.of(world(first));
                }),
            List.of(first));
    var menu = service.browse().orElseThrow();
    clock.now = NOW.plusSeconds(300).minusNanos(1);
    assertThat(service.resolve(menu, "1")).contains(first);
    clearInvocations(reader);
    int before = catalogReads.get();
    clock.now = NOW.plusSeconds(300);

    assertThat(service.resolve(menu, "1")).isEmpty();
    clock.now = NOW.plusSeconds(301);
    assertThat(service.resolve(menu, "studio/forest")).isEmpty();
    assertThat(catalogReads.get()).isEqualTo(before);
    verifyNoInteractions(reader);
  }

  @Test
  void expiryIsRecheckedAfterCanonicalAndCatalogReads() {
    var first = target("");
    AtomicReference<Runnable> onCatalogRead = new AtomicReference<>(() -> {});
    var service =
        service(
            GameplayWorldCatalog.forWorldSupplier(
                () -> {
                  onCatalogRead.get().run();
                  return List.of(world(first));
                }),
            List.of(first));
    var menu = service.browse().orElseThrow();
    when(reader.readCurrentTarget(first))
        .thenAnswer(
            invocation -> {
              clock.now = NOW.plusSeconds(300);
              return first;
            });
    assertThat(service.resolve(menu, "1")).isEmpty();

    clock.now = NOW;
    when(reader.readCurrentTarget(first)).thenReturn(first);
    onCatalogRead.set(() -> clock.now = NOW.plusSeconds(300));
    assertThat(service.resolve(menu, "1")).isEmpty();
  }

  @Test
  void browseTimeConsumesTheSameBoundedMenuLifetime() {
    var first = target("");
    var service = service(List.of(world(first)), List.of(first));
    when(reader.readVisiblePublicProductionTargets())
        .thenAnswer(
            invocation -> {
              clock.now = NOW.plusSeconds(300);
              return List.of(first);
            });
    assertThat(service.browse()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "realm",
        "namespace",
        "revision",
        "pointer",
        "runtime",
        "tenant",
        "world",
        "realm-slug",
        "world-display",
        "realm-display",
        "scope",
        "policy"
      })
  void missingOrContradictoryCatalogCorrelationIsRefused(String change) {
    var original = target("");
    var changed = target(change);
    assertThat(service(List.of(world(original)), List.of(changed)).browse()).isEmpty();
  }

  @Test
  void displayNamesAloneNeverCorrelateAndMissingTargetsFailClosed() {
    var first = target("");
    assertThat(service(List.of(world(first)), List.of()).browse()).isEmpty();
    assertThat(service(List.of(world(first)), List.of(target("tenant"))).browse()).isEmpty();
    // A synthetic catalog view without real UUID evidence cannot supply canonical identity.
    var realm = world(first).realms().getFirst();
    var incomplete =
        new RealmView(
            realm.slug(),
            realm.displayName(),
            realm.tenantId(),
            realm.gameInstanceId(),
            realm.pointerVersion(),
            true,
            true,
            false,
            realm.stateScope(),
            realm.characterCreationPolicy(),
            realm.catalogRevision());
    assertThat(
            service(List.of(new WorldView("forest", "Forest", List.of(incomplete))), List.of(first))
                .browse())
        .isEmpty();
  }

  @Test
  void duplicateFriendlyPairsAndRuntimeWorldIdentitiesAreInvalidEvenIfOneRowWouldMatch() {
    var first = target("");
    assertThat(service(List.of(world(first)), List.of(first, first)).browse()).isEmpty();
    assertThat(service(List.of(world(first)), List.of(first, target("duplicate-pair"))).browse())
        .isEmpty();
    assertThat(service(List.of(world(first)), List.of(first, target("duplicate-runtime"))).browse())
        .isEmpty();
    assertThat(service(List.of(world(first)), List.of(first, target("duplicate-world"))).browse())
        .isEmpty();
    assertThat(service(List.of(world(first)), Arrays.asList(first, null)).browse()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"uppercase-tenant", "uppercase-world", "bad-tenant", "bad-world"})
  void inconsistentCanonicalSlugsAreNotNormalizedIntoAuthority(String change) {
    var invalid = target(change);
    assertThat(service(List.of(world(invalid)), List.of(invalid)).browse()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"owner-namespace", "canonical-tenant", "hold-digest", "proof-digest", "terminal"})
  void changedCanonicalOwnerEvidenceCannotReuseTheRetainedSelection(String change) {
    var first = target("");
    var service = service(List.of(world(first)), List.of(first));
    var menu = service.browse().orElseThrow();
    when(reader.readCurrentTarget(first)).thenReturn(target(change));

    assertThat(service.resolve(menu, "1")).isEmpty();
  }

  @Test
  void selectionEvidenceChangeInvalidatesFingerprintEvenWithoutTargetCounterpart() {
    var first = target("");
    AtomicReference<List<WorldView>> worlds = new AtomicReference<>(List.of(world(first)));
    var service = service(GameplayWorldCatalog.forWorldSupplier(worlds::get), List.of(first));
    var menu = service.browse().orElseThrow();
    worlds.set(List.of(world(first, true)));

    assertThat(service.resolve(menu, "1")).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"realm", "namespace", "revision", "pointer", "world-display", "scope", "policy"})
  void catalogOnlyDriftCannotReuseTheRetainedTarget(String change) {
    var first = target("");
    AtomicReference<List<WorldView>> worlds = new AtomicReference<>(List.of(world(first)));
    var service = service(GameplayWorldCatalog.forWorldSupplier(worlds::get), List.of(first));
    var menu = service.browse().orElseThrow();
    worlds.set(List.of(world(target(change))));

    assertThat(service.resolve(menu, "studio/forest")).isEmpty();
    verify(reader).readCurrentTarget(first);
  }

  @Test
  void reachableInvalidCanonicalEvidenceIsEmptyButUnavailableReadsEscapeUnchanged() {
    var first = target("");
    var service = service(List.of(world(first)), List.of(first));
    var menu = service.browse().orElseThrow();
    var unavailable = new ReadUnavailableException("owner unreachable");
    when(reader.readCurrentTarget(first)).thenThrow(unavailable);
    assertThatThrownBy(() -> service.resolve(menu, "1")).isSameAs(unavailable);
    doThrow(new InvalidAuthorityException("owner stale")).when(reader).readCurrentTarget(first);
    assertThat(service.resolve(menu, "1")).isEmpty();
    when(reader.readVisiblePublicProductionTargets()).thenThrow(unavailable);
    assertThatThrownBy(service::browse).isSameAs(unavailable);
    doThrow(new InvalidAuthorityException("invalid catalog"))
        .when(reader)
        .readVisiblePublicProductionTargets();
    assertThat(service.browse()).isEmpty();
  }

  @Test
  void catalogUnavailabilityEscapesDuringBrowseAndResolve() {
    var first = target("");
    AtomicReference<Runnable> readEffect = new AtomicReference<>(() -> {});
    var service =
        service(
            GameplayWorldCatalog.forWorldSupplier(
                () -> {
                  readEffect.get().run();
                  return List.of(world(first));
                }),
            List.of(first));
    var menu = service.browse().orElseThrow();
    var unavailable = new GameplayWorldCatalog.AuthorityPointerUnavailableException("unavailable");
    readEffect.set(
        () -> {
          throw unavailable;
        });
    assertThatThrownBy(service::browse).isSameAs(unavailable);
    assertThatThrownBy(() -> service.resolve(menu, "1")).isSameAs(unavailable);
  }

  @Test
  void aSnapshotFromAnotherCompositionIsNotAccepted() {
    var first = target("");
    var source = service(List.of(world(first)), List.of(first));
    var menu = source.browse().orElseThrow();
    var other = service(List.of(world(first)), List.of(first));
    clearInvocations(reader);

    assertThat(other.resolve(menu, "1")).isEmpty();
    verifyNoInteractions(reader);
  }

  @Test
  void canonicalTargetsNeverRestoreCatalogCollisionCardinalityOrHiddenRowOmissions() {
    var first = target("");
    var second = target("second");
    var authority = mock(GameplayAdmissionPointerAuthorityService.class);
    var catalog = new GameplayWorldCatalog(authority);
    var publicPointer = pointer(first, "forest", "public", true, true, 1L);
    List<List<GameplayAdmissionPointerSnapshot>> guardedSources =
        List.of(
            // Folded global world-slug collision, even though canonical friendly pairs differ.
            List.of(publicPointer, pointer(second, "FOREST", "public", true, true, 1L)),
            // Two visible public realms for the same tenant.
            List.of(publicPointer, pointer(first, "forest", "another-public", true, true, 1L)),
            // An incomplete hidden row poisons the public tenant's authority.
            List.of(publicPointer, pointer(first, "forest", "hidden", false, false, 0L)),
            List.of(pointer(first, "forest", "public", false, true, 1L)));
    var service = service(catalog, List.of(first, second));
    for (var pointers : guardedSources) {
      when(authority.listPointers()).thenReturn(pointers);
      var menu = service.browse().orElseThrow();
      assertThat(menu.selectors()).isEmpty();
      assertThat(service.resolve(menu, "studio/forest")).isEmpty();
    }
  }

  @Test
  void pointerBackedCatalogOrderIsRetainedAndOmittedRowChangesStillInvalidateTheMenu() {
    var first = target("");
    var second = target("second");
    var authority = mock(GameplayAdmissionPointerAuthorityService.class);
    var firstPointer = pointer(first, "forest", "public", true, true, 1L);
    var secondPointer = pointer(second, "other-world", "public", true, true, 1L);
    // This hidden private realm is complete, so it does not poison the healthy first tenant.
    var hidden = pointer(first, "forest", "hidden", false, false, 1L);
    when(authority.listPointers()).thenReturn(List.of(secondPointer, firstPointer, hidden));
    var service = service(new GameplayWorldCatalog(authority), List.of(first, second));
    var menu = service.browse().orElseThrow();

    assertThat(menu.selectors()).containsExactly("other-tenant/other-world", "studio/forest");
    assertThat(service.resolve(menu, "2")).containsSame(first);
    when(authority.listPointers())
        .thenReturn(
            List.of(
                secondPointer, firstPointer, pointer(first, "forest", "hidden", false, false, 2L)));
    assertThat(service.resolve(menu, "2")).isEmpty();
    assertThat(menu.selectors()).containsExactly("other-tenant/other-world", "studio/forest");
  }

  @Test
  void actualDefaultOffCanonicalOwnerRemainsUnavailableRatherThanAnInvalidSelection() {
    var service =
        new DirectTextWorldSelectionService(
            GameplayWorldCatalog.forWorldViews(List.of(world(target("")))),
            CanonicalPlayerRouteReadService.unavailable(),
            clock);

    assertThatThrownBy(service::browse).isInstanceOf(ReadUnavailableException.class);
  }

  private DirectTextWorldSelectionService service(
      List<WorldView> worlds, List<CanonicalPlayableTarget> targets) {
    return service(GameplayWorldCatalog.forWorldViews(worlds), targets);
  }

  private DirectTextWorldSelectionService service(
      GameplayWorldCatalog catalog, List<CanonicalPlayableTarget> targets) {
    when(reader.readVisiblePublicProductionTargets()).thenReturn(targets);
    for (var target : targets) {
      if (target != null) {
        when(reader.readCurrentTarget(target)).thenReturn(target);
      }
    }
    return new DirectTextWorldSelectionService(catalog, reader, clock);
  }

  private static WorldView world(CanonicalPlayableTarget target) {
    return world(target, false);
  }

  private static WorldView world(CanonicalPlayableTarget target, boolean characterSelection) {
    return new WorldView(
        target.worldSlug(),
        target.worldDisplayName(),
        List.of(
            new RealmView(
                target.realmSlug(),
                target.realmDisplayName(),
                target.gameSessionTenantId(),
                target.gameInstanceId(),
                target.pointerVersion(),
                true,
                true,
                characterSelection,
                target.playableStateScope(),
                target.characterCreationPolicy(),
                target.catalogRevision(),
                target.realmId(),
                target.playableStateNamespaceId())));
  }

  private static GameplayAdmissionPointerSnapshot pointer(
      CanonicalPlayableTarget target,
      String world,
      String realm,
      boolean visible,
      boolean publicProduction,
      long catalogRevision) {
    return new GameplayAdmissionPointerSnapshot(
        world,
        target.worldDisplayName(),
        realm,
        target.realmDisplayName(),
        target.gameSessionTenantId(),
        target.gameInstanceId(),
        target.pointerVersion(),
        visible,
        publicProduction,
        false,
        target.playableStateScope(),
        target.characterCreationPolicy(),
        catalogRevision,
        target.realmId(),
        target.playableStateNamespaceId());
  }

  /** Controlled owner evidence uses real record invariants, never numeric-to-UUID derivation. */
  private static CanonicalPlayableTarget target(String change) {
    boolean second = "second".equals(change) || "same-tenant".equals(change);
    boolean otherWorld = second || "duplicate-runtime".equals(change);
    String tenantSlug =
        switch (change) {
          case "second", "duplicate-runtime", "duplicate-world", "tenant-slug" -> "other-tenant";
          case "uppercase-tenant" -> "STUDIO";
          case "bad-tenant" -> "bad/tenant";
          default -> "studio";
        };
    String worldSlug =
        switch (change) {
          case "uppercase-world" -> "FOREST";
          case "bad-world" -> "bad/world";
          case "world" -> "changed-world";
          default -> otherWorld ? "other-world" : "forest";
        };
    return new CanonicalPlayableTarget(
        "owner-namespace".equals(change) ? "another-owner" : "gameplay",
        tenantSlug,
        second || "canonical-tenant".equals(change) || "duplicate-world".equals(change)
            ? OTHER
            : TENANT,
        worldSlug,
        "world-display".equals(change) ? "Changed Forest" : "Forest",
        second || "realm".equals(change) ? OTHER : REALM,
        "realm-slug".equals(change) ? "changed-realm" : "public",
        "realm-display".equals(change) ? "Changed Realm" : "Live Realm",
        second || "tenant".equals(change) || "duplicate-pair".equals(change) ? 909L : 101L,
        second
                || "runtime".equals(change)
                || "duplicate-pair".equals(change)
                || "duplicate-world".equals(change)
            ? 808L
            : 202L,
        second || "namespace".equals(change) ? OTHER : NAMESPACE,
        "scope".equals(change) ? "ISOLATED" : "SHARED",
        second || "canonical-instance".equals(change) ? OTHER : INSTANCE,
        "canonical-version".equals(change) ? OTHER : VERSION,
        "runtime-version".equals(change) ? 304L : 303L,
        "revision".equals(change) ? 2L : 1L,
        "pointer".equals(change) ? 8L : 7L,
        ("pointer-digest".equals(change) ? "e" : "d").repeat(64),
        "epoch".equals(change) ? 83L : 82L,
        "request-id".equals(change) ? "another-open" : "original-open",
        ("request-digest".equals(change) ? "b" : "a").repeat(64),
        "origin".equals(change) ? OriginKind.EXPECT_CLOSED : OriginKind.NO_PRIOR_POINTER,
        "origin".equals(change) ? Long.valueOf(6L) : null,
        "hold".equals(change) ? OTHER : HOLD,
        "fence".equals(change) ? OTHER : FENCE,
        "sha256:" + ("hold-digest".equals(change) ? "b" : "c").repeat(64),
        "audit".equals(change) ? 28L : 27L,
        "sha256:" + ("proof-digest".equals(change) ? "e" : "f").repeat(64),
        Outcome.COMMITTED,
        false,
        "terminal".equals(change) ? NOW.minusSeconds(2) : NOW.minusSeconds(1),
        "policy".equals(change) ? "PRESEEDED_ONLY" : "ALLOW_NEW");
  }

  private static final class MutableClock extends Clock {
    private Instant now = NOW;

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(now, zone);
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
