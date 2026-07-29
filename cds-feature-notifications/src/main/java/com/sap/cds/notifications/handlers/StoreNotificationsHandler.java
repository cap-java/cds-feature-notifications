/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package com.sap.cds.notifications.handlers;

import cds.gen.notificationproviderservice.NotificationProviderService_;
import cds.gen.notificationproviderservice.Notifications;
import com.sap.cds.notifications.helpers.NotificationStorageHelper;
import com.sap.cds.services.Service;
import com.sap.cds.services.cds.CdsCreateEventContext;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.After;
import com.sap.cds.services.handler.annotations.ServiceName;
import java.time.Instant;
import java.util.List;

@ServiceName(value = NotificationProviderService_.CDS_NAME, type = Service.class)
public class StoreNotificationsHandler implements EventHandler {

  private final NotificationStorageHelper storageHelper;

  public StoreNotificationsHandler(NotificationStorageHelper storageHelper) {
    this.storageHelper = storageHelper;
  }

  @After
  public void storeNotifications(CdsCreateEventContext context, List<Notifications> results) {
    if (results == null || results.isEmpty()) {
      return;
    }

    Notifications result = results.get(0);
    Notifications requestEntry = Notifications.of(context.getCqn().entries().get(0));
    storageHelper.store(result.getId(), requestEntry, Instant.now());
  }
}
