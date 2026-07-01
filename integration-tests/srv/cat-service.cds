using my.bookshop as my from '../db/data-model';

service CatalogService {
    @notifications : [
      {
        type       : 'CertificateExpiration',
        on         : ['CREATE'],
        recipients : $self.createdBy,
        where      : ($self.stock > 50),
        parameters : {
          name              : $self.createdBy,
          certificateName   : $self.title,
          year              : $self.stock
        }
      },
      {
        type       : 'CertificateExpiration',
        on         : ['restock'],
        recipients : $self.createdBy,
        parameters : {
          name              : $self.createdBy,
          certificateName   : $self.title,
          year              : $self.stock
        }
      },
      {
        type       : 'CertificateExpiration',
        on         : ['UPDATE'],
        recipients : ['ops-team@example.com', '550e8400-e29b-41d4-a716-446655440000'],
        parameters : {
          name              : $self.createdBy,
          certificateName   : $self.title,
          year              : $self.stock
        }
      },
      {
        type       : 'CertificateExpiration',
        on         : ['CREATE'],
        recipients : 'java-team@example.com',
        where      : (contains($self.title, 'Java')),
        parameters : {
          name              : $self.createdBy,
          certificateName   : $self.title,
          year              : $self.stock
        }
      }
    ]
    entity Books as projection on my.Books
      actions {
        action restock(amount : Integer) returns Books;
      };

    @notifications : [
      {
        type       : 'SecurityAlert',
        on         : ['CREATE'],
        recipients : 'temporal-team@example.com',
        where      : ($self.createdAt < $now),
        parameters : {
          severity    : $self.severity,
          alertSource : $self.message
        }
      },
      {
        type       : 'SecurityAlert',
        on         : ['CREATE'],
        recipients : 'null-term-test@example.com',
        where      : (contains($self.message, $self.category, true)),
        parameters : {
          severity    : $self.severity,
          alertSource : $self.message
        }
      }
    ]
    entity Alerts as projection on my.Alerts;
}