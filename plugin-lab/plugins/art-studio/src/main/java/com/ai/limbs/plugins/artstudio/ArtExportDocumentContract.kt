package com.ai.limbs.plugins.artstudio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

internal data class ArtExportDocument(val mime: String, val name: String)

/** MIME and filename travel together, including a full animation's image/gif output. */
internal class ArtExportDocumentContract : ActivityResultContract<ArtExportDocument, Uri?>() {
    override fun createIntent(context: Context, input: ArtExportDocument): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE).setType(input.mime).putExtra(Intent.EXTRA_TITLE, input.name)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
}
