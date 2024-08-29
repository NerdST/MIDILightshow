package com.example.myapplication

class MIDIEvent(
    val t: ULong,
    val channel: Int,
    val eventType: MIDI_EVENTS,
    val noteNumber: Int,
    val noteVelocity: Int
) {

}