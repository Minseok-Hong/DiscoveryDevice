package your.package.discovery;

import your.package.discovery.DiscoveryDevice;
import your.package.discovery.DiscoveryDeviceAidlChange;

oneway interface IDiscoveryDeviceRepositoryCallback {
    void onInitialDevices(in List<DiscoveryDevice> devices);
    void onChanged(in DiscoveryDeviceAidlChange change);
}
