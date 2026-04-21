package your.package.discovery;

import your.package.discovery.IDiscoveryDeviceRepositoryCallback;

interface IDiscoveryDeviceRepository {
    void registerCallback(in IDiscoveryDeviceRepositoryCallback callback);
    void unregisterCallback(in IDiscoveryDeviceRepositoryCallback callback);
}
