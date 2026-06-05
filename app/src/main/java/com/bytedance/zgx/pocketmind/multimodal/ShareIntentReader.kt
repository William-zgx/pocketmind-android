package com.bytedance.zgx.pocketmind.multimodal

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.bytedance.zgx.pocketmind.ChatImageAttachment
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64

class ShareIntentReader(
    private val context: Context,
    private val imageTextExtractor: ImageTextExtractor = MlKitImageTextExtractor(context),
    private val pdfPageTextExtractor: PdfPageTextExtractor =
        AndroidPdfPageTextExtractor(context, imageTextExtractor),
) {
    fun read(
        intent: Intent?,
        mode: SharedInputReadMode = SharedInputReadMode.LocalPrompt,
    ): SharedInput? {
        if (intent == null) return null
        val action = intent.action ?: return null
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
        if (mode == SharedInputReadMode.ProtectedSignal) {
            return intent.protectedSharedInput(action)
        }

        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val uris = when (action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.streamUri())
            Intent.ACTION_SEND_MULTIPLE -> intent.streamUris()
            else -> emptyList()
        }
        return readUris(uris = uris, text = text, intentMimeType = intent.type, mode = mode)
    }

    fun readUris(
        uris: List<Uri>,
        text: String = "",
        intentMimeType: String? = null,
        mode: SharedInputReadMode = SharedInputReadMode.LocalPrompt,
    ): SharedInput? {
        if (mode == SharedInputReadMode.ProtectedSignal) {
            val sourceCount = uris.take(MAX_SHARED_ATTACHMENTS).size + if (text.isNotBlank()) 1 else 0
            return SharedInput(
                text = "",
                attachments = emptyList(),
                protectedSourceCount = sourceCount,
            ).takeUnless { it.isEmpty }
        }
        if (mode == SharedInputReadMode.RemoteVision) {
            val limitedUris = uris.take(MAX_SHARED_ATTACHMENTS)
            val imageAttachments = limitedUris
                .mapNotNull { uri -> uri.toRemoteVisionImageAttachment(intentMimeType) }
            val protectedSourceCount =
                (if (text.isNotBlank()) 1 else 0) + (limitedUris.size - imageAttachments.size)
            return SharedInput(
                text = "",
                attachments = imageAttachments,
                protectedSourceCount = protectedSourceCount,
            ).takeUnless { it.isEmpty }
        }
        val attachments = uris
            .take(MAX_SHARED_ATTACHMENTS)
            .map { uri -> uri.toSharedAttachment(intentMimeType, mode) }
        return SharedInput(text = text, attachments = attachments)
            .takeUnless { it.isEmpty }
    }

    private fun Intent.streamUri(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_STREAM)
        }

    private fun Intent.streamUris(): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }

    private fun Intent.protectedSharedInput(action: String): SharedInput? {
        val hasText = hasExtra(Intent.EXTRA_TEXT)
        val streamCount = when (action) {
            Intent.ACTION_SEND -> if (hasExtra(Intent.EXTRA_STREAM)) 1 else 0
            Intent.ACTION_SEND_MULTIPLE -> streamUris().take(MAX_SHARED_ATTACHMENTS).size
            else -> 0
        }
        val sourceCount = streamCount + if (hasText) 1 else 0
        return SharedInput(
            text = "",
            attachments = emptyList(),
            protectedSourceCount = sourceCount,
        ).takeUnless { it.isEmpty }
    }

    private fun Uri.toSharedAttachment(intentMimeType: String?, mode: SharedInputReadMode): SharedAttachment {
        val metadata = queryMetadata(this)
        val resolvedMimeType = resolveSharedAttachmentMimeType(
            resolverMimeType = runCatching { context.contentResolver.getType(this) }.getOrNull(),
            displayName = metadata.displayName,
            intentMimeType = intentMimeType,
        )
        val kind = sharedAttachmentKindFor(resolvedMimeType)
        val textPreview = if (mode == SharedInputReadMode.LocalPrompt) {
            readSharedAttachmentTextPreview(
                mimeType = resolvedMimeType,
                kind = kind,
                openInputStream = { context.contentResolver.openInputStream(this) },
                extractImageText = { imageTextExtractor.extract(this) },
                extractPdfImageText = { pdfPageTextExtractor.extract(this) },
            )
        } else {
            null
        }
        val imageAttachment = if (mode == SharedInputReadMode.RemoteVision && kind == SharedAttachmentKind.Image) {
            toRemoteImageAttachment(resolvedMimeType, metadata.sizeBytes)
        } else {
            null
        }
        return SharedAttachment(
            kind = kind,
            mimeType = resolvedMimeType,
            displayName = metadata.displayName,
            sizeBytes = metadata.sizeBytes,
            textPreview = textPreview,
            imageAttachment = imageAttachment,
        )
    }

    private fun Uri.toRemoteVisionImageAttachment(intentMimeType: String?): SharedAttachment? {
        val resolverMimeType = runCatching { context.contentResolver.getType(this) }.getOrNull()
        val resolverHasConcreteType = resolverMimeType
            ?.substringBefore(';')
            ?.trim()
            ?.isNotBlank() == true
        if (resolverHasConcreteType && !resolverMimeType.isImageMimeHint()) {
            return null
        }
        if (!resolverMimeType.isImageMimeHint() && !intentMimeType.isImageMimeHint()) {
            return null
        }
        val metadata = queryMetadata(this)
        val resolvedMimeType = resolveSharedAttachmentMimeType(
            resolverMimeType = resolverMimeType,
            displayName = metadata.displayName,
            intentMimeType = intentMimeType,
        )
        val kind = sharedAttachmentKindFor(resolvedMimeType)
        if (kind != SharedAttachmentKind.Image) return null
        val imageAttachment = toRemoteImageAttachment(resolvedMimeType, metadata.sizeBytes) ?: return null
        return SharedAttachment(
            kind = kind,
            mimeType = resolvedMimeType,
            displayName = metadata.displayName,
            sizeBytes = metadata.sizeBytes,
            textPreview = null,
            imageAttachment = imageAttachment,
        )
    }

    private fun Uri.toRemoteImageAttachment(mimeType: String?, sizeBytes: Long?): ChatImageAttachment? {
        val normalizedMimeType = mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.takeIf { mediaType -> mediaType.startsWith("image/") }
            ?: return null
        if (sizeBytes != null && sizeBytes > MAX_REMOTE_IMAGE_BYTES) return null
        val bytes = runCatching {
            context.contentResolver.openInputStream(this)?.use { input ->
                input.readRemoteImageBytes(maxBytes = MAX_REMOTE_IMAGE_BYTES)
            }
        }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        val base64 = Base64.getEncoder().encodeToString(bytes)
        return ChatImageAttachment(
            mimeType = normalizedMimeType,
            dataUrl = "data:$normalizedMimeType;base64,$base64",
        )
    }

    private fun queryMetadata(uri: Uri): AttachmentMetadata {
        var displayName: String? = null
        var sizeBytes: Long? = null
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        displayName = cursor.getString(nameIndex)
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        sizeBytes = cursor.getLong(sizeIndex).takeIf { it >= 0L }
                    }
                }
            }
        }
        return AttachmentMetadata(displayName = displayName, sizeBytes = sizeBytes)
    }

    private data class AttachmentMetadata(
        val displayName: String?,
        val sizeBytes: Long?,
    )

    private companion object {
        const val MAX_SHARED_ATTACHMENTS = 5
        const val MAX_REMOTE_IMAGE_BYTES = 8 * 1024 * 1024
    }
}

internal fun readSharedAttachmentTextPreview(
    mimeType: String?,
    kind: SharedAttachmentKind,
    openInputStream: () -> InputStream?,
    extractImageText: () -> SharedTextPreview?,
    extractPdfImageText: () -> SharedTextPreview? = { null },
    mode: SharedInputReadMode = SharedInputReadMode.LocalPrompt,
): SharedTextPreview? =
    when {
        mode == SharedInputReadMode.ProtectedSignal -> null

        kind == SharedAttachmentKind.Document && canReadTextPreviewFor(mimeType) ->
            readSharedAttachmentTextPreviewFromStream(openInputStream) { input ->
                TextAttachmentPreviewReader.read(input)
            }

        kind == SharedAttachmentKind.Document && canReadRichTextPreviewFor(mimeType) ->
            readSharedAttachmentTextPreviewFromStream(openInputStream) { input ->
                RichTextPreviewReader.read(input, mimeType)
            }

        kind == SharedAttachmentKind.Document && canReadPdfTextPreviewFor(mimeType) ->
            readSharedAttachmentTextPreviewFromStream(openInputStream) { input ->
                PdfTextPreviewReader.read(input, mimeType)
            } ?: extractPdfImageText()

        kind == SharedAttachmentKind.Document && canReadOfficeOpenXmlTextPreviewFor(mimeType) ->
            readSharedAttachmentTextPreviewFromStream(openInputStream) { input ->
                OfficeOpenXmlPreviewReader.read(input, mimeType)
            }

        kind == SharedAttachmentKind.Image && canReadImageTextPreviewFor(mimeType) ->
            extractImageText()

        else -> null
    }

enum class SharedInputReadMode {
    LocalPrompt,
    ProtectedSignal,
    RemoteVision,
}

private fun InputStream.readRemoteImageBytes(maxBytes: Int): ByteArray? {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) return null
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun String?.isImageMimeHint(): Boolean =
    this
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
        ?.startsWith("image/") == true

private fun readSharedAttachmentTextPreviewFromStream(
    openInputStream: () -> InputStream?,
    readPreview: (InputStream) -> SharedTextPreview?,
): SharedTextPreview? =
    runCatching {
        openInputStream()?.use { input ->
            readPreview(input)
        }
    }.getOrNull()
