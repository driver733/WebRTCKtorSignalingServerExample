package com.webrtc.ktor.server

import io.ktor.http.*
import io.ktor.server.testing.*
import module
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {
    @Test
    fun testRoot() {
        withTestApplication({ module(testing = true) }) {
            handleRequest(HttpMethod.Get, "/").apply {
                assertEquals(HttpStatusCode.OK, response.status())
                assertEquals("Hello from WebRTC signaling server", response.content)
            }
        }
    }
}
