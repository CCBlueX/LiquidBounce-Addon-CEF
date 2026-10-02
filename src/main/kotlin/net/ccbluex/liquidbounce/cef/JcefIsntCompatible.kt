package net.ccbluex.liquidbounce.cef

import net.ccbluex.liquidbounce.utils.client.error.errors.ClientError

class JcefIsntCompatible : ClientError(
    message = "JCEF Isn't compatible",
    needToReport = false
) {
    fun readResolve(): Any = JcefIsntCompatible()
}
