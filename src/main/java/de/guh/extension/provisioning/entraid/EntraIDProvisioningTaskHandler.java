package de.guh.extension.provisioning.entraid;

import static de.guh.plugin.daccord.extensions.provisioning.base.DACProvisioningTaskHelper.appendRemainingObjectFields;
import static de.guh.plugin.daccord.extensions.provisioning.base.DACProvisioningTaskHelper.getAttributeXML;
import static de.guh.plugin.daccord.extensions.provisioning.base.DACProvisioningTaskHelper.getEncryptedAttributeXML;
import static de.guh.plugin.daccord.extensions.provisioning.base.DACProvisioningTaskHelper.resolveField;
import static de.guh.plugin.daccord.extensions.provisioning.base.DACProvisioningTaskHelper.resolvePersonField;
import static de.guh.plugin.daccord.extensions.provisioning.base.DACProvisioningTaskHelper.toTaskResponse;
import static de.guh.plugin.daccord.extensions.provisioning.base.DACProvisioningTaskHelper.variable;

import java.util.HashMap;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.Logger;

import de.guh.gadget.GadgetConfiguration;
import de.guh.plugin.daccord.extensions.provisioning.base.DACProvisioningTaskHelper;
import de.guh.plugin.daccord.extensions.provisioning.base.DACProvisioningTaskHandler;
import de.guh.plugin.daccord.extensions.provisioning.base.DACTaskResponse;
import de.guh.plugin.gadgetapi.GadgetAPIResponse;
import de.guh.plugin.gadgetapi.GadgetAPIVariable;
import de.guh.plugin.xml.XML;

/**
 * Bridges the daccord Provisioning framework ({@link DACProvisioningTaskHandler}) to the
 * EntraIDJavaConnectorExtension (daccord-javaconnector-entraid-extension), which does the actual
 * Microsoft Graph API calls.
 * <p>
 * This handler only maps provisioning task data (hmTask/hmPerson/hmProvisioningConfiguration) into the
 * "attributes" XML the EntraID connector expects (see EntraIDJavaConnectorExtension#createAccount and
 * siblings: every attribute name here becomes a "pp_data_&lt;name&gt;" ConnectorRequest parameter, dots
 * build nested JSON objects, e.g. "passwordProfile.password"), then dispatches it via GadgetAPI to the
 * JAVAConnector - exactly like {@code ActiveDirectoryProvisioningTaskHandler} does for Active Directory,
 * just with Graph-style field names instead of LDAP attribute names.
 * <p>
 * Unlike Active Directory, Entra ID has no organizational-unit/container concept, so "move" operations
 * just update organizational attributes (department, officeLocation, ...), and no search_basedn/
 * sharing_strategy concepts are needed - accounts/groups are addressed directly by their Graph Object-ID.
 */
public class EntraIDProvisioningTaskHandler implements DACProvisioningTaskHandler {

	private Logger log;

	private void initLog(HashMap<String, Object> hmData) {
		this.log = (Logger) hmData.get("gadgetlog");
	}

	// =========================================================================
	// ACCOUNT
	// =========================================================================

	@Override
	public DACTaskResponse addAccount(HashMap<String, Object> hmData, HashMap<String, String> hmTask, HashMap<String, String> hmPerson,
			HashMap<String, String> hmSystem, HashMap<String, String> hmProvisioningConfiguration) throws Exception {

		initLog(hmData);
		log.info("EntraIDProvisioningTaskHandler()...addAccount()...[running]");

		String decryptionhash = getDecryptionHash(hmData);
		String domainname = hmProvisioningConfiguration.get("domainname");

		String surname = resolvePersonField(hmPerson, "surname", hmTask, "object_surname", "request_surname");
		String givenname = resolvePersonField(hmPerson, "givenname", hmTask, "object_givenname", "request_givenname");

		// A service account has no person, so its name has to be requested explicitly (request_name)
		String accounttype = DACProvisioningTaskHelper.resolveAccountType(hmTask, hmPerson);
		String mailnickname = DACProvisioningTaskHelper.resolveAccountname(hmData, hmTask, accounttype, givenname, surname, hmSystem,
				hmProvisioningConfiguration);
		if (mailnickname == null || mailnickname.isBlank()) {
			log.error("EntraIDProvisioningTaskHandler()...addAccount()...mailNickname could not be resolved");
			DACTaskResponse trError = new DACTaskResponse();
			trError.setError();
			return trError;
		}

		String password = DACProvisioningTaskHelper.resolvePassword(hmTask, hmPerson, hmProvisioningConfiguration, decryptionhash, "fromtask");

		log.info("EntraIDProvisioningTaskHandler()...addAccount()...accountname resolved: " + mailnickname);

		// Values that daccord knows are passed as attributes; everything left out is derived in the
		// connector from its own defaults, so a minimal configuration still produces a valid account.
		// Additional attributes travel as object_* task fields and are taken over unchanged.
		XML xmlAttributes = new XML("attributes");
		if (domainname != null && !domainname.isBlank())
			xmlAttributes.appendXML(getAttributeXML("userPrincipalName", mailnickname + "@" + domainname));
		if (givenname != null && !givenname.isBlank())
			xmlAttributes.appendXML(getAttributeXML("givenName", givenname));
		if (surname != null && !surname.isBlank())
			xmlAttributes.appendXML(getAttributeXML("surname", surname));
		if (password != null && !password.isBlank())
			xmlAttributes.appendXML(getEncryptedAttributeXML("PASSWORD", password));

		// Configurable defaults: only sent when the provisioning configuration sets them, otherwise the
		// connector applies its own (accountEnabled true, force password change true).
		String accountenabled = hmProvisioningConfiguration.get("initial_accountenabled");
		if (accountenabled != null && !accountenabled.isBlank())
			xmlAttributes.appendXML(getAttributeXML("accountEnabled", accountenabled));

		// userType distinguishes members and guests in Entra ID; daccord's account type has no counterpart
		xmlAttributes.appendXML(getAttributeXML("userType", hmProvisioningConfiguration.getOrDefault("usertype", "Member")));

		String forcechange = hmProvisioningConfiguration.get("password_forcechangenextsignin");
		if (forcechange != null && !forcechange.isBlank())
			xmlAttributes.appendXML(getAttributeXML("passwordProfile.forceChangePasswordNextSignIn", forcechange));

		// sharing strategy "unique": the connector appends a counter to the base name until a free
		// userPrincipalName is found. It needs the base name and the number of attempts it may make.
		String strategy = hmProvisioningConfiguration.getOrDefault("sharing_strategy", "standalone");
		String upnbase = null;
		String upnlimit = null;
		if ("unique".equalsIgnoreCase(strategy)) {
			upnbase = resolveUPNBase(hmData, givenname, surname);
			upnlimit = hmProvisioningConfiguration.get("upn_uniqueness_limit");
		}

		appendRemainingObjectFields(xmlAttributes, hmTask,
				Set.of("object_surname", "object_givenname", "object_username", "object_jobtitle", "object_password", "object_uniqueid"));

		// Contract: the data XML carries Graph attributes only, steering values travel as named
		// variables and reach the extension as pp_config_<name> (JAVAConnector for daccord 2.3 and later).
		GadgetAPIResponse response = sendRequest(hmData, hmProvisioningConfiguration, "account", "create",
				new GadgetAPIVariable("xmldatastring", xmlAttributes.toString(), GadgetAPIVariable.type_STRING),
				new GadgetAPIVariable("accountname", mailnickname, GadgetAPIVariable.type_STRING),
				new GadgetAPIVariable("strategy", strategy, GadgetAPIVariable.type_STRING),
				variable("domainname", domainname),
				variable("upnbase", upnbase),
				variable("upnlimit", upnlimit));

		DACTaskResponse trResponse = toTaskResponse(response);
		if (response.wasSuccess()) {
			trResponse.setDisplayname(mailnickname);
			log.info("EntraIDProvisioningTaskHandler()...addAccount()...completed successfully");
		} else {
			log.error("EntraIDProvisioningTaskHandler()...addAccount()...failed");
		}
		return trResponse;
	}

	@Override
	public DACTaskResponse modifyAccount(HashMap<String, Object> hmData, HashMap<String, String> hmTask, HashMap<String, String> hmPerson,
			HashMap<String, String> hmSystem, HashMap<String, String> hmProvisioningConfiguration) throws Exception {

		initLog(hmData);
		log.debug("EntraIDProvisioningTaskHandler()...modifyAccount()...[running]");

		String accountuniqueid = hmTask.get("object_uniqueid");

		XML xmlAttributes = new XML("attributes");

		String surname = resolveField(hmTask, "request_surname", "object_surname");
		String givenname = resolveField(hmTask, "request_givenname", "object_givenname");
		String mail = resolveField(hmTask, "request_mail", "object_mail");
		String jobtitle = resolveField(hmTask, "request_jobtitle", "object_jobtitle");

		// mail and jobTitle have no derived counterpart in the connector, so they travel as attributes;
		// given name and surname are passed as values and mapped there (including displayName).
		if (!mail.isBlank())
			xmlAttributes.appendXML(getAttributeXML("mail", mail));
		if (!jobtitle.isBlank())
			xmlAttributes.appendXML(getAttributeXML("jobTitle", jobtitle));

		if (!givenname.isBlank())
			xmlAttributes.appendXML(getAttributeXML("givenName", givenname));
		if (!surname.isBlank())
			xmlAttributes.appendXML(getAttributeXML("surname", surname));

		appendRemainingObjectFields(xmlAttributes, hmTask,
				Set.of("object_surname", "object_givenname", "object_mail", "object_jobtitle", "object_uniqueid",
						"object_syncenabled"));

		// The sync state is a steering value, not a Graph attribute. The connector reads it live and
		// only falls back to this collected one, which is as old as the last collector run.
		GadgetAPIResponse response = sendRequest(hmData, hmProvisioningConfiguration, "account", "modify",
				new GadgetAPIVariable("xmldatastring", xmlAttributes.toString(), GadgetAPIVariable.type_STRING),
				variable("accountuniqueid", accountuniqueid),
				variable("syncenabled", hmTask.get("object_syncenabled")),
				variable("syncedaccount_policy", hmProvisioningConfiguration.get("syncedaccount_policy")),
				variable("syncedaccount_cloudattributes", hmProvisioningConfiguration.get("syncedaccount_cloudattributes")));

		return withDisplayname(toTaskResponse(response), accountName(hmTask));
	}

	// =========================================================================
	// accountName - the current name of the account (object_name > request_name)
	// for the description (displayname) of the provisioning request
	// =========================================================================
	private static String accountName(HashMap<String, String> hmTask) {
		String name = hmTask.getOrDefault("object_name", "").trim();
		if (name.isBlank())
			name = hmTask.getOrDefault("request_name", "").trim();
		return name;
	}

	// =========================================================================
	// withDisplayname - sets the description (displayname) of the provisioning
	// request, if one is given
	// =========================================================================
	private static DACTaskResponse withDisplayname(DACTaskResponse trResponse, String displayname) {
		if (trResponse != null && displayname != null && !displayname.isBlank())
			trResponse.setDisplayname(displayname);
		return trResponse;
	}

	@Override
	public DACTaskResponse modifyAccountPassword(HashMap<String, Object> hmData, HashMap<String, String> hmTask, HashMap<String, String> hmPerson,
			HashMap<String, String> hmSystem, HashMap<String, String> hmProvisioningConfiguration) throws Exception {

		initLog(hmData);
		log.debug("EntraIDProvisioningTaskHandler()...modifyAccountPassword()...[running]");

		String decryptionhash = getDecryptionHash(hmData);
		String accountuniqueid = hmTask.get("object_uniqueid");

		String password = hmTask.get("object_password");
		if (password == null || password.isEmpty())
			password = hmTask.get("object_PASSWORD");

		// never send a password in clear text: without an encrypted value the task fails
		password = DACProvisioningTaskHelper.encryptPassword(password, decryptionhash);
		if (password == null) {
			log.error("EntraIDProvisioningTaskHandler()...modifyAccountPassword()...no password or encryption failed");
			DACTaskResponse trError = new DACTaskResponse();
			trError.setError();
			return trError;
		}

		String forcechange = hmProvisioningConfiguration.getOrDefault("password_forcechangenextsignin", "true");

		XML xmlAttributes = new XML("attributes");
		xmlAttributes.appendXML(getEncryptedAttributeXML("PASSWORD", password));

		GadgetAPIResponse response = sendRequest(hmData, hmProvisioningConfiguration, "account", "modifyPassword",
				new GadgetAPIVariable("xmldatastring", xmlAttributes.toString(), GadgetAPIVariable.type_STRING),
				variable("accountuniqueid", accountuniqueid),
				variable("forcechangepasswordnextsignin", forcechange));

		return withDisplayname(toTaskResponse(response), accountName(hmTask));
	}

	@Override
	public DACTaskResponse modifyAccountStatus(HashMap<String, Object> hmData, HashMap<String, String> hmTask, HashMap<String, String> hmPerson,
			HashMap<String, String> hmSystem, HashMap<String, String> hmProvisioningConfiguration) throws Exception {

		initLog(hmData);
		log.debug("EntraIDProvisioningTaskHandler()...modifyAccountStatus()...[running]");

		String accountuniqueid = hmTask.get("object_uniqueid");

		String accountenabled;
		String requestStatus = hmTask.getOrDefault("request_status", "").trim();
		if (!requestStatus.isBlank()) {
			switch (requestStatus.toLowerCase()) {
				case "active":
					accountenabled = "true";
					break;
				case "inactive":
					accountenabled = "false";
					break;
				default:
					log.error("EntraIDProvisioningTaskHandler()...modifyAccountStatus()...unknown request_status: " + requestStatus);
					DACTaskResponse trError = new DACTaskResponse();
					trError.setError();
					return trError;
			}
		} else {
			accountenabled = hmTask.getOrDefault("object_accountEnabled", "true");
		}

		XML xmlAttributes = new XML("attributes");

		xmlAttributes.appendXML(getAttributeXML("accountEnabled", accountenabled));

		GadgetAPIResponse response = sendRequest(hmData, hmProvisioningConfiguration, "account", "modifyStatus",
				new GadgetAPIVariable("xmldatastring", xmlAttributes.toString(), GadgetAPIVariable.type_STRING),
				variable("accountuniqueid", accountuniqueid));

		String statusdisplay = !requestStatus.isBlank() ? requestStatus.toLowerCase() : ("false".equalsIgnoreCase(accountenabled) ? "inactive" : "active");
		return withDisplayname(toTaskResponse(response), accountName(hmTask) + " -> " +
			statusdisplay);
	}

	@Override
	public DACTaskResponse modifyAccountType(HashMap<String, Object> hmData, HashMap<String, String> hmTask, HashMap<String, String> hmPerson,
			HashMap<String, String> hmSystem, HashMap<String, String> hmProvisioningConfiguration) throws Exception {

		initLog(hmData);
		log.debug("EntraIDProvisioningTaskHandler()...modifyAccountType()...[running]");

		// The new type is the Entra ID userType (Member or Guest) and has to be given with the task
		String accountuniqueid = hmTask.get("object_uniqueid");
		String accounttype = resolveField(hmTask, "request_type", "object_type");
		if (accounttype.isBlank()) {
			log.error("EntraIDProvisioningTaskHandler()...modifyAccountType()...new account type not given");
			DACTaskResponse trError = new DACTaskResponse();
			trError.setError();
			return trError;
		}

		GadgetAPIResponse response = sendRequest(hmData, hmProvisioningConfiguration, "account", "modifyType",
				variable("accountuniqueid", accountuniqueid),
				variable("accounttype", accounttype));

		return withDisplayname(toTaskResponse(response), accountName(hmTask) + " -> " +
			accounttype);
	}

	@Override
	public DACTaskResponse renameAccount(HashMap<String, Object> hmData, HashMap<String, String> hmTask, HashMap<String, String> hmPerson,
			HashMap<String, String> hmSystem, HashMap<String, String> hmProvisioningConfiguration) throws Exception {

		initLog(hmData);
		log.debug("EntraIDProvisioningTaskHandler()...renameAccount()...[running]");

		String accountuniqueid = hmTask.get("object_uniqueid");
		String domainname = hmProvisioningConfiguration.get("domainname");

		String givenname = hmTask.getOrDefault("request_givenname", "").trim();
		String surname = hmTask.getOrDefault("request_surname", "").trim();

		XML xmlAttributes = new XML("attributes");

		// Only a display name without given/surname has no derived counterpart, so it stays an attribute.
		if (givenname.isBlank() || surname.isBlank()) {
			String displayname = resolveField(hmTask, "request_displayname", "object_displayName");
			if (!displayname.isBlank())
				xmlAttributes.appendXML(getAttributeXML("displayName", displayname));
		}

		if (!givenname.isBlank())
			xmlAttributes.appendXML(getAttributeXML("givenName", givenname));
		if (!surname.isBlank())
			xmlAttributes.appendXML(getAttributeXML("surname", surname));
		if (!givenname.isBlank() && !surname.isBlank())
			xmlAttributes.appendXML(getAttributeXML("displayName", givenname + " " + surname));

		String mailnickname = resolveField(hmTask, "request_name", "object_mailNickname");
		String objectName = mailnickname;
		if (!mailnickname.isBlank() && domainname != null && !domainname.isBlank())
			xmlAttributes.appendXML(getAttributeXML("userPrincipalName", mailnickname + "@" + domainname));

		GadgetAPIResponse response = sendRequest(hmData, hmProvisioningConfiguration, "account", "rename",
				new GadgetAPIVariable("xmldatastring", xmlAttributes.toString(), GadgetAPIVariable.type_STRING),
				new GadgetAPIVariable("accountname", mailnickname, GadgetAPIVariable.type_STRING),
				variable("accountuniqueid", accountuniqueid));

		DACTaskResponse trResponse = toTaskResponse(response);
		if (!objectName.isBlank()) {
			trResponse.setObjectName(objectName);
			trResponse.addProperty("name", objectName);
		}

		// description: current name -> new name (object_name holds the current name)
		String accountnamecurrent = hmTask.getOrDefault("object_name", "").trim();
		withDisplayname(trResponse, accountnamecurrent.isBlank() || accountnamecurrent.equals(objectName) ? objectName : accountnamecurrent + " -> " +
			objectName);
		return trResponse;
	}

	@Override
	public DACTaskResponse reconcileAccount(HashMap<String, Object> hmData, HashMap<String, String> hmTask, HashMap<String, String> hmPerson,
			HashMap<String, String> hmSystem, HashMap<String, String> hmProvisioningConfiguration) throws Exception {

		initLog(hmData);
		log.debug("EntraIDProvisioningTaskHandler()...reconcileAccount()...[running]");

		String accountuniqueid = hmTask.get("object_uniqueid");

		XML xmlAttributes = new XML("attributes");

		GadgetAPIResponse response = sendRequest(hmData, hmProvisioningConfiguration, "account", "reconcile",
				new GadgetAPIVariable("xmldatastring", xmlAttributes.toString(), GadgetAPIVariable.type_STRING),
				variable("accountuniqueid", accountuniqueid));

		return toTaskResponse(response);
	}

	@Override
	public DACTaskResponse moveAccount(HashMap<String, Object> hmData, HashMap<String, String> hmTask, HashMap<String, String> hmPerson,
			HashMap<String, String> hmSystem, HashMap<String, String> hmProvisioningConfiguration) throws Exception {

		initLog(hmData);
		log.debug("EntraIDProvisioningTaskHandler()...moveAccount()...[running]");

		// Entra ID has no organizational-unit/container concept - "move" updates organizational
		// attributes instead (department, officeLocation, ...)
		String accountuniqueid = hmTask.get("object_uniqueid");

		XML xmlAttributes = new XML("attributes");

		String department = resolveField(hmTask, "request_department", "object_department");
		if (!department.isBlank())
			xmlAttributes.appendXML(getAttributeXML("department", department));

		String officelocation = resolveField(hmTask, "request_officelocation", "object_officeLocation");
		if (!officelocation.isBlank())
			xmlAttributes.appendXML(getAttributeXML("officeLocation", officelocation));

		appendRemainingObjectFields(xmlAttributes, hmTask, Set.of("object_department", "object_officeLocation", "object_uniqueid"));

		GadgetAPIResponse response = sendRequest(hmData, hmProvisioningConfiguration, "account", "move",
				new GadgetAPIVariable("xmldatastring", xmlAttributes.toString(), GadgetAPIVariable.type_STRING),
				variable("accountuniqueid", accountuniqueid));

		// description: name -> new department / office location
		String target = !department.isBlank() ? department : officelocation;
		return withDisplayname(toTaskResponse(response), target.isBlank() ? accountName(hmTask) : accountName(hmTask) + " -> " +
			target);
	}

	@Override
	public DACTaskResponse removeAccount(HashMap<String, Object> hmData, HashMap<String, String> hmTask, HashMap<String, String> hmPerson,
			HashMap<String, String> hmSystem, HashMap<String, String> hmProvisioningConfiguration) throws Exception {

		initLog(hmData);
		log.debug("EntraIDProvisioningTaskHandler()...removeAccount()...[running]");

		String accountuniqueid = hmTask.get("object_uniqueid");

		XML xmlAttributes = new XML("attributes");

		GadgetAPIResponse response = sendRequest(hmData, hmProvisioningConfiguration, "account", "delete",
				new GadgetAPIVariable("xmldatastring", xmlAttributes.toString(), GadgetAPIVariable.type_STRING),
				variable("accountuniqueid", accountuniqueid));

		return withDisplayname(toTaskResponse(response, accountuniqueid), accountName(hmTask));
	}

	@Override
	public DACTaskResponse customAccountCommand(HashMap<String, Object> hmData, HashMap<String, String> hmTask, HashMap<String, String> hmPerson,
			HashMap<String, String> hmSystem, HashMap<String, String> hmProvisioningConfiguration) throws Exception {

		initLog(hmData);
		log.debug("EntraIDProvisioningTaskHandler()...customAccountCommand()...[running]");
		return executeCustomCommand(hmData, hmTask, hmProvisioningConfiguration, "account");
	}

	// =========================================================================
	// SECURITYOBJECT (group)
	// =========================================================================

	@Override
	public DACTaskResponse addSecurityObject(HashMap<String, Object> hmData, HashMap<String, String> hmTask, HashMap<String, String> hmPerson,
			HashMap<String, String> hmSystem, HashMap<String, String> hmProvisioningConfiguration) throws Exception {

		initLog(hmData);
		log.debug("EntraIDProvisioningTaskHandler()...addSecurityObject()...[running]");

		String name = resolveField(hmTask, "request_name", "object_name");
		String description = resolveField(hmTask, "request_description", "object_description");

		if (name.isBlank()) {
			log.error("EntraIDProvisioningTaskHandler()...addSecurityObject()...name not given");
			DACTaskResponse trError = new DACTaskResponse();
			trError.setError();
			return trError;
		}

		// displayName, mailNickname, mailEnabled and securityEnabled are derived in the connector.
		XML xmlAttributes = new XML("attributes");
		if (!description.isBlank())
			xmlAttributes.appendXML(getAttributeXML("description", description));

		appendRemainingObjectFields(xmlAttributes, hmTask, Set.of("object_name", "object_description", "object_uniqueid"));

		// Only sent when configured; otherwise the connector applies its defaults
		// (mailNickname derived from the name, mailEnabled false, securityEnabled true).
		String mailenabled = hmProvisioningConfiguration.get("mailenabled");
		if (mailenabled != null && !mailenabled.isBlank())
			xmlAttributes.appendXML(getAttributeXML("mailEnabled", mailenabled));

		String securityenabled = hmProvisioningConfiguration.get("securityenabled");
		if (securityenabled != null && !securityenabled.isBlank())
			xmlAttributes.appendXML(getAttributeXML("securityEnabled", securityenabled));

		GadgetAPIResponse response = sendRequest(hmData, hmProvisioningConfiguration, "securityobject", "create",
				new GadgetAPIVariable("xmldatastring", xmlAttributes.toString(), GadgetAPIVariable.type_STRING),
				new GadgetAPIVariable("securityobjectname", name, GadgetAPIVariable.type_STRING));

		DACTaskResponse trResponse = toTaskResponse(response);
		if (response.wasSuccess())
			trResponse.setDisplayname(name);
		return trResponse;
	}

	@Override
	public DACTaskResponse modifySecurityObject(HashMap<String, Object> hmData, HashMap<String, String> hmTask, HashMap<String, String> hmPerson,
			HashMap<String, String> hmSystem, HashMap<String, String> hmProvisioningConfiguration) throws Exception {

		initLog(hmData);
		log.debug("EntraIDProvisioningTaskHandler()...modifySecurityObject()...[running]");

		String securityobjectuniqueid = hmTask.get("object_uniqueid");
		String description = resolveField(hmTask, "request_description", "object_description");

		XML xmlAttributes = new XML("attributes");
		if (!description.isBlank())
			xmlAttributes.appendXML(getAttributeXML("description", description));

		appendRemainingObjectFields(xmlAttributes, hmTask, Set.of("object_description", "object_uniqueid"));

		GadgetAPIResponse response = sendRequest(hmData, hmProvisioningConfiguration, "securityobject", "modify",
				new GadgetAPIVariable("xmldatastring", xmlAttributes.toString(), GadgetAPIVariable.type_STRING),
				variable("securityobjectuniqueid", securityobjectuniqueid));

		return toTaskResponse(response);
	}

	@Override
	public DACTaskResponse renameSecurityObject(HashMap<String, Object> hmData, HashMap<String, String> hmTask, HashMap<String, String> hmPerson,
			HashMap<String, String> hmSystem, HashMap<String, String> hmProvisioningConfiguration) throws Exception {

		initLog(hmData);
		log.debug("EntraIDProvisioningTaskHandler()...renameSecurityObject()...[running]");

		String securityobjectuniqueid = hmTask.get("object_uniqueid");
		String name = resolveField(hmTask, "request_name", "object_name");

		XML xmlAttributes = new XML("attributes");

		// The connector maps securityobjectname to displayName for group resources.
		GadgetAPIResponse response = sendRequest(hmData, hmProvisioningConfiguration, "securityobject", "rename",
				new GadgetAPIVariable("xmldatastring", xmlAttributes.toString(), GadgetAPIVariable.type_STRING),
				new GadgetAPIVariable("securityobjectname", name, GadgetAPIVariable.type_STRING),
				variable("securityobjectuniqueid", securityobjectuniqueid));

		DACTaskResponse trResponse = toTaskResponse(response);
		if (!name.isBlank()) {
			trResponse.setObjectName(name);
			trResponse.addProperty("name", name);
		}
		return trResponse;
	}

	@Override
	public DACTaskResponse reconcileSecurityObject(HashMap<String, Object> hmData, HashMap<String, String> hmTask, HashMap<String, String> hmPerson,
			HashMap<String, String> hmSystem, HashMap<String, String> hmProvisioningConfiguration) throws Exception {

		initLog(hmData);
		log.debug("EntraIDProvisioningTaskHandler()...reconcileSecurityObject()...[running]");

		String securityobjectuniqueid = hmTask.get("object_uniqueid");

		XML xmlAttributes = new XML("attributes");

		GadgetAPIResponse response = sendRequest(hmData, hmProvisioningConfiguration, "securityobject", "reconcile",
				new GadgetAPIVariable("xmldatastring", xmlAttributes.toString(), GadgetAPIVariable.type_STRING),
				variable("securityobjectuniqueid", securityobjectuniqueid));

		return toTaskResponse(response);
	}

	@Override
	public DACTaskResponse moveSecurityObject(HashMap<String, Object> hmData, HashMap<String, String> hmTask, HashMap<String, String> hmPerson,
			HashMap<String, String> hmSystem, HashMap<String, String> hmProvisioningConfiguration) throws Exception {

		initLog(hmData);
		log.debug("EntraIDProvisioningTaskHandler()...moveSecurityObject()...[running]");

		// Entra ID groups have no container concept either - map to an attribute update, same as
		// modifySecurityObject
		return modifySecurityObject(hmData, hmTask, hmPerson, hmSystem, hmProvisioningConfiguration);
	}

	@Override
	public DACTaskResponse removeSecurityObject(HashMap<String, Object> hmData, HashMap<String, String> hmTask, HashMap<String, String> hmPerson,
			HashMap<String, String> hmSystem, HashMap<String, String> hmProvisioningConfiguration) throws Exception {

		initLog(hmData);
		log.debug("EntraIDProvisioningTaskHandler()...removeSecurityObject()...[running]");

		String securityobjectuniqueid = hmTask.get("object_uniqueid");

		XML xmlAttributes = new XML("attributes");

		GadgetAPIResponse response = sendRequest(hmData, hmProvisioningConfiguration, "securityobject", "delete",
				new GadgetAPIVariable("xmldatastring", xmlAttributes.toString(), GadgetAPIVariable.type_STRING),
				variable("securityobjectuniqueid", securityobjectuniqueid));

		return toTaskResponse(response, securityobjectuniqueid);
	}

	@Override
	public DACTaskResponse customSecurityObjectCommand(HashMap<String, Object> hmData, HashMap<String, String> hmTask, HashMap<String, String> hmPerson,
			HashMap<String, String> hmSystem, HashMap<String, String> hmProvisioningConfiguration) throws Exception {

		initLog(hmData);
		log.debug("EntraIDProvisioningTaskHandler()...customSecurityObjectCommand()...[running]");
		return executeCustomCommand(hmData, hmTask, hmProvisioningConfiguration, "securityobject");
	}

	// =========================================================================
	// PERMISSION (group membership)
	// =========================================================================

	@Override
	public DACTaskResponse addPermission(HashMap<String, Object> hmData, HashMap<String, String> hmTask, HashMap<String, String> hmAccount,
			HashMap<String, String> hmSecurityObject, HashMap<String, String> hmSystem, HashMap<String, String> hmProvisioningConfiguration) throws Exception {

		initLog(hmData);
		log.debug("EntraIDProvisioningTaskHandler()...addPermission()...[running]");

		String accountuniqueid = resolveObjectUniqueid(hmAccount, hmTask.get("actiontargetuniqueid"), hmSystem.get("systemid"));
		String securityobjectuniqueid = resolveObjectUniqueid(hmSecurityObject, hmTask.get("actiontargetuniqueid2"), hmSystem.get("systemid"));

		if (accountuniqueid == null || accountuniqueid.isBlank() || securityobjectuniqueid == null || securityobjectuniqueid.isBlank()) {
			log.error("EntraIDProvisioningTaskHandler()...addPermission()...missing id - account: " + accountuniqueid +
				", securityobject: " + securityobjectuniqueid);
			DACTaskResponse trError = new DACTaskResponse();
			trError.setError();
			return trError;
		}

		XML xmlAttributes = new XML("attributes");

		// The permission resource reads both ids as named variables, not from the data XML.
		GadgetAPIResponse response = sendRequest(hmData, hmProvisioningConfiguration, "permission", "create",
				new GadgetAPIVariable("xmldatastring", xmlAttributes.toString(), GadgetAPIVariable.type_STRING),
				new GadgetAPIVariable("accountuniqueid", accountuniqueid, GadgetAPIVariable.type_STRING),
				new GadgetAPIVariable("securityobjectuniqueid", securityobjectuniqueid, GadgetAPIVariable.type_STRING));

		return toTaskResponse(response);
	}

	@Override
	public DACTaskResponse removePermission(HashMap<String, Object> hmData, HashMap<String, String> hmTask, HashMap<String, String> hmAccount,
			HashMap<String, String> hmSecurityObject, HashMap<String, String> hmSystem, HashMap<String, String> hmProvisioningConfiguration) throws Exception {

		initLog(hmData);
		log.debug("EntraIDProvisioningTaskHandler()...removePermission()...[running]");

		String accountuniqueid = resolveObjectUniqueid(hmAccount, hmTask.get("actiontargetuniqueid"), hmSystem.get("systemid"));
		String securityobjectuniqueid = resolveObjectUniqueid(hmSecurityObject, hmTask.get("actiontargetuniqueid2"), hmSystem.get("systemid"));

		XML xmlAttributes = new XML("attributes");

		// The permission resource reads both ids as named variables, not from the data XML.
		GadgetAPIResponse response = sendRequest(hmData, hmProvisioningConfiguration, "permission", "delete",
				new GadgetAPIVariable("xmldatastring", xmlAttributes.toString(), GadgetAPIVariable.type_STRING),
				new GadgetAPIVariable("accountuniqueid", accountuniqueid, GadgetAPIVariable.type_STRING),
				new GadgetAPIVariable("securityobjectuniqueid", securityobjectuniqueid, GadgetAPIVariable.type_STRING));

		return toTaskResponse(response);
	}

	// =========================================================================
	// customcommand shared implementation
	// The handler does not know the commands - they are names of the connector extension (e.g.
	// "set_authenticationmethods"). It passes the command, the object's id and every request_* value of
	// the task (without the prefix) as named variables, so a new command needs no change here.
	// PARAMETERS (from request form / task data): request_command|object_command, further request_*
	// =========================================================================
	private DACTaskResponse executeCustomCommand(HashMap<String, Object> hmData, HashMap<String, String> hmTask,
			HashMap<String, String> hmProvisioningConfiguration, String objectType) throws Exception {

		String idName = "account".equals(objectType) ? "accountuniqueid" : "securityobjectuniqueid";
		List<GadgetAPIVariable> variables = DACProvisioningTaskHelper.customCommandVariables(hmTask, idName);

		GadgetAPIResponse response = sendRequest(hmData, hmProvisioningConfiguration, objectType, "customcommand",
				variables.toArray(new GadgetAPIVariable[0]));

		return toTaskResponse(response);
	}

	// =========================================================================
	// sendRequest - connector request of this handler, see DACProvisioningTaskHelper#sendRequest
	// =========================================================================
	private GadgetAPIResponse sendRequest(HashMap<String, Object> hmData, HashMap<String, String> hmProvisioningConfiguration, String objectType,
			String action, GadgetAPIVariable... variables) throws Exception {
		return DACProvisioningTaskHelper.sendRequest(hmData, hmProvisioningConfiguration, objectType, action, DACProvisioningTaskHelper.DEFAULT_TIMEOUT,
				variables);
	}

	// =========================================================================
	// resolveUPNBase - builds "vorname.nachname" for the sharing strategy "unique". Special characters
	// are converted through the same DataHelper the account name generation uses, so umlauts end up
	// exactly as the rest of daccord would write them.
	// =========================================================================
	private String resolveUPNBase(HashMap<String, Object> hmData, String givenname, String surname) throws Exception {

		String[] converted = DACProvisioningTaskHelper.convertNames(hmData, givenname, surname);
		if (converted == null) {
			log.warn("EntraIDProvisioningTaskHandler()...resolveUPNBase()...no base name, connector falls back");
			return null;
		}
		return (converted[0] + "." + converted[1]).toLowerCase();
	}

	/**
	 * Reads the unique id of an account or security object.
	 * <p>
	 * Right after provisioning the object exists in the target system but not yet in daccord, because no
	 * collector has run since. The framework then has no node to hand over and "uniqueid" stays empty -
	 * only the globaluniqueid is known, which is composed as {@code <systemid>-<uniqueid>}. Deriving the
	 * id from it grants the permission immediately instead of leaving the task in error until the next
	 * collector run.
	 *
	 * @param hmObject  the account or security object data handed over by the framework, may be empty
	 * @param reference the task's reference to the object, used when hmObject carries no globaluniqueid
	 * @param systemid  the system id forming the prefix of the globaluniqueid
	 * @return the unique id, or null when no reference is available at all
	 */
	private String resolveObjectUniqueid(HashMap<String, String> hmObject, String reference, String systemid) {

		String uniqueid = hmObject == null ? null : hmObject.get("uniqueid");
		if (uniqueid != null && !uniqueid.isBlank())
			return uniqueid;

		// A freshly provisioned account exists in the target system but has not been collected yet, so
		// daccord has no node for it and only its globaluniqueid is known. That id is built as
		// "<systemid>-<uniqueid>", so cutting the prefix off grants the permission right away instead of
		// leaving it until the next collector run.
		String globaluniqueid = hmObject == null ? null : hmObject.get("globaluniqueid");
		if (globaluniqueid != null && !globaluniqueid.isBlank())
			reference = globaluniqueid;

		if (reference == null || reference.isBlank())
			return null;

		String prefix = systemid + "-";
		if (systemid != null && !systemid.isBlank() && reference.startsWith(prefix)) {
			uniqueid = reference.substring(prefix.length());
			log.info("EntraIDProvisioningTaskHandler()...resolveObjectUniqueid()...object not collected yet, derived id from " + reference);
			return uniqueid;
		}

		return reference;
	}

	private String getDecryptionHash(HashMap<String, Object> hmData) {
		String decryptionhash = null;
		try {
			GadgetConfiguration gadgetconfig = (GadgetConfiguration) hmData.get("gadgetconfiguration");
			decryptionhash = gadgetconfig.getParameter("decryptionhash");
		} catch (Exception e) {
			log.error("EntraIDProvisioningTaskHandler()...exception while trying to retrieve decryptionhash: {}", e.getMessage());
		}
		return decryptionhash;
	}

}
