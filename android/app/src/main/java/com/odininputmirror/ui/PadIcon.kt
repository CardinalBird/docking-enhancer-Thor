package com.odininputmirror.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.odininputmirror.R
import com.odininputmirror.domain.model.PadLayout
import com.odininputmirror.domain.model.SlotIcon
import com.odininputmirror.ui.theme.Palette

/** Which console's icons [PadIcon] draws. MirrorScreen provides it from the pad's saved preset. */
internal val LocalPadLayout = compositionLocalOf { PadLayout.XBOX }

/**
 * Every button prompt in the app: Kenney Input Prompts 1.5 (CC0), white vectors tinted by the theme.
 * Xbox Series art for the Xbox layout, Nintendo Switch 2 art for the Nintendo one.
 */
@Composable
internal fun PadIcon(icon: SlotIcon, size: Dp = 28.dp, tint: Color = Palette.textPrimary) {
    Image(
        painterResource(padIconRes(icon, LocalPadLayout.current)),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = Modifier.size(size),
    )
}

@DrawableRes
internal fun padIconRes(icon: SlotIcon, layout: PadLayout): Int = when (layout) {
    PadLayout.XBOX -> when (icon) {
        SlotIcon.A -> R.drawable.kp_xbox_button_a
        SlotIcon.B -> R.drawable.kp_xbox_button_b
        SlotIcon.X -> R.drawable.kp_xbox_button_x
        SlotIcon.Y -> R.drawable.kp_xbox_button_y
        SlotIcon.LEFT_BUMPER -> R.drawable.kp_xbox_lb
        SlotIcon.RIGHT_BUMPER -> R.drawable.kp_xbox_rb
        SlotIcon.LEFT_TRIGGER -> R.drawable.kp_xbox_lt
        SlotIcon.RIGHT_TRIGGER -> R.drawable.kp_xbox_rt
        SlotIcon.DPAD_UP -> R.drawable.kp_xbox_dpad_up
        SlotIcon.DPAD_DOWN -> R.drawable.kp_xbox_dpad_down
        SlotIcon.DPAD_LEFT -> R.drawable.kp_xbox_dpad_left
        SlotIcon.DPAD_RIGHT -> R.drawable.kp_xbox_dpad_right
        SlotIcon.LEFT_STICK_UP -> R.drawable.kp_xbox_stick_l_up
        SlotIcon.LEFT_STICK_DOWN -> R.drawable.kp_xbox_stick_l_down
        SlotIcon.LEFT_STICK_LEFT -> R.drawable.kp_xbox_stick_l_left
        SlotIcon.LEFT_STICK_RIGHT -> R.drawable.kp_xbox_stick_l_right
        SlotIcon.LEFT_STICK_CLICK -> R.drawable.kp_xbox_stick_l_press
        SlotIcon.RIGHT_STICK_UP -> R.drawable.kp_xbox_stick_r_up
        SlotIcon.RIGHT_STICK_DOWN -> R.drawable.kp_xbox_stick_r_down
        SlotIcon.RIGHT_STICK_LEFT -> R.drawable.kp_xbox_stick_r_left
        SlotIcon.RIGHT_STICK_RIGHT -> R.drawable.kp_xbox_stick_r_right
        SlotIcon.RIGHT_STICK_CLICK -> R.drawable.kp_xbox_stick_r_press
        SlotIcon.VIEW -> R.drawable.kp_xbox_button_view
        SlotIcon.MENU -> R.drawable.kp_xbox_button_menu
        SlotIcon.HOME -> R.drawable.kp_xbox_guide
        SlotIcon.BACK -> R.drawable.kp_xbox_button_back
    }
    PadLayout.NINTENDO -> when (icon) {
        SlotIcon.A -> R.drawable.kp_switch_button_a
        SlotIcon.B -> R.drawable.kp_switch_button_b
        SlotIcon.X -> R.drawable.kp_switch_button_x
        SlotIcon.Y -> R.drawable.kp_switch_button_y
        SlotIcon.LEFT_BUMPER -> R.drawable.kp_switch_button_l
        SlotIcon.RIGHT_BUMPER -> R.drawable.kp_switch_button_r
        SlotIcon.LEFT_TRIGGER -> R.drawable.kp_switch_button_zl
        SlotIcon.RIGHT_TRIGGER -> R.drawable.kp_switch_button_zr
        SlotIcon.DPAD_UP -> R.drawable.kp_switch_dpad_up
        SlotIcon.DPAD_DOWN -> R.drawable.kp_switch_dpad_down
        SlotIcon.DPAD_LEFT -> R.drawable.kp_switch_dpad_left
        SlotIcon.DPAD_RIGHT -> R.drawable.kp_switch_dpad_right
        SlotIcon.LEFT_STICK_UP -> R.drawable.kp_switch_stick_l_up
        SlotIcon.LEFT_STICK_DOWN -> R.drawable.kp_switch_stick_l_down
        SlotIcon.LEFT_STICK_LEFT -> R.drawable.kp_switch_stick_l_left
        SlotIcon.LEFT_STICK_RIGHT -> R.drawable.kp_switch_stick_l_right
        SlotIcon.LEFT_STICK_CLICK -> R.drawable.kp_switch_stick_l_press
        SlotIcon.RIGHT_STICK_UP -> R.drawable.kp_switch_stick_r_up
        SlotIcon.RIGHT_STICK_DOWN -> R.drawable.kp_switch_stick_r_down
        SlotIcon.RIGHT_STICK_LEFT -> R.drawable.kp_switch_stick_r_left
        SlotIcon.RIGHT_STICK_RIGHT -> R.drawable.kp_switch_stick_r_right
        SlotIcon.RIGHT_STICK_CLICK -> R.drawable.kp_switch_stick_r_press
        SlotIcon.VIEW -> R.drawable.kp_switch_button_minus
        SlotIcon.MENU -> R.drawable.kp_switch_button_plus
        SlotIcon.HOME -> R.drawable.kp_switch_button_home
        // No Switch "back" icon in the pack; Minus is the brief's stand-in.
        SlotIcon.BACK -> R.drawable.kp_switch_button_minus
    }
}
