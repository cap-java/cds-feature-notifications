/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package com.sap.cds.notifications.handlers;

import cds.gen.notificationproviderservice.NotificationProviderService_;
import cds.gen.notificationproviderservice.Notifications;
import cds.gen.notificationproviderservice.Notifications_;
import com.sap.cds.notifications.helpers.NotificationStorageService;
import com.sap.cds.services.Service;
import com.sap.cds.services.cds.CdsCreateEventContext;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.After;
import com.sap.cds.services.handler.annotations.ServiceName;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@ServiceName(value = NotificationProviderService_.CDS_NAME, type = Service.class)
public class StoreNotificationsHandler implements EventHandler {

  private final NotificationStorageService storageService;

  public StoreNotificationsHandler(NotificationStorageService storageService) {
    this.storageService = storageService;
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
      Notifications requestEntry = Notifications.of(entries.get(i));
      storageService.store(result.getId(), requestEntry, sentAt);
    }
  }
}
