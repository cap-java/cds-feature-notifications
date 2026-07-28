/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package com.sap.cds.notifications.helpers;

import cds.gen.notificationproviderservice.Notifications;
import cds.gen.notificationproviderservice.Recipients;
import cds.gen.sap.cds.notifications.NotificationProperties;
import cds.gen.sap.cds.notifications.NotificationTargetParameters;
import com.sap.cds.ql.Insert;
import com.sap.cds.services.persistence.PersistenceService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared service for persisting notifications to the database. */
public class NotificationStorageService {

  private static final Logger logger = LoggerFactory.getLogger(NotificationStorageService.class);

  private final PersistenceService persistenceService;

  public NotificationStorageService(PersistenceService persistenceService) {
    this.persistenceService = persistenceService;
  }

  public void store(String notificationId, Notifications request, Instant sentAt) {
    if (notificationId == null) {
      logger.warn("Skipping notification with null ID, cannot store to DB");
      return;
    }

    logger.info(
        "Storing notification '{}' for {} recipient(s) to DB",
        notificationId,
        request.getRecipients().size());

    for (Recipients recipient : request.getRecipients()) {
      String recipientId = resolveRecipientId(recipient);
      cds.gen.sap.cds.notifications.Notifications stored =
          buildStoredNotification(notificationId, request, recipientId, sentAt);
      persistenceService.run(
          Insert.into(cds.gen.sap.cds.notifications.Notifications_.class).entry(stored));
      logger.debug("Stored notification '{}' for recipient '{}'", notificationId, recipientId);
    }
  }

  private cds.gen.sap.cds.notifications.Notifications buildStoredNotification(
      String notificationId, Notifications request, String recipientId, Instant sentAt) {
    cds.gen.sap.cds.notifications.Notifications stored =
        cds.gen.sap.cds.notifications.Notifications.create();
    stored.setId(notificationId);
    stored.setRecipient(recipientId);
    stored.setNotificationTypeKey(request.getNotificationTypeKey());
    stored.setNotificationTemplateKey(request.getNotificationTemplateKey());
    stored.setPriority(request.getPriority());
    stored.setNavigationTargetObject(request.getNavigationTargetObject());
    stored.setNavigationTargetAction(request.getNavigationTargetAction());
    stored.setSentAt(sentAt);

    if (request.getProperties() != null) {
      stored.setProperties(buildProperties(request));
    }

    if (request.getTargetParameters() != null) {
      stored.setTargetParameters(buildTargetParameters(request));
    }

    return stored;
  }

  private List<NotificationProperties> buildProperties(Notifications notification) {
    List<NotificationProperties> properties = new ArrayList<>();
    notification
        .getProperties()
        .forEach(
            prop -> {
              NotificationProperties p = NotificationProperties.create();
              p.setPropertyKey(prop.getKey());
              p.setPropertyValue(prop.getValue());
              properties.add(p);
            });
    return properties;
  }

  private List<NotificationTargetParameters> buildTargetParameters(Notifications notification) {
    List<NotificationTargetParameters> params = new ArrayList<>();
    notification
        .getTargetParameters()
        .forEach(
            param -> {
              NotificationTargetParameters p = NotificationTargetParameters.create();
              p.setParamKey(param.getKey());
              p.setParamValue(param.getValue());
              params.add(p);
            });
    return params;
  }

  private String resolveRecipientId(Recipients recipient) {
    if (recipient.getGlobalUserId() != null && !recipient.getGlobalUserId().isBlank()) {
      return recipient.getGlobalUserId();
    }
    return recipient.getRecipientId();
  }
}
