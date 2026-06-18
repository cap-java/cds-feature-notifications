/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package com.sap.cds.notifications.handlers;

import cds.gen.notificationtemplateproviderservice.NotificationTemplateProviderService;
import cds.gen.notificationtemplateproviderservice.NotificationTemplates;
import cds.gen.notificationtemplateproviderservice.NotificationTemplates_;
import com.sap.cds.notifications.assemblers.NotificationTemplateAssembler;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.application.ApplicationLifecycleService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.runtime.CdsRuntime;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provisions standalone NotificationTemplates to ANS during application startup. Runs during the
 * APPLICATION_PREPARED lifecycle event. Standalone templates are referenced by Notifications (via
 * {@code NotificationTemplateKey}).
 */
@ServiceName(ApplicationLifecycleService.DEFAULT_NAME)
public class NotificationTemplateAutoProvisionerHandler implements EventHandler {

  private static final Logger logger =
      LoggerFactory.getLogger(NotificationTemplateAutoProvisionerHandler.class);

  private final NotificationTemplateAssembler notificationTemplateBuilder;
  private final NotificationTemplateProviderService notificationTemplateProviderService;

  public NotificationTemplateAutoProvisionerHandler(
      CdsRuntime runtime, NotificationTemplateProviderService notificationTemplateProviderService) {
    this.notificationTemplateBuilder = new NotificationTemplateAssembler(runtime);
    this.notificationTemplateProviderService = notificationTemplateProviderService;
  }

  @On(event = ApplicationLifecycleService.EVENT_APPLICATION_PREPARED)
  public void onApplicationPrepared() {
    logger.info("Auto-provisioning standalone NotificationTemplates from CDS annotations...");
    try {
      provisionNotificationTemplates();
      logger.info("Standalone NotificationTemplate auto-provisioning completed");
    } catch (IllegalStateException e) {
      // Developer mistake (e.g. malformed template) — fail hard
      throw e;
    } catch (Exception e) {
      // Transient error (network, ANS down) — log and continue
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

    // Fetch all existing templates from ANS to determine create vs update
    Set<String> existingTemplateKeys = fetchExistingTemplateKeys();
    logger.debug("Found {} existing standalone templates in ANS", existingTemplateKeys.size());

    for (NotificationTemplates template : templates) {
      String key = template.getKey();

      if (existingTemplateKeys.contains(key)) {
        updateTemplate(template);
      } else {
        createTemplate(template);
      }
    }
  }

  /** Fetch all existing template keys from ANS. */
  private Set<String> fetchExistingTemplateKeys() {
    try {
      return notificationTemplateProviderService
          .run(Select.from(NotificationTemplates_.class).columns(nt -> nt.Key()))
          .streamOf(NotificationTemplates.class)
          .map(NotificationTemplates::getKey)
          .filter(Objects::nonNull)
          .collect(Collectors.toSet());
    } catch (Exception e) {
      logger.warn("Could not fetch existing standalone templates from ANS: {}", e.getMessage());
      return Collections.emptySet();
    }
  }

  private void createTemplate(NotificationTemplates template) {
    try {
      notificationTemplateProviderService.run(
          Insert.into(NotificationTemplates_.CDS_NAME).entry(template));

      logger.debug(
          "Standalone NotificationTemplate '{}' created in ANS successfully", template.getKey());
    } catch (Exception e) {
      String errorMsg = e.getMessage() != null ? e.getMessage() : "";

      if (errorMsg.contains("409")) {
        // Race condition: template was created between our GET and INSERT
        logger.debug(
            "Standalone template '{}' was created concurrently (409). Attempting update...",
            template.getKey());
        updateTemplate(template);
        return;
      }

      if (errorMsg.contains("400")) {
        logger.error(
            "ANS rejected standalone template '{}' with 400 Bad Request. "
                + "Check that all required fields (Title in Translation) are set. Error: {}",
            template.getKey(),
            errorMsg);
        throw new IllegalStateException(
            String.format(
                "ANS rejected standalone template '%s' with 400 Bad Request. "
                    + "Ensure @notification.template annotations are properly configured. Error: %s",
                template.getKey(), errorMsg),
            e);
      }

      logger.error("Failed to create standalone template '{}' in ANS", template.getKey(), e);
      throw e;
    }
  }

  private void updateTemplate(NotificationTemplates template) {
    logger.debug("Updating standalone template '{}'", template.getKey());

    try {
      notificationTemplateProviderService.run(
          Update.entity(NotificationTemplates_.CDS_NAME).data(template));

      logger.debug(
          "Standalone NotificationTemplate '{}' updated in ANS successfully", template.getKey());
    } catch (Exception e) {
      String errorMsg = e.getMessage() != null ? e.getMessage() : "";

      if (errorMsg.contains("400")) {
        logger.error(
            "ANS rejected standalone template update '{}' with 400 Bad Request. Error: {}",
            template.getKey(),
            errorMsg);
        throw new IllegalStateException(
            String.format(
                "ANS rejected standalone template update '%s' with 400 Bad Request. Error: %s",
                template.getKey(), errorMsg),
            e);
      }

      logger.error("Failed to update standalone template '{}' in ANS", template.getKey(), e);
      throw e;
    }
  }
}
