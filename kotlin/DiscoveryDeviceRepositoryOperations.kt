package your.package.discovery

import kotlinx.coroutines.flow.Flow

interface DiscoveryDeviceRepositoryOperations {
    val discoveryDeviceListFlow: Flow<List<DiscoveryDevice>>
}

class DiscoveryDeviceRepositoryImpl(
    resource: DiscoveryManagerDiscoveryDeviceResource,
) : DiscoveryDeviceRepositoryOperations {

    override val discoveryDeviceListFlow: Flow<List<DiscoveryDevice>> =
        resource.deviceListFlow
}

class DiscoveryDeviceRepositoryProxy(
    resource: AidlDiscoveryDeviceResource,
) : DiscoveryDeviceRepositoryOperations {

    override val discoveryDeviceListFlow: Flow<List<DiscoveryDevice>> =
        resource.deviceListFlow
}
