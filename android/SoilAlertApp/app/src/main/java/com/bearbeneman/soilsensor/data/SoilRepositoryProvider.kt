package com.bearbeneman.soilsensor.data

import android.content.Context

object SoilRepositoryProvider {
    fun provide(context: Context): SoilRepository =
        SoilRepository.getInstance(context.applicationContext)
}

