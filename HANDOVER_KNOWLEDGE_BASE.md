# Handover Knowledge Base — cds-feature-notifications

## Table of Contents

1. [What This Plugin Is](#1-what-this-plugin-is)
2. [Repository Layout](#2-repository-layout)
3. [Architecture Overview](#3-architecture-overview)
   - [Runtime Mode Detection](#31-runtime-mode-detection)
   - [The Three ANS Remote Services](#32-the-three-ans-remote-services)
   - [Handler Pipeline](#33-handler-pipeline)
   - [Two Ways to Emit Notifications](#34-two-ways-to-emit-notifications)
   - [Handler Registration Decision Tree](#35-handler-registration-decision-tree)
4. [Key Architectural Decisions](#4-key-architectural-decisions)
   - [ADR-1: Wildcard Handler Registration](#adr-1-wildcard-handler-registration)
   - [ADR-2: Dynamic CDS Expressions via $DUMMY SELECT](#adr-2-dynamic-cds-expressions-via-dummy-select)
   - [ADR-3: Provision Only App-Translated Locales to ANS](#adr-3-provision-only-app-translated-locales-to-ans)
   - [ADR-4: Destination Precedence — Binding Wins over BTP ANS Destination](#adr-4-destination-precedence--binding-wins-over-btp-ans-destination)
   - [ADR-5: Custom OAuth2 Property Supplier for ANS Binding](#adr-5-custom-oauth2-property-supplier-for-ans-binding)
   - [ADR-6: Auto-Provisioning at Application Start via ApplicationLifecycleService](#adr-6-auto-provisioning-at-application-start-via-applicationlifecycleservice)
   - [ADR-7: Outbox Wrapping of NotificationProviderService](#adr-7-outbox-wrapping-of-notificationproviderservice)
   - [ADR-8: CDS Event `key` Elements as ANS Target Parameters](#adr-8-cds-event-key-elements-as-ans-target-parameters)
5. [Known Constraints and Non-Obvious Rules](#5-known-constraints-and-non-obvious-rules)
6. [Module Walkthrough](#6-module-walkthrough)
7. [Local Mode vs Production Mode](#7-local-mode-vs-production-mode)
8. [DB Storage and Cooldown](#8-db-storage-and-cooldown)
9. [Adding a new ANS remote service endpoint to the plugin](#9-adding-a-new-ans-remote-service-endpoint-to-the-plugin)
11. [Testing Strategy and Coverage](#11-testing-strategy-and-coverage)

---

## 1. What This Plugin Is

A **CAP Java plugin** that integrates SAP Alert Notification service (ANS) into any CAP Java application.
Developers annotate CDS events with `@notification` and entities with `@notifications`. The plugin takes over from there:

- **Provisions** notification types and templates to ANS automatically at startup.
- **Routes** emitted CDS events to ANS (production) or the console (local/dev).
- Works as a **zero-code addition** — consuming apps only need the Maven dependency and CDS annotations.

The plugin is an open-source Maven artifact published to **Maven Central**. 
---

## 2. Repository Layout

```
cds-feature-notifications/          ← repo root
  cds-feature-notifications/        ← main Maven module (the published artifact)
    src/main/java/com/sap/cds/notifications/
      NotificationServiceConfiguration.java   ← CdsRuntimeConfiguration entry point; registers all handlers and remote services
      [AlertNotificationOAuth2PropertySupplier.java](cds-feature-notifications/src/main/java/com/sap/cds/notifications/AlertNotificationOAuth2PropertySupplier.java)  ← custom OAuth2 supplier (see [ADR-5](#adr-5-custom-oauth2-property-supplier-for-ans-binding))
      assemblers/
        NotificationAssembler.java           ← builds ANS notification payload
        NotificationTypeAssembler.java       ← builds ANS NotificationType payload
        NotificationTemplateAssembler.java   ← builds ANS NotificationTemplate payload
      handlers/
        ProductionHandler.java               ← sends to ANS via outboxed NotificationProviderService
        LocalHandler.java                    ← logs notification to console (local mode)
        EntityNotificationHandler.java       ← intercepts @notifications entity annotations; emits CDS batch events
        NotificationTypeAutoProvisionerHandler.java      ← provisions notification types to ANS at startup (production mode)
        NotificationTemplateAutoProvisionerHandler.java  ← provisions standalone templates to ANS at startup (production mode)
        LocalNotificationTypeAutoProvisionerHandler.java      ← logs type provisioning to console (local mode)
        LocalNotificationTemplateAutoProvisionerHandler.java  ← logs template provisioning to console (local mode)
        StoreNotificationsHandler.java       ← DB storage (production)
        StoreNotificationsLocalHandler.java  ← DB storage (local mode)
      helpers/
        I18nHelper.java                      ← i18n resolution + HTML loading
        CooldownChecker.java                 ← cooldown enforcement
        NotificationStorageHelper.java       ← shared DB write logic
    src/main/resources/cds/com.sap.cds/cds-feature-notifications/
      index.cds                              ← re-exports all CDS models
      NotificationProviderService.cds        ← remote service model for notifications
      NotificationTypeProviderService.cds    ← remote service model for notification types
      NotificationTemplateProviderService.cds← remote service model for notification templates
      NotificationStorage.cds               ← DB entities for storing sent notifications
  sample-app/                               ← reference implementation (Bookshop)
  integration-tests/                        ← end-to-end tests with real CAP runtime
  coverage-report/                          ← aggregated JaCoCo report
```

### Entry point

The plugin is discovered by the CAP Java runtime via `META-INF/services/com.sap.cds.services.runtime.CdsRuntimeConfiguration` pointing to `NotificationServiceConfiguration`. Handler registration, remote service configuration, and mode detection all begin there.

---

## 3. Architecture Overview

### 3.1 Runtime Mode Detection

At startup, `NotificationServiceConfiguration.eventHandlers()` checks two conditions:

| Condition | Result |
|---|---|
| `alert-notification` service binding is present in the environment | Production mode |
| `cds.environment.production.enabled: true` in `application.yaml` | Production mode (use when there is no service binding but production handlers are needed — e.g. local dev with [`DestinationConfiguration`](#hybrid-testing--connecting-a-local-app-to-a-real-ans-instance), or integration tests) |
| Neither | Local mode (console output only) |

### 3.2 The Three ANS Remote Services

ANS exposes three separate OData v2 services, all reachable through the same `SAP_Notifications` HTTP destination but under different paths:

| CDS Remote Service | ANS OData Service | Path suffix | Purpose |
|---|---|---|---|
| `NotificationProviderService` | `Notification.svc` | `/v2` | Sending notifications |
| `NotificationTypeProviderService` | `NotificationType.svc` | `/v2` | Managing notification types |
| `NotificationTemplateProviderService` | `NotificationTemplate.svc` | `/odatav2` | Managing standalone templates |

> ⚠️ **Check before modifying:** The actual ANS API endpoints may have changed — the suffixes above reflect the code at handover time but should be verified against the ANS Notification Provider API reference. If the API paths change, update `NotificationServiceConfiguration.environment()` (`setSuffix` calls) and re-verify all three services.

All three are registered programmatically in `NotificationServiceConfiguration.environment()` via `CdsProperties.Remote`. This is why the consuming app's `pom.xml` must exclude these service models from code generation — otherwise the CDS Maven plugin would generate Java stubs for them, conflicting with the plugin's own generated classes.

### 3.3 Handler Pipeline

**Path for a programmatically emitted notification:**

```
user code: notificationService.emit(ctx)
  └─→ ProductionHandler.postNotifications()       [or LocalHandler in local mode]
        └─→ NotificationAssembler.buildNotifications()
              └─→ resolves recipients, priority, properties, target params
        └─→ CooldownChecker.filterCooldownRecipients()   [if cooldown configured]
        └─→ NotificationProviderService.run(Insert...)   [OData call to ANS, via outbox]
              └─→ StoreNotificationsHandler.storeNotifications()  [if storeNotifications=true]
```

**Path for a `@notifications`-annotated entity:**

```
CAP runtime: entity CRUD event fires
  └─→ EntityNotificationHandler.onEntityChange()   [@After on all ApplicationService events]
        └─→ checks entity has @notifications annotation
        └─→ evaluates where condition via $DUMMY SELECT
        └─→ resolves $self.field refs from result rows
        └─→ emits batch EventContext on the notification event's owning service
              └─→ ProductionHandler / LocalHandler (same path as above)
```

### 3.4 Two Ways to Emit Notifications

Both approaches converge at `ProductionHandler` / `LocalHandler`. The choice of approach is the consuming app developer's decision, not the plugin's.

#### Option A: Programmatic emit

The consuming app injects the **generated service interface** for whichever CDS service defines the `@notification`-annotated event and calls `emit()` explicitly. (`NotificationService` in the example below is the name used in the sample-app — your service can be named anything, as long as it defines the `@notification`-annotated event, e.g. `LowStockAlert`.)

```java
@Autowired
private NotificationService notificationService;   // generated from CDS

LowStockAlert data = LowStockAlert.create();
data.setRecipients("user@example.com");
data.setBookTitle("Wuthering Heights");
data.setStock(12);

LowStockAlertContext ctx = LowStockAlertContext.create();
ctx.setData(data);
notificationService.emit(ctx);
```

For **batch emit** (multiple notifications of the same type in one call):

```java
EventContext batchCtx = EventContext.create("LowStockAlert", null);
batchCtx.put("data", List.of(alert1, alert2, alert3));
notificationService.emit(batchCtx);
```

The generic `EventContext` is required here — the generated typed context (e.g. `LowStockAlertContext`) only accepts a single `CdsData` via `setData()` and has no built-in support for a list.

The plugin detects the `data` field: if it is a `List<CdsData>`, it processes each entry separately (batch mode); if it is a single `CdsData`, it processes it as a single notification.

**Constraint:** Batch is limited to a single notification type per `emit()` call — all entries in the list must belong to the same CDS event. To send notifications of different types, call `emit()` separately for each.

---

#### Option B: Declarative via `@notifications` entity annotation

The consuming app annotates a CDS entity. The plugin intercepts the specified CRUD events (or bound actions/functions) automatically — no `emit()` call is needed in the application code.

```cds
service CatalogService {
  @notifications : [{
    type       : 'LowStockAlert',
    on         : ['CREATE', 'UPDATE'],
    recipients : $self.createdBy,
    where      : ($self.stock < 100),
    parameters : {
      bookTitle : $self.title,
      stock     : $self.stock
    }
  }]
  entity Books as projection on my.Books;
}
```

`EntityNotificationHandler` intercepts these events `@After`, evaluates the `where` condition per result row, resolves `$self.field` references, and **automatically emits a batch event** — all matching rows for the same notification type are collected into a single `List<CdsData>` and emitted in one call. From that point, the same `ProductionHandler` / `LocalHandler` path applies.

**When to use which:**
- Use **Option A** when the notification logic is complex (multiple conditions, cross-entity data, external lookups) or when you need fine-grained control over when it fires.
- Use **Option B** when the notification maps cleanly to a single entity's CRUD lifecycle and the `where` condition and field mappings can be expressed in CDS.

---

### 3.5 Handler Registration Decision Tree

`NotificationServiceConfiguration.eventHandlers()` is the single place where every handler gets wired up. Which handlers are registered depends on three boolean flags evaluated at startup:

| Flag | Source |
|---|---|
| `ansBindingPresent` | Whether an `alert-notification` service binding exists in the environment |
| `productionEnabled` | Whether `cds.environment.production.enabled: true` is set in `application.yaml` |
| `storeNotifications` | Whether `cds.notifications.storeNotifications: true` is set |

The registration logic, as a decision tree:

```
storeNotifications?
  YES → obtain PersistenceService
        productionEnabled OR ansBindingPresent?
          YES → register StoreNotificationsHandler       (listens @After on NotificationProviderService)
          NO  → register StoreNotificationsLocalHandler  (listens @After on ApplicationService events)
  NO  → db = null  (CooldownChecker gets null → cooldown silently ignored)

CooldownChecker(db)   ← always created; if db==null it no-ops

productionEnabled OR ansBindingPresent?
  YES → register ProductionHandler(outboxedSvc, runtime, cooldownChecker)
        register NotificationTemplateAutoProvisionerHandler
        register NotificationTypeAutoProvisionerHandler
  NO  → register LocalHandler(runtime, cooldownChecker)
        register LocalNotificationTemplateAutoProvisionerHandler
        register LocalNotificationTypeAutoProvisionerHandler

ALWAYS → register EntityNotificationHandler   (mode-agnostic; just emits CDS events)
```

**Key observations:**

- `CooldownChecker` is always instantiated and always passed to `ProductionHandler` / `LocalHandler`. It only becomes active when `db != null`, which happens when `storeNotifications: true`. This means you can add `cooldown` to an annotation and later enable storage — the handler wiring does not change, the checker just starts working once it has a DB reference.

- `EntityNotificationHandler` is registered unconditionally, regardless of mode. It is not mode-aware itself — it just emits CDS events, which are then picked up by whichever of `ProductionHandler` or `LocalHandler` is registered.

- `StoreNotificationsHandler` vs `StoreNotificationsLocalHandler` follow the same production/local split as the main handlers, but for a different reason: in production, storage happens after the ANS response (the ANS-assigned ID is captured from the OData result); in local mode, there is no OData call, so storage reads the notification from the `EventContext` blackboard set by `LocalHandler` (see [Section 5](#storenotificationslocalhandler-communication-pattern)).

---

## 4. Key Architectural Decisions

### ADR-1: Wildcard Handler Registration

**Decision:** Both `ProductionHandler` and `EntityNotificationHandler` (and their local-mode counterparts) register with `@ServiceName(value = "*", type = ApplicationService.class)`, meaning they intercept every event on every `ApplicationService` in the application.

**Why:** CAP does not provide a mechanism at plugin init time to filter handler registration to only services/events that carry `@notification` or `@notifications` annotations. There is no "register only on these annotated events" API. So the handlers must attach broadly and perform the annotation check themselves at the top of the handler method — returning immediately if no relevant annotation is found.

**Consequence:** Every event fired on every service goes through a quick annotation check in `NotificationAssembler.buildNotifications()` (called by `ProductionHandler` / `LocalHandler`) and `EntityNotificationHandler.onEntityChange()`. These checks are cheap (CDS model lookup + annotation presence test) but they do execute on every single service call in the application. There is no known performance issue with this.

---

### ADR-2: Dynamic CDS Expressions via $DUMMY SELECT

**Decision:** CDS expressions in `@notification.priority` (e.g. `(year < 2025 ? 'HIGH' : 'LOW')`) and `where` conditions in `@notifications` (e.g. `($self.stock > 50)`) are evaluated by delegating to the database using a synthetic `SELECT <expr> AS result FROM $DUMMY LIMIT 1`.

**Why:** The alternative — implementing a full CQL expression evaluator inside the plugin — would require building and maintaining a significant amount of expression-parsing and evaluation code, including arithmetic, comparison operators, string functions, date/time functions, etc. Delegating to the database (which already knows how to evaluate CQL) is far simpler and keeps the plugin code minimal.

**Implementation note:** The CAP runtime parses CDS annotation expressions (e.g. `(contains(bookTitle, 'Heights') ? 'HIGH' : 'MEDIUM')`) into `CqnValue` AST nodes when the reflection API is queried. The plugin cannot pass these AST nodes directly to the database — they still contain unresolved element references that point to event/entity field names. The evaluation happens in three steps:

1. **Ref resolution** (`NotificationAssembler.evaluateCqnPriority()`, `EntityNotificationHandler.evaluateWhereCondition()`)**:** `ExpressionVisitor.copy()` traverses the AST with a custom `Modifier`. Every `CqnElementRef` (field reference like `bookTitle`) is replaced with its literal value from the event data or entity row. For `where` conditions, `$self.` prefix is stripped from references before lookup (e.g. `$self.stock` → `stock`). Currently only `$now` is resolved as a session variable (manually, to `Instant.now()`) — other CDS session variables are not resolved in the current implementation.
2. **Containment pre-evaluation:** `contains()`, `startsWith()`, `endsWith()` are represented as `CqnContainmentTest` nodes, which the low-level `CdsDataStore` cannot render directly. The plugin resolves both sides of the test to strings in Java and replaces the node with either a tautology (`1=1`) or contradiction (`1=0`).
3. **DB evaluation:** The fully-resolved expression is executed as `SELECT (<expr>) AS result FROM $DUMMY LIMIT 1` via `JdbcPersistenceService.getCdsDataStore()`. This bypasses CDS model entity resolution (which would fail on `$DUMMY`) by going directly to the low-level JDBC datastore.

**Risk:** Both `$DUMMY` and `com.sap.cds.ql.impl.ExpressionVisitor` are CAP internals — if either changes in a future CAP version, the entire expression evaluation path breaks. Only portable CQL functions that CAP translates to native SQL (rather than emulates) are guaranteed to work with the `$DUMMY SELECT` approach. Date/time functions (`days_between`, `months_between`, etc.) require CAP Java April 2026 or later. Verify supported expressions against the official CAP Java documentation — see [Standard Functions](https://cap.cloud.sap/docs/guides/databases/cap-level-dbs#standard-functions).

---

### ADR-3: Provision Only App-Translated Locales to ANS

**Decision:** Only locales for which the app has explicitly defined notification translations in its own `srv/_i18n/i18n_<lang>.properties` files are provisioned to ANS — not all available locales.

**Background:** The CDS compiler compiles `_i18n/i18n*.properties` files (located next to `.cds` source files) into a single [`edmx/_i18n/i18n.json`](sample-app/srv/src/main/resources/edmx/_i18n/i18n.json) file at build time. `EdmxI18nProvider` reads from this compiled file. When the app uses `@sap/cds/common` (which almost every CAP app does), CDS merges the common model's own `.properties` files into `i18n.json` as well — and `@sap/cds/common` ships translations for its framework labels (e.g. "Created By", "Modified At") in ~37 languages. As a result, `EdmxI18nProvider.getLocales()` returns ~37 locales. For locales where the app has not created a `srv/_i18n/i18n_<lang>.properties` file (e.g. Danish in the sample-app), `EdmxI18nProvider.getTexts(locale)` returns only the `@sap/cds/common` framework entries — and resolving `@notification.template.title` for that locale falls back to the English value.

For a locale that has no app-specific notification translation, `EdmxI18nProvider.getTexts(locale)` falls back to the English value. So resolving `@notification.template.title` for German would return the same English string. Without filtering, the plugin would provision ~37 ANS notification templates, all containing identical English text but tagged with different language codes.

**How it works:** `I18nHelper.getAvailableLocalesForEvent()` resolves `@notification.template.title` for each locale and compares it against the English value. `title` was chosen as the comparison field because it is a mandatory field in `@notification.template` — guaranteed to be present on any valid notification event (any mandatory field would have worked, but `title` is the natural representative). A locale is included only if its title differs from English AND contains no unresolved `{i18n>KEY}` placeholders. English is always included.

**Known edge case:** If a legitimately-translated notification title happens to be identical to the English title in some language, that language will be excluded from provisioning. This is an accepted trade-off — sending 37 broken templates to ANS is worse than omitting one valid but title-identical translation.

**How ANS determines the recipient's language for email delivery:**

The plugin provisions one template translation per locale defined in the app's i18n files. When ANS delivers a notification, the language selection works differently depending on the channel:

- **Email:** ANS resolves the recipient's language preference from SAP Cloud Identity Services (IAS) via the `Identity_Authentication_Connectivity_IDS` destination. If this destination is missing or returns a 401 error (invalid credentials), ANS cannot resolve the language and **falls back to English**. This is an ANS-side behavior — the plugin has no control over it.
- **SAP Build Work Zone (web/in-app):** IAS is not used. ANS uses the recipient's Work Zone language setting instead.

The `Identity_Authentication_Connectivity_IDS` destination must be configured in the BTP subaccount — it is not part of the plugin itself. The destination uses **BasicAuthentication** with the IAS technical user's `Client ID` as username and `Client Secret` as password (not just the Client ID alone).

- Language can also be **enforced at the ANS provider API level** via the `Language` field on the `Notifications` payload — this overrides the recipient's own language preference. The plugin does not currently set this field; ANS falls back to recipient-based resolution via IAS. If language enforcement is needed in the future, `NotificationAssembler.buildSingleNotification()` is the method to extend.

For the full destination setup guide, see the [Identity Authentication Destination section in the README](../README.md#identity-authentication-destination-language-resolution) or the [SAP Identity Directory Connectivity docs](https://help.sap.com/docs/task-center/sap-task-center/identity-directory-connectivity?q=destination).

---

### ADR-4: Destination Precedence — Binding Wins over BTP ANS Destination

There are two ways to connect the plugin to an ANS instance:
1. **Service binding** — bind an `alert-notification` service instance to the app (via `cf bind-service` or MTA). The plugin detects the binding and converts it to a `SAP_Notifications` HTTP destination at runtime.
2. **BTP ANS Destination** — manually create a `SAP_Notifications` destination in the BTP Destination Service (e.g. via Work Zone or BTP Cockpit) pointing to an ANS instance.

**Decision:** When both are present simultaneously, the binding wins.

**Why `prepend`:** The SAP Cloud SDK registers the BTP Destination Service loader as part of its own initialization — before our plugin runs. Our plugin calls `DestinationAccessor.prependDestinationLoader()` during the `environment()` phase (in `NotificationServiceConfiguration.environment()`), which inserts the binding-derived `DefaultDestinationLoader` at position 0 (the front of the chain), before the already-registered BTP Destination Service loader. When the CAP OData remote service resolves `SAP_Notifications`, it queries loaders in order and returns the first match. Because our loader is at position 0, the binding-derived destination is returned first — the BTP Destination Service is never queried. If `append` had been used instead, our loader would go to the end and the BTP destination would win.

---

### ADR-5: Custom OAuth2 Property Supplier for ANS Binding

**Decision:** The plugin registers a custom `AlertNotificationOAuth2PropertySupplier` to override how the SAP Cloud SDK extracts the service URI from an `alert-notification` service binding.

**Why:** The SAP Cloud SDK's `OAuth2ServiceBindingDestinationLoader` converts a service binding into an HTTP destination automatically. To do that, it uses an `OAuth2PropertySupplier` to locate the service URI inside the binding credentials JSON. The default supplier looks for the URL in standard fields (e.g. `credentials.uri`). ANS uses a non-standard structure: the ANS instance's base URL lives under `credentials.endpoints.notifications_url` (instead of the standard `credentials.uri`). Without the override, the SDK cannot find the URL and destination creation fails.

**Implementation:**
- `AlertNotificationOAuth2PropertySupplier` extends `DefaultOAuth2PropertySupplier` and overrides `getServiceUri()` to call `getCredentialOrThrow(URI.class, "endpoints", "notifications_url")`.
- It is registered in a `static` initializer block of `NotificationServiceConfiguration`:
  ```java
  static {
      OAuth2ServiceBindingDestinationLoader.registerPropertySupplier(
          options -> ServiceBindingUtils.matches(options.getServiceBinding(), "alert-notification"),
          AlertNotificationOAuth2PropertySupplier::new);
  }
  ```
  The static block runs exactly once when the class is loaded, before any instance methods execute. The predicate `ServiceBindingUtils.matches(..., "alert-notification")` ensures the custom supplier is only used for `alert-notification` bindings, leaving all other services unaffected.

**What to check if ANS connectivity breaks:** If ANS changes its binding credentials structure in a future version, `getServiceUri()` in `AlertNotificationOAuth2PropertySupplier` is the first place to update. Verify the actual binding JSON with `cf env <app-name>` and look for the ANS instance's base URL under `credentials.endpoints`.

---

### ADR-6: Auto-Provisioning at Application Start via ApplicationLifecycleService

**Decision:** All four auto-provisioner handlers (`NotificationTypeAutoProvisionerHandler`, `NotificationTemplateAutoProvisionerHandler`, and their local-mode counterparts) are annotated with `@ServiceName(ApplicationLifecycleService.DEFAULT_NAME)` and listen to `EVENT_APPLICATION_PREPARED`.

**Why at startup:** ANS requires that a notification type and a standalone notification template already exist in ANS **before** a notification of that type can be sent. If the types/templates are missing when the first notification is emitted, ANS will reject the request. By provisioning at `EVENT_APPLICATION_PREPARED` — which fires after the CDS model is fully built but before the application starts serving any requests — the plugin guarantees that all types and templates are present in ANS by the time any user action can trigger a notification.

**Why `EVENT_APPLICATION_PREPARED` specifically:** This lifecycle event is the earliest point at which the full CDS model (and therefore all `@notification`-annotated events) is available. An earlier hook (e.g. before model compilation) would not yet have the annotation metadata needed to build the type/template payloads.

**Consequence:** Provisioning is a blocking synchronous call during startup. If ANS is unreachable at startup time, a warning is logged and the application continues — it does not crash (except on 400 Bad Request, which is treated as a developer mistake and does crash; see [Section 5 — IllegalStateException vs regular exceptions](#illegalstateexception-vs-regular-exceptions-in-provisioners)). Types and templates that were provisioned in a previous deployment remain in ANS, so notifications can still be sent even if re-provisioning temporarily fails.

**Notification type provisioning (GET-all → PATCH/INSERT):** Fetch all existing types from ANS in one call → build a `Key → Id` map → for each type in the CDS model: update (PATCH) if key exists, create (INSERT) if not. On 409 conflict (race condition between the GET-all and the INSERT), re-fetch that specific type's ID and update.

**Notification template provisioning (DELETE + recreate):** Fetch all existing templates → for each template in the CDS model: **DELETE + re-create** if it already exists, INSERT if not. This differs from types — CDS `Update` translates to PATCH at the OData level, but the ANS `NotificationTemplate.svc` endpoint requires PUT for updates (not PATCH). Since CAP's OData v2 client maps `Update` to PATCH rather than PUT, a proper update is not possible through the standard CDS API. The workaround is to delete the existing template and create it fresh. The template payload includes `PropertiesSchema` (auto-generated JSON Schema from event elements), `Tags` (source service name + event name for admin UI filtering), and `Visibility` (`PUBLIC` if `@notification.customizable: true`, otherwise `PRIVATE`).

**Downside of DELETE + recreate:** Deleting a template in ANS also removes associated user settings for that template. This means user settings are reset on every deployment. For production use cases this is not an ideal update strategy — it was chosen only because ANS requires PUT for template updates but CAP's OData v2 client only supports PATCH. If CAP ever supports PUT for OData v2 remote services, the DELETE + recreate approach should be revisited.

**`PUBLIC` visibility is irreversible:** Once a template is made `PUBLIC` in ANS, it cannot be reverted to `PRIVATE`. This is an ANS constraint enforced server-side.

**Future direction — Content Deployment (GACD):**

The ANS team is planning a content deployment approach where notification types and templates are packaged as a deployment artifact (content module) at build time, rather than provisioned at runtime by the application:

- Types and templates would be encapsulated in a dedicated package (module) deployed via **GACD** (Generic Application Content Deployer)
- GACD is idempotent — it only deploys the delta from previous deployments
- The idea: use the CDS annotations at build time to generate a schema/content package; that package is then deployed separately from the application
- Multi-tenant support: content deployment could enable proper multi-tenant provisioning, which the current startup-time approach does not fully address

**Impact on this plugin:** If content deployment is adopted by ANS, the `NotificationTypeAutoProvisionerHandler` and `NotificationTemplateAutoProvisionerHandler` (and their local equivalents) could become unnecessary — provisioning would happen via the deployer, not at application startup. **Keep an eye on ANS roadmap updates and align with the ANS team before making significant changes to the auto-provisioning logic.**

---

### ADR-7: Outbox Wrapping of NotificationProviderService

**Decision:** In production mode, the `NotificationProviderService` (the OData remote service that sends notifications to ANS) is wrapped in CAP's persistent ordered outbox before being injected into `ProductionHandler`:

```java
NotificationProviderService outboxedSvc;
if (outbox != null) {
    outboxedSvc = outbox.outboxed(providerSvc);  // returns a transparent proxy
} else {
    outboxedSvc = providerSvc;  // direct call, no outbox
}
// outboxedSvc is then passed to ProductionHandler
```

**How the outbox proxy works:** `outbox.outboxed(providerSvc)` returns a proxy object that implements the same `NotificationProviderService` interface. When `ProductionHandler` calls `notificationProviderService.run(Insert.into(...).entry(notification))` on this proxy, the call is **not** immediately forwarded to ANS. Instead:

1. The notification payload is serialized and persisted to the application's database within the current transaction.
2. After the transaction commits, the outbox delivers the call to the real `NotificationProviderService` (i.e. ANS) asynchronously, with automatic retry on failure.
3. Delivery is **ordered** — notifications are forwarded to ANS in the same order they were queued (`PERSISTENT_ORDERED_NAME`).

**Why this matters:** Without the outbox, a notification send to ANS is a synchronous HTTP call. If ANS is temporarily unavailable, the call fails and the notification is lost. With the outbox, the notification is durable — it survives application restarts, ANS downtime, and transient network errors, because it lives in the database until successfully delivered.

**Scope — only `NotificationProviderService` is outboxed:** The type/template auto-provisioners at startup are NOT outboxed. They run synchronously during `EVENT_APPLICATION_PREPARED`. Only the actual notification sending is wrapped, because:
- Provisioning happens once at startup and is idempotent (re-runs on next startup if it failed).
- Notification sending happens at runtime per user action, where reliability and loss-prevention matter more.

**Fallback when outbox is unavailable:** If `OutboxService` is not present in the service catalog (e.g. in a minimal test setup without persistence), a warning is logged and the unwrapped `providerSvc` is used directly. Notifications still send, but without durability guarantees.

### ADR-8: CDS Event `key` Elements as ANS Target Parameters

**Decision:** `NotificationAssembler.extractTargetParameters()` uses the `key` annotation on CDS event elements to determine which fields become ANS `TargetParameters`. Only elements marked with `key` in the event definition are included.

```cds
event LowStockAlert {
  key bookId : Integer;   // → becomes TargetParameter
  recipients : String;
  bookTitle  : String;    // → becomes Property only
  stock      : Integer;   // → becomes Property only
}
```

**What `key` controls:**

- **With `key`:** The field becomes an ANS `TargetParameter` — used for navigation deep links and for the cooldown check. The cooldown check uses target parameters to distinguish "same notification type, different record" from "same notification type, same record". If `key bookId` is set, a recipient can be notified again for a different book even if they are in cooldown for this one.

- **Without `key`:** The field becomes a regular ANS `Property` — available in Mustache templates as `{{fieldName}}` but not used for navigation or cooldown matching.

**Why the developer controls this explicitly:** Not every event field should be a navigation key. For example, `stock` is a display value, not a record identifier. Letting the developer mark `key` fields gives full control over which fields drive navigation targets and cooldown scoping. Automatically treating all fields (or all entity key fields) as target parameters would make cooldown too coarse or generate unnecessary ANS payload.

**For the `@notifications` declarative path:** Entity key fields do NOT automatically become target parameters in the emitted event — you must explicitly mark the corresponding element as `key` in the CDS event definition. Also, if `parameters` is omitted in the `@notifications` annotation, all entity fields are passed through as properties automatically — useful for quick prototyping but can expose unwanted fields (including personal data). For entities with `@PersonalData` annotations, always declare `parameters` explicitly to control which fields are included.

---

### ADR-9: Standalone Templates Over Embedded Templates

**Decision:** The plugin provisions notification templates as **standalone templates** via the `NotificationTemplate.svc` API — not as embedded templates inside the `NotificationType` payload.

**Background:** ANS supports two ways to define notification content:
1. **Embedded templates** — template content is included directly inside the `NotificationType` payload (nested under the type object).
2. **Standalone templates** — template content is provisioned separately via the `NotificationTemplate.svc` endpoint, linked to the type by a shared key.

ANS does not allow both `Templates` and `Translations` on a `NotificationType` at the same time — it is one or the other, but at least one must be present. The plugin sets `Translations` on the type for admin UI display labels (`DisplayName`, `GroupTitle`) and provisions the actual notification content (title, body, email body) separately via the `NotificationTemplate.svc` standalone template endpoint.

**Why standalone:**
- Standalone templates are more powerful and flexible (confirmed with ANS team).
- For the consuming app, this distinction is invisible — the plugin handles both the type and the template behind the scenes using the same CDS annotations.

**Template visibility — `PRIVATE` by default:**
Templates are provisioned as `PRIVATE` unless `@notification.customizable: true` is set on the event, in which case they are provisioned as `PUBLIC`. This default was chosen because template customization (via the ANS admin UI) should be a deliberate decision by the developer — not something that happens automatically for every notification type.

**`PUBLIC` visibility is irreversible:** Once a template is made `PUBLIC` in ANS, it cannot be reverted to `PRIVATE` through the API. Changing back requires re-provisioning (the plugin deletes and recreates the template on every startup, so changing the annotation back to no `@notification.customizable` and redeploying will recreate it as `PRIVATE`).

---

## 5. Known Constraints and Non-Obvious Rules

### Event names must be globally unique

The event's **simple name** (e.g. `LowStockAlert`) — not its fully qualified name — is used as the `NotificationTypeKey` in ANS. This is a deliberate plugin design choice: the simple name maps cleanly to the ANS type key. The consequence is that if two different services define events with the same simple name, the last one to be provisioned at startup will **silently overwrite** the other's notification type in ANS.

### `using from 'com.sap.cds/cds-feature-notifications'` is mandatory

Without this `using` statement in the consuming app's CDS model, the plugin's remote service CDS definitions are not loaded into the model graph. At runtime, CAP cannot register the remote services and the plugin fails silently. This is the first thing to check when the plugin seems to do nothing.

### Consuming apps must exclude the plugin's remote service models from code generation

The plugin JAR ships its CDS models (`NotificationProviderService.cds`, `NotificationTypeProviderService.cds`, `NotificationTemplateProviderService.cds`) on the classpath so that CAP can load them at runtime. However, the `cds-maven-plugin` in the consuming app also discovers these models and tries to generate Java classes from them — resulting in duplicate class definitions: one set from the plugin JAR itself, and another set freshly generated in the consuming app's own `src/gen/`. This causes **compile-time conflicts** (duplicate class names on the classpath).

The workaround is to add explicit `<excludes>` to the `cds-maven-plugin` configuration in the consuming app's `srv/pom.xml`:

```xml
<excludes>
  <exclude>NotificationProviderService.**</exclude>
  <exclude>NotificationProviderService</exclude>
  <exclude>NotificationTypeProviderService.**</exclude>
  <exclude>NotificationTypeProviderService</exclude>
  <exclude>NotificationTemplateProviderService.**</exclude>
  <exclude>NotificationTemplateProviderService</exclude>
</excludes>
```

**Why this is a CAP Java limitation:** Ideally, a plugin JAR should be able to declare its own CDS models as "already compiled — do not re-generate for consumers." CAP Java currently has no such mechanism. The plugin cannot protect consumers from this collision automatically; each consuming app must add the excludes manually.

**If CAP Java adds native plugin model exclusion support in the future**, these `<excludes>` entries in consuming apps could become unnecessary. If you see that CAP introduces a `cds-maven-plugin` feature like `plugin-model-excludes` or similar, revisit whether the manual excludes can be dropped and update the README accordingly.

### `IllegalStateException` vs regular exceptions in provisioners

Both `NotificationTypeAutoProvisionerHandler` and `NotificationTemplateAutoProvisionerHandler` distinguish between:
- **`IllegalStateException`**: developer mistake (e.g. ANS returns 400 Bad Request). This is **re-thrown**, crashing startup. This is intentional — a misconfigured notification type should fail loudly during development.
- **Any other exception**: transient error (network, ANS down). Logged as error, startup continues.

### `StoreNotificationsLocalHandler` communication pattern

In local mode, the `LocalHandler` places sent notifications in `EventContext` under the key `SENT_NOTIFICATIONS_KEY` (`"com.sap.cds.notifications.stored"`). The `StoreNotificationsLocalHandler` then reads them `@After` the same event. This is how the two handlers share data without a direct reference — via the event context as a blackboard.

In production mode this pattern is not needed: `StoreNotificationsHandler` listens `@After` on the remote `NotificationProviderService` create event and reads the ANS-assigned ID from the OData response.

### `NotificationTemplateProviderService` uses a different URL suffix

All three remote services share the `SAP_Notifications` destination but differ in path:
- Notification sending: `/v2` + service `Notification.svc`
- Type management: `/v2` + service `NotificationType.svc`
- Template management: `/odatav2` (not `/v2`!) + service `NotificationTemplate.svc`

The different suffix for templates is an ANS API quirk, not a bug. Do not normalize these to the same prefix.

### Hardcoded ANS values that may need updating if ANS changes

The following string literals are hardcoded in the plugin and tied to ANS API or BTP platform conventions. If ANS or BTP changes any of these, the plugin breaks silently or with confusing errors.

| Value | Location | What it controls | Break scenario |
|---|---|---|---|
| `"alert-notification"` | `NotificationServiceConfiguration.java` (3 places) | SAP BTP service binding label used for mode detection and OAuth2 supplier matching | If SAP renames the ANS service offering, mode detection falls back to local mode even with a valid binding |
| `"SAP_Notifications"` | `NotificationServiceConfiguration.java` (2 places) | HTTP destination name for all three ANS remote services | If the destination is registered under a different name, remote service calls fail with "destination not found" |
| `"MUSTACHE"` | `NotificationTypeAssembler.java`, `NotificationTemplateAssembler.java` | Template syntax identifier sent to ANS at provisioning | If ANS renames or replaces the Mustache syntax, templates are rejected or behave unexpectedly |
| `"LOW"`, `"NEUTRAL"`, `"MEDIUM"`, `"HIGH"` | `NotificationAssembler.java` (`VALID_PRIORITIES` set) | Allowed priority values — anything outside this set is rejected before reaching ANS | If ANS adds a new priority level (e.g. `"CRITICAL"`), the plugin will silently discard it instead of forwarding it |
| `"NEUTRAL"` (default priority fallback) | `NotificationAssembler.java` | Default priority when none is specified in the annotation | If ANS changes its default priority semantics, the fallback may no longer be appropriate |
| `"odata-v2"` | `NotificationServiceConfiguration.java` | Remote service type for all three ANS services | ANS services are OData v2; if a future service uses v4, this must change |
| `"/v2"` / `"/odatav2"` URL suffixes | `NotificationServiceConfiguration.java` | OData endpoint paths for the three ANS services — already documented in Section 3.2 | See the warning in [Section 3.2](#32-the-three-ans-remote-services) |


### `services()` is intentionally empty

`NotificationServiceConfiguration.services()` has an empty body — this is not a bug. The three remote services are defined in `.cds` model files inside the plugin JAR, which CAP loads automatically from the classpath. The method still exists because `CdsRuntimeConfiguration` requires it.

If you ever see "service not found" errors for `NotificationProviderService`, `NotificationTypeProviderService`, or `NotificationTemplateProviderService`, the root cause is that the CDS model files are not being loaded (missing `using from 'com.sap.cds/cds-feature-notifications'` in the consuming app, or JAR not on classpath) — not that this method is empty.

### CSRF must be enabled for every ANS OData v2 remote service

CAP's OData v2 remote service client does not enable CSRF protection by default. ANS requires CSRF tokens for all mutating requests (POST/PUT/DELETE) — without `csrf.setEnabled(true)`, write requests silently return HTTP 403.

Any future remote service pointing at an ANS OData v2 endpoint must explicitly set this flag. It is not inherited from existing configs.

### `notificationTypeVersion` is hardcoded to `"1"`

`NotificationTypeAssembler` always sets `notificationTypeVersion = "1"` (hardcoded in `extractNotificationTypeFromEvent()`). ANS supports versioned types, but versioning is expensive — ANS retains all historical versions and the admin UI clutters quickly. For CAP applications the type structure is always regenerated from the CDS model at startup, so versioning is not needed — this was confirmed with the ANS team. If versioning is ever needed, update `setNotificationTypeVersion()` in `extractNotificationTypeFromEvent()`. Note that ANS treats each new version as a distinct type — old versions remain in ANS and must be cleaned up manually.

---

## 6. Module Walkthrough

### `NotificationAssembler`

**ANS API dependency:** The field names and structure built by this class directly mirror the ANS `Notification.svc` OData v2 API payload format. If ANS changes its API (new required fields, renamed properties, changed payload structure), `NotificationAssembler` is the first class to update.

The central payload builder. Shared between `ProductionHandler` and `LocalHandler`. Given an `EventContext`, it:
1. Finds the corresponding `CdsEvent` in the model (first by simple name, then by scanning all namespaces).
2. Checks for the `@notification.template.title` annotation as the canonical signal that this is a notification event. The dotted path is used because of CDS annotation flattening: `@notification: { template: { title: '...' } }` is stored in the reflected model as `notification.template.title`, not as a top-level `notification` entry — so `findAnnotation("notification")` returns empty. `title` is used specifically because it is a mandatory field in `@notification.template`.
3. Handles both single-entry (`CdsData`) and batch (`List<CdsData>`) payloads.
4. Resolves priority (static enum, static string, or dynamic `CqnValue` expression via DB).
5. Auto-detects recipient format and maps to the correct ANS field:
   - Value contains `@` → treated as **email address** → mapped to `RecipientId`
   - Value looks like a UUID → treated as **IAS Global User ID** → mapped to `GlobalUserId`. Note: I/P/S user numbers are not valid here — only the UUID assigned by IAS works.
   
   The plugin handles the distinction automatically so the consuming app only needs to pass the raw value.

   **Multiple recipients:** The CDS event element can be declared as `array of String` to send the same notification to multiple recipients in one emit call:

   ```cds
   event LowStockAlert {
     recipients : array of String;  // multiple emails or UUIDs
     key bookId : UUID;
     stock      : Integer;
   }
   ```

   ```java
   LowStockAlert data = LowStockAlert.create();
   data.put("recipients", List.of("user1@example.com", "user2@example.com"));
   ```

   The assembler iterates the list and creates one ANS `Recipients` entry per value, each auto-detected as email or UUID.
6. Maps `key`-annotated fields → `TargetParameters`, non-key non-recipient fields → `Properties`.
7. Extracts `@Common.SemanticObject`/`@Common.SemanticObjectAction` → `NavigationTargetObject`/`Action`.

### `EntityNotificationHandler`

Runs `@After` every entity operation. Does nothing if the entity has no `@notifications` annotation. When it does:
- Reads result rows from `context.get("result")` — handles both `Result` (CRUD) and `Map` (bound actions).
- Evaluates optional `where` conditions per row via `$DUMMY SELECT`.
- Resolves `$self.fieldName` expressions for recipients and parameters.
- Collects rows that pass the `where` condition into a `List<CdsData>` and emits a single batch event on the notification event's owning service.

**Why `@After` and not `@On` or `@Before`:** The notification must be sent only after the entity operation has completed successfully. `@After` guarantees two things: (1) the result rows are available (fields like `createdBy`/`modifiedBy` are only populated after the write), and (2) the operation has not been rolled back — if the CRUD fails, `@After` is not called, so no notification is sent for a failed write. Using `@On` would mean intercepting the actual CRUD execution, not reacting to it.

**Supported expression types for `recipients`:**

| Syntax | Example | How it works |
|---|---|---|
| `$self.fieldName` | `$self.createdBy` | Reads the field value from the entity row returned by the CRUD operation |
| Static string | `'admin@example.com'` | Passed through as-is |
| Array | `[$self.createdBy, $self.modifiedBy]` | Each entry resolved separately; nulls are dropped |

**`$user` is not supported** as a recipient expression. Only `$self.fieldName` and static strings are resolved. Support for `$user` was not implemented — see [ADR-2](#adr-2-dynamic-cds-expressions-via-dummy-select) for the full list of resolved session variables.

**Why `$self.createdBy` works:** CAP's managed aspect automatically populates `createdBy` and `modifiedBy` on every entity that uses `managed` (or extends `cuid, managed`). After a CREATE or UPDATE, these fields are present in the result row, so `$self.createdBy` reliably resolves to the user who triggered the operation.

The `@notifications` annotation value must be an **array** — even if there is only one notification config. Each entry is a map with the following fields:

| Field | Type | Required | Description |
|---|---|---|---|
| `type` | String | Yes | Name of the CDS event to emit |
| `on` | Array of Strings | Yes | CRUD events or bound action names that trigger the notification (e.g. `['CREATE', 'UPDATE']`) |
| `recipients` | Expression, String, or Array | Yes | `$self.fieldName` expression, static string (e.g. `'admin@example.com'`), or array of either |
| `where` | CQL Expression | No | Boolean condition — notification only fires if met |
| `parameters` | Map | No | Explicit field mapping: `propertyName : $self.fieldName` |

The `parameters` field maps entity fields to notification properties under custom names:

```cds
@notifications : [
  {
    type       : 'LowStockAlert',
    on         : ['CREATE', 'UPDATE'],
    recipients : $self.createdBy,
    where      : ($self.stock < 100),
    parameters : {
      bookTitle : $self.title,
      author    : $self.author,
      stock     : $self.stock
    }
  }
]
entity Books ...
```

For each entry, the handler resolves the `$self.fieldName` expression against the actual entity row and puts the result into the event `CdsData` under the given key (`bookTitle`, `author`, `stock`). These keys then flow through `NotificationAssembler` into the ANS notification's `Properties` list and can be referenced as `{{bookTitle}}`, `{{author}}`, `{{stock}}` in the Mustache template.

**If `parameters` is omitted**, all entity fields are passed through as properties automatically — see [Section 5](#for-the-notifications-declarative-path) for the implications.

### `NotificationTypeAssembler`

**ANS API dependency:** Builds the `NotificationType` payload for the ANS `NotificationType.svc` OData v2 API. The field mapping (e.g. `DisplayName` ← `@notification.template.publicTitle`, `GroupTitle` ← `@notification.template.groupedTitle`, `DeliveryChannels.Type` as `"MAIL"`/`"WEB"`) is derived from the ANS Notification Types API. If ANS adds new required fields to a notification type (e.g. new translation fields, new delivery channel properties), `NotificationTypeAssembler.extractTranslations()` and `extractDeliveryChannels()` are the methods to extend.

**Delivery channels are optional.** If `@notification.deliveryChannels` is not set on a CDS event, the assembler sends no delivery channels to ANS, and ANS falls back to its default — **Web only**. To also enable email delivery, add the annotation explicitly:

```cds
@notification.deliveryChannels : [{ channel: #MAIL, enabled: true, defaultPreference: true }]
event LowStockAlert { ... }
```

### `NotificationTemplateAssembler`

**ANS API dependency:** Builds the standalone `NotificationTemplate` payload for the ANS `NotificationTemplate.svc` OData v2 API (note: different URL suffix `/odatav2` vs `/v2` for the other two services). Key mappings: `Translation.Title` ← `@notification.template.title`, `Translation.Body` ← `@notification.template.subtitle`, `Translation.Preview` ← `@notification.template.publicTitle`, `PropertiesSchema` is auto-generated as a JSON Schema object from the event's non-key, non-recipient elements — each field becomes a typed property (CDS type mapped to JSON Schema type), `Tags` are key-value metadata attached to the template: two tags are set — `source` (the owning service name, e.g. `NotificationService`) and `event` (the event simple name, e.g. `LowStockAlert`) — so templates can be filtered and grouped by service or event in the ANS admin UI.

If ANS changes its template API — for example adds new translation fields, changes the `PropertiesSchema` format, or modifies how `Tags` work — `NotificationTemplateAssembler.createTranslation()`, `buildPropertiesSchema()`, and `buildTags()` are the methods to update.

**HTML email body — inline vs separate file:**

`@notification.template.email.html` supports two forms:

- **Inline HTML string** — the annotation value is the HTML content directly:
  ```cds
  email: { html: '<p>Hello <b>{{buyer}}</b>, your order is confirmed.</p>' }
  ```

- **File path** — the annotation value ends with `.html`, and the plugin loads the file from the classpath at provisioning time:
  ```cds
  email: { html: 'email-templates/book-ordered.html' }
  ```

The detection is simple: if the resolved value ends with `.html`, it is treated as a classpath path; otherwise it is used as-is. The file must be placed under `src/main/resources/` in the consuming app's `srv` module (e.g. `src/main/resources/email-templates/book-ordered.html`) — Spring Boot puts everything under `src/main/resources/` on the classpath automatically, so no extra configuration is needed.

Both `{i18n>KEY}` and `{{mustache}}` placeholders can appear in the HTML file (see the two-phase resolution note in the [`I18nHelper` section](#i18nhelper)).

> **General rule:** Whenever ANS releases API changes, cross-check all three assemblers against the updated ANS documentation. The CDS remote service models (`.cds` files under `src/main/resources/cds/`) may also need updating if the OData entity shapes change — see [Section 9](#9-adding-a-new-ans-remote-service-endpoint-to-the-plugin) for how to add a new remote service endpoint.

### `I18nHelper`

**Why it exists:** Both `NotificationTypeAssembler` and `NotificationTemplateAssembler` need to resolve i18n translations from CDS annotation values into concrete strings for every available locale. Without a shared helper, this logic would be duplicated in both assemblers. `I18nHelper` centralises all i18n-related work so the assemblers only call high-level methods and stay focused on building ANS payloads.

**What it wraps:** `EdmxI18nProvider` — a CAP-internal provider that reads from `edmx/_i18n/i18n.json`, which the CDS Maven plugin compiles from all `_i18n/i18n*.properties` files found next to `.cds` source files. `I18nHelper` obtains this provider lazily on first use via `runtime.getProvider(EdmxI18nProvider.class)`.

**Key methods and what they do:**

- **`getAvailableLocales()`** — asks `EdmxI18nProvider` which locales exist in the application. Always falls back to English if the provider returns nothing. See [ADR-3](#adr-3-provision-only-app-translated-locales-to-ans) for why raw locale discovery is not enough on its own.

- **`getAvailableLocalesForEvent(event)`** — filters the raw locale list down to only those that have actual notification translations for this specific event. Compares each locale's resolved `@notification.template.title` against the English value; a locale is kept only if its title differs AND is fully resolved. English is always kept. This prevents the ~37 framework-supplied locales from `@sap/cds/common` from flooding ANS with unresolved `{i18n>KEY}` placeholder strings (see [ADR-3](#adr-3-provision-only-app-translated-locales-to-ans)).

- **`getI18nTexts(locale)`** — returns the full key→value map for a given locale from `EdmxI18nProvider`. For English specifically, it also merges in `Locale.ROOT` (the default `i18n.properties` with no language suffix) using `putIfAbsent`, so apps that only define `i18n.properties` (without an explicit `i18n_en.properties`) still work correctly.

- **`resolveAnnotationValue(event, annotationPath, i18nTexts)`** — reads the annotation value (e.g. `@notification.template.title`) from the CDS event, then passes it through `resolveI18n()`. Returns `null` if the annotation is absent or any placeholder cannot be resolved.

- **`resolveI18n(value, i18nTexts)`** — replaces all `{i18n>KEY}` patterns in a string with the corresponding values from the map. If after all replacements any `{i18n>` pattern still remains (i.e. a key was missing), returns `null` instead of a partially-resolved string. This is a deliberate safety measure: a template with an unresolved placeholder reaching ANS would display a raw `{i18n>KEY}` string to end users.

- **`loadHtmlFromClasspath(filePath, i18nTexts)`** — loads an HTML file from the classpath (used for `@notification.template.email.html`), resolves `{i18n>KEY}` placeholders in its content, and applies a light minification pass (strips HTML comments, collapses whitespace between tags, trims). The raw HTML content is **cached after first load** so the file is only read from disk once per application lifecycle, regardless of how many locales request it. Only the i18n-resolved version differs per locale.

**Two-phase placeholder resolution — using `{i18n>KEY}` and `{{mustache}}` together:**

Both placeholder types can appear in the same HTML file or i18n value, but they are resolved at different times:

| Placeholder | Example | Resolved by | When |
|---|---|---|---|
| `{i18n>KEY}` | `{i18n>EMAIL_SUBJECT}` | Plugin (`I18nHelper`) | At provisioning time — when the template is sent to ANS |
| `{{mustache}}` | `{{bookTitle}}` | ANS | At delivery time — when ANS sends the actual notification |

A typical HTML email template combines both:

```html
<h1>{i18n>EMAIL_GREETING}</h1>
<p>Book <strong>{{bookTitle}}</strong> is running low on stock: {{stock}} remaining.</p>
```

At provisioning, `{i18n>EMAIL_GREETING}` is replaced with the translated text (e.g. `"Hello"`). The `{{bookTitle}}` and `{{stock}}` placeholders are left as-is in the template sent to ANS. At delivery, ANS fills them in from the notification's property values.

The same pattern works inside i18n `.properties` values:

```properties
EMAIL_SUBJECT=Low stock alert for {{bookTitle}}
```

Here `EMAIL_SUBJECT` is resolved via `{i18n>EMAIL_SUBJECT}` at provisioning — and the resulting string (which still contains `{{bookTitle}}`) is passed to ANS, which resolves the Mustache variable at delivery.

### `NotificationStorageHelper`

**Why it exists:** Both `StoreNotificationsHandler` (production) and `StoreNotificationsLocalHandler` (local) need to write notifications to the DB. Extracting the write logic into a shared helper avoids duplication and keeps the two handlers thin.

**What gets stored and where:**

A single `store(notificationId, request, sentAt)` call writes one row per recipient. For a notification sent to three recipients, three rows are written to `sap.cds.notifications.Notifications` (see [Section 8](#8-db-storage-and-cooldown) for where these tables come from).

For each recipient the following fields are stored:

| Field in `Notifications` | Source |
|---|---|
| `ID` | ANS-assigned ID (production) or locally generated UUID (local mode) |
| `recipient` | Resolved recipient string — UUID if `GlobalUserId` is set, email otherwise |
| `notificationTypeKey` | From the assembled `Notifications` object (= CDS event simple name) |
| `notificationTemplateKey` | Same value as `notificationTypeKey` |
| `priority` | Resolved priority string (`HIGH`, `MEDIUM`, `LOW`, `NEUTRAL`) or null if not set |
| `navigationTargetObject` | From `@Common.SemanticObject` annotation |
| `navigationTargetAction` | From `@Common.SemanticObjectAction` annotation |
| `sentAt` | Timestamp passed in by the caller (`Instant.now()` at time of storage) |

In addition, two composition child tables are populated from the same request:

**`sap.cds.notifications.NotificationProperties`** — one row per non-key, non-recipient event field (the Mustache template placeholders — see [ADR-8](#adr-8-cds-event-key-elements-as-ans-target-parameters) for the key/non-key distinction). For example `bookTitle = "Wuthering Heights"`, `stock = "12"`. These correspond to the `Properties` list in the ANS payload.

**`sap.cds.notifications.NotificationTargetParameters`** — one row per `key`-annotated event field (used for deep-link navigation). For example `bookId = "abc-123"`. These correspond to `TargetParameters` in the ANS payload (see [ADR-8](#adr-8-cds-event-key-elements-as-ans-target-parameters) for why only `key` fields become target parameters).

**`resolveRecipientId` (static):** Extracts the plain string identifier from a `Recipients` object — prefers `GlobalUserId` (UUID) if set, falls back to `RecipientId` (email). This is the value stored in the `recipient` column and is also what `CooldownChecker` queries against, ensuring the stored and queried values always match.

**Important:** `NotificationStorageHelper` does not check or filter before writing — it trusts the caller to invoke it only for notifications that were actually sent. Cooldown filtering happens upstream in `CooldownChecker`, before the storage handler is invoked.

### `CooldownChecker`


**Why it exists:** The plugin needs to prevent the same notification from being sent repeatedly to the same recipient within a configured time window. This logic must run just before the notification is sent — after assembly but before the ANS call. Extracting it into a dedicated class keeps `ProductionHandler` and `LocalHandler` clean, and makes the cooldown logic independently testable.

**Prerequisite:** `CooldownChecker` requires `storeNotifications: true` to be enabled. Without DB history there is nothing to check against, so when `PersistenceService` is `null` (i.e. `storeNotifications` is off), `filterCooldownRecipients` logs a warning and returns the notification unchanged.

**How it works — `filterCooldownRecipients(event, notification)`:**

This is the single public method, called by both `ProductionHandler` and `LocalHandler` before sending.

1. Reads the `@notification.cooldown` annotation from the CDS event. If absent or `<= 0`, returns the notification unchanged — no cooldown applies.
2. Computes the cutoff timestamp: `now - cooldownDays`.
3. Builds a map of the current notification's `targetParameters` (key → value) from the `NavigationTargetParams` list. This map represents the "scope" of this specific notification (e.g. `{collaborationId: "abc-123"}`). If there are no `key`-annotated fields, the map is empty — the cooldown then applies globally per type+recipient.
4. For each recipient in the notification's `Recipients` list, calls `isInCooldown(typeKey, recipientId, cutoff, targetParamsMap)`.
5. Builds a filtered list of recipients that are **not** in cooldown. Recipients in cooldown are silently dropped.
6. If the filtered list is empty (every recipient is in cooldown), returns `null` — the caller skips the notification entirely. If at least one recipient is not in cooldown, updates the notification's recipients to the filtered list and returns it.

**How `isInCooldown` queries the DB:**

Queries `sap.cds.notifications.Notifications` for rows where:
- `notificationTypeKey = <typeKey>`
- `recipient = <recipientId>`
- `sentAt > <cutoff>` (i.e. sent within the cooldown window)

For each matching stored notification, it expands and fetches the `targetParameters` composition, builds a `storedParams` map (paramKey → paramValue), and compares it with `currentTargetParams` using standard Java `Map.equals()`. The recipient is considered in cooldown only if an exact match on both the time window **and** the target parameters is found.

**Why target parameters are part of the cooldown key:** Consider `ApproveCollaboration` with `key collaborationId`. If `cooldown: 20`, the recipient should not receive the same request for the same collaboration twice within 20 days — but they should still receive it for a *different* collaboration. Without target parameters in the check, any notification of the same type within the window would be suppressed, regardless of which record it relates to.

**`resolveRecipientId` (static, package-private):** Extracts the string identifier from a `Recipients` object — prefers `GlobalUserId` (UUID) if set, falls back to `RecipientId` (email). This mirrors the same logic used by `NotificationStorageHelper` when storing, ensuring the stored and queried recipient IDs always match.

---

## 7. Local Mode vs Production Mode

| Aspect | Local Mode | Production Mode |
|---|---|---|
| Trigger | No binding, no `production.enabled` | Binding present OR `production.enabled: true` |
| Notification send | `LocalHandler` logs to console | `ProductionHandler` calls ANS via outbox |
| Notification types | `LocalNotificationTypeAutoProvisionerHandler` logs to console | `NotificationTypeAutoProvisionerHandler` calls ANS |
| Templates | `LocalNotificationTemplateAutoProvisionerHandler` logs to console | `NotificationTemplateAutoProvisionerHandler` calls ANS |
| DB storage | `StoreNotificationsLocalHandler` reads from `EventContext` | `StoreNotificationsHandler` reads ANS-returned ID |
| Local notification ID | UUID generated locally | ID assigned by ANS (returned in OData response) |
| Template rendering | English only, Mustache placeholders filled locally | ANS handles language resolution and placeholder substitution |

Local mode renders templates locally for display — it uses English i18n and replaces `{{mustache}}` placeholders with actual values. In production, ANS receives the Mustache template and raw property values separately and resolves them at delivery time.

### Hybrid testing — connecting a local app to a real ANS instance

Two options for testing production mode locally:

**Option 1 — Hybrid mode (binding-based):** Pull a CF binding and run with the `hybrid` profile:

```bash
cds bind --to ans-notifications-email
cds bind --exec mvn spring-boot:run
```

`cds bind` writes the CF service binding credentials to `.cdsrc-private.json` (gitignored, lives at `sample-app/.cdsrc-private.json`). `cds bind --exec mvn spring-boot:run` then runs the app with those credentials injected into the environment, so the plugin detects the binding and activates production mode automatically.

**Option 2 — `DestinationConfiguration` (Work Zone credentials in code):** If you want to test against Work Zone credentials without a CF binding, uncomment and fill in the `DestinationConfiguration` class in `sample-app/srv/src/main/java/customer/sample_app/config/DestinationConfiguration.java`. It programmatically creates the `SAP_Notifications` destination from hardcoded credentials via `prependDestinationLoader`. The `@Profile("!cloud")` annotation ensures it only activates locally and does not interfere with the binding path in cloud.

Since there is no service binding in this case, `ansBindingPresent` is `false` and the plugin would fall back to local mode. To force production mode, set `cds.environment.production.enabled: true` in `application.yaml`. With Option 1 (hybrid mode), this flag is not needed — the CF binding is present in the environment and `ansBindingPresent` becomes `true` automatically.

---

## 8. DB Storage and Cooldown

Enabled via `cds.notifications.storeNotifications: true`.

**Where the tables come from:** The three DB entities are defined in `NotificationStorage.cds`, which lives inside the plugin JAR under `cds/com.sap.cds/cds-feature-notifications/`. When the consuming app adds `using from 'com.sap.cds/cds-feature-notifications'` to its CDS model (mandatory — see [Section 5](#using-from-comsapcds-cds-feature-notifications-is-mandatory)), CAP picks up this file and includes it in the application's combined CDS model. CAP's schema evolution then automatically creates the corresponding tables in the application's database at startup. No manual SQL or migration script is needed — the consuming app gets the tables for free just by having the plugin as a dependency and the `using` statement in its CDS.

| Table | Key | Contents |
|---|---|---|
| `sap.cds.notifications.Notifications` | `(ID, recipient)` | One row per notification per recipient |
| `sap.cds.notifications.NotificationProperties` | `(notification, propertyKey)` | Template placeholder values |
| `sap.cds.notifications.NotificationTargetParameters` | `(notification, paramKey)` | Key-annotated field values |

All three entities carry `@PersonalData` annotations, making them compatible with `cds-feature-data-privacy` for GDPR erasure requests (the `recipient` field is the data subject ID). Specifically: `Notifications` has entity-level `@PersonalData.EntitySemantics: 'Other'` (marks the entity as a personal data container) plus field-level `DataSubjectID` on `recipient`. `NotificationProperties` and `NotificationTargetParameters` have only field-level `@PersonalData.FieldSemantics: 'IsPotentiallyPersonal'` on their value fields — no entity-level annotation.

**Cooldown** is layered on top: before sending, `CooldownChecker` queries `sap.cds.notifications.Notifications` and filters out any recipient who received the same notification type with the same target parameters within the cooldown window. Without `storeNotifications: true`, cooldown has no history to query and is silently ignored.

---

## 9. Adding a new ANS remote service endpoint to the plugin

This is exactly how `NotificationProviderService`, `NotificationTypeProviderService`, and `NotificationTemplateProviderService` were originally added. Follow these steps in order:

**Step 1 — Fetch the OData metadata**

Download the EDMX file from the service's `$metadata` endpoint:
```
GET https://<ans-host>/v2/<ServiceName>.svc/$metadata
```
Save it as an `.edmx` file.

**Step 2 — Import into the project**

```bash
cds import <input_file>.edmx --as cds
```
This imports the EDMX into the project and:
- Creates a `.cds` (CDS model) and `.xml` (EDMX copy) under `srv/external/`
- Adds a service definition entry to `application.yaml`

**Step 3 — CSN generation (already wired, one-time setup)**

The CDS Maven plugin compiles `srv/external/*.cds` to a CSN file automatically during the build via:
```xml
<command>compile ${project.basedir}/srv/external/*.cds --to csn --dest ${project.build.directory}/cds-output/all.csn</command>
```
This step is already configured in `pom.xml` — no change needed for a new service.

**Step 4 — Java class generation (already wired, one-time setup)**

The Maven build generates typed Java interfaces from the CSN file and adds them to the build path. This is already configured in `pom.xml` via:
```xml
<phase>generate-sources</phase>
<configuration>
  <outputDirectory>${project.build.directory}/generated-sources/cds</outputDirectory>
</configuration>

<!-- build-helper-maven-plugin adds generated-sources/cds to the compile source roots -->
<plugin>
  <groupId>org.codehaus.mojo</groupId>
  <artifactId>build-helper-maven-plugin</artifactId>
  <executions>
    <execution>
      <id>add-source</id>
      <phase>generate-sources</phase>
      <goals><goal>add-source</goal></goals>
      <configuration>
        <sources>
          <source>${project.build.directory}/generated-sources/cds</source>
        </sources>
      </configuration>
    </execution>
  </executions>
</plugin>
```
After `mvn compile`, the typed Java service interface and entity classes are available in `target/generated-sources/cds/`.

**Step 5 — Copy the CDS model into the plugin resources**

Copy the generated `.cds` (and `.xml`) files from `srv/external/` into:
```
src/main/resources/cds/com.sap.cds/cds-feature-notifications/
```
Then add a `using from` line in `index.cds` so the model is part of the plugin's published CDS bundle.

**Step 6 — Include `srv/external` files in the JAR (one-time setup per new service)**

Add this Maven resource entry to `pom.xml` so the `.cds`, `.csn`, and `.xml` files are packaged into the JAR and available on the classpath at runtime:
```xml
<resource>
  <directory>srv/external</directory>
  <targetPath>srv/external</targetPath>
  <includes>
    <include>**/*.csn</include>
    <include>**/*.cds</include>
    <include>**/*.xml</include>
  </includes>
</resource>
```

**Step 7 — Register the remote service in `NotificationServiceConfiguration.environment()`**

Add a `RemoteServiceConfig` block pointing to the `SAP_Notifications` destination with the correct OData path (see the existing three services as examples):
```java
RemoteServiceConfig myNewConfig = new RemoteServiceConfig();
myNewConfig.setType("odata-v2");
myNewConfig.getDestination().setName("SAP_Notifications");
myNewConfig.getHttp().setSuffix("/v2");
myNewConfig.getHttp().setService("NewService.svc");
myNewConfig.getHttp().getCsrf().setEnabled(true);
configurer.getCdsRuntime().getEnvironment().getCdsProperties()
    .getRemote().getServices().put("NewServiceName", myNewConfig);
```

**Step 8 — Add handler logic**

Register a handler that uses the new service (e.g. an auto-provisioner on `EVENT_APPLICATION_PREPARED`, or a send handler) in `NotificationServiceConfiguration.eventHandlers()`.

**Step 9 — Exclude from `sample-app` code generation**

Add the new service to the `<excludes>` block of the `cds-maven-plugin` configuration in `sample-app/srv/pom.xml`, so the consuming app does not generate duplicate Java classes:
```xml
<exclude>NewServiceName.**</exclude>
<exclude>NewServiceName</exclude>
```
Also add the same exclude to the `integration-tests` module if needed.

---

## 11. Testing Strategy and Coverage

### Integration Tests — Built on a Real CAP Sample App

The `integration-tests/` module is a **complete standalone CAP Java application** (a Bookshop variant) that depends on the plugin as a Maven artifact. Its purpose is to test the plugin's behaviour end-to-end through a real CAP runtime — not by calling the plugin's internals directly, but by using it the same way a consuming application would: defining `@notification`-annotated CDS events, injecting the generated service, and emitting notifications.

Structure:
```
integration-tests/
  db/                   ← data model (Books, Alerts)
  srv/                  ← CAP service layer
    src/main/           ← application code (CatalogServiceHandler, Application.java)
    src/test/
      integration/      ← test classes (one per feature area)
      handlers/mock/    ← ANS mock handlers (registered only under "test" profile)
      testdata/         ← builder helpers per notification event type
```

The integration tests boot the full CAP Spring Boot runtime (`@SpringBootTest`) with an H2 in-memory database, so they exercise the real plugin code paths: mode detection, handler registration, auto-provisioning, `NotificationAssembler`, `EntityNotificationHandler`, cooldown, DB storage, etc.

### Why ANS Cannot Be Called in Tests

The three ANS remote services (`NotificationProviderService`, `NotificationTypeProviderService`, `NotificationTemplateProviderService`) are OData v2 remote services that would normally make HTTP calls to a real ANS instance. In tests there is no ANS instance available, and even if there were, tests must not rely on external services — they would be slow, flaky, and require BTP credentials in CI.

### The Three ANS Mock Handlers

Instead of mocking at the HTTP level, the mocks are CAP **event handlers** that register on the same service names and intercept the same CDS events, short-circuiting the real remote OData handler by calling `context.setCompleted()`.

| Mock class | Service intercepted | What it simulates |
|---|---|---|
| `NotificationProviderServiceMockHandler` | `NotificationProviderService` | Receiving a notification: validates type exists, generates an ID, stores in-memory |
| `NotificationTypeProviderServiceMockHandler` | `NotificationTypeProviderService` | ANS type store: handles CREATE, UPDATE, and SELECT operations in-memory |
| `NotificationTemplateProviderServiceMockHandler` | `NotificationTemplateProviderService` | ANS template store: handles CREATE, UPDATE, and SELECT operations in-memory |

All three are annotated `@Profile("test")` and `@Component`, so they are only loaded when the Spring profile `test` is active. This prevents them from interfering with the `sample-app` or any other non-test context.

`NotificationProviderServiceMockHandler` also **enforces the ANS constraint** that the referenced notification type must already exist before a notification can be sent — it cross-checks with `NotificationTypeProviderServiceMockHandler`'s in-memory store. This means integration tests catch missing auto-provisioning bugs the same way ANS would in production.

Tests assert against the mocks via their static getter methods (e.g. `NotificationProviderServiceMockHandler.getNotificationsByTypeKey(...)`, `getAllNotifications()`). Each test cleans up the mock stores in `@BeforeEach` via the corresponding `clearAll...()` methods.

### Integration Test Coverage Areas

| Test class | What it covers |
|---|---|
| `NotificationIntegrationTest` | Programmatic emit, recipient formats, priority, navigation targets, batch emit |
| `EntityNotificationIntegrationTest` | `@notifications` entity annotation, CRUD triggers, where conditions, parameter mapping |
| `NotificationTypeProvisioningTest` | Auto-provisioning of types at startup, upsert behaviour, i18n translations |
| `NotificationTemplateProvisioningTest` | Auto-provisioning of templates, visibility, properties schema, email templates |
| `LocalModeIntegrationTest` | Local mode console output (no ANS calls) |
| `StoreNotificationsIntegrationTest` | DB persistence of sent notifications (production mode) |
| `StoreNotificationsLocalModeIntegrationTest` | DB persistence in local mode |
| `CooldownIntegrationTest` | Cooldown window enforcement, per-recipient and per-target-parameter scoping |

### Coverage Report — Merging Unit and Integration Tests

The `coverage-report/` module is a dedicated Maven module with no source code. Its sole purpose is to **merge the JaCoCo coverage data** from both the unit tests and the integration tests into a single aggregated HTML report.

How it works (via JaCoCo Maven plugin in `coverage-report/pom.xml`):
1. **`jacoco:merge`** (phase `prepare-package`): merges two `.exec` files:
   - `cds-feature-notifications/target/jacoco.exec` — unit test coverage
   - `integration-tests/srv/target/jacoco.exec` — integration test coverage
   - Output: `coverage-report/target/jacoco-merged.exec`
2. **`jacoco:report`** (phase `verify`): generates the HTML/XML/CSV report from the merged `.exec` file against the compiled classes of `cds-feature-notifications`. Generated code (`cds/gen/**`) is excluded from the report.

The report lands in `coverage-report/target/site/jacoco-aggregate/`. Run `mvn verify` from the repo root to regenerate it.

> **Note:** `coverage-report` is not deployed (`maven-deploy-plugin` is skipped) — it is a local reporting artifact only.

### How to debug

Set `logging.level.'[com.sap.cds.notifications]': DEBUG` in `application.yaml` to see detailed plugin output. In local mode, provisioning results are already logged at INFO level.

---
