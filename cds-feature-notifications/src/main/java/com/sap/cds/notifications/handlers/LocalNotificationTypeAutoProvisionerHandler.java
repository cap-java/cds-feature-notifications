/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package com.sap.cds.notifications.handlers;

import cds.gen.notificationtypeproviderservice.DeliveryChannels;
import cds.gen.notificationtypeproviderservice.NotificationTypes;
import cds.gen.notificationtypeproviderservice.Templates;
import com.sap.cds.notifications.assemblers.NotificationTypeAssembler;
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

  private final NotificationTypeAssembler notificationTypeBuilder;

  public LocalNotificationTypeAutoProvisionerHandler(CdsRuntime runtime) {
    this.notificationTypeBuilder = new NotificationTypeAssembler(runtime);
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
    logger.info("┌──────────────────────────────────────────────────────────────┐");
    logger.info("│ NotificationType (Local Mode - Not Sent to ANS)");
    logger.info("│   Key:     {}", notificationType.getNotificationTypeKey());
    logger.info("│   Version: {}", notificationType.getNotificationTypeVersion());
    logger.info(
        "│   Templates ({}):",
        notificationType.getTemplates() != null ? notificationType.getTemplates().size() : 0);

    if (notificationType.getTemplates() != null) {
      for (Templates template : notificationType.getTemplates()) {
        logger.info("│     - Language:        {}", template.getLanguage());
        logger.info("│       Public Title:    {}", template.getTemplatePublic());
        logger.info("│       Sensitive Title: {}", template.getTemplateSensitive());
        logger.info("│       Grouped Title:   {}", template.getTemplateGrouped());
        logger.info("│       Subtitle:        {}", template.getSubtitle());
        logger.info("│       Email Subject:   {}", template.getEmailSubject());
        if (template.getEmailHtml() != null) {
          String preview =
              template.getEmailHtml().substring(0, Math.min(100, template.getEmailHtml().length()))
                  + "...";
          logger.info("│       Email HTML:      {}", preview);
        }
      }
    }

    if (notificationType.getDeliveryChannels() != null) {
      logger.info("│   Delivery Channels ({}):", notificationType.getDeliveryChannels().size());
      for (DeliveryChannels channel : notificationType.getDeliveryChannels()) {
        logger.info(
            "│     - Type: {}, Enabled: {}, DefaultPreference: {}",
            channel.getType(),
            channel.getEnabled(),
            channel.getDefaultPreference());
      }
    }
    logger.info("└──────────────────────────────────────────────────────────────┘");

    logger.info(
        "NotificationType '{}' logged (LOCAL MODE - not sent to ANS)",
        notificationType.getNotificationTypeKey());
  }
}
