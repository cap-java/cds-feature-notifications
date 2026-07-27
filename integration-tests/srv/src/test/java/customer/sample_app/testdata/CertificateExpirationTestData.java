/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package customer.sample_app.testdata;

import cds.gen.my.notifications.notificationservice.CertificateExpiration;
import java.time.LocalDate;
import java.util.List;

/**
 * Test data builder for creating CertificateExpiration test objects. Case 1: recipients is a single
 * String.
 */
public class CertificateExpirationTestData {

  /** Creates a valid CertificateExpiration (email recipient). */
  public static CertificateExpiration createValidCertificateExpiration() {
    CertificateExpiration data = CertificateExpiration.create();
    data.setRecipients("test@example.com");
    data.setName("Test User");
    data.setCertId("cert-123");
    data.setCertificateName("SSL Certificate");
    data.setExpirationDate(LocalDate.of(2026, 12, 31));
    data.setRenewLink("https://example.com/renew");
    data.setYear(2026);
    data.setCompanyName("SAP SE");
    return data;
  }

  /** Creates a CertificateExpiration with a UUID recipient (for auto-detection tests). */
  public static CertificateExpiration createWithUUIDRecipient() {
    CertificateExpiration data = CertificateExpiration.create();
    data.setRecipients("550e8400-e29b-41d4-a716-446655440000");
    data.setName("UUID User");
    data.setCertId("cert-456");
    data.setCertificateName("SSL Certificate");
    data.setExpirationDate(LocalDate.of(2026, 12, 31));
    data.setRenewLink("https://example.com/renew");
    data.setYear(2026);
    data.setCompanyName("SAP SE");
    return data;
  }

  /** Creates a CertificateExpiration WITHOUT recipients (invalid for testing validation). */
  public static CertificateExpiration createWithoutRecipients() {
    CertificateExpiration data = CertificateExpiration.create();
    data.setName("Admin User");
    data.setCertId("cert-789");
    data.setCertificateName("SSL Certificate");
    data.setExpirationDate(LocalDate.of(2026, 12, 31));
    data.setRenewLink("https://example.com/renew");
    data.setYear(2026);
    data.setCompanyName("SAP SE");
    return data;
  }

  /** Creates a batch of 3 CertificateExpirations with distinct recipients for batch emit tests. */
  public static List<CertificateExpiration> createBatchOfThree() {
    return List.of(
        builder().recipients("user1@example.com").name("User 1").certificateName("Cert A").build(),
        builder().recipients("user2@example.com").name("User 2").certificateName("Cert B").build(),
        builder().recipients("user3@example.com").name("User 3").certificateName("Cert C").build());
  }

  /**
   * Creates 2 CertificateExpirations with distinct data (Alice/2026, Bob/2024) for verifying batch
   * emit preserves individual data.
   */
  public static List<CertificateExpiration> createAliceAndBob() {
    return List.of(
        builder()
            .recipients("alice@example.com")
            .name("Alice")
            .certificateName("SSL Cert")
            .year(2026)
            .build(),
        builder()
            .recipients("bob@example.com")
            .name("Bob")
            .certificateName("TLS Cert")
            .year(2024)
            .build());
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private String recipients = "default@example.com";
    private String name = "Default User";
    private String certId = "cert-default";
    private String certificateName = "Default Certificate";
    private LocalDate expirationDate = LocalDate.of(2026, 12, 31);
    private String renewLink = "https://example.com/renew";
    private Integer year = 2026;
    private String companyName = "SAP SE";

    public Builder recipients(String recipients) {
      this.recipients = recipients;
      return this;
    }

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Builder certId(String certId) {
      this.certId = certId;
      return this;
    }

    public Builder certificateName(String certificateName) {
      this.certificateName = certificateName;
      return this;
    }

    public Builder expirationDate(LocalDate expirationDate) {
      this.expirationDate = expirationDate;
      return this;
    }

    public Builder renewLink(String renewLink) {
      this.renewLink = renewLink;
      return this;
    }

    public Builder year(Integer year) {
      this.year = year;
      return this;
    }

    public Builder companyName(String companyName) {
      this.companyName = companyName;
      return this;
    }

    public CertificateExpiration build() {
      CertificateExpiration data = CertificateExpiration.create();
      data.setRecipients(recipients);
      data.setName(name);
      data.setCertId(certId);
      data.setCertificateName(certificateName);
      data.setExpirationDate(expirationDate);
      data.setRenewLink(renewLink);
      data.setYear(year);
      data.setCompanyName(companyName);
      return data;
    }
  }
}
