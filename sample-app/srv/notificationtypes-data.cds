namespace my.notifications;

using from 'com.sap.cds/cds-feature-notifications';

/*
@notificationType: {
  templatePublic: 'New book order',
  templateSensitive: 'Order details: {{title}}',
  templateGrouped: '{{count}} new book orders',
  description: 'There is a new book order',
  subtitle: 'Book Order',
  emailSubject: 'Book Order',
  emailText: 'Book has been ordered.',
  deliveryChannels: [
    {
      type: #MAIL, 
      enabled: true, 
      defaultPreference: true,
    }
  ],
  priority: #HIGH
   //priority : (title = 'ABC' ? #High : #Low), // #Medium, #Neutral, #Low
}
event BookOrder { //The event name represents the notification type key
   // Recipients should automatically be added by the plugin to avoid need to be specified by the app
  recipients: String;
  title  : String(255);
  creator : String(100);
}
*/

/* @notificationType: {
  templatePublic: 'Certificate Expiry',
  templateSensitive: 'Certificate: {{certName}}',
  templateGrouped: ' certificates expiring',
  description: 'Certificate {{certName}} expires on {{expDate}}',
  subtitle: 'Certificate Expiration',
  emailSubject: 'Certificate Expiration',
  emailHtml: '<!DOCTYPE html><html><head><style>body{font-family:Arial,sans-serif;background-color:#f4f4f4;margin:0;padding:0}.container{max-width:600px;margin:40px auto;background:#fff;border-radius:8px;box-shadow:0 2px 8px rgba(0,0,0,0.1);overflow:hidden}.header{background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);color:#fff;padding:30px;text-align:center}.header h1{margin:0;font-size:28px}.content{padding:30px}.warning-box{background:#fff3cd;border-left:4px solid #ffc107;padding:15px;margin:20px 0;border-radius:4px}.warning-box strong{color:#856404}.cert-details{background:#f8f9fa;padding:20px;border-radius:6px;margin:20px 0}.cert-details p{margin:10px 0;font-size:14px}.cert-details strong{color:#495057}.btn{display:inline-block;padding:12px 30px;background:#667eea;color:#fff;text-decoration:none;border-radius:5px;margin-top:20px;font-weight:bold}.footer{background:#f8f9fa;padding:20px;text-align:center;font-size:12px;color:#6c757d}</style></head><body><div class="container"><div class="header"><h1>🔐 Certificate Expiration Alert</h1></div><div class="content"><p>Dear Administrator,</p><div class="warning-box"><strong>⚠️ Action Required:</strong> A certificate in your system is approaching expiration.</div><div class="cert-details"><p><strong>Certificate Name:</strong> {{certName}}</p><p><strong>Expiration Date:</strong> {{expDate}}</p><p><strong>Status:</strong> <span style="color:#dc3545">Expiring Soon</span></p></div><p>To maintain security and avoid service disruptions, please renew this certificate before the expiration date.</p><a href="#" class="btn">Renew Certificate</a></div><div class="footer"><p>This is an automated notification from your Certificate Management System.</p><p>© 2026 Your Organization. All rights reserved.</p></div></div></body></html>',
  /* deliveryChannels: [
    {
      type: #MAIL, 
      enabled: true, 
      defaultPreference: true,
    },
    {
      type: #WEB, 
      enabled: true, 
      defaultPreference: true,
    }
  ], //deliverychannel optional
  priority: #HIGH
   //priority : (title = 'ABC' ? #High : #Low), // #Medium, #Neutral, #Low
}
event CertificateExpiration { //The event name represents the notification type key
   // Recipients should automatically be added by the plugin to avoid need to be specified by the app
  recipients: String;
  certName: String;  
  count: Integer;                 
  expDate: Date;
} */

//-------------------------------------------------------------
/* @description : '{i18n>DESCRIPTION}' //Label in WZ configuration UI for notification type
@notification : {
   template: {
      title : '{i18n>TEMPLATE_SENSITIVE}', // Properties of event are used as Mustache rendering context
      publicTitle : '{i18n>TEMPLATE_PUBLIC}',
      subtitle : '{i18n>SUBTITLE}',
      groupedTitle : '{i18n>TEMPLATE_GROUPED}',
      // Optional - plugin / ANS needs to offer post processing capabilities to have a generic email template for all notifications
      email : {
         subject : '{i18n>EMAIL_SUBJECT}',
         html: '{i18n>EMAIL_HTML}',
      },
   },
   // Optional - by default Mail and Web should be enabled
   deliveryChannels: [
    {
      channel: #Mail,
      enabled: true,
      defaultPreference: true
      // ... other properties from ANS delivery channels
    }
   ],
   // Prio is a Notification property, thus xpr as anno should be supported to dynamically determine the priority
   priority : #HIGH, // #Medium, #Neutral, #Low
}
event CertificateExpiration { //The event name represents the notification type key
   // Recipients should automatically be added by the plugin to avoid need to be specified by the app
  recipients: String;
  certName: String;  
  count: Integer;                 
  expDate: Date;
} */

/*
@notificationType: {
  templatePublic: 'Collaboration Approval Needed',
  templateSensitive: 'Action Required: Approve Collaboration',
  templateGrouped: '{{count}} certificates expiring', 
  description: 'A collaboration request is waiting for your approval',
  subtitle: 'Collaboration Approval',
  emailSubject: 'Collaboration Approval Request {{test}}',
  emailHtml: '<html><body><h1>Collaboration Approval</h1><p>Please review and approve the collaboration request.</p></body></html>',
  emailText: 'Collaboration Approval Request - Please review',
  deliveryChannels: [
    {
      type: #MAIL, 
      enabled: true, 
      defaultPreference: true,
    }
  ],
  priority: #HIGH
   //priority : (title = 'ABC' ? #High : #Low), // #Medium, #Neutral, #Low
}
event ApproveCollaboration { //The event name represents the notification type key
   // Recipients should automatically be added by the plugin to avoid need to be specified by the app
   recipients: String;
   test: String;
}

// Predeliver Recipients with plugin
type Recipients {
   RecipientId: String;
   GlobalUserUUID: String
}
*/

// Type definitions
type DeliveryChannelType : String enum {
  MAIL; WEB; MOBILE;
}

// Priority values: LOW, NEUTRAL, MEDIUM, HIGH (validated at runtime by the plugin)

// Notification Service
service NotificationService {
  
  @description : '{i18n>DESCRIPTION}'
  @UI.AdaptationHidden: false
  @notification : {
     template: {
        title : '{i18n>TEMPLATE_SENSITIVE}',
        publicTitle : '{i18n>TEMPLATE_PUBLIC}',
        subtitle : '{i18n>SUBTITLE}',
        groupedTitle : '{i18n>TEMPLATE_GROUPED}',
        email : {
           subject : '{i18n>EMAIL_SUBJECT}',
           html: 'email-templates/certificate-expiration.html',
        },
     },
     deliveryChannels: [
      {
        channel: #Mail,
        enabled: true,
        defaultPreference: true
      }
     ],
     priority : (year > 2025 ? 'HIGH' : 'LOW'), 
  }
  @Common.SemanticObject : 'project1'
  @Common.SemanticObjectAction : 'display'
  // Case 1: String — single recipient (auto-detected as email or UUID)
  event CertificateExpiration {
    recipients: String;
    name: String;
    certificateName: String;                  
    expirationDate: Date;
    renewLink: String;
    year: Integer;
    companyName: String;
  }

  // Case 2: array of String — multiple recipients (auto-detected as email or UUID)
  @notification : {
     template: {
        title : 'System maintenance scheduled for {{systemName}}',
        publicTitle : 'Maintenance Notice',
        subtitle : 'Maintenance: {{maintenanceWindow}} — Impact: {{impact}}',
        groupedTitle : 'Maintenance Notices',
     },
     priority : (contains(impact, 'critical') ? 'HIGH' : 'MEDIUM'),
  }
  event SystemMaintenance {
    recipients: array of String;
    systemName: String;
    maintenanceWindow: String;
    impact: String;
  }

  // Case 3: Date/time function in dynamic priority (days_between)
  @notification : {
     template: {
        title : 'Contract {{contractName}} deadline approaching',
        publicTitle : 'Contract Deadline',
        subtitle : 'Contract: {{contractName}} — Party: {{counterparty}}',
        groupedTitle : 'Contract Deadlines',
     },
     priority : (days_between($now, deadlineDate) < 30 ? 'HIGH' : 'LOW'),
  }
  event ContractDeadline {
    recipients: String;
    contractName: String;
    deadlineDate: Timestamp;
    counterparty: String;
  }

  // Case 4: startsWith() string function in dynamic priority
  @notification : {
     template: {
        title : 'Security Alert: {{alertSource}}',
        publicTitle : 'Security Alert',
        subtitle : 'Severity: {{severity}} — Source: {{alertSource}}',
        groupedTitle : 'Security Alerts',
     },
     priority : (startsWith(severity, 'CRIT') ? 'HIGH' : 'LOW'),
  }
  event SecurityAlert {
    recipients: String;
    severity: String;
    alertSource: String;
    description: String;
  }

  // Case 5: endsWith() string function in dynamic priority
  @notification : {
     template: {
        title : 'Server Incident on {{serverName}}',
        publicTitle : 'Server Incident',
        subtitle : 'Server: {{serverName}} — Type: {{incidentType}}',
        groupedTitle : 'Server Incidents',
     },
     priority : (endsWith(serverName, '-prod') ? 'HIGH' : 'LOW'),
  }
  event ServerIncident {
    recipients: String;
    serverName: String;
    incidentType: String;
    description: String;
  }

  // Case 6: Nested function in containment — contains(concat(...))
  @notification : {
     template: {
        title : 'Deployment {{environment}}/{{appName}} completed',
        publicTitle : 'Deployment Notification',
        subtitle : 'App: {{appName}} — Env: {{environment}}',
        groupedTitle : 'Deployment Notifications',
     },
     priority : (contains(concat(environment, '-', appName), 'PROD-critical') ? 'HIGH' : 'LOW'),
  }
  event DeploymentNotification {
    recipients: String;
    environment: String;
    appName: String;
    version: String;
  }

  // Future notification events here
}

