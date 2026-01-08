package com.goodwy.commons.views

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.view.menu.MenuBuilder
import androidx.core.content.ContextCompat
import androidx.core.view.MenuCompat
import com.goodwy.commons.R
import com.goodwy.commons.databinding.PopupMenuBlurBinding
import com.goodwy.commons.extensions.applyColorFilter
import com.goodwy.commons.extensions.getProperBlurOverlayColor
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView

/**
 * Custom toolbar implementation using LinearLayout that mimics MaterialToolbar API
 * while preserving the same styling and functionality.
 */
class CustomToolbar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var navigationIconView: ImageView? = null
    private var titleTextView: TextView? = null
    private var menuButton: ImageView? = null
    private var menuContainer: LinearLayout? = null
    
    private var _menu: Menu? = null
    private var menuInflater: MenuInflater? = null
    private var onMenuItemClickListener: MenuItem.OnMenuItemClickListener? = null
    private var onNavigationClickListener: OnClickListener? = null
    
    var navigationIcon: Drawable?
        get() = navigationIconView?.drawable
        set(value) {
            if (navigationIconView == null && value != null) {
                createNavigationIcon()
            }
            navigationIconView?.setImageDrawable(value)
            navigationIconView?.visibility = if (value != null) View.VISIBLE else View.GONE
        }
    
    var title: CharSequence?
        get() = titleTextView?.text
        set(value) {
            if (titleTextView == null && value != null) {
                createTitle()
            }
            titleTextView?.text = value
            titleTextView?.visibility = if (value != null && value.isNotEmpty()) View.VISIBLE else View.GONE
        }
    
    val menu: Menu
        get() {
            if (_menu == null) {
                _menu = MenuBuilder(context)
            }
            return _menu!!
        }
    
    var overflowIcon: Drawable?
        get() = menuButton?.drawable
        set(value) {
            if (menuButton == null && value != null) {
                createMenuButton()
            }
            menuButton?.setImageDrawable(value)
        }
    
    var collapseIcon: Drawable? = null
    
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(
            resources.getDimensionPixelSize(R.dimen.activity_margin),
            0,
            resources.getDimensionPixelSize(R.dimen.activity_margin),
            0
        )
        
        // Read attributes from XML
        attrs?.let {
            val attrsArray = intArrayOf(
                android.R.attr.title,
                androidx.appcompat.R.attr.menu
            )
            val typedArray = context.obtainStyledAttributes(it, attrsArray)
            
            // Read title
            val titleRes = typedArray.getResourceId(0, 0)
            if (titleRes != 0) {
                title = context.getString(titleRes)
            } else {
                val titleText = typedArray.getString(0)
                if (!titleText.isNullOrEmpty()) {
                    title = titleText
                }
            }
            
            // Read menu resource
            val menuRes = typedArray.getResourceId(1, 0)
            if (menuRes != 0) {
                post { inflateMenu(menuRes) }
            }
            
            typedArray.recycle()
        }
        
        // Create title by default
        createTitle()
    }
    
    private fun getSelectableItemBackgroundBorderless(): Drawable? {
        val typedValue = TypedValue()
        return if (context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, typedValue, true)) {
            ContextCompat.getDrawable(context, typedValue.resourceId)
        } else {
            null
        }
    }
    
    private fun createNavigationIcon() {
        navigationIconView = ImageView(context).apply {
            layoutParams = LayoutParams(
                resources.getDimensionPixelSize(R.dimen.normal_icon_size),
                resources.getDimensionPixelSize(R.dimen.normal_icon_size)
            ).apply {
                marginEnd = resources.getDimensionPixelSize(R.dimen.normal_margin)
            }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = getSelectableItemBackgroundBorderless()
            setPadding(
                resources.getDimensionPixelSize(R.dimen.smaller_margin),
                resources.getDimensionPixelSize(R.dimen.smaller_margin),
                resources.getDimensionPixelSize(R.dimen.smaller_margin),
                resources.getDimensionPixelSize(R.dimen.smaller_margin)
            )
            onNavigationClickListener?.let { setOnClickListener(it) }
        }
        addView(navigationIconView, 0)
    }
    
    private fun createTitle() {
        titleTextView = TextView(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = resources.getDimensionPixelSize(R.dimen.normal_margin)
                marginEnd = resources.getDimensionPixelSize(R.dimen.normal_margin)
            }
            textSize = resources.getDimension(R.dimen.actionbar_text_size) / resources.displayMetrics.scaledDensity
            setTextColor(ContextCompat.getColor(context, R.color.default_text_color))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        val insertIndex = navigationIconView?.let { indexOfChild(it) + 1 } ?: 0
        addView(titleTextView, insertIndex)
    }
    
    private fun createMenuButton() {
        menuButton = ImageView(context).apply {
            layoutParams = LayoutParams(
                resources.getDimensionPixelSize(R.dimen.normal_icon_size),
                resources.getDimensionPixelSize(R.dimen.normal_icon_size)
            ).apply {
                marginStart = resources.getDimensionPixelSize(R.dimen.smaller_margin)
            }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = getSelectableItemBackgroundBorderless()
            setPadding(
                resources.getDimensionPixelSize(R.dimen.smaller_margin),
                resources.getDimensionPixelSize(R.dimen.smaller_margin),
                resources.getDimensionPixelSize(R.dimen.smaller_margin),
                resources.getDimensionPixelSize(R.dimen.smaller_margin)
            )
            setOnClickListener { showMenuPopup() }
        }
        addView(menuButton)
    }
    
    private fun createMenuContainer() {
        menuContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }
        addView(menuContainer)
    }
    
    fun setNavigationContentDescription(resId: Int) {
        navigationIconView?.contentDescription = context.getString(resId)
    }
    
    fun setNavigationContentDescription(description: CharSequence?) {
        navigationIconView?.contentDescription = description
    }
    
    fun setNavigationOnClickListener(listener: OnClickListener?) {
        onNavigationClickListener = listener
        navigationIconView?.setOnClickListener(listener)
    }
    
    fun setTitleTextColor(color: Int) {
        titleTextView?.setTextColor(color)
    }
    
    fun setTitleTextColor(colors: ColorStateList?) {
        titleTextView?.setTextColor(colors)
    }
    
    fun inflateMenu(resId: Int) {
        if (menuInflater == null) {
            menuInflater = MenuInflater(context)
        }
        menuInflater?.inflate(resId, menu)
        updateMenuDisplay()
    }
    
    fun setOnMenuItemClickListener(listener: MenuItem.OnMenuItemClickListener?) {
        onMenuItemClickListener = listener
    }
    
    fun invalidateMenu() {
        updateMenuDisplay()
    }
    
    private fun updateMenuDisplay() {
        val menu = _menu ?: return
        
        // Clear existing menu views
        menuContainer?.removeAllViews()
        
        // Don't show menu items as icons - show all items in overflow menu only
        // Check if there are any visible menu items
        val hasVisibleItems = (0 until menu.size())
            .mapNotNull { menu.getItem(it) }
            .any { it.isVisible }
        
        if (hasVisibleItems) {
            // Show overflow menu button
            if (menuButton == null) {
                createMenuButton()
            }
            menuButton?.visibility = View.VISIBLE
            menuContainer?.visibility = View.GONE
        } else {
            // No visible items, hide everything
            menuButton?.visibility = View.GONE
            menuContainer?.visibility = View.GONE
        }
    }
    
    private fun showMenuPopup() {
        val menu = _menu ?: return
        val activity = context as? Activity ?: return
        val blurTarget = activity.findViewById<BlurTarget>(R.id.mainBlurTarget) ?: return
        
        // Create custom popup window with blur
        val inflater = LayoutInflater.from(context)
        val binding = PopupMenuBlurBinding.inflate(inflater, null, false)
        
        // Setup rounded corners - apply clipToOutline to the root container
        val rootContainer = binding.root
        rootContainer.clipToOutline = true
        
        // Setup BlurView
        val blurView = binding.blurView
        val decorView = activity.window.decorView
        val windowBackground = decorView.background
        
        blurView.setOverlayColor(context.getProperBlurOverlayColor())
        blurView.setupWith(blurTarget)
            .setFrameClearDrawable(windowBackground)
            .setBlurRadius(8f)
            .setBlurAutoUpdate(true)
        
        // Create menu items
        val menuContainer = binding.menuContainer
        val visibleItems = (0 until menu.size())
            .mapNotNull { menu.getItem(it) }
            .filter { it.isVisible }
        
        visibleItems.forEach { item ->
            val menuItemView = inflater.inflate(R.layout.item_popup_menu, menuContainer, false)
            val titleView = menuItemView.findViewById<TextView>(R.id.menu_item_title)
            
            titleView.text = item.title
            
            menuItemView.setOnClickListener {
                onMenuItemClickListener?.onMenuItemClick(item) ?: false
                popupWindow?.dismiss()
            }
            
            menuContainer.addView(menuItemView)
        }
        
        // Measure the content to get proper size
        binding.root.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        
        // Create and show popup window
        popupWindow = PopupWindow(
            binding.root,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 8f
            setBackgroundDrawable(null)
            isOutsideTouchable = true
            isFocusable = true
            
            // Calculate position - show below the menu button, aligned to the right
            val anchor = menuButton ?: this@CustomToolbar
            val location = IntArray(2)
            anchor.getLocationOnScreen(location)
            val rootLocation = IntArray(2)
            (context as? Activity)?.window?.decorView?.rootView?.getLocationOnScreen(rootLocation)
            
            val x = location[0] + anchor.width - binding.root.measuredWidth
            val y = location[1] + anchor.height
            
            showAtLocation(
                (context as? Activity)?.window?.decorView?.rootView,
                Gravity.NO_GRAVITY,
                x - (rootLocation[0]),
                y - (rootLocation[1])
            )
        }
    }
    
    private var popupWindow: PopupWindow? = null
    
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // Update menu display when layout changes (only if menu exists)
        if (_menu != null && _menu!!.size() > 0) {
            post { updateMenuDisplay() }
        }
    }
}
