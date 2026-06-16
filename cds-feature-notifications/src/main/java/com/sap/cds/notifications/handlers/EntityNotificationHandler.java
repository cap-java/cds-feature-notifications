/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package com.sap.cds.notifications.handlers;

import com.sap.cds.CdsData;
import com.sap.cds.Result;
import com.sap.cds.Struct;
import com.sap.cds.notifications.builders.NotificationBuilder;
import com.sap.cds.ql.CQL;
import com.sap.cds.ql.cqn.CqnContainmentTest;
import com.sap.cds.ql.cqn.CqnElementRef;
import com.sap.cds.ql.cqn.CqnPredicate;
import com.sap.cds.ql.cqn.CqnValue;
import com.sap.cds.ql.cqn.Modifier;
import com.sap.cds.ql.impl.ExpressionVisitor;
import com.sap.cds.reflect.CdsEntity;
import com.sap.cds.reflect.CdsEvent;
import com.sap.cds.services.EventContext;
import com.sap.cds.services.Service;
import com.sap.cds.services.cds.ApplicationService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.After;
import com.sap.cds.services.handler.annotations.ServiceName;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler that intercepts CRUD events on entities annotated with {@code @notifications} and
 * automatically emits notification events on the owning service. The emitted events are then
 * handled by {@link ProductionHandler} (sends to ANS) or {@link LocalHandler} (logs to console)
 * depending on the runtime mode.
 *
 * <p>Supports CRUD operations ({@code CREATE}, {@code READ}, {@code UPDATE}, {@code DELETE}) as
 * well as bound actions and functions. The {@code on} field in the annotation config specifies
 * which events trigger the notification.
 *
 * <p>Example (CRUD):
 *
 * <pre>
 * &#64;notifications : [{
 *   type       : 'BookCreated',
 *   on         : ['CREATE'],
 *   recipients : $self.createdBy,
 *   where      : ($self.stock &gt; 50),
 *   parameters : {
 *     title : $self.title,
 *     stock : $self.stock
 *   }
 * }]
 * entity Books as projection on my.Books;
 * </pre>
 *
 * <p>Example (bound action):
 *
 * <pre>
 * &#64;notifications : [{
 *   type       : 'OrderApproved',
 *   on         : ['approve'],
 *   recipients : $self.createdBy
 * }]
 * entity Orders as projection on my.Orders {
 *   action approve() returns Boolean;
 * }
 * </pre>
 *
 * <p>If {@code parameters} is omitted, all entity fields are passed as notification properties. If
 * specified, only the declared mappings are used, allowing field renaming (e.g., {@code bookTitle :
 * $self.title}).
 */
@ServiceName(value = "*", type = ApplicationService.class)
public class EntityNotificationHandler implements EventHandler {

  private static final Logger logger = LoggerFactory.getLogger(EntityNotificationHandler.class);

  @After(event = "*")
  public void onEntityChange(EventContext context) {
    CdsEntity entity = context.getTarget();
    if (entity == null) {
      return;
    }

    // Check for @notifications annotation on the entity
    var annoOpt = entity.findAnnotation("notifications");
    if (annoOpt.isEmpty()) {
      return;
    }

    Object annoValue = annoOpt.get().getValue();
    String currentEvent = context.getEvent();

    logger.debug(
        "Entity '{}' has @notifications, processing event '{}'",
        entity.getQualifiedName(),
        currentEvent);

    // @notifications is an array of notification configs
    if (!(annoValue instanceof List<?> notifications)) {
      logger.error("@notifications on '{}' must be an array", entity.getQualifiedName());
      return;
    }

    // Get entity data from context result
    List<Map<String, Object>> entityRows = extractEntityData(context);

    // Get the service to emit notification events on
    // (resolved from the CDS event's owning service, not the current CRUD service)

    for (Object item : notifications) {
      if (item instanceof Map<?, ?> notification) {
        processNotificationEntry(notification, currentEvent, entity, entityRows, context);
      }
    }
  }

  /**
   * Extract entity data rows from the event context. After CREATE/UPDATE, the result is a {@link
   * Result} containing affected rows. After bound actions/functions, the result may be a single
   * entity (Map/CdsData).
   */
  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> extractEntityData(EventContext context) {
    List<Map<String, Object>> rows = new ArrayList<>();
    Object result = context.get("result");
    if (result instanceof Result r) {
      // CRUD operations return Result (iterable rows)
      r.forEach(rows::add);
    } else if (result instanceof Map<?, ?> map) {
      // Bound actions/functions may return a single entity (CdsData/Map)
      rows.add((Map<String, Object>) map);
    }
    return rows;
  }

  /** Process a single @notifications annotation entry against the current event. */
  private void processNotificationEntry(
      Map<?, ?> notification,
      String currentEvent,
      CdsEntity entity,
      List<Map<String, Object>> entityRows,
      EventContext context) {

    // Check 'on' — which events trigger this notification
    if (!(notification.get("on") instanceof List<?> onList)) {
      logger.error(
          "@notifications on '{}' is missing or has invalid 'on' field, must be an array of event"
              + " names",
          entity.getQualifiedName());
      return;
    }
    if (onList.stream().noneMatch(t -> t instanceof String s && s.equalsIgnoreCase(currentEvent))) {
      return;
    }

    // Get notification type (must reference a CDS event with @notification annotations)
    if (!(notification.get("type") instanceof String notificationType)
        || notificationType.isBlank()) {
      logger.error("@notifications on '{}' is missing 'type'", entity.getQualifiedName());
      return;
    }

    Object recipients = notification.get("recipients");

    // Optional: where condition to restrict when notification is sent
    Object whereCondition = notification.get("where");

    // Optional: explicit parameter mapping (entity field → notification property)
    Map<?, ?> properties = notification.get("parameters") instanceof Map<?, ?> m ? m : null;

    logger.debug(
        "Entity notification triggered: type='{}', event='{}', entity='{}'",
        notificationType,
        currentEvent,
        entity.getQualifiedName());

    // Collect event data for all matching rows, then emit as a single batch
    List<CdsData> batchData = new ArrayList<>();

    if (entityRows.isEmpty()) {
      CdsData data = buildEventData(notificationType, recipients, properties, null);
      // buildEventData returns null when recipients cannot be resolved (e.g., $self.createdBy with
      // no entity data)
      if (data != null) {
        batchData.add(data);
      }
    } else {
      for (Map<String, Object> entityObject : entityRows) {
        try {
          // Evaluate optional where condition — skip entity if not met
          if (whereCondition != null
              && !evaluateWhereCondition(whereCondition, entityObject, context)) {
            logger.debug(
                "Where condition not met for entity data, skipping notification type '{}'",
                notificationType);
            continue;
          }
          CdsData data = buildEventData(notificationType, recipients, properties, entityObject);
          // null when recipients unresolvable for this row — skip silently, already logged in
          // buildEventData
          if (data != null) {
            batchData.add(data);
          }
        } catch (IllegalArgumentException e) {
          throw e; // Developer mistake (invalid where condition) — fail loudly
        } catch (Exception e) {
          logger.warn(
              "Failed to process entity row for notification type '{}': {}",
              notificationType,
              e.getMessage(),
              e);
        }
      }
    }

    if (batchData.isEmpty()) {
      logger.debug("No notification data to emit for type '{}'", notificationType);
      return;
    }

    // Emit as a single batch event
    emitBatchNotification(notificationType, batchData, context);
  }

  /**
   * Build event data map for a single entity row. Returns null if recipients cannot be resolved.
   *
   * @param properties if non-null, only the declared mappings are used; otherwise all entity fields
   *     are passed through
   */
  private CdsData buildEventData(
      String notificationType,
      Object recipients,
      Map<?, ?> properties,
      Map<String, Object> entityData) {
    // Resolve recipients from annotation expression (supports single and array)
    Object resolvedRecipients = resolveRecipients(recipients, entityData);
    if (resolvedRecipients == null) {
      logger.warn("No recipients resolved for notification type '{}', skipping", notificationType);
      return null;
    }

    // Build event data: recipients + entity fields (or explicit parameter mapping)
    Map<String, Object> eventData = new HashMap<>();
    eventData.put("recipients", resolvedRecipients);

    if (properties != null && entityData != null) {
      // Explicit mapping: propertyName → $self.fieldName expression
      properties.forEach(
          (paramName, expression) -> {
            if (paramName instanceof String key) {
              Object resolved = resolveExpression(expression, entityData);
              if (resolved != null) {
                eventData.put(key, resolved);
              }
            }
          });
    } else if (entityData != null) {
      // No parameters config — pass all entity fields through
      entityData.forEach(
          (key, value) -> {
            if (value != null) {
              eventData.putIfAbsent(key, value);
            }
          });
    }

    return Struct.access(eventData).as(CdsData.class);
  }

  /**
   * Emit collected notification data as a single CDS event. The payload is always a {@code
   * List<CdsData>}, even for single-entry batches. Each {@link CdsData} element contains the
   * resolved recipients and properties for one entity row.
   *
   * <p>The event is looked up in the CDS model to determine its owning service, then emitted on
   * that service. Downstream {@code @On} handlers ({@link ProductionHandler} or {@link
   * LocalHandler}) pick up the event and delegate to {@link NotificationBuilder}, which iterates
   * the list and builds one ANS notification per entry.
   */
  private void emitBatchNotification(
      String notificationType, List<CdsData> batchData, EventContext context) {
    try {
      // Find the CDS event to determine its owning service
      var eventOpt =
          context.getModel().events().filter(e -> e.getName().equals(notificationType)).findFirst();

      if (eventOpt.isEmpty()) {
        logger.error(
            "No CDS event '{}' found in model, check that it is defined as a CDS event",
            notificationType);
        return;
      }

      CdsEvent cdsEvent = eventOpt.get();
      String qualifiedName = cdsEvent.getQualifiedName();
      String serviceName = qualifiedName.substring(0, qualifiedName.lastIndexOf('.'));

      // Get the owning service from service catalog
      Service service =
          context.getServiceCatalog().getService(ApplicationService.class, serviceName);

      EventContext notificationCtx = EventContext.create(notificationType, null);
      notificationCtx.put("data", batchData);
      service.emit(notificationCtx);
      logger.debug(
          "Emitted {} notification(s) for event '{}' on service '{}'",
          batchData.size(),
          notificationType,
          serviceName);
    } catch (Exception e) {
      logger.error(
          "Failed to emit notification for type '{}': {}", notificationType, e.getMessage(), e);
    }
  }

  /**
   * Resolve recipients from annotation config. Supports:
   *
   * <ul>
   *   <li>Single expression: {@code $self.createdBy} → resolved string
   *   <li>Static string: {@code 'admin@sap.com'} → passed through as-is
   *   <li>Array: {@code [$self.createdBy, $self.modifiedBy]} → List of resolved strings
   * </ul>
   *
   * @return String (single recipient), List&lt;String&gt; (multiple), or null
   */
  private Object resolveRecipients(Object recipientsConfig, Map<String, Object> entityData) {
    // Array: [$self.createdBy, $self.modifiedBy, 'static@sap.com']
    if (recipientsConfig instanceof List<?> list) {
      List<String> resolved = new ArrayList<>();
      for (Object item : list) {
        Object val = resolveExpression(item, entityData);
        if (val != null) {
          resolved.add(val.toString());
        }
      }
      return resolved.isEmpty() ? null : resolved;
    }

    // Single expression or static string
    Object resolved = resolveExpression(recipientsConfig, entityData);
    return resolved != null ? resolved.toString() : null;
  }

  /**
   * Evaluate a CDS where condition by delegating to the database.
   *
   * <p>Resolves all {@code $self} references to literal values from entity data, then executes
   * {@code SELECT (<expr>) AS result FROM <any-entity> LIMIT 1} to evaluate the boolean expression.
   *
   * <p>If the where condition is a simple field reference ({@code {"=": "$self.isActive"}}), the
   * field value is checked for truthiness directly without DB evaluation.
   *
   * @return true if condition is met, false otherwise
   * @throws IllegalArgumentException if the where condition is not a valid boolean expression
   * @throws IllegalStateException if the where condition cannot be evaluated at runtime
   */
  private boolean evaluateWhereCondition(
      Object whereCondition, Map<String, Object> entityData, EventContext context) {

    // Complex CDS expression: CqnValue (e.g., $self.stock > 50)
    if (!(whereCondition instanceof CqnValue cqnValue)) {
      throw new IllegalArgumentException(
          "Where condition must be a boolean expression (e.g., '($self.stock > 50)'), got: "
              + whereCondition.getClass().getName());
    }

    try {
      Set<String> fieldNames = entityData.keySet();

      // Resolve all $self refs to literal values from entity data
      CqnValue resolved =
          ExpressionVisitor.copy(
              cqnValue,
              new Modifier() {
                @Override
                public CqnValue ref(CqnElementRef ref) {
                  String name = ref.displayName();
                  if ("$now".equalsIgnoreCase(name)) return CQL.val(Instant.now());
                  if (name.startsWith("$self.")) {
                    name = name.substring("$self.".length());
                  }
                  for (String field : fieldNames) {
                    if (field.equalsIgnoreCase(name)) {
                      Object val = entityData.get(field);
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
                  return NotificationBuilder.evaluateContainment(
                      position, value, term, caseInsensitive, context.getServiceCatalog());
                }
              });

      Result result = NotificationBuilder.executeDummySelect(resolved, context.getServiceCatalog());

      boolean conditionMet =
          result
              .first()
              .map(
                  row -> {
                    Object val = row.get("result");
                    if (val instanceof Boolean b) return b;
                    if (val instanceof Number n) return n.intValue() != 0;
                    return val != null
                        && !"0".equals(val.toString())
                        && !"false".equalsIgnoreCase(val.toString());
                  })
              .orElse(false);

      logger.debug("Where condition evaluated to: {} for notification type", conditionMet);
      return conditionMet;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to evaluate where condition: " + e.getMessage(), e);
    }
  }

  /**
   * Resolve a CDS annotation expression to a value from entity data. Used for both {@code
   * recipients} and {@code parameters} expressions. Supports:
   *
   * <ul>
   *   <li>CDS expression map: {"=": "$self.fieldName"} → entity field lookup
   *   <li>Static string/value: passed through as-is
   * </ul>
   */
  private Object resolveExpression(Object expression, Map<String, Object> entityData) {
    if (expression instanceof Map<?, ?> map && map.containsKey("=")) {
      String expr = map.get("=").toString();
      String fieldName = expr.startsWith("$self.") ? expr.substring("$self.".length()) : expr;
      return entityData != null ? entityData.get(fieldName) : null;
    } else if (expression instanceof String s) {
      return s;
    }
    logger.warn(
        "Unsupported expression type: {}",
        expression != null ? expression.getClass().getName() : "null");
    return null;
  }
}
