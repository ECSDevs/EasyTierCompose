package cc.ptoe.easytier.compose.transport.root

import android.os.Parcel
import android.os.Parcelable

/** AIDL payload for the root-only TUN setup. */
data class RootTunSpec(
    val ipv4Cidr: String?,
    val mtu: Int,
    val manualRoutes: List<String>,
    val proxyCidrs: List<String>,
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readString(),
        parcel.readInt(),
        buildList { parcel.readStringList(this) },
        buildList { parcel.readStringList(this) },
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(ipv4Cidr)
        parcel.writeInt(mtu)
        parcel.writeStringList(manualRoutes)
        parcel.writeStringList(proxyCidrs)
    }

    override fun describeContents() = 0

    companion object CREATOR : Parcelable.Creator<RootTunSpec> {
        override fun createFromParcel(parcel: Parcel) = RootTunSpec(parcel)
        override fun newArray(size: Int): Array<RootTunSpec?> = arrayOfNulls(size)
    }
}

data class RootRuntimeStatus(
    val state: String,
    val virtualIpv4: String?,
    val tunDevice: String?,
    val error: String?,
) : Parcelable {
    constructor(parcel: Parcel) : this(parcel.readString().orEmpty(), parcel.readString(), parcel.readString(), parcel.readString())

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(state)
        parcel.writeString(virtualIpv4)
        parcel.writeString(tunDevice)
        parcel.writeString(error)
    }

    override fun describeContents() = 0

    companion object CREATOR : Parcelable.Creator<RootRuntimeStatus> {
        override fun createFromParcel(parcel: Parcel) = RootRuntimeStatus(parcel)
        override fun newArray(size: Int): Array<RootRuntimeStatus?> = arrayOfNulls(size)
    }
}
