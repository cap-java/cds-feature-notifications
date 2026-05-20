/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package customer.sample_app.testdata;

import cds.gen.my.notifications.notificationservice.SecurityAlert;

/**
 * Test data for SecurityAlert event. Uses startsWith() in its CDS priority expression: {@code
 * startsWith(severity, 'CRIT') ? 'HIGH' : 'LOW'}
 */
public class SecurityAlertTestData {

  /** Creates a SecurityAlert with severity starting with 'CRIT' (→ HIGH priority). */
  public static SecurityAlert createWithCriticalSeverity() {
    SecurityAlert data = SecurityAlert.create();
    data.setRecipients("security@example.com");
    data.setSeverity("CRITICAL - Unauthorized access detected");
    data.setAlertSource("Firewall");
    data.setDescription("Multiple failed login attempts from unknown IP");
    return data;
  }

  /** Creates a SecurityAlert with severity NOT starting with 'CRIT' (→ LOW priority). */
  public static SecurityAlert createWithLowSeverity() {
    SecurityAlert data = SecurityAlert.create();
    data.setRecipients("security@example.com");
    data.setSeverity("INFO - Routine scan completed");
    data.setAlertSource("Antivirus");
    data.setDescription("Weekly scan completed with no findings");
    return data;
  }
}
