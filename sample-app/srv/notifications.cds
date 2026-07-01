using from 'com.sap.cds/cds-feature-notifications';

namespace sap.capire.bookshop.notifications;

service NotificationService {

  /**
   * Example 1: Manual notification with i18n, HTML email, delivery channels,
   * semantic object navigation, and customizable template.
   */
  @description : '{i18n>BOOK_ORDERED_DESCRIPTION}'
  @notification: {
    customizable: true,
    template: {
      title        : '{i18n>BOOK_ORDERED_TEMPLATE_SENSITIVE}',
      publicTitle  : '{i18n>BOOK_ORDERED_TEMPLATE_PUBLIC}',
      subtitle     : '{i18n>BOOK_ORDERED_SUBTITLE}',
      groupedTitle : '{i18n>BOOK_ORDERED_TEMPLATE_GROUPED}',
      email: {
        subject: '{i18n>BOOK_ORDERED_EMAIL_SUBJECT}',
        html   : 'email-templates/book-ordered.html',
      },
    },
    deliveryChannels: [
      {
        channel           : #Mail,
        enabled           : true,
        defaultPreference : true,
      },
      {
        channel           : #Web,
        enabled           : true,
        defaultPreference : true,
      }
    ],
    priority: #HIGH,
  }
  @Common.SemanticObject      : 'Books'
  @Common.SemanticObjectAction: 'display'
  event BookOrdered {
    recipients : String;
    bookTitle  : String;
    quantity   : Integer;
    buyer      : String;
  }

  /**
   * Example 2: Low stock alert with dynamic priority using contains() function.
   * Demonstrates array of recipients — multiple emails/UUIDs in a single notification.
   * The plugin auto-detects whether each value is an email or a UUID.
   */
  @notification: {
    template: {
      title         : 'Low stock alert for "{{bookTitle}}"',
      publicTitle   : 'Low stock alert',
      subtitle      : 'Remaining stock: {{stock}}',
      groupedTitle  : '{{count}} low stock alerts',
    },
    deliveryChannels: [
      { channel: #Mail, enabled: true, defaultPreference: true }
    ],
    priority: (contains(bookTitle, 'Heights') ? 'HIGH' : (stock < 5 ? 'HIGH' : 'MEDIUM')),
  }
  event LowStockAlert {
    recipients : array of String;  // Case 2: multiple recipients (emails or UUIDs)
    bookTitle  : String;
    stock      : Integer;
  }

  /**
   * Example 3: Stock replenished notification, triggered via bound action.
   * See admin-service.cds — demonstrates @notifications annotation with
   * bound action trigger (restock).
   */
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
}
