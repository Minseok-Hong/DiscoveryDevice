package your.package.discovery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.shareIn

abstract class DiscoveryDeviceResource(
    scope: CoroutineScope,
) {
    val deviceListFlow: SharedFlow<List<DiscoveryDevice>> =
        createDeviceListFlow()
            .shareIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(
                    stopTimeoutMillis = 0,
                    replayExpirationMillis = 0,
                ),
                replay = 1,
            )

    private fun createDeviceListFlow(): Flow<List<DiscoveryDevice>> =
        callbackFlow {
            val deviceStore = DeviceStore()
            val emitDevices: (List<DiscoveryDevice>) -> Unit = { devices ->
                trySend(devices)
            }
            val listener = createChangeListener(
                deviceStore = deviceStore,
                emitDevices = emitDevices,
            )

            try {
                registerListener(listener)
                emitInitialDevices(
                    deviceStore = deviceStore,
                    emitDevices = emitDevices,
                )
            } catch (throwable: Throwable) {
                cleanup(listener, deviceStore)
                throw throwable
            }

            awaitClose {
                cleanup(listener, deviceStore)
            }
        }

    private fun createChangeListener(
        deviceStore: DeviceStore,
        emitDevices: (List<DiscoveryDevice>) -> Unit,
    ): DiscoveryDeviceChangeListener =
        object : DiscoveryDeviceChangeListener {
            override fun onChanged(change: DiscoveryDeviceChange) {
                val devices = deviceStore.applyChange(change) ?: return
                emitDevices(devices)
            }
        }

    private suspend fun emitInitialDevices(
        deviceStore: DeviceStore,
        emitDevices: (List<DiscoveryDevice>) -> Unit,
    ) {
        val initialDevices = getInitialDevices()
        val devices = deviceStore.applyInitialDevices(initialDevices)
        emitDevices(devices)
    }

    private fun cleanup(
        listener: DiscoveryDeviceChangeListener,
        deviceStore: DeviceStore,
    ) {
        runCatching { unregisterListener(listener) }
        deviceStore.clear()
    }

    protected abstract suspend fun registerListener(
        listener: DiscoveryDeviceChangeListener,
    )

    protected abstract suspend fun getInitialDevices(): List<DiscoveryDevice>

    protected abstract fun unregisterListener(
        listener: DiscoveryDeviceChangeListener,
    )

    private class DeviceStore {
        private val lock = Any()
        private val devices = mutableListOf<DiscoveryDevice>()
        private val pendingChanges = mutableListOf<DiscoveryDeviceChange>()

        private var initialDevicesApplied = false

        fun applyInitialDevices(
            initialDevices: List<DiscoveryDevice>,
        ): List<DiscoveryDevice> =
            synchronized(lock) {
                devices.clear()
                initialDevices.forEach(::upsertLocked)

                pendingChanges.forEach(::applyChangeLocked)
                pendingChanges.clear()

                initialDevicesApplied = true
                currentDevicesLocked()
            }

        fun applyChange(
            change: DiscoveryDeviceChange,
        ): List<DiscoveryDevice>? =
            synchronized(lock) {
                if (!initialDevicesApplied) {
                    pendingChanges += change
                    return@synchronized null
                }

                val changed = applyChangeLocked(change)
                if (changed) currentDevicesLocked() else null
            }

        fun clear() {
            synchronized(lock) {
                devices.clear()
                pendingChanges.clear()
                initialDevicesApplied = false
            }
        }

        private fun applyChangeLocked(change: DiscoveryDeviceChange): Boolean =
            when (change.type) {
                DiscoveryDeviceChange.Type.ADDED,
                DiscoveryDeviceChange.Type.UPDATED -> {
                    upsertLocked(change.device)
                    true
                }

                DiscoveryDeviceChange.Type.REMOVED -> {
                    devices.remove(change.device)
                }
            }

        private fun upsertLocked(device: DiscoveryDevice) {
            val index = devices.indexOf(device)

            if (index >= 0) {
                devices[index] = device
            } else {
                devices += device
            }
        }

        private fun currentDevicesLocked(): List<DiscoveryDevice> =
            devices.toList()
    }
}
