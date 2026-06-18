/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package customer.sample_app.testdata;

import cds.gen.my.notifications.notificationservice.SystemMaintenance;
import java.util.List;

/**
 * Test data for SystemMaintenance event. Case 2: recipients is array of String
 * (Collection<String>).
 */
public class SystemMaintenanceTestData {

  /** Creates a SystemMaintenance with multiple string recipients. */
  public static SystemMaintenance createValid() {
    SystemMaintenance data = SystemMaintenance.create();
    data.setRecipients(List.of("admin1@example.com", "admin2@example.com", "admin3@example.com"));
    data.setSystemName("Production ERP");
    data.setMaintenanceWindow("2026-04-01 02:00 - 06:00 UTC");
    data.setImpact("System will be unavailable during maintenance");
    return data;
  }

  /** Creates a SystemMaintenance with a single string in the list. */
  public static SystemMaintenance createWithSingleRecipient() {
    SystemMaintenance data = SystemMaintenance.create();
    data.setRecipients(List.of("solo-admin@example.com"));
    data.setSystemName("Dev Server");
    data.setMaintenanceWindow("2026-04-15 22:00 - 23:00 UTC");
    data.setImpact("Brief restart required");
    return data;
  }

  /** Creates a SystemMaintenance with mixed recipients (emails and UUIDs). */
  public static SystemMaintenance createWithMixedRecipients() {
    SystemMaintenance data = SystemMaintenance.create();
    data.setRecipients(
        List.of(
            "cfo@example.com", "550e8400-e29b-41d4-a716-446655440000", "finance-lead@example.com"));
    data.setSystemName("Finance Platform");
    data.setMaintenanceWindow("2026-06-01 03:00 - 05:00 UTC");
    data.setImpact("Scheduled upgrade");
    return data;
  }

  /**
   * Creates a SystemMaintenance with impact containing 'critical' (for contains() priority test).
   */
  public static SystemMaintenance createWithCriticalImpact() {
    SystemMaintenance data = SystemMaintenance.create();
    data.setRecipients(List.of("admin@example.com"));
    data.setSystemName("Production ERP");
    data.setMaintenanceWindow("2026-06-01 02:00 - 06:00 UTC");
    data.setImpact("critical outage expected");
    return data;
  }

  /** Creates a SystemMaintenance WITHOUT recipients (invalid for testing validation). */
  public static SystemMaintenance createWithoutRecipients() {
    SystemMaintenance data = SystemMaintenance.create();
    data.setSystemName("Test Server");
    data.setMaintenanceWindow("2026-05-01 00:00 - 04:00 UTC");
    data.setImpact("No impact");
    return data;
  }

  /**
   * Creates a SystemMaintenance WITHOUT the impact field. The priority expression {@code
   * contains(impact, 'critical')} will fail to resolve, triggering the NEUTRAL fallback.
   */
  public static SystemMaintenance createWithoutImpact() {
    SystemMaintenance data = SystemMaintenance.create();
    data.setRecipients(List.of("admin@example.com"));
    data.setSystemName("Production ERP");
    data.setMaintenanceWindow("2026-06-01 02:00 - 06:00 UTC");
    // impact intentionally omitted
    return data;
  }
}
