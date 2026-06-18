/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package customer.sample_app.integration;

import static org.junit.jupiter.api.Assertions.*;

import cds.gen.my.notifications.notificationservice.CertificateExpirationContext;
import cds.gen.my.notifications.notificationservice.NotificationService;
import com.sap.cds.notifications.handlers.LocalNotificationTemplateAutoProvisionerHandler;
import com.sap.cds.notifications.handlers.LocalNotificationTypeAutoProvisionerHandler;
import com.sap.cds.services.runtime.CdsRuntime;
import customer.sample_app.testdata.CertificateExpirationTestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for local mode (production.enabled: false). Verifies that LocalHandler,
 * LocalNotificationTypeAutoProvisionerHandler, and LocalNotificationTemplateAutoProvisionerHandler
 * start up and handle events without errors.
 */
@SpringBootTest
@ActiveProfiles("local")
public class LocalModeIntegrationTest {

  @Autowired private NotificationService.Application notificationService;

  @Autowired private CdsRuntime cdsRuntime;

  @Test
  void testLocalHandlerLogsNotification() {
    CertificateExpirationContext context = CertificateExpirationContext.create();
    context.setData(CertificateExpirationTestData.createValidCertificateExpiration());

    assertDoesNotThrow(
        () -> notificationService.emit(context),
        "LocalHandler should handle notification without errors in local mode");
  }

  @Test
  void testLocalNotificationTypeAutoProvisionerHandlerRunsOnStartup() {
    assertDoesNotThrow(
        () -> new LocalNotificationTypeAutoProvisionerHandler(cdsRuntime).onApplicationPrepared(),
        "LocalNotificationTypeAutoProvisionerHandler should run without errors in local mode");
  }

  @Test
  void testLocalNotificationTemplateAutoProvisionerHandlerRunsOnStartup() {
    assertDoesNotThrow(
        () ->
            new LocalNotificationTemplateAutoProvisionerHandler(cdsRuntime).onApplicationPrepared(),
        "LocalNotificationTemplateAutoProvisionerHandler should run without errors in local mode");
  }
}
