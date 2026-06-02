/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package customer.sample_app.handlers.mock;

import cds.gen.notificationtemplateproviderservice.NotificationTemplates;
import cds.gen.notificationtemplateproviderservice.NotificationTemplates_;
import com.sap.cds.services.cds.CdsCreateEventContext;
import com.sap.cds.services.cds.CdsReadEventContext;
import com.sap.cds.services.cds.CdsUpdateEventContext;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Mock handler for NotificationTemplateProviderService. Intercepts CREATE, READ and UPDATE
 * operations to prevent actual remote HTTP calls during integration tests.
 */
@Component
@Profile("test")
@ServiceName("NotificationTemplateProviderService")
public class NotificationTemplateProviderServiceMockHandler implements EventHandler {

  private static final Logger logger =
      LoggerFactory.getLogger(NotificationTemplateProviderServiceMockHandler.class);

  // In-memory storage for notification templates 
  private static final Map<String, NotificationTemplates> templateStore =
      new ConcurrentHashMap<>();

  // Tracks how many times each template key has been updated
  private static final Map<String, AtomicInteger> updateCountByKey = new ConcurrentHashMap<>();

  @On(event = CqnService.EVENT_CREATE, entity = NotificationTemplates_.CDS_NAME)
  public void interceptCreate(CdsCreateEventContext context) {
    logger.debug(
        "MockHandler intercepting NotificationTemplates CREATE - {} entries",
        context.getCqn().entries().size());

    List<Map<String, Object>> resultEntries = new ArrayList<>();

    context
        .getCqn()
        .entries()
        .forEach(
            entry -> {
              logger.debug("NotificationTemplate entry data: {}", entry);

              NotificationTemplates template = NotificationTemplates.create();
              entry.forEach(template::put);

              String key = template.getKey();
              if (key == null || key.isEmpty()) {
                throw new IllegalArgumentException("NotificationTemplate Key is required");
              }

              // Store template by key
              templateStore.put(key, template);

              logger.debug(
                  "Mock NotificationTemplateProviderService: Stored template with Key: {}, Visibility: {}",
                  key,
                  template.getVisibility());

              resultEntries.add(entry);
            });

    context.setResult(resultEntries);
    context.setCompleted();
  }

  @On(event = CqnService.EVENT_READ, entity = NotificationTemplates_.CDS_NAME)
  public void interceptRead(CdsReadEventContext context) {
    logger.debug("MockHandler intercepting NotificationTemplates READ");

    List<Map<String, Object>> results = new ArrayList<>();
    for (NotificationTemplates template : templateStore.values()) {
      Map<String, Object> row = new LinkedHashMap<>();
      template.forEach(row::put);
      results.add(row);
    }

    logger.debug("MockHandler returning {} notification templates for READ", results.size());
    context.setResult(results);
    context.setCompleted();
  }

  @On(event = CqnService.EVENT_UPDATE, entity = NotificationTemplates_.CDS_NAME)
  public void interceptUpdate(CdsUpdateEventContext context) {
    logger.debug("MockHandler intercepting NotificationTemplates UPDATE");

    context
        .getCqn()
        .entries()
        .forEach(
            entry -> {
              NotificationTemplates updated = NotificationTemplates.create();
              entry.forEach(updated::put);

              String key = updated.getKey();
              if (key != null && templateStore.containsKey(key)) {
                templateStore.put(key, updated);
                updateCountByKey
                    .computeIfAbsent(key, k -> new AtomicInteger(0))
                    .incrementAndGet();
                logger.debug("MockHandler updated notification template: Key={}", key);
              } else {
                logger.warn("MockHandler UPDATE: no existing template with Key={}", key);
              }
            });

    context.setResult(Collections.emptyList());
    context.setCompleted();
  }

  // ──────────────────────────────────────────────────────────────
  // Static helpers for test assertions
  // ──────────────────────────────────────────────────────────────

  /**
   * Retrieves a template by key.
   *
   * @param key The template key
   * @return The template or null if not found
   */
  public static NotificationTemplates getTemplateByKey(String key) {
    return templateStore.get(key);
  }

  /**
   * Retrieves all stored templates.
   *
   * @return All templates
   */
  public static List<NotificationTemplates> getAllTemplates() {
    return new ArrayList<>(templateStore.values());
  }

  /** Clears all stored templates. Useful for test cleanup. */
  public static void clearAllTemplates() {
    templateStore.clear();
    updateCountByKey.clear();
    logger.debug("Mock NotificationTemplateProviderService: Cleared all templates");
  }

  /**
   * Gets the count of stored templates.
   *
   * @return The number of templates
   */
  public static int getTemplateCount() {
    return templateStore.size();
  }

  /**
   * Checks if a template exists by key.
   *
   * @param key The template key
   * @return true if the template exists
   */
  public static boolean hasTemplate(String key) {
    return templateStore.containsKey(key);
  }

  /**
   * Gets the number of times a template was updated.
   *
   * @param key The template key
   * @return The number of updates, 0 if never updated
   */
  public static int getUpdateCount(String key) {
    AtomicInteger count = updateCountByKey.get(key);
    return count != null ? count.get() : 0;
  }
}
