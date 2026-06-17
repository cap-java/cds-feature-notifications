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
import java.util.List;
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
    logger.info(
        "Auto-provisioning standalone NotificationTemplates from CDS annotations"
            + " (LOCAL MODE - Logging Only)...");
    try {
      provisionNotificationTemplates();
      logger.info("Standalone NotificationTemplate auto-provisioning completed (LOCAL MODE)");
    } catch (Exception e) {
      logger.error("Standalone NotificationTemplate auto-provisioning failed", e);
    }
  }

  private void provisionNotificationTemplates() {
    List<NotificationTemplates> templates =
        notificationTemplateBuilder.buildAllNotificationTemplates();

    if (templates.isEmpty()) {
      logger.info("No standalone NotificationTemplates found in CDS model (LOCAL MODE)");
      return;
    }

    for (NotificationTemplates template : templates) {
      logTemplate(template);
    }
  }

  private void logTemplate(NotificationTemplates template) {
    logger.info("===============================================================");
    logger.info("Standalone NotificationTemplate (Local Mode - Not Sent to ANS)");
    logger.info(
        """
          Key: {}
          Visibility: {}
          Translations: {}""",
        template.getKey(),
        template.getVisibility(),
        template.getTranslations() != null ? template.getTranslations().size() : 0);

    if (template.getTranslations() != null) {
      for (Translations translation : template.getTranslations()) {
        logger.info(
            """
              - Language: {}
                Syntax: {}
                Title: {}
                Preview: {}
                Body: {}
                Description: {}""",
            translation.getLanguage(),
            translation.getSyntax(),
            translation.getTitle(),
            translation.getPreview(),
            translation.getBody(),
            translation.getDescription());

        if (translation.getEmail() != null) {
          logger.info(
              """
                Email Subject: {}
                Email BodyHtml: {}
                Email BodyText: {}""",
              translation.getEmail().getSubject(),
              translation.getEmail().getBodyHtml() != null
                  ? translation
                          .getEmail()
                          .getBodyHtml()
                          .substring(
                              0, Math.min(100, translation.getEmail().getBodyHtml().length()))
                      + "..."
                  : "null",
              translation.getEmail().getBodyText());
        }
      }
    }

    logger.info("===============================================================");
    logger.info(
        "Standalone template '{}' logged (LOCAL MODE - not sent to ANS)", template.getKey());
  }
}
