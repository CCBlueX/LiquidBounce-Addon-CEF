package net.ccbluex.liquidbounce.cef.error

import net.ccbluex.liquidbounce.utils.client.error.errors.ClientError

class JcefIsntCompatible : ClientError(
    message = "JCEF Isn't compatible",
    needToReport = false
) {
    fun readResolve(): Any = JcefIsntCompatible()
}
