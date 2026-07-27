/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package com.sap.cds.notifications.handlers;

import cds.gen.notificationproviderservice.NotificationProviderService_;
import cds.gen.notificationproviderservice.Notifications;
import cds.gen.notificationproviderservice.Notifications_;
import cds.gen.notificationproviderservice.Recipients;
import cds.gen.sap.cds.notifications.NotificationProperties;
import cds.gen.sap.cds.notifications.NotificationTargetParameters;
import com.sap.cds.ql.Insert;
import com.sap.cds.services.cds.CdsCreateEventContext;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.After;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.persistence.PersistenceService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ServiceName(
    value = NotificationProviderService_.CDS_NAME,
    type = com.sap.cds.services.Service.class)
public class StoreNotificationsHandler implements EventHandler {

  private static final Logger logger = LoggerFactory.getLogger(StoreNotificationsHandler.class);

  private final PersistenceService persistenceService;

  public StoreNotificationsHandler(PersistenceService persistenceService) {
    this.persistenceService = persistenceService;
  }

  @After(event = CqnService.EVENT_CREATE, entity = Notifications_.CDS_NAME)
  public void storeNotifications(CdsCreateEventContext context) {
    List<Notifications> results = context.getResult().listOf(Notifications.class);
    List<Map<String, Object>> entries = context.getCqn().entries();

    if (results == null || results.isEmpty()) {
      return;
    }

    Instant sentAt = Instant.now();

    for (int i = 0; i < results.size(); i++) {
      Notifications result = results.get(i);
      if (result.getId() == null) {
        logger.warn("Skipping notification with null ID, cannot store to DB");
        continue;
      }

      Notifications requestEntry = Notifications.of(entries.get(i));

      logger.info(
          "Storing notification '{}' for {} recipient(s) to DB",
          result.getId(),
          requestEntry.getRecipients().size());

      for (Recipients recipient : requestEntry.getRecipients()) {
        String recipientId = resolveRecipientId(recipient);
        cds.gen.sap.cds.notifications.Notifications stored =
            buildStoredNotification(result, requestEntry, recipientId, sentAt);
        persistenceService.run(
            Insert.into(cds.gen.sap.cds.notifications.Notifications_.class).entry(stored));
        logger.debug("Stored notification '{}' for recipient '{}'", result.getId(), recipientId);
      }
    }
  }

  private cds.gen.sap.cds.notifications.Notifications buildStoredNotification(
      Notifications result, Notifications request, String recipientId, Instant sentAt) {
    cds.gen.sap.cds.notifications.Notifications stored =
        cds.gen.sap.cds.notifications.Notifications.create();
    stored.setId(result.getId());
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
