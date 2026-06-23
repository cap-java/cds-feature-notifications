/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package customer.sample_app.integration;

import static org.junit.jupiter.api.Assertions.*;

import cds.gen.notificationtypeproviderservice.NotificationTypeProviderService;
import cds.gen.notificationtypeproviderservice.NotificationTypes;
import com.sap.cds.notifications.handlers.NotificationTypeAutoProvisionerHandler;
import com.sap.cds.services.runtime.CdsRuntime;
import customer.sample_app.handlers.mock.NotificationTypeProviderServiceMockHandler;
import java.util.*;
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
 * (existing) / INSERT (new) flow and that each notification type gets its own unique ID.
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

  private NotificationTypeAutoProvisionerHandler createProvisioner() {
    return new NotificationTypeAutoProvisionerHandler(cdsRuntime, notificationTypeProviderService);
  }

  // ──────────────────────────────────────────────────────────────
  // Test 1: Each notification type must have a unique ID
  // ──────────────────────────────────────────────────────────────

  @Test
  void testEachNotificationTypeHasUniqueId() {
    LOG.debug("==========================================");
    LOG.debug("Test: Each notification type should have a unique NotificationTypeId");
    LOG.debug("==========================================");

    List<NotificationTypes> allTypes =
        NotificationTypeProviderServiceMockHandler.getAllNotificationTypes();
    assertEquals(
        EXPECTED_KEYS.size(),
        allTypes.size(),
        "All expected notification types should be provisioned");

    Map<String, String> keyToId = new HashMap<>();
    for (NotificationTypes nt : allTypes) {
      String key = nt.getNotificationTypeKey();
      String id = nt.getNotificationTypeId();

      assertNotNull(id, "NotificationTypeId should not be null for: " + key);
      assertFalse(id.isEmpty(), "NotificationTypeId should not be empty for: " + key);

      keyToId.put(key, id);
      LOG.debug("Type: {} → ID: {}", key, id);
    }

    Set<String> uniqueIds = new HashSet<>(keyToId.values());
    assertEquals(
        EXPECTED_KEYS.size(),
        uniqueIds.size(),
        "Each notification type must have a UNIQUE NotificationTypeId. Found IDs: " + keyToId);

    LOG.debug("All {} notification types have unique IDs", uniqueIds.size());
  }

  // ──────────────────────────────────────────────────────────────
  // Test 2: Re-provisioning uses delete+create (new IDs assigned)
  // ──────────────────────────────────────────────────────────────

  @Test
  void testReProvisioningUpdatesEachTypeCorrectly() {
    LOG.debug("==========================================");
    LOG.debug("Test: Re-provisioning should delete and recreate each type");
    LOG.debug("==========================================");

    Map<String, Integer> deletesBefore = new HashMap<>();
    for (String key : EXPECTED_KEYS) {
      deletesBefore.put(key, NotificationTypeProviderServiceMockHandler.getDeleteCount(key));
    }
    assertEquals(
        EXPECTED_KEYS.size(),
        NotificationTypeProviderServiceMockHandler.getNotificationTypeCount(),
        "All types should exist before re-provisioning");

    createProvisioner().onApplicationPrepared();

    for (String key : EXPECTED_KEYS) {
      int deletesAfter = NotificationTypeProviderServiceMockHandler.getDeleteCount(key);
      assertEquals(
          deletesBefore.get(key) + 1,
          deletesAfter,
          "NotificationType '" + key + "' should have been deleted once during re-provisioning");
    }

    assertEquals(
        EXPECTED_KEYS.size(),
        NotificationTypeProviderServiceMockHandler.getNotificationTypeCount(),
        "All types should still exist after re-provisioning (delete+create)");

    LOG.debug("Re-provisioning verified — all types were deleted and recreated");
  }

  // ──────────────────────────────────────────────────────────────
  // Test 3: Re-provisioning deletes and recreates all types
  // ──────────────────────────────────────────────────────────────

  @Test
  void testReProvisioningUpdatesAllTypes() {
    LOG.debug("==========================================");
    LOG.debug("Test: Re-provisioning should trigger DELETE+CREATE for each existing type");
    LOG.debug("==========================================");

    Map<String, Integer> deletesBefore = new HashMap<>();
    for (String key : EXPECTED_KEYS) {
      deletesBefore.put(key, NotificationTypeProviderServiceMockHandler.getDeleteCount(key));
    }

    createProvisioner().onApplicationPrepared();

    for (String key : EXPECTED_KEYS) {
      int before = deletesBefore.get(key);
      int after = NotificationTypeProviderServiceMockHandler.getDeleteCount(key);

      assertEquals(
          before + 1,
          after,
          "NotificationType '"
              + key
              + "' should have been deleted exactly once. Before: "
              + before
              + ", After: "
              + after);

      LOG.debug("Type '{}': delete count {} → {}", key, before, after);
    }

    LOG.debug(
        "All {} types were deleted and recreated during re-provisioning", EXPECTED_KEYS.size());
  }
}
