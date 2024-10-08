package com.example.myapplication

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
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
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.databinding.ActivityMainBinding
import com.example.myapplication.databinding.RecyclerviewSingleItemBinding
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
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

    private val midiPlayer = MIDIPlayer (this)

    private var workerThread: Thread? = null
    private lateinit var readBuffer: ByteArray
    private var readBufferPosition: Int = 0
    var counter: Int = 0

    @Volatile
    var stopWorker: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sharedPreferences = getSharedPreferences("my_app_prefs", Context.MODE_PRIVATE)

        midiFileList = loadMIDIFileList()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // All the recyclerView stuff
        val midiLayoutManager: RecyclerView.LayoutManager = LinearLayoutManager ( this )
        binding.rvListMidifiles.layoutManager = midiLayoutManager
        midiRvAdapter = FileListAdapter ( this, midiFileList, midiPlayer, binding )
        binding.rvListMidifiles.adapter = midiRvAdapter

        binding.seekBarSongProgress.max = 0
        binding.seekBarSongProgress.progress = midiPlayer.t.toInt()

        binding.seekBarSongProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    midiPlayer.t = progress.toULong()
                    midiPlayer.updateIteratorFromBeginning()
                }

                // Update the Text whenever Progress Bar Changes
                binding.textViewProgress.text = midiPlayer.t.toString()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // Do nothing
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Do nothing
            }
        })

        // Update SeekBar when MIDIPlayer value changes
        midiPlayer.onValueChanged = { newValue ->
            binding.seekBarSongProgress.progress = newValue.toInt()
        }

        // Update
        binding.seekBarBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(p0: SeekBar?, p1: Int, p2: Boolean) {
                if (p2) {
                    val sendVal: UByte = p1.toUByte()
                    val sendArray: ByteArray = byteArrayOf('B'.code.toByte(), p1.toByte())
                    sendData(sendArray)
                }
            }

            override fun onStartTrackingTouch(p0: SeekBar?) {

            }

            override fun onStopTrackingTouch(p0: SeekBar?) {

            }
        })

        // Toggle isPlaying on button click
        binding.playButton.setOnClickListener {
            midiPlayer.isPlaying = !midiPlayer.isPlaying
            updateButtonState()
        }

        // Import Button
        binding.importButton.setOnClickListener {
            getContent.launch("audio/*")
        }

        // Pair Button
        binding.pairButton.setOnClickListener {
            try {
                findBT()
                openBT()
            } catch ( exception: IOException ) { }
        }

        // Close Button
        binding.closeButton.setOnClickListener {
            try {
                closeBT()
            } catch ( exception: IOException ) { }
        }

        // Example of a call to a native method
        binding.textViewProgress.text = binding.seekBarSongProgress.progress.toString()

        midiRvAdapter.notifyDataSetChanged()
    }

    fun saveMIDIFileList(midiFileList: ArrayList<MIDIFile>) {
        val editor = sharedPreferences.edit()
        val serializedList = midiFileList.map { Json.encodeToString(MIDIFile.serializer(), it) }
        editor.putStringSet(MIDI_FILE_LIST_KEY, serializedList.toSet())
        editor.apply()
    }

    private fun loadMIDIFileList(): ArrayList<MIDIFile> {
        val midiFileList = ArrayList<MIDIFile>()
        val serializedList = sharedPreferences.getStringSet(MIDI_FILE_LIST_KEY, null) ?: emptySet()
        for (serializedString in serializedList) {
            val midiFile = Json.decodeFromString<MIDIFile>(serializedString)
            midiFileList.add(midiFile)
        }
        return midiFileList
    }

    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        isGranted ->
            if ( isGranted ) {
                Log.i("DEBUG", "Permission Granted!")
            } else {
                Log.i("DEBUG", "Permission Denied")
            }
    }

    private fun findBT () {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)

            return
        }

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if ( bluetoothAdapter == null ) {
            binding.myLabel.text = "No bluetooth adapter available"
        }

        if ( !bluetoothAdapter.isEnabled) {
            val enableBluetooth = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            registerForResult.launch(enableBluetooth)
        }

        val pairedDevices: Set<BluetoothDevice> = bluetoothAdapter.bondedDevices

        for (device in pairedDevices) {
            if (device.name == "ESP32test") {
                bluetoothDevice = device
                break
            }
        }

        binding.myLabel.text = "Bluetooth Device Found!"
    }

    @Throws(IOException::class)
    fun openBT() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)

            return
        }

        val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") //Standard SerialPortService ID
        bluetoothSocket = bluetoothDevice.createRfcommSocketToServiceRecord(uuid)
        bluetoothSocket.connect()
        mmOutputStream = bluetoothSocket.outputStream
        midiPlayer.mOutputStream = mmOutputStream
        mmInputStream = bluetoothSocket.inputStream

        beginListenForData()

        binding.myLabel.text = "Bluetooth Opened"
    }

    private fun beginListenForData() {
        val handler: Handler = Handler()
        val delimiter: Byte = 10 //This is the ASCII code for a newline character

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
                                System.arraycopy(
                                    readBuffer,
                                    0,
                                    encodedBytes,
                                    0,
                                    encodedBytes.size
                                )
                                val data =
                                    String(encodedBytes, charset("US-ASCII"))
                                readBufferPosition = 0

                                handler.post(Runnable { binding.myLabel.text = data })
                            } else {
                                readBuffer[readBufferPosition++] = b
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

    @Throws(IOException::class)
    fun sendData( message: ByteArray ) {
        var msg: ByteArray = message
        msg += '\n'.code.toByte()
        try {
            if ( mmOutputStream != null ) mmOutputStream!!.write(msg)
        } catch (e: IOException) { }
    }

    @Throws(IOException::class)
    fun closeBT() {
        stopWorker = true
        mmOutputStream!!.close()
        midiPlayer.mOutputStream!!.close()
        mmInputStream!!.close()
        bluetoothSocket.close()
        binding.myLabel.text = "Bluetooth Closed"
    }

    private val registerForResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->

    }

    private fun updateButtonState () {
        if (midiPlayer.isPlaying) {
            binding.playButton.setImageResource(android.R.drawable.ic_media_pause)
        } else {
            binding.playButton.setImageResource(android.R.drawable.ic_media_play)
        }
    }

    private val getContent = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        // Get the name of the file
        var fileName: String = ""
        if ( uri != null ) {
            val cursor: Cursor? = this.contentResolver.query(uri, null, null, null, null)
            if ( cursor != null && cursor.moveToFirst() ) {
                val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                fileName = cursor.getString(displayNameIndex)
            }
//            copyFileToInternalStorage(uri, fileName)
            val parsedFile = midiPlayer.parseMIDIFile(uri, fileName)
            if ( parsedFile != null ){
//                serializeAndSave(this,fileName,parsedFile)
                midiFileList.plusAssign(parsedFile)
                midiRvAdapter.notifyDataSetChanged()
            }

//            midiPlayer.loadMIDIFile(uri)
            saveMIDIFileList(midiFileList)
        }

        // Set the FileName text to the filename
//        binding.textViewFileName.text = fileName
    }





    /**
     * A native method that is implemented by the 'myapplication' native library,
     * which is packaged with this application.
     */
//    external fun stringFromJNI(): String
//
//    companion object {
//        // Used to load the 'myapplication' library on application startup.
//        init {
//            System.loadLibrary("myapplication")
//        }
//    }
}

@RequiresApi(Build.VERSION_CODES.O)
class FileListAdapter(
    private val context: Context,
    private val rvFileList: ArrayList<MIDIFile>,
    private val midiPlayer: MIDIPlayer,
    private val mainBinding: ActivityMainBinding,
): RecyclerView.Adapter<FileListAdapter.ViewHolder>() {

    inner class ViewHolder ( val binding: RecyclerviewSingleItemBinding ) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = RecyclerviewSingleItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        with ( holder ) {
            with ( rvFileList[position] ) {
                binding.textViewFilename.text = this.name
                binding.imageButtonUpload.setOnClickListener {
                    if ( this.uriString != null ) {
//                        val loadedMIDIFile = this.name?.let { it1 ->
//                            deserializeAndLoad(context,
//                                it1
//                            )
//                        }
//                        if (loadedMIDIFile != null) {
//                            midiPlayer.loadMIDIFile(loadedMIDIFile)
//                        }
                        midiPlayer.loadMIDIFile(this)
                        mainBinding.seekBarSongProgress.max = midiPlayer.max.toInt()
                        mainBinding.textViewFileName.text = this.name
                    }
                }

                binding.imageButtonDelete.setOnClickListener {
                    if ( this.uriString != null ) {
                        rvFileList.remove(this)
                        (context as MainActivity).saveMIDIFileList(rvFileList)
                        notifyDataSetChanged()
                    }
                }
            }
        }
    }

    override fun getItemCount(): Int {
        return rvFileList.size
    }
}