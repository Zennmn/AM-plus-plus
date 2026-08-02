package org.bytedeco.javacpp

/** JVM fixture for the JavaCPP liveness field used by the target adapter. */
open class Pointer {
    @JvmField
    var address: Long = 1L
}
