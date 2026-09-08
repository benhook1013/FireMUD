package net.firedevops.firemud.hostedidentity.model;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;
import net.firedevops.firemud.hostedidentity.contract.HostedIdentityContract;

@Group(HostedIdentityContract.API_GROUP)
@Version(HostedIdentityContract.API_VERSION)
public class HostedEnvironmentIdentity
    extends CustomResource<HostedEnvironmentIdentitySpec, HostedEnvironmentIdentityStatus>
    implements Namespaced {}
