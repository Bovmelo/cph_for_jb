package io.github.bovmelo.cph.model

import kotlinx.serialization.json.Json

object CphJson {
    val format = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
        prettyPrint = true
    }
}
