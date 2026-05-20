namespace my.bookshop;

using { managed } from '@sap/cds/common';

entity Books : managed {
  key ID : Integer;
  title  : localized String;
  stock  : Integer;
}

entity Alerts : managed {
  key ID      : Integer;
  message     : String;
  severity    : String;
  category    : String;
}