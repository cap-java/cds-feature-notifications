# Bookshop Sample - Notifications Plugin

This sample demonstrates how to use the `cds-feature-notifications` plugin in a CAP Java application. It extends the classic CAP bookshop sample to show different notification patterns supported by the plugin.

## What This Sample Demonstrates

- Manual notification via `NotificationService` from Java handler code
- Entity-based notifications via `@notifications` annotation on CDS entities
- CRUD triggers (`UPDATE`) and bound action triggers (`restock`)
- Dynamic priority expressions using CDS functions (`contains()`, ternary operator)
- i18n support for notification templates
- HTML email templates
- Delivery channel control (Mail only, Web only, Mail + Web)
- Semantic object navigation (deep link from notification to app)
- Customizable notification templates
- `where` conditions to filter when notifications are sent

## Prerequisites

- Java 17 or higher
- Maven 3.6 or higher
- Node.js 18 or higher
- npm
- CAP Java (`com.sap.cds:cds-services-bom`) **4.9.0 or higher**

## Getting Started

1. **Install the plugin to your local Maven repository** (only needed when running locally with a snapshot version):
   ```bash
   # From the project root
   mvn install -pl cds-feature-notifications -DskipTests
   ```

2. **Navigate to the sample**:
   ```bash
   cd sample-app
   ```

3. **Build the application**:
   ```bash
   mvn clean compile
   ```

4. **Run the application**:
   ```bash
   mvn spring-boot:run
   ```

5. **Access the application**:
   - Browse Books: http://localhost:8080/browse/index.html
   - Admin Books: http://localhost:8080/admin-books/index.html

## Notification Examples

### Example 1: Manual Notification — `BookOrdered`

**File:** `srv/notifications.cds`, `srv/src/main/java/.../handlers/CatalogServiceHandler.java`

When a user submits an order via the `submitOrder` action, the Java handler manually emits a `BookOrdered` notification through the `NotificationService`.

**Features demonstrated:**
- Manual emit from Java handler
- i18n template (`{i18n>BOOK_ORDERED_TEMPLATE_SENSITIVE}`)
- HTML email template (`email-templates/book-ordered.html`)
- Mail + Web delivery channels
- `@Common.SemanticObject` for deep link navigation
- `customizable: true` to allow end-user configuration
- Static priority `#HIGH`
- Single string recipient (`recipients: String`)

```cds
@Common.SemanticObject      : 'Books'
@Common.SemanticObjectAction: 'display'
@notification: {
  customizable: true,
  template: {
    title       : '{i18n>BOOK_ORDERED_TEMPLATE_SENSITIVE}',
    publicTitle : '{i18n>BOOK_ORDERED_TEMPLATE_PUBLIC}',
    subtitle    : '{i18n>BOOK_ORDERED_SUBTITLE}',
    groupedTitle: '{i18n>BOOK_ORDERED_TEMPLATE_GROUPED}',
    email: {
      subject: '{i18n>BOOK_ORDERED_EMAIL_SUBJECT}',
      html   : 'email-templates/book-ordered.html',
    },
  },
  deliveryChannels: [
    { channel: #Mail, enabled: true, defaultPreference: true },
    { channel: #Web,  enabled: true, defaultPreference: true },
  ],
  priority: #HIGH,
}
event BookOrdered {
  recipients : String;  // single email or UUID
  bookTitle  : String;
  quantity   : Integer;
  buyer      : String;
}
```

```java
BookOrdered notification = BookOrdered.create();
notification.setRecipients(context.getUserInfo().getName());
notification.setBookTitle(book.getTitle());
notification.setQuantity(context.getQuantity());
notification.setBuyer(context.getUserInfo().getName());

BookOrderedContext notifContext = BookOrderedContext.create();
notifContext.setData(notification);
notificationService.emit(notifContext);
```

---

### Example 2: Entity Change Notification — `LowStockAlert`

**File:** `srv/notifications.cds`, `srv/admin-service.cds`

Notifications are sent automatically by the plugin when the `Books` entity is updated and the stock drops below 10 — no Java handler code needed. The `@notifications` annotation on the entity declares the trigger condition, recipients, and parameter mapping.

**Features demonstrated:**
- `@notifications` annotation on entity (declarative, no Java code)
- `UPDATE` CRUD trigger
- `where` condition to filter notifications (`$self.stock < 10`)
- Dynamic priority combining `contains()` and numeric comparison
- Email-only delivery channel (`#Mail`)
- Array of recipients (`recipients: array of String`) — multiple emails or UUIDs
- `$self.fieldName` for parameter mapping

```cds
// Event definition in notifications.cds
@notification: {
  template: {
    title        : 'Low stock alert for "{{bookTitle}}"',
    publicTitle  : 'Low stock alert',
    subtitle     : 'Remaining stock: {{stock}}',
    groupedTitle : '{{count}} low stock alerts',
  },
  deliveryChannels: [
    { channel: #Mail, enabled: true, defaultPreference: true }
  ],
  priority: (contains(bookTitle, 'Heights') ? 'HIGH' : (stock < 5 ? 'HIGH' : 'MEDIUM')),
}
event LowStockAlert {
  recipients : array of String;
  bookTitle  : String;
  stock      : Integer;
}
```

```cds
// Entity annotation in admin-service.cds
@notifications: [{
  type      : 'LowStockAlert',
  on        : ['UPDATE'],
  recipients: $self.createdBy,
  where     : ($self.stock < 10),
  parameters: {
    bookTitle: $self.title,
    stock    : $self.stock,
  }
}]
entity Books as projection on my.Books;
```

---

### Example 3: Bound Action Notification — `StockReplenished`

**File:** `srv/notifications.cds`, `srv/admin-service.cds`

Notifications are sent automatically when the `restock` bound action is called on a Book. This demonstrates how to trigger notifications from custom actions without writing any Java handler code.

**Features demonstrated:**
- `@notifications` annotation with bound action trigger (`restock`)
- Web-only delivery (no `deliveryChannels` → ANS default: Web notification only, no email)
- Static priority `#MEDIUM`
- `$self.fieldName` for parameter mapping from the entity to notification properties

```cds
// Event definition in notifications.cds
@notification: {
  template: {
    title         : 'Stock replenished for "{{bookTitle}}"',
    publicTitle   : 'Stock replenished',
    subtitle      : 'New stock level: {{newStock}}',
    groupedTitle  : '{{count}} stock replenishments',
  },
  priority: #MEDIUM,
}
event StockReplenished {
  recipients : String;
  bookTitle  : String;
  newStock   : Integer;
}
```

```cds
// Entity annotation in admin-service.cds
@notifications: [{
  type      : 'StockReplenished',
  on        : ['restock'],
  recipients: $self.createdBy,
  parameters: {
    bookTitle: $self.title,
    newStock : $self.stock,
  }
}]
entity Books as projection on my.Books
  actions {
    action restock(amount : Integer) returns Books;
  };
```

---

## Implementation Details

### Maven Configuration

The notifications plugin is added to `srv/pom.xml`:

```xml
<dependency>
    <groupId>com.sap.cds</groupId>
    <artifactId>cds-feature-notifications</artifactId>
</dependency>
```

Also add excludes for the plugin's remote service models inside the `cds-maven-plugin` configuration, so they are not generated in your project:

```xml
<!-- srv/pom.xml, inside the cds-maven-plugin configuration -->
<excludes>
    <exclude>NotificationProviderService.**</exclude>
    <exclude>NotificationProviderService</exclude>
    <exclude>NotificationTypeProviderService.**</exclude>
    <exclude>NotificationTypeProviderService</exclude>
    <exclude>NotificationTemplateProviderService.**</exclude>
    <exclude>NotificationTemplateProviderService</exclude>
</excludes>
```

### CDS Model

The sample uses two CDS files for notifications:

- **`srv/notifications.cds`** — defines notification events with `@notification` annotations (template, priority, delivery channels)
- **`srv/admin-service.cds`** — uses `@notifications` on the `Books` entity to declaratively trigger notifications on CRUD/action events

```cds
// srv/notifications.cds — event definitions
service NotificationService {
  @notification: { template: {...}, deliveryChannels: [...], priority: #HIGH }
  event BookOrdered { recipients: String; bookTitle: String; ... }

  @notification: { template: {...}, deliveryChannels: [{ channel: #Mail, ... }], priority: (...) }
  event LowStockAlert { recipients: array of String; bookTitle: String; stock: Integer; }

  @notification: { template: {...}, priority: #MEDIUM }
  event StockReplenished { recipients: String; bookTitle: String; newStock: Integer; }
}
```

```cds
// srv/admin-service.cds — declarative triggers
@notifications: [
  { type: 'LowStockAlert',     on: ['UPDATE'],  where: ($self.stock < 10), ... },
  { type: 'StockReplenished',  on: ['restock'], ... }
]
entity Books as projection on my.Books actions { action restock(...); };
```

### i18n Support

Notification templates support internationalization. Translation files are located in `srv/_i18n/`:

| File | Language |
|---|---|
| `i18n.properties` | Default (English) |
| `i18n_de.properties` | German |

### HTML Email Template

The `BookOrdered` notification uses an HTML email template located at `srv/src/main/resources/email-templates/book-ordered.html`. The template uses `{i18n>KEY}` for translated strings and `{{fieldName}}` for dynamic values from the event payload.

## Local Mode vs Production Mode

The plugin automatically switches between two modes based on your `application.yaml` configuration.

### Local Mode (default)

No configuration needed. Notifications are logged to the console instead of being sent to SAP Alert Notification Service. This is the default when running locally.

```yaml
# application.yaml — local mode (default, no extra config needed)
```

You will see log output like:
```
INFO - [LocalHandler] Notification sent: BookOrdered → recipient: admin
```

### Production Mode

To send notifications to SAP Alert Notification Service (and route them to SAP Build Work Zone), enable production mode in `application.yaml`:

```yaml
cds:
  environment:
    production:
      enabled: true
```

In production mode, the plugin needs ANS credentials. There are two ways to provide them:

**Option 1: Bind an ANS service instance on Cloud Foundry**

Create an ANS service instance with the `business-notifications` plan and bind it to the app. The plugin auto-discovers the binding.

**Option 2: Local testing with manual destination registration**

For local testing against a real ANS instance, use the `DestinationConfiguration.java` template at `srv/src/main/java/customer/sample_app/config/`. The file is fully commented out — to use it:

1. Uncomment the class body
2. Fill in your ANS service key values:
   - `clientId`, `clientSecret` — from the ANS service key
   - `tokenUrl` — OAuth2 token endpoint
   - `host` — ANS API URL
   - `destinationName` — must be `SAP_Notifications` (matches the plugin's expected destination name)
3. Run with `cds.environment.production.enabled: true`

## Troubleshooting

- **Port conflicts**: If port 8080 is in use, specify a different port: `mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8081"`
- **Plugin not found**: Make sure you ran `mvn install -pl cds-feature-notifications -DskipTests` from the project root first

## Advanced Topics

For advanced topics like production ANS configuration, recipient formats, dynamic priority expressions, language resolution via IAS destination, template customization, batch notifications, and outbox support, see the [main project documentation](../README.md).
