package com.cutvideo.editor.domain.model

import java.util.UUID

/** Central id factory so every entity id has the same shape across the app. */
object Ids {
    fun newId(prefix: String): String = "$prefix-${UUID.randomUUID()}"
    fun project(): String = newId("prj")
    fun track(): String = newId("trk")
    fun clip(): String = newId("clp")
    fun media(): String = newId("med")
    fun mask(): String = newId("msk")
}
