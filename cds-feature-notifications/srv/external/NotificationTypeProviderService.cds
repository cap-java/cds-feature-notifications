/* checksum : fd665da2456de29e0ea4c6fe54baa0c8 */
@cds.external : true
@m.IsDefaultEntityContainer : 'true'
service NotificationTypeProviderService {
  @cds.external : true
  @cds.persistence.skip : true
  entity NotificationTypes {
    key NotificationTypeId : UUID not null;
    NotificationTypeKey : String(128) not null;
    NotificationTypeVersion : String(20) not null;
    IsGroupable : Boolean;
    UserPreferencesVisibility : String(20) not null;
    SanitizeEmails : Boolean;
    CategoryKey : String(64);
    Translations : Association to many Translations {  };
    Templates : Association to many Templates {  };
    Actions : Association to many Actions {  };
    DeliveryChannels : Association to many DeliveryChannels {  };
  };

  @cds.external : true
  @cds.persistence.skip : true
  entity Translations {
    key NotificationTypeId : UUID not null;
    key Language : String(20) not null;
    Syntax : String(20) not null;
    GroupTitle : String(256) not null;
    DisplayName : String(256) not null;
    Description : String(256);
  };

  @cds.external : true
  @cds.persistence.skip : true
  entity Templates {
    key NotificationTypeId : UUID not null;
    key Language : String(20) not null;
    TemplatePublic : String(250) not null;
    TemplateSensitive : String(250) not null;
    TemplateGrouped : String(250) not null;
    Description : String(256);
    TemplateLanguage : String(20);
    Subtitle : String(5000);
    EmailSubject : String(250);
    EmailText : LargeString;
    EmailHtml : LargeString;
    EmailSenderName : String(128);
  };

  @cds.external : true
  @cds.persistence.skip : true
  entity Actions {
    key ActionId : String(32) not null;
    key Language : String(20) not null;
    key NotificationTypeId : UUID not null;
    ActionText : String(40) not null;
    GroupActionText : String(40) not null;
    Nature : String(20) not null;
  };

  @cds.external : true
  @cds.persistence.skip : true
  entity DeliveryChannels {
    key Type : String not null;
    Enabled : Boolean;
    DefaultPreference : Boolean;
    EditablePreference : Boolean;
    Configuration : Association to Configuration {  };
  };

  @cds.external : true
  @cds.persistence.skip : true
  entity Configuration {
    key Type : String not null;
    IncludeNavigationProperties : Association to many IncludeNavigationProperties {  };
    IncludeNavigationTargetParameters : Association to many IncludeNavigationTargetParameters {  };
  };

  @cds.external : true
  @cds.persistence.skip : true
  entity IncludeNavigationTargetParameters {
    key ![Key] : String not null;
  };

  @cds.external : true
  @cds.persistence.skip : true
  entity IncludeNavigationProperties {
    key ![Key] : String not null;
  };
};

