/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package com.sap.cds.notifications.handlers;

import cds.gen.notificationtemplateproviderservice.NotificationTemplates;
import cds.gen.notificationtemplateproviderservice.Translations;

import com.sap.cds.notifications.builders.NotificationTemplateBuilder;
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

  private final NotificationTemplateBuilder notificationTemplateBuilder;

  public LocalNotificationTemplateAutoProvisionerHandler(CdsRuntime runtime) {
    this.notificationTemplateBuilder = new NotificationTemplateBuilder(runtime);
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
    System.out.println("\n===============================================================");
    System.out.println("Standalone NotificationTemplate (Local Mode - Not Sent to ANS)");
    System.out.println("  Key: " + template.getKey());
    System.out.println("  Visibility: " + template.getVisibility());
    System.out.println(
        "  Translations ("
            + (template.getTranslations() != null ? template.getTranslations().size() : 0)
            + "):");

    if (template.getTranslations() != null) {
      for (Translations translation : template.getTranslations()) {
        System.out.println(
            "    - Language: "
                + translation.getLanguage()
                + "\n"
                + "      Syntax: "
                + translation.getSyntax()
                + "\n"
                + "      Title: "
                + translation.getTitle()
                + "\n"
                + "      Preview: "
                + translation.getPreview()
                + "\n"
                + "      Body: "
                + translation.getBody()
                + "\n"
                + "      Description: "
                + translation.getDescription());

        if (translation.getEmail() != null) {
          System.out.println(
              "      Email Subject: "
                  + translation.getEmail().getSubject()
                  + "\n"
                  + "      Email BodyHtml: "
                  + (translation.getEmail().getBodyHtml() != null
                      ? translation
                              .getEmail()
                              .getBodyHtml()
                              .substring(
                                  0, Math.min(100, translation.getEmail().getBodyHtml().length()))
                          + "..."
                      : "null")
                  + "\n"
                  + "      Email BodyText: "
                  + translation.getEmail().getBodyText());
        }
      }
    }

    System.out.println("===============================================================\n");

    logger.info(
        "Standalone template '{}' logged (LOCAL MODE - not sent to ANS)", template.getKey());
  }
}
