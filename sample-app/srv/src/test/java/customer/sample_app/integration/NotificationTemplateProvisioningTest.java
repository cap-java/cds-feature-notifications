/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package customer.sample_app.integration;

import static org.junit.jupiter.api.Assertions.*;

import cds.gen.notificationtemplateproviderservice.NotificationTemplateProviderService;
import cds.gen.notificationtemplateproviderservice.NotificationTemplates;
import cds.gen.notificationtemplateproviderservice.Translations;
import com.sap.cds.notifications.handlers.NotificationTemplateAutoProvisionerHandler;
import com.sap.cds.services.runtime.CdsRuntime;
import customer.sample_app.handlers.mock.NotificationTemplateProviderServiceMockHandler;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for standalone NotificationTemplate provisioning. Verifies that
 * NotificationTemplateAutoProvisionerHandler correctly creates templates from CDS event annotations
 * at application startup.
 */
@SpringBootTest
@ActiveProfiles("test")
public class NotificationTemplateProvisioningTest {

  private static final Logger LOG =
      LoggerFactory.getLogger(NotificationTemplateProvisioningTest.class);

  /** Pattern to detect unresolved i18n placeholders like {i18n>KEY} */
  private static final Pattern UNRESOLVED_I18N = Pattern.compile("\\{i18n>[^}]+\\}");

  @Autowired private CdsRuntime cdsRuntime;

  @Autowired private NotificationTemplateProviderService notificationTemplateProviderService;

  private NotificationTemplateAutoProvisionerHandler createProvisioner() {
    return new NotificationTemplateAutoProvisionerHandler(
        cdsRuntime, notificationTemplateProviderService);
  }

  // ──────────────────────────────────────────────────────────────
  // Test 1: Templates are provisioned at startup
  // ──────────────────────────────────────────────────────────────

  @Test
  void testTemplatesProvisionedAtStartup() {
    LOG.debug("==========================================");
    LOG.debug("Test: Templates should be auto-provisioned at startup");
    LOG.debug("==========================================");

    List<NotificationTemplates> allTemplates =
        NotificationTemplateProviderServiceMockHandler.getAllTemplates();
    assertFalse(allTemplates.isEmpty(), "At least one NotificationTemplate should be provisioned");

    LOG.debug("Total templates provisioned: {}", allTemplates.size());

    // The sample-app has 6 events with @notification.template.title → 6 templates
    assertTrue(
        allTemplates.size() >= 6,
        "Expected at least 6 templates (one per annotated event), got: " + allTemplates.size());
  }

  // ──────────────────────────────────────────────────────────────
  // Test 2: CertificateExpiration template has correct structure
  // ──────────────────────────────────────────────────────────────

  @Test
  void testCertificateExpirationTemplateStructure() {
    LOG.debug("==========================================");
    LOG.debug("Test: CertificateExpiration template structure");
    LOG.debug("==========================================");

    NotificationTemplates template =
        NotificationTemplateProviderServiceMockHandler.getTemplateByKey("CertificateExpiration");
    assertNotNull(template, "CertificateExpiration template should be provisioned");

    // Key
    assertEquals("CertificateExpiration", template.getKey());

    // Visibility - @notification.customizable: true → PUBLIC
    assertEquals(
        "PUBLIC",
        template.getVisibility(),
        "Template with @notification.customizable: true should be PUBLIC");

    // PropertiesSchema - should include all event elements EXCEPT recipients
    String schema = template.getPropertiesSchema();
    assertNotNull(schema, "PropertiesSchema should be set");
    assertFalse(
        schema.contains("recipients"),
        "Schema should NOT contain 'recipients' (it's not a template variable)");
    assertTrue(
        schema.contains("certificateName"), "Schema should contain 'certificateName' property");
    assertTrue(
        schema.contains("expirationDate"), "Schema should contain 'expirationDate' property");
    assertTrue(schema.contains("name"), "Schema should contain 'name' property");

    // Translations
    List<Translations> translations = template.getTranslations();
    assertNotNull(translations, "Translations should not be null");
    assertFalse(translations.isEmpty(), "Translations should not be empty");

    LOG.debug(
        "CertificateExpiration template: Key={}, Visibility={}, Translations={}",
        template.getKey(),
        template.getVisibility(),
        translations.size());
  }

  // ──────────────────────────────────────────────────────────────
  // Test 3: Visibility defaults to PRIVATE (not set) when no annotation
  // ──────────────────────────────────────────────────────────────

  @Test
  void testVisibilityDefaultsToPrivate() {
    LOG.debug("==========================================");
    LOG.debug(
        "Test: Templates without @notification.customizable should have null visibility (PRIVATE)");
    LOG.debug("==========================================");

    // SystemMaintenance has no @notification.customizable → visibility should be null (ANS defaults
    // PRIVATE)
    NotificationTemplates template =
        NotificationTemplateProviderServiceMockHandler.getTemplateByKey("SystemMaintenance");
    assertNotNull(template, "SystemMaintenance template should be provisioned");
    assertNull(
        template.getVisibility(),
        "Template without @notification.customizable should have null visibility (ANS defaults to PRIVATE)");

    LOG.debug(
        "SystemMaintenance visibility: {} (null = ANS default PRIVATE)", template.getVisibility());
  }

  // ──────────────────────────────────────────────────────────────
  // Test 4: i18n resolved in translations
  // ──────────────────────────────────────────────────────────────

  @Test
  void testI18nResolvedInTranslations() {
    LOG.debug("==========================================");
    LOG.debug("Test: i18n placeholders should be resolved in template translations");
    LOG.debug("==========================================");

    NotificationTemplates template =
        NotificationTemplateProviderServiceMockHandler.getTemplateByKey("CertificateExpiration");
    assertNotNull(template, "CertificateExpiration template should be provisioned");

    List<Translations> translations = template.getTranslations();
    assertFalse(translations.isEmpty(), "Should have at least one translation");

    for (Translations t : translations) {
      String lang = t.getLanguage();

      // Title must be set (required field)
      assertNotNull(t.getTitle(), "Title should not be null for lang: " + lang);
      assertFalse(t.getTitle().isEmpty(), "Title should not be empty for lang: " + lang);

      // No unresolved i18n placeholders
      assertNoUnresolvedI18n(t.getTitle(), "Title", lang);
      assertNoUnresolvedI18n(t.getBody(), "Body", lang);
      assertNoUnresolvedI18n(t.getPreview(), "Preview", lang);
      assertNoUnresolvedI18n(t.getDescription(), "Description", lang);

      if (t.getEmail() != null) {
        assertNoUnresolvedI18n(t.getEmail().getSubject(), "Email.Subject", lang);
        assertNoUnresolvedI18n(t.getEmail().getBodyHtml(), "Email.BodyHtml", lang);
      }

      LOG.debug(
          "[{}] Title={}, Body={}, Preview={}", lang, t.getTitle(), t.getBody(), t.getPreview());
    }
  }

  // ──────────────────────────────────────────────────────────────
  // Test 5: Multi-language translations for i18n-based template
  // ──────────────────────────────────────────────────────────────

  @Test
  void testMultiLanguageTranslations() {
    LOG.debug("==========================================");
    LOG.debug("Test: CertificateExpiration should have translations for all i18n languages");
    LOG.debug("==========================================");

    NotificationTemplates template =
        NotificationTemplateProviderServiceMockHandler.getTemplateByKey("CertificateExpiration");
    assertNotNull(template);

    List<Translations> translations = template.getTranslations();

    // The sample-app has i18n files for: en, de, tr, es
    assertTrue(
        translations.size() >= 4,
        "Expected translations for at least 4 languages, got: " + translations.size());

    // Verify English
    Translations en = findTranslation(translations, "en");
    assertNotNull(en, "English translation should exist");
    assertTrue(
        en.getTitle().contains("{{certificateName}}"),
        "English title should contain Mustache variable {{certificateName}}");

    // Verify German
    Translations de = findTranslation(translations, "de");
    assertNotNull(de, "German translation should exist");

    // Verify Turkish
    Translations tr = findTranslation(translations, "tr");
    assertNotNull(tr, "Turkish translation should exist");

    // Verify Spanish
    Translations es = findTranslation(translations, "es");
    assertNotNull(es, "Spanish translation should exist");

    // Titles should differ per language (i18n resolved)
    assertNotEquals(en.getTitle(), de.getTitle(), "EN and DE titles should differ");

    LOG.debug("Translations verified for {} languages", translations.size());
  }

  // ──────────────────────────────────────────────────────────────
  // Test 6: Static template (no i18n) has identical translations for all locales
  // ──────────────────────────────────────────────────────────────

  @Test
  void testStaticTemplateTranslation() {
    LOG.debug("==========================================");
    LOG.debug(
        "Test: Templates with static strings should have same value across all locale translations");
    LOG.debug("==========================================");

    // SystemMaintenance uses static strings (no {i18n>...} placeholders).
    // Since i18n files exist in the project for other events, translations are created
    // for all discovered locales — but all with the same static value.
    NotificationTemplates template =
        NotificationTemplateProviderServiceMockHandler.getTemplateByKey("SystemMaintenance");
    assertNotNull(template, "SystemMaintenance template should be provisioned");

    List<Translations> translations = template.getTranslations();
    assertNotNull(translations, "Translations should not be null");
    assertFalse(translations.isEmpty(), "Should have at least one translation");

    // Verify title contains Mustache variable
    Translations first = translations.get(0);
    assertNotNull(first.getTitle());
    assertTrue(
        first.getTitle().contains("{{systemName}}"),
        "Title should contain Mustache variable: " + first.getTitle());

    // All translations should have the same title (static string, no i18n differentiation)
    String expectedTitle = first.getTitle();
    for (Translations t : translations) {
      assertEquals(
          expectedTitle,
          t.getTitle(),
          "Static template should have identical title across all locales, but lang '"
              + t.getLanguage()
              + "' differs");
    }

    LOG.debug(
        "Static template: {} translations, all with title: {}", translations.size(), expectedTitle);
  }

  // ──────────────────────────────────────────────────────────────
  // Test 7: Email HTML loaded from file
  // ──────────────────────────────────────────────────────────────

  @Test
  void testEmailHtmlLoadedFromFile() {
    LOG.debug("==========================================");
    LOG.debug("Test: CertificateExpiration email HTML should be loaded from file");
    LOG.debug("==========================================");

    NotificationTemplates template =
        NotificationTemplateProviderServiceMockHandler.getTemplateByKey("CertificateExpiration");
    assertNotNull(template);

    List<Translations> translations = template.getTranslations();
    Translations en = findTranslation(translations, "en");
    assertNotNull(en, "English translation should exist");
    assertNotNull(en.getEmail(), "Email should be set for CertificateExpiration");
    assertNotNull(en.getEmail().getBodyHtml(), "Email HTML body should be loaded");

    // Verify it's actual HTML content, not a file path
    String html = en.getEmail().getBodyHtml();
    assertFalse(html.endsWith(".html"), "Email body should be HTML content, not a file path");
    assertTrue(html.contains("<"), "Email body should contain HTML tags");

    LOG.debug("Email HTML loaded successfully ({} chars)", html.length());
  }

  // ──────────────────────────────────────────────────────────────
  // Test 8: Tags contain source and event
  // ──────────────────────────────────────────────────────────────

  @Test
  void testTagsContainSourceAndEvent() {
    LOG.debug("==========================================");
    LOG.debug("Test: Template tags should contain source and event information");
    LOG.debug("==========================================");

    NotificationTemplates template =
        NotificationTemplateProviderServiceMockHandler.getTemplateByKey("CertificateExpiration");
    assertNotNull(template);

    var tags = template.getTags();
    assertNotNull(tags, "Tags should not be null");
    assertFalse(tags.isEmpty(), "Tags should not be empty");

    LOG.debug("Tags: {}", tags);
  }

  // ──────────────────────────────────────────────────────────────
  // Test 9: Translation metadata (source, event, displayName)
  // ──────────────────────────────────────────────────────────────

  @Test
  void testTranslationMetadata() {
    LOG.debug("==========================================");
    LOG.debug("Test: Translations should have source, event, and displayName set");
    LOG.debug("==========================================");

    NotificationTemplates template =
        NotificationTemplateProviderServiceMockHandler.getTemplateByKey("CertificateExpiration");
    assertNotNull(template);

    Translations en = findTranslation(template.getTranslations(), "en");
    assertNotNull(en);

    // Source = service name (e.g. "NotificationService")
    assertNotNull(en.getSource(), "Source should be set");
    assertFalse(en.getSource().isEmpty(), "Source should not be empty");

    // Event = event name
    assertEquals("CertificateExpiration", en.getEvent(), "Event should be the event name");

    // DisplayName = event name
    assertEquals(
        "CertificateExpiration", en.getDisplayName(), "DisplayName should be the event name");

    LOG.debug(
        "Source={}, Event={}, DisplayName={}", en.getSource(), en.getEvent(), en.getDisplayName());
  }

  // ──────────────────────────────────────────────────────────────
  // Test 10: PropertiesSchema reflects event elements
  // ──────────────────────────────────────────────────────────────

  @Test
  void testPropertiesSchemaReflectsEventElements() {
    LOG.debug("==========================================");
    LOG.debug("Test: PropertiesSchema should be a JSON schema of event elements");
    LOG.debug("==========================================");

    NotificationTemplates template =
        NotificationTemplateProviderServiceMockHandler.getTemplateByKey("SystemMaintenance");
    assertNotNull(template);

    String schema = template.getPropertiesSchema();
    assertNotNull(schema, "PropertiesSchema should be set");

    // Should be valid JSON-like structure
    assertTrue(
        schema.contains("properties") || schema.contains("type"),
        "Schema should contain JSON Schema keywords: " + schema);

    LOG.debug("PropertiesSchema: {}", schema);
  }

  // ──────────────────────────────────────────────────────────────
  // Test 11: i18n placeholders resolved for all languages (exact values)
  // ──────────────────────────────────────────────────────────────

  @Test
  void testI18nPlaceholdersResolvedForAllLanguages() {
    LOG.debug("==========================================");
    LOG.debug("Test: i18n placeholders should be resolved for EN, DE, TR, ES");
    LOG.debug("==========================================");

    NotificationTemplates template =
        NotificationTemplateProviderServiceMockHandler.getTemplateByKey("CertificateExpiration");
    assertNotNull(template, "CertificateExpiration template should be provisioned");

    List<Translations> translations = template.getTranslations();

    // Verify Title (maps to @notification.template.title)
    assertEquals(
        "Certificate: {{certificateName}}", findTranslation(translations, "en").getTitle());
    assertEquals("Zertifikat: {{certificateName}}", findTranslation(translations, "de").getTitle());
    assertEquals("Sertifika: {{certificateName}}", findTranslation(translations, "tr").getTitle());
    assertEquals(
        "Certificado: {{certificateName}}", findTranslation(translations, "es").getTitle());

    // Verify Body (maps to @notification.template.subtitle)
    assertEquals("Certificate Expiration", findTranslation(translations, "en").getBody());
    assertEquals("Zertifikatablauf", findTranslation(translations, "de").getBody());
    assertEquals("Sertifika Sona Ermesi", findTranslation(translations, "tr").getBody());
    assertEquals("Expiración de Certificado", findTranslation(translations, "es").getBody());

    // Verify Preview (maps to @notification.template.publicTitle)
    assertEquals("Certificate Expiry", findTranslation(translations, "en").getPreview());
    assertEquals("Zertifikatablauf", findTranslation(translations, "de").getPreview());
    assertEquals("Sertifika Sona Ermesi", findTranslation(translations, "tr").getPreview());
    assertEquals("Expiración de Certificado", findTranslation(translations, "es").getPreview());

    // Verify Email Subject
    assertEquals(
        "Certificate Expiration Alert",
        findTranslation(translations, "en").getEmail().getSubject());
    assertEquals(
        "Zertifikatablauf-Warnung", findTranslation(translations, "de").getEmail().getSubject());
    assertEquals(
        "Sertifika Sona Erme Uyarısı", findTranslation(translations, "tr").getEmail().getSubject());
    assertEquals(
        "Alerta de Expiración de Certificado",
        findTranslation(translations, "es").getEmail().getSubject());

    // Verify Description
    assertEquals(
        "Certificate Expiration Alert", findTranslation(translations, "en").getDescription());
    assertEquals("Zertifikatablauf-Warnung", findTranslation(translations, "de").getDescription());
    assertEquals(
        "Sertifika Sona Erme Uyarısı", findTranslation(translations, "tr").getDescription());
    assertEquals(
        "Alerta de Expiración de Certificado",
        findTranslation(translations, "es").getDescription());

    LOG.debug("All 4 languages verified with exact i18n values");
  }

  // ──────────────────────────────────────────────────────────────
  // Test 12: Email HTML contains Mustache variables (runtime)
  // ──────────────────────────────────────────────────────────────

  @Test
  void testEmailHtmlContainsMustacheVariables() {
    LOG.debug("==========================================");
    LOG.debug("Test: Email HTML should contain Mustache variables for ANS runtime");
    LOG.debug("==========================================");

    NotificationTemplates template =
        NotificationTemplateProviderServiceMockHandler.getTemplateByKey("CertificateExpiration");
    assertNotNull(template);

    Translations en = findTranslation(template.getTranslations(), "en");
    assertNotNull(en, "English translation should exist");
    assertNotNull(en.getEmail(), "Email should be set");

    String emailHtml = en.getEmail().getBodyHtml();
    assertNotNull(emailHtml, "Email HTML should not be null");

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
    }

    LOG.debug("All {} Mustache variables present in email HTML", expectedVariables.size());
  }

  // ──────────────────────────────────────────────────────────────
  // Test 13: Email HTML i18n resolved per language
  // ──────────────────────────────────────────────────────────────

  @Test
  void testEmailHtmlI18nResolvedPerLanguage() {
    LOG.debug("==========================================");
    LOG.debug("Test: Email HTML i18n values should differ per language");
    LOG.debug("==========================================");

    NotificationTemplates template =
        NotificationTemplateProviderServiceMockHandler.getTemplateByKey("CertificateExpiration");
    assertNotNull(template);

    List<Translations> translations = template.getTranslations();

    // Button text per language
    Map<String, String> expectedButtonTexts =
        Map.of(
            "en", "Renew Now",
            "de", "Jetzt erneuern",
            "tr", "Şimdi Yenile",
            "es", "Renovar Ahora");

    // Greeting per language (contains Mustache variable {{name}})
    Map<String, String> expectedGreetings =
        Map.of(
            "en", "Dear {{name}}",
            "de", "Sehr geehrte(r) {{name}}",
            "tr", "Sayın {{name}}",
            "es", "Estimado(a) {{name}}");

    for (String lang : List.of("en", "de", "tr", "es")) {
      Translations t = findTranslation(translations, lang);
      assertNotNull(t, "Translation should exist for lang: " + lang);
      assertNotNull(t.getEmail(), "Email should exist for lang: " + lang);

      String emailHtml = t.getEmail().getBodyHtml();
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

  // ──────────────────────────────────────────────────────────────
  // Helpers
  // ──────────────────────────────────────────────────────────────

  private Translations findTranslation(List<Translations> translations, String lang) {
    return translations.stream().filter(t -> lang.equals(t.getLanguage())).findFirst().orElse(null);
  }

  private void assertNoUnresolvedI18n(String value, String fieldName, String lang) {
    if (value != null) {
      assertFalse(
          UNRESOLVED_I18N.matcher(value).find(),
          "Unresolved {i18n>...} placeholder in " + fieldName + " [" + lang + "]: " + value);
    }
  }

  // ──────────────────────────────────────────────────────────────
  // Test: Re-provisioning updates existing templates
  // ──────────────────────────────────────────────────────────────

  @Test
  void testReProvisioningUpdatesExistingTemplates() {
    LOG.debug("==========================================");
    LOG.debug("Test: Re-provisioning should UPDATE existing templates");
    LOG.debug("==========================================");

    int countBefore = NotificationTemplateProviderServiceMockHandler.getTemplateCount();
    assertTrue(countBefore > 0, "Templates should already be provisioned at startup");

    int updatesBefore =
        NotificationTemplateProviderServiceMockHandler.getUpdateCount("CertificateExpiration");

    createProvisioner().onApplicationPrepared();

    int updatesAfter =
        NotificationTemplateProviderServiceMockHandler.getUpdateCount("CertificateExpiration");
    assertEquals(
        updatesBefore + 1,
        updatesAfter,
        "CertificateExpiration template should have been updated once during re-provisioning");

    assertEquals(
        countBefore,
        NotificationTemplateProviderServiceMockHandler.getTemplateCount(),
        "Template count should remain the same after re-provisioning");

    LOG.debug("Re-provisioning triggered UPDATE for existing templates");
  }
}
