package your.package.discovery

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AidlDiscoveryDeviceResource(
    scope: CoroutineScope,
    private val remoteRepositoryProvider: suspend () -> IDiscoveryDeviceRepository,
) : DiscoveryDeviceResource(scope) {

    private var remoteRepository: IDiscoveryDeviceRepository? = null
    private var remoteCallback: IDiscoveryDeviceRepositoryCallback? = null
    private var initialDevicesDeferred: CompletableDeferred<List<DiscoveryDevice>>? = null

    override suspend fun registerListener(listener: DiscoveryDeviceChangeListener) {
        val remote = withContext(Dispatchers.IO) {
            remoteRepositoryProvider()
        }

        val deferred = CompletableDeferred<List<DiscoveryDevice>>()

        val callback = object : IDiscoveryDeviceRepositoryCallback.Stub() {
            override fun onInitialDevices(devices: MutableList<DiscoveryDevice>?) {
                deferred.complete(devices.orEmpty())
            }

            override fun onChanged(change: DiscoveryDeviceAidlChange) {
                listener.onChanged(change.toDomain())
            }
        }

        remoteRepository = remote
        remoteCallback = callback
        initialDevicesDeferred = deferred

        withContext(Dispatchers.IO) {
            remote.registerCallback(callback)
        }
    }

    override suspend fun getInitialDevices(): List<DiscoveryDevice> =
        initialDevicesDeferred
            ?.await()
            ?: error("AIDL discovery callback is not registered.")

    override fun unregisterListener(listener: DiscoveryDeviceChangeListener) {
        val remote = remoteRepository
        val callback = remoteCallback

        remoteRepository = null
        remoteCallback = null

        initialDevicesDeferred?.cancel()
        initialDevicesDeferred = null

        if (remote != null && callback != null) {
            runCatching {
                remote.unregisterCallback(callback)
            }
        }
    }
}
