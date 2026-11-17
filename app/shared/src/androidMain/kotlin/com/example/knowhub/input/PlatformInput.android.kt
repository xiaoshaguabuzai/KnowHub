package com.example.knowhub.input

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

private const val MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024

private class Handler<T> {
    var onSuccess: ((T) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun success(value: T) {
        val callback = onSuccess
        clear()
        callback?.invoke(value)
    }

    fun error(message: String) {
        val callback = onError
        clear()
        callback?.invoke(message)
    }

    fun clear() {
        onSuccess = null
        onError = null
    }
}

private class AndroidPlatformInput(
    private val context: Context,
    private val ocr: TextRecognizer,
    private val voiceLauncher: androidx.activity.result.ActivityResultLauncher<Intent>,
    private val fileLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    private val photoLauncher: androidx.activity.result.ActivityResultLauncher<Void?>,
    private val voiceHandler: Handler<String>,
    private val fileHandler: Handler<PickedAttachment>,
    private val photoHandler: Handler<PickedAttachment>,
) : PlatformInput {

    override val voiceSupported: Boolean = true
    override val fileSupported: Boolean = true
    override val cameraSupported: Boolean = true
    override val ocrSupported: Boolean = true

    override fun startVoice(onResult: (String) -> Unit, onError: (String) -> Unit) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "请说出你的问题")
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            onError("设备未安装语音识别服务")
            return
        }
        voiceHandler.onSuccess = onResult
        voiceHandler.onError = onError
        runCatching { voiceLauncher.launch(intent) }.onFailure {
            voiceHandler.clear()
            onError("无法启动语音识别：${it.message}")
        }
    }

    override fun pickFile(onPicked: (PickedAttachment) -> Unit, onError: (String) -> Unit) {
        fileHandler.onSuccess = onPicked
        fileHandler.onError = onError
        runCatching {
            fileLauncher.launch(
                arrayOf(
                    "application/pdf",
                    "text/plain",
                    "text/markdown",
                    "text/csv",
                    "application/json",
                    "image/*",
                )
            )
        }.onFailure {
            fileHandler.clear()
            onError("无法打开文件选择器：${it.message}")
        }
    }

    override fun takePhoto(onPicked: (PickedAttachment) -> Unit, onError: (String) -> Unit) {
        photoHandler.onSuccess = onPicked
        photoHandler.onError = onError
        runCatching { photoLauncher.launch(null) }.onFailure {
            photoHandler.clear()
            onError("无法启动相机：${it.message}")
        }
    }

    override suspend fun recognizeText(photo: PickedAttachment): String {
        val bitmap = BitmapFactory.decodeByteArray(photo.bytes, 0, photo.bytes.size) ?: return ""
        val image = InputImage.fromBitmap(bitmap, 0)
        return suspendCancellableCoroutine { continuation ->
            ocr.process(image)
                .addOnSuccessListener { result ->
                    if (continuation.isActive) continuation.resume(result.text)
                }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resume("")
                }
        }
    }

    fun onVoiceResult(result: ActivityResult) {
        if (result.resultCode != Activity.RESULT_OK) {
            voiceHandler.error("语音输入已取消")
            return
        }
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        if (text.isBlank()) voiceHandler.error("没有识别到语音内容，请重试") else voiceHandler.success(text)
    }

    fun onFilePicked(uri: Uri?) {
        if (uri == null) {
            fileHandler.error("未选择文件")
            return
        }
        runCatching { readFile(uri) }
            .onSuccess { fileHandler.success(it) }
            .onFailure { fileHandler.error(it.message ?: "读取文件失败") }
    }

    fun onPhotoTaken(bitmap: Bitmap?) {
        if (bitmap == null) {
            photoHandler.error("拍照已取消")
            return
        }
        runCatching { bitmapToAttachment(bitmap) }
            .onSuccess { photoHandler.success(it) }
            .onFailure { photoHandler.error(it.message ?: "处理照片失败") }
    }

    private fun readFile(uri: Uri): PickedAttachment {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        var name = "attachment"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) name = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: name
        }
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("无法读取文件")
        if (bytes.isEmpty()) throw IllegalStateException("文件内容为空")
        if (bytes.size > MAX_ATTACHMENT_BYTES) throw IllegalStateException("附件不能超过 10MB")
        return PickedAttachment(name = name, mime = mime, bytes = bytes)
    }

    private fun bitmapToAttachment(bitmap: Bitmap): PickedAttachment {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)
        val bytes = output.toByteArray()
        if (bytes.size > MAX_ATTACHMENT_BYTES) throw IllegalStateException("照片过大，请靠近拍摄或压缩后重试")
        return PickedAttachment(
            name = "photo-${System.currentTimeMillis()}.jpg",
            mime = "image/jpeg",
            bytes = bytes,
        )
    }
}

@Composable
actual fun rememberPlatformInput(): PlatformInput {
    val context = LocalContext.current
    val ocr = remember { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    val voiceHandler = remember { Handler<String>() }
    val fileHandler = remember { Handler<PickedAttachment>() }
    val photoHandler = remember { Handler<PickedAttachment>() }

    lateinit var input: AndroidPlatformInput

    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        input.onVoiceResult(result)
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        input.onFilePicked(uri)
    }
    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        input.onPhotoTaken(bitmap)
    }

    input = remember(context, ocr, voiceLauncher, fileLauncher, photoLauncher) {
        AndroidPlatformInput(
            context = context,
            ocr = ocr,
            voiceLauncher = voiceLauncher,
            fileLauncher = fileLauncher,
            photoLauncher = photoLauncher,
            voiceHandler = voiceHandler,
            fileHandler = fileHandler,
            photoHandler = photoHandler,
        )
    }
    return input
}
