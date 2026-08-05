/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package customer.sample_app.integration;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import cds.gen.my.notifications.notificationservice.NotificationService;
import cds.gen.my.notifications.notificationservice.ReminderNotification;
import cds.gen.my.notifications.notificationservice.ReminderNotificationContext;
import cds.gen.sap.cds.notifications.NotificationTargetParameters;
import cds.gen.sap.cds.notifications.Notifications;
import cds.gen.sap.cds.notifications.Notifications_;
import com.sap.cds.CdsData;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.services.persistence.PersistenceService;
import customer.sample_app.handlers.mock.NotificationProviderServiceMockHandler;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests verifying the cooldown mechanism: a notification is skipped if the same type
 * was already sent to the same recipient with the same target parameters within the cooldown
 * window.
 */
@SpringBootTest
@ActiveProfiles("test")
public class CooldownIntegrationTest {

  @Autowired private NotificationService.Application notificationService;
  @Autowired private PersistenceService persistenceService;

  @BeforeEach
  void setup() {
    NotificationProviderServiceMockHandler.clearAllNotifications();
  }

  @Test
  void testSecondNotificationSkippedWhenInCooldown() {
    String recipient = "cooldown-test-1@example.com";
    String reminderId = "reminder-cd-1";

    // Insert a recent notification record directly into DB (1 day ago, cooldown is 2 days)
    Notifications recent = Notifications.create();
    recent.setId(UUID.randomUUID().toString());
    recent.setRecipient(recipient);
    recent.setNotificationTypeKey("ReminderNotification");
    recent.setNotificationTemplateKey("ReminderNotification");
    recent.setSentAt(Instant.now().minus(1, ChronoUnit.DAYS));
    NotificationTargetParameters param = NotificationTargetParameters.create();
    param.setParamKey("reminderId");
    param.setParamValue(reminderId);
    recent.setTargetParameters(List.of(param));
    persistenceService.run(Insert.into(Notifications_.CDS_NAME).entry(recent));

    // Send notification — cooldown active → should be skipped
    ReminderNotification data = buildReminder(recipient, reminderId);
    ReminderNotificationContext ctx = ReminderNotificationContext.create();
    ctx.setData(data);

    notificationService.emit(ctx);

    await()
        .during(500, MILLISECONDS)
        .atMost(1, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() == 0);

    assertEquals(
        0,
        NotificationProviderServiceMockHandler.getNotificationCount(),
        "Notification should be skipped due to cooldown");
  }

  @Test
  void testNotificationNotBlockedByDifferentTargetParams() {
    // First send with reminderId "reminder-cd-2a"
    ReminderNotification firstData = buildReminder("cooldown-test-2@example.com", "reminder-cd-2a");
    ReminderNotificationContext firstCtx = ReminderNotificationContext.create();
    firstCtx.setData(firstData);

    notificationService.emit(firstCtx);
    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() == 1);

    await()
        .atMost(5, SECONDS)
        .until(
            () ->
                !persistenceService
                    .run(
                        Select.from(Notifications_.CDS_NAME)
                            .where(n -> n.get("recipient").eq("cooldown-test-2@example.com")))
                    .listOf(CdsData.class)
                    .isEmpty());

    NotificationProviderServiceMockHandler.clearAllNotifications();

    // Second send with DIFFERENT reminderId "reminder-cd-2b" — different target params,
    // so cooldown does not apply even for the same recipient
    ReminderNotification secondData =
        buildReminder("cooldown-test-2@example.com", "reminder-cd-2b");
    ReminderNotificationContext secondCtx = ReminderNotificationContext.create();
    secondCtx.setData(secondData);

    notificationService.emit(secondCtx);
    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() == 1);

    assertEquals(
        1,
        NotificationProviderServiceMockHandler.getNotificationCount(),
        "Notification with different target params should go through regardless of cooldown");

    cds.gen.notificationproviderservice.Notifications sent =
        NotificationProviderServiceMockHandler.getAllNotifications().get(0);
    assertEquals(
        "reminder-cd-2b",
        sent.getTargetParameters().get(0).getValue(),
        "Sent notification should have the new reminderId as target parameter");
  }

  @Test
  void testCooldownDoesNotApplyToRecipientsNotPreviouslyNotified() {
    String recipientInCooldown = "cooldown-test-3a@example.com";
    String recipientFresh = "cooldown-test-3b@example.com";

    // Pre-send for recipientInCooldown
    ReminderNotification firstData = buildReminder(recipientInCooldown, "reminder-cd-3");
    ReminderNotificationContext firstCtx = ReminderNotificationContext.create();
    firstCtx.setData(firstData);

    notificationService.emit(firstCtx);
    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() == 1);

    // Wait for DB storage before second emit
    await()
        .atMost(5, SECONDS)
        .until(
            () ->
                !persistenceService
                    .run(
                        Select.from(Notifications_.CDS_NAME)
                            .where(n -> n.get("recipient").eq(recipientInCooldown)))
                    .listOf(CdsData.class)
                    .isEmpty());

    NotificationProviderServiceMockHandler.clearAllNotifications();

    // Emit for recipientFresh — same reminderId, different recipient → should go through
    ReminderNotification secondData = buildReminder(recipientFresh, "reminder-cd-3");
    ReminderNotificationContext secondCtx = ReminderNotificationContext.create();
    secondCtx.setData(secondData);

    notificationService.emit(secondCtx);
    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() == 1);

    cds.gen.notificationproviderservice.Notifications sent =
        NotificationProviderServiceMockHandler.getAllNotifications().get(0);
    assertEquals(
        recipientFresh,
        sent.getRecipients().get(0).getRecipientId(),
        "Notification should be sent to the fresh recipient");
  }

  @Test
  void testNotificationSentAfterCooldownExpires() {
    String recipient = "cooldown-test-4@example.com";
    String reminderId = "reminder-cd-4";

    // Insert an expired notification record directly into DB (3 days ago, cooldown is 2 days)
    Notifications expired = Notifications.create();
    expired.setId(UUID.randomUUID().toString());
    expired.setRecipient(recipient);
    expired.setNotificationTypeKey("ReminderNotification");
    expired.setNotificationTemplateKey("ReminderNotification");
    expired.setSentAt(Instant.now().minus(3, ChronoUnit.DAYS));
    NotificationTargetParameters param = NotificationTargetParameters.create();
    param.setParamKey("reminderId");
    param.setParamValue(reminderId);
    expired.setTargetParameters(List.of(param));
    persistenceService.run(Insert.into(Notifications_.CDS_NAME).entry(expired));

    // Send notification — cooldown has expired → should go through
    ReminderNotification data = buildReminder(recipient, reminderId);
    ReminderNotificationContext ctx = ReminderNotificationContext.create();
    ctx.setData(data);

    notificationService.emit(ctx);
    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() == 1);

    assertEquals(
        1,
        NotificationProviderServiceMockHandler.getNotificationCount(),
        "Notification should be sent when cooldown has expired");

    cds.gen.notificationproviderservice.Notifications sent =
        NotificationProviderServiceMockHandler.getAllNotifications().get(0);
    assertEquals(
        recipient,
        sent.getRecipients().get(0).getRecipientId(),
        "Notification should be sent to the correct recipient");

    // Also verify it was stored in DB (new row added)
    await()
        .atMost(5, SECONDS)
        .until(
            () ->
                persistenceService
                        .run(
                            Select.from(Notifications_.CDS_NAME)
                                .where(n -> n.get("recipient").eq(recipient)))
                        .listOf(CdsData.class)
                        .size()
                    == 2);

    assertEquals(
        2,
        persistenceService
            .run(Select.from(Notifications_.CDS_NAME).where(n -> n.get("recipient").eq(recipient)))
            .listOf(CdsData.class)
            .size(),
        "A new DB record should be added after cooldown expires");
  }

  @Test
  void testCooldownFiltersOnlyRecipientsInCooldown() {
    String reminderId = "reminder-cd-5";

    // Insert recent records for alice and bob (in cooldown)
    for (String recipient : List.of("alice@example.com", "bob@example.com")) {
      Notifications recent = Notifications.create();
      recent.setId(UUID.randomUUID().toString());
      recent.setRecipient(recipient);
      recent.setNotificationTypeKey("ReminderNotification");
      recent.setNotificationTemplateKey("ReminderNotification");
      recent.setSentAt(Instant.now().minus(1, ChronoUnit.DAYS));
      NotificationTargetParameters param = NotificationTargetParameters.create();
      param.setParamKey("reminderId");
      param.setParamValue(reminderId);
      recent.setTargetParameters(List.of(param));
      persistenceService.run(Insert.into(Notifications_.CDS_NAME).entry(recent));
    }

    // Send to all 3 — alice and bob in cooldown, charlie is not
    ReminderNotification data = ReminderNotification.create();
    data.setRecipients(List.of("alice@example.com", "bob@example.com", "charlie@example.com"));
    data.setReminderId(reminderId);
    data.setMessage("Please complete your pending action");
    ReminderNotificationContext ctx = ReminderNotificationContext.create();
    ctx.setData(data);

    notificationService.emit(ctx);
    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() == 1);

    cds.gen.notificationproviderservice.Notifications sent =
        NotificationProviderServiceMockHandler.getAllNotifications().get(0);
    assertEquals(1, sent.getRecipients().size(), "Only charlie should receive the notification");
    assertEquals(
        "charlie@example.com",
        sent.getRecipients().get(0).getRecipientId(),
        "Charlie should be the only recipient");
  }

  private ReminderNotification buildReminder(String recipient, String reminderId) {
    ReminderNotification data = ReminderNotification.create();
    data.setRecipients(List.of(recipient));
    data.setReminderId(reminderId);
    data.setMessage("Please complete your pending action");
    return data;
  }
}
