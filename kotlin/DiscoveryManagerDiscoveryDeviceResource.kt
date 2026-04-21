package your.package.discovery

import kotlinx.coroutines.CoroutineScope

class DiscoveryManagerDiscoveryDeviceResource(
    scope: CoroutineScope,
    private val discoveryManager: AbstractDiscoveryManager,
    private val qcDeviceMapper: (QcDevice) -> DiscoveryDevice,
    private val discoveryEventMapper: (Int) -> DiscoveryDeviceChange.Type?,
) : DiscoveryDeviceResource(scope) {

    private var discoveryResultListener: DeviceDiscoveryResultListener? = null

    override suspend fun registerListener(listener: DiscoveryDeviceChangeListener) {
        val resultListener = object : DeviceDiscoveryResultListener {
            override fun onDiscoveryResult(event: Int, qcDevice: QcDevice) {
                val changeType = discoveryEventMapper(event) ?: return

                listener.onChanged(
                    DiscoveryDeviceChange(
                        type = changeType,
                        device = qcDeviceMapper(qcDevice),
                    ),
                )
            }
        }

        discoveryResultListener = resultListener
        discoveryManager.registerDiscoveryResultListener(resultListener)
    }

    override suspend fun getInitialDevices(): List<DiscoveryDevice> =
        discoveryManager
            .getDevices()
            .filterIsInstance<QcDevice>()
            .map(qcDeviceMapper)

    override fun unregisterListener(listener: DiscoveryDeviceChangeListener) {
        discoveryResultListener?.let { resultListener ->
            discoveryManager.unregisterDiscoveryResultListener(resultListener)
        }
        discoveryResultListener = null
    }
}
