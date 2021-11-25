/*
 * Copyright (c) 2021. Proton AG
 *
 * This file is part of ProtonVPN.
 *
 * ProtonVPN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ProtonVPN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ProtonVPN.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.protonvpn.android.logging

enum class LogCategory(private val categoryName: String) {
    API("api"),
    APP("app"),
    APP_UPDATE("app.update"),
    CONN("conn"),
    CONN_CONNECT("conn.connect"),
    CONN_DISCONNECT("conn.disconnect"),
    CONN_SERVER_SWITCH("conn.server_switch"),
    LOCAL_AGENT("local_agent"),
    NET("net"),
    OS("os"),
    PROTOCOL("protocol"),
    SETTINGS("settings"),
    UI("ui"),
    USER("user"),
    USER_CERT("user.cert"),
    USER_PLAN("user.plan");

    fun toLog() = categoryName
}

class LogEventType(
    private val category: LogCategory,
    private val name: String,
    val level: LogLevel
) {
    override fun toString() = "${level.toLog()} ${category.toLog()}:$name"
}

val ConnCurrentState = LogEventType(LogCategory.CONN, "current", LogLevel.INFO)
val ConnStateChange = LogEventType(LogCategory.CONN, "state_change", LogLevel.INFO)
val ConnError = LogEventType(LogCategory.CONN, "error", LogLevel.ERROR)
val ConnConnectTrigger = LogEventType(LogCategory.CONN_CONNECT, "trigger", LogLevel.INFO)
val ConnConnectScan = LogEventType(LogCategory.CONN_CONNECT, "scan", LogLevel.INFO)
val ConnConnectScanFailed = LogEventType(LogCategory.CONN_CONNECT, "scan_failed", LogLevel.INFO)
val ConnConnectScanResult = LogEventType(LogCategory.CONN_CONNECT, "scan_result", LogLevel.INFO)
val ConnConnectStart = LogEventType(LogCategory.CONN_CONNECT, "start", LogLevel.INFO)
val ConnConnectConnected = LogEventType(LogCategory.CONN_CONNECT, "connected", LogLevel.INFO)

val ConnDisconnectTrigger = LogEventType(LogCategory.CONN_DISCONNECT, "trigger", LogLevel.INFO)

val ConnServerSwitchTrigger = LogEventType(LogCategory.CONN_SERVER_SWITCH, "trigger", LogLevel.INFO)
val ConnServerSwitchServerSelected =
    LogEventType(LogCategory.CONN_SERVER_SWITCH, "server_selected", LogLevel.INFO)
val ConnServerSwitchFailed = LogEventType(LogCategory.CONN_SERVER_SWITCH, "switch_failed", LogLevel.INFO)

val LocalAgentLog = LogEventType(LogCategory.LOCAL_AGENT, "log", LogLevel.INFO)
val LocalAgentStateChange = LogEventType(LogCategory.LOCAL_AGENT, "state_change", LogLevel.INFO)
val LocalAgentError = LogEventType(LogCategory.LOCAL_AGENT, "error", LogLevel.ERROR)
val LocalAgentStatus = LogEventType(LogCategory.LOCAL_AGENT, "status", LogLevel.INFO)

val UserCertCurrentState = LogEventType(LogCategory.USER_CERT, "current", LogLevel.INFO)
val UserCertRefresh = LogEventType(LogCategory.USER_CERT, "refresh", LogLevel.INFO)
val UserCertRevoked = LogEventType(LogCategory.USER_CERT, "revoked", LogLevel.INFO)
val UserCertNewCert = LogEventType(LogCategory.USER_CERT, "new_cert", LogLevel.INFO)
val UserCertRefreshError = LogEventType(LogCategory.USER_CERT, "refresh_error", LogLevel.WARNING)
val UserCertScheduleRefresh = LogEventType(LogCategory.USER_CERT, "schedule_refresh", LogLevel.INFO)

val UserPlanCurrent = LogEventType(LogCategory.USER_PLAN, "current", LogLevel.INFO)
val UserPlanChange = LogEventType(LogCategory.USER_PLAN, "change", LogLevel.INFO)
val UserPlanMaxSessionsReached = LogEventType(LogCategory.USER_PLAN, "max_sessions_reached", LogLevel.INFO)
