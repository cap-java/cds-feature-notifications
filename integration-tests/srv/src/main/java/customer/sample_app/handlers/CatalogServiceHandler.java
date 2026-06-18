/*
 * © 2026 SAP SE or an SAP affiliate company and cds-feature-notifications contributors.
 */
package customer.sample_app.handlers;

import cds.gen.catalogservice.Books;
import cds.gen.catalogservice.BooksRestockContext;
import cds.gen.catalogservice.Books_;
import cds.gen.catalogservice.CatalogService_;
import cds.gen.my.notifications.notificationservice.CertificateExpiration;
import cds.gen.my.notifications.notificationservice.CertificateExpirationContext;
import cds.gen.my.notifications.notificationservice.NotificationService;
import com.sap.cds.ql.Update;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.After;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.persistence.PersistenceService;
import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
@ServiceName(CatalogService_.CDS_NAME)
public class CatalogServiceHandler implements EventHandler {

  @Autowired private NotificationService.Application notificationService;

  @Autowired
  @Qualifier(CatalogService_.CDS_NAME)
  private CqnService catalogService;

  @Autowired private PersistenceService db;

  @After(event = CqnService.EVENT_READ)
  public void discountBooks(Stream<Books> books) {
    books
        .filter(b -> b.getTitle() != null && b.getStock() != null)
        .filter(b -> b.getStock() > 200)
        .forEach(b -> b.setTitle(b.getTitle() + " (discounted)"));

    // Create CertificateExpiration event
    CertificateExpiration certificateExpiration = CertificateExpiration.create();
    certificateExpiration.setRecipients("buse.halis@sap.com");
    certificateExpiration.setName("user");
    certificateExpiration.setCertificateName("Cert");
    certificateExpiration.setExpirationDate(LocalDate.of(2026, 3, 15));
    certificateExpiration.setRenewLink("https://example.com/renew-certificate");
    certificateExpiration.setYear(2026);
    certificateExpiration.setCompanyName("SAP");

    // Create context and set data
    CertificateExpirationContext eventCtx = CertificateExpirationContext.create();
    eventCtx.setData(certificateExpiration);

    // Emit event through CDS-defined NotificationService
    notificationService.emit(eventCtx);
  }

  @On(event = "restock", entity = Books_.CDS_NAME)
  public void onRestock(BooksRestockContext context) {
    Integer amount = context.getAmount();

    // Use PersistenceService (not CatalogService) to avoid triggering READ handlers
    Books currentBook = db.run(context.getCqn()).single(Books.class);
    Integer bookId = currentBook.getId();

    // Update stock in DB (returns only affected row count, not entity data)
    db.run(
        Update.entity("my.bookshop.Books")
            .data(Map.of("stock", amount))
            .where(b -> b.get("ID").eq(bookId)));

    // Read back updated book with all fields for EntityNotificationHandler
    Books result =
        db.run(com.sap.cds.ql.Select.from("my.bookshop.Books").where(b -> b.get("ID").eq(bookId)))
            .single(Books.class);
    context.setResult(result);
    context.setCompleted();
  }
}
