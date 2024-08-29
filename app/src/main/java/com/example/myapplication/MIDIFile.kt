package com.example.myapplication

import android.net.Uri
import kotlinx.serialization.Serializable


@Serializable
data class MIDIFile(
    val name: String?,
    val uriString: String,
    val list: ArrayList<MIDIPacket>,
    val delay: Double = 1.0,
    val max: ULong = 0u,
) {

}