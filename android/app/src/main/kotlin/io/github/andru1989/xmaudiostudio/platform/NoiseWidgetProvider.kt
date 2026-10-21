package io.github.andru1989.xmaudiostudio.platform

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.View
import android.widget.RemoteViews
import io.github.andru1989.xmaudiostudio.MainActivity
import io.github.andru1989.xmaudiostudio.R
import io.github.andru1989.xmaudiostudio.XmAudioStudioApp
import io.github.andru1989.xmaudiostudio.core.model.AmbientSoundMode

/**
 * Home-screen widget (classic RemoteViews): model, battery, noise mode and three mode buttons.
 *
 * The widget never polls (`updatePeriodMillis = 0`). While the process is alive, [PlatformSync]
 * pushes every distinct status; after the process dies the launcher keeps the last pushed views,
 * which by then say "último estado conocido" with an absolute time.
 */
class NoiseWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val container = (context.applicationContext as XmAudioStudioApp).container
        container.restoreSavedTarget()
        val status = container.platform.refresh()
        manager.updateAppWidget(appWidgetIds, render(context, status))
    }

    companion object {
        private data class ModeButton(
            val mode: AmbientSoundMode,
            val container: Int,
            val label: Int,
            val text: Int,
            val description: Int,
        )

        private val buttons = listOf(
            ModeButton(AmbientSoundMode.NOISE_CANCELLING, R.id.widget_button_nc, R.id.widget_button_nc_label, R.string.widget_button_nc, R.string.widget_button_nc_description),
            ModeButton(AmbientSoundMode.AMBIENT_SOUND, R.id.widget_button_ambient, R.id.widget_button_ambient_label, R.string.widget_button_ambient, R.string.widget_button_ambient_description),
            ModeButton(AmbientSoundMode.OFF, R.id.widget_button_off, R.id.widget_button_off_label, R.string.widget_button_off, R.string.widget_button_off_description),
        )

        /** Pushes [status] to every placed widget; a no-op when none is placed. */
        fun updateAll(context: Context, status: NoiseControlStatus) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(context, NoiseWidgetProvider::class.java)) }.getOrNull()
            if (ids == null || ids.isEmpty()) return
            manager.updateAppWidget(ids, render(context, status))
        }

        fun render(context: Context, status: NoiseControlStatus): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_noise_control)
            val ready = status.availability == Availability.READY
            val mode = status.mode.takeIf { ready }

            views.setTextViewText(R.id.widget_mode, PlatformText.modeName(context, mode))
            views.setImageViewResource(R.id.widget_mode_icon, PlatformText.modeIcon(mode))
            val battery = PlatformText.battery(context, status).takeIf { ready }
            views.setTextViewText(R.id.widget_battery, battery.orEmpty())
            views.setViewVisibility(R.id.widget_battery, if (battery == null) View.GONE else View.VISIBLE)
            val statusLine = PlatformText.widgetStatus(context, status)
            val model = PlatformText.model(context, status)
            views.setTextViewText(
                R.id.widget_status,
                if (model != null && ready) context.getString(R.string.platform_model_line, model, statusLine) else statusLine,
            )

            val openApp = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(android.R.id.background, openApp)

            buttons.forEachIndexed { index, button ->
                val selected = mode == button.mode
                views.setInt(button.container, "setBackgroundResource", if (selected) R.drawable.widget_chip_selected else R.drawable.widget_chip)
                val label = context.getString(button.text)
                views.setTextViewText(button.label, if (selected) bold(label) else label)
                // Set here too: the layout's XML value is resolved in the launcher's locale, not the app's.
                views.setContentDescription(button.container, context.getString(button.description))
                // Not ready: the buttons open the app, where the missing piece can be fixed.
                views.setOnClickPendingIntent(button.container, if (ready) NoiseControlReceiver.pendingIntent(context, button.mode, index + 1) else openApp)
            }
            return views
        }

        private fun bold(text: String): CharSequence =
            SpannableString(text).apply { setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }
}
