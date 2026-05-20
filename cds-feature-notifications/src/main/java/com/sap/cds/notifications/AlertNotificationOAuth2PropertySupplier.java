/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package com.sap.cds.notifications;

import com.sap.cloud.sdk.cloudplatform.connectivity.DefaultOAuth2PropertySupplier;
import com.sap.cloud.sdk.cloudplatform.connectivity.ServiceBindingDestinationOptions;
import java.net.URI;

public class AlertNotificationOAuth2PropertySupplier extends DefaultOAuth2PropertySupplier {

  public AlertNotificationOAuth2PropertySupplier(ServiceBindingDestinationOptions options) {
    super(options);
  }

  @Override
  public URI getServiceUri() {
    // credentials.endpoints.notifications_url
    return getCredentialOrThrow(URI.class, "endpoints", "notifications_url");
  }
}
