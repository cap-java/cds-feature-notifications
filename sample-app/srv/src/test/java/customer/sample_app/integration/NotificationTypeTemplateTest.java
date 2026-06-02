/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package customer.sample_app.integration;

import static org.junit.jupiter.api.Assertions.*;

import cds.gen.notificationtypeproviderservice.NotificationTypes;
import cds.gen.notificationtypeproviderservice.Templates;
import customer.sample_app.handlers.mock.NotificationTypeProviderServiceMockHandler;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Tests for notification type template resolution. Verifies i18n placeholders (build-time) and
 * Mustache variables (runtime) in templates.
 *
 * <p>i18n variables ({i18n>KEY}) are resolved by NotificationTypeBuilder at startup. Mustache
 * variables ({{variable}}) are kept as-is for ANS to resolve at runtime.
 */
@SpringBootTest
@ActiveProfiles("test")
public class NotificationTypeTemplateTest {

  private static final Logger LOG = LoggerFactory.getLogger(NotificationTypeTemplateTest.class);

  /** Pattern to detect unresolved i18n placeholders like {i18n>KEY} */
  private static final Pattern UNRESOLVED_I18N = Pattern.compile("\\{i18n>[^}]+\\}");

  // ──────────────────────────────────────────────────────────────
  // Helper methods
  // ──────────────────────────────────────────────────────────────

  private NotificationTypes getNotificationType(String key) {
    NotificationTypes nt =
        NotificationTypeProviderServiceMockHandler.getNotificationTypeByKeyVersion(key, "1");
    assertNotNull(nt, "Notification type '" + key + "' should be auto-provisioned at startup");
    return nt;
  }

  private Templates getTemplateForLanguage(NotificationTypes nt, String lang) {
    List<Templates> templates = nt.getTemplates();
    assertNotNull(templates, "Templates should not be null for " + nt.getNotificationTypeKey());

    return templates.stream()
        .filter(t -> lang.equals(t.getLanguage()))
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError(
                    "No template found for language '"
                        + lang
                        + "' in "
                        + nt.getNotificationTypeKey()));
  }

  private void assertNoUnresolvedI18n(String value, String fieldName, String lang) {
    if (value != null) {
      assertFalse(
          UNRESOLVED_I18N.matcher(value).find(),
          "Unresolved {i18n>...} placeholder in " + fieldName + " [" + lang + "]: " + value);
    }
  }

  // ──────────────────────────────────────────────────────────────
  // Test 1: i18n placeholders resolved for all languages
  // ──────────────────────────────────────────────────────────────

  @Test
  void testI18nPlaceholdersResolvedForAllLanguages() {
    LOG.debug("==========================================");
    LOG.debug("Test: i18n placeholders should be resolved for EN, DE, TR, ES");
    LOG.debug("==========================================");

    NotificationTypes nt = getNotificationType("CertificateExpiration");

    // Verify templates exist for all 4 languages
    Map<String, String> expectedSensitiveTitles =
        Map.of(
            "en", "Certificate: {{certificateName}}",
            "de", "Zertifikat: {{certificateName}}",
            "tr", "Sertifika: {{certificateName}}",
            "es", "Certificado: {{certificateName}}");

    for (Map.Entry<String, String> entry : expectedSensitiveTitles.entrySet()) {
      String lang = entry.getKey();
      String expectedTitle = entry.getValue();

      Templates template = getTemplateForLanguage(nt, lang);

      assertEquals(
          expectedTitle,
          template.getTemplateSensitive(),
          "TemplateSensitive should be resolved for language: " + lang);

      LOG.debug("[{}] TemplateSensitive: {}", lang, template.getTemplateSensitive());
    }

    // Also verify subtitle and other fields for specific languages
    Templates enTemplate = getTemplateForLanguage(nt, "en");
    assertEquals("Certificate Expiration", enTemplate.getSubtitle());
    assertEquals("Certificate Expiry", enTemplate.getTemplatePublic());
    assertEquals("Certificates expiring", enTemplate.getTemplateGrouped());
    assertEquals("Certificate Expiration Alert", enTemplate.getEmailSubject());

    Templates deTemplate = getTemplateForLanguage(nt, "de");
    assertEquals("Zertifikatablauf", deTemplate.getSubtitle());
    assertEquals("Zertifikatablauf", deTemplate.getTemplatePublic());
    assertEquals("Ablaufende Zertifikate", deTemplate.getTemplateGrouped());
    assertEquals("Zertifikatablauf-Warnung", deTemplate.getEmailSubject());

    Templates trTemplate = getTemplateForLanguage(nt, "tr");
    assertEquals("Sertifika Sona Ermesi", trTemplate.getSubtitle());
    assertEquals("Sertifika Sona Ermesi", trTemplate.getTemplatePublic());
    assertEquals("Süresi Dolan Sertifikalar", trTemplate.getTemplateGrouped());
    assertEquals("Sertifika Sona Erme Uyarısı", trTemplate.getEmailSubject());

    Templates esTemplate = getTemplateForLanguage(nt, "es");
    assertEquals("Expiración de Certificado", esTemplate.getSubtitle());
    assertEquals("Expiración de Certificado", esTemplate.getTemplatePublic());
    assertEquals("Certificados por expirar", esTemplate.getTemplateGrouped());
    assertEquals("Alerta de Expiración de Certificado", esTemplate.getEmailSubject());

    LOG.debug("All 4 languages verified successfully");
  }

  // ──────────────────────────────────────────────────────────────
  // Test 2: No unresolved {i18n>...} placeholders in any template
  // ──────────────────────────────────────────────────────────────

  @Test
  void testNoUnresolvedI18nPlaceholdersInTemplates() {
    LOG.debug("==========================================");
    LOG.debug("Test: No unresolved {i18n>...} placeholders in any template");
    LOG.debug("==========================================");

    List<NotificationTypes> allTypes =
        NotificationTypeProviderServiceMockHandler.getAllNotificationTypes();
    assertFalse(allTypes.isEmpty(), "At least one notification type should exist");

    int totalTemplatesChecked = 0;

    for (NotificationTypes nt : allTypes) {
      List<Templates> templates = nt.getTemplates();
      if (templates == null) continue;

      for (Templates t : templates) {
        String lang = t.getLanguage();
        String typeKey = nt.getNotificationTypeKey();
        String prefix = typeKey + "/" + lang;

        assertNoUnresolvedI18n(t.getTemplatePublic(), prefix + "/templatePublic", lang);
        assertNoUnresolvedI18n(t.getTemplateSensitive(), prefix + "/templateSensitive", lang);
        assertNoUnresolvedI18n(t.getTemplateGrouped(), prefix + "/templateGrouped", lang);
        assertNoUnresolvedI18n(t.getSubtitle(), prefix + "/subtitle", lang);
        assertNoUnresolvedI18n(t.getDescription(), prefix + "/description", lang);
        assertNoUnresolvedI18n(t.getEmailSubject(), prefix + "/emailSubject", lang);
        assertNoUnresolvedI18n(t.getEmailHtml(), prefix + "/emailHtml", lang);
        assertNoUnresolvedI18n(t.getEmailText(), prefix + "/emailText", lang);

        totalTemplatesChecked++;
      }
    }

    LOG.debug(
        "Checked {} templates across {} notification types — no unresolved i18n placeholders",
        totalTemplatesChecked,
        allTypes.size());
  }

  // ──────────────────────────────────────────────────────────────
  // Test 3: Email HTML contains Mustache variables (runtime)
  // ──────────────────────────────────────────────────────────────

  @Test
  void testEmailHtmlContainsMustacheVariables() {
    LOG.debug("==========================================");
    LOG.debug("Test: Email HTML should contain Mustache variables for ANS runtime");
    LOG.debug("==========================================");

    NotificationTypes nt = getNotificationType("CertificateExpiration");
    Templates enTemplate = getTemplateForLanguage(nt, "en");

    String emailHtml = enTemplate.getEmailHtml();
    assertNotNull(emailHtml, "Email HTML should not be null for CertificateExpiration");

    // These Mustache variables must be present — ANS resolves them at runtime
    List<String> expectedVariables =
        List.of(
            "{{certificateName}}",
            "{{expirationDate}}",
            "{{renewLink}}",
            "{{name}}",
            "{{year}}",
            "{{companyName}}");

    for (String variable : expectedVariables) {
      assertTrue(
          emailHtml.contains(variable), "Email HTML should contain Mustache variable: " + variable);
      LOG.debug("Found Mustache variable: {}", variable);
    }

    LOG.debug("All {} Mustache variables present in email HTML", expectedVariables.size());
  }

  // ──────────────────────────────────────────────────────────────
  // Test 4: Email HTML i18n resolved per language
  // ──────────────────────────────────────────────────────────────

  @Test
  void testEmailHtmlI18nResolvedPerLanguage() {
    LOG.debug("==========================================");
    LOG.debug("Test: Email HTML i18n values should differ per language");
    LOG.debug("==========================================");

    NotificationTypes nt = getNotificationType("CertificateExpiration");

    // EMAIL_BUTTON value per language
    Map<String, String> expectedButtonTexts =
        Map.of(
            "en", "Renew Now",
            "de", "Jetzt erneuern",
            "tr", "Şimdi Yenile",
            "es", "Renovar Ahora");

    // EMAIL_GREETING value per language (contains Mustache variable {{name}})
    Map<String, String> expectedGreetings =
        Map.of(
            "en", "Dear {{name}}",
            "de", "Sehr geehrte(r) {{name}}",
            "tr", "Sayın {{name}}",
            "es", "Estimado(a) {{name}}");

    for (String lang : List.of("en", "de", "tr", "es")) {
      Templates template = getTemplateForLanguage(nt, lang);
      String emailHtml = template.getEmailHtml();
      assertNotNull(emailHtml, "Email HTML should not be null for lang: " + lang);

      // Verify button text
      String expectedButton = expectedButtonTexts.get(lang);
      assertTrue(
          emailHtml.contains(expectedButton),
          "[" + lang + "] Email HTML should contain button text: '" + expectedButton + "'");

      // Verify greeting text
      String expectedGreeting = expectedGreetings.get(lang);
      assertTrue(
          emailHtml.contains(expectedGreeting),
          "[" + lang + "] Email HTML should contain greeting: '" + expectedGreeting + "'");

      LOG.debug("[{}] Button: '{}', Greeting: '{}'", lang, expectedButton, expectedGreeting);
    }

    LOG.debug("Email HTML i18n verified for all 4 languages");
  }
}
