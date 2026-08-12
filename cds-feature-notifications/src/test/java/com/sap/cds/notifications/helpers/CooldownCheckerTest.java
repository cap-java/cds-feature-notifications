/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package com.sap.cds.notifications.helpers;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cds.gen.notificationproviderservice.NavigationTargetParams;
import cds.gen.notificationproviderservice.Notifications;
import cds.gen.notificationproviderservice.Recipients;
import cds.gen.sap.cds.notifications.NotificationTargetParameters;
import com.sap.cds.Result;
import com.sap.cds.Struct;
import com.sap.cds.ql.cqn.CqnSelect;
import com.sap.cds.reflect.CdsAnnotation;
import com.sap.cds.reflect.CdsEvent;
import com.sap.cds.services.persistence.PersistenceService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CooldownCheckerTest {

  private PersistenceService persistenceService;
  private CooldownChecker cooldownChecker;

  @BeforeEach
  void setUp() {
    persistenceService = mock(PersistenceService.class);
    cooldownChecker = new CooldownChecker(persistenceService);
  }

  @Nested
  @DisplayName("filterCooldownRecipients")
  class FilterCooldownRecipients {

    @Test
    @DisplayName("returns notification unchanged when persistence service is not available")
    void returnsNotificationUnchangedWhenPersistenceServiceIsNull() {
      CooldownChecker checkerWithoutDb = new CooldownChecker(null);
      CdsEvent event = mockEventWithCooldown(20);
      Notifications notification = buildNotification("user@example.com", "BookOrdered", List.of());

      Notifications result = checkerWithoutDb.filterCooldownRecipients(event, notification);

      assertSame(notification, result);
    }

    @Test
    @DisplayName("returns notification unchanged when persistence service throws an exception")
    void returnsNotificationUnchangedWhenPersistenceServiceThrows() {
      CdsEvent event = mockEventWithCooldown(20);
      Notifications notification = buildNotification("user@example.com", "BookOrdered", List.of());

      when(persistenceService.run(any(CqnSelect.class)))
          .thenThrow(new RuntimeException("DB error"));

      Notifications result = cooldownChecker.filterCooldownRecipients(event, notification);

      assertSame(notification, result);
    }

    @Test
    @DisplayName("returns notification unchanged when no cooldown annotation is present")
    void returnsNotificationUnchangedWhenNoCooldownAnnotation() {
      CdsEvent event = mock(CdsEvent.class);
      when(event.findAnnotation("notification.cooldown")).thenReturn(Optional.empty());

      Notifications notification = buildNotification("user@example.com", "BookOrdered", List.of());

      Notifications result = cooldownChecker.filterCooldownRecipients(event, notification);

      assertSame(notification, result);
    }

    @Test
    @DisplayName("returns notification unchanged when cooldown is zero or negative")
    void returnsNotificationUnchangedWhenCooldownIsZero() {
      CdsEvent event = mockEventWithCooldown(0);
      Notifications notification = buildNotification("user@example.com", "BookOrdered", List.of());

      Notifications result = cooldownChecker.filterCooldownRecipients(event, notification);

      assertSame(notification, result);
    }

    @Test
    @DisplayName("returns notification unchanged when no stored notifications exist")
    void returnsNotificationUnchangedWhenNoStoredNotificationsExist() {
      CdsEvent event = mockEventWithCooldown(20);

      Notifications notification = buildNotification("user@example.com", "BookOrdered", List.of());

      Result mockResult = mock(Result.class);
      when(mockResult.listOf(cds.gen.sap.cds.notifications.Notifications.class))
          .thenReturn(List.of());
      when(persistenceService.run(any(CqnSelect.class))).thenReturn(mockResult);

      Notifications result = cooldownChecker.filterCooldownRecipients(event, notification);

      assertSame(notification, result);
    }

    @Test
    @DisplayName("returns null when all recipients are within cooldown window and no target params")
    void returnsNullWhenAllRecipientsInCooldownWithNoTargetParams() {
      CdsEvent event = mockEventWithCooldown(20);

      Notifications notification = buildNotification("user@example.com", "BookOrdered", List.of());

      cds.gen.sap.cds.notifications.Notifications stored =
          cds.gen.sap.cds.notifications.Notifications.create();
      stored.setId("some-id");
      stored.setSentAt(Instant.now().minus(5, ChronoUnit.DAYS));
      stored.setTargetParameters(List.of());

      Result mockResult = mock(Result.class);
      when(mockResult.listOf(cds.gen.sap.cds.notifications.Notifications.class))
          .thenReturn(List.of(stored));
      when(persistenceService.run(any(CqnSelect.class))).thenReturn(mockResult);

      Notifications result = cooldownChecker.filterCooldownRecipients(event, notification);

      assertNull(result);
    }

    @Test
    @DisplayName("returns null when recipient has same target params within cooldown window")
    void returnsNullWhenSameTargetParamsWithinCooldownWindow() {
      CdsEvent event = mockEventWithCooldown(20);

      List<NavigationTargetParams> targetParams = List.of(buildTargetParam("bookId", "123"));
      Notifications notification =
          buildNotification("user@example.com", "BookOrdered", targetParams);

      cds.gen.sap.cds.notifications.Notifications stored =
          cds.gen.sap.cds.notifications.Notifications.create();
      stored.setId("some-id");
      stored.setSentAt(Instant.now().minus(5, ChronoUnit.DAYS));
      NotificationTargetParameters storedParam = NotificationTargetParameters.create();
      storedParam.setParamKey("bookId");
      storedParam.setParamValue("123");
      stored.setTargetParameters(List.of(storedParam));

      Result mockResult = mock(Result.class);
      when(mockResult.listOf(cds.gen.sap.cds.notifications.Notifications.class))
          .thenReturn(List.of(stored));
      when(persistenceService.run(any(CqnSelect.class))).thenReturn(mockResult);

      Notifications result = cooldownChecker.filterCooldownRecipients(event, notification);

      assertNull(result);
    }

    @Test
    @DisplayName(
        "returns notification unchanged when stored notification has different target params")
    void returnsNotificationUnchangedWhenDifferentTargetParams() {
      CdsEvent event = mockEventWithCooldown(20);

      List<NavigationTargetParams> targetParams = List.of(buildTargetParam("bookId", "123"));
      Notifications notification =
          buildNotification("user@example.com", "BookOrdered", targetParams);

      cds.gen.sap.cds.notifications.Notifications stored =
          cds.gen.sap.cds.notifications.Notifications.create();
      stored.setId("some-id");
      stored.setSentAt(Instant.now().minus(5, ChronoUnit.DAYS));
      NotificationTargetParameters storedParam = NotificationTargetParameters.create();
      storedParam.setParamKey("bookId");
      storedParam.setParamValue("999"); // different value
      stored.setTargetParameters(List.of(storedParam));

      Result mockResult = mock(Result.class);
      when(mockResult.listOf(cds.gen.sap.cds.notifications.Notifications.class))
          .thenReturn(List.of(stored));
      when(persistenceService.run(any(CqnSelect.class))).thenReturn(mockResult);

      Notifications result = cooldownChecker.filterCooldownRecipients(event, notification);

      assertSame(notification, result);
    }
  }

  private CdsEvent mockEventWithCooldown(int days) {
    CdsEvent event = mock(CdsEvent.class);
    @SuppressWarnings("unchecked")
    CdsAnnotation<Object> annotation = mock(CdsAnnotation.class);
    when(annotation.getValue()).thenReturn(days);
    when(event.findAnnotation("notification.cooldown")).thenReturn(Optional.of(annotation));
    return event;
  }

  private Notifications buildNotification(
      String recipientEmail, String typeKey, List<NavigationTargetParams> targetParams) {
    Notifications notification = Struct.create(Notifications.class);
    notification.setNotificationTypeKey(typeKey);
    notification.setTargetParameters(targetParams);

    Recipients recipient = Struct.create(Recipients.class);
    recipient.setRecipientId(recipientEmail);
    notification.setRecipients(List.of(recipient));

    return notification;
  }

  private NavigationTargetParams buildTargetParam(String key, String value) {
    NavigationTargetParams param = Struct.create(NavigationTargetParams.class);
    param.setKey(key);
    param.setValue(value);
    return param;
  }
}
