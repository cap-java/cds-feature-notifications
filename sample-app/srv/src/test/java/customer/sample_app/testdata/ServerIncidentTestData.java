/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package customer.sample_app.testdata;

import cds.gen.my.notifications.notificationservice.ServerIncident;

/**
 * Test data for ServerIncident event. Uses endsWith() in its CDS priority expression: {@code
 * endsWith(serverName, '-prod') ? 'HIGH' : 'LOW'}
 */
public class ServerIncidentTestData {

  /** Creates a ServerIncident with server name ending in '-prod' (→ HIGH priority). */
  public static ServerIncident createWithProdServer() {
    ServerIncident data = ServerIncident.create();
    data.setRecipients("ops@example.com");
    data.setServerName("app-server-prod");
    data.setIncidentType("CPU Overload");
    data.setDescription("CPU usage exceeded 95% for 10 minutes");
    return data;
  }

  /** Creates a ServerIncident with server name NOT ending in '-prod' (→ LOW priority). */
  public static ServerIncident createWithDevServer() {
    ServerIncident data = ServerIncident.create();
    data.setRecipients("ops@example.com");
    data.setServerName("app-server-dev");
    data.setIncidentType("CPU Overload");
    data.setDescription("CPU usage exceeded 95% for 10 minutes");
    return data;
  }
}
