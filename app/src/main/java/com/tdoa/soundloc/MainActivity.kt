package com.tdoa.soundloc

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private val TAG = "TDOA_SOUND"
    private val REQUEST_AUDIO_PERM = 101
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var ntpOffsetMs: Long = 0L
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var apiUrl: String = "http://127.0.0.1:8000/report"
    private var deviceId: String = "S1"

    lateinit var tvStatus: TextView
    lateinit var etApi: EditText
    lateinit var etDevId: EditText
    lateinit var btnSyncNtp: Button
    lateinit var btnStart: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        etApi = findViewById(R.id.etApiUrl)
        etDevId = findViewById(R.id.etDevId)
        btnSyncNtp = findViewById(R.id.btnSyncNtp)
        btnStart = findViewById(R.id.btnStart)

        btnSyncNtp.setOnClickListener { syncNtp() }
        btnStart.setOnClickListener { startRecord() }
    }

    private fun syncNtp() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val server = "ntp.aliyun.com"
                val port = 123
                val buffer = ByteArray(48)
                buffer[0] = 0x1B.toByte()
                val sock = Socket(server, port)
                sock.soTimeout = 3000
                val out = sock.getOutputStream()
                out.write(buffer)
                val `in` = sock.getInputStream()
                `in`.read(buffer)
                sock.close()

                val receiveTime = System.currentTimeMillis()
                val transmit = read32(buffer, 40)
                val t = transmit - 2208988800L
                val ntpTime = t * 1000
                ntpOffsetMs = ntpTime - receiveTime
                runOnUiThread {
                    tvStatus.text = "NTP同步成功，偏移：$ntpOffsetMs ms"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "NTP失败：" + e.message
                }
            }
        }
    }

    private fun read32(buf: ByteArray, offset: Int): Long {
        var r = 0L
        for (i in 0..3) {
            r = r shl 8
            r += buf[offset + i].toLong() and 0xFF
        }
        return r
    }

    private fun startRecord() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO_PERM)
            return
        }
        apiUrl = etApi.text.toString()
        deviceId = etDevId.text.toString()
        val bufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufSize)
        audioRecord?.startRecording()
        isRecording = true
        tvStatus.text = "正在监听声音..."
        GlobalScope.launch(Dispatchers.IO) {
            val buf = ShortArray(bufSize / 2)
            while (isRecording) {
                val len = audioRecord?.read(buf, 0, buf.size) ?: 0
                var sum = 0.0
                for (s in buf) {
                    sum += s * s
                }
                val rms = Math.sqrt(sum / buf.size)
                if (rms > 3000) {
                    val ntpTs = System.currentTimeMillis() + ntpOffsetMs
                    sendReport(ntpTs)
                    runOnUiThread {
                        tvStatus.text = "识别到声音，时间戳：$ntpTs"
                    }
                }
            }
        }
    }

    private fun sendReport(tsMs: Long) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val url = URL(apiUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                val json = """{"device_id":"$deviceId","ntp_timestamp_ms":$tsMs,"confidence":0.8}"""
                val writer = OutputStreamWriter(conn.outputStream)
                writer.write(json)
                writer.flush()
                writer.close()
                conn.responseCode
            } catch (e: Exception) {
                Log.e(TAG, "上传失败", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
    }
}
