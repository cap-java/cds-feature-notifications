/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package customer.sample_app.integration;

import static org.junit.jupiter.api.Assertions.*;

import cds.gen.notificationtypeproviderservice.NotificationTypeProviderService;
import cds.gen.notificationtypeproviderservice.NotificationTypes;
import cds.gen.notificationtypeproviderservice.Templates;
import com.sap.cds.notifications.handlers.NotificationTypeAutoProvisionerHandler;
import com.sap.cds.services.runtime.CdsRuntime;
import customer.sample_app.handlers.mock.NotificationTypeProviderServiceMockHandler;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Tests for notification type auto-provisioning (SELECT → INSERT/UPDATE flow).
 *
 * <p>These tests specifically verify that the provisioner correctly handles the SELECT-all → UPDATE
 * (existing) / INSERT (new) flow and that each notification type gets its own unique ID and retains
 * its own data after updates.
 */
@SpringBootTest
@ActiveProfiles("test")
public class NotificationTypeProvisioningTest {

  private static final Logger LOG = LoggerFactory.getLogger(NotificationTypeProvisioningTest.class);

  @Autowired private CdsRuntime cdsRuntime;

  @Autowired private NotificationTypeProviderService notificationTypeProviderService;

  private static final Set<String> EXPECTED_KEYS =
      Set.of(
          "CertificateExpiration",
          "SystemMaintenance",
          "ContractDeadline",
          "SecurityAlert",
          "ServerIncident",
          "DeploymentNotification");

  /**
   * Creates a fresh provisioner handler (same as production code does). The handler is not a Spring
   * bean — it's created via new() in NotificationServiceConfiguration.
   */
  private NotificationTypeAutoProvisionerHandler createProvisioner() {
    return new NotificationTypeAutoProvisionerHandler(cdsRuntime, notificationTypeProviderService);
  }

  // ──────────────────────────────────────────────────────────────
  // Test 1: Each notification type must have a unique ID
  // ──────────────────────────────────────────────────────────────

  @Test
  void testEachNotificationTypeHasUniqueId() {
    LOG.info("==========================================");
    LOG.info("Test: Each notification type should have a unique NotificationTypeId");
    LOG.info("==========================================");

    List<NotificationTypes> allTypes =
        NotificationTypeProviderServiceMockHandler.getAllNotificationTypes();
    assertEquals(
        EXPECTED_KEYS.size(),
        allTypes.size(),
        "All expected notification types should be provisioned");

    // Collect all IDs
    Map<String, String> keyToId = new HashMap<>();
    for (NotificationTypes nt : allTypes) {
      String key = nt.getNotificationTypeKey();
      String id = nt.getNotificationTypeId();

      assertNotNull(id, "NotificationTypeId should not be null for: " + key);
      assertFalse(id.isEmpty(), "NotificationTypeId should not be empty for: " + key);

      keyToId.put(key, id);
      LOG.info("Type: {} → ID: {}", key, id);
    }

    // Verify all IDs are unique
    Set<String> uniqueIds = new HashSet<>(keyToId.values());
    assertEquals(
        EXPECTED_KEYS.size(),
        uniqueIds.size(),
        "Each notification type must have a UNIQUE NotificationTypeId. " + "Found IDs: " + keyToId);

    LOG.info("All {} notification types have unique IDs", uniqueIds.size());
  }

  // ──────────────────────────────────────────────────────────────
  // Test 2: Re-provisioning updates each type with correct data
  // ──────────────────────────────────────────────────────────────

  @Test
  void testReProvisioningUpdatesEachTypeCorrectly() {
    LOG.info("==========================================");
    LOG.info("Test: Re-provisioning should update each type with its own correct data");
    LOG.info("==========================================");

    // Record state BEFORE re-provisioning
    Map<String, String> idsBefore = new HashMap<>();
    for (NotificationTypes nt :
        NotificationTypeProviderServiceMockHandler.getAllNotificationTypes()) {
      idsBefore.put(nt.getNotificationTypeKey(), nt.getNotificationTypeId());
    }
    assertEquals(
        EXPECTED_KEYS.size(), idsBefore.size(), "All types should exist before re-provisioning");

    // Trigger re-provisioning (simulates app restart)
    LOG.info("Triggering re-provisioning...");
    createProvisioner().onApplicationPrepared();

    // Verify IDs remain the same (UPDATE, not new INSERT)
    Map<String, String> idsAfter = new HashMap<>();
    for (NotificationTypes nt :
        NotificationTypeProviderServiceMockHandler.getAllNotificationTypes()) {
      idsAfter.put(nt.getNotificationTypeKey(), nt.getNotificationTypeId());
    }

    assertEquals(
        idsBefore,
        idsAfter,
        "NotificationTypeIds should remain the same after re-provisioning (UPDATE, not INSERT)");

    // Verify each type's data is correct (not overwritten by another type)
    assertTypeDataIsCorrect(
        "CertificateExpiration", "Certificate Expiry", "Certificate: {{certificateName}}");
    assertTypeDataIsCorrect(
        "SystemMaintenance",
        "Maintenance Notice",
        "System maintenance scheduled for {{systemName}}");

    LOG.info("Re-provisioning verified — all types retain correct data");
  }

  // ──────────────────────────────────────────────────────────────
  // Test 3: Update count matches expected types
  // ──────────────────────────────────────────────────────────────

  @Test
  void testReProvisioningUpdatesAllTypes() {
    LOG.info("==========================================");
    LOG.info("Test: Re-provisioning should trigger UPDATE for each existing type");
    LOG.info("==========================================");

    // Record update counts before
    Map<String, Integer> countsBefore = new HashMap<>();
    for (String key : EXPECTED_KEYS) {
      countsBefore.put(key, NotificationTypeProviderServiceMockHandler.getUpdateCount(key));
    }

    // Trigger re-provisioning
    createProvisioner().onApplicationPrepared();

    // Each type should have been updated exactly once more
    for (String key : EXPECTED_KEYS) {
      int before = countsBefore.get(key);
      int after = NotificationTypeProviderServiceMockHandler.getUpdateCount(key);

      assertEquals(
          before + 1,
          after,
          "NotificationType '"
              + key
              + "' should have been updated exactly once. "
              + "Before: "
              + before
              + ", After: "
              + after);

      LOG.info("Type '{}': update count {} → {}", key, before, after);
    }

    LOG.info("All {} types were updated during re-provisioning", EXPECTED_KEYS.size());
  }

  // ──────────────────────────────────────────────────────────────
  // Test 4: No data cross-contamination between types
  // ──────────────────────────────────────────────────────────────

  @Test
  void testNoDataCrossContaminationBetweenTypes() {
    LOG.info("==========================================");
    LOG.info("Test: Each type's English template must contain its own specific content");
    LOG.info("==========================================");

    Map<String, String> typeToExpectedSensitive =
        Map.of(
            "CertificateExpiration", "certificateName",
            "SystemMaintenance", "systemName");

    for (Map.Entry<String, String> entry : typeToExpectedSensitive.entrySet()) {
      String typeKey = entry.getKey();
      String expectedVariable = entry.getValue();

      NotificationTypes nt =
          NotificationTypeProviderServiceMockHandler.getNotificationTypeByKeyVersion(typeKey, "1");
      assertNotNull(nt, "Type '" + typeKey + "' should exist");

      // Find the English template
      Templates enTemplate =
          nt.getTemplates().stream()
              .filter(t -> "en".equals(t.getLanguage()))
              .findFirst()
              .orElseThrow(() -> new AssertionError("No English template for " + typeKey));

      String sensitive = enTemplate.getTemplateSensitive();
      assertNotNull(sensitive, "TemplateSensitive should not be null for " + typeKey);

      assertTrue(
          sensitive.contains("{{" + expectedVariable + "}}"),
          "Type '"
              + typeKey
              + "' TemplateSensitive should contain '{{"
              + expectedVariable
              + "}}' "
              + "but was: '"
              + sensitive
              + "'. This indicates data cross-contamination from another type.");

      // Also verify publicTitle is unique per type
      String publicTitle = enTemplate.getTemplatePublic();
      LOG.info("[{}] publicTitle='{}', sensitive='{}'", typeKey, publicTitle, sensitive);
    }

    // Verify all publicTitles are distinct
    Set<String> publicTitles =
        EXPECTED_KEYS.stream()
            .map(
                key ->
                    NotificationTypeProviderServiceMockHandler.getNotificationTypeByKeyVersion(
                        key, "1"))
            .map(
                nt ->
                    nt.getTemplates().stream()
                        .filter(t -> "en".equals(t.getLanguage()))
                        .findFirst()
                        .map(Templates::getTemplatePublic)
                        .orElse(null))
            .collect(Collectors.toSet());

    assertEquals(
        EXPECTED_KEYS.size(),
        publicTitles.size(),
        "Each notification type must have a unique publicTitle. Found: " + publicTitles);

    LOG.info("No cross-contamination detected — all types have unique, correct data");
  }

  // ──────────────────────────────────────────────────────────────
  // Helper
  // ──────────────────────────────────────────────────────────────

  private void assertTypeDataIsCorrect(
      String typeKey, String expectedPublicTitle, String expectedSensitive) {
    NotificationTypes nt =
        NotificationTypeProviderServiceMockHandler.getNotificationTypeByKeyVersion(typeKey, "1");
    assertNotNull(nt, "Type '" + typeKey + "' should exist after re-provisioning");

    Templates enTemplate =
        nt.getTemplates().stream()
            .filter(t -> "en".equals(t.getLanguage()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No English template for " + typeKey));

    assertEquals(
        expectedPublicTitle,
        enTemplate.getTemplatePublic(),
        "PublicTitle mismatch for '"
            + typeKey
            + "' — data may have been overwritten by another type");
    assertEquals(
        expectedSensitive,
        enTemplate.getTemplateSensitive(),
        "SensitiveTitle mismatch for '"
            + typeKey
            + "' — data may have been overwritten by another type");

    LOG.info(
        "[{}] ✓ publicTitle='{}', sensitive='{}'", typeKey, expectedPublicTitle, expectedSensitive);
  }
}
