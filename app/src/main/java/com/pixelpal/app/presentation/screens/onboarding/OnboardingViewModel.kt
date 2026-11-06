package com.pixelpal.app.presentation.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pixelpal.app.data.local.datastore.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    private val _petName = MutableStateFlow("Pixel")
    val petName: StateFlow<String> = _petName.asStateFlow()

    private val _userName = MutableStateFlow("")
    val userName: StateFlow<String> = _userName.asStateFlow()

    private val _selectedPetType = MutableStateFlow("cat")
    val selectedPetType: StateFlow<String> = _selectedPetType.asStateFlow()

    fun updatePetName(name: String) {
        _petName.value = name
    }

    fun updateUserName(name: String) {
        _userName.value = name
    }

    fun selectPetType(type: String) {
        _selectedPetType.value = type
    }

    fun saveAndComplete(onComplete: () -> Unit) {
        viewModelScope.launch {
            preferencesManager.setPetName(_petName.value.ifBlank { "Pixel" })
            val user = _userName.value.trim()
            if (user.isNotBlank()) {
                preferencesManager.setUserName(user)
            }
            preferencesManager.setSelectedPetType(_selectedPetType.value)
            preferencesManager.setIsFirstLaunch(false)
            onComplete()
        }
    }
}
