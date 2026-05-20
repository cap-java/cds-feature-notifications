/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package com.sap.cds.notifications.handlers;

import cds.gen.notificationtypeproviderservice.DeliveryChannels;
import cds.gen.notificationtypeproviderservice.NotificationTypes;
import cds.gen.notificationtypeproviderservice.Templates;
import com.sap.cds.services.application.ApplicationLifecycleService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.runtime.CdsRuntime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ServiceName(ApplicationLifecycleService.DEFAULT_NAME)
public class LocalNotificationTypeAutoProvisionerHandler implements EventHandler {

  private static final Logger logger =
      LoggerFactory.getLogger(LocalNotificationTypeAutoProvisionerHandler.class);

  private final NotificationTypeBuilder notificationTypeBuilder;

  public LocalNotificationTypeAutoProvisionerHandler(CdsRuntime runtime) {
    this.notificationTypeBuilder = new NotificationTypeBuilder(runtime);
  }

  @On(event = ApplicationLifecycleService.EVENT_APPLICATION_PREPARED)
  public void onApplicationPrepared() {
    logger.info(
        "Auto-provisioning NotificationTypes from CDS annotations (LOCAL MODE - Logging Only)...");
    try {
      provisionNotificationTypes();
      logger.info("Auto-provisioning completed (LOCAL MODE)");
    } catch (Exception e) {
      logger.error("Auto-provisioning failed", e);
    }
  }

  private void provisionNotificationTypes() {
    List<NotificationTypes> notificationTypes = notificationTypeBuilder.buildAllNotificationTypes();

    for (NotificationTypes notificationType : notificationTypes) {
      logNotificationType(notificationType);
    }
  }

  private void logNotificationType(NotificationTypes notificationType) {
    // Log to console instead of sending to ANS
    System.out.println("\n===============================================================");
    System.out.println("NotificationType (Local Mode - Not Sent to ANS)");
    System.out.println("  Key: " + notificationType.getNotificationTypeKey());
    System.out.println("  Version: " + notificationType.getNotificationTypeVersion());
    System.out.println(
        "  Templates ("
            + (notificationType.getTemplates() != null ? notificationType.getTemplates().size() : 0)
            + "):");

    if (notificationType.getTemplates() != null) {
      for (Templates template : notificationType.getTemplates()) {
        System.out.println(
            "    - Language: "
                + template.getLanguage()
                + "\n"
                + "      Public Title: "
                + template.getTemplatePublic()
                + "\n"
                + "      Sensitive Title: "
                + template.getTemplateSensitive()
                + "\n"
                + "      Grouped Title: "
                + template.getTemplateGrouped()
                + "\n"
                + "      Subtitle: "
                + template.getSubtitle()
                + "\n"
                + "      Email Subject: "
                + template.getEmailSubject()
                + "\n"
                + "      Email HTML: "
                + (template.getEmailHtml() != null
                    ? template
                            .getEmailHtml()
                            .substring(0, Math.min(100, template.getEmailHtml().length()))
                        + "..."
                    : "null"));
      }
    }

    if (notificationType.getDeliveryChannels() != null) {
      System.out.println(
          "  Delivery Channels (" + notificationType.getDeliveryChannels().size() + "):");
      for (DeliveryChannels channel : notificationType.getDeliveryChannels()) {
        System.out.println(
            "    - Type: "
                + channel.getType()
                + ", Enabled: "
                + channel.getEnabled()
                + ", DefaultPreference: "
                + channel.getDefaultPreference());
      }
    }

    System.out.println("===============================================================\n");

    logger.info(
        "NotificationType '{}' logged (LOCAL MODE - not sent to ANS)",
        notificationType.getNotificationTypeKey());
  }
}
