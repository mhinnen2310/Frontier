package nl.frontier.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class DialogScreenCatalogTest {
  @Test
  void everyRequestedScreenHasReachableValidActionsAndBackNavigation() {
    assertEquals(15, FrontierUi.Screen.values().length);
    Set<String> rootTargets =
        DialogScreenCatalog.actions(FrontierUi.Screen.FRONTIER).stream()
            .map(DialogScreenCatalog.Action::command)
            .collect(Collectors.toSet());

    for (FrontierUi.Screen screen : FrontierUi.Screen.values()) {
      var actions = DialogScreenCatalog.actions(screen);
      assertFalse(actions.isEmpty(), screen + " has no actions");
      assertTrue(
          actions.stream().allMatch(action -> action.command().startsWith("frontier ")),
          screen + " contains a non-Frontier action");
      if (screen != FrontierUi.Screen.FRONTIER) {
        assertTrue(
            rootTargets.contains("frontier menu " + screen.name().toLowerCase()),
            screen + " is unreachable from root");
        assertTrue(
            actions.stream().anyMatch(action -> action.command().equals("frontier menu frontier")),
            screen + " has no back navigation");
      }
    }
  }

  @Test
  void catalogContainsEveryRoadmapAreaAndNoRemovedPlaceholderCommands() {
    Set<String> titles =
        Arrays.stream(FrontierUi.Screen.values())
            .map(DialogScreenCatalog::title)
            .collect(Collectors.toSet());
    assertTrue(
        titles.containsAll(
            Set.of(
                "Frontier",
                "Settlement",
                "Architect & Buildings",
                "Districts",
                "Kingdom",
                "Treasury",
                "Repair",
                "War",
                "Market",
                "Workers & Population",
                "Contracts",
                "Infrastructure",
                "History",
                "Reports",
                "Settings")));
    var commands =
        Arrays.stream(FrontierUi.Screen.values())
            .flatMap(screen -> DialogScreenCatalog.actions(screen).stream())
            .map(DialogScreenCatalog.Action::command)
            .toList();
    assertFalse(commands.contains("frontier war status"));
    assertFalse(commands.contains("frontier repair status"));
    assertFalse(commands.contains("frontier market browse"));
    assertTrue(commands.stream().anyMatch(command -> command.contains("$(amount)")));
    assertTrue(commands.stream().anyMatch(command -> command.contains("$(target_city)")));
  }
}
