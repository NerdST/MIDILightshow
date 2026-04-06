package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.databinding.ActivityMainBinding
import com.example.myapplication.databinding.RecyclerviewSingleItemBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

@RequiresApi(Build.VERSION_CODES.O)
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var midiRvAdapter: FileListAdapter
    private lateinit var midiFileList: ArrayList<MIDIFile>

    companion object {
        private const val MIDI_FILE_LIST_KEY = "midi_file_list"
    }

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bluetoothSocket: BluetoothSocket
    private lateinit var bluetoothDevice: BluetoothDevice

    private var mmOutputStream: OutputStream? = null
    private var mmInputStream: InputStream? = null

    private val midiPlayer = MIDIPlayer(this)

    private var workerThread: Thread? = null
    private lateinit var readBuffer: ByteArray
    private var readBufferPosition: Int = 0
    var counter: Int = 0

    @Volatile
    var stopWorker: Boolean = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sharedPreferences = getSharedPreferences("my_app_prefs", Context.MODE_PRIVATE)
        midiFileList = loadMIDIFileList()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val midiLayoutManager: RecyclerView.LayoutManager = LinearLayoutManager(this)
        binding.rvListMidifiles.layoutManager = midiLayoutManager
        midiRvAdapter = FileListAdapter(this, midiFileList, midiPlayer, binding)
        binding.rvListMidifiles.adapter = midiRvAdapter

        binding.seekBarSongProgress.max = 0
        binding.seekBarSongProgress.progress = midiPlayer.t.toInt()

        binding.seekBarSongProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    midiPlayer.t = progress.toULong()
                    midiPlayer.updateIteratorFromBeginning()
                }
                binding.textViewProgress.text = midiPlayer.t.toString()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        midiPlayer.onValueChanged = { newValue ->
            // onValueChanged is already dispatched to main thread by MIDIPlayer
            binding.seekBarSongProgress.progress = newValue.toInt()
        }

        binding.seekBarBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(p0: SeekBar?, p1: Int, p2: Boolean) {
                if (p2) sendData(byteArrayOf('B'.code.toByte(), p1.toByte()))
            }
            override fun onStartTrackingTouch(p0: SeekBar?) {}
            override fun onStopTrackingTouch(p0: SeekBar?) {}
        })

        binding.playButton.setOnClickListener {
            midiPlayer.isPlaying = !midiPlayer.isPlaying
            updateButtonState()
        }

        binding.importButton.setOnClickListener {
            getContent.launch("audio/*")
        }

        binding.pairButton.setOnClickListener {
            try { findBT(); openBT() } catch (e: IOException) { }
        }

        binding.closeButton.setOnClickListener {
            try { closeBT() } catch (e: IOException) { }
        }

        // Fast-forward / slow-forward buttons: only restart playback if already playing,
        // so pressing them while paused doesn't accidentally start the song.
        binding.imageButtonFF1.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> restartWithMultiplier(1.5)
                MotionEvent.ACTION_UP   -> restartWithMultiplier(1.0)
            }
            true
        }

        binding.imageButtonFF2.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> restartWithMultiplier(2.0)
                MotionEvent.ACTION_UP   -> restartWithMultiplier(1.0)
            }
            true
        }

        binding.imageButtonSS1.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> restartWithMultiplier(0.75)
                MotionEvent.ACTION_UP   -> restartWithMultiplier(1.0)
            }
            true
        }

        binding.imageButtonSS2.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> restartWithMultiplier(0.5)
                MotionEvent.ACTION_UP   -> restartWithMultiplier(1.0)
            }
            true
        }

        binding.imageButtonLoop.setOnClickListener {
            midiPlayer.isLooping = !midiPlayer.isLooping
            binding.textViewLooping.text = if (midiPlayer.isLooping) "Looping" else "Not Looping"
        }

        binding.textViewProgress.text = binding.seekBarSongProgress.progress.toString()
        midiRvAdapter.notifyDataSetChanged()
    }

    private fun restartWithMultiplier(multiplier: Double) {
        midiPlayer.tickTimeMultiplier = multiplier
        if (midiPlayer.isPlaying) {
            midiPlayer.isPlaying = false
            midiPlayer.isPlaying = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopWorker = true
        workerThread?.interrupt()
        midiPlayer.isPlaying = false
        try { mmOutputStream?.close() } catch (e: IOException) { }
        try { mmInputStream?.close() } catch (e: IOException) { }
        try { if (::bluetoothSocket.isInitialized) bluetoothSocket.close() } catch (e: IOException) { }
    }

    fun saveMIDIFileList(list: ArrayList<MIDIFile>) {
        sharedPreferences.edit()
            .putString(MIDI_FILE_LIST_KEY, Json.encodeToString(ListSerializer(MIDIFile.serializer()), list))
            .apply()
    }

    private fun loadMIDIFileList(): ArrayList<MIDIFile> {
        return try {
            val json = sharedPreferences.getString(MIDI_FILE_LIST_KEY, null) ?: return ArrayList()
            ArrayList(Json.decodeFromString(ListSerializer(MIDIFile.serializer()), json))
        } catch (e: Exception) {
            // ClassCastException if upgrading from old StringSet format, or malformed JSON —
            // clear the stale entry so it doesn't crash on every subsequent launch.
            sharedPreferences.edit().remove(MIDI_FILE_LIST_KEY).apply()
            ArrayList()
        }
    }

    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        Log.i("DEBUG", if (isGranted) "Permission Granted!" else "Permission Denied")
    }

    private fun findBT() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }

        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bm.adapter

        if (!bluetoothAdapter.isEnabled) {
            val enableBluetooth = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            registerForResult.launch(enableBluetooth)
        }

        for (device in bluetoothAdapter.bondedDevices) {
            if (device.name == "ESP32test") {
                bluetoothDevice = device
                break
            }
        }
        binding.myLabel.text = "Bluetooth Device Found!"
    }

    @Throws(IOException::class)
    fun openBT() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }

        val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        bluetoothSocket = bluetoothDevice.createRfcommSocketToServiceRecord(uuid)
        bluetoothSocket.connect()
        mmOutputStream = bluetoothSocket.outputStream
        midiPlayer.mOutputStream = mmOutputStream
        mmInputStream = bluetoothSocket.inputStream

        beginListenForData()
        binding.myLabel.text = "Bluetooth Opened"
    }

    private fun beginListenForData() {
        val handler = Handler(Looper.getMainLooper())
        val delimiter: Byte = 10

        stopWorker = false
        readBufferPosition = 0
        readBuffer = ByteArray(1024)
        workerThread = Thread {
            while (!Thread.currentThread().isInterrupted && !stopWorker) {
                try {
                    val bytesAvailable = mmInputStream!!.available()
                    if (bytesAvailable > 0) {
                        val packetBytes = ByteArray(bytesAvailable)
                        mmInputStream!!.read(packetBytes)
                        for (i in 0 until bytesAvailable) {
                            val b = packetBytes[i]
                            if (b == delimiter) {
                                val encodedBytes = ByteArray(readBufferPosition)
                                System.arraycopy(readBuffer, 0, encodedBytes, 0, readBufferPosition)
                                val data = String(encodedBytes, charset("US-ASCII"))
                                readBufferPosition = 0
                                handler.post { binding.myLabel.text = data }
                            } else if (readBufferPosition < readBuffer.size) {
                                readBuffer[readBufferPosition++] = b
                            } else {
                                // Buffer overflow — reset rather than crash
                                readBufferPosition = 0
                            }
                        }
                    }
                } catch (ex: IOException) {
                    stopWorker = true
                }
            }
        }
        workerThread!!.start()
    }

    fun sendData(message: ByteArray) {
        val msg = message + '\n'.code.toByte()
        try {
            mmOutputStream?.write(msg)
            Log.i("BRIGHTNESS CHANGE", msg.toString(Charsets.UTF_8))
        } catch (e: IOException) { }
    }

    fun closeBT() {
        stopWorker = true
        try { mmOutputStream?.close() } catch (e: IOException) { }
        try { mmInputStream?.close() } catch (e: IOException) { }
        try { if (::bluetoothSocket.isInitialized) bluetoothSocket.close() } catch (e: IOException) { }
        mmOutputStream = null
        midiPlayer.mOutputStream = null
        mmInputStream = null
        binding.myLabel.text = "Bluetooth Closed"
    }

    private val registerForResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ -> }

    private fun updateButtonState() {
        if (midiPlayer.isPlaying) {
            binding.playButton.setImageResource(android.R.drawable.ic_media_pause)
        } else {
            binding.playButton.setImageResource(android.R.drawable.ic_media_play)
        }
    }

    private val getContent = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@registerForActivityResult

        var fileName = ""
        contentResolver.query(uri, null, null, null, null)?.use { cursor: Cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) fileName = cursor.getString(idx)
            }
        }

        val name = fileName
        // Parse on IO thread to avoid blocking the UI on large files
        lifecycleScope.launch(Dispatchers.IO) {
            val parsedFile = midiPlayer.parseMIDIFile(uri, name)
            if (parsedFile != null) {
                withContext(Dispatchers.Main) {
                    midiFileList.add(parsedFile)
                    midiRvAdapter.notifyDataSetChanged()
                    saveMIDIFileList(midiFileList)
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
class FileListAdapter(
    private val context: Context,
    private val rvFileList: ArrayList<MIDIFile>,
    private val midiPlayer: MIDIPlayer,
    private val mainBinding: ActivityMainBinding,
) : RecyclerView.Adapter<FileListAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: RecyclerviewSingleItemBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = RecyclerviewSingleItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        with(holder) {
            with(rvFileList[position]) {
                binding.textViewFilename.text = this.name
                binding.imageButtonUpload.setOnClickListener {
                    midiPlayer.loadMIDIFile(this)
                    mainBinding.seekBarSongProgress.max = midiPlayer.max.toInt()
                    mainBinding.textViewFileName.text = this.name
                }
                binding.imageButtonDelete.setOnClickListener {
                    rvFileList.remove(this)
                    (context as MainActivity).saveMIDIFileList(rvFileList)
                    notifyDataSetChanged()
                }
            }
        }
    }

    override fun getItemCount(): Int = rvFileList.size
}
