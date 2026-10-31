package com.odininputmirror.domain.usecase

import com.odininputmirror.domain.model.HomeButtonMode
import com.odininputmirror.domain.repository.MirrorSettingsRepository

class SetHomeModeUseCase(
    private val mirrorSettingsRepository: MirrorSettingsRepository,
) {
    operator fun invoke(mode: HomeButtonMode) = mirrorSettingsRepository.setHomeMode(mode)
}

class SetComboHoldKillAppEnabledUseCase(
    private val mirrorSettingsRepository: MirrorSettingsRepository,
) {
    operator fun invoke(enabled: Boolean) = mirrorSettingsRepository.setComboHoldKillAppEnabled(enabled)
}

class SetVirtualMouseEnabledUseCase(
    private val mirrorSettingsRepository: MirrorSettingsRepository,
) {
    operator fun invoke(enabled: Boolean) = mirrorSettingsRepository.setVirtualMouseEnabled(enabled)
}
