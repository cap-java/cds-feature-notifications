# Changelog

All notable changes to this project will be documented in this file.

This project adheres to [Semantic Versioning](http://semver.org/).

The format is based on [Keep a Changelog](http://keepachangelog.com/).

## Version 1.0.0 - not yet released

### Added

- Initial version of the project
- CAP Java plugin for integrating SAP Alert Notification Service (ANS) with CAP Java applications
- `@notification` annotation on CDS events to define notification types and standalone notification templates
- Support for email templates via `template.email.subject` and `template.email.html` fields
- Mustache syntax (`{{variableName}}`) for dynamic content substitution in notification templates and notification type translations
- i18n support via `{i18n>KEY}` placeholders in all template fields with automatic language detection per recipient
- Translation locales filtered per event to exclude framework-only locales (e.g. `@sap/cds/common` translations) that have no app-specific notification texts
- Automatic notification type provisioning to ANS at application startup (`NotificationTypeAutoProvisionerHandler`) using `Translations` payload to comply with ANS API spec
- Required field validation for `@notification.template.groupedTitle` annotation — throws `IllegalStateException` at startup if missing
- Re-provisioning of `NotificationType` and `NotificationTemplate` uses delete+create strategy to avoid ANS 400 rejection when existing type has `Templates` but new payload uses `Translations`, and because ANS does not provide a PATCH endpoint for templates
- Explicit `PRIVATE` visibility set on `NotificationTemplate` when `@notification.customizable` annotation is absent
- Local mode operation: logs notifications to console without requiring an ANS service binding (`LocalHandler`)
- Production mode operation: sends notifications to SAP Alert Notification Service (`ProductionHandler`)
- Mode toggled via `cds.environment.production.enabled` configuration property; defaults to local mode when property is not set or is `null`
- Programmatic notification emission by injecting the generated `NotificationService` in CAP event handlers
- Declarative notification triggering via `@notifications` annotation on CDS entities for `CREATE`, `UPDATE`, and `DELETE` events
- Support for static and dynamic notification priorities (`#LOW`, `#NEUTRAL`, `#MEDIUM`, `#HIGH`) evaluated via CDS expressions at runtime
- Conditional notification emission using `where` clauses with CDS expression language
- `parameters` field in `@notifications` for explicit entity field mapping to event properties
- Recipient support for single strings, arrays of strings, and structured recipient objects (email mapped to `RecipientId`, UUID to `GlobalUserId`)
- Batch notification processing: emit multiple notifications of the same type in a single call
- Multi-channel delivery configuration: Web and/or Email with `enabled` and `defaultPreference` flags
- Navigation target support via `@Common.SemanticObject` and `@Common.SemanticObjectAction` annotations for SAP Fiori Launchpad integration
- Recipient language resolution via Identity Authentication Service (IAS) destination `Identity_Authentication_Connectivity_IDS`
- Persistent outbox integration for guaranteed in-order delivery with automatic retry on failure
- Automatic registration of `NotificationProviderService`, `NotificationTypeProviderService`, and `NotificationTemplateProviderService` as OData v2 remote services
- Standalone notification template provisioning via `@notification.customizable` annotation for user-customizable templates
- Support for SAP ANS standalone service binding (`business-notifications` plan) and SAP Work Zone credentials
- Sample application demonstrating programmatic and declarative notification patterns with integration tests for Java 17 and 21
