package com.omnioone.commons.compose.extensions

import android.content.Context
import com.omnioone.commons.helpers.BaseConfig

val Context.config: BaseConfig get() = BaseConfig.newInstance(applicationContext)
