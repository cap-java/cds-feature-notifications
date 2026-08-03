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
import cds.gen.sap.cds.notifications.Notifications_;
import com.sap.cds.CdsData;
import com.sap.cds.ql.Select;
import com.sap.cds.services.persistence.PersistenceService;
import customer.sample_app.testdata.CertificateExpirationTestData;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests verifying that notifications are stored to DB in local mode when
 * storeNotifications is enabled.
 */
@SpringBootTest
@ActiveProfiles("default")
public class StoreNotificationsLocalModeIntegrationTest {

  @Autowired private NotificationService.Application notificationService;
  @Autowired private PersistenceService persistenceService;

  @Test
  void testNotificationIsStoredToDbInLocalMode() {
    // Given — unique recipient to isolate from other tests
    CertificateExpiration data =
        CertificateExpirationTestData.builder()
            .recipients("local-store-test@example.com")
            .certId("cert-local-1")
            .build();
    CertificateExpirationContext ctx = CertificateExpirationContext.create();
    ctx.setData(data);

    // When
    notificationService.emit(ctx);

    await()
        .atMost(5, SECONDS)
        .until(
            () ->
                !persistenceService
                    .run(
                        Select.from(Notifications_.CDS_NAME)
                            .where(n -> n.get("recipient").eq("local-store-test@example.com")))
                    .listOf(CdsData.class)
                    .isEmpty());

    // Then: one row in DB for this recipient
    List<CdsData> rows =
        persistenceService
            .run(
                Select.from(Notifications_.CDS_NAME)
                    .where(n -> n.get("recipient").eq("local-store-test@example.com")))
            .listOf(CdsData.class);
    assertEquals(1, rows.size(), "Should have 1 stored notification in local mode");

    CdsData row = rows.get(0);
    assertNotNull(row.get("ID"), "ID should be generated locally");
    assertEquals("local-store-test@example.com", row.get("recipient"));
    assertEquals("CertificateExpiration", row.get("notificationTypeKey"));
    assertNotNull(row.get("sentAt"), "sentAt should not be null");
  }
}
