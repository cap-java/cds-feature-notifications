package customer.sample_app.handlers;

import static cds.gen.catalogservice.CatalogService_.BOOKS;

import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.After;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.catalogservice.Books;
import cds.gen.catalogservice.Books_;
import cds.gen.catalogservice.BooksSubmitOrderContext;
import cds.gen.catalogservice.CatalogService_;
import cds.gen.catalogservice.OrderedBook;
import cds.gen.catalogservice.OrderedBookContext;
import cds.gen.sap.capire.bookshop.notifications.notificationservice.BookOrdered;
import cds.gen.sap.capire.bookshop.notifications.notificationservice.BookOrderedContext;
import cds.gen.sap.capire.bookshop.notifications.notificationservice.NotificationService;

@Component
@ServiceName(CatalogService_.CDS_NAME)
public class CatalogServiceHandler implements EventHandler {

	@Autowired
	private PersistenceService db;

	@Autowired
	private NotificationService.Application notificationService;

	@On(entity = Books_.CDS_NAME)
	public Books submitOrder(BooksSubmitOrderContext context) {
		// get book from bound action context
		Books book = db.run(context.getCqn()).single(Books.class);
		String bookId = book.getId();

		// decrease and update stock in database
		db.run(Update.entity(BOOKS).byId(bookId).set(b -> b.stock(), s -> s.minus(context.getQuantity())));

		// read updated stock from database
		book = db.run(Select.from(BOOKS).where(b -> b.ID().eq(bookId))).single();

		// publish CDS event
		OrderedBook orderedBook = OrderedBook.create();
		orderedBook.setBook(book.getId());
		orderedBook.setQuantity(context.getQuantity());
		orderedBook.setBuyer(context.getUserInfo().getName());

		OrderedBookContext orderedBookEvent = OrderedBookContext.create();
		orderedBookEvent.setData(orderedBook);
		context.getService().emit(orderedBookEvent);

		// Example 1: send manual notification via NotificationService
		BookOrdered notification = BookOrdered.create();
		notification.setRecipients(context.getUserInfo().getName());
		notification.setBookTitle(book.getTitle());
		notification.setQuantity(context.getQuantity());
		notification.setBuyer(context.getUserInfo().getName());

		BookOrderedContext notifContext = BookOrderedContext.create();
		notifContext.setData(notification);
		notificationService.emit(notifContext);

		return book;
	}

	@After(event = CqnService.EVENT_READ)
	public void discountBooks(Stream<Books> books) {
		books.filter(b -> b.getTitle() != null && b.getStock() != null)
		.filter(b -> b.getStock() > 200)
		.forEach(b -> b.setTitle(b.getTitle() + " (discounted)"));
	}

}
