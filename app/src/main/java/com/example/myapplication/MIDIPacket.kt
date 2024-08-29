package com.example.myapplication

class MIDIPacket (
    val t: ULong,
    var packetString: String,
    var packetData: ByteArray
) {
}