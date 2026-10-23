package de.oventa.tvlauncher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

data class TvApp(
    val packageName: String,
    val label: String,
    val banner: ImageBitmap?,
    val icon: ImageBitmap?
)

data class TvInput(val id: String, val label: String)

/** Standard-Auswahl: SmartTube statt YouTube. YouTube bleibt installiert, ist aber versteckt. */
val DEFAULT_VISIBLE = listOf(
    "org.smarttube.stable",
    "com.netflix.ninja",
    "tv.twitch.android.viewer",
    "org.jellyfin.androidtv",
    "ch.protonvpn.android",
    "org.xbmc.kodi"
)

object Apps {

    fun load(context: Context): List<TvApp> {
        val pm = context.packageManager
        val result = LinkedHashMap<String, TvApp>()
        val cats = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
        for (cat in cats) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(cat)
            val list = try { pm.queryIntentActivities(intent, 0) } catch (e: Exception) { emptyList() }
            for (ri in list) {
                val pkg = ri.activityInfo.packageName
                if (pkg == context.packageName || result.containsKey(pkg)) continue
                val label = ri.loadLabel(pm)?.toString() ?: pkg
                val bannerD: Drawable? = try {
                    ri.activityInfo.loadBanner(pm) ?: ri.activityInfo.applicationInfo.loadBanner(pm)
                } catch (e: Exception) { null }
                val iconD: Drawable? = try { ri.loadIcon(pm) } catch (e: Exception) { null }
                result[pkg] = TvApp(
                    packageName = pkg,
                    label = label,
                    banner = bannerD?.let { toBitmap(it, 480, 270) },
                    icon = iconD?.let { toBitmap(it, 192, 192) }
                )
            }
        }
        return result.values.sortedBy { it.label.lowercase() }
    }

    fun launch(context: Context, pkg: String): Boolean {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try { context.startActivity(intent); true } catch (e: Exception) { false }
    }

    fun inputs(context: Context): List<TvInput> {
        val tim = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return emptyList()
        return try {
            tim.tvInputList
                .filter { it.isPassthroughInput && it.type != TvInputInfo.TYPE_TUNER }
                .map { info ->
                    val custom = info.loadCustomLabel(context)?.toString()
                    TvInput(info.id, if (!custom.isNullOrBlank()) custom else info.loadLabel(context).toString())
                }
        } catch (e: Exception) { emptyList() }
    }

    fun openInput(context: Context, inputId: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputId))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { context.startActivity(intent); true } catch (e: Exception) { false }
    }

    fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }

    private fun toBitmap(d: Drawable, w: Int, h: Int): ImageBitmap {
        val iw = if (d.intrinsicWidth > 0) d.intrinsicWidth else w
        val ih = if (d.intrinsicHeight > 0) d.intrinsicHeight else h
        val scale = minOf(w.toFloat() / iw, h.toFloat() / ih, 2f)
        val bw = (iw * scale).toInt().coerceAtLeast(1)
        val bh = (ih * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT)
        d.setBounds(0, 0, bw, bh)
        d.draw(c)
        return bmp.asImageBitmap()
    }
}
