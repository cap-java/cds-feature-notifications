/* checksum : 2d433e84cc586e5858f94572091c634f */
@cds.external : true
@m.IsDefaultEntityContainer : 'true'
service NotificationProviderService {
  @cds.external : true
  @cds.persistence.skip : true
  entity Notifications {
    key Id : UUID not null;
    OriginId : String(200);
    NotificationTypeId : UUID not null;
    NotificationTypeKey : String(128) not null;
    NotificationTypeVersion : String(20);
    NotificationTemplateKey : String;
    NavigationTargetAction : String(500);
    NavigationTargetObject : String(500);
    Priority : String(20);
    Severity : String(16);
    ProviderId : String(32);
    ActorId : String(20);
    ActorType : String(20);
    ActorDisplayText : String(120);
    ActorImageURL : String(100);
    @odata.Type : 'Edm.DateTimeOffset'
    NotificationTypeTimestamp : Timestamp;
    RequiresRecipientBasedTranslation : Boolean;
    Recipients : Association to many Recipients {  };
    Properties : Association to many NotificationProperties {  };
    TargetParameters : Association to many NavigationTargetParams {  };
    Attachments : Association to many Attachments {  };
  };

  @cds.external : true
  @cds.persistence.skip : true
  entity Recipients {
    key RecipientId : String(254) not null;
    key ProviderRecipientId : String(254) not null;
    key GlobalUserId : String(36) not null;
    key NotificationId : UUID not null;
    key IasHost : String(254) not null;
    key TenantId : String(36) not null;
    key XsuaaLevel : String(254) not null;
    key RoleName : String(254) not null;
    IasGroupId : String(36);
    Language : String(5) not null;
    IasHostIdentification : Boolean;
    @Core.MediaType : 'application/octet-stream'
    blob : LargeBinary;
  };

  @cds.external : true
  @cds.persistence.skip : true
  entity NotificationProperties {
    key ![Key] : String(128) not null;
    key NotificationId : UUID not null;
    key Language : String(5) not null;
    Value : String not null;
    Type : String(20) not null;
    IsSensitive : Boolean;
    @Core.MediaType : 'application/octet-stream'
    blob : LargeBinary;
  };

  @cds.external : true
  @cds.persistence.skip : true
  entity NavigationTargetParams {
    key ![Key] : String(250) not null;
    key NotificationId : UUID not null;
    Value : String not null;
    @Core.MediaType : 'application/octet-stream'
    blob : LargeBinary;
  };

  @cds.external : true
  @cds.persistence.skip : true
  entity Attachments {
    key NotificationId : UUID not null;
    Headers : Association to AttachmentHeaders {  };
    Content : Association to AttachmentContent {  };
  };

  @cds.external : true
  @cds.persistence.skip : true
  entity AttachmentHeaders {
    key NotificationId : UUID not null;
    ContentType : String;
    ContentDisposition : String;
    ContentId : String;
  };

  @cds.external : true
  @cds.persistence.skip : true
  entity AttachmentContent {
    key NotificationId : UUID not null;
    External : Association to AttachmentContentExternal {  };
  };

  @cds.external : true
  @cds.persistence.skip : true
  entity AttachmentContentExternal {
    key NotificationId : UUID not null;
    Path : String;
  };
};

