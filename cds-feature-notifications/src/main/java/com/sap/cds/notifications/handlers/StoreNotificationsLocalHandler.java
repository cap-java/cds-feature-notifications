/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package com.sap.cds.notifications.handlers;

import cds.gen.notificationproviderservice.Notifications;
import com.sap.cds.notifications.helpers.NotificationStorageService;
import com.sap.cds.services.EventContext;
import com.sap.cds.services.cds.ApplicationService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.After;
import com.sap.cds.services.handler.annotations.ServiceName;
import java.time.Instant;
import java.util.List;

@ServiceName(value = "*", type = ApplicationService.class)
public class StoreNotificationsLocalHandler implements EventHandler {

  private final NotificationStorageService storageService;

  public StoreNotificationsLocalHandler(NotificationStorageService storageService) {
    this.storageService = storageService;
  }

  @After(event = "*")
  public void storeNotifications(EventContext context) {
    @SuppressWarnings("unchecked")
    List<Notifications> sentNotifications =
        (List<Notifications>) context.get(LocalHandler.SENT_NOTIFICATIONS_KEY);

    if (sentNotifications == null || sentNotifications.isEmpty()) {
      return;
    }

    Instant sentAt = Instant.now();

    for (Notifications notification : sentNotifications) {
      storageService.store(notification.getId(), notification, sentAt);
    }
  }
}
