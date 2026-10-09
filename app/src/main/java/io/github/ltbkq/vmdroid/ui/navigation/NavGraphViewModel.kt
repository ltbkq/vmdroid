package io.github.ltbkq.vmdroid.ui.navigation

import androidx.lifecycle.ViewModel
import io.github.ltbkq.vmdroid.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Exposes only the bits NavGraph itself needs — keeps MainActivity free of repo wiring. */
@HiltViewModel
class NavGraphViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
) : ViewModel() {
    val isSetupDone = settingsRepository.isSetupDone
    val darkTheme   = settingsRepository.darkTheme
    val dynamicColorEnabled = settingsRepository.dynamicColorEnabled
}
