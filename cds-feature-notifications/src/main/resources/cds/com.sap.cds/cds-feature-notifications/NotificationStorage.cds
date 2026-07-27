namespace sap.cds.notifications;

@PersonalData.EntitySemantics : 'Other'
entity Notifications {
  key ID                      : UUID        not null;
  @PersonalData.FieldSemantics : 'DataSubjectID'
  key recipient               : String(254) not null;
  notificationTypeKey         : String(128);
  notificationTemplateKey     : String;
  priority                    : String(20);
  navigationTargetObject      : String(500);
  navigationTargetAction      : String(500);
  sentAt                      : Timestamp;
  properties                  : Composition of many NotificationProperties
                                  on properties.notification = $self;
  targetParameters            : Composition of many NotificationTargetParameters
                                  on targetParameters.notification = $self;
}

entity NotificationProperties {
  key notification            : Association to Notifications not null;
  key propertyKey             : String(128) not null;
  @PersonalData.FieldSemantics : 'IsPotentiallyPersonal'
  propertyValue               : String;
}

entity NotificationTargetParameters {
  key notification            : Association to Notifications not null;
  key paramKey                : String(250) not null;
  @PersonalData.FieldSemantics : 'IsPotentiallyPersonal'
  paramValue                  : String;
}
