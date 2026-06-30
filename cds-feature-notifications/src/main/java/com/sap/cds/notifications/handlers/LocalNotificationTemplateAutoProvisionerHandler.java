/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package com.sap.cds.notifications.handlers;

import cds.gen.notificationtemplateproviderservice.NotificationTemplates;
import cds.gen.notificationtemplateproviderservice.Translations;
import com.sap.cds.notifications.assemblers.NotificationTemplateAssembler;
import com.sap.cds.services.application.ApplicationLifecycleService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.runtime.CdsRuntime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local-mode handler for standalone NotificationTemplate provisioning. Logs template details to
 * console instead of sending to ANS.
 */
@ServiceName(ApplicationLifecycleService.DEFAULT_NAME)
public class LocalNotificationTemplateAutoProvisionerHandler implements EventHandler {

  private static final Logger logger =
      LoggerFactory.getLogger(LocalNotificationTemplateAutoProvisionerHandler.class);

  private final NotificationTemplateAssembler notificationTemplateBuilder;

  public LocalNotificationTemplateAutoProvisionerHandler(CdsRuntime runtime) {
    this.notificationTemplateBuilder = new NotificationTemplateAssembler(runtime);
  }

  @On(event = ApplicationLifecycleService.EVENT_APPLICATION_PREPARED)
  public void onApplicationPrepared() {
    try {
      provisionNotificationTemplates();
    } catch (Exception e) {
      logger.error("Standalone NotificationTemplate auto-provisioning failed", e);
    }
  }

  private void provisionNotificationTemplates() {
    List<NotificationTemplates> templates =
        notificationTemplateBuilder.buildAllNotificationTemplates();

    if (templates.isEmpty()) {
      logger.info("No standalone NotificationTemplates found in CDS model");
      return;
    }

    logger.info("╔══════════════════════════════════════════════════════════════╗");
    logger.info("║  NOTIFICATION TEMPLATES (Local Mode — Not Sent to ANS)      ║");
    logger.info("╚══════════════════════════════════════════════════════════════╝");

    for (NotificationTemplates template : templates) {
      logTemplate(template);
    }
  }

  private void logTemplate(NotificationTemplates template) {
    int translationCount =
        template.getTranslations() != null ? template.getTranslations().size() : 0;

    logger.info("┌──────────────────────────────────────────────────────────────┐");
    logger.info(
        "│ Template: '{}' | visibility={} | translations={}",
        template.getKey(),
        template.getVisibility(),
        translationCount);

    if (template.getPropertiesSchema() != null) {
      logger.info("│ Properties: {}", extractPropertyNames(template.getPropertiesSchema()));
    }

    logger.info("├──────────────────────────────────────────────────────────────┤");

    if (template.getTranslations() != null) {
      for (Translations translation : template.getTranslations()) {
        logger.info("│  [{}]", translation.getLanguage());
        logger.info("│    title:       {}", translation.getTitle());
        logger.info("│    preview:     {}", translation.getPreview());
        logger.info("│    body:        {}", translation.getBody());
        logger.info("│    description: {}", translation.getDescription());

        if (translation.getEmail() != null) {
          logger.info("│    email subject:   {}", translation.getEmail().getSubject());
          logger.info(
              "│    email bodyText:  {}",
              translation.getEmail().getBodyText() != null
                  ? translation.getEmail().getBodyText()
                  : "(not set)");
          if (translation.getEmail().getBodyHtml() != null) {
            logger.info(
                "│    email bodyHtml:  {}",
                translation
                    .getEmail()
                    .getBodyHtml()
                    .replaceAll("<[^>]+>", " ")
                    .replaceAll("\\s+", " ")
                    .trim());
          } else {
            logger.info("│    email bodyHtml:  (not set)");
          }
        }
        logger.info("│");
      }
    }
    logger.info("└──────────────────────────────────────────────────────────────┘");
  }

  private String extractPropertyNames(String propertiesSchema) {
    List<String> names = new ArrayList<>();
    Matcher matcher = Pattern.compile("\"([^\"]+)\":\\{\"type\"").matcher(propertiesSchema);
    while (matcher.find()) {
      names.add(matcher.group(1));
    }
    return names.isEmpty() ? "(none)" : String.join(", ", names);
  }
}
