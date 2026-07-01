using {sap.capire.bookshop as my} from '../db/schema';

service AdminService @(requires: 'admin') {
  @notifications: [
    {
      type      : 'LowStockAlert',
      on        : ['UPDATE'],
      recipients: 'admin@example.com',
      where     : ($self.stock < 10),
      parameters: {
        bookTitle: $self.title,
        stock    : $self.stock,
      }
    },
    {
      type      : 'StockReplenished',
      on        : ['restock'],
      recipients: 'admin@example.com',
      parameters: {
        bookTitle: $self.title,
        newStock : $self.stock,
      }
    }
  ]
  entity Books   as projection on my.Books
    actions {
      @Core.OperationAvailable: true
      @Common.SideEffects: {
        TargetProperties: ['_it/stock'],
        TargetEntities  : ['_it']
      }
      action restock(amount : Integer) returns Books;
    };
  entity Authors as projection on my.Authors;
}
