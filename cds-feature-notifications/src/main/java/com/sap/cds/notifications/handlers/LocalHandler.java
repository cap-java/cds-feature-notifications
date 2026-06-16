/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package com.sap.cds.notifications.handlers;

import cds.gen.notificationproviderservice.NotificationProperties;
import cds.gen.notificationproviderservice.Notifications;
import cds.gen.notificationproviderservice.Recipients;

import com.sap.cds.notifications.builders.NotificationBuilder;
import com.sap.cds.services.EventContext;
import com.sap.cds.services.cds.ApplicationService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.runtime.CdsRuntime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ServiceName(value = "*", type = ApplicationService.class)
public class LocalHandler implements EventHandler {

  private static final Logger logger = LoggerFactory.getLogger(LocalHandler.class);
  private final NotificationBuilder notificationBuilder;

  public LocalHandler(CdsRuntime runtime) {
    this.notificationBuilder = new NotificationBuilder(runtime);
  }

  @On(event = "*")
  public void postNotifications(EventContext context) {
    List<NotificationBuilder.NotificationBuildResult> results =
        notificationBuilder.buildNotifications(context);
    if (results.isEmpty()) {
      return;
    }

    String eventName = results.get(0).eventName();
    logger.info(
        "=== Processing {} notification(s) (LOCAL MODE) for event: {} ===",
        results.size(),
        eventName);

    for (int i = 0; i < results.size(); i++) {
      Notifications notification = results.get(i).notification();

      logger.info("---------------------------------------------------------------");
      if (results.size() > 1) {
        logger.info("Notification {}/{} (Local Mode - Not Sent to ANS)", i + 1, results.size());
      } else {
        logger.info("Notification (Local Mode - Not Sent to ANS)");
      }
      logger.info("  Event: {}", eventName);
      logger.info("  NotificationTypeKey: {}", notification.getNotificationTypeKey());
      logger.info("  NotificationTypeVersion: {}", notification.getNotificationTypeVersion());
      logger.info(
          "  Priority: {}",
          notification.getPriority() != null ? notification.getPriority() : "NEUTRAL");
      logger.info("  Recipients:");
      for (Recipients recipient : notification.getRecipients()) {
        String recipientInfo =
            recipient.getRecipientId() != null
                ? recipient.getRecipientId()
                : "GlobalUserId=" + recipient.getGlobalUserId();
        logger.info("    - {}", recipientInfo);
      }
      logger.info("  Properties ({}):", notification.getProperties().size());
      for (NotificationProperties prop : notification.getProperties()) {
        logger.info("    - {}: {}", prop.getKey(), prop.getValue());
      }
      logger.info("---------------------------------------------------------------");
    }

    logger.info("{} notification(s) logged successfully for event: {}", results.size(), eventName);
    context.setCompleted();
  }
}
