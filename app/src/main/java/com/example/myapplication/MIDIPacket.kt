package com.example.myapplication

import kotlinx.serialization.Serializable

@Serializable
data class MIDIPacket(
    val t: ULong,
    var packetData: ByteArray
) {
    // data class doesn't generate correct equals/hashCode for ByteArray by default
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MIDIPacket) return false
        return t == other.t && packetData.contentEquals(other.packetData)
    }

    override fun hashCode(): Int = 31 * t.hashCode() + packetData.contentHashCode()
}