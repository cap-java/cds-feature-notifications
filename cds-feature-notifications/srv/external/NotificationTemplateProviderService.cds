/* checksum : cac3625183c69a17811b517c0646c808 */
@cds.external : true
@m.IsDefaultEntityContainer : 'true'
service NotificationTemplateProviderService {
  @cds.external : true
  @cds.persistence.skip : true
  entity NotificationTemplates {
    key ![Key] : String not null;
    CreatedAt : Integer64;
    UpdatedAt : Integer64;
    PropertiesSchema : String;
    OwnerId : String;
    Visibility : String;
    BasedOn : String;
    SanitizeEmails : Boolean;
    Translations : Association to many Translations {  };
    Tags : Association to many Tags {  };
  };

  @cds.external : true
  @cds.persistence.skip : true
  entity Translations {
    key Language : String not null;
    Syntax : String;
    Title : String;
    Body : String;
    Preview : String;
    Email : Email;
    Description : String;
    DisplayName : String;
    Source : String;
    Event : String;
  };

  @cds.external : true
  @cds.persistence.skip : true
  entity Tags {
    key ![Key] : String not null;
    Value : String;
  };

  @cds.external : true
  type Email {
    Subject : String;
    BodyText : String;
    BodyHtml : String;
    SenderName : String;
  };
};

