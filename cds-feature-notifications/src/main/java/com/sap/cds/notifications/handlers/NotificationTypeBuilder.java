/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package com.sap.cds.notifications.handlers;

import cds.gen.notificationtypeproviderservice.DeliveryChannels;
import cds.gen.notificationtypeproviderservice.NotificationTypes;
import cds.gen.notificationtypeproviderservice.Templates;
import com.sap.cds.Struct;
import com.sap.cds.adapter.edmx.EdmxI18nProvider;
import com.sap.cds.reflect.CdsEvent;
import com.sap.cds.reflect.CdsModel;
import com.sap.cds.services.runtime.CdsRuntime;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Helper class to build NotificationType objects from CDS event annotations. */
public class NotificationTypeBuilder {

  private static final Logger logger = LoggerFactory.getLogger(NotificationTypeBuilder.class);

  private final CdsRuntime runtime;
  private EdmxI18nProvider i18nProvider;

  public NotificationTypeBuilder(CdsRuntime runtime) {
    this.runtime = runtime;
  }

  /** Build all notification types from CDS model event annotations. */
  public List<NotificationTypes> buildAllNotificationTypes() {
    List<NotificationTypes> notificationTypes = new ArrayList<>();
    CdsModel model = runtime.getCdsModel();

    model
        .events()
        .forEach(
            event -> {
              logger.info("=== Checking event: {} ===", event.getQualifiedName());

              // Check for notification annotation
              boolean hasNotificationAnnotation =
                  event.findAnnotation("notification.template.title").isPresent();

              if (hasNotificationAnnotation) {
                logger.info(
                    "Found @notification annotation on event: {}", event.getQualifiedName());
                extractNotificationTypeFromEvent(event).ifPresent(notificationTypes::add);
              } else {
                logger.debug(
                    "Event {} has no @notification annotation, skipping", event.getQualifiedName());
              }
            });

    return notificationTypes;
  }

  /** Extract NotificationType from a CDS event with annotations. */
  private Optional<NotificationTypes> extractNotificationTypeFromEvent(CdsEvent event) {
    String key = event.getName();

    NotificationTypes nt = Struct.create(NotificationTypes.class);
    nt.setNotificationTypeKey(key);
    nt.setNotificationTypeVersion("1");

    Set<Locale> locales = getAvailableLocales();
    logger.debug("Creating templates for {} discovered i18n locales", locales.size());

    List<Templates> templates = new ArrayList<>();

    // Create template for each discovered locale using EdmxI18nProvider
    for (Locale locale : locales) {
      String lang = locale.toLanguageTag();
      Map<String, String> i18nTexts = getI18nTexts(locale);
      Templates template = createTemplate(event, lang, i18nTexts);
      templates.add(template);
      logger.debug("Created template for language: {} with {} i18n texts", lang, i18nTexts.size());
    }

    nt.setTemplates(templates);

    // Extract delivery channels
    List<DeliveryChannels> deliveryChannels = extractDeliveryChannels(event);
    if (!deliveryChannels.isEmpty()) {
      nt.setDeliveryChannels(deliveryChannels);
    }

    logger.info("Extracted NotificationType: {}", key);
    return Optional.of(nt);
  }

  private Templates createTemplate(CdsEvent event, String lang, Map<String, String> i18nTexts) {
    Templates template = Struct.create(Templates.class);
    template.setLanguage(lang);

    // Validate and set required fields
    String publicTitle =
        resolveAnnotationValue(event, "notification.template.publicTitle", i18nTexts);
    if (publicTitle == null || publicTitle.trim().isEmpty()) {
      throw new IllegalStateException(
          String.format(
              "Missing required annotation: @notification.template.publicTitle for event '%s'. ANS"
                  + " requires publicTitle to be set. Please add 'publicTitle:"
                  + " '{i18n>TEMPLATE_PUBLIC}'' to your @notification.template annotation and"
                  + " define 'TEMPLATE_PUBLIC' in your i18n properties files.",
              event.getName()));
    }
    template.setTemplatePublic(publicTitle);

    String sensitiveTitle = resolveAnnotationValue(event, "notification.template.title", i18nTexts);
    if (sensitiveTitle == null || sensitiveTitle.trim().isEmpty()) {
      throw new IllegalStateException(
          String.format(
              "Missing required annotation: @notification.template.title for event '%s'. ANS"
                  + " requires title (sensitive template) to be set. Please add 'title:"
                  + " '{i18n>TEMPLATE_SENSITIVE}'' to your @notification.template annotation and"
                  + " define 'TEMPLATE_SENSITIVE' in your i18n properties files.",
              event.getName()));
    }
    template.setTemplateSensitive(sensitiveTitle);

    String groupedTitle =
        resolveAnnotationValue(event, "notification.template.groupedTitle", i18nTexts);
    if (groupedTitle == null || groupedTitle.trim().isEmpty()) {
      throw new IllegalStateException(
          String.format(
              "Missing required annotation: @notification.template.groupedTitle for event '%s'. ANS"
                  + " requires groupedTitle to be set. Please add 'groupedTitle:"
                  + " '{i18n>TEMPLATE_GROUPED}'' to your @notification annotation and define"
                  + " 'TEMPLATE_GROUPED' in your i18n properties files.",
              event.getName()));
    }
    template.setTemplateGrouped(groupedTitle);

    template.setDescription(resolveAnnotationValue(event, "description", i18nTexts));

    String subtitle = resolveAnnotationValue(event, "notification.template.subtitle", i18nTexts);
    if (subtitle == null || subtitle.trim().isEmpty()) {
      throw new IllegalStateException(
          String.format(
              "Missing required annotation: @notification.template.subtitle for event '%s'. ANS"
                  + " requires subtitle to be set. Please add 'subtitle: '{i18n>SUBTITLE}'' to your"
                  + " @notification.template annotation and define 'SUBTITLE' in your i18n"
                  + " properties files.",
              event.getName()));
    }
    template.setSubtitle(subtitle);

    template.setEmailSubject(
        resolveAnnotationValue(event, "notification.template.email.subject", i18nTexts));

    // Resolve email HTML - if it's a file path, load the file content
    String emailHtmlValue =
        resolveAnnotationValue(event, "notification.template.email.html", i18nTexts);
    if (emailHtmlValue != null && emailHtmlValue.endsWith(".html")) {
      String htmlContent = loadHtmlFromClasspath(emailHtmlValue, i18nTexts);
      template.setEmailHtml(htmlContent);
      logger.info("Loaded HTML template from file: {}", emailHtmlValue);
    } else {
      template.setEmailHtml(emailHtmlValue);
    }

    template.setEmailText(resolveAnnotationValue(event, "notificationType.emailText", i18nTexts));
    template.setTemplateLanguage("MUSTACHE");

    return template;
  }

  @SuppressWarnings("unchecked")
  private List<DeliveryChannels> extractDeliveryChannels(CdsEvent event) {
    List<DeliveryChannels> deliveryChannels = new ArrayList<>();

    var channelsAnno = event.findAnnotation("notification.deliveryChannels");
    if (channelsAnno.isEmpty()) {
      return deliveryChannels;
    }

    Object channelsValue = channelsAnno.get().getValue();
    if (!(channelsValue instanceof List)) {
      return deliveryChannels;
    }

    List<Map<String, Object>> channelsList = (List<Map<String, Object>>) channelsValue;

    for (Map<String, Object> channel : channelsList) {
      DeliveryChannels deliveryChannel = Struct.create(DeliveryChannels.class);

      // Parse CDS enum value: {#=MAIL} or "MAIL" → "MAIL"
      Object typeObj = channel.get("channel");
      String channelType;
      if (typeObj instanceof Map) {
        Map<String, Object> enumMap = (Map<String, Object>) typeObj;
        channelType =
            enumMap.values().stream()
                .findFirst()
                .map(Object::toString)
                .orElse("MAIL")
                .toUpperCase();
      } else {
        channelType = typeObj.toString().toUpperCase();
      }

      deliveryChannel.setType(channelType);
      deliveryChannel.setEnabled((Boolean) channel.getOrDefault("enabled", true));
      deliveryChannel.setDefaultPreference(
          (Boolean) channel.getOrDefault("defaultPreference", true));

      deliveryChannels.add(deliveryChannel);

      logger.info(
          "Parsed delivery channel: Type={}, Enabled={}, DefaultPreference={}",
          deliveryChannel.getType(),
          deliveryChannel.getEnabled(),
          deliveryChannel.getDefaultPreference());
    }

    return deliveryChannels;
  }

  /**
   * Discover available locales from the EdmxI18nProvider. This dynamically detects which languages
   * have i18n translations defined in the application's _i18n/ properties files, rather than
   * relying on a hardcoded list. Always includes English as a fallback.
   *
   * @since cds-services 4.9.0
   */
  private Set<Locale> getAvailableLocales() {
    if (i18nProvider == null) {
      i18nProvider = runtime.getProvider(EdmxI18nProvider.class);
    }
    if (i18nProvider != null) {
      Set<Locale> locales = i18nProvider.getLocales();
      if (!locales.isEmpty()) {
        return locales;
      }
    }
    logger.warn("EdmxI18nProvider.getLocales() returned no locales, falling back to English only");
    return Set.of(Locale.ENGLISH);
  }

  /**
   * Get i18n texts for a given locale from the EdmxI18nProvider. The provider reads from
   * edmx/_i18n/i18n.json which is generated by the CDS compiler from _i18n/i18n*.properties files
   * located next to the .cds source files.
   */
  private Map<String, String> getI18nTexts(Locale locale) {
    if (i18nProvider != null) {
      return i18nProvider.getTexts(locale);
    }
    logger.warn("EdmxI18nProvider not available, i18n resolution will not work");
    return Collections.emptyMap();
  }

  /** Extract i18n key from annotation value like {i18n>KEY} and resolve using EdmxI18nProvider. */
  private String resolveAnnotationValue(
      CdsEvent event, String annotationPath, Map<String, String> i18nTexts) {
    String annotationValue =
        event.findAnnotation(annotationPath).map(a -> (String) a.getValue()).orElse(null);

    if (annotationValue == null) {
      return null;
    }

    return resolveI18n(annotationValue, i18nTexts);
  }

  /**
   * Resolve all {i18n>KEY} patterns in a string using the i18n texts map. Supports both
   * single-placeholder values (e.g. "{i18n>TITLE}") and multi-placeholder values (e.g.
   * "{i18n>GREETING}, {i18n>BODY}").
   */
  private String resolveI18n(String value, Map<String, String> i18nTexts) {
    if (value == null || !value.contains("{i18n>")) {
      return value;
    }

    for (Map.Entry<String, String> entry : i18nTexts.entrySet()) {
      value = value.replace("{i18n>" + entry.getKey() + "}", entry.getValue());
    }
    return value;
  }

  /** Cache for raw HTML content loaded from classpath to avoid repeated disk reads */
  private final Map<String, String> htmlCache = new HashMap<>();

  /**
   * Load HTML content from classpath resource and resolve {i18n>KEY} placeholders. HTML content is
   * cached after first load since only i18n values change per language.
   */
  private String loadHtmlFromClasspath(String filePath, Map<String, String> i18nTexts) {
    String content =
        htmlCache.computeIfAbsent(
            filePath,
            path -> {
              try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
                if (is != null) {
                  return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                } else {
                  logger.error("HTML file not found: {}", path);
                  return null;
                }
              } catch (Exception e) {
                logger.error("Failed to load HTML file: {}", path, e);
                return null;
              }
            });

    if (content == null) {
      return null;
    }

    // Resolve {i18n>KEY} placeholders in the HTML content
    String resolvedHtml = resolveI18n(content, i18nTexts);

    // Minify HTML: remove comments and unnecessary whitespace
    String compactHtml =
        resolvedHtml
            .replaceAll("(?s)<!--.*?-->", "")
            .replaceAll(">\\s+<", "><")
            .replaceAll("\\s{2,}", " ")
            .trim();

    logger.debug(
        "Loaded HTML file: {} (original: {} bytes, resolved+compacted: {} bytes)",
        filePath,
        content.length(),
        compactHtml.length());
    return compactHtml;
  }
}
