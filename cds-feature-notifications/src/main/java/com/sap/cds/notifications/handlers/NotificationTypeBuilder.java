/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package com.sap.cds.notifications.handlers;

import cds.gen.notificationtypeproviderservice.DeliveryChannels;
import cds.gen.notificationtypeproviderservice.NotificationTypes;
import cds.gen.notificationtypeproviderservice.Templates;
import com.sap.cds.Struct;
import com.sap.cds.notifications.helpers.I18nHelper;
import com.sap.cds.reflect.CdsEvent;
import com.sap.cds.reflect.CdsModel;
import com.sap.cds.services.runtime.CdsRuntime;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Helper class to build NotificationType objects from CDS event annotations. */
public class NotificationTypeBuilder {

  private static final Logger logger = LoggerFactory.getLogger(NotificationTypeBuilder.class);

  private final CdsRuntime runtime;
  private final I18nHelper i18nHelper;

  public NotificationTypeBuilder(CdsRuntime runtime) {
    this.runtime = runtime;
    this.i18nHelper = new I18nHelper(runtime);
  }

  /** Build all notification types from CDS model event annotations. */
  public List<NotificationTypes> buildAllNotificationTypes() {
    List<NotificationTypes> notificationTypes = new ArrayList<>();
    CdsModel model = runtime.getCdsModel();

    model.events()
        .filter(event -> event.findAnnotation("notification.template.title").isPresent())
        .forEach(event -> extractNotificationTypeFromEvent(event).ifPresent(notificationTypes::add));

    return notificationTypes;
  }

  /** Extract NotificationType from a CDS event with annotations. */
  private Optional<NotificationTypes> extractNotificationTypeFromEvent(CdsEvent event) {
    String key = event.getName();

    NotificationTypes nt = Struct.create(NotificationTypes.class);
    nt.setNotificationTypeKey(key);
    nt.setNotificationTypeVersion("1");

    Set<Locale> locales = i18nHelper.getAvailableLocales();
    logger.debug("Creating templates for {} discovered i18n locales", locales.size());

    List<Templates> templates = new ArrayList<>();

    // Create template for each discovered locale using EdmxI18nProvider
    for (Locale locale : locales) {
      String lang = locale.toLanguageTag();
      Map<String, String> i18nTexts = i18nHelper.getI18nTexts(locale);
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

    logger.debug("Extracted NotificationType: {}", key);
    return Optional.of(nt);
  }

  private Templates createTemplate(CdsEvent event, String lang, Map<String, String> i18nTexts) {
    Templates template = Struct.create(Templates.class);
    template.setLanguage(lang);

    // Validate and set required fields
    String publicTitle =
        i18nHelper.resolveAnnotationValue(event, "notification.template.publicTitle", i18nTexts);
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

    String sensitiveTitle = i18nHelper.resolveAnnotationValue(event, "notification.template.title", i18nTexts);
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
        i18nHelper.resolveAnnotationValue(event, "notification.template.groupedTitle", i18nTexts);
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

    template.setDescription(i18nHelper.resolveAnnotationValue(event, "description", i18nTexts));

    String subtitle = i18nHelper.resolveAnnotationValue(event, "notification.template.subtitle", i18nTexts);
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
        i18nHelper.resolveAnnotationValue(event, "notification.template.email.subject", i18nTexts));

    // Resolve email HTML - if it's a file path, load the file content
    String emailHtmlValue =
        i18nHelper.resolveAnnotationValue(event, "notification.template.email.html", i18nTexts);
    if (emailHtmlValue != null && emailHtmlValue.endsWith(".html")) {
      String htmlContent = i18nHelper.loadHtmlFromClasspath(emailHtmlValue, i18nTexts);
      template.setEmailHtml(htmlContent);
      logger.debug("Loaded HTML template from file: {}", emailHtmlValue);
    } else {
      template.setEmailHtml(emailHtmlValue);
    }

    template.setEmailText(i18nHelper.resolveAnnotationValue(event, "notificationType.emailText", i18nTexts));
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

      logger.debug(
          "Parsed delivery channel: Type={}, Enabled={}, DefaultPreference={}",
          deliveryChannel.getType(),
          deliveryChannel.getEnabled(),
          deliveryChannel.getDefaultPreference());
    }

    return deliveryChannels;
  }
}
