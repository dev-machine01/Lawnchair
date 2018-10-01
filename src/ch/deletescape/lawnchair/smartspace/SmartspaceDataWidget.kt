/*
 *     This file is part of Lawnchair Launcher.
 *
 *     Lawnchair Launcher is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Lawnchair Launcher is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with Lawnchair Launcher.  If not, see <https://www.gnu.org/licenses/>.
 */

package ch.deletescape.lawnchair.smartspace

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.support.annotation.Keep
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.text.TextUtils
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import ch.deletescape.lawnchair.BlankActivity
import ch.deletescape.lawnchair.getAllChilds
import ch.deletescape.lawnchair.lawnchairApp
import ch.deletescape.lawnchair.runOnMainThread
import com.android.launcher3.R
import com.android.launcher3.Utilities

@Keep
class SmartspaceDataWidget(controller: LawnchairSmartspaceController) : LawnchairSmartspaceController.DataProvider(controller) {

    private val context = controller.context
    private val prefs = Utilities.getLawnchairPrefs(context)
    private val smartspaceWidgetHost = SmartspaceWidgetHost()
    private var smartspaceView: SmartspaceWidgetHostView? = null
    private val widgetIdPref = prefs::smartspaceWidgetId
    private val providerInfo = getSmartspaceWidgetProvider(context)
    private var isWidgetBound = false

    init {
        if (!Utilities.ATLEAST_NOUGAT) throw IllegalStateException("only available on Nougat and above")
    }

    private fun startBinding() {
        val widgetManager = AppWidgetManager.getInstance(context)

        var widgetId = widgetIdPref.get()
        val widgetInfo = widgetManager.getAppWidgetInfo(widgetId)
        isWidgetBound = widgetInfo != null && widgetInfo.provider == providerInfo.provider

        val oldWidgetId = widgetId
        if (!isWidgetBound) {
            if (widgetId > -1) {
                // widgetId is already bound and its not the correct provider. reset host.
                smartspaceWidgetHost.deleteHost()
            }

            widgetId = smartspaceWidgetHost.allocateAppWidgetId()
            isWidgetBound = widgetManager.bindAppWidgetIdIfAllowed(
                    widgetId, providerInfo.profile, providerInfo.provider, null)
        }

        if (isWidgetBound) {
            smartspaceView = smartspaceWidgetHost.createView(context, widgetId, providerInfo) as SmartspaceWidgetHostView
            smartspaceWidgetHost.startListening()
            onSetupComplete()
        } else {
            val bindIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, providerInfo.provider)
            BlankActivity.startActivityForResult(context, bindIntent, 1028, 0) { resultCode, _ ->
                if (resultCode == Activity.RESULT_OK) {
                    startBinding()
                } else {
                    smartspaceWidgetHost.deleteAppWidgetId(widgetId)
                    widgetId = -1
                    widgetIdPref.set(-1)
                    onSetupComplete()
                }
            }
        }

        if (oldWidgetId != widgetId) {
            widgetIdPref.set(widgetId)
        }
    }

    override fun performSetup() {
        startBinding()
    }

    override fun waitForSetup() {
        super.waitForSetup()

        if (!isWidgetBound) throw IllegalStateException("widget must be bound")
    }

    override fun onDestroy() {
        super.onDestroy()

        smartspaceWidgetHost.stopListening()
    }

    fun updateData(weatherIcon: Bitmap?, temperature: String?, cardIcon: Bitmap?, title: TextView?, subtitle: TextView?) {
        val weather = parseWeatherData(weatherIcon, temperature)
        val card = if (cardIcon != null && title != null && subtitle != null) {
            LawnchairSmartspaceController.CardData(cardIcon,
                    title.text.toString(), title.ellipsize, subtitle.text.toString(), subtitle.ellipsize)
        } else {
            null
        }
        updateData(weather, card)
    }

    inner class SmartspaceWidgetHost : AppWidgetHost(context, 1027) {

        override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView {
            return SmartspaceWidgetHostView(context)
        }
    }

    inner class SmartspaceWidgetHostView(context: Context) : AppWidgetHostView(context) {

        @Suppress("UNCHECKED_CAST")
        override fun updateAppWidget(remoteViews: RemoteViews?) {
            super.updateAppWidget(remoteViews)

            val childs = getAllChilds()
            val texts = (childs.filter { it is TextView } as List<TextView>).filter { !TextUtils.isEmpty(it.text) }
            val images = childs.filter { it is ImageView } as List<ImageView>
            var weatherIconView: ImageView? = null
            var temperature = "0C"
            var cardIconView: ImageView? = null
            var title: TextView? = null
            var subtitle: TextView? = null
            if (texts.isEmpty()) return
            if (images.size >= 2) {
                weatherIconView = images.last()
                temperature = texts.last().text.toString()
            }
            if (images.isNotEmpty() && images.size != 2) {
                cardIconView = images.first()
                title = texts[0]
                subtitle = texts[1]
            }
            updateData(extractBitmap(weatherIconView), temperature, extractBitmap(cardIconView), title, subtitle)
        }
    }

    private fun extractBitmap(imageView: ImageView?): Bitmap? {
        return (imageView?.drawable as? BitmapDrawable)?.bitmap
    }

    companion object {

        private const val TAG = "SmartspaceDataWidget"
        private const val googlePackage = "com.google.android.googlequicksearchbox"
        private const val smartspaceComponent = "com.google.android.apps.gsa.staticplugins.smartspace.widget.SmartspaceWidgetProvider"

        private val smartspaceProviderComponent = ComponentName(googlePackage, smartspaceComponent)

        fun getSmartspaceWidgetProvider(context: Context): AppWidgetProviderInfo {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val providers = appWidgetManager.installedProviders.filter { it.provider == smartspaceProviderComponent }
            val provider = providers.firstOrNull()
            if (provider != null) {
                return provider
            } else {
                runOnMainThread {
                    val foreground = context.lawnchairApp.activityHandler.foregroundActivity ?: context
                    if (foreground is AppCompatActivity) {
                        AlertDialog.Builder(foreground)
                                .setTitle(R.string.smartspace_provider_error)
                                .setMessage(R.string.smartspace_widget_provider_not_found)
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                    }
                }
                throw RuntimeException("smartspace widget not found")
            }
        }

        fun parseWeatherData(weatherIcon: Bitmap?, temperature: String?): LawnchairSmartspaceController.WeatherData? {
            return if (weatherIcon != null && temperature != null) {
                try {
                    val temperatureAmount = temperature.substring(0, temperature.indexOfFirst { it < '0' || it > '9' })
                    LawnchairSmartspaceController.WeatherData(weatherIcon, temperatureAmount.toInt(), temperature.contains("C"))
                } catch (e: NumberFormatException) {
                    null
                }
            } else {
                null
            }
        }
    }
}
