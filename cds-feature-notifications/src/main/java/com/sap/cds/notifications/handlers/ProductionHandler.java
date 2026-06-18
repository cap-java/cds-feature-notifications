/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package com.sap.cds.notifications.handlers;

import cds.gen.notificationproviderservice.NotificationProviderService;
import cds.gen.notificationproviderservice.Notifications;
import cds.gen.notificationproviderservice.Notifications_;
import com.sap.cds.notifications.assemblers.NotificationAssembler;
import com.sap.cds.ql.Insert;
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
public class ProductionHandler implements EventHandler {

  private static final Logger logger = LoggerFactory.getLogger(ProductionHandler.class);
  private final NotificationProviderService notificationProviderService;
  private final NotificationAssembler notificationBuilder;

  public ProductionHandler(
      NotificationProviderService notificationProviderService, CdsRuntime runtime) {
    this.notificationProviderService = notificationProviderService;
    this.notificationBuilder = new NotificationAssembler(runtime);
  }

  @On(event = "*")
  public void postNotifications(EventContext context) {
    List<NotificationAssembler.NotificationBuildResult> results =
        notificationBuilder.buildNotifications(context);
    if (results.isEmpty()) {
      return;
    }

    String eventName = results.get(0).eventName();
    logger.debug("=== Processing {} notification(s) for event: {} ===", results.size(), eventName);

    int successCount = 0;
    Exception firstError = null;

    for (int i = 0; i < results.size(); i++) {
      Notifications notification = results.get(i).notification();
      try {
        notificationProviderService.run(Insert.into(Notifications_.CDS_NAME).entry(notification));
        successCount++;
      } catch (Exception e) {
        logger.error(
            "Failed to send notification {}/{} for event '{}' (recipients: {}): {}",
            i + 1,
            results.size(),
            eventName,
            notification.getRecipients(),
            e.getMessage(),
            e);
        if (firstError == null) {
          firstError = e;
        } else {
          firstError.addSuppressed(e);
        }
      }
    }

    logger.debug(
        "Sent {}/{} notification(s) for event: {}", successCount, results.size(), eventName);

    if (firstError != null) {
      throw new RuntimeException(
          String.format(
              "Failed to send %d/%d notification(s) for event '%s': %s",
              results.size() - successCount, results.size(), eventName, firstError.getMessage()),
          firstError);
    }

    context.setCompleted();
  }
}
