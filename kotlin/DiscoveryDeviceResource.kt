package your.package.discovery

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.shareIn

private const val TAG = "DiscoveryDeviceResource"

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
            val deviceStore = DiscoveryDeviceStore()
            val emitDevices: (List<DiscoveryDevice>) -> Unit = { devices ->
                runCatching { trySend(devices) }
            }
            val listener = createChangeListener(
                deviceStore = deviceStore,
                emitDevices = emitDevices,
            )

            val started =
                runCatching {
                    registerListener(listener)
                    emitInitialDevices(
                        deviceStore = deviceStore,
                        emitDevices = emitDevices,
                    )
                }.onFailure { throwable ->
                    Log.w(TAG, "Failed to start discovery device flow.", throwable)
                    cleanup(listener, deviceStore)
                    close()
                }.isSuccess

            if (!started) {
                return@callbackFlow
            }

            awaitClose {
                cleanup(listener, deviceStore)
            }
        }

    private fun createChangeListener(
        deviceStore: DiscoveryDeviceStore,
        emitDevices: (List<DiscoveryDevice>) -> Unit,
    ): DiscoveryDeviceChangeListener =
        object : DiscoveryDeviceChangeListener {
            override fun onChanged(change: DiscoveryDeviceChange) {
                runCatching {
                    deviceStore.applyChange(change)
                }.onFailure { throwable ->
                    Log.w(TAG, "Failed to apply discovery device change.", throwable)
                }.getOrNull()?.let(emitDevices)
            }
        }

    private suspend fun emitInitialDevices(
        deviceStore: DiscoveryDeviceStore,
        emitDevices: (List<DiscoveryDevice>) -> Unit,
    ) {
        val initialDevices = getInitialDevices()
        val devices = deviceStore.applyInitialDevices(initialDevices)
        emitDevices(devices)
    }

    private fun cleanup(
        listener: DiscoveryDeviceChangeListener,
        deviceStore: DiscoveryDeviceStore,
    ) {
        runCatching {
            unregisterListener(listener)
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to unregister discovery listener.", throwable)
        }
        deviceStore.clear()
    }

    protected abstract suspend fun registerListener(
        listener: DiscoveryDeviceChangeListener,
    )

    protected abstract suspend fun getInitialDevices(): List<DiscoveryDevice>

    protected abstract fun unregisterListener(
        listener: DiscoveryDeviceChangeListener,
    )
}
