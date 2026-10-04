package com.squareify.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

/** Holds the app's [AppModel] (the grid and everything done to it), so it survives rotation and theme changes. */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    val platform = AndroidPlatform(application)
    val model = AppModel(platform, viewModelScope)
}
