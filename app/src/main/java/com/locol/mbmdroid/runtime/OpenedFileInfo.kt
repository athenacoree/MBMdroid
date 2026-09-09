package com.locol.mbmdroid.runtime

import android.net.Uri

data class OpenedFileInfo(
    val uri: Uri,
    val mimeType: String,
    val displayName: String
)
