/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package com.sap.cds.notifications.assemblers;

import cds.gen.notificationtypeproviderservice.DeliveryChannels;
import cds.gen.notificationtypeproviderservice.NotificationTypes;
import com.sap.cds.Struct;
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

  public NotificationTypeAssembler(CdsRuntime runtime) {
    this.runtime = runtime;
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

    // Extract delivery channels
    List<DeliveryChannels> deliveryChannels = extractDeliveryChannels(event);
    if (!deliveryChannels.isEmpty()) {
      nt.setDeliveryChannels(deliveryChannels);
    }

    logger.debug("Extracted NotificationType: {}", key);
    return Optional.of(nt);
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
