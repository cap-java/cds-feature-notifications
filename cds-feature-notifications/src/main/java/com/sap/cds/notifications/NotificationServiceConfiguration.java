/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package com.sap.cds.notifications;

import cds.gen.notificationproviderservice.NotificationProviderService;
import cds.gen.notificationproviderservice.NotificationProviderService_;
import cds.gen.notificationtemplateproviderservice.NotificationTemplateProviderService;
import cds.gen.notificationtemplateproviderservice.NotificationTemplateProviderService_;
import cds.gen.notificationtypeproviderservice.NotificationTypeProviderService;
import cds.gen.notificationtypeproviderservice.NotificationTypeProviderService_;
import com.sap.cds.notifications.handlers.EntityNotificationHandler;
import com.sap.cds.notifications.handlers.LocalHandler;
import com.sap.cds.notifications.handlers.LocalNotificationTypeAutoProvisionerHandler;
import com.sap.cds.notifications.handlers.NotificationTypeAutoProvisionerHandler;
import com.sap.cds.notifications.handlers.ProductionHandler;
import com.sap.cds.services.environment.CdsProperties;
import com.sap.cds.services.environment.CdsProperties.Remote.RemoteServiceConfig;
import com.sap.cds.services.outbox.OutboxService;
import com.sap.cds.services.runtime.CdsRuntime;
import com.sap.cds.services.runtime.CdsRuntimeConfiguration;
import com.sap.cds.services.runtime.CdsRuntimeConfigurer;
import com.sap.cds.services.utils.environment.ServiceBindingUtils;
import com.sap.cloud.environment.servicebinding.api.ServiceBinding;
import com.sap.cloud.sdk.cloudplatform.connectivity.DefaultDestinationLoader;
import com.sap.cloud.sdk.cloudplatform.connectivity.DefaultHttpDestination;
import com.sap.cloud.sdk.cloudplatform.connectivity.DestinationAccessor;
import com.sap.cloud.sdk.cloudplatform.connectivity.HttpDestination;
import com.sap.cloud.sdk.cloudplatform.connectivity.OAuth2ServiceBindingDestinationLoader;
import com.sap.cloud.sdk.cloudplatform.connectivity.OnBehalfOf;
import com.sap.cloud.sdk.cloudplatform.connectivity.ServiceBindingDestinationLoader;
import com.sap.cloud.sdk.cloudplatform.connectivity.ServiceBindingDestinationOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NotificationServiceConfiguration implements CdsRuntimeConfiguration {

  private static final Logger logger =
      LoggerFactory.getLogger(NotificationServiceConfiguration.class);

  static {
    OAuth2ServiceBindingDestinationLoader.registerPropertySupplier(
        options -> ServiceBindingUtils.matches(options.getServiceBinding(), "alert-notification"),
        AlertNotificationOAuth2PropertySupplier::new);
  }

  @Override
  public void services(CdsRuntimeConfigurer configurer) {
    // NotificationService is now defined in CDS model
  }

  @Override
  public void eventHandlers(CdsRuntimeConfigurer configurer) {

    CdsProperties.Environment environment =
        configurer.getCdsRuntime().getEnvironment().getCdsProperties().getEnvironment();

    NotificationProviderService providerSvc =
        configurer
            .getCdsRuntime()
            .getServiceCatalog()
            .getService(NotificationProviderService.class, NotificationProviderService_.CDS_NAME);

    OutboxService outbox =
        configurer
            .getCdsRuntime()
            .getServiceCatalog()
            .getService(OutboxService.class, OutboxService.PERSISTENT_ORDERED_NAME);

    NotificationTypeProviderService typeProviderSvc =
        configurer
            .getCdsRuntime()
            .getServiceCatalog()
            .getService(
                NotificationTypeProviderService.class, NotificationTypeProviderService_.CDS_NAME);

    NotificationProviderService outboxedSvc;
    if (outbox != null) {
      outboxedSvc = outbox.outboxed(providerSvc);
      logger.info(
          "NotificationProviderService outboxed via {}", OutboxService.PERSISTENT_ORDERED_NAME);
    } else {
      outboxedSvc = providerSvc;
      logger.warn(
          "OutboxService '{}' is not available. Notifications will NOT be outboxed.",
          OutboxService.PERSISTENT_ORDERED_NAME);
    }

    if (environment.getProduction().isEnabled()) {
      logger.info("Production mode enabled - using ProductionHandler");
      configurer.eventHandler(new ProductionHandler(outboxedSvc, configurer.getCdsRuntime()));
      // Register handler for auto-provisioning on application prepared event
      configurer.eventHandler(
          new NotificationTypeAutoProvisionerHandler(configurer.getCdsRuntime(), typeProviderSvc));
    } else {
      logger.info("Local mode enabled - using LocalHandler (notifications will be logged only)");
      configurer.eventHandler(new LocalHandler(configurer.getCdsRuntime()));
      // Register local handler for auto-provisioning (logging only, not sending to ANS)
      configurer.eventHandler(
          new LocalNotificationTypeAutoProvisionerHandler(configurer.getCdsRuntime()));
    }

    // Entity-level @notifications handler, emits CDS events handled by
    // ProductionHandler/LocalHandler
    configurer.eventHandler(new EntityNotificationHandler());
  }

  @Override
  public void environment(CdsRuntimeConfigurer configurer) {

    CdsRuntimeConfiguration.super.environment(configurer);

    // Get CDS runtime from the configurer
    CdsRuntime runtime = configurer.getCdsRuntime();

    // Find the service binding for alert-notification in the environment
    ServiceBinding binding =
        runtime
            .getEnvironment()
            .getServiceBindings()
            .filter(b -> b.getServiceName().get().equals("alert-notification"))
            .findFirst()
            .orElse(null);

    if (binding != null) {
      // Create a HttpDestination
      HttpDestination httpDestination =
          ServiceBindingDestinationLoader.defaultLoaderChain()
              .getDestination(
                  ServiceBindingDestinationOptions.forService(binding)
                      .onBehalfOf(OnBehalfOf.TECHNICAL_USER_CURRENT_TENANT)
                      .build());

      System.out.println("http destination is " + httpDestination);
      System.out.println("http destination url is " + httpDestination.getUri());
      System.out.println("http destination name value is" + httpDestination.get("name").get());

      // Add the destination to CDS runtime so RemoteServiceConfig can use it
      DestinationAccessor.prependDestinationLoader(
          new DefaultDestinationLoader()
              .registerDestination(
                  DefaultHttpDestination.fromDestination(httpDestination)
                      .name("SAP_Notifications")
                      .build()
                      .asHttp()));
    }

    // Define a remote service in CDS runtime that uses the SAP_Notifications destination
    RemoteServiceConfig notification = new RemoteServiceConfig();

    notification.setType("odata-v2");

    notification.getDestination().setName("SAP_Notifications");

    notification.getHttp().setSuffix("/v2");
    notification.getHttp().setService("Notification.svc");
    notification.getHttp().getCsrf().setEnabled(true);

    // Register the remote service in CDS runtime environment under the name
    // "NotificationProviderService"
    configurer
        .getCdsRuntime()
        .getEnvironment()
        .getCdsProperties()
        .getRemote()
        .getServices()
        .put("NotificationProviderService", notification);

    // Define the remote service for notification types in CDS runtime that uses the
    // SAP_Notifications destination

    RemoteServiceConfig notificationTypeConfig = new RemoteServiceConfig();

    notificationTypeConfig.setType("odata-v2");

    notificationTypeConfig.getDestination().setName("SAP_Notifications");

    notificationTypeConfig.getHttp().setSuffix("/v2");
    notificationTypeConfig.getHttp().setService("NotificationType.svc");
    notificationTypeConfig.getHttp().getCsrf().setEnabled(true);

    // Register the remote service in CDS runtime environment under the name
    // "NotificationTypeProviderService"
    configurer
        .getCdsRuntime()
        .getEnvironment()
        .getCdsProperties()
        .getRemote()
        .getServices()
        .put("NotificationTypeProviderService", notificationTypeConfig);

    // Define the remote service for notification templates in CDS runtime that uses the
    // SAP_Notifications destination
    RemoteServiceConfig notificationTemplateConfig = new RemoteServiceConfig();

    notificationTemplateConfig.setType("odata-v2");

    notificationTemplateConfig.getDestination().setName("SAP_Notifications");

    notificationTemplateConfig.getHttp().setSuffix("/odatav2");
    notificationTemplateConfig.getHttp().setService("NotificationTemplate.svc");
    notificationTemplateConfig.getHttp().getCsrf().setEnabled(true);

    // Register the remote service in CDS runtime environment under the name
    // "NotificationTemplateProviderService"
    configurer
        .getCdsRuntime()
        .getEnvironment()
        .getCdsProperties()
        .getRemote()
        .getServices()
        .put("NotificationTemplateProviderService", notificationTemplateConfig);
  }
}
