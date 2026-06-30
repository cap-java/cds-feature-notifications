/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package com.sap.cds.notifications.handlers;

import cds.gen.notificationproviderservice.NotificationProperties;
import cds.gen.notificationproviderservice.Notifications;
import com.sap.cds.notifications.assemblers.NotificationAssembler;
import com.sap.cds.notifications.helpers.I18nHelper;
import com.sap.cds.reflect.CdsEvent;
import com.sap.cds.services.EventContext;
import com.sap.cds.services.cds.ApplicationService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.runtime.CdsRuntime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ServiceName(value = "*", type = ApplicationService.class)
public class LocalHandler implements EventHandler {

  private static final Logger logger = LoggerFactory.getLogger(LocalHandler.class);
  private final NotificationAssembler notificationBuilder;
  private final I18nHelper i18nHelper;

  public LocalHandler(CdsRuntime runtime) {
    this.notificationBuilder = new NotificationAssembler(runtime);
    this.i18nHelper = new I18nHelper(runtime);
  }

  @On(event = "*")
  public void postNotifications(EventContext context) {
    List<NotificationAssembler.NotificationBuildResult> results =
        notificationBuilder.buildNotifications(context);
    if (results.isEmpty()) {
      return;
    }

    for (int i = 0; i < results.size(); i++) {
      NotificationAssembler.NotificationBuildResult result = results.get(i);
      Notifications notification = result.notification();
      CdsEvent event = result.event();

      Map<String, String> props =
          notification.getProperties().stream()
              .collect(
                  Collectors.toMap(
                      NotificationProperties::getKey,
                      p -> p.getValue() != null ? p.getValue() : ""));

      String title = renderTemplate(event, "notification.template.title", props);
      String subtitle = renderTemplate(event, "notification.template.subtitle", props);
      String emailSubject = renderTemplate(event, "notification.template.email.subject", props);
      String emailBodyText = renderTemplate(event, "notification.template.email.text", props);
      String emailBodyHtml = renderTemplate(event, "notification.template.email.html", props);

      String recipientList =
          notification.getRecipients().stream()
              .map(r -> r.getRecipientId() != null ? r.getRecipientId() : r.getGlobalUserId())
              .collect(Collectors.joining(", "));

      String priority = notification.getPriority() != null ? notification.getPriority() : "NEUTRAL";

      String index = results.size() > 1 ? " (" + (i + 1) + "/" + results.size() + ")" : "";

      logger.info("┌──────────────────────────────────────────────────────────────┐");
      logger.info("│  LOCAL NOTIFICATION{} (not sent to ANS)", index);
      logger.info("├──────────────────────────────────────────────────────────────┤");
      logger.info("│  From:     noreply@notifications.local");
      logger.info("│  To:       {}", recipientList);
      logger.info(
          "│  Subject:  {}",
          emailSubject != null ? emailSubject : (title != null ? title : result.eventName()));
      logger.info("│  Priority: {}", priority);
      logger.info("├──────────────────────────────────────────────────────────────┤");
      if (subtitle != null) {
        logger.info("│  {}", subtitle);
      }
      if (emailBodyText != null || emailBodyHtml != null) {
        logger.info("│");
        logger.info("│  Email:");
        if (emailBodyText != null) {
          logger.info("│    Body (text): {}", emailBodyText);
        }
        if (emailBodyHtml != null) {
          String htmlContent =
              emailBodyHtml.endsWith(".html")
                  ? i18nHelper.loadHtmlFromClasspath(
                      emailBodyHtml, i18nHelper.getI18nTexts(Locale.ENGLISH))
                  : emailBodyHtml;
          if (htmlContent != null) {
            for (Map.Entry<String, String> entry : props.entrySet()) {
              htmlContent = htmlContent.replace("{{" + entry.getKey() + "}}", entry.getValue());
            }
            int bodyStart = htmlContent.toLowerCase().indexOf("<body");
            int bodyEnd = htmlContent.toLowerCase().indexOf("</body>");
            String bodyContent =
                (bodyStart >= 0 && bodyEnd > bodyStart)
                    ? htmlContent.substring(htmlContent.indexOf('>', bodyStart) + 1, bodyEnd)
                    : htmlContent;
            logger.info(
                "│    Body: {}",
                bodyContent.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim());
          }
        }
      }
      logger.info("│");
      logger.info("│  Notification Type: {}", result.eventName());
      if (!props.isEmpty()) {
        logger.info("│  Parameters:");
        props.forEach((key, value) -> logger.info("│    - {} = {}", key, value));
      }
      logger.info("└──────────────────────────────────────────────────────────────┘");
    }

    context.setCompleted();
  }

  private String renderTemplate(CdsEvent event, String annotationPath, Map<String, String> props) {
    Map<String, String> i18nTexts = i18nHelper.getI18nTexts(Locale.ENGLISH);
    String template = i18nHelper.resolveAnnotationValue(event, annotationPath, i18nTexts);
    if (template == null) {
      return null;
    }
    for (Map.Entry<String, String> entry : props.entrySet()) {
      template = template.replace("{{" + entry.getKey() + "}}", entry.getValue());
    }
    return template;
  }
}
