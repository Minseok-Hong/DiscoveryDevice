package your.package.discovery

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class DiscoveryDeviceAidlChange(
    val type: Int,
    val device: DiscoveryDevice,
) : Parcelable {

    fun toDomain(): DiscoveryDeviceChange =
        DiscoveryDeviceChange(
            type = when (type) {
                TYPE_ADDED -> DiscoveryDeviceChange.Type.ADDED
                TYPE_UPDATED -> DiscoveryDeviceChange.Type.UPDATED
                TYPE_REMOVED -> DiscoveryDeviceChange.Type.REMOVED
                else -> error("Unknown discovery device change type: $type")
            },
            device = device,
        )

    companion object {
        const val TYPE_ADDED = 1
        const val TYPE_UPDATED = 2
        const val TYPE_REMOVED = 3

        fun from(change: DiscoveryDeviceChange): DiscoveryDeviceAidlChange =
            DiscoveryDeviceAidlChange(
                type = when (change.type) {
                    DiscoveryDeviceChange.Type.ADDED -> TYPE_ADDED
                    DiscoveryDeviceChange.Type.UPDATED -> TYPE_UPDATED
                    DiscoveryDeviceChange.Type.REMOVED -> TYPE_REMOVED
                },
                device = change.device,
            )
    }
}
