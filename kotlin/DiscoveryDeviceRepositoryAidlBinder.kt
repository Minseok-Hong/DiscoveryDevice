package your.package.discovery

import android.os.RemoteCallbackList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class DiscoveryDeviceRepositoryAidlBinder(
    private val scope: CoroutineScope,
    private val resource: DiscoveryDeviceResource,
    private val isSameDevice: (DiscoveryDevice, DiscoveryDevice) -> Boolean = { previous, current ->
        previous == current
    },
    private val hasSameContent: (DiscoveryDevice, DiscoveryDevice) -> Boolean = { previous, current ->
        previous == current
    },
) : IDiscoveryDeviceRepository.Stub() {

    private val callbacks = RemoteCallbackList<IDiscoveryDeviceRepositoryCallback>()
    private val jobLock = Any()

    private var collectJob: Job? = null
    @Volatile
    private var latestDevices: List<DiscoveryDevice> = emptyList()

    @Volatile
    private var initialDevicesSent = false

    override fun registerCallback(callback: IDiscoveryDeviceRepositoryCallback?) {
        if (callback == null) return

        callbacks.register(callback)

        val alreadyCollecting = startCollectingIfNeeded()
        if (alreadyCollecting) {
            sendInitialDevicesIfReady(callback)
        }
    }

    override fun unregisterCallback(callback: IDiscoveryDeviceRepositoryCallback?) {
        if (callback == null) return

        callbacks.unregister(callback)

        if (callbacks.registeredCallbackCount == 0) {
            stopCollecting()
        }
    }

    private fun startCollectingIfNeeded(): Boolean =
        synchronized(jobLock) {
            if (collectJob?.isActive == true) {
                return@synchronized true
            }

            collectJob = scope.launch {
                resource.deviceListFlow.collect { devices ->
                    publish(devices)
                }
            }

            false
        }

    private fun stopCollecting() {
        synchronized(jobLock) {
            collectJob?.cancel()
            collectJob = null
            latestDevices = emptyList()
            initialDevicesSent = false
        }
    }

    private fun publish(devices: List<DiscoveryDevice>) {
        if (!initialDevicesSent) {
            initialDevicesSent = true
            latestDevices = devices
            broadcastInitialDevices(devices)
            return
        }

        createChanges(
            previous = latestDevices,
            current = devices,
        ).forEach(::broadcastChange)

        latestDevices = devices
    }

    private fun createChanges(
        previous: List<DiscoveryDevice>,
        current: List<DiscoveryDevice>,
    ): List<DiscoveryDeviceAidlChange> {
        val removed = previous
            .filterNot { previousDevice ->
                current.any { currentDevice ->
                    isSameDevice(previousDevice, currentDevice)
                }
            }
            .map { device ->
                DiscoveryDeviceAidlChange.from(
                    DiscoveryDeviceChange(
                        type = DiscoveryDeviceChange.Type.REMOVED,
                        device = device,
                    ),
                )
            }

        val addedOrUpdated = current.mapNotNull { currentDevice ->
            val previousDevice = previous.firstOrNull { candidate ->
                isSameDevice(candidate, currentDevice)
            }

            val changeType = when {
                previousDevice == null -> DiscoveryDeviceChange.Type.ADDED
                !hasSameContent(previousDevice, currentDevice) -> DiscoveryDeviceChange.Type.UPDATED
                else -> null
            }

            changeType?.let { type ->
                DiscoveryDeviceAidlChange.from(
                    DiscoveryDeviceChange(
                        type = type,
                        device = currentDevice,
                    ),
                )
            }
        }

        return removed + addedOrUpdated
    }

    private fun sendInitialDevicesIfReady(
        callback: IDiscoveryDeviceRepositoryCallback,
    ) {
        if (!initialDevicesSent) return

        runCatching {
            callback.onInitialDevices(latestDevices.toMutableList())
        }
    }

    private fun broadcastInitialDevices(devices: List<DiscoveryDevice>) {
        val count = callbacks.beginBroadcast()
        try {
            repeat(count) { index ->
                runCatching {
                    callbacks
                        .getBroadcastItem(index)
                        .onInitialDevices(devices.toMutableList())
                }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private fun broadcastChange(change: DiscoveryDeviceAidlChange) {
        val count = callbacks.beginBroadcast()
        try {
            repeat(count) { index ->
                runCatching {
                    callbacks
                        .getBroadcastItem(index)
                        .onChanged(change)
                }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }
}
