/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package com.sap.cds.notifications.handlers;

import cds.gen.notificationtemplateproviderservice.Email;
import cds.gen.notificationtemplateproviderservice.NotificationTemplates;
import cds.gen.notificationtemplateproviderservice.Tags;
import cds.gen.notificationtemplateproviderservice.Translations;
import com.sap.cds.Struct;
import com.sap.cds.notifications.helpers.I18nHelper;
import com.sap.cds.reflect.CdsBaseType;
import com.sap.cds.reflect.CdsEvent;
import com.sap.cds.reflect.CdsModel;
import com.sap.cds.reflect.CdsSimpleType;
import com.sap.cds.services.runtime.CdsRuntime;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class to build standalone NotificationTemplate objects from CDS event annotations. 
 *
 * <p>Annotation mapping to standalone template fields:
 *
 * <ul>
 *   <li>{@code @notification.template.title} → Translation.Title
 *   <li>{@code @notification.template.subtitle} → Translation.Body
 *   <li>{@code @notification.template.publicTitle} → Translation.Preview
 *   <li>{@code @notification.template.email.subject} → Translation.Email.Subject
 *   <li>{@code @notification.template.email.html} → Translation.Email.BodyHtml
 *   <li>{@code @notification.template.email.text} → Translation.Email.BodyText
 *   <li>{@code @notification.template.description} → Translation.Description
 * </ul>
 */
public class NotificationTemplateBuilder {

  private static final Logger logger = LoggerFactory.getLogger(NotificationTemplateBuilder.class);

  private static final String DEFAULT_SYNTAX = "MUSTACHE";

  private final CdsRuntime runtime;
  private final I18nHelper i18nHelper;

  public NotificationTemplateBuilder(CdsRuntime runtime) {
    this.runtime = runtime;
    this.i18nHelper = new I18nHelper(runtime);
  }

  /** Build all standalone notification templates from CDS model event annotations. */
  public List<NotificationTemplates> buildAllNotificationTemplates() {
    List<NotificationTemplates> templates = new ArrayList<>();
    CdsModel model = runtime.getCdsModel();

    model.events()
        .filter(event -> event.findAnnotation("notification.template.title").isPresent())
        .forEach(event -> extractTemplateFromEvent(event).ifPresent(templates::add));

    return templates;
  }

  /** Extract a standalone NotificationTemplate from a CDS event with annotations. */
  private Optional<NotificationTemplates> extractTemplateFromEvent(CdsEvent event) {
    String key = event.getName();
    String qualifiedName = event.getQualifiedName();
    String source = extractSource(qualifiedName);
    String eventName = key;

    NotificationTemplates template = Struct.create(NotificationTemplates.class);
    template.setKey(key);

    // Visibility - from @notification.customizable annotation (ANS defaults to PRIVATE)
    // @notification.customizable: true → PUBLIC, absent or false → PRIVATE (default)
    String visibility = extractVisibility(event);
    if (visibility != null) {
      template.setVisibility(visibility);
    }

    // PropertiesSchema - auto-generated from event elements
    String propertiesSchema = buildPropertiesSchema(event);
    if (propertiesSchema != null) {
      template.setPropertiesSchema(propertiesSchema);
    }

    // Tags - source and event for filtering in admin UI
    List<Tags> tags = buildTags(source, eventName);
    template.setTags(tags);

    Set<Locale> locales = i18nHelper.getAvailableLocales();
    logger.debug("Creating translations for {} discovered i18n locales", locales.size());

    List<Translations> translations = new ArrayList<>();

    for (Locale locale : locales) {
      String lang = locale.toLanguageTag();
      Map<String, String> i18nTexts = i18nHelper.getI18nTexts(locale);
      Translations translation = createTranslation(event, lang, i18nTexts, source, eventName);
      translations.add(translation);
      logger.debug(
          "Created translation for language: {} with {} i18n texts", lang, i18nTexts.size());
    }

    template.setTranslations(translations);

    logger.info("Extracted standalone NotificationTemplate: Key={}, Source={}", key, source);
    return Optional.of(template);
  }

  private Translations createTranslation(
      CdsEvent event, String lang, Map<String, String> i18nTexts, String source, String eventName) {
    Translations translation = Struct.create(Translations.class);
    translation.setLanguage(lang);
    translation.setSyntax(DEFAULT_SYNTAX);

    // Source, Event, DisplayName - for admin UI filtering and display
    translation.setSource(source);
    translation.setEvent(eventName);
    translation.setDisplayName(eventName);

    // Title (required) - from @notification.template.title
    String title = i18nHelper.resolveAnnotationValue(event, "notification.template.title", i18nTexts);
    if (title == null || title.trim().isEmpty()) {
      throw new IllegalStateException(
          String.format(
              "Missing required annotation: @notification.template.title for event '%s'. "
                  + "The standalone template API requires a Title to be set.",
              event.getName()));
    }
    translation.setTitle(title);

    // Preview - from @notification.template.publicTitle
    String preview = i18nHelper.resolveAnnotationValue(event, "notification.template.publicTitle", i18nTexts);
    if (preview != null && !preview.trim().isEmpty()) {
      translation.setPreview(preview);
    }

    // Body - from @notification.template.subtitle
    String body = i18nHelper.resolveAnnotationValue(event, "notification.template.subtitle", i18nTexts);
    if (body != null && !body.trim().isEmpty()) {
      translation.setBody(body);
    }

    // Description - from @notification.template.description or @description
    String description = i18nHelper.resolveAnnotationValue(event, "notification.template.description", i18nTexts);
    if (description == null) {
      description = i18nHelper.resolveAnnotationValue(event, "description", i18nTexts);
    }
    if (description != null && !description.trim().isEmpty()) {
      translation.setDescription(description);
    }

    // Email
    Email email = buildEmail(event, i18nTexts);
    if (email != null) {
      translation.setEmail(email);
    }

    return translation;
  }

  private Email buildEmail(CdsEvent event, Map<String, String> i18nTexts) {
    String subject =
        i18nHelper.resolveAnnotationValue(event, "notification.template.email.subject", i18nTexts);
    String htmlValue =
        i18nHelper.resolveAnnotationValue(event, "notification.template.email.html", i18nTexts);
    String textValue =
        i18nHelper.resolveAnnotationValue(event, "notification.template.email.text", i18nTexts);

    if (subject == null && htmlValue == null && textValue == null) {
      return null;
    }

    Email email = Struct.create(Email.class);

    if (subject != null) {
      email.setSubject(subject);
    }

    // Resolve email HTML - if it's a file path, load the file content
    if (htmlValue != null) {
      if (htmlValue.endsWith(".html")) {
        String htmlContent = i18nHelper.loadHtmlFromClasspath(htmlValue, i18nTexts);
        email.setBodyHtml(htmlContent);
        logger.info("Loaded HTML template from file: {}", htmlValue);
      } else {
        email.setBodyHtml(htmlValue);
      }
    }

    if (textValue != null) {
      email.setBodyText(textValue);
    }

    return email;
  }

  /**
   * Extract visibility from @notification.customizable annotation.
   * Returns "PUBLIC" if @notification.customizable: true, null otherwise (ANS defaults to PRIVATE).
   */
  private String extractVisibility(CdsEvent event) {
    return event
        .findAnnotation("notification.customizable")
        .map(a -> {
          Object val = a.getValue();
          // @notification.customizable: true → template is PUBLIC (visible for customization)
          if (Boolean.TRUE.equals(val) || "true".equalsIgnoreCase(String.valueOf(val))) {
            return "PUBLIC";
          }
          // @notification.customizable: false or any other value → PRIVATE (default)
          return null;
        })
        .orElse(null);
  }

  /**
   * Extract source (service name) from the event's qualified name. E.g. "CatalogService.BookOrdered"
   * → "CatalogService".
   */
  private String extractSource(String qualifiedName) {
    int lastDot = qualifiedName.lastIndexOf('.');
    if (lastDot > 0) {
      return qualifiedName.substring(0, lastDot);
    }
    return qualifiedName;
  }

  /** Build Tags list for source and event filtering in admin UI. */
  private List<Tags> buildTags(String source, String eventName) {
    List<Tags> tags = new ArrayList<>();

    Tags sourceTag = Struct.create(Tags.class);
    sourceTag.setKey("source");
    sourceTag.setValue(source);
    tags.add(sourceTag);

    Tags eventTag = Struct.create(Tags.class);
    eventTag.setKey("event");
    eventTag.setValue(eventName);
    tags.add(eventTag);

    return tags;
  }

  /**
   * Build JSON Schema from event elements for the PropertiesSchema field. Maps CDS types to JSON
   * Schema types. Excludes the 'recipients' field as it's not a template variable.
   */
  private String buildPropertiesSchema(CdsEvent event) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\"type\":\"object\",\"properties\":{");

    List<String> propertyEntries = new ArrayList<>();
    List<String> requiredFields = new ArrayList<>();

    event
        .elements()
        .forEach(
            element -> {
              String fieldName = element.getName();
              // Skip recipients - it's not a template variable
              if (!"recipients".equals(fieldName)) {
                String jsonType = cdsTypeToJsonSchemaType(element);
                propertyEntries.add(
                    String.format(
                        "\"%s\":{\"type\":\"%s\",\"title\":\"%s\"}",
                        fieldName, jsonType, fieldName));
                requiredFields.add("\"" + fieldName + "\"");
              }
            });

    sb.append(String.join(",", propertyEntries));
    sb.append("},\"required\":[");
    sb.append(String.join(",", requiredFields));
    sb.append("]}");

    String schema = sb.toString();
    logger.debug("Generated PropertiesSchema: {}", schema);
    return schema;
  }

  /** Map CDS element type to JSON Schema type string. */
  private String cdsTypeToJsonSchemaType(com.sap.cds.reflect.CdsElementDefinition element) {
    try {
      com.sap.cds.reflect.CdsType cdsType = element.getType();
      if (!cdsType.isSimple()) {
        return "string";
      }
      CdsSimpleType simpleType = (CdsSimpleType) cdsType;
      CdsBaseType baseType = simpleType.getType();
      return switch (baseType) {
        case INT16, INT32, INT64, INTEGER, INTEGER64, UINT8 -> "integer";
        case DECIMAL, DOUBLE -> "number";
        case BOOLEAN -> "boolean";
        default -> "string";
      };
    } catch (Exception e) {
      // If type cannot be resolved, default to string
      return "string";
    }
  }

}
