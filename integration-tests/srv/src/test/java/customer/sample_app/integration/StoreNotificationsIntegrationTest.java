/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package customer.sample_app.integration;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import cds.gen.my.notifications.notificationservice.CertificateExpiration;
import cds.gen.my.notifications.notificationservice.CertificateExpirationContext;
import cds.gen.my.notifications.notificationservice.NotificationService;
import cds.gen.my.notifications.notificationservice.SystemMaintenance;
import cds.gen.my.notifications.notificationservice.SystemMaintenanceContext;
import cds.gen.sap.cds.notifications.NotificationProperties_;
import cds.gen.sap.cds.notifications.NotificationTargetParameters_;
import cds.gen.sap.cds.notifications.Notifications_;
import com.sap.cds.CdsData;
import com.sap.cds.ql.Select;
import com.sap.cds.services.persistence.PersistenceService;
import customer.sample_app.handlers.mock.NotificationProviderServiceMockHandler;
import customer.sample_app.testdata.CertificateExpirationTestData;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests verifying that notifications are stored to DB when storeNotifications is
 * enabled.
 */
@SpringBootTest
@ActiveProfiles("test")
public class StoreNotificationsIntegrationTest {

  @Autowired private NotificationService.Application notificationService;
  @Autowired private PersistenceService persistenceService;

  @BeforeEach
  void setup() {
    NotificationProviderServiceMockHandler.clearAllNotifications();
  }

  @Test
  void testNotificationIsStoredToDb() {
    // Given — unique recipient to isolate from other tests
    CertificateExpiration data =
        CertificateExpirationTestData.builder()
            .recipients("store-test-1@example.com")
            .certId("cert-store-1")
            .build();
    CertificateExpirationContext ctx = CertificateExpirationContext.create();
    ctx.setData(data);

    // When
    notificationService.emit(ctx);

    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);

    // Then: one row in DB for this recipient
    List<CdsData> rows =
        persistenceService
            .run(
                Select.from(Notifications_.CDS_NAME)
                    .where(n -> n.get("recipient").eq("store-test-1@example.com")))
            .listOf(CdsData.class);
    assertEquals(1, rows.size(), "Should have 1 stored notification");

    CdsData row = rows.get(0);
    assertNotNull(row.get("ID"), "ID should not be null");
    assertEquals("store-test-1@example.com", row.get("recipient"));
    assertEquals("CertificateExpiration", row.get("notificationTypeKey"));
    assertNotNull(row.get("sentAt"), "sentAt should not be null");
  }

  @Test
  void testPropertiesAreStoredToDb() {
    // Given
    CertificateExpiration data =
        CertificateExpirationTestData.builder()
            .recipients("store-test-2@example.com")
            .certId("cert-store-2")
            .build();
    CertificateExpirationContext ctx = CertificateExpirationContext.create();
    ctx.setData(data);

    // When
    notificationService.emit(ctx);

    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);

    // Then: non-key fields stored as properties
    List<CdsData> props =
        persistenceService
            .run(
                Select.from(NotificationProperties_.CDS_NAME)
                    .where(p -> p.get("notification_recipient").eq("store-test-2@example.com")))
            .listOf(CdsData.class);
    assertFalse(props.isEmpty(), "Should have stored notification properties");

    // certId is a key field — should NOT be in properties
    boolean certIdInProperties =
        props.stream().anyMatch(p -> "certId".equals(p.get("propertyKey")));
    assertFalse(certIdInProperties, "certId (key field) should not appear in properties");
  }

  @Test
  void testTargetParametersAreStoredToDb() {
    // Given: CertificateExpiration has key certId
    CertificateExpiration data =
        CertificateExpirationTestData.builder()
            .recipients("store-test-3@example.com")
            .certId("cert-store-3")
            .build();
    CertificateExpirationContext ctx = CertificateExpirationContext.create();
    ctx.setData(data);

    // When
    notificationService.emit(ctx);

    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);

    // Then: certId stored as target parameter
    List<CdsData> params =
        persistenceService
            .run(
                Select.from(NotificationTargetParameters_.CDS_NAME)
                    .where(p -> p.get("notification_recipient").eq("store-test-3@example.com")))
            .listOf(CdsData.class);
    assertEquals(1, params.size(), "Should have 1 target parameter (certId)");
    assertEquals("certId", params.get(0).get("paramKey"));
    assertEquals("cert-store-3", params.get(0).get("paramValue"));
  }

  @Test
  void testMultipleRecipientsCreateMultipleDbRows() {
    // Given: SystemMaintenance has array of String recipients
    SystemMaintenance data = SystemMaintenance.create();
    data.setRecipients(
        List.of(
            "store-multi-1@example.com", "store-multi-2@example.com", "store-multi-3@example.com"));
    data.setSystemName("TestSystem");
    data.setMaintenanceWindow("2026-08-01 02:00-04:00");
    data.setImpact("low");

    SystemMaintenanceContext ctx = SystemMaintenanceContext.create();
    ctx.setData(data);

    // When
    notificationService.emit(ctx);

    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);

    // Then: 3 rows in DB — one per recipient
    List<CdsData> rows =
        persistenceService
            .run(
                Select.from(Notifications_.CDS_NAME)
                    .where(
                        n ->
                            n.get("recipient")
                                .eq("store-multi-1@example.com")
                                .or(n.get("recipient").eq("store-multi-2@example.com"))
                                .or(n.get("recipient").eq("store-multi-3@example.com"))))
            .listOf(CdsData.class);
    assertEquals(3, rows.size(), "Should have 3 stored notifications — one per recipient");

    // All should have the same notification ID
    long distinctIds = rows.stream().map(r -> r.get("ID")).distinct().count();
    assertEquals(1, distinctIds, "All rows should share the same notification ID");

    // Each should have a different recipient
    long distinctRecipients = rows.stream().map(r -> r.get("recipient")).distinct().count();
    assertEquals(3, distinctRecipients, "Each row should have a distinct recipient");
  }
}
