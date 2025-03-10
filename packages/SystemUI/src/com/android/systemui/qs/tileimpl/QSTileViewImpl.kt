/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.tileimpl

import android.animation.ArgbEvaluator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources.ID_NULL
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.os.Trace
import android.os.UserHandle
import android.provider.Settings.System
import android.service.quicksettings.Tile
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import com.android.settingslib.Utils
import com.android.systemui.R
import com.android.systemui.animation.LaunchableView
import com.android.systemui.animation.LaunchableViewDelegate
import com.android.systemui.plugins.qs.QSIconView
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.qs.QSTile.BooleanState
import com.android.systemui.plugins.qs.QSTileView
import com.android.systemui.qs.TileUtils
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSIconViewImpl.QS_ANIM_LENGTH
import java.util.Objects
import java.util.Random

private const val TAG = "QSTileViewImpl"
open class QSTileViewImpl @JvmOverloads constructor(
    context: Context,
    private val _icon: QSIconView,
    private val collapsed: Boolean = false
) : QSTileView(context), HeightOverrideable, LaunchableView {

    companion object {
        private const val INVALID = -1
        private const val BACKGROUND_NAME = "background"
        private const val LABEL_NAME = "label"
        private const val SECONDARY_LABEL_NAME = "secondaryLabel"
        private const val CHEVRON_NAME = "chevron"
        const val UNAVAILABLE_ALPHA = 0.3f
        const val TILE_ALPHA = 0.2f
        const val INACTIVE_ALPHA = 0.2f
        @VisibleForTesting
        internal const val TILE_STATE_RES_PREFIX = "tile_states_"
    }

    private var _position: Int = INVALID

    override fun setPosition(position: Int) {
        _position = position
    }

    override var heightOverride: Int = HeightOverrideable.NO_OVERRIDE
        set(value) {
            if (field == value) return
            field = value
            updateHeight()
        }

    override var squishinessFraction: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            updateHeight()
        }

    private val qsPanelStyle: Int = System.getIntForUser(
            context.contentResolver,
            System.QS_PANEL_STYLE, 0, UserHandle.USER_CURRENT
        )

    private val tintAlpha: Boolean = qsPanelStyle == 1 || qsPanelStyle == 2 || qsPanelStyle == 10

    private val colorActive = Utils.getColorAttrDefaultColor(context,
            android.R.attr.colorAccent)
    private val colorOffstate = Utils.getColorAttrDefaultColor(context, R.attr.offStateColor)
    private val offStateAlpha = ContextCompat.getColorStateList(context, R.drawable.color_alpha_offstate)?.defaultColor ?: colorOffstate
    private val colorInactive = if (tintAlpha) offStateAlpha else Utils.getColorAttrDefaultColor(context, R.attr.offStateColor)
    private val colorUnavailable = Utils.applyAlpha(UNAVAILABLE_ALPHA, colorOffstate)

    private val colorLabelActive =
            Utils.getColorAttrDefaultColor(context, com.android.internal.R.attr.textColorOnAccent)
    private val colorLabelInactive =
            Utils.getColorAttrDefaultColor(context, android.R.attr.textColorPrimary)
    private val colorLabelUnavailable =
        Utils.getColorAttrDefaultColor(context, com.android.internal.R.attr.textColorTertiary)

    private val colorSecondaryLabelActive =
            Utils.getColorAttrDefaultColor(context, android.R.attr.textColorSecondaryInverse)
    private val colorSecondaryLabelInactive =
            Utils.getColorAttrDefaultColor(context, android.R.attr.textColorSecondary)
    private val colorSecondaryLabelUnavailable =
        Utils.getColorAttrDefaultColor(context, com.android.internal.R.attr.textColorTertiary)

    // QS Style 2
    private val colorActiveAlpha = ContextCompat.getColorStateList(context, R.drawable.color_accent_alpha)?.defaultColor ?: Utils.applyAlpha(TILE_ALPHA, Utils.getColorAttrDefaultColor(context, android.R.attr.colorAccent))
    private val colorInactiveAlpha = offStateAlpha

    // QS Style 3
    private var randomColor: Random = Random()
    
    // QS Style 8
    private val colorActiveSurround = resources.getColor(R.color.qs_white_bg)
    
    @SuppressLint("NewApi")
    private var randomTint: Int = Color.rgb(
        (randomColor.nextInt(256) / 2f + 0.5).toFloat(),
        randomColor.nextInt(256).toFloat(),
        randomColor.nextInt(256).toFloat()
    )

    private val colorActiveRandom = Utils.applyAlpha(TILE_ALPHA, randomTint)
    private val colorLabelActiveRandom = randomTint
    private val colorSecondaryLabelActiveRandom = randomTint

    private lateinit var label: TextView
    protected lateinit var secondaryLabel: TextView
    private lateinit var labelContainer: IgnorableChildLinearLayout
    protected lateinit var sideView: ViewGroup
    private lateinit var customDrawableView: ImageView
    private lateinit var chevronView: ImageView
    private var mQsLogger: QSLogger? = null
    protected var showRippleEffect = true

    private lateinit var ripple: RippleDrawable
    private lateinit var colorBackgroundDrawable: Drawable
    private var paintColor: Int = 0
    private val singleAnimator: ValueAnimator = ValueAnimator().apply {
        setDuration(QS_ANIM_LENGTH)
        addUpdateListener { animation ->
            setAllColors(
                // These casts will throw an exception if some property is missing. We should
                // always have all properties.
                animation.getAnimatedValue(BACKGROUND_NAME) as Int,
                animation.getAnimatedValue(LABEL_NAME) as Int,
                animation.getAnimatedValue(SECONDARY_LABEL_NAME) as Int,
                animation.getAnimatedValue(CHEVRON_NAME) as Int
            )
        }
    }

    private var accessibilityClass: String? = null
    private var stateDescriptionDeltas: CharSequence? = null
    private var lastStateDescription: CharSequence? = null
    private var tileState = false
    private var lastState = INVALID
    private val launchableViewDelegate = LaunchableViewDelegate(
        this,
        superSetVisibility = { super.setVisibility(it) },
    )
    private var lastDisabledByPolicy = false

    private val locInScreen = IntArray(2)
    private var vertical = false
    private val forceHideCheveron = true
    private var labelHide = false
    private var labelSize = 14f

    init {
        setId(generateViewId())

        vertical = TileUtils.getQSTileVerticalLayout(context, if (vertical) 1 else 0)
        labelHide = TileUtils.getQSTileLabelHide(context)
        labelSize = TileUtils.getQSTileLabelSize(context)

        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        clipChildren = false
        clipToPadding = false
        isFocusable = true
        background = createTileBackground()
        setColor(getBackgroundColorForState(QSTile.State.DEFAULT_STATE))

        val iconSize = resources.getDimensionPixelSize(R.dimen.qs_icon_size)
        addView(_icon, LayoutParams(iconSize, iconSize))

        createAndAddLabels()
        createAndAddSideView()
        updateResources()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        updateResources()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        Trace.traceBegin(Trace.TRACE_TAG_APP, "QSTileViewImpl#onMeasure")
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        Trace.endSection()
    }

    override fun resetOverride() {
        heightOverride = HeightOverrideable.NO_OVERRIDE
        updateHeight()
    }

    fun setQsLogger(qsLogger: QSLogger) {
        mQsLogger = qsLogger
    }

    fun updateResources() {
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSize)
        secondaryLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSize)

        val iconSize = context.resources.getDimensionPixelSize(R.dimen.qs_icon_size)
        _icon.layoutParams.apply {
            height = iconSize
            width = iconSize
        }

        if (vertical) {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
        } else {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
        }

        if (labelHide)
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL

        val padding = resources.getDimensionPixelSize(R.dimen.qs_tile_padding)
        val startPadding = if (vertical) padding else resources.getDimensionPixelSize(R.dimen.qs_tile_start_padding)
        setPaddingRelative(startPadding, padding, padding, padding)

        val labelMargin = if (vertical) 0 else resources.getDimensionPixelSize(R.dimen.qs_label_container_margin)
        (labelContainer.layoutParams as MarginLayoutParams).apply {
            marginStart = labelMargin
        }

        (sideView.layoutParams as MarginLayoutParams).apply {
            marginStart = labelMargin
        }
        (chevronView.layoutParams as MarginLayoutParams).apply {
            height = iconSize
            width = iconSize
        }

        val endMargin = resources.getDimensionPixelSize(R.dimen.qs_drawable_end_margin)
        (customDrawableView.layoutParams as MarginLayoutParams).apply {
            height = iconSize
            marginEnd = endMargin
        }
    }

    private fun createAndAddLabels() {
        labelContainer = LayoutInflater.from(context)
                .inflate(if (vertical) R.layout.qs_tile_label_vertical else R.layout.qs_tile_label,this, false) as IgnorableChildLinearLayout
        label = labelContainer.requireViewById(R.id.tile_label)
        secondaryLabel = labelContainer.requireViewById(R.id.app_label)
        if (collapsed) {
            labelContainer.ignoreLastView = true
            // Ideally, it'd be great if the parent could set this up when measuring just this child
            // instead of the View class having to support this. However, due to the mysteries of
            // LinearLayout's double measure pass, we cannot overwrite `measureChild` or any of its
            // sibling methods to have special behavior for labelContainer.
            labelContainer.forceUnspecifiedMeasure = true
            secondaryLabel.alpha = 0f
        }
        setLabelColor(getLabelColorForState(QSTile.State.DEFAULT_STATE))
        setSecondaryLabelColor(getSecondaryLabelColorForState(QSTile.State.DEFAULT_STATE))
        if (!labelHide)
            addView(labelContainer)
    }

    private fun createAndAddSideView() {
        sideView = LayoutInflater.from(context)
                .inflate(R.layout.qs_tile_side_icon, this, false) as ViewGroup
        customDrawableView = sideView.requireViewById(R.id.customDrawable)
        chevronView = sideView.requireViewById(R.id.chevron)
        setChevronColor(getChevronColorForState(QSTile.State.DEFAULT_STATE))
        addView(sideView)
    }

    fun createTileBackground(): Drawable {
        ripple = mContext.getDrawable(R.drawable.qs_tile_background) as RippleDrawable
        colorBackgroundDrawable = ripple.findDrawableByLayerId(R.id.background)
        return ripple
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        updateHeight()
    }

    private fun updateHeight() {
        val actualHeight = if (heightOverride != HeightOverrideable.NO_OVERRIDE) {
            heightOverride
        } else {
            measuredHeight
        }
        // Limit how much we affect the height, so we don't have rounding artifacts when the tile
        // is too short.
        val constrainedSquishiness = constrainSquishiness(squishinessFraction)
        bottom = top + (actualHeight * constrainedSquishiness).toInt()
        scrollY = (actualHeight - height) / if (vertical) 7 else 2
        label.alpha = if (!vertical) 1.0f else Math.pow(squishinessFraction.toDouble(), 7.0).toFloat()
    }

    override fun updateAccessibilityOrder(previousView: View?): View {
        accessibilityTraversalAfter = previousView?.id ?: ID_NULL
        return this
    }

    override fun getIcon(): QSIconView {
        return _icon
    }

    override fun getIconWithBackground(): View {
        return icon
    }

    override fun init(tile: QSTile) {
        init(
                { v: View? -> tile.click(this) },
                { view: View? ->
                    tile.longClick(this)
                    true
                }
        )
    }

    private fun init(
        click: OnClickListener?,
        longClick: OnLongClickListener?
    ) {
        setOnClickListener(click)
        onLongClickListener = longClick
    }

    override fun onStateChanged(state: QSTile.State) {
        post {
            handleStateChanged(state)
        }
    }

    override fun getDetailY(): Int {
        return top + height / 2
    }

    override fun hasOverlappingRendering(): Boolean {
        // Avoid layers for this layout - we don't need them.
        return false
    }

    override fun setClickable(clickable: Boolean) {
        super.setClickable(clickable)
        background = if (clickable && showRippleEffect) {
            ripple.also {
                // In case that the colorBackgroundDrawable was used as the background, make sure
                // it has the correct callback instead of null
                colorBackgroundDrawable.callback = it
            }
        } else {
            colorBackgroundDrawable
        }
    }

    override fun getLabelContainer(): View {
        return labelContainer
    }

    override fun getLabel(): View {
        return label
    }

    override fun getSecondaryLabel(): View {
        return secondaryLabel
    }

    override fun getSecondaryIcon(): View {
        return sideView
    }

    override fun setShouldBlockVisibilityChanges(block: Boolean) {
        launchableViewDelegate.setShouldBlockVisibilityChanges(block)
    }

    override fun setVisibility(visibility: Int) {
        launchableViewDelegate.setVisibility(visibility)
    }

    // Accessibility

    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)
        if (!TextUtils.isEmpty(accessibilityClass)) {
            event.className = accessibilityClass
        }
        if (event.contentChangeTypes == AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION &&
                stateDescriptionDeltas != null) {
            event.text.add(stateDescriptionDeltas)
            stateDescriptionDeltas = null
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        // Clear selected state so it is not announce by talkback.
        info.isSelected = false
        if (lastDisabledByPolicy) {
            info.addAction(
                    AccessibilityNodeInfo.AccessibilityAction(
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.id,
                            resources.getString(
                                R.string.accessibility_tile_disabled_by_policy_action_description
                            )
                    )
            )
        }
        if (!TextUtils.isEmpty(accessibilityClass)) {
            info.className = if (lastDisabledByPolicy) {
                Button::class.java.name
            } else {
                accessibilityClass
            }
            if (Switch::class.java.name == accessibilityClass) {
                val label = resources.getString(
                        if (tileState) R.string.switch_bar_on else R.string.switch_bar_off)
                // Set the text here for tests in
                // android.platform.test.scenario.sysui.quicksettings. Can be removed when
                // UiObject2 has a new getStateDescription() API and tests are updated.
                info.text = label
                info.isChecked = tileState
                info.isCheckable = true
                if (isLongClickable) {
                    info.addAction(
                            AccessibilityNodeInfo.AccessibilityAction(
                                    AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK.id,
                                    resources.getString(
                                            R.string.accessibility_long_click_tile)))
                }
            }
        }
        if (_position != INVALID) {
            info.collectionItemInfo =
                AccessibilityNodeInfo.CollectionItemInfo(_position, 1, 0, 1, false)
        }
    }

    override fun toString(): String {
        val sb = StringBuilder(javaClass.simpleName).append('[')
        sb.append("locInScreen=(${locInScreen[0]}, ${locInScreen[1]})")
        sb.append(", iconView=$_icon")
        sb.append(", tileState=$tileState")
        sb.append("]")
        return sb.toString()
    }

    // HANDLE STATE CHANGES RELATED METHODS

    protected open fun handleStateChanged(state: QSTile.State) {
        val allowAnimations = animationsEnabled()
        showRippleEffect = state.showRippleEffect
        isClickable = state.state != Tile.STATE_UNAVAILABLE
        isLongClickable = state.handlesLongClick
        icon.setIcon(state, allowAnimations)
        contentDescription = state.contentDescription

        // State handling and description
        val stateDescription = StringBuilder()
        val arrayResId = SubtitleArrayMapping.getSubtitleId(state.spec)
        val stateText = state.getStateText(arrayResId, resources)
        state.secondaryLabel = state.getSecondaryLabel(stateText)
        if (!TextUtils.isEmpty(stateText)) {
            stateDescription.append(stateText)
        }
        if (state.disabledByPolicy && state.state != Tile.STATE_UNAVAILABLE) {
            stateDescription.append(", ")
            stateDescription.append(getUnavailableText(state.spec))
        }
        if (!TextUtils.isEmpty(state.stateDescription)) {
            stateDescription.append(", ")
            stateDescription.append(state.stateDescription)
            if (lastState != INVALID && state.state == lastState &&
                    state.stateDescription != lastStateDescription) {
                stateDescriptionDeltas = state.stateDescription
            }
        }

        setStateDescription(stateDescription.toString())
        lastStateDescription = state.stateDescription

        accessibilityClass = if (state.state == Tile.STATE_UNAVAILABLE) {
            null
        } else {
            state.expandedAccessibilityClassName
        }

        if (state is BooleanState) {
            val newState = state.value
            if (tileState != newState) {
                tileState = newState
            }
        }
        //

        // Labels
        if (!Objects.equals(label.text, state.label)) {
            label.text = state.label
        }
        if (!Objects.equals(secondaryLabel.text, state.secondaryLabel)) {
            secondaryLabel.text = state.secondaryLabel
            secondaryLabel.visibility = if (TextUtils.isEmpty(secondaryLabel.text)) {
                GONE
            } else {
                VISIBLE
            }
        }

        // Colors
        if (state.state != lastState || state.disabledByPolicy || lastDisabledByPolicy) {
            singleAnimator.cancel()
            mQsLogger?.logTileBackgroundColorUpdateIfInternetTile(
                    state.spec,
                    state.state,
                    state.disabledByPolicy,
                    getBackgroundColorForState(state.state, state.disabledByPolicy))
            if (allowAnimations) {
                singleAnimator.setValues(
                        colorValuesHolder(
                                BACKGROUND_NAME,
                                paintColor,
                                getBackgroundColorForState(state.state, state.disabledByPolicy)
                        ),
                        colorValuesHolder(
                                LABEL_NAME,
                                label.currentTextColor,
                                getLabelColorForState(state.state, state.disabledByPolicy)
                        ),
                        colorValuesHolder(
                                SECONDARY_LABEL_NAME,
                                secondaryLabel.currentTextColor,
                                getSecondaryLabelColorForState(state.state, state.disabledByPolicy)
                        ),
                        colorValuesHolder(
                                CHEVRON_NAME,
                                chevronView.imageTintList?.defaultColor ?: 0,
                                getChevronColorForState(state.state, state.disabledByPolicy)
                        )
                    )
                singleAnimator.start()
            } else {
                setAllColors(
                    getBackgroundColorForState(state.state, state.disabledByPolicy),
                    getLabelColorForState(state.state, state.disabledByPolicy),
                    getSecondaryLabelColorForState(state.state, state.disabledByPolicy),
                    getChevronColorForState(state.state, state.disabledByPolicy)
                )
            }
        }

        // Right side icon
        loadSideViewDrawableIfNecessary(state)

        label.isEnabled = !state.disabledByPolicy

        lastState = state.state
        lastDisabledByPolicy = state.disabledByPolicy
    }

    private fun setAllColors(
        backgroundColor: Int,
        labelColor: Int,
        secondaryLabelColor: Int,
        chevronColor: Int
    ) {
        setColor(backgroundColor)
        setLabelColor(labelColor)
        setSecondaryLabelColor(secondaryLabelColor)
        setChevronColor(chevronColor)
    }

    private fun setColor(color: Int) {
        colorBackgroundDrawable.mutate().setTint(color)
        paintColor = color
    }

    private fun setLabelColor(color: Int) {
        label.setTextColor(color)
    }

    private fun setSecondaryLabelColor(color: Int) {
        secondaryLabel.setTextColor(color)
    }

    private fun setChevronColor(color: Int) {
        chevronView.imageTintList = ColorStateList.valueOf(color)
    }

    private fun loadSideViewDrawableIfNecessary(state: QSTile.State) {
        if (state.sideViewCustomDrawable != null) {
            customDrawableView.setImageDrawable(state.sideViewCustomDrawable)
            customDrawableView.visibility = VISIBLE
            chevronView.visibility = GONE
        } else if ((state !is BooleanState || state.forceExpandIcon) && !forceHideCheveron) {
            customDrawableView.setImageDrawable(null)
            customDrawableView.visibility = GONE
            chevronView.visibility = VISIBLE
        } else {
            customDrawableView.setImageDrawable(null)
            customDrawableView.visibility = GONE
            chevronView.visibility = GONE
        }
    }

    private fun getUnavailableText(spec: String?): String {
        val arrayResId = SubtitleArrayMapping.getSubtitleId(spec)
        return resources.getStringArray(arrayResId)[Tile.STATE_UNAVAILABLE]
    }

    /*
     * The view should not be animated if it's not on screen and no part of it is visible.
     */
    protected open fun animationsEnabled(): Boolean {
        if (!isShown) {
            return false
        }
        if (alpha != 1f) {
            return false
        }
        getLocationOnScreen(locInScreen)
        return locInScreen.get(1) >= -height
    }

    private fun getBackgroundColorForState(state: Int, disabledByPolicy: Boolean = false): Int {
        return when {
            state == Tile.STATE_UNAVAILABLE || disabledByPolicy -> colorUnavailable
            state == Tile.STATE_ACTIVE -> 
                if(qsPanelStyle == 2 || qsPanelStyle == 10) 
                    colorActiveAlpha 
                else if(qsPanelStyle == 3) 
                    colorActiveRandom 
                else colorActive
            state == Tile.STATE_INACTIVE -> if(qsPanelStyle >= 1) colorInactiveAlpha else colorInactive
            else -> {
                Log.e(TAG, "Invalid state $state")
                0
            }
        }
    }

    private fun getLabelColorForState(state: Int, disabledByPolicy: Boolean = false): Int {
        return when {
            state == Tile.STATE_UNAVAILABLE || disabledByPolicy -> colorLabelUnavailable
            state == Tile.STATE_ACTIVE -> 
                if(qsPanelStyle == 1 || qsPanelStyle == 2 || qsPanelStyle == 10)
                    colorActive
                else if(qsPanelStyle == 3) 
                    colorLabelActiveRandom
                else if(qsPanelStyle == 4 || qsPanelStyle == 6 || qsPanelStyle == 8 || qsPanelStyle == 9)   
                    colorActiveSurround
                else colorLabelActive
            state == Tile.STATE_INACTIVE -> colorLabelInactive
            else -> {
                Log.e(TAG, "Invalid state $state")
                0
            }
        }
    }

    private fun getSecondaryLabelColorForState(state: Int, disabledByPolicy: Boolean = false): Int {
        return when {
            state == Tile.STATE_UNAVAILABLE || disabledByPolicy -> colorSecondaryLabelUnavailable
            state == Tile.STATE_ACTIVE -> 
                if(qsPanelStyle == 1 || qsPanelStyle == 2 || qsPanelStyle == 10) 
                    colorActive
                else if(qsPanelStyle == 3) 
                    colorSecondaryLabelActiveRandom
                else if(qsPanelStyle == 4 || qsPanelStyle == 6 || qsPanelStyle == 8 || qsPanelStyle == 9)   
                    colorActiveSurround
                else colorSecondaryLabelActive
            state == Tile.STATE_INACTIVE -> colorSecondaryLabelInactive
            else -> {
                Log.e(TAG, "Invalid state $state")
                0
            }
        }
    }

    private fun getChevronColorForState(state: Int, disabledByPolicy: Boolean = false): Int =
            getSecondaryLabelColorForState(state, disabledByPolicy)

    @VisibleForTesting
    internal fun getCurrentColors(): List<Int> = listOf(
            paintColor,
            label.currentTextColor,
            secondaryLabel.currentTextColor,
            chevronView.imageTintList?.defaultColor ?: 0
    )
}

fun constrainSquishiness(squish: Float): Float {
    return 0.1f + squish * 0.9f
}

private fun colorValuesHolder(name: String, vararg values: Int): PropertyValuesHolder {
    return PropertyValuesHolder.ofInt(name, *values).apply {
        setEvaluator(ArgbEvaluator.getInstance())
    }
}
