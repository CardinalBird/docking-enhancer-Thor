package com.controlswitch.domain.usecase

import com.controlswitch.domain.model.HomeButtonMode
import com.controlswitch.domain.model.MirrorStatus
import com.controlswitch.domain.repository.DockStateRepository
import com.controlswitch.domain.repository.MirrorProcessRepository
import com.controlswitch.domain.repository.MirrorSettingsRepository

class GetMirrorStatusUseCase(
    private val mirrorProcessRepository: MirrorProcessRepository,
    private val mirrorSettingsRepository: MirrorSettingsRepository,
    private val dockStateRepository: DockStateRepository,
) {
    operator fun invoke(): MirrorStatus {
        val running = mirrorProcessRepository.isRunning()
        val settings = mirrorSettingsRepository.getSettings()
        return MirrorStatus(
            running = running,
            expectedRunning = settings.expectedRunning,
            source = settings.source,
            target = settings.target,
            sourceGuid = settings.sourceGuid,
            targetGuid = settings.targetGuid,
            homeMode = settings.homeMode,
            comboHoldKillApp = settings.comboHoldKillApp,
            virtualMouse = settings.virtualMouse,
            autoMirrorEnabled = settings.autoMirrorEnabled,
            docked = dockStateRepository.isDockActive(),
            manualInternalGuid = settings.manualInternalGuid,
        )
    }
}
