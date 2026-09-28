package com.jsalinas.grokwatch.complication

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.jsalinas.grokwatch.R
import com.jsalinas.grokwatch.ui.MainActivity

/** Static "Grok" complication; tapping it opens the app, which starts listening immediately. */
class GrokComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(type, tapAction = null)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        build(request.complicationType, tapAction())

    // A fresh task on every tap = a fresh conversation that starts listening right away.
    @SuppressLint("WearRecents")
    private fun tapAction(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun build(type: ComplicationType, tapAction: PendingIntent?): ComplicationData? {
        val icon = Icon.createWithResource(this, R.drawable.ic_grok)
        val description = PlainComplicationText.Builder(getString(R.string.complication_label)).build()
        val mono = MonochromaticImage.Builder(icon).build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                PlainComplicationText.Builder(getString(R.string.complication_short)).build(),
                description,
            ).setMonochromaticImage(mono).setTapAction(tapAction).build()

            ComplicationType.MONOCHROMATIC_IMAGE ->
                MonochromaticImageComplicationData.Builder(mono, description).setTapAction(tapAction).build()

            ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(
                SmallImage.Builder(icon, SmallImageType.ICON).build(),
                description,
            ).setTapAction(tapAction).build()

            else -> null
        }
    }
}
