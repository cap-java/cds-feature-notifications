/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package com.sap.cds.notifications.assemblers;

import cds.gen.notificationtypeproviderservice.DeliveryChannels;
import cds.gen.notificationtypeproviderservice.NotificationTypes;
import cds.gen.notificationtypeproviderservice.Translations;
import com.sap.cds.Struct;
import com.sap.cds.notifications.helpers.I18nHelper;
import com.sap.cds.reflect.CdsEvent;
import com.sap.cds.reflect.CdsModel;
import com.sap.cds.services.runtime.CdsRuntime;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Helper class to build NotificationType objects from CDS event annotations. */
public class NotificationTypeAssembler {

  private static final Logger logger = LoggerFactory.getLogger(NotificationTypeAssembler.class);

  private final CdsRuntime runtime;
  private final I18nHelper i18nHelper;

  public NotificationTypeAssembler(CdsRuntime runtime) {
    this.runtime = runtime;
    this.i18nHelper = new I18nHelper(runtime);
  }

  /** Build all notification types from CDS model event annotations. */
  public List<NotificationTypes> buildAllNotificationTypes() {
    List<NotificationTypes> notificationTypes = new ArrayList<>();
    CdsModel model = runtime.getCdsModel();

    model
        .events()
        .filter(event -> event.findAnnotation("notification.template.title").isPresent())
        .forEach(
            event -> extractNotificationTypeFromEvent(event).ifPresent(notificationTypes::add));

    return notificationTypes;
  }

  /** Extract NotificationType from a CDS event with annotations. */
  private Optional<NotificationTypes> extractNotificationTypeFromEvent(CdsEvent event) {
    String key = event.getName();

    NotificationTypes nt = Struct.create(NotificationTypes.class);
    nt.setNotificationTypeKey(key);
    nt.setNotificationTypeVersion("1");

    // Extract translations (required by ANS — at least Translations or Templates must be present)
    List<Translations> translations = extractTranslations(event);
    nt.setTranslations(translations);

    // Extract delivery channels
    List<DeliveryChannels> deliveryChannels = extractDeliveryChannels(event);
    if (!deliveryChannels.isEmpty()) {
      nt.setDeliveryChannels(deliveryChannels);
    }

    logger.debug("Extracted NotificationType: {} with {} translation(s)", key, translations.size());
    return Optional.of(nt);
  }

  /** ANS Translation field constraints (per ANS API spec). */
  private static final int MAX_LANGUAGE_LENGTH = 20;

  private static final int MAX_TEXT_LENGTH = 256;

  /**
   * Extract Translations from CDS event annotations for all available i18n locales.
   *
   * <p>Mapping:
   *
   * <ul>
   *   <li>{@code @notification.template.title} → DisplayName (required)
   *   <li>{@code @notification.template.groupedTitle} → GroupTitle (required)
   *   <li>{@code @description} → Description (optional)
   * </ul>
   */
  private List<Translations> extractTranslations(CdsEvent event) {
    Set<Locale> locales = i18nHelper.getAvailableLocalesForEvent(event);
    List<Translations> translations = new ArrayList<>();

    for (Locale locale : locales) {
      Map<String, String> i18nTexts = i18nHelper.getI18nTexts(locale);

      Translations translation = Struct.create(Translations.class);
      translation.setLanguage(truncate(locale.toLanguageTag(), MAX_LANGUAGE_LENGTH));

      // DisplayName — from @notification.template.title
      String displayName =
          i18nHelper.resolveAnnotationValue(event, "notification.template.title", i18nTexts);
      if (displayName == null || displayName.isBlank()) {
        displayName = event.getName();
      }
      translation.setDisplayName(truncate(displayName, MAX_TEXT_LENGTH));

      // GroupTitle — from @notification.template.groupedTitle (required)
      String groupTitle =
          i18nHelper.resolveAnnotationValue(event, "notification.template.groupedTitle", i18nTexts);
      if (groupTitle == null || groupTitle.isBlank()) {
        throw new IllegalStateException(
            String.format(
                "Missing required annotation: @notification.template.groupedTitle for event '%s'.",
                event.getName()));
      }
      translation.setGroupTitle(truncate(groupTitle, MAX_TEXT_LENGTH));

      translation.setSyntax("MUSTACHE");

      // Description — from @description (optional)
      String description = i18nHelper.resolveAnnotationValue(event, "description", i18nTexts);
      if (description != null && !description.isBlank()) {
        translation.setDescription(truncate(description, MAX_TEXT_LENGTH));
      }

      translations.add(translation);
      logger.debug(
          "Created NotificationType translation: lang={}, displayName={}, groupTitle={}",
          locale.toLanguageTag(),
          displayName,
          groupTitle);
    }

    return translations;
  }

  private static String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength);
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
