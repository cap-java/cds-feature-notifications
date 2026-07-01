/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package customer.sample_app.config;
/*
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.sap.cds.services.application.ApplicationLifecycleService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.Before;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cloud.sdk.cloudplatform.connectivity.DefaultDestinationLoader;
import com.sap.cloud.sdk.cloudplatform.connectivity.DefaultHttpDestination;
import com.sap.cloud.sdk.cloudplatform.connectivity.DestinationAccessor;
import com.sap.cloud.sdk.cloudplatform.connectivity.OAuth2DestinationBuilder;
import com.sap.cloud.sdk.cloudplatform.connectivity.OnBehalfOf;
import com.sap.cloud.security.config.ClientCredentials;
import com.sap.cloud.security.config.ClientIdentity;

@Component
@Profile("!cloud")
@ServiceName(ApplicationLifecycleService.DEFAULT_NAME)
public class DestinationConfiguration implements EventHandler {

	@Before(event = ApplicationLifecycleService.EVENT_APPLICATION_PREPARED)
	public void registerDestination() {
        // ANS credentials
        final String clientId = "";
        final String clientSecret = "";
        final String tokenUrl = "";
        final String host = "";
        final String destinationName = "";

        ClientIdentity clientCredentials = new ClientCredentials(clientId, clientSecret);

        DefaultHttpDestination httpDestination = OAuth2DestinationBuilder
                .forTargetUrl(host)
                .withTokenEndpoint(tokenUrl)
                .withClient(clientCredentials, OnBehalfOf.TECHNICAL_USER_CURRENT_TENANT)
                .name(destinationName)
                .build();

        DestinationAccessor.prependDestinationLoader(
                new DefaultDestinationLoader().registerDestination(httpDestination));
    }
}
*/
