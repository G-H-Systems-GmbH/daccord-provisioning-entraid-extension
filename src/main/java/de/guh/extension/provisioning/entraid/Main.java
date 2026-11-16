package de.guh.extension.provisioning.entraid;

import de.guh.plugin.daccord.extensions.provisioning.test.DACProvisioningTestRunner;

/**
 * Runs one action of the EntraIDProvisioningTaskHandler outside daccord, for tests - see
 * DACProvisioningTestRunner and the README (section "Testen").
 */
public class Main {

	public static void main(String[] args) {
		DACProvisioningTestRunner.run(new EntraIDProvisioningTaskHandler(), args);
	}
}
