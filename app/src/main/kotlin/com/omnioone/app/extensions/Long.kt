package com.omnioone.app.extensions

import com.omnioone.commons.extensions.toDayCode

fun Long.getDayCode(): String {
    return toDayCode("yyyy-MM-dd") // format helps with sorting in call log
}
