---
title: Provisioning Extension Microsoft Entra ID
---

# daccord Provisioning Extension – Microsoft Entra ID

The TaskHandler carries out provisioning tasks from daccord in **Microsoft Entra ID**. It reads the task, the person and the ProvisioningConfiguration, derives account name, password and the Graph attributes from them and sends them through the JavaConnector to the **daccord JavaConnector Extension – Microsoft Entra ID**, which talks to Microsoft Graph.

Version 2.0 is for **daccord 2.3** and belongs to the JavaConnector Extension **daccord-javaconnector-entraid-extension 2.0**; both are shipped together. The Entra ID logic (strategies, synced accounts, defaults) lives in the JavaConnector Extension, the TaskHandler supplies the values.

## Contents

- [Features](#features)
- [Installation in daccord](#installation-in-daccord)
- [ProvisioningConfiguration](#provisioningconfiguration)
- [Tasks](#tasks)
- [Handover to the connector](#handover-to-the-connector)
- [Testing](#testing)
- [Error handling](#error-handling)
- [Technology](#technology)
- [Building](#building)

## Features

- All 19 actions for accounts, groups and memberships
- **Account name** from the request, the task or generated from given name and surname (unique across several systems)
- **Initial password** by strategy: from the task, the person, the configuration, the request, generated or none
- **Account type:** service accounts need a requested name
- **Further attributes:** every `object_*` field of the task is taken over as Graph attribute of the same name
- **Custom commands:** the command and all request values are passed to the JavaConnector Extension

## Installation in daccord

1. Copy the JAR `daccord-provisioning-entraid-extension-2.0.x.jar` to `/opt/GuH/daccord-provisioning-gadget/res/extensions/` (owner as the other files of the RuntimeEngine) and restart the RuntimeEngine so that the provisioning gadget loads the TaskHandler (restarting the gadget alone is not possible at the moment).
2. Install the JavaConnector Extension Entra ID and create a collector for it, see its README.
3. Create a ProvisioningConfiguration, at least with:

| Field | Value |
|---|---|
| `type` | `automatic` |
| `class` | `de.guh.extension.provisioning.entraid.EntraIDProvisioningTaskHandler` (without `.java`) |
| `collectorid` | ID of the Entra ID collector |

## ProvisioningConfiguration

| Key | Default | Effect |
|---|---|---|
| `collectorid` | – | collector whose connector is used (required) |
| `domainname` | tenant domain of the connection (`gp_param3`) | domain of the `userPrincipalName` |
| `sharing_strategy` | `standalone` | `standalone`, `shared` or `unique`, see the README of the JavaConnector Extension |
| `upn_uniqueness_limit` | `20` | with `unique`: number of attempts |
| `accountname_strategy` | `GIVENNAMEFIRST` | account name from given name and surname: `GIVENNAMEFIRST`, `SURNAMEFIRST` or `RANDOMNUMBER` |
| `accountnameuniqueness_systemids` | this system | systems in which the generated name has to be unique |
| `usertype` | `Member` | `userType` of new accounts: `Member` or `Guest` |
| `initial_accountenabled` | `true` (in the JavaConnector Extension) | new accounts enabled or disabled |
| `password_strategy` | `fromtask` | see below |
| `initialpassword` | – | password for `fromconfiguration`, fallback for `fromperson`/`fromrequest` |
| `password_generate_length`, `password_generate_regex` | – | for `generate`: length and allowed characters (regex Base64 encoded) |
| `password_forcechangenextsignin` | `true` | change the password at the next sign-in |
| `mailenabled`, `securityenabled` | `false`, `true` (in the JavaConnector Extension) | kind of new groups |
| `syncedaccount_policy` | `skipattributes` | handling of accounts synced from AD: `skipattributes`, `fail`, `ignore` |
| `syncedaccount_cloudattributes` | `usageLocation,preferredLanguage` | fields written on synced accounts anyway |

### Password (`password_strategy`)

| Strategy | Source |
|---|---|
| `fromtask` | `object_password` of the task |
| `fromperson` | initial password of the person, otherwise `initialpassword` |
| `fromconfiguration` | `initialpassword` |
| `fromrequest` | `request_initialpassword`, otherwise person, otherwise `initialpassword` |
| `generate` | generated according to `password_generate_length` and `password_generate_regex` |
| `none` | no password; the JavaConnector Extension generates one |

The password is transferred encrypted. If encryption fails, the task fails – it is never sent in clear text.

## Tasks

For every value: request (`request_*`) before person before task (`object_*`).

| Action | read | to Entra ID |
|---|---|---|
| addAccount | given name/surname, `request_name`/`object_username`, `request_type`/`object_type`, password, further `object_*` | `userPrincipalName`, `givenName`, `surname`, `userType`, `accountEnabled`, password, further attributes |
| modifyAccount | `mail`, `jobtitle`, given name/surname, `object_syncenabled`, further `object_*` | changed attributes |
| modifyAccountPassword | `object_password` | new password |
| modifyAccountStatus | `request_status` (`active`/`inactive`), otherwise `object_accountEnabled` | `accountEnabled` |
| modifyAccountType | `request_type`/`object_type` (required) | `userType` (`Member`/`Guest`) |
| renameAccount | `request_name`, `request_displayname`, given name/surname | `mailNickname`, `userPrincipalName`, `displayName` |
| moveAccount | `department`, `officeLocation`, further `object_*` | organisational fields (Entra ID has no OUs) |
| removeAccount, reconcileAccount | `object_uniqueid` | |
| addSecurityObject, modifySecurityObject, renameSecurityObject | `request_name`/`object_name`, `description`, further `object_*` | `displayName`, `description`, attributes |
| moveSecurityObject | as modifySecurityObject | |
| removeSecurityObject, reconcileSecurityObject | `object_uniqueid` | |
| addPermission, removePermission | IDs of account and group | membership |
| customAccountCommand, customSecurityObjectCommand | `request_command`/`object_command`, all `request_*` | command of the JavaConnector Extension |

An account that has just been created and not been collected yet has no ID in daccord. For memberships it is then derived from the `globaluniqueid` (`<systemid>-<id>`), so that the permission can be granted right away.

Empty values are not transferred. **Clearing** a field in Entra ID is not possible at the moment.

## Handover to the connector

The TaskHandler sends two kinds of values to the JavaConnector:

- **Identifiers and steering values** as named variables, e.g. `accountuniqueid`, `strategy`, `domainname`, `upnbase`, `syncedaccount_policy`, `command`. The JavaConnector Extension reads them as `pp_config_<name>`.
- **Graph attributes** in the data XML, e.g. `givenName`, `department`, `PASSWORD`. They go to Graph as they are.

This needs the **JavaConnector of daccord 2.3 or later**, which passes every variable on.

## Testing

The test runs with the `-runnable.jar` against a real daccord 2.3 system, its JavaConnector with the JavaConnector Extension Entra ID and a **test tenant** – **not** in the project directory. The TaskHandler is called exactly as by the provisioning gadget.

### Test prerequisites

| What | Where from |
|---|---|
| daccord 2.3 system with JavaConnector 2.3, in it the JavaConnector Extension Entra ID 2.0 and a collector on the test tenant | note collector ID and system ID; check the collector with `connect` first |
| `config.xml` of the provisioning gadget | `/opt/GuH/daccord-provisioning-gadget/conf/config.xml` (database access, `decryptionhash`) |
| resource directory of the provisioning gadget | `/opt/GuH/daccord-provisioning-gadget/res` (umlaut conversion of generated names) |
| network access from the test machine | to the daccord database and to the REST URL of the JavaConnector |
| checking in Entra ID | Entra admin center or Graph Explorer |

### Preparation

```bash
mvn clean package
mkdir -p ~/provtest-entraid && cp examples/*.xml ~/provtest-entraid/
cp target/daccord-provisioning-entraid-extension-2.0.0-runnable.jar ~/provtest-entraid/
scp <daccord-server>:/opt/GuH/daccord-provisioning-gadget/conf/config.xml ~/provtest-entraid/
cd ~/provtest-entraid
```

Replace `YOUR_SYSTEM_ID`, `YOUR_COLLECTOR_ID` and – after creation – `YOUR_USER_OBJECT_ID` or `YOUR_GROUP_OBJECT_ID` (from the output) in **every** task file.

### Call

```bash
java -Dlog4j2.level=DEBUG -jar daccord-provisioning-entraid-extension-2.0.0-runnable.jar \
  <action> /opt/GuH/daccord-provisioning-gadget/res config.xml <task>.xml
```

Success: `SUCCESS! Operation completed.` and `Object Unique ID: …`, exit code 0. Failure: `FAILED! Operation failed.` with the error message, exit code 1. The response of Graph is in the log of the JavaConnector.

### Test sequence

| # | Action | File | Expected | Check in Entra ID |
|---|---|---|---|---|
| 1 | `addaccount` | `account-create.xml` | SUCCESS, object ID | user with generated name, `userType` Member, `department` IT → enter the object ID |
| 2 | `modifyaccount` | `account-modify.xml` | SUCCESS | `jobTitle`, `officeLocation` |
| 3 | `modifyaccountpassword` | `account-password.xml` | SUCCESS | sign-in with the new password |
| 4 | `modifyaccountstatus` | `account-status-disable.xml` | SUCCESS | account disabled |
| 5 | `modifyaccountstatus` | `account-status-enable.xml` | SUCCESS | account enabled |
| 6 | `modifyaccounttype` | `account-type.xml` | SUCCESS | `userType` Guest |
| 7 | `renameaccount` | `account-rename.xml` | SUCCESS | new `mailNickname`, `userPrincipalName`, `displayName` |
| 8 | `moveaccount` | `account-move.xml` | SUCCESS | `department`, `officeLocation` |
| 9 | `reconcileaccount` | `account-reconcile.xml` | SUCCESS | – |
| 10 | `customaccountcommand` | `account-command.xml` | SUCCESS | authentication methods of the user |
| 11 | `addsecurityobject` | `securityobject-create.xml` | SUCCESS, object ID | group exists → enter the object ID |
| 12 | `addpermission` | `permission-create.xml` | SUCCESS | user is member |
| 13 | `removepermission` | `permission-delete.xml` | SUCCESS | membership removed |
| 14 | `modifysecurityobject` | `securityobject-modify.xml` | SUCCESS | `description` |
| 15 | `renamesecurityobject` | `securityobject-rename.xml` | SUCCESS | `displayName` |
| 16 | `movesecurityobject` | `securityobject-move.xml` | SUCCESS | `description` |
| 17 | `reconcilesecurityobject` | `securityobject-reconcile.xml` | SUCCESS | – |
| 18 | `customsecurityobjectcommand` | `securityobject-command.xml` | FAILED `ERROR_GRAPHREQUEST_DISABLED`, SUCCESS with `gp_param11 = true` at the collector | – |
| 19 | `removesecurityobject` | `securityobject-delete.xml` | SUCCESS | group deleted |
| 20 | `removeaccount` | `account-delete.xml` | SUCCESS | user deleted |

Also: `sharing_strategy = unique` with a name that is taken (a number is appended), a service account (`request_type = service`) without `request_name` – that has to fail.

Afterwards trigger the same tasks through daccord itself (request or lifecycle) to check the path through the RuntimeEngine.

## Error handling

A task fails if

- no account name can be derived (service account without `request_name`, given name or surname missing, all generated names taken),
- with `generate` the length or the regex is missing (`ERROR_MISSING_PASSWORD_GENERATE_LENGTH`, `…_REGEX`) or generation fails,
- a password cannot be encrypted,
- `modifyAccountType` is called without a new type,
- `command` is missing for a custom command,
- the JavaConnector Extension reports an error (codes see its README).

The TaskHandler writes to the log of the RuntimeEngine, the JavaConnector Extension to the log of the JavaConnector.

## Technology

| | |
|---|---|
| Language | Java 21 |
| Build | Maven |
| Platform | daccord 2.3, inchorus-daccord-provisioning-extension 2.3 (`DACProvisioningTaskHandler`, `DACProvisioningTaskHelper`), gadget-core 4.x |
| Counterpart | daccord JavaConnector Extension – Microsoft Entra ID 2.0 |
| Logging | Log4j 2 |

## Building

```bash
mvn clean package
```
