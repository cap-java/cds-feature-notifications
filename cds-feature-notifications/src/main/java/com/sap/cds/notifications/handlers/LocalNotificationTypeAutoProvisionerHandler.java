/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package com.sap.cds.notifications.handlers;

import cds.gen.notificationtypeproviderservice.DeliveryChannels;
import cds.gen.notificationtypeproviderservice.NotificationTypes;
import cds.gen.notificationtypeproviderservice.Translations;
import com.sap.cds.notifications.assemblers.NotificationTypeAssembler;
import com.sap.cds.services.application.ApplicationLifecycleService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.runtime.CdsRuntime;
import java.util.List;
import java.util.stream.Collectors;
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
    try {
      provisionNotificationTypes();
    } catch (Exception e) {
      logger.error("NotificationType auto-provisioning failed", e);
    }
  }

  private void provisionNotificationTypes() {
    List<NotificationTypes> notificationTypes = notificationTypeBuilder.buildAllNotificationTypes();

    if (notificationTypes.isEmpty()) {
      logger.info("No NotificationTypes found in CDS model");
      return;
    }

    logger.info("╔══════════════════════════════════════════════════════════════╗");
    logger.info("║  NOTIFICATION TYPES (Local Mode — Not Sent to ANS)          ║");
    logger.info("╚══════════════════════════════════════════════════════════════╝");

    for (NotificationTypes notificationType : notificationTypes) {
      logNotificationType(notificationType);
    }
  }

  private void logNotificationType(NotificationTypes notificationType) {
    int translationCount =
        notificationType.getTranslations() != null ? notificationType.getTranslations().size() : 0;
    int channelCount =
        notificationType.getDeliveryChannels() != null
            ? notificationType.getDeliveryChannels().size()
            : 0;

    logger.info("┌──────────────────────────────────────────────────────────────┐");
    logger.info(
        "│ Type: '{}' | translations={} | channels={}",
        notificationType.getNotificationTypeKey(),
        translationCount,
        channelCount);
    logger.info("├──────────────────────────────────────────────────────────────┤");

    if (notificationType.getTranslations() != null
        && !notificationType.getTranslations().isEmpty()) {
      List<Translations> translations = notificationType.getTranslations();
      String languages =
          translations.stream().map(Translations::getLanguage).collect(Collectors.joining(", "));
      Translations display =
          translations.stream()
              .filter(t -> "en".equals(t.getLanguage()))
              .findFirst()
              .orElse(translations.get(0));
      logger.info("│  displayName: {}  [{}]", display.getDisplayName(), languages);
      logger.info("│  groupTitle:  {}  [{}]", display.getGroupTitle(), languages);
    }

    if (notificationType.getDeliveryChannels() != null) {
      logger.info("│  Delivery Channels:");
      for (DeliveryChannels channel : notificationType.getDeliveryChannels()) {
        logger.info(
            "│    - {} (enabled={}, defaultPreference={})",
            channel.getType(),
            channel.getEnabled(),
            channel.getDefaultPreference());
      }
    }
    logger.info("└──────────────────────────────────────────────────────────────┘");
  }
}
