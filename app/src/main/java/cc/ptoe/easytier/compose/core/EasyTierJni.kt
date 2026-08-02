package cc.ptoe.easytier.compose.core

import com.easytier.jni.EasyTierJNI

/** App-owned facade over the bundled reference EasyTier JNI library. */
object EasyTierJni {
    fun setTunFd(instanceName: String, fd: Int): Int = EasyTierJNI.setTunFd(instanceName, fd)
    fun parseConfig(config: String): Int = EasyTierJNI.parseConfig(config)
    fun runNetworkInstance(config: String): Int = EasyTierJNI.runNetworkInstance(config)
    fun retainNetworkInstance(instanceNames: Array<String>?): Int = EasyTierJNI.retainNetworkInstance(instanceNames)
    fun collectNetworkInfos(maxLength: Int): String? = EasyTierJNI.collectNetworkInfos(maxLength)
    fun getLastError(): String? = EasyTierJNI.getLastError()
}
