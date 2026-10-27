package io.github.andru1989.xmaudiostudio.platform

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import io.github.andru1989.xmaudiostudio.R
import io.github.andru1989.xmaudiostudio.XmAudioStudioApp

/**
 * The equalizer widget's list before Android 12 (later versions get it inside the widget's views).
 * Reads the status [PlatformSync] last published; never touches the session.
 */
class EqWidgetService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(private val context: Context) : RemoteViewsFactory {
        private var status: EqWidgetStatus? = null

        override fun onCreate() = Unit

        override fun onDataSetChanged() {
            status = (context as XmAudioStudioApp).container.platform.eqStatus.value
        }

        override fun onDestroy() = Unit

        override fun getCount(): Int = status?.profiles?.size ?: 0

        override fun getViewAt(position: Int): RemoteViews {
            val current = status
            val item = current?.profiles?.getOrNull(position) ?: return RemoteViews(context.packageName, R.layout.widget_eq_row)
            return EqWidgetProvider.row(context, item, item.id == current.activeId)
        }

        override fun getLoadingView(): RemoteViews? = null

        override fun getViewTypeCount(): Int = 1

        override fun getItemId(position: Int): Long = position.toLong()

        override fun hasStableIds(): Boolean = false
    }
}
