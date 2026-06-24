/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package com.sap.cds.notifications.assemblers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cds.gen.notificationtypeproviderservice.NotificationTypes;
import cds.gen.notificationtypeproviderservice.Translations;
import com.sap.cds.adapter.edmx.EdmxI18nProvider;
import com.sap.cds.reflect.CdsAnnotation;
import com.sap.cds.reflect.CdsEvent;
import com.sap.cds.reflect.CdsModel;
import com.sap.cds.services.runtime.CdsRuntime;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NotificationTypeAssemblerTest {

  private CdsRuntime runtime;
  private EdmxI18nProvider i18nProvider;

  @BeforeEach
  void setUp() {
    runtime = mock(CdsRuntime.class);
    i18nProvider = mock(EdmxI18nProvider.class);
    when(runtime.getProvider(EdmxI18nProvider.class)).thenReturn(i18nProvider);
    when(i18nProvider.getLocales()).thenReturn(Set.of(Locale.ENGLISH));
    when(i18nProvider.getTexts(Locale.ENGLISH)).thenReturn(Collections.emptyMap());
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private CdsEvent mockEvent(String name, String title, String groupedTitle) {
    CdsEvent event = mock(CdsEvent.class);
    when(event.getName()).thenReturn(name);

    CdsAnnotation titleAnno = mock(CdsAnnotation.class);
    when(titleAnno.getValue()).thenReturn(title != null ? title : "");
    // @notification.template.title is used to filter events in buildAllNotificationTypes
    when(event.findAnnotation("notification.template.title"))
        .thenReturn(Optional.of(mock(CdsAnnotation.class)));
    // @notification.template.publicTitle is used as DisplayName in Translations
    when(event.findAnnotation("notification.template.publicTitle"))
        .thenReturn(title != null ? Optional.of(titleAnno) : Optional.empty());

    if (groupedTitle != null) {
      CdsAnnotation groupAnno = mock(CdsAnnotation.class);
      when(groupAnno.getValue()).thenReturn(groupedTitle);
      when(event.findAnnotation("notification.template.groupedTitle"))
          .thenReturn(Optional.of(groupAnno));
    } else {
      when(event.findAnnotation("notification.template.groupedTitle")).thenReturn(Optional.empty());
    }

    when(event.findAnnotation("description")).thenReturn(Optional.empty());
    when(event.findAnnotation("notification.deliveryChannels")).thenReturn(Optional.empty());
    return event;
  }

  private List<NotificationTypes> build(CdsEvent event) {
    CdsModel model = mock(CdsModel.class);
    when(runtime.getCdsModel()).thenReturn(model);
    when(model.events()).thenReturn(Stream.of(event));
    return new NotificationTypeAssembler(runtime).buildAllNotificationTypes();
  }

  @Test
  void happyPath_translationBuiltCorrectly() {
    CdsEvent event = mockEvent("BookOrdered", "New book order", "{{_group_count}} new orders");
    List<NotificationTypes> result = build(event);

    assertEquals(1, result.size());
    Translations t = result.get(0).getTranslations().get(0);
    assertEquals("New book order", t.getDisplayName());
    assertEquals("{{_group_count}} new orders", t.getGroupTitle());
    assertEquals("MUSTACHE", t.getSyntax());
    assertEquals("en", t.getLanguage());
  }

  @Test
  void missingGroupedTitle_throwsIllegalStateException() {
    CdsEvent event = mockEvent("BookOrdered", "New book order", null);
    assertThrows(IllegalStateException.class, () -> build(event));
  }

  @Test
  void missingPublicTitle_throwsIllegalStateException() {
    CdsEvent event = mockEvent("BookOrdered", null, "{{_group_count}} new orders");
    assertThrows(IllegalStateException.class, () -> build(event));
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void description_setWhenPresent() {
    CdsEvent event = mockEvent("BookOrdered", "New book order", "{{_group_count}} new orders");
    CdsAnnotation descAnno = mock(CdsAnnotation.class);
    when(descAnno.getValue()).thenReturn("Handles book orders");
    when(event.findAnnotation("description")).thenReturn(Optional.of(descAnno));

    Translations t = build(event).get(0).getTranslations().get(0);
    assertEquals("Handles book orders", t.getDescription());
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void deliveryChannels_parsedCorrectly() {
    CdsEvent event = mockEvent("BookOrdered", "New book order", "{{_group_count}} new orders");
    CdsAnnotation channelsAnno = mock(CdsAnnotation.class);
    when(channelsAnno.getValue())
        .thenReturn(List.of(Map.of("channel", "MAIL", "enabled", true, "defaultPreference", true)));
    when(event.findAnnotation("notification.deliveryChannels"))
        .thenReturn(Optional.of(channelsAnno));

    List<NotificationTypes> result = build(event);
    assertNotNull(result.get(0).getDeliveryChannels());
    assertEquals(1, result.get(0).getDeliveryChannels().size());
    assertEquals("MAIL", result.get(0).getDeliveryChannels().get(0).getType());
  }
}
