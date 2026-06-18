/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package com.sap.cds.notifications.builders;

import static org.junit.jupiter.api.Assertions.*;

import cds.gen.notificationproviderservice.Recipients;
import com.sap.cds.notifications.assemblers.NotificationAssembler;
import com.sap.cds.ql.CQL;
import com.sap.cds.ql.cqn.CqnComparisonPredicate;
import com.sap.cds.ql.cqn.CqnContainmentTest;
import com.sap.cds.ql.cqn.CqnPredicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for recipient auto-detection logic in {@link NotificationAssembler}. Covers isUUID,
 * isEmail, and createRecipientFromId including edge cases (malformed, null, empty).
 */
class NotificationBuilderTest {

  // ── isUUID ────────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("isUUID")
  class IsUUIDTests {

    @Test
    void validLowercaseUUID() {
      assertTrue(NotificationAssembler.isUUID("550e8400-e29b-41d4-a716-446655440000"));
    }

    @Test
    void validUppercaseUUID() {
      assertTrue(NotificationAssembler.isUUID("550E8400-E29B-41D4-A716-446655440000"));
    }

    @Test
    void validRandomUUID() {
      assertTrue(NotificationAssembler.isUUID(java.util.UUID.randomUUID().toString()));
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          "not-a-uuid",
          "550e8400-e29b-41d4-a716",
          "550e8400e29b41d4a716446655440000",
          "user@example.com",
          "",
          "   "
        })
    void invalidStringsReturnFalse(String value) {
      assertFalse(NotificationAssembler.isUUID(value));
    }

    @Test
    void nullThrowsNullPointerException() {
      assertThrows(NullPointerException.class, () -> NotificationAssembler.isUUID(null));
    }
  }

  // ── isEmail ───────────────────────────────────────────────────────────────

  @Nested
  @DisplayName("isEmail")
  class IsEmailTests {

    @ParameterizedTest
    @ValueSource(
        strings = {
          "user@example.com",
          "ops-team@example.com",
          "first.last@domain.org",
          "user+tag@sub.domain.co",
          "a@b.cd",
          "user123@test-server.example.com"
        })
    void validEmailsReturnTrue(String email) {
      assertTrue(NotificationAssembler.isEmail(email), "Should accept valid email: " + email);
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          "not-an-email",
          "missing@tld",
          "@no-local.com",
          "no-at-sign.com",
          "user@.com",
          "user@domain.c",
          "",
          "   ",
          "550e8400-e29b-41d4-a716-446655440000"
        })
    void invalidEmailsReturnFalse(String value) {
      assertFalse(NotificationAssembler.isEmail(value), "Should reject invalid email: " + value);
    }

    @Test
    void nullThrowsNullPointerException() {
      assertThrows(NullPointerException.class, () -> NotificationAssembler.isEmail(null));
    }
  }

  // ── createRecipientFromId ─────────────────────────────────────────────────

  @Nested
  @DisplayName("createRecipientFromId")
  class CreateRecipientFromIdTests {

    @Test
    void validUUID_mapsToGlobalUserId() {
      String uuid = "550e8400-e29b-41d4-a716-446655440000";
      Recipients recipient = NotificationAssembler.createRecipientFromId(uuid);

      assertEquals(uuid, recipient.getGlobalUserId(), "UUID should be mapped to GlobalUserId");
      assertNull(recipient.getRecipientId(), "UUID recipient should not have RecipientId");
    }

    @Test
    void validEmail_mapsToRecipientId() {
      String email = "ops-team@example.com";
      Recipients recipient = NotificationAssembler.createRecipientFromId(email);

      assertEquals(email, recipient.getRecipientId(), "Email should be mapped to RecipientId");
      assertNull(recipient.getGlobalUserId(), "Email recipient should not have GlobalUserId");
    }

    @ParameterizedTest
    @ValueSource(strings = {"plain-text", "not-email-or-uuid", "12345", "   "})
    void unsupportedFormat_throwsIllegalArgumentException(String value) {
      IllegalArgumentException ex =
          assertThrows(
              IllegalArgumentException.class,
              () -> NotificationAssembler.createRecipientFromId(value));
      assertTrue(
          ex.getMessage().contains("Unsupported recipient format"),
          "Exception message should mention unsupported format");
    }

    @Test
    void emptyString_throwsIllegalArgumentException() {
      assertThrows(
          IllegalArgumentException.class, () -> NotificationAssembler.createRecipientFromId(""));
    }

    @ParameterizedTest
    @NullSource
    void null_throwsNullPointerException(String value) {
      assertThrows(
          NullPointerException.class, () -> NotificationAssembler.createRecipientFromId(value));
    }
  }

  // ── evaluateContainment ───────────────────────────────────────────────────

  @Nested
  @DisplayName("evaluateContainment")
  class EvaluateContainmentTests {

    // Helper: extract match result from the returned predicate.
    // CQL.comparison(val(1), EQ, val(1)) may be optimized to CqnBoolLiteral(true)
    // and CQL.comparison(val(1), EQ, val(0)) to CqnBoolLiteral(false).
    private boolean isMatch(CqnPredicate predicate) {
      if (predicate instanceof com.sap.cds.ql.cqn.CqnLiteral<?> boolLit) {
        return Boolean.TRUE.equals(boolLit.value()) || Integer.valueOf(1).equals(boolLit.value());
      }
      if (predicate instanceof CqnComparisonPredicate cmp) {
        Object rhs = ((com.sap.cds.ql.cqn.CqnLiteral<?>) cmp.right()).value();
        return Integer.valueOf(1).equals(rhs);
      }
      throw new AssertionError("Unexpected predicate type: " + predicate.getClass().getName());
    }

    // ── contains (case-sensitive) ─────────────────────────────────

    @Test
    void contains_caseSensitive_match() {
      CqnPredicate result =
          NotificationAssembler.evaluateContainment(
              CqnContainmentTest.Position.ANY,
              CQL.val("Hello World"),
              CQL.val("World"),
              false,
              null);
      assertTrue(isMatch(result));
    }

    @Test
    void contains_caseSensitive_noMatch() {
      CqnPredicate result =
          NotificationAssembler.evaluateContainment(
              CqnContainmentTest.Position.ANY,
              CQL.val("Hello World"),
              CQL.val("world"),
              false,
              null);
      assertFalse(isMatch(result));
    }

    // ── contains (case-insensitive) ───────────────────────────────

    @Test
    void contains_caseInsensitive_match() {
      CqnPredicate result =
          NotificationAssembler.evaluateContainment(
              CqnContainmentTest.Position.ANY,
              CQL.val("production-server"),
              CQL.val("PRODUCTION"),
              true,
              null);
      assertTrue(isMatch(result));
    }

    @Test
    void contains_caseInsensitive_noMatch() {
      CqnPredicate result =
          NotificationAssembler.evaluateContainment(
              CqnContainmentTest.Position.ANY,
              CQL.val("Hello World"),
              CQL.val("missing"),
              true,
              null);
      assertFalse(isMatch(result));
    }

    // ── startsWith (case-sensitive) ───────────────────────────────

    @Test
    void startsWith_caseSensitive_match() {
      CqnPredicate result =
          NotificationAssembler.evaluateContainment(
              CqnContainmentTest.Position.START,
              CQL.val("CRITICAL-alert"),
              CQL.val("CRITICAL"),
              false,
              null);
      assertTrue(isMatch(result));
    }

    @Test
    void startsWith_caseSensitive_noMatch() {
      CqnPredicate result =
          NotificationAssembler.evaluateContainment(
              CqnContainmentTest.Position.START,
              CQL.val("CRITICAL-alert"),
              CQL.val("critical"),
              false,
              null);
      assertFalse(isMatch(result));
    }

    // ── startsWith (case-insensitive) ─────────────────────────────

    @Test
    void startsWith_caseInsensitive_match() {
      CqnPredicate result =
          NotificationAssembler.evaluateContainment(
              CqnContainmentTest.Position.START,
              CQL.val("CRITICAL-alert"),
              CQL.val("critical"),
              true,
              null);
      assertTrue(isMatch(result));
    }

    @Test
    void startsWith_caseInsensitive_noMatch() {
      CqnPredicate result =
          NotificationAssembler.evaluateContainment(
              CqnContainmentTest.Position.START,
              CQL.val("Hello World"),
              CQL.val("world"),
              true,
              null);
      assertFalse(isMatch(result));
    }

    // ── endsWith (case-sensitive) ─────────────────────────────────

    @Test
    void endsWith_caseSensitive_match() {
      CqnPredicate result =
          NotificationAssembler.evaluateContainment(
              CqnContainmentTest.Position.END,
              CQL.val("server-PROD"),
              CQL.val("-PROD"),
              false,
              null);
      assertTrue(isMatch(result));
    }

    @Test
    void endsWith_caseSensitive_noMatch() {
      CqnPredicate result =
          NotificationAssembler.evaluateContainment(
              CqnContainmentTest.Position.END,
              CQL.val("server-PROD"),
              CQL.val("-prod"),
              false,
              null);
      assertFalse(isMatch(result));
    }

    // ── endsWith (case-insensitive) ───────────────────────────────

    @Test
    void endsWith_caseInsensitive_match() {
      CqnPredicate result =
          NotificationAssembler.evaluateContainment(
              CqnContainmentTest.Position.END,
              CQL.val("server-PROD"),
              CQL.val("-prod"),
              true,
              null);
      assertTrue(isMatch(result));
    }

    @Test
    void endsWith_caseInsensitive_noMatch() {
      CqnPredicate result =
          NotificationAssembler.evaluateContainment(
              CqnContainmentTest.Position.END,
              CQL.val("server-PROD"),
              CQL.val("-staging"),
              true,
              null);
      assertFalse(isMatch(result));
    }

    // ── edge cases ────────────────────────────────────────────────

    @Test
    void emptyTerm_alwaysMatches() {
      CqnPredicate result =
          NotificationAssembler.evaluateContainment(
              CqnContainmentTest.Position.ANY, CQL.val("anything"), CQL.val(""), false, null);
      assertTrue(isMatch(result));
    }

    @Test
    void emptyValue_noMatch() {
      CqnPredicate result =
          NotificationAssembler.evaluateContainment(
              CqnContainmentTest.Position.ANY, CQL.val(""), CQL.val("something"), false, null);
      assertFalse(isMatch(result));
    }

    @Test
    void bothEmpty_matches() {
      CqnPredicate result =
          NotificationAssembler.evaluateContainment(
              CqnContainmentTest.Position.ANY, CQL.val(""), CQL.val(""), false, null);
      assertTrue(isMatch(result));
    }
  }
}
