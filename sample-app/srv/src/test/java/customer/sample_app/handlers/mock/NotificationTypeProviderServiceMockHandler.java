/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package customer.sample_app.handlers.mock;

import cds.gen.notificationtypeproviderservice.NotificationTypes;
import cds.gen.notificationtypeproviderservice.NotificationTypes_;
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
 * Mock handler for NotificationTypeProviderService. Intercepts CREATE, READ and UPDATE operations
 * to prevent actual remote HTTP calls.
 */
@Component
@Profile("test")
@ServiceName("NotificationTypeProviderService")
public class NotificationTypeProviderServiceMockHandler implements EventHandler {

  private static final Logger logger =
      LoggerFactory.getLogger(NotificationTypeProviderServiceMockHandler.class);

  // In-memory storage for notification types
  private static final Map<String, NotificationTypes> notificationTypeStore =
      new ConcurrentHashMap<>();

  // Secondary index for quick lookup by key and version
  private static final Map<String, NotificationTypes> notificationTypeByKeyVersion =
      new ConcurrentHashMap<>();

  // Tracks how many times each NotificationTypeKey has been updated
  private static final Map<String, AtomicInteger> updateCountByKey = new ConcurrentHashMap<>();

  @On(event = CqnService.EVENT_CREATE, entity = NotificationTypes_.CDS_NAME)
  public void interceptCreate(CdsCreateEventContext context) {
    logger.info(
        "MockHandler intercepting NotificationTypes CREATE - {} entries",
        context.getCqn().entries().size());

    List<Map<String, Object>> resultEntries = new ArrayList<>();

    // Extract notification types from CQN entries
    context
        .getCqn()
        .entries()
        .forEach(
            entry -> {
              logger.info("NotificationType entry data: {}", entry);

              NotificationTypes notificationType = NotificationTypes.create();
              entry.forEach(notificationType::put);

              // Generate ID if not present
              if (notificationType.getNotificationTypeId() == null) {
                notificationType.setNotificationTypeId(UUID.randomUUID().toString());
                entry.put("NotificationTypeId", notificationType.getNotificationTypeId());
              }

              // Store notification type by ID
              notificationTypeStore.put(notificationType.getNotificationTypeId(), notificationType);

              // Store by key+version for easy lookup
              String keyVersion =
                  notificationType.getNotificationTypeKey()
                      + ":"
                      + notificationType.getNotificationTypeVersion();
              notificationTypeByKeyVersion.put(keyVersion, notificationType);

              logger.info(
                  "Mock NotificationTypeProviderService: Stored notification type with ID: {}, Key: {}, Version: {}",
                  notificationType.getNotificationTypeId(),
                  notificationType.getNotificationTypeKey(),
                  notificationType.getNotificationTypeVersion());

              // Add to result
              resultEntries.add(entry);
            });

    // Set result and mark as completed to prevent RemoteODataHandler from executing
    context.setResult(resultEntries);
    context.setCompleted();
  }

  @On(event = CqnService.EVENT_READ, entity = NotificationTypes_.CDS_NAME)
  public void interceptRead(CdsReadEventContext context) {
    logger.info("MockHandler intercepting NotificationTypes READ");

    List<Map<String, Object>> results = new ArrayList<>();
    for (NotificationTypes nt : notificationTypeStore.values()) {
      Map<String, Object> row = new LinkedHashMap<>();
      nt.forEach(row::put);
      results.add(row);
    }

    logger.info("MockHandler returning {} notification types for READ", results.size());
    context.setResult(results);
    context.setCompleted();
  }

  @On(event = CqnService.EVENT_UPDATE, entity = NotificationTypes_.CDS_NAME)
  public void interceptUpdate(CdsUpdateEventContext context) {
    logger.info("MockHandler intercepting NotificationTypes UPDATE");

    context
        .getCqn()
        .entries()
        .forEach(
            entry -> {
              NotificationTypes updated = NotificationTypes.create();
              entry.forEach(updated::put);

              String id = updated.getNotificationTypeId();
              if (id != null && notificationTypeStore.containsKey(id)) {
                notificationTypeStore.put(id, updated);

                String keyVersion =
                    updated.getNotificationTypeKey() + ":" + updated.getNotificationTypeVersion();
                notificationTypeByKeyVersion.put(keyVersion, updated);

                // Track update count per key
                String key = updated.getNotificationTypeKey();
                updateCountByKey.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();

                logger.info("MockHandler updated notification type: Key={}, ID={}", key, id);
              } else {
                logger.warn("MockHandler UPDATE: no existing type with ID={}", id);
              }
            });

    context.setResult(Collections.emptyList());
    context.setCompleted();
  }

  /**
   * Retrieves a notification type by ID for testing assertions.
   *
   * @param id The notification type ID
   * @return The notification type or null if not found
   */
  public static NotificationTypes getNotificationTypeById(String id) {
    return notificationTypeStore.get(id);
  }

  /**
   * Retrieves a notification type by key and version.
   *
   * @param notificationTypeKey The notification type key
   * @param notificationTypeVersion The notification type version
   * @return The notification type or null if not found
   */
  public static NotificationTypes getNotificationTypeByKeyVersion(
      String notificationTypeKey, String notificationTypeVersion) {
    String keyVersion = notificationTypeKey + ":" + notificationTypeVersion;
    return notificationTypeByKeyVersion.get(keyVersion);
  }

  /**
   * Retrieves all stored notification types.
   *
   * @return All notification types
   */
  public static List<NotificationTypes> getAllNotificationTypes() {
    return new ArrayList<>(notificationTypeStore.values());
  }

  /** Clears all stored notification types. Useful for test cleanup. */
  public static void clearAllNotificationTypes() {
    notificationTypeStore.clear();
    notificationTypeByKeyVersion.clear();
    updateCountByKey.clear();
    logger.info("Mock NotificationTypeProviderService: Cleared all notification types");
  }

  /**
   * Gets the count of stored notification types.
   *
   * @return The number of notification types
   */
  public static int getNotificationTypeCount() {
    return notificationTypeStore.size();
  }

  /**
   * Checks if a notification type exists by ID.
   *
   * @param id The notification type ID
   * @return true if the notification type exists
   */
  public static boolean hasNotificationType(String id) {
    return notificationTypeStore.containsKey(id);
  }

  /**
   * Checks if a notification type exists by key and version.
   *
   * @param notificationTypeKey The notification type key
   * @param notificationTypeVersion The notification type version
   * @return true if the notification type exists
   */
  public static boolean hasNotificationTypeByKeyVersion(
      String notificationTypeKey, String notificationTypeVersion) {
    String keyVersion = notificationTypeKey + ":" + notificationTypeVersion;
    return notificationTypeByKeyVersion.containsKey(keyVersion);
  }

  /**
   * Gets the number of times a notification type was updated (via UPDATE event).
   *
   * @param notificationTypeKey The notification type key
   * @return The number of updates, 0 if never updated
   */
  public static int getUpdateCount(String notificationTypeKey) {
    AtomicInteger count = updateCountByKey.get(notificationTypeKey);
    return count != null ? count.get() : 0;
  }

  /** Gets the total number of updates across all notification types. */
  public static int getTotalUpdateCount() {
    return updateCountByKey.values().stream().mapToInt(AtomicInteger::get).sum();
  }
}
