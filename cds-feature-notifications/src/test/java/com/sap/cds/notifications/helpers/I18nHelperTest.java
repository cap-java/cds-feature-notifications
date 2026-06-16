/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package com.sap.cds.notifications.helpers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sap.cds.adapter.edmx.EdmxI18nProvider;
import com.sap.cds.services.runtime.CdsRuntime;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class I18nHelperTest {

  private I18nHelper i18nHelper;

  @BeforeEach
  void setUp() {
    i18nHelper = new I18nHelper(mock(CdsRuntime.class));
  }

  @Nested
  @DisplayName("resolveI18n")
  class ResolveI18nTests {

    @Test
    void nullValue_returnsNull() {
      assertNull(i18nHelper.resolveI18n(null, Map.of()));
    }

    @Test
    void noPlaceholder_returnsOriginal() {
      String input = "Book Order";
      assertEquals(input, i18nHelper.resolveI18n(input, Map.of("SUBTITLE", "Book Order")));
    }

    @Test
    void singlePlaceholder_resolved() {
      assertEquals(
          "Book Order",
          i18nHelper.resolveI18n("{i18n>SUBTITLE}", Map.of("SUBTITLE", "Book Order")));
    }

    @Test
    void unknownKey_notReplaced() {
      String input = "{i18n>UNKNOWN_KEY}";
      assertEquals(input, i18nHelper.resolveI18n(input, Map.of("SUBTITLE", "Book Order")));
    }

    @Test
    void multiplePlaceholders_allResolved() {
      String input = "{i18n>EMAIL_SUBJECT}: {i18n>SUBTITLE}";
      assertEquals(
          "Book Order: Book has been ordered",
          i18nHelper.resolveI18n(
              input,
              Map.of(
                  "EMAIL_SUBJECT", "Book Order",
                  "SUBTITLE", "Book has been ordered")));
    }
  }

  @Nested
  @DisplayName("loadHtmlFromClasspath")
  class LoadHtmlFromClasspathTests {

    @Test
    void fileNotFound_returnsNull() {
      assertNull(i18nHelper.loadHtmlFromClasspath("non/existent/file.html", Map.of()));
    }
  }

  @Nested
  @DisplayName("getAvailableLocales")
  class GetAvailableLocalesTests {

    @Test
    void nullProvider_fallsBackToEnglish() {
      CdsRuntime runtime = mock(CdsRuntime.class);
      when(runtime.getProvider(EdmxI18nProvider.class)).thenReturn(null);
      I18nHelper helper = new I18nHelper(runtime);

      Set<Locale> locales = helper.getAvailableLocales();
      assertEquals(Set.of(Locale.ENGLISH), locales);
    }

    @Test
    void emptyLocalesFromProvider_fallsBackToEnglish() {
      EdmxI18nProvider provider = mock(EdmxI18nProvider.class);
      when(provider.getLocales()).thenReturn(Collections.emptySet());
      CdsRuntime runtime = mock(CdsRuntime.class);
      when(runtime.getProvider(EdmxI18nProvider.class)).thenReturn(provider);
      I18nHelper helper = new I18nHelper(runtime);

      Set<Locale> locales = helper.getAvailableLocales();
      assertEquals(Set.of(Locale.ENGLISH), locales);
    }
  }

  @Nested
  @DisplayName("getI18nTexts")
  class GetI18nTextsTests {

    @Test
    void nullProvider_returnsEmptyMap() {
      CdsRuntime runtime = mock(CdsRuntime.class);
      when(runtime.getProvider(EdmxI18nProvider.class)).thenReturn(null);
      I18nHelper helper = new I18nHelper(runtime);

      assertTrue(helper.getI18nTexts(Locale.ENGLISH).isEmpty());
    }
  }
}
