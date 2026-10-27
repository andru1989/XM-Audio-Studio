package io.github.andru1989.xmaudiostudio.platform

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.widget.RemoteViews
import androidx.core.net.toUri
import io.github.andru1989.xmaudiostudio.MainActivity
import io.github.andru1989.xmaudiostudio.R
import io.github.andru1989.xmaudiostudio.XmAudioStudioApp
import io.github.andru1989.xmaudiostudio.core.presets.ProfileCategory

/**
 * Home-screen equalizer widget (classic RemoteViews): the active profile, ◀ / ▶ to step through
 * the profiles, and the full list of the selected model's profiles; a tap on a row applies it.
 *
 * Same update policy as [NoiseWidgetProvider]: no polling, [PlatformSync] pushes every distinct
 * [EqWidgetStatus] while the process lives. The list is sent with the views on Android 12+
 * (RemoteCollectionItems); older versions read it through [EqWidgetService].
 */
class EqWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val container = (context.applicationContext as XmAudioStudioApp).container
        container.restoreSavedTarget()
        container.platform.refresh()
        push(context, manager, appWidgetIds, container.platform.eqStatus.value)
    }

    companion object {

        /** Pushes [status] to every placed widget; a no-op when none is placed. */
        fun updateAll(context: Context, status: EqWidgetStatus) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(context, EqWidgetProvider::class.java)) }.getOrNull()
            if (ids == null || ids.isEmpty()) return
            push(context, manager, ids, status)
        }

        private fun push(context: Context, manager: AppWidgetManager, ids: IntArray, status: EqWidgetStatus) {
            ids.forEach { id -> manager.updateAppWidget(id, render(context, id, status)) }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                @Suppress("DEPRECATION")
                manager.notifyAppWidgetViewDataChanged(ids, R.id.eq_list)
            }
        }

        fun render(context: Context, appWidgetId: Int, status: EqWidgetStatus): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_eq)
            val ready = status.base.availability == Availability.READY
            val active = status.profiles.firstOrNull { it.id == status.activeId }

            val title = when {
                !ready -> context.getString(R.string.eq_widget_unknown)
                active != null -> active.name
                status.unsavedCurve -> context.getString(R.string.eq_widget_custom)
                else -> context.getString(R.string.eq_widget_unknown)
            }
            views.setTextViewText(R.id.eq_title, title)
            views.setTextViewText(R.id.eq_status, statusLine(context, status, ready))

            val openApp = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.eq_header, openApp)
            // Set here too: the layout's XML value is resolved in the launcher's locale, not the app's.
            views.setContentDescription(R.id.eq_prev, context.getString(R.string.eq_widget_prev))
            views.setContentDescription(R.id.eq_next, context.getString(R.string.eq_widget_next))
            views.setTextViewText(R.id.eq_empty, context.getString(R.string.eq_widget_empty))
            // Not ready: every control opens the app, where the missing piece can be fixed.
            views.setOnClickPendingIntent(R.id.eq_prev, if (ready) EqProfileReceiver.stepIntent(context, -1) else openApp)
            views.setOnClickPendingIntent(R.id.eq_next, if (ready) EqProfileReceiver.stepIntent(context, +1) else openApp)
            views.setPendingIntentTemplate(R.id.eq_list, if (ready) EqProfileReceiver.applyTemplate(context) else openApp)
            views.setEmptyView(R.id.eq_list, R.id.eq_empty)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val items = RemoteViews.RemoteCollectionItems.Builder().setViewTypeCount(1)
                status.profiles.forEachIndexed { index, item -> items.addItem(index.toLong(), row(context, item, item.id == status.activeId)) }
                views.setRemoteAdapter(R.id.eq_list, items.build())
            } else {
                val adapter = Intent(context, EqWidgetService::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                // A distinct data URI per widget, so each one gets its own factory.
                adapter.data = adapter.toUri(Intent.URI_INTENT_SCHEME).toUri()
                @Suppress("DEPRECATION")
                views.setRemoteAdapter(R.id.eq_list, adapter)
            }
            return views
        }

        /** One list row; the fill-in intent carries the profile id to [EqProfileReceiver]. */
        fun row(context: Context, item: EqWidgetItem, active: Boolean): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_eq_row)
            views.setInt(R.id.eq_row, "setBackgroundResource", if (active) R.drawable.widget_chip_selected else R.drawable.widget_chip)
            views.setTextViewText(R.id.eq_row_name, if (active) bold(item.name) else item.name)
            views.setTextViewText(R.id.eq_row_tag, context.getString(categoryLabel(item.category)))
            views.setContentDescription(
                R.id.eq_row,
                context.getString(if (active) R.string.eq_widget_active_description else R.string.eq_widget_apply_description, item.name),
            )
            views.setOnClickFillInIntent(R.id.eq_row, Intent().putExtra(EqProfileReceiver.EXTRA_PROFILE_ID, item.id))
            return views
        }

        private fun statusLine(context: Context, status: EqWidgetStatus, ready: Boolean): String {
            if (ready && status.blocked) return context.getString(R.string.eq_widget_blocked)
            val line = PlatformText.widgetStatus(context, status.base)
            val model = PlatformText.model(context, status.base)
            return if (model != null && ready) context.getString(R.string.platform_model_line, model, line) else line
        }

        private fun categoryLabel(category: ProfileCategory): Int = when (category) {
            ProfileCategory.DEVICE_PRESET -> R.string.cat_device_preset
            ProfileCategory.MEASURED -> R.string.cat_measured
            ProfileCategory.SUBJECTIVE -> R.string.cat_subjective
            ProfileCategory.USER -> R.string.cat_user
        }

        private fun bold(text: String): CharSequence =
            SpannableString(text).apply { setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }
}
