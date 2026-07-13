package nl.frontier.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.junit.jupiter.api.Test;

class InfrastructureDirtyListenerContractTest {
  @Test
  void observesAllRouteChangingEventFamiliesAfterCancellationDecisions() {
    Set<String> handlers =
        java.util.Arrays.stream(InfrastructureDirtyListener.class.getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(EventHandler.class))
            .map(Method::getName)
            .collect(Collectors.toSet());
    assertEquals(
        Set.of(
            "onPlace",
            "onBreak",
            "onBurn",
            "onFlow",
            "onBlockExplode",
            "onEntityExplode",
            "onPistonExtend",
            "onPistonRetract"),
        handlers);
    for (Method method : InfrastructureDirtyListener.class.getDeclaredMethods()) {
      EventHandler handler = method.getAnnotation(EventHandler.class);
      if (handler == null) continue;
      assertEquals(EventPriority.MONITOR, handler.priority());
      assertTrue(handler.ignoreCancelled());
    }
  }
}
