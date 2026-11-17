package com.example.knowhub.input

import androidx.compose.runtime.Composable

/** 用户选中的文件/照片，字节已在内存中（客户端限制 10MB）。 */
data class PickedAttachment(
    val name: String,
    val mime: String,
    val bytes: ByteArray,
)

/**
 * 输入侧平台能力：语音转文字、文件选择、拍照（+端侧 OCR）。
 * Android 用系统识别器 / SAF / 相机 + ML Kit；其他端为空实现（按钮会提示不支持）。
 */
interface PlatformInput {
    val voiceSupported: Boolean
    val fileSupported: Boolean
    val cameraSupported: Boolean
    val ocrSupported: Boolean

    fun startVoice(onResult: (String) -> Unit, onError: (String) -> Unit)
    fun pickFile(onPicked: (PickedAttachment) -> Unit, onError: (String) -> Unit)
    fun takePhoto(onPicked: (PickedAttachment) -> Unit, onError: (String) -> Unit)
    suspend fun recognizeText(photo: PickedAttachment): String
}

@Composable
expect fun rememberPlatformInput(): PlatformInput

/** 供 iOS / JS / JVM 等未实现平台复用。 */
class NoopPlatformInput : PlatformInput {
    override val voiceSupported: Boolean = false
    override val fileSupported: Boolean = false
    override val cameraSupported: Boolean = false
    override val ocrSupported: Boolean = false

    override fun startVoice(onResult: (String) -> Unit, onError: (String) -> Unit) {
        onError("当前平台暂不支持语音输入")
    }

    override fun pickFile(onPicked: (PickedAttachment) -> Unit, onError: (String) -> Unit) {
        onError("当前平台暂不支持文件上传")
    }

    override fun takePhoto(onPicked: (PickedAttachment) -> Unit, onError: (String) -> Unit) {
        onError("当前平台暂不支持拍照")
    }

    override suspend fun recognizeText(photo: PickedAttachment): String = ""
}
