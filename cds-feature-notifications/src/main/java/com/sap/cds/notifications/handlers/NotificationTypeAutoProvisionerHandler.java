/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package com.sap.cds.notifications.handlers;

import cds.gen.notificationtypeproviderservice.NotificationTypeProviderService;
import cds.gen.notificationtypeproviderservice.NotificationTypes;
import cds.gen.notificationtypeproviderservice.NotificationTypes_;
import com.sap.cds.notifications.assemblers.NotificationTypeAssembler;
import com.sap.cds.ql.Delete;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.services.application.ApplicationLifecycleService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.runtime.CdsRuntime;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ServiceName(ApplicationLifecycleService.DEFAULT_NAME)
public class NotificationTypeAutoProvisionerHandler implements EventHandler {

  private static final Logger logger =
      LoggerFactory.getLogger(NotificationTypeAutoProvisionerHandler.class);

  private final NotificationTypeAssembler notificationTypeBuilder;
  private final NotificationTypeProviderService notificationTypeProviderService;

  public NotificationTypeAutoProvisionerHandler(
      CdsRuntime runtime, NotificationTypeProviderService notificationTypeProviderService) {
    this.notificationTypeBuilder = new NotificationTypeAssembler(runtime);
    this.notificationTypeProviderService = notificationTypeProviderService;
  }

  @On(event = ApplicationLifecycleService.EVENT_APPLICATION_PREPARED)
  public void onApplicationPrepared() {
    logger.info("Auto-provisioning NotificationTypes from CDS annotations...");
    try {
      provisionNotificationTypes();
      logger.info("Auto-provisioning completed");
    } catch (IllegalStateException e) {
      // Developer mistake (e.g. malformed notification type) — fail hard
      throw e;
    } catch (Exception e) {
      // Transient error (network, ANS down) — log and continue
      logger.error("Auto-provisioning failed", e);
    }
  }

  private void provisionNotificationTypes() {
    List<NotificationTypes> notificationTypes = notificationTypeBuilder.buildAllNotificationTypes();

    // Fetch ALL existing notification types from ANS in a single call
    // to build a reliable Key → Id mapping.
    Map<String, String> existingTypeIds = fetchExistingNotificationTypeIds();
    logger.debug("Found {} existing notification types in ANS", existingTypeIds.size());

    for (NotificationTypes notificationType : notificationTypes) {
      String key = notificationType.getNotificationTypeKey();
      String existingId = existingTypeIds.get(key);

      if (existingId != null) {
        updateNotificationType(notificationType, existingId);
      } else {
        createNotificationType(notificationType);
      }
    }
  }

  /** Fetch all existing notification types from ANS and return a map of Key → Id. */
  private Map<String, String> fetchExistingNotificationTypeIds() {
    try {
      return notificationTypeProviderService
          .run(
              Select.from(NotificationTypes_.class)
                  .columns(nt -> nt.NotificationTypeKey(), nt -> nt.NotificationTypeId())
                  .where(
                      nt ->
                          nt.NotificationTypeKey()
                              .isNotNull()
                              .and(nt.NotificationTypeId().isNotNull())))
          .streamOf(NotificationTypes.class)
          .collect(
              Collectors.toMap(
                  NotificationTypes::getNotificationTypeKey,
                  NotificationTypes::getNotificationTypeId,
                  (id1, id2) -> id1 // keep first if duplicate keys
                  ));
    } catch (Exception e) {
      logger.warn("Could not fetch existing notification types from ANS: {}", e.getMessage());
      return Collections.emptyMap();
    }
  }

  private void createNotificationType(NotificationTypes notificationType) {
    try {
      notificationTypeProviderService.run(
          Insert.into(NotificationTypes_.CDS_NAME).entry(notificationType));

      logger.debug(
          "NotificationType '{}' created in ANS successfully",
          notificationType.getNotificationTypeKey());
    } catch (Exception e) {
      String errorMsg = e.getMessage() != null ? e.getMessage() : "";

      if (errorMsg.contains("409")) {
        // Race condition: type was created between our GET-all and this INSERT.
        // Re-fetch this specific type's ID and update.
        logger.debug(
            "NotificationType '{}' was created concurrently (409). Attempting update...",
            notificationType.getNotificationTypeKey());
        String id =
            notificationTypeProviderService
                .run(
                    Select.from(NotificationTypes_.class)
                        .where(
                            nt ->
                                nt.NotificationTypeKey()
                                    .eq(notificationType.getNotificationTypeKey())))
                .first(NotificationTypes.class)
                .map(NotificationTypes::getNotificationTypeId)
                .orElse(null);

        if (id != null) {
          updateNotificationType(notificationType, id);
        } else {
          logger.warn(
              "Could not resolve ID for '{}' after 409 — skipping update",
              notificationType.getNotificationTypeKey());
        }
        return;
      }

      if (errorMsg.contains("400")) {
        logger.error(
            "ANS rejected NotificationType '{}' with 400 Bad Request. "
                + "This usually means required fields are missing or invalid. "
                + "Check that all required fields (publicTitle, title, groupedTitle, subtitle) are set. "
                + "Error: {}",
            notificationType.getNotificationTypeKey(),
            errorMsg);
        throw new IllegalStateException(
            String.format(
                "ANS rejected NotificationType '%s' with 400 Bad Request. "
                    + "Ensure all required template fields are properly configured in your CDS model and i18n files. Error: %s",
                notificationType.getNotificationTypeKey(), errorMsg),
            e);
      }

      logger.error(
          "Failed to create NotificationType '{}' in ANS",
          notificationType.getNotificationTypeKey(),
          e);
      throw e;
    }
  }

  private void updateNotificationType(
      NotificationTypes notificationType, String notificationTypeId) {
    logger.debug(
        "Updating NotificationType '{}' (id={}) via delete+create",
        notificationType.getNotificationTypeKey(),
        notificationTypeId);

    try {
      // ANS does not allow mixing Translations and Templates on update,
      // so we delete the existing type and recreate it cleanly.
      notificationTypeProviderService.run(
          Delete.from(NotificationTypes_.class)
              .where(nt -> nt.NotificationTypeId().eq(notificationTypeId)));

      logger.debug(
          "Deleted existing NotificationType '{}' (id={})",
          notificationType.getNotificationTypeKey(),
          notificationTypeId);
    } catch (Exception e) {
      logger.warn(
          "Failed to delete existing NotificationType '{}' (id={}): {}. Attempting create anyway.",
          notificationType.getNotificationTypeKey(),
          notificationTypeId,
          e.getMessage());
    }

    createNotificationType(notificationType);
  }
}
