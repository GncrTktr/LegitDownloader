package com.example.legitdownloader

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.example.legitdownloader.databinding.ActivityMainBinding
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale
import androidx.core.content.FileProvider
import android.content.Intent.ACTION_SEND
import android.content.Intent.EXTRA_STREAM
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION

class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private val client by lazy { OkHttpClient.Builder().build() }

    private var lastSavedFile: File? = null

    private val blockedHosts = setOf(
        "youtube.com", "www.youtube.com", "m.youtube.com",
        "music.youtube.com", "youtu.be", "www.youtu.be",
        "youtube-nocookie.com", "www.youtube-nocookie.com"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnDownload.setOnClickListener { onDownloadClick() }
        binding.btnShare.setOnClickListener { onShareClick() }

        binding.progress.progress = 0
        binding.txtStatus.text = "Durum: Hazır"
    }

    private fun onDownloadClick() {
        val url = binding.inputUrl.text.toString().trim()
        if (url.isEmpty()) {
            toast("Lütfen bir URL girin.")
            return
        }
        val host = try { URI(url).host?.lowercase(Locale.ROOT) ?: "" } catch (_: Exception) { "" }
        if (host in blockedHosts || host.endsWith(".youtube.com")) {
            toast("YouTube indirimi bu uygulamada desteklenmiyor.")
            binding.txtStatus.text = "Durum: YouTube engellendi"
            return
        }
        val formatIndex = binding.spinnerFormat.selectedItemPosition
        val wantMp3 = (formatIndex == 1)

        lifecycleScope.launchWhenStarted {
            binding.btnDownload.isEnabled = false
            binding.btnShare.isEnabled = false
            binding.progress.progress = 0
            binding.txtStatus.text = "Durum: İndiriliyor..."

            val downloadResult = withContext(Dispatchers.IO) {
                runCatching { downloadFile(url) }
            }.getOrElse {
                withContext(Dispatchers.Main) {
                    binding.txtStatus.text = "Durum: İndirme hatası: ${it.message}"
                    binding.btnDownload.isEnabled = true
                }
                return@launchWhenStarted
            }

            if (downloadResult == null) {
                binding.txtStatus.text = "Durum: İndirme başarısız."
                binding.btnDownload.isEnabled = true
                return@launchWhenStarted
            }

            if (wantMp3) {
                binding.txtStatus.text = "Durum: MP3'e dönüştürülüyor..."
                val mp3File = withContext(Dispatchers.IO) {
                    runCatching { convertToMp3(downloadResult) }.getOrNull()
                }
                if (mp3File == null) {
                    binding.txtStatus.text = "Durum: Dönüştürme başarısız."
                    binding.btnDownload.isEnabled = true
                    return@launchWhenStarted
                } else {
                    lastSavedFile = mp3File
                }
            } else {
                lastSavedFile = downloadResult
            }

            binding.txtStatus.text = "Durum: Tamamlandı → ${lastSavedFile?.name}"
            binding.btnDownload.isEnabled = true
            binding.btnShare.isEnabled = true
            binding.progress.progress = 100
        }
    }

    private suspend fun downloadFile(url: String): File? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")

            val contentType = resp.header("Content-Type") ?: "application/octet-stream"
            val ext = when {
                contentType.contains("mp4") -> "mp4"
                contentType.contains("webm") -> "webm"
                contentType.contains("audio/mpeg") -> "mp3"
                contentType.contains("audio/mp4") -> "m4a"
                else -> "bin"
            }

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
            val outFile = File(getExternalFilesDir(null), "LD_$stamp.$ext")

            resp.body?.byteStream()?.use { inStream ->
                FileOutputStream(outFile).use { out ->
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                    var totalRead = 0L
                    val contentLen = resp.body?.contentLength() ?: -1L

                    while (true) {
                        val read = inStream.read(buf)
                        if (read == -1) break
                        out.write(buf, 0, read)
                        totalRead += read
                        if (contentLen > 0) {
                            val pct = ((totalRead * 100) / contentLen).toInt().coerceIn(0, 100)
                            withContext(Dispatchers.Main) {
                                binding.progress.progress = pct
                                binding.txtStatus.text = "Durum: İndiriliyor... %$pct"
                            }
                        }
                    }
                    out.flush()
                }
            }
            return@use outFile
        }
    }

    private fun convertToMp3(inputFile: File): File? {
        val baseName = inputFile.nameWithoutExtension
        val outFile = File(inputFile.parentFile, "${baseName}.mp3")
        val cmd = "-y -i \"${inputFile.absolutePath}\" -vn -q:a 2 \"${outFile.absolutePath}\""
        val session = FFmpegKit.execute(cmd)
        return if (ReturnCode.isSuccess(session.returnCode)) outFile else null
    }

    private fun onShareClick() {
        val file = lastSavedFile ?: run {
            toast("Paylaşılacak dosya yok.")
            return
        }
        val uri: Uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
        val intent = Intent(ACTION_SEND).apply {
            type = "*/*"
            putExtra(EXTRA_STREAM, uri)
            addFlags(FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "Dosyayı paylaş"))
        } catch (e: ActivityNotFoundException) {
            toast("Paylaşım için uygulama bulunamadı.")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
