package com.goodwy.commons.views

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.view.menu.MenuBuilder
import com.goodwy.commons.R
import com.goodwy.commons.databinding.PopupMenuBlurBinding
import com.goodwy.commons.extensions.getProperBlurOverlayColor
import com.goodwy.commons.extensions.getProperTextColor
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

/**
 * Custom popup menu with blur effect, similar to dialogs.
 * Can be used as a drop-in replacement for PopupMenu.
 */
class BlurPopupMenu(
    context: Context,
    anchor: View,
    gravity: Int = Gravity.NO_GRAVITY,
    touchX: Float = -1f,
    touchY: Float = -1f
) {
    private val context: Context = context
    private val anchor: View = anchor
    private val gravity: Int = gravity
    private val touchX: Float = touchX
    private val touchY: Float = touchY
    val menu: Menu = MenuBuilder(context)
    private val menuInflater: MenuInflater = MenuInflater(context)
    private var onMenuItemClickListener: MenuItem.OnMenuItemClickListener? = null
    private var popupWindow: PopupWindow? = null

    /**
     * Inflate a menu resource into this popup menu.
     */
    fun inflate(menuRes: Int) {
        menuInflater.inflate(menuRes, menu)
    }

    /**
     * Set a listener that will be invoked when a menu item is clicked.
     */
    fun setOnMenuItemClickListener(listener: MenuItem.OnMenuItemClickListener?) {
        onMenuItemClickListener = listener
    }

    /**
     * Show the popup menu anchored to the view specified during construction.
     */
    fun show() {
        val activity = context as? Activity ?: return
        val blurTarget = activity.findViewById<BlurTarget>(R.id.mainBlurTarget) ?: return

        // Create custom popup window with blur
        val inflater = LayoutInflater.from(context)
        val popupBinding = PopupMenuBlurBinding.inflate(inflater, null, false)

        // Setup rounded corners
        popupBinding.root.clipToOutline = true

        // Setup BlurView
        val blurView = popupBinding.blurView
        val decorView = activity.window.decorView
        val windowBackground = decorView.background

        blurView.setOverlayColor(context.getProperBlurOverlayColor())
        blurView.setupWith(blurTarget)
            .setFrameClearDrawable(windowBackground)
            .setBlurRadius(8f)
            .setBlurAutoUpdate(true)

        // Create menu items
        val menuContainer = popupBinding.menuContainer
        val textColor = context.getProperTextColor()
        val visibleItems = (0 until menu.size())
            .mapNotNull { menu.getItem(it) }
            .filter { it.isVisible }

        visibleItems.forEach { item ->
            val menuItemView = inflater.inflate(R.layout.item_popup_menu, menuContainer, false)
            val titleView = menuItemView.findViewById<TextView>(R.id.menu_item_title)

            titleView.text = item.title
            titleView.setTextColor(textColor)

            menuItemView.setOnClickListener {
                val handled = onMenuItemClickListener?.onMenuItemClick(item) ?: false
                if (handled) {
                    popupWindow?.dismiss()
                }
            }

            menuContainer.addView(menuItemView)
        }

        // Don't show if no visible items
        if (visibleItems.isEmpty()) {
            return
        }

        // Measure the content
        popupBinding.root.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        // Calculate position based on touch position or gravity
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val rootLocation = IntArray(2)
        activity.window.decorView.rootView.getLocationOnScreen(rootLocation)

        val x: Int
        val y: Int

        // If touchX is provided and valid, position menu based on touch position
        if (touchX >= 0 && anchor.width > 0) {
            val screenWidth = activity.resources.displayMetrics.widthPixels
            val screenHeight = activity.resources.displayMetrics.heightPixels
            val menuWidth = popupBinding.root.measuredWidth
            val menuHeight = popupBinding.root.measuredHeight
            val offset = activity.resources.getDimensionPixelSize(R.dimen.smaller_margin)
            
            // Calculate X position: center menu on touch point, or align to touch point
            val touchXInt = touchX.toInt()
            val anchorX = location[0]
            val touchScreenX = anchorX + touchXInt
            
            // Try to center menu on touch point, but keep within screen bounds
            var menuX = touchScreenX - menuWidth / 2
            
            // Keep within screen bounds
            if (menuX < offset) {
                menuX = offset
            } else if (menuX + menuWidth > screenWidth - offset) {
                menuX = screenWidth - menuWidth - offset
            }
            
            x = menuX - rootLocation[0]
            
            // Calculate Y position based on touchY if provided
            if (touchY >= 0 && anchor.height > 0) {
                val touchYInt = touchY.toInt()
                val anchorY = location[1]
                val touchScreenY = anchorY + touchYInt
                
                // Try to position menu centered on touch point vertically
                var menuY = touchScreenY - menuHeight / 2
                
                // Keep within screen bounds - prefer below touch point if near top
                if (menuY < offset) {
                    menuY = touchScreenY + offset // Show below touch point
                } else if (menuY + menuHeight > screenHeight - offset) {
                    menuY = touchScreenY - menuHeight - offset // Show above touch point
                }
                
                y = menuY - rootLocation[1]
            } else {
                // Fall back to below anchor if touchY not provided
                y = location[1] + anchor.height - rootLocation[1]
            }
        } else {
            // Fall back to gravity-based positioning
            when (gravity) {
                Gravity.START, Gravity.LEFT -> {
                    x = location[0] - rootLocation[0]
                    y = location[1] + anchor.height - rootLocation[1]
                }
                Gravity.END, Gravity.RIGHT -> {
                    x = location[0] + anchor.width - popupBinding.root.measuredWidth - rootLocation[0]
                    y = location[1] + anchor.height - rootLocation[1]
                }
                Gravity.CENTER -> {
                    x = location[0] + (anchor.width - popupBinding.root.measuredWidth) / 2 - rootLocation[0]
                    y = location[1] + anchor.height - rootLocation[1]
                }
                else -> {
                    // Default: align to end (right in LTR, left in RTL)
                    x = location[0] + anchor.width - popupBinding.root.measuredWidth - rootLocation[0]
                    y = location[1] + anchor.height - rootLocation[1]
                }
            }
        }

        // Create and show popup window
        popupWindow = PopupWindow(
            popupBinding.root,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 8f
            setBackgroundDrawable(null)
            isOutsideTouchable = true
            isFocusable = true

            showAtLocation(
                activity.window.decorView.rootView,
                Gravity.NO_GRAVITY,
                x,
                y
            )
        }
    }

    /**
     * Dismiss the popup menu.
     */
    fun dismiss() {
        popupWindow?.dismiss()
        popupWindow = null
    }

    /**
     * Check if the popup menu is showing.
     */
    fun isShowing(): Boolean = popupWindow?.isShowing == true
}
