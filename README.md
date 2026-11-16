---
title: Provisioning Extension Microsoft Entra ID
---

# daccord Provisioning Extension – Microsoft Entra ID

Der TaskHandler setzt Provisionierungsaufträge aus daccord in **Microsoft Entra ID** um. Er liest den Auftrag, die Person und die ProvisioningConfiguration, bildet daraus Kontoname, Kennwort und die Graph-Attribute und schickt sie über den JavaConnector an die **daccord JavaConnector Extension – Microsoft Entra ID**, die mit Microsoft Graph spricht.

Version 2.0 ist für **daccord 2.3** und gehört zur JavaConnector Extension **daccord-javaconnector-entraid-extension 2.0**; beide werden zusammen ausgeliefert. Die Fachlogik von Entra ID (Strategien, synchronisierte Konten, Standardwerte) liegt in der JavaConnector Extension, der TaskHandler liefert die Werte.

## Inhalt

- [Funktionsumfang](#funktionsumfang)
- [Installation in daccord](#installation-in-daccord)
- [ProvisioningConfiguration](#provisioningconfiguration)
- [Aufträge](#aufträge)
- [Übergabe an den Connector](#übergabe-an-den-connector)
- [Testen](#testen)
- [Fehlerbehandlung](#fehlerbehandlung)
- [Technik](#technik)
- [Bauen](#bauen)

## Funktionsumfang

- Alle 19 Aktionen für Konten, Gruppen und Mitgliedschaften
- **Kontoname** aus Antrag, Auftrag oder generiert aus Vor- und Nachname (eindeutig über mehrere Systeme)
- **Initialkennwort** nach Strategie: aus Auftrag, Person, Konfiguration, Antrag, generiert oder keines
- **Kontotyp:** Service-Konten brauchen einen beantragten Namen
- **Weitere Attribute:** jedes `object_*`-Feld des Auftrags wird als gleichnamiges Graph-Attribut übernommen
- **Custom Commands:** Befehl und alle Antragswerte werden an die JavaConnector Extension durchgereicht

## Installation in daccord

1. Das JAR `daccord-provisioning-entraid-extension-2.0.x.jar` nach `/opt/GuH/daccord-provisioning-gadget/res/extensions/` kopieren (Eigentümer wie die übrigen Dateien der RuntimeEngine) und die RuntimeEngine neu starten, damit das Provisioning-Gadget den TaskHandler lädt (ein Neustart nur des Gadgets ist derzeit nicht möglich).
2. Die JavaConnector Extension Entra ID installieren und einen Collector dafür anlegen, siehe deren README.
3. Eine ProvisioningConfiguration anlegen, mindestens mit:

| Feld | Wert |
|---|---|
| `type` | `automatic` |
| `class` | `de.guh.extension.provisioning.entraid.EntraIDProvisioningTaskHandler` (ohne `.java`) |
| `collectorid` | ID des Entra ID-Collectors |

## ProvisioningConfiguration

| Schlüssel | Standard | Wirkung |
|---|---|---|
| `collectorid` | – | Collector, über dessen Connector gesendet wird (Pflicht) |
| `domainname` | Tenant-Domain der Verbindung (`gp_param3`) | Domain des `userPrincipalName` |
| `sharing_strategy` | `standalone` | `standalone`, `shared` oder `unique`, siehe README der JavaConnector Extension |
| `upn_uniqueness_limit` | `20` | bei `unique`: Anzahl der Versuche |
| `accountname_strategy` | `GIVENNAMEFIRST` | Kontoname aus Vor- und Nachname: `GIVENNAMEFIRST`, `SURNAMEFIRST` oder `RANDOMNUMBER` |
| `accountnameuniqueness_systemids` | dieses System | Systeme, in denen der generierte Name eindeutig sein muss |
| `usertype` | `Member` | `userType` neuer Konten: `Member` oder `Guest` |
| `initial_accountenabled` | `true` (in der JavaConnector Extension) | neue Konten aktiv oder gesperrt |
| `password_strategy` | `fromtask` | siehe unten |
| `initialpassword` | – | Kennwort für `fromconfiguration`, Rückfall für `fromperson`/`fromrequest` |
| `password_generate_length`, `password_generate_regex` | – | für `generate`: Länge und erlaubte Zeichen (Regex Base64-kodiert) |
| `password_forcechangenextsignin` | `true` | Kennwort bei der nächsten Anmeldung ändern |
| `mailenabled`, `securityenabled` | `false`, `true` (in der JavaConnector Extension) | Art neuer Gruppen |
| `syncedaccount_policy` | `skipattributes` | Umgang mit aus AD synchronisierten Konten: `skipattributes`, `fail`, `ignore` |
| `syncedaccount_cloudattributes` | `usageLocation,preferredLanguage` | Felder, die an synchronisierten Konten trotzdem geschrieben werden |

### Kennwort (`password_strategy`)

| Strategie | Quelle |
|---|---|
| `fromtask` | `object_password` des Auftrags |
| `fromperson` | Initialkennwort der Person, sonst `initialpassword` |
| `fromconfiguration` | `initialpassword` |
| `fromrequest` | `request_initialpassword`, sonst Person, sonst `initialpassword` |
| `generate` | generiert nach `password_generate_length` und `password_generate_regex` |
| `none` | kein Kennwort; die JavaConnector Extension erzeugt eines |

Das Kennwort wird verschlüsselt übertragen. Schlägt die Verschlüsselung fehl, bricht der Auftrag ab – es wird nie im Klartext gesendet.

## Aufträge

Bei jedem Wert gilt: Antrag (`request_*`) vor Person vor Auftrag (`object_*`).

| Aktion | gelesen | an Entra ID |
|---|---|---|
| addAccount | Vor-/Nachname, `request_name`/`object_username`, `request_type`/`object_type`, Kennwort, weitere `object_*` | `userPrincipalName`, `givenName`, `surname`, `userType`, `accountEnabled`, Kennwort, weitere Attribute |
| modifyAccount | `mail`, `jobtitle`, Vor-/Nachname, `object_syncenabled`, weitere `object_*` | geänderte Attribute |
| modifyAccountPassword | `object_password` | neues Kennwort |
| modifyAccountStatus | `request_status` (`active`/`inactive`), sonst `object_accountEnabled` | `accountEnabled` |
| modifyAccountType | `request_type`/`object_type` (Pflicht) | `userType` (`Member`/`Guest`) |
| renameAccount | `request_name`, `request_displayname`, Vor-/Nachname | `mailNickname`, `userPrincipalName`, `displayName` |
| moveAccount | `department`, `officeLocation`, weitere `object_*` | Organisationsfelder (Entra ID kennt keine OUs) |
| removeAccount, reconcileAccount | `object_uniqueid` | |
| addSecurityObject, modifySecurityObject, renameSecurityObject | `request_name`/`object_name`, `description`, weitere `object_*` | `displayName`, `description`, Attribute |
| moveSecurityObject | wie modifySecurityObject | |
| removeSecurityObject, reconcileSecurityObject | `object_uniqueid` | |
| addPermission, removePermission | IDs von Konto und Gruppe | Mitgliedschaft |
| customAccountCommand, customSecurityObjectCommand | `request_command`/`object_command`, alle `request_*` | Befehl der JavaConnector Extension |

Ein Konto, das gerade erst angelegt und noch nicht eingesammelt wurde, hat in daccord noch keine ID. Für Mitgliedschaften wird sie dann aus der `globaluniqueid` (`<systemid>-<id>`) abgeleitet, damit die Berechtigung sofort vergeben werden kann.

Leere Werte werden nicht übertragen. Ein Feld in Entra ID zu **leeren** ist derzeit nicht möglich.

## Übergabe an den Connector

Der TaskHandler schickt zwei Arten von Werten an den JavaConnector:

- **IDs und Steuerwerte** als benannte Variablen, z.B. `accountuniqueid`, `strategy`, `domainname`, `upnbase`, `syncedaccount_policy`, `command`. Die JavaConnector Extension liest sie als `pp_config_<name>`.
- **Graph-Attribute** im Daten-XML, z.B. `givenName`, `department`, `PASSWORD`. Sie gehen unverändert an Graph.

Dafür braucht es den **JavaConnector ab daccord 2.3**, der alle Variablen weitergibt.

## Testen

Der Test läuft mit dem `-runnable.jar` gegen ein echtes daccord-2.3-System, dessen JavaConnector mit der JavaConnector Extension Entra ID und einen **Test-Tenant** – **nicht** im Projektverzeichnis. Der TaskHandler wird genau so aufgerufen wie vom Provisioning-Gadget.

### Voraussetzungen

| Was | Woher |
|---|---|
| daccord-2.3-System mit JavaConnector 2.3, darin die JavaConnector Extension Entra ID 2.0 und ein Collector auf den Test-Tenant | Collector-ID und System-ID notieren; den Collector vorher mit `connect` prüfen |
| `config.xml` des Provisioning-Gadgets | `/opt/GuH/daccord-provisioning-gadget/conf/config.xml` (Datenbankzugang, `decryptionhash`) |
| Ressourcenverzeichnis des Provisioning-Gadgets | `/opt/GuH/daccord-provisioning-gadget/res` (Umlaut-Umwandlung bei generierten Namen) |
| Netzwerkzugang vom Testrechner | zur daccord-Datenbank und zur REST-URL des JavaConnectors |
| Prüfung in Entra ID | Entra Admin Center oder Graph Explorer |

### Vorbereitung

```bash
mvn clean package
mkdir -p ~/provtest-entraid && cp examples/*.xml ~/provtest-entraid/
cp target/daccord-provisioning-entraid-extension-2.0.0-runnable.jar ~/provtest-entraid/
scp <daccord-server>:/opt/GuH/daccord-provisioning-gadget/conf/config.xml ~/provtest-entraid/
cd ~/provtest-entraid
```

In **jeder** Auftragsdatei `YOUR_SYSTEM_ID`, `YOUR_COLLECTOR_ID` und – nach dem Anlegen – `YOUR_USER_OBJECT_ID` bzw. `YOUR_GROUP_OBJECT_ID` (aus der Ausgabe) ersetzen.

### Aufruf

```bash
java -Dlog4j2.level=DEBUG -jar daccord-provisioning-entraid-extension-2.0.0-runnable.jar \
  <aktion> /opt/GuH/daccord-provisioning-gadget/res config.xml <auftrag>.xml
```

Erfolg: `SUCCESS! Operation completed.` und `Object Unique ID: …`, Exit-Code 0. Fehler: `FAILED! Operation failed.` mit der Fehlermeldung, Exit-Code 1. Die Antwort von Graph steht im Log des JavaConnectors.

### Testablauf

| # | Aktion | Datei | Erwartet | Prüfen in Entra ID |
|---|---|---|---|---|
| 1 | `addaccount` | `account-create.xml` | SUCCESS, Object-ID | Benutzer mit generiertem Namen, `userType` Member, `department` IT → Object-ID eintragen |
| 2 | `modifyaccount` | `account-modify.xml` | SUCCESS | `jobTitle`, `officeLocation` |
| 3 | `modifyaccountpassword` | `account-password.xml` | SUCCESS | Anmeldung mit neuem Kennwort |
| 4 | `modifyaccountstatus` | `account-status-disable.xml` | SUCCESS | Konto gesperrt |
| 5 | `modifyaccountstatus` | `account-status-enable.xml` | SUCCESS | Konto aktiv |
| 6 | `modifyaccounttype` | `account-type.xml` | SUCCESS | `userType` Guest |
| 7 | `renameaccount` | `account-rename.xml` | SUCCESS | `mailNickname`, `userPrincipalName`, `displayName` neu |
| 8 | `moveaccount` | `account-move.xml` | SUCCESS | `department`, `officeLocation` |
| 9 | `reconcileaccount` | `account-reconcile.xml` | SUCCESS | – |
| 10 | `customaccountcommand` | `account-command.xml` | SUCCESS | Authentifizierungsmethoden des Benutzers |
| 11 | `addsecurityobject` | `securityobject-create.xml` | SUCCESS, Object-ID | Gruppe vorhanden → Object-ID eintragen |
| 12 | `addpermission` | `permission-create.xml` | SUCCESS | Benutzer ist Mitglied |
| 13 | `removepermission` | `permission-delete.xml` | SUCCESS | Mitgliedschaft entfernt |
| 14 | `modifysecurityobject` | `securityobject-modify.xml` | SUCCESS | `description` |
| 15 | `renamesecurityobject` | `securityobject-rename.xml` | SUCCESS | `displayName` |
| 16 | `movesecurityobject` | `securityobject-move.xml` | SUCCESS | `description` |
| 17 | `reconcilesecurityobject` | `securityobject-reconcile.xml` | SUCCESS | – |
| 18 | `customsecurityobjectcommand` | `securityobject-command.xml` | FAILED `ERROR_GRAPHREQUEST_DISABLED`, mit `gp_param11 = true` am Collector SUCCESS | – |
| 19 | `removesecurityobject` | `securityobject-delete.xml` | SUCCESS | Gruppe gelöscht |
| 20 | `removeaccount` | `account-delete.xml` | SUCCESS | Benutzer gelöscht |

Zusätzlich: `sharing_strategy = unique` mit einem schon belegten Namen (Nummer wird angehängt), ein Service-Konto (`request_type = service`) ohne `request_name` – das muss scheitern.

Danach dieselben Aufträge über daccord selbst auslösen (Antrag bzw. Lebenszyklus), um den Weg über die RuntimeEngine zu prüfen.

## Fehlerbehandlung

Ein Auftrag endet mit Fehler, wenn

- kein Kontoname gebildet werden kann (Service-Konto ohne `request_name`, Vor- oder Nachname fehlen, alle generierten Namen belegt),
- bei `generate` Länge oder Regex fehlen (`ERROR_MISSING_PASSWORD_GENERATE_LENGTH`, `…_REGEX`) oder die Generierung scheitert,
- ein Kennwort nicht verschlüsselt werden kann,
- `modifyAccountType` ohne neuen Typ aufgerufen wird,
- bei einem Custom Command `command` fehlt,
- die JavaConnector Extension einen Fehler meldet (Codes siehe deren README).

Der TaskHandler schreibt in das Log der RuntimeEngine, die JavaConnector Extension in das Log des JavaConnectors.

## Technik

| | |
|---|---|
| Sprache | Java 21 |
| Build | Maven |
| Plattform | daccord 2.3, inchorus-daccord-provisioning-extension 2.3 (`DACProvisioningTaskHandler`, `DACProvisioningTaskHelper`), gadget-core 4.x |
| Gegenstück | daccord JavaConnector Extension – Microsoft Entra ID 2.0 |
| Logging | Log4j 2 |

## Bauen

```bash
mvn clean package
```
