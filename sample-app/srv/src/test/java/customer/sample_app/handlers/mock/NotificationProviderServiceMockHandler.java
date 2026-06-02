/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package customer.sample_app.handlers.mock;

import cds.gen.notificationproviderservice.Notifications;
import cds.gen.notificationproviderservice.Notifications_;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.cds.CdsCreateEventContext;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Mock handler for NotificationProviderService. Intercepts CREATE operations to prevent actual
 * remote HTTP calls.
 */
@Component
@Profile("test")
@ServiceName("NotificationProviderService")
public class NotificationProviderServiceMockHandler implements EventHandler {

  private static final Logger logger =
      LoggerFactory.getLogger(NotificationProviderServiceMockHandler.class);

  // In-memory storage for notifications
  private static final Map<String, Notifications> notificationStore = new ConcurrentHashMap<>();

  @On(event = CqnService.EVENT_CREATE, entity = Notifications_.CDS_NAME)
  public void interceptCreate(CdsCreateEventContext context) {
    logger.debug("MockHandler intercepting CREATE - {} entries", context.getCqn().entries().size());

    List<Map<String, Object>> resultEntries = new ArrayList<>();

    // Extract notifications from CQN entries
    context
        .getCqn()
        .entries()
        .forEach(
            entry -> {
              logger.debug("Entry data: {}", entry);

              Notifications notification = Notifications.create();
              entry.forEach(notification::put);

              // Validate that notification type exists
              String notificationTypeKey = notification.getNotificationTypeKey();
              String notificationTypeVersion = notification.getNotificationTypeVersion();

              if (notificationTypeKey == null || notificationTypeKey.isEmpty()) {
                String errorMsg = "Notification type key is required";
                logger.error(errorMsg);
                throw new ServiceException("NOTIFICATION_TYPE_REQUIRED", errorMsg);
              }

              boolean typeExists =
                  NotificationTypeProviderServiceMockHandler.hasNotificationTypeByKeyVersion(
                      notificationTypeKey, notificationTypeVersion);

              if (!typeExists) {
                String errorMsg =
                    String.format(
                        "Notification type '%s' with version '%s' does not exist. "
                            + "Notification types must be auto-provisioned at application startup.",
                        notificationTypeKey, notificationTypeVersion);
                logger.error(errorMsg);
                throw new ServiceException("NOTIFICATION_TYPE_NOT_FOUND", errorMsg);
              }

              // Generate ID if not present
              if (notification.getId() == null) {
                notification.setId(UUID.randomUUID().toString());
                entry.put("Id", notification.getId());
              }

              // Store notification
              notificationStore.put(notification.getId(), notification);
              logger.debug(
                  "Mock NotificationProviderService: Stored notification with ID: {}, TypeKey: {}",
                  notification.getId(),
                  notification.getNotificationTypeKey());

              // Add to result
              resultEntries.add(entry);
            });

    // Set result and mark as completed to prevent RemoteODataHandler from executing
    context.setResult(resultEntries);
    context.setCompleted();
  }

  /**
   * Retrieves a notification by ID for testing assertions.
   *
   * @param id The notification ID
   * @return The notification or null if not found
   */
  public static Notifications getNotificationById(String id) {
    return notificationStore.get(id);
  }

  /**
   * Retrieves all notifications with the given notification type key.
   *
   * @param notificationTypeKey The notification type key
   * @return List of matching notifications
   */
  public static List<Notifications> getNotificationsByTypeKey(String notificationTypeKey) {
    return notificationStore.values().stream()
        .filter(n -> notificationTypeKey.equals(n.getNotificationTypeKey()))
        .toList();
  }

  /**
   * Retrieves all stored notifications.
   *
   * @return All notifications
   */
  public static List<Notifications> getAllNotifications() {
    return new ArrayList<>(notificationStore.values());
  }

  /** Clears all stored notifications. Useful for test cleanup. */
  public static void clearAllNotifications() {
    notificationStore.clear();
    logger.debug("Mock NotificationProviderService: Cleared all notifications");
  }

  /**
   * Gets the count of stored notifications.
   *
   * @return The number of notifications
   */
  public static int getNotificationCount() {
    return notificationStore.size();
  }

  /**
   * Checks if a notification exists by ID.
   *
   * @param id The notification ID
   * @return true if the notification exists
   */
  public static boolean hasNotification(String id) {
    return notificationStore.containsKey(id);
  }
}
