/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package customer.sample_app.testdata;

import cds.gen.my.notifications.notificationservice.DeploymentNotification;

/**
 * Test data for DeploymentNotification event. Case 6: nested function in containment test —
 * contains(concat(environment, '-', appName), 'PROD-critical').
 */
public class DeploymentNotificationTestData {

  /**
   * Creates a deployment where concat(environment, '-', appName) is "PROD-critical-service" →
   * contains 'PROD-critical' → HIGH.
   */
  public static DeploymentNotification createWithProdCritical() {
    DeploymentNotification data = DeploymentNotification.create();
    data.setRecipients("ops@example.com");
    data.setEnvironment("PROD");
    data.setAppName("critical-service");
    data.setVersion("2.1.0");
    return data;
  }

  /**
   * Creates a deployment where concat(environment, '-', appName) is "DEV-my-app" → does NOT contain
   * 'PROD-critical' → LOW.
   */
  public static DeploymentNotification createWithDevApp() {
    DeploymentNotification data = DeploymentNotification.create();
    data.setRecipients("dev@example.com");
    data.setEnvironment("DEV");
    data.setAppName("my-app");
    data.setVersion("0.5.0");
    return data;
  }
}
