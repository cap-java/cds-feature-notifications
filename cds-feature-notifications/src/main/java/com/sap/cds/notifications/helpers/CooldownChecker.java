/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package com.sap.cds.notifications.helpers;

import cds.gen.notificationproviderservice.NavigationTargetParams;
import cds.gen.notificationproviderservice.Notifications;
import cds.gen.notificationproviderservice.Recipients;
import cds.gen.sap.cds.notifications.NotificationTargetParameters;
import cds.gen.sap.cds.notifications.Notifications_;
import com.sap.cds.Result;
import com.sap.cds.ql.Select;
import com.sap.cds.reflect.CdsEvent;
import com.sap.cds.services.persistence.PersistenceService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CooldownChecker {

  private static final Logger logger = LoggerFactory.getLogger(CooldownChecker.class);

  private final PersistenceService persistenceService;

  public CooldownChecker(PersistenceService persistenceService) {
    this.persistenceService = persistenceService;
  }

  /**
   * Filters out recipients that are within the cooldown window from the given notification. Returns
   * the notification with only the recipients that should receive it, or null if all recipients are
   * in cooldown and the notification should be skipped entirely.
   */
  public Notifications filterCooldownRecipients(CdsEvent event, Notifications notification) {
    if (persistenceService == null) {
      return notification;
    }

    var cooldownAnnotation = event.findAnnotation("notification.cooldown");
    if (cooldownAnnotation.isEmpty()) {
      return notification;
    }

    int cooldownDays;
    try {
      cooldownDays = ((Number) cooldownAnnotation.get().getValue()).intValue();
    } catch (Exception e) {
      logger.warn(
          "Invalid cooldown value for event '{}', skipping cooldown check", event.getName());
      return notification;
    }

    if (cooldownDays <= 0) {
      return notification;
    }

    Instant cutoff = Instant.now().minus(cooldownDays, ChronoUnit.DAYS);
    String typeKey = notification.getNotificationTypeKey();
    Map<String, String> currentTargetParams =
        buildTargetParamsMap(notification.getTargetParameters());

    List<Recipients> filtered =
        notification.getRecipients().stream()
            .filter(
                r -> {
                  String recipientId = NotificationStorageHelper.resolveRecipientId(r);
                  boolean inCooldown =
                      isInCooldown(typeKey, recipientId, cutoff, currentTargetParams);
                  if (inCooldown) {
                    logger.debug(
                        "Recipient '{}' is in cooldown for notification type '{}'",
                        recipientId,
                        typeKey);
                  }
                  return !inCooldown;
                })
            .collect(Collectors.toList());

    if (filtered.isEmpty()) {
      return null;
    }

    notification.setRecipients(filtered);
    return notification;
  }

  private boolean isInCooldown(
      String typeKey, String recipientId, Instant cutoff, Map<String, String> currentTargetParams) {
    try {
      Result result =
          persistenceService.run(
              Select.from(Notifications_.class)
                  .columns(n -> n.ID(), n -> n.sentAt(), n -> n.targetParameters().expand())
                  .where(
                      n ->
                          n.notificationTypeKey()
                              .eq(typeKey)
                              .and(n.recipient().eq(recipientId))
                              .and(n.sentAt().gt(cutoff))));

      for (cds.gen.sap.cds.notifications.Notifications stored :
          result.listOf(cds.gen.sap.cds.notifications.Notifications.class)) {
        Map<String, String> storedParams = buildStoredParamsMap(stored.getTargetParameters());
        if (storedParams.equals(currentTargetParams)) {
          return true;
        }
      }
    } catch (Exception e) {
      logger.warn(
          "Failed to check cooldown for recipient '{}', type '{}': {}",
          recipientId,
          typeKey,
          e.getMessage());
    }
    return false;
  }

  private Map<String, String> buildTargetParamsMap(List<NavigationTargetParams> params) {
    if (params == null) {
      return Map.of();
    }
    return params.stream()
        .filter(p -> p.getKey() != null)
        .collect(
            Collectors.toMap(
                NavigationTargetParams::getKey, p -> p.getValue() != null ? p.getValue() : ""));
  }

  private Map<String, String> buildStoredParamsMap(List<NotificationTargetParameters> params) {
    if (params == null) {
      return Map.of();
    }
    return params.stream()
        .filter(p -> p.getParamKey() != null)
        .collect(
            Collectors.toMap(
                NotificationTargetParameters::getParamKey,
                p -> p.getParamValue() != null ? p.getParamValue() : ""));
  }
}
