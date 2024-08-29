package com.example.myapplication

enum class MIDI_EVENTS(val v: Byte) {
    NOTE_OFF(0x80.toByte()),
    NOTE_ON(0x90.toByte()),
    POLYPHONIC_KEY_PRESSURE(0xA0.toByte()),
    CONTROLLER_CHANGE(0xB0.toByte()),
    PROGRAM_CHANGE(0xC0.toByte()),
    CHANNEL_PRESSURE(0xD0.toByte()),
    PITCH_BEND_CHANGE(0xE0.toByte());
}

enum class META_EVENTS(val v: Byte) {
    META_EVENT(0xFF.toByte()),
    SEQUENCE_NUMBER(0x00.toByte()),
    TEXT_EVENT(0x01.toByte()),
    COPYRIGHT_NOTICE(0x02.toByte()),
    TRACK_NAME(0x03.toByte()),
    INSTRUMENT_NAME(0x04.toByte()),
    LYRICS(0x05.toByte()),
    MARKER(0x06.toByte()),
    CUE_POINT(0x07.toByte()),
    MIDI_CHANNEL_PREFIX(0x20.toByte()),
    END_OF_TRACK(0x2F.toByte()),
    SET_TEMPO(0x51.toByte()),
    SMPTE_OFFSET(0x54.toByte()),
    TIME_SIGNATURE(0x58.toByte()),
    KEY_SIGNATURE(0x59.toByte()),
    SEQUENCER_SPECIFIC(0x7F.toByte());
}

enum class SYSEX_EVENTS(val v: Byte) {
    SYSEX_EVENT_1(0xF0.toByte()),
    SYSEX_EVENT_2(0xF7.toByte());
}
