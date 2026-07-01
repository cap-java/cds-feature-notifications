package customer.sample_app.handlers;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.adminservice.AdminService_;
import cds.gen.adminservice.Books;
import cds.gen.adminservice.Books_;
import cds.gen.adminservice.BooksRestockContext;

@Component
@ServiceName(AdminService_.CDS_NAME)
public class AdminServiceHandler implements EventHandler {

	@Autowired
	private PersistenceService db;

	@On(event = "restock", entity = Books_.CDS_NAME)
	public void onRestock(BooksRestockContext context) {
		Books currentBook = db.run(context.getCqn()).single(Books.class);
		String bookId = currentBook.getId();

		db.run(Update.entity("sap.capire.bookshop.Books")
				.data(Map.of("stock", context.getAmount()))
				.where(b -> b.get("ID").eq(bookId)));

		Books result = db.run(Select.from("sap.capire.bookshop.Books")
				.where(b -> b.get("ID").eq(bookId))).single(Books.class);
		context.setResult(result);
		context.setCompleted();
	}
}
