package com.example.myapplication

import android.content.Context
import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ObjectInputStream
import java.io.ObjectOutputStream


@Serializable
data class MIDIFile(
    private val context: Context,
    val name: String?,
    val uri: Uri?,
    val list: ArrayList<MIDIPacket>,
    val delay: Double = 1.0,
    val max: ULong = 0u,
) {

}

fun serializeAndSave(context: Context, name: String, midiFile: MIDIFile) {
    val json = Json.encodeToString(midiFile)
    val fos = context.openFileOutput(name, Context.MODE_PRIVATE)
    fos.write(json.toByteArray())
    fos.close()
}

fun deserializeAndLoad(context: Context, name: String): MIDIFile? {
    val fis = context.openFileInput(name)
    val data = fis.readBytes().toString(Charsets.UTF_8)
    fis.close()
    return try {
        Json.decodeFromString(data)
    } catch (e: Exception) {
        null // Handle potential deserialization error (optional)
    }
}