package tech.yaya.agente

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * One row per app — icon, name, optional sub-label, a switch — shared by the
 * end-of-onboarding apps screen ([AppsSetupActivity]) and Settings
 * ([MainActivity]) so the two never drift apart.
 */
object AppToggles {

    fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        // Not visible to us (Android 11+ package visibility): an app this
        // phone learned proved it exists by posting notifications.
        ProfileStore.get(ctx, pkg) != null
    }

    fun appIcon(ctx: Context, pkg: String): Drawable? = try {
        ctx.packageManager.getApplicationIcon(pkg)
    } catch (_: Exception) {
        ContextCompat.getDrawable(ctx, R.drawable.ic_bell)?.apply {
            setTint(ContextCompat.getColor(ctx, R.color.agento_on_surface_muted))
        }
    }

    /**
     * Adds a row to [parent]. Uninstalled apps render dimmed with the switch
     * disabled: a toggle for an app that cannot post a notification is a lie.
     */
    fun addRow(
        ctx: Context,
        parent: LinearLayout,
        pkg: String,
        name: String,
        installed: Boolean,
        subLabel: String?,
        checked: Boolean,
        switchEnabled: Boolean = installed,
        onToggle: (Boolean) -> Unit,
    ): MaterialSwitch {
        val res = ctx.resources
        val iconSize = res.getDimensionPixelSize(R.dimen.space_xl)
        val gap = res.getDimensionPixelSize(R.dimen.space_m)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = res.getDimensionPixelSize(R.dimen.touch_min)
            alpha = if (installed) 1f else 0.45f
        }
        row.addView(ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = gap }
            setImageDrawable(appIcon(ctx, pkg))
            // Decorative: the name TextView right next to it carries the label.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        val labels = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        labels.addView(TextView(ctx).apply {
            text = name
            setTextAppearance(R.style.TextAppearance_Agento_Body)
            setTextColor(ContextCompat.getColor(ctx, R.color.agento_on_surface))
        })
        if (subLabel != null) labels.addView(TextView(ctx).apply {
            text = subLabel
            setTextAppearance(R.style.TextAppearance_Agento_Label)
            setTextColor(ContextCompat.getColor(ctx, R.color.agento_on_surface_muted))
        })
        row.addView(labels)
        val sw = MaterialSwitch(ctx).apply {
            isEnabled = switchEnabled
            isChecked = installed && checked
            contentDescription = name
            setOnCheckedChangeListener { _, on -> onToggle(on) }
        }
        row.addView(sw)
        parent.addView(row)
        return sw
    }
}
