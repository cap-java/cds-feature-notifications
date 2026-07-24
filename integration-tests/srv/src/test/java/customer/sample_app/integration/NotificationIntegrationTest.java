/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package customer.sample_app.integration;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import cds.gen.my.notifications.notificationservice.CertificateExpiration;
import cds.gen.my.notifications.notificationservice.CertificateExpirationContext;
import cds.gen.my.notifications.notificationservice.ContractDeadline;
import cds.gen.my.notifications.notificationservice.ContractDeadlineContext;
import cds.gen.my.notifications.notificationservice.DeploymentNotification;
import cds.gen.my.notifications.notificationservice.DeploymentNotificationContext;
import cds.gen.my.notifications.notificationservice.NotificationService;
import cds.gen.my.notifications.notificationservice.SecurityAlert;
import cds.gen.my.notifications.notificationservice.SecurityAlertContext;
import cds.gen.my.notifications.notificationservice.ServerIncident;
import cds.gen.my.notifications.notificationservice.ServerIncidentContext;
import cds.gen.my.notifications.notificationservice.SystemMaintenance;
import cds.gen.my.notifications.notificationservice.SystemMaintenanceContext;
import cds.gen.notificationproviderservice.NavigationTargetParams;
import cds.gen.notificationproviderservice.Notifications;
import cds.gen.notificationproviderservice.Recipients;
import cds.gen.notificationtypeproviderservice.NotificationTypes;
import com.sap.cds.services.EventContext;
import com.sap.cds.services.ServiceException;
import customer.sample_app.handlers.mock.NotificationProviderServiceMockHandler;
import customer.sample_app.handlers.mock.NotificationTypeProviderServiceMockHandler;
import customer.sample_app.testdata.CertificateExpirationTestData;
import customer.sample_app.testdata.ContractDeadlineTestData;
import customer.sample_app.testdata.DeploymentNotificationTestData;
import customer.sample_app.testdata.SecurityAlertTestData;
import customer.sample_app.testdata.ServerIncidentTestData;
import customer.sample_app.testdata.SystemMaintenanceTestData;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for notification feature using mock handlers. Tests the complete flow from
 * notification emission to storage.
 */
@SpringBootTest
@ActiveProfiles("test")
public class NotificationIntegrationTest {

  private static final Logger LOG = LoggerFactory.getLogger(NotificationIntegrationTest.class);

  @Autowired private NotificationService.Application notificationService;

  @Autowired private Environment environment;

  @BeforeEach
  void setup() {
    LOG.debug("========================================");
    LOG.debug("Active profiles: {}", String.join(", ", environment.getActiveProfiles()));
    LOG.debug("Setting up test - clearing notifications");
    LOG.debug("========================================");

    // Clear notifications before each test
    // Note: NotificationTypes are NOT cleared as they are provisioned at startup
    NotificationProviderServiceMockHandler.clearAllNotifications();
  }

  @Test
  void testNotificationIsStoredInMockHandler() {
    LOG.debug("==========================================");
    LOG.debug("Test: Notification should be stored in mock handler");
    LOG.debug("==========================================");

    // Given: Create certificate expiration event using test data
    CertificateExpiration certificateExpiration =
        CertificateExpirationTestData.createValidCertificateExpiration();

    CertificateExpirationContext eventContext = CertificateExpirationContext.create();
    eventContext.setData(certificateExpiration);

    // When: Emit notification
    LOG.debug("Emitting notification event");
    notificationService.emit(eventContext);

    // Wait for async event processing (CAP uses ordered-collector thread pool)
    await()
        .atMost(5, SECONDS)
        .pollInterval(100, java.util.concurrent.TimeUnit.MILLISECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);

    // Then: Verify notification was stored in mock handler
    List<Notifications> allNotifications =
        NotificationProviderServiceMockHandler.getAllNotifications();
    LOG.debug("Total notifications stored: {}", allNotifications.size());

    assertFalse(allNotifications.isEmpty(), "At least one notification should be stored");

    // Verify notification details
    Notifications storedNotification = allNotifications.get(0);
    assertNotNull(storedNotification.getId(), "Notification ID should not be null");
    assertNotNull(
        storedNotification.getNotificationTypeKey(), "Notification type key should not be null");

    LOG.debug("Notification stored successfully with ID: {}", storedNotification.getId());
    LOG.debug("Notification type key: {}", storedNotification.getNotificationTypeKey());
  }

  @Test
  void testNotificationTypeIsAutoProvisioned() {
    LOG.debug("==========================================");
    LOG.debug("Test: All notification types should be auto-provisioned at startup");
    LOG.debug("==========================================");

    // Note: NotificationTypes are auto-provisioned during application startup
    // by NotificationTypeAutoProvisionerHandler, not when emitting notifications.

    // Then: Verify all 4 notification types were auto-provisioned during startup
    List<NotificationTypes> allNotificationTypes =
        NotificationTypeProviderServiceMockHandler.getAllNotificationTypes();
    LOG.debug("Total notification types stored: {}", allNotificationTypes.size());

    Set<String> expectedKeys =
        Set.of(
            "CertificateExpiration",
            "SystemMaintenance",
            "ContractDeadline",
            "SecurityAlert",
            "ServerIncident");

    assertTrue(
        allNotificationTypes.size() >= expectedKeys.size(),
        "Expected at least "
            + expectedKeys.size()
            + " notification types to be auto-provisioned, got "
            + allNotificationTypes.size());

    // Verify each notification type has required fields and matches an expected key
    Set<String> actualKeys = new java.util.HashSet<>();
    for (NotificationTypes nt : allNotificationTypes) {
      assertNotNull(nt.getNotificationTypeId(), "Notification type ID should not be null");
      assertNotNull(nt.getNotificationTypeKey(), "Notification type key should not be null");
      assertNotNull(
          nt.getNotificationTypeVersion(), "Notification type version should not be null");
      actualKeys.add(nt.getNotificationTypeKey());

      LOG.debug(
          "Auto-provisioned: key={}, version={}, id={}",
          nt.getNotificationTypeKey(),
          nt.getNotificationTypeVersion(),
          nt.getNotificationTypeId());
    }

    assertTrue(
        actualKeys.containsAll(expectedKeys),
        "All expected notification type keys should be auto-provisioned. Missing: "
            + expectedKeys.stream().filter(k -> !actualKeys.contains(k)).toList());
  }

  @Test
  void testMultipleNotificationsAreStored() {
    LOG.debug("==========================================");
    LOG.debug("Test: Multiple notifications should be stored");
    LOG.debug("==========================================");

    // Check initial count
    int initialCount = NotificationProviderServiceMockHandler.getNotificationCount();
    LOG.debug("BEFORE LOOP - Notification count: {}", initialCount);

    // Given: Create multiple certificate expiration events using test data builder
    for (int i = 1; i <= 3; i++) {
      LOG.debug(">>> LOOP ITERATION {} STARTING <<<", i);

      CertificateExpiration certificateExpiration =
          CertificateExpirationTestData.builder()
              .recipients("user" + i + "@example.com")
              .name("User " + i)
              .certificateName("Certificate " + i)
              .build();

      CertificateExpirationContext eventContext = CertificateExpirationContext.create();
      eventContext.setData(certificateExpiration);

      // When: Emit notification
      LOG.debug(">>> Emitting notification #{}", i);
      notificationService.emit(eventContext);

      // Check count after each emit
      int currentCount = NotificationProviderServiceMockHandler.getNotificationCount();
      LOG.debug(">>> AFTER EMIT #{} - Notification count: {}", i, currentCount);
    }

    LOG.debug(
        "AFTER ALL EMITS - Final notification count: {}",
        NotificationProviderServiceMockHandler.getNotificationCount());

    // Wait for async event processing (CAP uses ordered-collector thread pool)
    await()
        .atMost(5, SECONDS)
        .pollInterval(100, java.util.concurrent.TimeUnit.MILLISECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() == 3);

    // Then: Verify all notifications were stored
    int notificationCount = NotificationProviderServiceMockHandler.getNotificationCount();
    LOG.debug("Total notifications stored: {}", notificationCount);

    assertEquals(3, notificationCount, "Exactly 3 notifications should be stored");

    List<Notifications> allNotifications =
        NotificationProviderServiceMockHandler.getAllNotifications();
    for (Notifications notification : allNotifications) {
      assertNotNull(notification.getId(), "Each notification should have an ID");
      LOG.debug("Notification stored with ID: {}", notification.getId());
    }
  }

  @Test
  void testRetrieveNotificationByTypeKey() {
    LOG.debug("==========================================");
    LOG.debug("Test: Retrieve notification by type key");
    LOG.debug("==========================================");

    // Given: Create and emit notification using test data
    CertificateExpiration certificateExpiration =
        CertificateExpirationTestData.createValidCertificateExpiration();

    CertificateExpirationContext eventContext = CertificateExpirationContext.create();
    eventContext.setData(certificateExpiration);

    notificationService.emit(eventContext);

    // Wait for async event processing
    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);

    // When: Get the notification type key from first notification
    List<Notifications> allNotifications =
        NotificationProviderServiceMockHandler.getAllNotifications();
    assertFalse(allNotifications.isEmpty(), "Notification should be stored");

    String notificationTypeKey = allNotifications.get(0).getNotificationTypeKey();
    LOG.debug("Searching for notifications with type key: {}", notificationTypeKey);

    // Then: Retrieve notifications by type key
    List<Notifications> notificationsByTypeKey =
        NotificationProviderServiceMockHandler.getNotificationsByTypeKey(notificationTypeKey);

    assertFalse(notificationsByTypeKey.isEmpty(), "Should find notifications with the type key");
    assertEquals(
        notificationTypeKey,
        notificationsByTypeKey.get(0).getNotificationTypeKey(),
        "Retrieved notification should have the correct type key");

    LOG.debug(
        "Successfully retrieved {} notification(s) with type key: {}",
        notificationsByTypeKey.size(),
        notificationTypeKey);
  }

  @Test
  void testRetrieveNotificationTypeByKeyAndVersion() {
    LOG.debug("==========================================");
    LOG.debug("Test: Retrieve notification type by key and version");
    LOG.debug("==========================================");

    // Note: NotificationTypes are auto-provisioned at startup, not when emitting notifications.
    // This test verifies the retrieval functionality using the startup-provisioned types.

    // When: Get the notification type that was provisioned at startup
    List<NotificationTypes> allNotificationTypes =
        NotificationTypeProviderServiceMockHandler.getAllNotificationTypes();
    assertFalse(
        allNotificationTypes.isEmpty(), "Notification type should be auto-provisioned at startup");

    NotificationTypes notificationType = allNotificationTypes.get(0);
    String key = notificationType.getNotificationTypeKey();
    String version = notificationType.getNotificationTypeVersion();

    LOG.debug("Searching for notification type with key: {}, version: {}", key, version);

    // Then: Retrieve notification type by key and version
    NotificationTypes retrievedType =
        NotificationTypeProviderServiceMockHandler.getNotificationTypeByKeyVersion(key, version);

    assertNotNull(retrievedType, "Should find notification type with key and version");
    assertEquals(
        key, retrievedType.getNotificationTypeKey(), "Retrieved type should have correct key");
    assertEquals(
        version,
        retrievedType.getNotificationTypeVersion(),
        "Retrieved type should have correct version");

    LOG.debug("Successfully retrieved notification type with key: {}, version: {}", key, version);
  }

  @Test
  void testValidationRejectsEmptyRecipients() {
    LOG.debug("==========================================");
    LOG.debug("Test: Validation should reject notification without recipients");
    LOG.debug("==========================================");

    // Given: Create certificate expiration event WITHOUT recipients using test data
    CertificateExpiration certificateExpiration =
        CertificateExpirationTestData.createWithoutRecipients();

    // Create event context
    CertificateExpirationContext eventContext = CertificateExpirationContext.create();
    eventContext.setData(certificateExpiration);

    // When & Then: Emit notification - should throw ServiceException
    LOG.debug("Emitting notification event without recipients: {}", eventContext.getEvent());

    ServiceException exception =
        assertThrows(
            ServiceException.class,
            () -> {
              notificationService.emit(eventContext);
            });

    LOG.debug("Test passed - validation correctly rejected notification without recipients");
    LOG.debug("Exception message: {}", exception.getMessage());
  }

  @Test
  void testMultipleRecipientsSupport() {
    LOG.debug("==========================================");
    LOG.debug("Test: Notification should support multiple recipients (array of String)");
    LOG.debug("==========================================");

    // Given: SystemMaintenance event with array of String recipients
    SystemMaintenance data = SystemMaintenanceTestData.createValid();

    SystemMaintenanceContext ctx = SystemMaintenanceContext.create();
    ctx.setData(data);

    // When
    notificationService.emit(ctx);

    // Wait for async event processing
    await()
        .atMost(5, SECONDS)
        .pollInterval(100, java.util.concurrent.TimeUnit.MILLISECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);

    // Then: Verify notification was stored with multiple recipients
    Notifications stored = NotificationProviderServiceMockHandler.getAllNotifications().get(0);
    List<Recipients> recipients = stored.getRecipients();

    assertNotNull(recipients, "Recipients list should not be null");
    assertEquals(3, recipients.size(), "Should have 3 recipients");

    LOG.debug("Multiple recipients test passed — {} recipients", recipients.size());
  }

  // ──────────────────────────────────────────────────────────────
  // Recipient Format & Auto-Detection Tests
  // ──────────────────────────────────────────────────────────────

  @Test
  void testRecipientCase1_String() {
    LOG.debug("==========================================");
    LOG.debug("Test: Case 1 — CertificateExpiration with String recipient");
    LOG.debug("==========================================");

    // Given: CertificateExpiration — recipients: String
    CertificateExpiration data = CertificateExpirationTestData.createValidCertificateExpiration();

    CertificateExpirationContext ctx = CertificateExpirationContext.create();
    ctx.setData(data);

    // When
    notificationService.emit(ctx);

    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);

    // Then: One recipient with RecipientId set
    Notifications stored = NotificationProviderServiceMockHandler.getAllNotifications().get(0);
    List<Recipients> recipients = stored.getRecipients();

    assertNotNull(recipients, "Recipients list should not be null");
    assertEquals(1, recipients.size(), "Should have exactly 1 recipient");
    assertEquals(
        "test@example.com",
        recipients.get(0).getRecipientId(),
        "RecipientId should match the String value");

    LOG.debug("Case 1 passed — RecipientId: {}", recipients.get(0).getRecipientId());
  }

  @Test
  void testRecipientCase2_ArrayOfString() {
    LOG.debug("==========================================");
    LOG.debug("Test: Case 2 — SystemMaintenance with array of String recipients");
    LOG.debug("==========================================");

    // Given: SystemMaintenance — recipients: array of String
    SystemMaintenance data = SystemMaintenanceTestData.createValid();

    SystemMaintenanceContext ctx = SystemMaintenanceContext.create();
    ctx.setData(data);

    // When
    notificationService.emit(ctx);

    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);

    // Then: 3 recipients, each with RecipientId
    Notifications stored = NotificationProviderServiceMockHandler.getAllNotifications().get(0);
    List<Recipients> recipients = stored.getRecipients();

    assertNotNull(recipients, "Recipients list should not be null");
    assertEquals(3, recipients.size(), "Should have 3 recipients");
    assertEquals("admin1@example.com", recipients.get(0).getRecipientId());
    assertEquals("admin2@example.com", recipients.get(1).getRecipientId());
    assertEquals("admin3@example.com", recipients.get(2).getRecipientId());

    LOG.debug("Case 2 passed — {} recipients resolved from array of String", recipients.size());
  }

  @Test
  void testRecipientAutoDetection_UUIDMappedToGlobalUserId() {
    LOG.debug("==========================================");
    LOG.debug("Test: UUID string auto-detected and mapped to GlobalUserId");
    LOG.debug("==========================================");

    // Given: CertificateExpiration with UUID recipient
    CertificateExpiration data = CertificateExpirationTestData.createWithUUIDRecipient();

    CertificateExpirationContext ctx = CertificateExpirationContext.create();
    ctx.setData(data);

    // When
    notificationService.emit(ctx);

    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);

    // Then: 1 recipient with GlobalUserId set (UUID auto-detected)
    Notifications stored = NotificationProviderServiceMockHandler.getAllNotifications().get(0);
    List<Recipients> recipients = stored.getRecipients();

    assertNotNull(recipients, "Recipients list should not be null");
    assertEquals(1, recipients.size(), "Should have exactly 1 recipient");
    assertNull(recipients.get(0).getRecipientId(), "RecipientId should be null for UUID");
    assertEquals(
        "550e8400-e29b-41d4-a716-446655440000",
        recipients.get(0).getGlobalUserId(),
        "UUID should be mapped to GlobalUserId");

    LOG.debug("UUID auto-detection passed — GlobalUserId: {}", recipients.get(0).getGlobalUserId());
  }

  @Test
  void testRecipientAutoDetection_MixedEmailAndUUIDArray() {
    LOG.debug("==========================================");
    LOG.debug("Test: Mixed email/UUID array — each auto-detected correctly");
    LOG.debug("==========================================");

    // Given: SystemMaintenance with mixed recipients (emails + UUID)
    SystemMaintenance data = SystemMaintenanceTestData.createWithMixedRecipients();

    SystemMaintenanceContext ctx = SystemMaintenanceContext.create();
    ctx.setData(data);

    // When
    notificationService.emit(ctx);

    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);

    // Then: 3 recipients with correct auto-detection
    Notifications stored = NotificationProviderServiceMockHandler.getAllNotifications().get(0);
    List<Recipients> recipients = stored.getRecipients();

    assertNotNull(recipients, "Recipients list should not be null");
    assertEquals(3, recipients.size(), "Should have 3 recipients");

    // r1: email → RecipientId
    assertEquals(
        "cfo@example.com",
        recipients.get(0).getRecipientId(),
        "First recipient email should map to RecipientId");
    assertNull(recipients.get(0).getGlobalUserId(), "First recipient should not have GlobalUserId");

    // r2: UUID → GlobalUserId
    assertNull(recipients.get(1).getRecipientId(), "Second recipient should not have RecipientId");
    assertEquals(
        "550e8400-e29b-41d4-a716-446655440000",
        recipients.get(1).getGlobalUserId(),
        "Second recipient UUID should map to GlobalUserId");

    // r3: email → RecipientId
    assertEquals(
        "finance-lead@example.com",
        recipients.get(2).getRecipientId(),
        "Third recipient email should map to RecipientId");
    assertNull(recipients.get(2).getGlobalUserId(), "Third recipient should not have GlobalUserId");

    LOG.debug("Mixed auto-detection passed — {} recipients resolved", recipients.size());
  }

  // ──────────────────────────────────────────────────────────────
  // Navigation Target Tests (@Common.SemanticObject / Action)
  // ──────────────────────────────────────────────────────────────

  @Test
  void testNavigationTargetFromSemanticObjectAnnotation() {
    LOG.debug("==========================================");
    LOG.debug("Test: @Common.SemanticObject should map to NavigationTargetObject/Action");
    LOG.debug("==========================================");

    // Given: CertificateExpiration has @Common.SemanticObject:'project1' and
    // @Common.SemanticObjectAction:'display'
    CertificateExpiration data = CertificateExpirationTestData.createValidCertificateExpiration();

    CertificateExpirationContext ctx = CertificateExpirationContext.create();
    ctx.setData(data);

    // When
    notificationService.emit(ctx);

    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);

    // Then: Notification should have navigation target fields set
    Notifications stored = NotificationProviderServiceMockHandler.getAllNotifications().get(0);

    assertEquals(
        "project1",
        stored.getNavigationTargetObject(),
        "NavigationTargetObject should be mapped from @Common.SemanticObject");
    assertEquals(
        "display",
        stored.getNavigationTargetAction(),
        "NavigationTargetAction should be mapped from @Common.SemanticObjectAction");

    LOG.debug(
        "Navigation target: object={}, action={}",
        stored.getNavigationTargetObject(),
        stored.getNavigationTargetAction());
  }

  @Test
  void testNoNavigationTargetWhenAnnotationMissing() {
    LOG.debug("==========================================");
    LOG.debug(
        "Test: Notification without @Common.SemanticObject should have null navigation target");
    LOG.debug("==========================================");

    // Given: SystemMaintenance has NO @Common.SemanticObject annotation
    SystemMaintenance data = SystemMaintenanceTestData.createValid();

    SystemMaintenanceContext ctx = SystemMaintenanceContext.create();
    ctx.setData(data);

    // When
    notificationService.emit(ctx);

    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);

    // Then: Navigation target fields should be null
    Notifications stored = NotificationProviderServiceMockHandler.getAllNotifications().get(0);

    assertNull(
        stored.getNavigationTargetObject(),
        "NavigationTargetObject should be null when @Common.SemanticObject is missing");
    assertNull(
        stored.getNavigationTargetAction(),
        "NavigationTargetAction should be null when @Common.SemanticObjectAction is missing");

    LOG.debug(
        "No navigation target — correct behavior for events without semantic object annotation");
  }

  // ──────────────────────────────────────────────────────────────
  // Dynamic Priority Tests (CDS Expression Evaluation)
  // ──────────────────────────────────────────────────────────────

  @Test
  void testDynamicPriority_HighWhenYearAbove2025() {
    LOG.debug("==========================================");
    LOG.debug("Test: Dynamic priority should be HIGH when year > 2025");
    LOG.debug("==========================================");

    // Given: CertificateExpiration with year = 2026
    // CDS annotation: priority : (year > 2025 ? 'HIGH' : 'LOW')
    CertificateExpiration data = CertificateExpirationTestData.builder().year(2026).build();

    CertificateExpirationContext ctx = CertificateExpirationContext.create();
    ctx.setData(data);

    // When
    notificationService.emit(ctx);

    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);

    // Then: Priority should be HIGH (year > 2025)
    Notifications stored = NotificationProviderServiceMockHandler.getAllNotifications().get(0);

    LOG.debug("Dynamic priority result: {}", stored.getPriority());

    assertNotNull(stored.getPriority(), "Priority should not be null");
    assertEquals("HIGH", stored.getPriority(), "Priority should be HIGH when year > 2025");
  }

  @Test
  void testDynamicPriority_LowWhenYearNotAbove2025() {
    LOG.debug("==========================================");
    LOG.debug("Test: Dynamic priority should be LOW when year <= 2025");
    LOG.debug("==========================================");

    // Given: CertificateExpiration with year = 2024
    // CDS annotation: priority : (year > 2025 ? 'HIGH' : 'LOW')
    CertificateExpiration data = CertificateExpirationTestData.builder().year(2024).build();

    CertificateExpirationContext ctx = CertificateExpirationContext.create();
    ctx.setData(data);

    // When
    notificationService.emit(ctx);

    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);

    // Then: Priority should be LOW (year <= 2025)
    Notifications stored = NotificationProviderServiceMockHandler.getAllNotifications().get(0);

    LOG.debug("Dynamic priority result: {}", stored.getPriority());

    assertNotNull(stored.getPriority(), "Priority should not be null");
    assertEquals("LOW", stored.getPriority(), "Priority should be LOW when year <= 2025");
  }

  // ──────────────────────────────────────────────────────────────
  // Batch Notification Emit Tests
  // ──────────────────────────────────────────────────────────────

  @Test
  void testBatchNotificationEmit() {
    LOG.debug("==========================================");
    LOG.debug("Test: Batch emit — multiple notifications in a single emit call");
    LOG.debug("==========================================");

    // Given: Create 3 certificate expiration payloads with different recipients
    List<CertificateExpiration> batch = CertificateExpirationTestData.createBatchOfThree();

    // When: Emit all 3 notifications in a single batch emit
    EventContext batchCtx = EventContext.create("CertificateExpiration", null);
    batchCtx.put("data", batch);
    notificationService.emit(batchCtx);

    // Wait for async event processing
    await()
        .atMost(5, SECONDS)
        .pollInterval(100, java.util.concurrent.TimeUnit.MILLISECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() >= 3);

    // Then: Verify all 3 notifications were stored
    List<Notifications> allNotifications =
        NotificationProviderServiceMockHandler.getAllNotifications();
    assertEquals(3, allNotifications.size(), "Batch emit should create 3 notifications");

    // Verify each notification has the correct type key
    for (Notifications notification : allNotifications) {
      assertEquals(
          "CertificateExpiration",
          notification.getNotificationTypeKey(),
          "Each notification should have correct type key");
      assertNotNull(notification.getId(), "Each notification should have an ID");
    }

    LOG.debug(
        "Batch emit test passed — {} notifications created in single emit",
        allNotifications.size());
  }

  @Test
  void testBatchNotificationEmitPreservesIndividualData() {
    LOG.debug("==========================================");
    LOG.debug("Test: Batch emit preserves individual notification data");
    LOG.debug("==========================================");

    // Given: 2 certificate expirations with different data
    List<CertificateExpiration> batch = CertificateExpirationTestData.createAliceAndBob();

    // When: Batch emit
    EventContext batchCtx = EventContext.create("CertificateExpiration", null);
    batchCtx.put("data", batch);
    notificationService.emit(batchCtx);

    await()
        .atMost(5, SECONDS)
        .pollInterval(100, java.util.concurrent.TimeUnit.MILLISECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() >= 2);

    // Then: Verify each notification has distinct data
    List<Notifications> notifications =
        NotificationProviderServiceMockHandler.getNotificationsByTypeKey("CertificateExpiration");
    assertEquals(2, notifications.size(), "Should have 2 notifications");

    // Check that recipients are different
    Set<String> recipientIds = new java.util.HashSet<>();
    for (Notifications n : notifications) {
      assertNotNull(n.getRecipients(), "Recipients should not be null");
      assertFalse(n.getRecipients().isEmpty(), "Recipients should not be empty");
      recipientIds.add(n.getRecipients().get(0).getRecipientId());
    }
    assertEquals(2, recipientIds.size(), "Each notification should have a different recipient");
    assertTrue(recipientIds.contains("alice@example.com"), "Should contain Alice's email");
    assertTrue(recipientIds.contains("bob@example.com"), "Should contain Bob's email");

    LOG.debug("Batch data preservation test passed — individual data correctly isolated");
  }

  // ──────────────────────────────────────────────────────────────
  // Date/Time Function Priority Tests (days_between)
  // ──────────────────────────────────────────────────────────────

  @Test
  void testDynamicPriority_HighWhenDeadlineWithin30Days() {
    LOG.debug("==========================================");
    LOG.debug("Test: days_between — HIGH priority when deadline < 30 days away");
    LOG.debug("==========================================");

    // Given: ContractDeadline with deadline 10 days from now
    // CDS annotation: priority : (days_between($now, deadlineDate) < 30 ? 'HIGH' : 'LOW')
    ContractDeadline data = ContractDeadlineTestData.createWithNearDeadline();

    ContractDeadlineContext ctx = ContractDeadlineContext.create();
    ctx.setData(data);

    // When
    notificationService.emit(ctx);

    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);

    // Then: Priority should be HIGH (deadline within 30 days)
    Notifications stored = NotificationProviderServiceMockHandler.getAllNotifications().get(0);

    LOG.debug("days_between priority result: {}", stored.getPriority());

    assertNotNull(stored.getPriority(), "Priority should not be null");
    assertEquals("HIGH", stored.getPriority(), "Priority should be HIGH when deadline < 30 days");
  }

  @Test
  void testDynamicPriority_LowWhenDeadlineFarAway() {
    LOG.debug("==========================================");
    LOG.debug("Test: days_between — LOW priority when deadline >= 30 days away");
    LOG.debug("==========================================");

    // Given: ContractDeadline with deadline 90 days from now
    // CDS annotation: priority : (days_between($now, deadlineDate) < 30 ? 'HIGH' : 'LOW')
    ContractDeadline data = ContractDeadlineTestData.createWithFarDeadline();

    ContractDeadlineContext ctx = ContractDeadlineContext.create();
    ctx.setData(data);

    // When
    notificationService.emit(ctx);

    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);

    // Then: Priority should be LOW (deadline far away)
    Notifications stored = NotificationProviderServiceMockHandler.getAllNotifications().get(0);

    LOG.debug("days_between priority result: {}", stored.getPriority());

    assertNotNull(stored.getPriority(), "Priority should not be null");
    assertEquals("LOW", stored.getPriority(), "Priority should be LOW when deadline >= 30 days");
  }

  // ──────────────────────────────────────────────────────────────
  // String Function Priority Tests (contains)
  // ──────────────────────────────────────────────────────────────

  @Test
  void testDynamicPriority_HighWhenImpactContainsCritical() {
    LOG.debug("==========================================");
    LOG.debug("Test: contains — HIGH priority when impact contains 'critical'");
    LOG.debug("==========================================");

    // Given: SystemMaintenance with impact containing 'critical'
    // CDS annotation: priority : (contains(impact, 'critical') ? 'HIGH' : 'MEDIUM')
    SystemMaintenance data = SystemMaintenanceTestData.createWithCriticalImpact();

    SystemMaintenanceContext ctx = SystemMaintenanceContext.create();
    ctx.setData(data);

    // When
    notificationService.emit(ctx);

    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);

    // Then
    Notifications stored = NotificationProviderServiceMockHandler.getAllNotifications().get(0);
    LOG.debug("contains priority result: {}", stored.getPriority());

    assertNotNull(stored.getPriority(), "Priority should not be null");
    assertEquals(
        "HIGH", stored.getPriority(), "Priority should be HIGH when impact contains 'critical'");
  }

  @Test
  void testDynamicPriority_MediumWhenImpactDoesNotContainCritical() {
    LOG.debug("==========================================");
    LOG.debug("Test: contains — MEDIUM priority when impact does not contain 'critical'");
    LOG.debug("==========================================");

    // Given: SystemMaintenance with impact NOT containing 'critical'
    SystemMaintenance data = SystemMaintenanceTestData.createValid();

    SystemMaintenanceContext ctx = SystemMaintenanceContext.create();
    ctx.setData(data);

    // When
    notificationService.emit(ctx);

    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);

    // Then
    Notifications stored = NotificationProviderServiceMockHandler.getAllNotifications().get(0);
    LOG.debug("contains priority result: {}", stored.getPriority());

    assertNotNull(stored.getPriority(), "Priority should not be null");
    assertEquals(
        "MEDIUM",
        stored.getPriority(),
        "Priority should be MEDIUM when impact does not contain 'critical'");
  }

  // ──────────────────────────────────────────────────────────────
  // String Function Priority Tests (startsWith)
  // ──────────────────────────────────────────────────────────────

  @Test
  void testDynamicPriority_HighWhenSeverityStartsWithCrit() {
    LOG.debug("==========================================");
    LOG.debug("Test: startsWith — HIGH priority when severity starts with 'CRIT'");
    LOG.debug("==========================================");

    // Given: SecurityAlert with severity starting with 'CRIT'
    // CDS annotation: priority : (startsWith(severity, 'CRIT') ? 'HIGH' : 'LOW')
    SecurityAlert data = SecurityAlertTestData.createWithCriticalSeverity();

    SecurityAlertContext ctx = SecurityAlertContext.create();
    ctx.setData(data);

    // When
    notificationService.emit(ctx);

    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);

    // Then
    Notifications stored = NotificationProviderServiceMockHandler.getAllNotifications().get(0);
    LOG.debug("startsWith priority result: {}", stored.getPriority());

    assertNotNull(stored.getPriority(), "Priority should not be null");
    assertEquals(
        "HIGH", stored.getPriority(), "Priority should be HIGH when severity starts with 'CRIT'");
  }

  @Test
  void testDynamicPriority_LowWhenSeverityDoesNotStartWithCrit() {
    LOG.debug("==========================================");
    LOG.debug("Test: startsWith — LOW priority when severity does not start with 'CRIT'");
    LOG.debug("==========================================");

    // Given: SecurityAlert with severity NOT starting with 'CRIT'
    SecurityAlert data = SecurityAlertTestData.createWithLowSeverity();

    SecurityAlertContext ctx = SecurityAlertContext.create();
    ctx.setData(data);

    // When
    notificationService.emit(ctx);

    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);

    // Then
    Notifications stored = NotificationProviderServiceMockHandler.getAllNotifications().get(0);
    LOG.debug("startsWith priority result: {}", stored.getPriority());

    assertNotNull(stored.getPriority(), "Priority should not be null");
    assertEquals(
        "LOW",
        stored.getPriority(),
        "Priority should be LOW when severity does not start with 'CRIT'");
  }

  // ──────────────────────────────────────────────────────────────
  // String Function Priority Tests (endsWith)
  // ──────────────────────────────────────────────────────────────

  @Test
  void testDynamicPriority_HighWhenServerNameEndsWithProd() {
    LOG.debug("==========================================");
    LOG.debug("Test: endsWith — HIGH priority when serverName ends with '-prod'");
    LOG.debug("==========================================");

    // Given: ServerIncident with server name ending in '-prod'
    // CDS annotation: priority : (endsWith(serverName, '-prod') ? 'HIGH' : 'LOW')
    ServerIncident data = ServerIncidentTestData.createWithProdServer();

    ServerIncidentContext ctx = ServerIncidentContext.create();
    ctx.setData(data);

    // When
    notificationService.emit(ctx);

    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);

    // Then
    Notifications stored = NotificationProviderServiceMockHandler.getAllNotifications().get(0);
    LOG.debug("endsWith priority result: {}", stored.getPriority());

    assertNotNull(stored.getPriority(), "Priority should not be null");
    assertEquals(
        "HIGH", stored.getPriority(), "Priority should be HIGH when serverName ends with '-prod'");
  }

  @Test
  void testDynamicPriority_LowWhenServerNameDoesNotEndWithProd() {
    LOG.debug("==========================================");
    LOG.debug("Test: endsWith — LOW priority when serverName does not end with '-prod'");
    LOG.debug("==========================================");

    // Given: ServerIncident with server name NOT ending in '-prod'
    ServerIncident data = ServerIncidentTestData.createWithDevServer();

    ServerIncidentContext ctx = ServerIncidentContext.create();
    ctx.setData(data);

    // When
    notificationService.emit(ctx);

    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);

    // Then
    Notifications stored = NotificationProviderServiceMockHandler.getAllNotifications().get(0);
    LOG.debug("endsWith priority result: {}", stored.getPriority());

    assertNotNull(stored.getPriority(), "Priority should not be null");
    assertEquals(
        "LOW",
        stored.getPriority(),
        "Priority should be LOW when serverName does not end with '-prod'");
  }

  // ──────────────────────────────────────────────────────────────
  // Priority Fallback Tests (missing field → NEUTRAL)
  // ──────────────────────────────────────────────────────────────

  @Test
  void testDynamicPriority_NeutralWhenRequiredFieldMissing() {
    LOG.debug("==========================================");
    LOG.debug("Test: NEUTRAL fallback when priority expression field is missing");
    LOG.debug("==========================================");

    // Given: SystemMaintenance WITHOUT impact field
    // CDS annotation: priority : (contains(impact, 'critical') ? 'HIGH' : 'MEDIUM')
    // Missing impact → ref unresolved → IllegalStateException → catch → NEUTRAL
    SystemMaintenance data = SystemMaintenanceTestData.createWithoutImpact();

    SystemMaintenanceContext ctx = SystemMaintenanceContext.create();
    ctx.setData(data);

    // When
    notificationService.emit(ctx);

    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);

    // Then: Priority should fall back to NEUTRAL
    Notifications stored = NotificationProviderServiceMockHandler.getAllNotifications().get(0);
    LOG.debug("Fallback priority result: {}", stored.getPriority());

    assertNotNull(stored.getPriority(), "Priority should not be null");
    assertEquals(
        "NEUTRAL",
        stored.getPriority(),
        "Priority should be NEUTRAL when a required field is missing from event data");
  }

  // ──────────────────────────────────────────────────────────────
  // Boundary Condition Tests
  // ──────────────────────────────────────────────────────────────

  @Test
  void testDynamicPriority_LowWhenDeadlineExactly30DaysAway() {
    LOG.debug("==========================================");
    LOG.debug("Test: days_between boundary — LOW when deadline is exactly 30 days away");
    LOG.debug("==========================================");

    // Given: ContractDeadline with deadline exactly 30 days from now
    // CDS annotation: priority : (days_between($now, deadlineDate) < 30 ? 'HIGH' : 'LOW')
    // days_between = 30, and 30 < 30 = false → LOW
    ContractDeadline data = ContractDeadlineTestData.createWithBoundaryDeadline();

    ContractDeadlineContext ctx = ContractDeadlineContext.create();
    ctx.setData(data);

    // When
    notificationService.emit(ctx);

    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);

    // Then: Priority should be LOW (30 < 30 is false)
    Notifications stored = NotificationProviderServiceMockHandler.getAllNotifications().get(0);
    LOG.debug("Boundary priority result: {}", stored.getPriority());

    assertNotNull(stored.getPriority(), "Priority should not be null");
    assertEquals(
        "LOW",
        stored.getPriority(),
        "Priority should be LOW when deadline is exactly 30 days away (30 < 30 = false)");
  }

  // ──────────────────────────────────────────────────────────────
  // Nested Function in Containment Tests — contains(concat(...))
  // ──────────────────────────────────────────────────────────────

  @Test
  void testDynamicPriority_HighWhenConcatContainsProdCritical() {
    LOG.debug("==========================================");
    LOG.debug("Test: contains(concat()) — HIGH when result contains 'PROD-critical'");
    LOG.debug("==========================================");

    // Given: environment="PROD", appName="critical-service"
    // concat → "PROD-critical-service" which contains "PROD-critical" → HIGH
    DeploymentNotification data = DeploymentNotificationTestData.createWithProdCritical();

    DeploymentNotificationContext ctx = DeploymentNotificationContext.create();
    ctx.setData(data);

    // When
    notificationService.emit(ctx);

    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);

    // Then
    Notifications stored = NotificationProviderServiceMockHandler.getAllNotifications().get(0);
    LOG.debug("contains(concat()) priority result: {}", stored.getPriority());

    assertNotNull(stored.getPriority(), "Priority should not be null");
    assertEquals(
        "HIGH",
        stored.getPriority(),
        "Priority should be HIGH when concat result contains 'PROD-critical'");
  }

  @Test
  void testDynamicPriority_LowWhenConcatDoesNotContainProdCritical() {
    LOG.debug("==========================================");
    LOG.debug("Test: contains(concat()) — LOW when result does not contain 'PROD-critical'");
    LOG.debug("==========================================");

    // Given: environment="DEV", appName="my-app"
    // concat → "DEV-my-app" which does NOT contain "PROD-critical" → LOW
    DeploymentNotification data = DeploymentNotificationTestData.createWithDevApp();

    DeploymentNotificationContext ctx = DeploymentNotificationContext.create();
    ctx.setData(data);

    // When
    notificationService.emit(ctx);

    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);

    // Then
    Notifications stored = NotificationProviderServiceMockHandler.getAllNotifications().get(0);
    LOG.debug("contains(concat()) priority result: {}", stored.getPriority());

    assertNotNull(stored.getPriority(), "Priority should not be null");
    assertEquals(
        "LOW",
        stored.getPriority(),
        "Priority should be LOW when concat result does not contain 'PROD-critical'");
  }

  @Test
  void testKeyFieldsAreSetAsTargetParameters() {
    // Given: CertificateExpiration with certId (key field)
    CertificateExpiration data = CertificateExpirationTestData.createValidCertificateExpiration();
    CertificateExpirationContext ctx = CertificateExpirationContext.create();
    ctx.setData(data);

    // When
    notificationService.emit(ctx);

    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);

    // Then: certId should be in TargetParameters, not in Properties
    Notifications stored = NotificationProviderServiceMockHandler.getAllNotifications().get(0);

    List<NavigationTargetParams> targetParams = stored.getTargetParameters();
    assertNotNull(targetParams, "TargetParameters should not be null");
    assertEquals(1, targetParams.size(), "Should have 1 target parameter (certId)");
    assertEquals("certId", targetParams.get(0).getKey(), "Target parameter key should be certId");
    assertEquals("cert-123", targetParams.get(0).getValue(), "Target parameter value should match");

    // certId should NOT be in Properties
    boolean certIdInProperties =
        stored.getProperties().stream().anyMatch(p -> "certId".equals(p.getKey()));
    assertFalse(certIdInProperties, "certId should not appear in Properties");
  }
}
