package com.controlswitch.domain.usecase

import com.controlswitch.domain.repository.InputDeviceRepository

class GetConnectedDevicesUseCase(
    private val inputDeviceRepository: InputDeviceRepository,
) {
    operator fun invoke() = inputDeviceRepository.getConnectedControllers()
}
