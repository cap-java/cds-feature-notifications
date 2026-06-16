/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package com.sap.cds.notifications.builders;

import cds.gen.notificationproviderservice.NotificationProperties;
import cds.gen.notificationproviderservice.Notifications;
import cds.gen.notificationproviderservice.Recipients;
import com.google.common.annotations.VisibleForTesting;
import com.sap.cds.CdsData;
import com.sap.cds.Result;
import com.sap.cds.Struct;
import com.sap.cds.impl.parser.builder.ExpressionBuilder;
import com.sap.cds.ql.CQL;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Value;
import com.sap.cds.ql.cqn.CqnComparisonPredicate;
import com.sap.cds.ql.cqn.CqnContainmentTest;
import com.sap.cds.ql.cqn.CqnElementRef;
import com.sap.cds.ql.cqn.CqnLiteral;
import com.sap.cds.ql.cqn.CqnPredicate;
import com.sap.cds.ql.cqn.CqnValue;
import com.sap.cds.ql.cqn.Modifier;
import com.sap.cds.ql.impl.ExpressionVisitor;
import com.sap.cds.reflect.CdsEvent;
import com.sap.cds.reflect.CdsModel;
import com.sap.cds.services.EventContext;
import com.sap.cds.services.ServiceCatalog;
import com.sap.cds.services.impl.persistence.JdbcPersistenceService;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.runtime.CdsRuntime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class to build notification objects from event context. Reduces code duplication between
 * ProductionHandler and LocalHandler.
 */
public class NotificationBuilder {

  private static final Logger logger = LoggerFactory.getLogger(NotificationBuilder.class);

  /** Valid priority values as defined by the SAP Alert Notification service. */
  private static final Set<String> VALID_PRIORITIES = Set.of("LOW", "NEUTRAL", "MEDIUM", "HIGH");

  private final CdsRuntime runtime;

  public NotificationBuilder(CdsRuntime runtime) {
    this.runtime = runtime;
  }

  /**
   * Build notifications from event context. Supports both single and batch payloads.
   *
   * <p>If {@code context.get("data")} is a {@link List}, each entry is treated as a separate
   * notification payload (batch mode). If it is a single {@link CdsData}, a single-element list is
   * returned (backward compatible).
   *
   * @return list of build results, or an empty list if the event is not a notification event
   */
  public List<NotificationBuildResult> buildNotifications(EventContext context) {
    String eventName = context.getEvent();

    logger.debug("Processing event: {}", eventName);

    // Find event from CDS Model
    CdsModel model = runtime.getCdsModel();
    Optional<CdsEvent> eventOpt = model.findEvent(eventName);

    // If not found by simple name, search all namespaces
    if (eventOpt.isEmpty()) {
      logger.debug(
          "Event '{}' not found by simple name, searching in all namespaces...", eventName);
      eventOpt = model.events().filter(e -> e.getName().equals(eventName)).findFirst();
    }

    // If event is not found or is not a CDS event, silently skip
    if (eventOpt.isEmpty()) {
      return List.of();
    }

    CdsEvent event = eventOpt.get();
    logger.debug("Found CDS event: {}", event.getQualifiedName());

    // Check if event has @notificationType annotation
    boolean hasNotificationTypeAnnotation =
        event.findAnnotation("notification.template.title").isPresent();

    // If @notificationType is not present, silently skip
    if (!hasNotificationTypeAnnotation) {
      return List.of();
    }

    // Determine single vs batch payload
    Object eventDataObj = context.get("data");

    if (eventDataObj instanceof List<?> dataList) {
      // Batch mode: List<CdsData>
      if (dataList.isEmpty()) {
        logger.warn(
            "Batch notification emit for event '{}' with empty data list, skipping", eventName);
        return List.of();
      }
      logger.debug("Batch notification emit for event '{}': {} entries", eventName, dataList.size());
      List<NotificationBuildResult> results = new ArrayList<>();
      for (Object item : dataList) {
        if (!(item instanceof CdsData cdsData)) {
          throw new IllegalArgumentException(
              String.format(
                  "Cannot send notification for event '%s': each batch entry must be a CdsData"
                      + " object (got: %s)",
                  eventName, item != null ? item.getClass().getName() : "null"));
        }
        results.add(buildSingleNotification(eventName, event, cdsData));
      }
      return results;
    }

    if (eventDataObj instanceof CdsData eventData) {
      // Single mode: programmatic API emits a single CdsData directly
      return List.of(buildSingleNotification(eventName, event, eventData));
    }

    String errorMsg =
        String.format(
            "Cannot send notification for event '%s': event data must be a CdsData or List<CdsData>"
                + " (got: %s)",
            eventName, eventDataObj != null ? eventDataObj.getClass().getName() : "null");
    logger.error(errorMsg);
    throw new IllegalArgumentException(errorMsg);
  }

  /** Build a single notification from event metadata and payload data. */
  private NotificationBuildResult buildSingleNotification(
      String eventName, CdsEvent event, CdsData eventData) {

    String notificationTypeKey = event.getName();

    // Read priority annotation (can be enum, string, or CDS expression) - optional field
    String priority = extractPriority(event, eventData);

    // Validate and parse recipients (supports String and List<String>)
    List<Recipients> recipientsList = resolveRecipients(eventName, eventData);

    // Create notification object
    Notifications notification = Struct.create(Notifications.class);
    notification.setNotificationTypeKey(notificationTypeKey);
    notification.setNotificationTypeVersion("1");
    notification.setNotificationTemplateKey(notificationTypeKey);
    notification.setPriority(priority);
    notification.setRecipients(recipientsList);

    // Set navigation target from @Common.SemanticObject / @Common.SemanticObjectAction
    extractNavigationTarget(event, notification);

    // Add all fields from event data as properties
    List<NotificationProperties> properties = extractProperties(event, eventData);
    notification.setProperties(properties);

    logger.debug("Built notification with {} properties", properties.size());

    return new NotificationBuildResult(eventName, notification, event);
  }

  private String extractPriority(CdsEvent event, CdsData eventData) {
    var priorityAnno = event.findAnnotation("notification.priority");
    if (priorityAnno.isEmpty()) {
      return null;
    }

    Object priority = priorityAnno.get().getValue();

    // CqnValue expression: the CAP runtime represents CDS expressions as typed CqnValue AST nodes.
    // Examples: CASE WHEN year > 2025 THEN 'HIGH' ELSE 'LOW' END
    if (priority instanceof CqnValue cqnPriority) {
      return evaluateCqnPriority(cqnPriority, eventData);
    }

    // Enum map: {#=HIGH}
    if (priority instanceof Map<?, ?> map) {
      String resolved =
          map.values().stream().findFirst().map(Object::toString).orElse("MEDIUM").toUpperCase();
      return validatePriority(resolved);
    }

    // String literal: "HIGH"
    return validatePriority(priority.toString().toUpperCase());
  }

  /**
   * Evaluate a CDS expression for priority by delegating to the database.
   *
   * <p>Resolves all element references (event fields, {@code $now}, etc.) to literal values, then
   * executes {@code SELECT <expr> AS result FROM $DUMMY LIMIT 1} via the low-level {@link
   * com.sap.cds.CdsDataStore} to bypass CDS model entity resolution.
   */
  private String evaluateCqnPriority(CqnValue cqnValue, CdsData eventData) {
    try {
      // Collect event field names for ref resolution
      Set<String> fieldNames = eventData.keySet();

      // Resolve all refs: event fields + CDS session variables like $now
      // Also transforms containment tests (contains/startsWith/endsWith) into
      // simple true/false predicates when both args are resolved literals,
      // since the DataStore cannot render CqnContainmentTest nodes directly.
      CqnValue resolved =
          ExpressionVisitor.copy(
              cqnValue,
              new Modifier() {
                @Override
                public CqnValue ref(CqnElementRef ref) {
                  String name = ref.displayName();
                  // CDS session variable $now — must be manually resolved, runtime does NOT
                  // auto-resolve it
                  if ("$now".equalsIgnoreCase(name)) return CQL.val(Instant.now());
                  // Event fields
                  for (String field : fieldNames) {
                    if (field.equalsIgnoreCase(name)) {
                      Object val = eventData.get(field);
                      return val != null ? CQL.val(val) : ref;
                    }
                  }
                  return ref;
                }

                @Override
                public CqnPredicate containment(
                    CqnContainmentTest.Position position,
                    CqnValue value,
                    CqnValue term,
                    boolean caseInsensitive) {
                  return evaluateContainment(
                      position, value, term, caseInsensitive, runtime.getServiceCatalog());
                }
              });

      Result result = executeDummySelect(resolved, runtime.getServiceCatalog());

      String priority =
          result
              .first()
              .map(row -> row.get("result"))
              .map(v -> v.toString().toUpperCase())
              .orElse("NEUTRAL");
      logger.debug("Dynamic priority evaluated via DB to: {}", priority);
      return validatePriority(priority);
    } catch (Exception e) {
      logger.error(
          "Failed to evaluate dynamic priority expression: {}. Defaulting to NEUTRAL.",
          e.getMessage(),
          e);
      return "NEUTRAL";
    }
  }

  /**
   * Execute a scalar expression via the synthetic {@code $DUMMY} table:
   *
   * <pre>SELECT (&lt;resolvedExpression&gt;) AS result FROM $DUMMY LIMIT 1</pre>
   *
   * <p>This bypasses CDS model entity resolution and works for evaluating CQL expressions
   * (comparisons, arithmetic, string/date-time functions) against the database.
   *
   * @param resolvedExpression CQL expression with all references already resolved to literals
   * @param serviceCatalog service catalog to obtain the persistence service
   * @return query result containing a single row with column "result"
   */
  public static Result executeDummySelect(CqnValue resolvedExpression, ServiceCatalog serviceCatalog) {
    Value<?> expr = ExpressionBuilder.create(resolvedExpression).value();
    PersistenceService ps =
        serviceCatalog.getService(PersistenceService.class, PersistenceService.DEFAULT_NAME);
    if (!(ps instanceof JdbcPersistenceService db)) {
      throw new UnsupportedOperationException(
          "Expression evaluation requires JdbcPersistenceService but got: "
              + ps.getClass().getName());
    }
    return db.getCdsDataStore().execute(Select.from("$DUMMY").columns(expr.as("result")).limit(1));
  }

  /**
   * Evaluate a containment test ({@code contains}, {@code startsWith}, {@code endsWith}) by
   * resolving each argument to a plain string and performing the check in Java.
   *
   * @param position which containment mode to apply (START, END, or ANY for contains)
   * @param value the CQN expression representing the string to search within (left-hand side)
   * @param term the CQN expression representing the substring to search for (right-hand side)
   * @param caseInsensitive if true, normalize both sides to lowercase before comparison
   * @param serviceCatalog used to evaluate non-literal expressions via {@code $DUMMY} SELECT; may
   *     be null if both {@code value} and {@code term} are guaranteed to be literals
   * @return a tautology ({@code 1=1}) or contradiction ({@code 1=0}) predicate
   */
  public static CqnPredicate evaluateContainment(
      CqnContainmentTest.Position position,
      CqnValue value,
      CqnValue term,
      boolean caseInsensitive,
      ServiceCatalog serviceCatalog) {
    String v = resolveToString(value, serviceCatalog);
    String t = resolveToString(term, serviceCatalog);
    if (v == null || t == null) {
      return CQL.comparison(CQL.val(1), CqnComparisonPredicate.Operator.EQ, CQL.val(0));
    }
    String lhs = caseInsensitive ? v.toLowerCase(Locale.ROOT) : v;
    String rhs = caseInsensitive ? t.toLowerCase(Locale.ROOT) : t;
    boolean match =
        switch (position) {
          case START -> lhs.startsWith(rhs);
          case END -> lhs.endsWith(rhs);
          default -> lhs.contains(rhs);
        };
    return CQL.comparison(CQL.val(1), CqnComparisonPredicate.Operator.EQ, CQL.val(match ? 1 : 0));
  }

  /**
   * Resolve a CQN value to a plain string. If the value is already a literal, extract it directly.
   * Otherwise (e.g. {@code concat(...)}, {@code toupper(...)}) evaluate via a {@code $DUMMY}
   * SELECT. Returns {@code null} if the value cannot be resolved (e.g. DB returns NULL).
   */
  static String resolveToString(CqnValue val, ServiceCatalog serviceCatalog) {
    if (val instanceof CqnLiteral<?> lit) {
      Object v = lit.value();
      return v != null ? v.toString() : null;
    }
    // Complex expression (concat, toupper, etc.) — evaluate via DB
    Result r = executeDummySelect(val, serviceCatalog);
    return r.first().map(row -> row.get("result")).map(Object::toString).orElse(null);
  }

  /**
   * Validate that a priority value is one of the allowed values (LOW, NEUTRAL, MEDIUM, HIGH). Logs
   * a warning and falls back to NEUTRAL if the value is invalid.
   */
  private String validatePriority(String priority) {
    if (VALID_PRIORITIES.contains(priority)) {
      return priority;
    }
    logger.warn(
        "Invalid priority value '{}'. Must be one of {}. Defaulting to NEUTRAL.",
        priority,
        VALID_PRIORITIES);
    return "NEUTRAL";
  }

  /**
   * Extract @Common.SemanticObject and @Common.SemanticObjectAction annotations and map them to
   * NavigationTargetObject and NavigationTargetAction on the notification.
   */
  private void extractNavigationTarget(CdsEvent event, Notifications notification) {
    event
        .findAnnotation("Common.SemanticObject")
        .map(a -> (String) a.getValue())
        .ifPresent(
            value -> {
              notification.setNavigationTargetObject(value);
              logger.debug("Set NavigationTargetObject: {}", value);
            });

    event
        .findAnnotation("Common.SemanticObjectAction")
        .map(a -> (String) a.getValue())
        .ifPresent(
            value -> {
              notification.setNavigationTargetAction(value);
              logger.debug("Set NavigationTargetAction: {}", value);
            });
  }

  /**
   * Resolve recipients from event data. Supports the following formats:
   *
   * <ul>
   *   <li>String: single recipient (auto-detected as email or UUID)
   *   <li>List of Strings: multiple recipients (each auto-detected as email or UUID)
   * </ul>
   */
  private List<Recipients> resolveRecipients(String eventName, CdsData eventData) {
    Object recipientsObj = eventData.get("recipients");
    if (recipientsObj == null) {
      String errorMsg =
          String.format(
              "Cannot send notification for event '%s': 'recipients' field is mandatory but"
                  + " missing",
              eventName);
      logger.error(errorMsg);
      throw new IllegalArgumentException(errorMsg);
    }

    List<Recipients> recipientsList = new ArrayList<>();

    if (recipientsObj instanceof List<?> list) {
      recipientsList.addAll(
          list.stream()
              .map(String.class::cast)
              .map(NotificationBuilder::createRecipientFromId)
              .toList());
    } else if (recipientsObj instanceof String) {
      String recipientsStr = ((String) recipientsObj).trim();
      if (recipientsStr.isEmpty()) {
        throw new IllegalArgumentException(
            String.format(
                "Cannot send notification for event '%s': 'recipients' field is mandatory but"
                    + " empty",
                eventName));
      }
      recipientsList.add(createRecipientFromId(recipientsStr));
    } else {
      throw new IllegalArgumentException(
          String.format(
              "Cannot send notification for event '%s': unsupported recipients type: %s",
              eventName, recipientsObj.getClass().getName()));
    }

    if (recipientsList.isEmpty()) {
      throw new IllegalArgumentException(
          String.format(
              "Cannot send notification for event '%s': no valid recipients resolved", eventName));
    }

    logger.debug("Resolved {} recipient(s) for event '{}'", recipientsList.size(), eventName);
    return recipientsList;
  }

  // Auto-detects UUID vs email and maps to GlobalUserId or RecipientId accordingly
  @VisibleForTesting
  public static Recipients createRecipientFromId(String recipientId) {
    Recipients recipient = Struct.create(Recipients.class);
    if (isUUID(recipientId)) {
      recipient.setGlobalUserId(recipientId);
      logger.debug("Detected UUID recipient, mapped to GlobalUserId: {}", recipientId);
    } else if (isEmail(recipientId)) {
      recipient.setRecipientId(recipientId);
      logger.debug("Detected email recipient, mapped to RecipientId: {}", recipientId);
    } else {
      throw new IllegalArgumentException(
          String.format(
              "Unsupported recipient format: '%s'. Only email addresses and UUIDs are accepted.",
              recipientId));
    }
    return recipient;
  }

  @VisibleForTesting
  public static boolean isUUID(String value) {
    try {
      UUID.fromString(value);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  // Simplified RFC 5322 email pattern matching local-part@domain.tld where TLD is at least 2
  // letters
  private static final Pattern EMAIL_PATTERN =
      Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

  @VisibleForTesting
  public static boolean isEmail(String value) {
    return EMAIL_PATTERN.matcher(value).matches();
  }

  private List<NotificationProperties> extractProperties(CdsEvent event, CdsData eventData) {
    List<NotificationProperties> properties = new ArrayList<>();

    event
        .elements()
        .forEach(
            element -> {
              String fieldName = element.getName();
              // Skip recipients as it's already processed
              if (!fieldName.equals("recipients")) {
                Object value = eventData.get(fieldName);
                if (value != null) {
                  NotificationProperties prop = Struct.create(NotificationProperties.class);
                  prop.setKey(fieldName);
                  prop.setValue(value.toString());
                  properties.add(prop);
                  logger.debug("Added property: {} = {}", fieldName, value);
                }
              }
            });

    return properties;
  }

  /** Result object containing the built notification and related metadata. */
  public record NotificationBuildResult(
      String eventName, Notifications notification, CdsEvent event) {}
}
