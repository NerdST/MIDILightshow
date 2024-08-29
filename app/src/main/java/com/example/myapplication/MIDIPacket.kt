package com.example.myapplication

import kotlinx.serialization.Serializable

@Serializable
data class MIDIPacket (
    val t: ULong,
    var packetString: String,
    var packetData: ByteArray
) {

}