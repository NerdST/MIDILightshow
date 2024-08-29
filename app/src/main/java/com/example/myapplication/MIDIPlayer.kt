package com.example.myapplication

import android.content.Context
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.io.Serializable
import java.nio.ByteBuffer
import java.time.Clock
import java.time.Instant
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

@RequiresApi(Build.VERSION_CODES.O)
class MIDIPlayer(
    inputContext: Context,
) {
    private val context = inputContext
    private var tickTime: Double = 1.0
    private var tickDelayNanos: Duration = Duration.ZERO
    private var currentDuration: java.time.Duration = java.time.Duration.ZERO
    private var job: Job? = null
    private val playerScope = CoroutineScope(Dispatchers.Default)
    private val tickScope = CoroutineScope(Dispatchers.Default)
//    private var midiEventList: ArrayList<MIDIEvent> = arrayListOf()
    private var midiPacketList: ArrayList<MIDIPacket> = arrayListOf()
//    private var midiEventIterator: MutableListIterator<MIDIEvent> = midiEventList.listIterator()
    private var midiPacketIterator: MutableListIterator<MIDIPacket> = midiPacketList.listIterator()
    private var midiEventCurrent: MIDIEvent? = null
    private var midiPacketCurrent: MIDIPacket? = null
    var mOutputStream: OutputStream? = null

    fun loadMIDIFile (inputMIDIFile: MIDIFile) {
        midiPacketList = inputMIDIFile.list
        tickTime = inputMIDIFile.delay
        tickDelayNanos = (tickTime * 1000000.0).toLong().nanoseconds
        max = inputMIDIFile.max

        // Reset progress of the player
        t = 0u
        isPlaying = false
    }

    var max: ULong = 0u

    fun parseMIDIFile ( inputUri: Uri, inputName: String ): MIDIFile? {
        val inputFileStream = context.contentResolver.openInputStream(inputUri)

        // Check if file is open
        if ( inputFileStream == null ) {
            Toast.makeText(context, "Error: Could not open file", Toast.LENGTH_SHORT).show()
            return null
        }

        val buffer4 = ByteArray(4)
        val buffer2 = ByteArray(2)
        var buffer: Byte
        var header = CharArray(4)
//        midiEventList.clear()
        midiPacketList.clear()

        /*--------------------------------------------------------------------------------------*/
        /*                                  Parsing the Header                                  */
        /*======================================================================================*/

        // Read in first 4 bytes
        inputFileStream.read(buffer4)

        // Check if it's correct
        if ( buffer4[0] != 'M'.toByte() || buffer4[1] != 'T'.toByte() || buffer4[2] != 'h'.toByte() || buffer4[3] != 'd'.toByte() ) {
            Toast.makeText(context, "Error: Invalid Header", Toast.LENGTH_SHORT).show()
            return null
        }


        // Read in the header
        inputFileStream.read(buffer4)
        var length: Int = ByteBuffer.wrap(buffer4).getInt()

        if (length != 6) {
            Toast.makeText(context, "Error: Header Size Mismatch", Toast.LENGTH_SHORT).show()
            return null
        }

        // Read in the format
        inputFileStream.read(buffer2)
        var format: Int = ByteBuffer.wrap(buffer2).getShort().toInt()
        if (format != 0 && format != 1 && format != 2) {
            Toast.makeText(context, "Error: Invalid File Format", Toast.LENGTH_SHORT).show()
            return null
        }

        // Read in the number of tracks
        inputFileStream.read(buffer2)
        var tracks: Int = ByteBuffer.wrap(buffer2).getShort().toInt()

        // Read in the time division
        inputFileStream.read(buffer2)
        var tpb: Int = ByteBuffer.wrap(buffer2).getShort().toInt()

        // Check if time division is positive
        if (tpb < 0) {
            Toast.makeText(context, "Error: Invalid TPB", Toast.LENGTH_SHORT).show()
            return null
        }
//        else {
//            Toast.makeText(context, "YIPPIE", Toast.LENGTH_SHORT).show()
//        }

        /*======================================================================================*/

        var eotReached: Boolean
        var time: ULong;
        var currentTrack = 0
        var deltaTime: ULong
        var tempo: Int
        var dataLength: Int = 0
        var trackName: String
        var trackChannel: Int = 0
        max = 0u

        while ( inputFileStream.available() > 0 && currentTrack < tracks ) {
            // Read in the first 4 bytes
            inputFileStream.read(buffer4)

            // Check if its correct
            if ( buffer4[0] != 'M'.toByte() || buffer4[1] != 'T'.toByte() || buffer4[2] != 'r'.toByte() || buffer4[3] != 'k'.toByte() ) {
                Toast.makeText(context, "Error: Invalid Header", Toast.LENGTH_SHORT).show()
                return null
            }

            // Print out the track number

            // Read in the track length
            inputFileStream.read(buffer4)
            length = ByteBuffer.wrap(buffer4).getInt()

            // Reset Variables
            eotReached = false
            time = 0u

            // Read in the track
            var i = 0
            while (i < length) {
                // Read in the delta time
                deltaTime = 0u
                do {
                    buffer = inputFileStream.read().toByte()
                    i++
                    deltaTime = ( deltaTime shl 7 ) or ((buffer.toInt() and 0x7F).toULong())
                    if ( deltaTime == (-1).toULong() ) { break }
                } while ( ( buffer.toUInt() and 0x80.toUInt() ).toInt() != 0 )
                time += deltaTime

                // Read in event type
                buffer = inputFileStream.read().toByte()
                i++

                // Check if it's a meta event
                if ( buffer.toInt() == -1 ) {
                    // Read in meta event type
                    buffer = inputFileStream.read().toByte()
                    i++

                    // Perform action based on meta event type
                    when (buffer) {
                        META_EVENTS.SET_TEMPO.v -> {
                            // Read in the length of the data
                            buffer = inputFileStream.read().toByte()
                            i++

                            // See if that's correct
                            if ( buffer.toInt() != 3 ) {
                                Toast.makeText(context, "Error: Invalid Tempo Argument", Toast.LENGTH_SHORT).show()
                                return null
                            }

                            // Read in the data
                            inputFileStream.read(buffer4,0,buffer.toInt())
                            i += buffer.toInt()
                            tempo = ByteBuffer.wrap(buffer4).getInt()
                            tempo = tempo shr 8
                            tickTime = tempo.toDouble() / tpb.toDouble() / 1000.0
//                            tickDelayNanos = Duration.ofNanos((tickTime * 1000000).toLong())
                            tickDelayNanos = (tickTime * 1000000.0).toLong().nanoseconds
//                            Toast.makeText(context, tickDelayNanos.toString(), Toast.LENGTH_SHORT).show()

                        }
                        META_EVENTS.TIME_SIGNATURE.v -> {
                            buffer = inputFileStream.read().toByte()
                            i++

                            if ( buffer.toInt() != 4 ) {
                                Toast.makeText(context, "Error: Invalid Time Signature", Toast.LENGTH_SHORT).show()
                                return null
                            }

                            inputFileStream.read(buffer4,0,buffer.toInt())
                            i += buffer.toInt()
                        }
                        META_EVENTS.TRACK_NAME.v -> {
                            dataLength = 0
                            do {
                                buffer = inputFileStream.read().toByte()
                                i++

                                dataLength = ( dataLength shl 7 ) or ( buffer.toInt() and 0x7F )
                            } while ( buffer.toInt() and 0x80 > 0 )

                            val nameBuffer = ByteArray(dataLength)
                            inputFileStream.read(nameBuffer, 0, dataLength)
                            i += dataLength

                            trackName = String(nameBuffer, Charsets.UTF_8)
                            trackChannel = trackName.last().digitToInt()
                        }
                        META_EVENTS.END_OF_TRACK.v -> {
                            buffer = inputFileStream.read().toByte()
                            i++

                            eotReached = true
                        }
                    }
                } else if ( buffer.toInt() == -16 || buffer.toInt() == -9 ) {
                    Toast.makeText(context, "SYSEX EVENT", Toast.LENGTH_SHORT).show()
                } else if ( (buffer.toInt() and 0x80.toInt()) > 0 ) {
                    when ((buffer.toInt() and 0xF0.toInt()).toByte()) {
                        MIDI_EVENTS.NOTE_ON.v -> {
                            buffer = inputFileStream.read().toByte()
                            i++
                            val noteName = buffer

                            buffer = inputFileStream.read().toByte()
                            i++
                            val noteVelocity = ( buffer.toInt() shl 1 ).toByte()

                            // Create a new entry if there isn't one at the current timestamp
                            if ( !(midiPacketList.any { it.t == time }) ) {
                                midiPacketList.plusAssign(MIDIPacket(time, "P", byteArrayOf('P'.code.toByte())))
                            }

                            val packetIndex = midiPacketList.indexOfFirst { it.t == time }
                            midiPacketList[packetIndex].packetString += "S" + trackChannel +
                                    "%02X".format(noteName.toInt()) +
                                    "%02X".format(noteVelocity.toInt())
                            midiPacketList[packetIndex].packetData += byteArrayOf(
                                'S'.code.toByte(), trackChannel.toByte(), noteName, noteVelocity)

//                            midiEventList.plusAssign(MIDIEvent(time, trackChannel, MIDI_EVENTS.NOTE_ON, noteName.toInt(), noteVelocity.toInt()))
                        }
                        MIDI_EVENTS.NOTE_OFF.v -> {
                            buffer = inputFileStream.read().toByte()
                            i++
                            val noteName = buffer

                            buffer = inputFileStream.read().toByte()
                            i++
                            val noteVelocity = ( buffer.toInt() shl 1 ).toByte()

                            // Create a new entry if there isn't one at the current timestamp
                            if ( !(midiPacketList.any { it.t == time }) ) {
                                midiPacketList.plusAssign(MIDIPacket(time, "P", byteArrayOf('P'.code.toByte())))
                            }

                            if ( trackChannel == 1 ) {
                                val packetIndex = midiPacketList.indexOfFirst { it.t == time }
                                midiPacketList[packetIndex].packetString += "S" + trackChannel +
                                        "%02X".format(noteName.toInt()) + "00"
                                midiPacketList[packetIndex].packetData += byteArrayOf(
                                    'S'.code.toByte(), trackChannel.toByte(), noteName, 0)
                            }

//                            midiEventList.plusAssign(MIDIEvent(time, trackChannel, MIDI_EVENTS.NOTE_OFF, noteName.toInt(), noteVelocity.toInt()))
                        }
                        MIDI_EVENTS.PROGRAM_CHANGE.v -> {
                            Toast.makeText(context, "Program Change event", Toast.LENGTH_SHORT).show()
                            buffer = inputFileStream.read().toByte()
                            i++
                        }
                        MIDI_EVENTS.CHANNEL_PRESSURE.v -> {
                            Toast.makeText(context, "Channel Aftertouch event", Toast.LENGTH_SHORT).show()
                            buffer = inputFileStream.read().toByte()
                            i++
                        }
                        else -> {
                            buffer = inputFileStream.read().toByte()
                            buffer = inputFileStream.read().toByte()
                            i += 2
                        }
                    }
                    if ( max < time ) {
                        max = time
                    }
                }
            }

            if ( !eotReached ) {
                Toast.makeText(context, "Error: EOT not reached", Toast.LENGTH_SHORT).show()
                return null
            }

            currentTrack++
        }

        inputFileStream.close()
//        midiEventList.sortBy { it.t }
        midiPacketList.sortBy { it.t }
        t = 0u
        currentDuration = java.time.Duration.ZERO
//        midiEventIterator = midiEventList.listIterator()
//        midiEventCurrent = midiEventIterator.next()
        midiPacketIterator = midiPacketList.listIterator()
        midiPacketCurrent = midiPacketIterator.next()

        return MIDIFile(context, inputName, inputUri, midiPacketList, tickTime, max)
    }

    // All the player related stuff
    var t: ULong = 0u
        set ( newValue ) {
            field = newValue
            onValueChanged?.invoke(newValue)
        }

    var onValueChanged: ((ULong) -> Unit)? = null

    var isPlaying: Boolean = false
        set ( newValue ) {
            field = newValue
            if (newValue) {
                startPlaying()
            } else {
                stopPlaying()
            }
        }

    private var clock: Clock = Clock.systemDefaultZone()
    private var startInstant: Instant = clock.instant() // or Instant.now();
    private var elapsedTime: java.time.Duration = java.time.Duration.ZERO
    var seconds: Long = startInstant.epochSecond
    var nano: Long = startInstant.nano.toLong()
    private var s: Double = 0.0

    private fun startPlaying() {
        startInstant = clock.instant()

        job = playerScope.launch {
            while ( isPlaying ) {
                elapsedTime = java.time.Duration.between(startInstant, clock.instant())
                s = elapsedTime.seconds.toDouble() + (elapsedTime.nano.toDouble() / 10.0.pow(9))
                s += currentDuration.seconds.toDouble() + (currentDuration.nano.toDouble() / 10.0.pow(9))
                t = (s / ( tickTime / 1000.0 )).toULong()
//                Log.i("BREAKRJ#I", t.toString())
                updateIterator()
                if ( t >= max ) isPlaying = false
                delay ( 1 )
            }
            currentDuration += elapsedTime
            job?.cancel()
        }

//        job?.cancel()
//
//        job = playerScope.launch {
//            while ( true ) {
//                delay ( tickDelayNanos )
//                updateIterator()
//                if ( tick == max ) isPlaying = !isPlaying
//                tick++
//                if (!isPlaying) break
//            }
//        }
    }

    private fun stopPlaying() {
        job?.cancel()
        currentDuration += elapsedTime
    }

    fun updateIteratorFromBeginning() {
        midiPacketIterator = midiPacketList.listIterator()
        midiPacketCurrent = midiPacketIterator.next()

        // All the fancy math calculamations
        startInstant = clock.instant()
        s = t.toDouble() * (tickTime / 1000.0)
        currentDuration = java.time.Duration.ofNanos((s * 10.0.pow(9)).toLong())

        while ( midiPacketCurrent?.t!! < t ) {
            if ( midiPacketIterator.hasNext() ) midiPacketCurrent = midiPacketIterator.next()
            else break
        }

        if ( midiPacketIterator.hasPrevious() ) midiPacketCurrent = midiPacketIterator.previous()
    }

    private fun sendBTMessage ( message: ByteArray ) {
        var msg: ByteArray = message
        msg += '\n'.code.toByte()
        if ( mOutputStream != null ) {
            mOutputStream!!.write(msg)
        }
    }

    private fun updateIterator() {
        while ( midiPacketCurrent?.t!! <= t ) {
//            midiPacketCurrent?.packetString?.let { Log.i ( "MIDI EVENT", it) }
            if ( midiPacketCurrent != null ) { sendBTMessage(midiPacketCurrent!!.packetData) }
            if ( midiPacketIterator.hasNext() ) midiPacketCurrent = midiPacketIterator.next()
            else break
        }

//        Log.i("CURRENT TICK", tick.toString())
//        while ( midiEventCurrent?.t == tick ) {
//            Log.i("MIDI EVENT", midiEventCurrent?.eventType.toString() +
//                    "  \tat\t" + midiEventCurrent?.t.toString() +
//                    " \tchannel\t" + midiEventCurrent?.channel.toString())
//            if ( midiEventCurrent?.eventType == MIDI_EVENTS.NOTE_ON ) {
//                try {
//                    sendBTMessage( "S" + midiEventCurrent?.channel.toString() +
//                            "%02X".format(midiEventCurrent?.noteNumber) +
//                            "%02X".format(midiEventCurrent?.noteVelocity) )
//                } catch ( e: IOException ) {}
//            } else {
//                if ( midiEventCurrent?.channel == 1 ) {
//                    try {
//                        sendBTMessage( "S" + midiEventCurrent?.channel.toString() +
//                                "%02X".format(midiEventCurrent?.noteNumber) + "00" )
//                    } catch ( e: IOException ) {}
//                }
//
//            }
//
//            if ( midiEventIterator.hasNext() ) midiEventCurrent = midiEventIterator.next()
//            else break
//        }
    }
}
