package com.example.myapplication

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.math.pow

@RequiresApi(Build.VERSION_CODES.O)
class MIDIPlayer(inputContext: Context) {

    private val context = inputContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var tickTime: Double = 1.0
    var tickTimeMultiplier: Double = 1.0
    private var currentDuration: Duration = Duration.ZERO
    private var job: Job? = null
    private val playerScope = CoroutineScope(Dispatchers.Default)
    private var midiPacketList: ArrayList<MIDIPacket> = arrayListOf()
    private var midiPacketIterator: MutableListIterator<MIDIPacket> = midiPacketList.listIterator()
    private var midiPacketCurrent: MIDIPacket? = null
    var mOutputStream: OutputStream? = null
    var max: ULong = 0u

    fun loadMIDIFile(inputMIDIFile: MIDIFile) {
        midiPacketList = inputMIDIFile.list
        tickTime = inputMIDIFile.delay
        max = inputMIDIFile.max
        t = 0u
        isPlaying = false
        currentDuration = Duration.ZERO
        midiPacketIterator = midiPacketList.listIterator()
        midiPacketCurrent = if (midiPacketIterator.hasNext()) midiPacketIterator.next() else null
    }

    fun parseMIDIFile(inputUri: Uri, inputName: String): MIDIFile? {
        val stream = context.contentResolver.openInputStream(inputUri) ?: run {
            showToast("Error: Could not open file")
            return null
        }

        val buf4 = ByteArray(4)
        val buf2 = ByteArray(2)

        return stream.use { fs ->

            // ── Header chunk ────────────────────────────────────────────────────────
            fs.read(buf4)
            if (buf4[0] != 'M'.code.toByte() || buf4[1] != 'T'.code.toByte() ||
                buf4[2] != 'h'.code.toByte() || buf4[3] != 'd'.code.toByte()) {
                showToast("Error: Invalid MIDI header"); return@use null
            }
            fs.read(buf4)
            if (ByteBuffer.wrap(buf4).int != 6) {
                showToast("Error: Header size mismatch"); return@use null
            }
            fs.read(buf2)
            val format = ByteBuffer.wrap(buf2).short.toInt()
            if (format !in 0..2) {
                showToast("Error: Unsupported MIDI format $format"); return@use null
            }
            fs.read(buf2)
            val tracks = ByteBuffer.wrap(buf2).short.toInt()
            fs.read(buf2)
            val tpb = ByteBuffer.wrap(buf2).short.toInt()
            if (tpb < 0) {
                showToast("Error: SMPTE time code not supported"); return@use null
            }

            // ── Track chunks ─────────────────────────────────────────────────────────
            // LinkedHashMap gives O(1) packet lookup per timestamp, replacing the prior O(n²) scans.
            // ByteArrayOutputStream per timestamp replaces O(k²) ByteArray concatenation.
            val packetMap = LinkedHashMap<ULong, ByteArrayOutputStream>()
            var parsedTickTime = 0.0
            var parsedMax: ULong = 0u

            for (trackIndex in 0 until tracks) {
                if (fs.available() <= 0) break

                fs.read(buf4)
                if (buf4[0] != 'M'.code.toByte() || buf4[1] != 'T'.code.toByte() ||
                    buf4[2] != 'r'.code.toByte() || buf4[3] != 'k'.code.toByte()) {
                    showToast("Error: Invalid track header at track $trackIndex"); return@use null
                }
                fs.read(buf4)
                val trackLen = ByteBuffer.wrap(buf4).int

                var time: ULong = 0u
                var i = 0
                var eotReached = false
                var trackChannel = 0
                var runningStatus = 0  // last channel status byte; 0 = none

                while (i < trackLen && !eotReached) {

                    // Delta time — variable-length quantity (VLQ)
                    var deltaTime: ULong = 0u
                    var b: Int
                    do {
                        b = fs.read(); i++
                        deltaTime = (deltaTime shl 7) or ((b and 0x7F).toULong())
                    } while (b and 0x80 != 0)
                    time += deltaTime

                    val eventByte = fs.read(); i++

                    when {

                        // ── Meta event (0xFF) ────────────────────────────────────────────
                        eventByte == 0xFF -> {
                            runningStatus = 0  // meta events cancel running status
                            val metaType = fs.read(); i++

                            // Meta event length is also VLQ-encoded
                            var metaLen = 0; var lb: Int
                            do { lb = fs.read(); i++; metaLen = (metaLen shl 7) or (lb and 0x7F) } while (lb and 0x80 != 0)

                            when (metaType) {
                                0x51 -> { // SET_TEMPO
                                    if (metaLen != 3) {
                                        showToast("Error: Invalid tempo data"); return@use null
                                    }
                                    // Read 3 bytes into positions 1-3 of a 4-byte buffer so
                                    // ByteBuffer.int gives the correct 24-bit big-endian value.
                                    val tempoBytes = ByteArray(4)
                                    fs.read(tempoBytes, 1, 3); i += 3
                                    val tempo = ByteBuffer.wrap(tempoBytes).int
                                    parsedTickTime = tempo.toDouble() / tpb.toDouble() / 1000.0
                                }
                                0x03 -> { // TRACK_NAME — last digit is the channel number (1/2/3)
                                    val nameBytes = ByteArray(metaLen)
                                    fs.read(nameBytes, 0, metaLen); i += metaLen
                                    val trackName = String(nameBytes, Charsets.UTF_8)
                                    trackChannel = trackName.last().digitToIntOrNull() ?: trackChannel
                                }
                                0x2F -> { // END_OF_TRACK
                                    eotReached = true
                                    // metaLen is 0; nothing to skip
                                }
                                else -> {
                                    // Unknown/unneeded meta event — MUST consume its data or
                                    // the byte cursor falls out of sync for the rest of the track.
                                    repeat(metaLen) { fs.read(); i++ }
                                }
                            }
                        }

                        // ── SysEx (0xF0 / 0xF7) ─────────────────────────────────────────
                        eventByte == 0xF0 || eventByte == 0xF7 -> {
                            runningStatus = 0  // sysex cancels running status
                            var sysexLen = 0; var lb: Int
                            do { lb = fs.read(); i++; sysexLen = (sysexLen shl 7) or (lb and 0x7F) } while (lb and 0x80 != 0)
                            repeat(sysexLen) { fs.read(); i++ }
                        }

                        // ── Channel message ──────────────────────────────────────────────
                        // Handles both new status bytes and MIDI running status (data byte
                        // reuses the last status byte, skipping retransmission of the status).
                        else -> {
                            if (eventByte and 0x80 == 0 && runningStatus == 0) {
                                // Orphaned data byte with no active running status — skip
                            } else {
                                val statusByte = if (eventByte and 0x80 != 0) { runningStatus = eventByte; eventByte } else runningStatus
                                val firstData  = if (eventByte and 0x80 != 0) fs.read().also { i++ } else eventByte

                                when (statusByte and 0xF0) {
                                    0x90 -> { // NOTE_ON
                                        // Velocity is 7-bit (0-127); shift left to fill 8-bit range (0-254)
                                        val velocity = (fs.read() shl 1).toByte(); i++
                                        packetMap.getOrPut(time) { ByteArrayOutputStream() }
                                            .write(byteArrayOf('S'.code.toByte(), trackChannel.toByte(), firstData.toByte(), velocity))
                                    }
                                    0x80 -> { // NOTE_OFF — only ch1 (luminosity) needs an explicit zero
                                        fs.read(); i++
                                        if (trackChannel == 1) {
                                            packetMap.getOrPut(time) { ByteArrayOutputStream() }
                                                .write(byteArrayOf('S'.code.toByte(), trackChannel.toByte(), firstData.toByte(), 0))
                                        }
                                    }
                                    0xC0, 0xD0 -> { /* Program Change / Channel Pressure: 1 data byte, already consumed as firstData */ }
                                    else -> { fs.read(); i++ } // 0xA0, 0xB0, 0xE0: consume the 2nd data byte
                                }
                                if (time > parsedMax) parsedMax = time
                            }
                        }
                    }
                }

                if (!eotReached) {
                    showToast("Error: End of track not found at track $trackIndex")
                    return@use null
                }
            }

            val parsedPacketList = ArrayList<MIDIPacket>(packetMap.size)
            for ((t, baos) in packetMap) {
                parsedPacketList.add(MIDIPacket(t, baos.toByteArray()))
            }
            // Sort needed for multi-track files where timestamps interleave across tracks
            parsedPacketList.sortBy { it.t }

            MIDIFile(inputName, inputUri.toString(), parsedPacketList, parsedTickTime, parsedMax)
        }
    }

    // ── Playback ──────────────────────────────────────────────────────────────────────

    var t: ULong = 0u
        set(newValue) {
            field = newValue
            // Always dispatch the callback on the main thread so callers can safely touch UI
            val cb = onValueChanged
            if (cb != null) mainHandler.post { cb(newValue) }
        }

    var onValueChanged: ((ULong) -> Unit)? = null

    var isPlaying: Boolean = false
        set(newValue) {
            field = newValue
            if (newValue) startPlaying() else stopPlaying()
        }

    var isLooping: Boolean = false

    private val clock: Clock = Clock.systemDefaultZone()
    private var startInstant: Instant = clock.instant()
    private var elapsedTime: Duration = Duration.ZERO
    var seconds: Long = startInstant.epochSecond
    var nano: Long = startInstant.nano.toLong()
    private var s: Double = 0.0

    private fun startPlaying() {
        startInstant = clock.instant()
        if (midiPacketList.isEmpty()) return

        job = playerScope.launch {
            while (true) {
                elapsedTime = Duration.between(startInstant, clock.instant())
                val scaled = (elapsedTime.toNanos().toDouble() * tickTimeMultiplier).toLong()
                elapsedTime = Duration.ofNanos(scaled)
                s = elapsedTime.seconds.toDouble() + (elapsedTime.nano.toDouble() / 1e9)
                s += currentDuration.seconds.toDouble() + (currentDuration.nano.toDouble() / 1e9)
                t = (s / (tickTime / 1000.0)).toULong()
                updateIterator()
                if (t >= max) break
                delay(1)
            }
            // Commit elapsed time and zero it out so stopPlaying() doesn't double-count it
            currentDuration += elapsedTime
            elapsedTime = Duration.ZERO

            if (isLooping) {
                t = 0u
                currentDuration = Duration.ZERO
                midiPacketIterator = midiPacketList.listIterator()
                midiPacketCurrent = if (midiPacketIterator.hasNext()) midiPacketIterator.next() else null
                isPlaying = true
            } else {
                isPlaying = false
            }
        }
    }

    private fun stopPlaying() {
        job?.cancel()
        currentDuration += elapsedTime
    }

    @Synchronized
    fun updateIteratorFromBeginning() {
        midiPacketIterator = midiPacketList.listIterator()
        midiPacketCurrent = if (midiPacketIterator.hasNext()) midiPacketIterator.next() else null

        startInstant = clock.instant()
        s = t.toDouble() * (tickTime / 1000.0)
        currentDuration = Duration.ofNanos((s * 1e9).toLong())

        while (midiPacketCurrent != null && midiPacketCurrent!!.t < t) {
            if (!midiPacketIterator.hasNext()) break
            midiPacketCurrent = midiPacketIterator.next()
        }
        if (midiPacketIterator.hasPrevious()) midiPacketCurrent = midiPacketIterator.previous()
    }

    private fun sendBTMessage(message: ByteArray) {
        val msg = message + '\n'.code.toByte()
        try {
            mOutputStream?.write(msg)
            Log.i("SONG EVENT", msg.toString(Charsets.UTF_8))
        } catch (e: IOException) { }
    }

    @Synchronized
    private fun updateIterator() {
        while (midiPacketCurrent != null && midiPacketCurrent!!.t <= t) {
            sendBTMessage(midiPacketCurrent!!.packetData)
            midiPacketCurrent = if (midiPacketIterator.hasNext()) midiPacketIterator.next() else null
        }
    }

    private fun showToast(message: String) {
        mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }
}
