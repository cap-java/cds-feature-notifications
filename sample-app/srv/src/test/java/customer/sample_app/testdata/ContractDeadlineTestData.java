/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package customer.sample_app.testdata;

import cds.gen.my.notifications.notificationservice.ContractDeadline;
import java.time.Duration;
import java.time.Instant;

/**
 * Test data builder for creating ContractDeadline test objects. Uses days_between() date/time
 * function in its CDS priority expression.
 */
public class ContractDeadlineTestData {

  /** Creates a ContractDeadline with a deadline in the near future (< 30 days → HIGH priority). */
  public static ContractDeadline createWithNearDeadline() {
    ContractDeadline data = ContractDeadline.create();
    data.setRecipients("legal@example.com");
    data.setContractName("SLA Agreement");
    data.setDeadlineDate(Instant.now().plus(Duration.ofDays(10)));
    data.setCounterparty("Acme Corp");
    return data;
  }

  /** Creates a ContractDeadline with a deadline far in the future (>= 30 days → LOW priority). */
  public static ContractDeadline createWithFarDeadline() {
    ContractDeadline data = ContractDeadline.create();
    data.setRecipients("legal@example.com");
    data.setContractName("Vendor Agreement");
    data.setDeadlineDate(Instant.now().plus(Duration.ofDays(90)));
    data.setCounterparty("Globex Inc");
    return data;
  }

  /**
   * Creates a ContractDeadline with deadline exactly 30 days away (boundary condition). {@code
   * days_between($now, deadlineDate) = 30}, and {@code 30 < 30 = false} → LOW priority.
   */
  public static ContractDeadline createWithBoundaryDeadline() {
    ContractDeadline data = ContractDeadline.create();
    data.setRecipients("legal@example.com");
    data.setContractName("Boundary Contract");
    data.setDeadlineDate(Instant.now().plus(Duration.ofDays(30)));
    data.setCounterparty("EdgeCase Ltd");
    return data;
  }
}
