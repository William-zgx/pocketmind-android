package com.bytedance.zgx.solin.multimodal

import com.bytedance.zgx.solin.ChatImageAttachment
import java.util.Base64

/**
 * Bridges raw JPEG screenshot bytes into a [ChatImageAttachment] for a single remote-vision send.
 *
 * Mirrors the in-memory-bytes → data URL construction in
 * `ShareIntentReader.Uri.toRemoteImageAttachment` (the proven remote image transport path):
 * compact for vision, base64-encode, wrap as `data:image/jpeg;base64,...`. The resulting dataUrl
 * has exactly the shape `RemoteChatRuntime.userMessageJson` consumes for the OpenAI multimodal
 * `image_url.url` part.
 *
 * Returns `null` if the bytes are empty or vision compaction fails — callers fail closed (no send).
 * The bytes are treated as transient throughout; nothing is persisted.
 */
internal fun rawScreenshotJpegToChatImageAttachment(jpegBytes: ByteArray): ChatImageAttachment? {
    if (jpegBytes.isEmpty()) return null
    val compacted = jpegBytes.compactedImageBytesForVision() ?: return null
    val base64 = Base64.getEncoder().encodeToString(compacted)
    return ChatImageAttachment(
        mimeType = "image/jpeg",
        dataUrl = "data:image/jpeg;base64,$base64",
    )
}
