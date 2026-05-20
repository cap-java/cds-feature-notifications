/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package customer.sample_app.integration;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import cds.gen.catalogservice.Alerts;
import cds.gen.catalogservice.Alerts_;
import cds.gen.catalogservice.Books;
import cds.gen.catalogservice.BooksRestockContext;
import cds.gen.catalogservice.Books_;
import cds.gen.catalogservice.CatalogService_;
import cds.gen.notificationproviderservice.NotificationProperties;
import cds.gen.notificationproviderservice.Notifications;
import cds.gen.notificationproviderservice.Recipients;
import com.sap.cds.Result;
import com.sap.cds.ql.Delete;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.cds.CqnService;
import customer.sample_app.handlers.mock.NotificationProviderServiceMockHandler;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for entity-level @notifications annotation. Tests that CRUD operations on
 * annotated entities trigger notifications.
 */
@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(username = "test.user@example.com")
public class EntityNotificationIntegrationTest {

  private static final Logger LOG =
      LoggerFactory.getLogger(EntityNotificationIntegrationTest.class);

  @Autowired
  @Qualifier(CatalogService_.CDS_NAME)
  private CqnService catalogService;

  @BeforeEach
  void setup() {
    NotificationProviderServiceMockHandler.clearAllNotifications();
  }

  @Test
  void testEntityCreateTriggersNotification() {
    // Given: A Books entity annotated with @notifications on CREATE + where : ($self.stock > 50)
    Books book = Books.create();
    book.setId(999);
    book.setTitle("Test Book");
    book.setStock(100);

    // When: INSERT into CatalogService.Books
    Result insertResult = catalogService.run(Insert.into(Books_.CDS_NAME).entry(book));

    // Get the createdBy value set by the managed aspect ($user)
    String createdBy = (String) insertResult.single().get("createdBy");
    LOG.debug("Managed aspect set createdBy to: '{}'", createdBy);

    // If no user context → createdBy is null → recipients can't be resolved → skip test
    Assumptions.assumeTrue(
        createdBy != null && !createdBy.isEmpty(),
        "Test requires a user context to resolve $self.createdBy");

    // Then: A notification should be emitted
    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);

    Notifications stored = NotificationProviderServiceMockHandler.getAllNotifications().get(0);

    LOG.debug(
        "Entity notification received: type={}, priority={}, recipient={}",
        stored.getNotificationTypeKey(),
        stored.getPriority(),
        stored.getRecipients().get(0).getRecipientId());

    // Verify notification type
    assertEquals(
        "CertificateExpiration",
        stored.getNotificationTypeKey(),
        "Notification type key should match @notifications.type");

    // Verify recipients
    assertNotNull(stored.getRecipients(), "Recipients should not be null");
    assertFalse(stored.getRecipients().isEmpty(), "Recipients should not be empty");
    assertEquals(
        createdBy,
        stored.getRecipients().get(0).getRecipientId(),
        "Recipient should be resolved from $self.createdBy");

    // Verify parameters mapping: entity fields → CertificateExpiration event fields
    // name ← $self.createdBy, certificateName ← $self.title, year ← $self.stock
    List<NotificationProperties> props = stored.getProperties();
    assertNotNull(props, "Properties should not be null when parameters is configured");

    Map<String, String> propMap = new HashMap<>();
    props.forEach(p -> propMap.put(p.getKey(), p.getValue()));
    LOG.debug("Notification properties from parameters mapping: {}", propMap);

    assertEquals(
        "Test Book",
        propMap.get("certificateName"),
        "Parameter 'certificateName' should be mapped from $self.title");
    assertEquals("100", propMap.get("year"), "Parameter 'year' should be mapped from $self.stock");
    assertEquals(
        createdBy, propMap.get("name"), "Parameter 'name' should be mapped from $self.createdBy");

    // Entity-specific fields (ID, stock, title) should NOT appear under their original names
    assertFalse(
        propMap.containsKey("ID"),
        "ID should not be in properties when explicit parameters mapping is used");
    assertFalse(
        propMap.containsKey("title"),
        "Original 'title' key should not be in properties — it was remapped to 'certificateName'");
  }

  @Test
  void testEntityUpdateTriggersMixedArrayRecipients() {
    // Given: Insert a book with stock > 50 (where condition met → CREATE notification fires)
    Books book = Books.create();
    book.setId(998);
    book.setTitle("Book to Update");
    book.setStock(100);
    catalogService.run(Insert.into(Books_.CDS_NAME).entry(book));

    // Clear any notification from the CREATE
    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);
    NotificationProviderServiceMockHandler.clearAllNotifications();

    // When: UPDATE the book → triggers mixed array recipients ['ops-team@example.com', 'UUID']
    Map<String, Object> update = new HashMap<>();
    update.put("ID", 998);
    update.put("stock", 20);
    catalogService.run(Update.entity(Books_.CDS_NAME).data(update));

    // Then: Notification with 2 recipients (static email + static UUID)
    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);

    Notifications stored = NotificationProviderServiceMockHandler.getAllNotifications().get(0);
    List<Recipients> recipients = stored.getRecipients();

    LOG.debug(
        "Mixed array recipients: count={}, r1={}, r2={}",
        recipients.size(),
        recipients.get(0).getRecipientId(),
        recipients.size() > 1 ? recipients.get(1).getGlobalUserId() : "N/A");

    assertEquals(2, recipients.size(), "Should have 2 recipients (email + UUID)");

    // First recipient: static email → RecipientId
    assertEquals(
        "ops-team@example.com",
        recipients.get(0).getRecipientId(),
        "First recipient (email) should be mapped to RecipientId");
    assertNull(recipients.get(0).getGlobalUserId(), "First recipient should not have GlobalUserId");

    // Second recipient: static UUID → GlobalUserId
    assertNull(
        recipients.get(1).getRecipientId(), "Second recipient (UUID) should not have RecipientId");
    assertEquals(
        "550e8400-e29b-41d4-a716-446655440000",
        recipients.get(1).getGlobalUserId(),
        "Second recipient (UUID) should be mapped to GlobalUserId");
  }

  @Test
  void testEntityDeleteDoesNotTriggerNotification() {
    // Given: Insert a book with stock > 50 (where condition met → CREATE notification fires)
    Books book = Books.create();
    book.setId(997);
    book.setTitle("Book to Delete");
    book.setStock(100);
    catalogService.run(Insert.into(Books_.CDS_NAME).entry(book));

    // Clear any notification from the CREATE
    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);
    NotificationProviderServiceMockHandler.clearAllNotifications();

    // When: DELETE the book (not in 'on' list — only CREATE is configured)
    catalogService.run(Delete.from(Books_.CDS_NAME).matching(Map.of("ID", 997)));

    // Then: No notification should be emitted
    await()
        .during(1, SECONDS)
        .atMost(2, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() == 0);

    assertEquals(
        0,
        NotificationProviderServiceMockHandler.getNotificationCount(),
        "DELETE should NOT trigger notification when only CREATE is in 'on' list");
  }

  @Test
  void testBoundActionTriggersNotification() {
    // Given: Insert a book with stock > 50 (where condition met → CREATE notification fires)
    Books book = Books.create();
    book.setId(996);
    book.setTitle("Book for Restock");
    book.setStock(100);
    Result insertResult = catalogService.run(Insert.into(Books_.CDS_NAME).entry(book));

    String createdBy = (String) insertResult.single().get("createdBy");
    LOG.debug("Managed aspect set createdBy to: '{}'", createdBy);

    // Clear any notification from the CREATE
    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);
    NotificationProviderServiceMockHandler.clearAllNotifications();

    Assumptions.assumeTrue(
        createdBy != null && !createdBy.isEmpty(),
        "Test requires a user context to resolve $self.createdBy");

    // When: Call bound action 'restock' on the book
    BooksRestockContext restockCtx = BooksRestockContext.create();
    restockCtx.setAmount(100);
    restockCtx.setCqn(Select.from(Books_.class).matching(Map.of("ID", 996)));
    catalogService.emit(restockCtx);

    // Then: A notification should be emitted for the 'restock' action
    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);

    Notifications stored = NotificationProviderServiceMockHandler.getAllNotifications().get(0);

    LOG.debug(
        "Bound action notification received: type={}, priority={}, recipient={}",
        stored.getNotificationTypeKey(),
        stored.getPriority(),
        stored.getRecipients().get(0).getRecipientId());

    // Verify notification type
    assertEquals(
        "CertificateExpiration",
        stored.getNotificationTypeKey(),
        "Notification type key should match @notifications.type for restock action");

    // Verify recipient: resolved from $self.createdBy → auto-detected as email → RecipientId
    assertNotNull(stored.getRecipients(), "Recipients should not be null");
    assertFalse(stored.getRecipients().isEmpty(), "Recipients should not be empty");
    assertEquals(
        createdBy,
        stored.getRecipients().get(0).getRecipientId(),
        "Recipient should be resolved from $self.createdBy of the book entity");

    // Verify parameters mapping
    List<NotificationProperties> props = stored.getProperties();
    assertNotNull(props, "Properties should not be null");

    Map<String, String> propMap = new HashMap<>();
    props.forEach(p -> propMap.put(p.getKey(), p.getValue()));
    LOG.debug("Bound action notification properties: {}", propMap);

    assertEquals(
        "Book for Restock",
        propMap.get("certificateName"),
        "Parameter 'certificateName' should be mapped from $self.title");
    assertEquals(
        createdBy, propMap.get("name"), "Parameter 'name' should be mapped from $self.createdBy");
  }

  @Test
  void testCreateWithWhereConditionNotMet() {
    // Given: A book with stock ≤ 50 (where condition: $self.stock > 50 is NOT met)
    Books book = Books.create();
    book.setId(990);
    book.setTitle("Low Stock Book");
    book.setStock(30);

    // When: INSERT the book
    catalogService.run(Insert.into(Books_.CDS_NAME).entry(book));

    // Then: No notification should be emitted (where condition filters it out)
    await()
        .during(1, SECONDS)
        .atMost(2, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() == 0);

    assertEquals(
        0,
        NotificationProviderServiceMockHandler.getNotificationCount(),
        "CREATE with stock=30 should NOT trigger notification when where : ($self.stock > 50)");
  }

  @Test
  void testCreateWithWhereConditionBoundary() {
    // Given: A book with stock = 50 exactly (where condition: $self.stock > 50 is NOT met)
    Books book = Books.create();
    book.setId(989);
    book.setTitle("Boundary Stock Book");
    book.setStock(50);

    // When: INSERT the book
    catalogService.run(Insert.into(Books_.CDS_NAME).entry(book));

    // Then: No notification — 50 is NOT > 50
    await()
        .during(1, SECONDS)
        .atMost(2, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() == 0);

    assertEquals(
        0,
        NotificationProviderServiceMockHandler.getNotificationCount(),
        "CREATE with stock=50 should NOT trigger notification (50 is not > 50)");
  }

  @Test
  void testCreateWithWhereContainsConditionMet() {
    // Given: A book whose title contains 'Java' (where : contains($self.title, 'Java'))
    //        stock ≤ 50, so only the contains-where notification fires (not the stock > 50 one)
    Books book = Books.create();
    book.setId(988);
    book.setTitle("Learning Java Programming");
    book.setStock(10);

    // When: INSERT the book
    catalogService.run(Insert.into(Books_.CDS_NAME).entry(book));

    // Then: Exactly 1 notification from the contains-where rule (recipient: java-team@example.com)
    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);

    assertEquals(
        1,
        NotificationProviderServiceMockHandler.getNotificationCount(),
        "CREATE with title containing 'Java' should trigger the contains-where notification");

    Notifications stored = NotificationProviderServiceMockHandler.getAllNotifications().get(0);
    assertEquals(
        "java-team@example.com",
        stored.getRecipients().get(0).getRecipientId(),
        "Recipient should be 'java-team@example.com' from the contains-where rule");
  }

  @Test
  void testCreateWithWhereContainsConditionNotMet() {
    // Given: A book whose title does NOT contain 'Java'
    //        stock ≤ 50, so neither where condition is met
    Books book = Books.create();
    book.setId(987);
    book.setTitle("Learning Python Guide");
    book.setStock(10);

    // When: INSERT the book
    catalogService.run(Insert.into(Books_.CDS_NAME).entry(book));

    // Then: No notification — title doesn't contain 'Java' and stock is not > 50
    await()
        .during(1, SECONDS)
        .atMost(2, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() == 0);

    assertEquals(
        0,
        NotificationProviderServiceMockHandler.getNotificationCount(),
        "CREATE with title not containing 'Java' and stock ≤ 50 should NOT trigger any"
            + " notification");
  }

  @Test
  void testCreateWithWhereNowConditionMet() {
    // Given: An Alerts entity annotated with where: ($self.createdAt < $now).
    //        The managed aspect sets createdAt during INSERT, and $now resolves to Instant.now()
    //        at evaluation time, so createdAt < $now is deterministically true.
    Alerts alert = Alerts.create();
    alert.setId(1001);
    alert.setMessage("Disk full");
    alert.setSeverity("CRITICAL");

    // When: INSERT into CatalogService.Alerts
    catalogService.run(Insert.into(Alerts_.CDS_NAME).entry(alert));

    // Then: The $now-based where condition fires → temporal-team@example.com receives notification
    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);

    Notifications stored = NotificationProviderServiceMockHandler.getAllNotifications().get(0);

    assertEquals(
        "temporal-team@example.com",
        stored.getRecipients().get(0).getRecipientId(),
        "Recipient should be 'temporal-team@example.com' from the $now-where rule on Alerts");

    assertEquals(
        "SecurityAlert", stored.getNotificationTypeKey(), "Notification type should match");

    // Verify parameters
    List<NotificationProperties> props = stored.getProperties();
    assertNotNull(props, "Properties should not be null");

    Map<String, String> propMap = new HashMap<>();
    props.forEach(p -> propMap.put(p.getKey(), p.getValue()));
    LOG.debug("$now-where notification properties from Alerts entity: {}", propMap);

    assertEquals(
        "Disk full",
        propMap.get("alertSource"),
        "Parameter 'alertSource' should be mapped from $self.message");
    assertEquals(
        "CRITICAL",
        propMap.get("severity"),
        "Parameter 'severity' should be mapped from $self.severity");
  }

  @Test
  void testContainsWithNullTerm_noMatch() {
    // Given: An Alerts entity with message = "some text" but category = NULL.
    //        The rule: contains($self.message, $self.category)
    //        When the term (category) is NULL, evaluateContainment short-circuits to 1=0 (no
    // match).
    Alerts alert = Alerts.create();
    alert.setId(1010);
    alert.setMessage("some text");
    alert.setSeverity("LOW");
    alert.setCategory(null); // term is NULL

    // When: INSERT into CatalogService.Alerts
    catalogService.run(Insert.into(Alerts_.CDS_NAME).entry(alert));

    // Then: The $now rule fires (createdAt < $now is always true), but the contains rule
    //       must NOT fire because category (term) is NULL.
    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() > 0);

    // Wait to ensure only 1 notification arrives (from $now rule only)
    await()
        .during(1, SECONDS)
        .atMost(2, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() == 1);

    // Verify the contains rule (null-term-test@example.com) did NOT fire
    boolean containsRuleFired =
        NotificationProviderServiceMockHandler.getAllNotifications().stream()
            .anyMatch(
                n ->
                    n.getRecipients().stream()
                        .anyMatch(r -> "null-term-test@example.com".equals(r.getRecipientId())));
    assertFalse(
        containsRuleFired,
        "contains($self.message, $self.category) should NOT match when category is NULL");
  }

  @Test
  void testContainsWithNonNullTerm_matches() {
    // Given: An Alerts entity where message contains the category value.
    //        Rule: contains($self.message, $self.category, true) → case-insensitive.
    //        message = "critical failure detected", category = "CRITICAL" → matches.
    Alerts alert = Alerts.create();
    alert.setId(1011);
    alert.setMessage("critical failure detected");
    alert.setSeverity("HIGH");
    alert.setCategory("CRITICAL");

    // When: INSERT into CatalogService.Alerts
    catalogService.run(Insert.into(Alerts_.CDS_NAME).entry(alert));

    // Then: Both $now rule AND contains rule fire → ≥ 2 notifications
    await()
        .atMost(5, SECONDS)
        .until(() -> NotificationProviderServiceMockHandler.getNotificationCount() >= 2);

    // Verify the contains rule (null-term-test@example.com) DID fire
    boolean containsRuleFired =
        NotificationProviderServiceMockHandler.getAllNotifications().stream()
            .anyMatch(
                n ->
                    n.getRecipients().stream()
                        .anyMatch(r -> "null-term-test@example.com".equals(r.getRecipientId())));
    assertTrue(
        containsRuleFired,
        "contains($self.message, $self.category, true) should match case-insensitively"
            + " when both are non-null");
  }
}
