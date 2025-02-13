/*
 * Copyright (c) 2021 Proton Technologies AG
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

package com.protonvpn.android.vpn

import com.protonvpn.android.api.ProtonApiRetroFit
import com.protonvpn.android.appconfig.AppConfig
import com.protonvpn.android.auth.data.VpnUser
import com.protonvpn.android.auth.usecase.CurrentUser
import com.protonvpn.android.logging.ConnServerSwitchFailed
import com.protonvpn.android.logging.ConnServerSwitchServerSelected
import com.protonvpn.android.logging.ConnServerSwitchTrigger
import com.protonvpn.android.logging.LogCategory
import com.protonvpn.android.logging.ProtonLogger
import com.protonvpn.android.logging.toLog
import com.protonvpn.android.models.config.UserData
import com.protonvpn.android.models.config.VpnProtocol
import com.protonvpn.android.models.profiles.Profile
import com.protonvpn.android.models.vpn.ConnectingDomain
import com.protonvpn.android.models.vpn.ConnectionParams
import com.protonvpn.android.models.vpn.Server
import com.protonvpn.android.models.vpn.usecase.GetConnectingDomain
import com.protonvpn.android.settings.data.EffectiveCurrentUserSettings
import com.protonvpn.android.settings.data.LocalUserSettings
import com.protonvpn.android.ui.home.ServerListUpdater
import com.protonvpn.android.utils.ServerManager
import com.protonvpn.android.utils.UserPlanManager
import com.protonvpn.android.utils.UserPlanManager.InfoChange.PlanChange
import com.protonvpn.android.utils.UserPlanManager.InfoChange.UserBecameDelinquent
import com.protonvpn.android.utils.UserPlanManager.InfoChange.VpnCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.proton.core.network.domain.ApiResult
import me.proton.core.network.domain.HttpResponseCodes
import me.proton.core.network.domain.NetworkManager
import java.io.Serializable
import javax.inject.Inject
import javax.inject.Singleton

sealed class SwitchServerReason : Serializable {

    data class Downgrade(val fromTier: String, val toTier: String) : SwitchServerReason()
    object UserBecameDelinquent : SwitchServerReason()
    object ServerInMaintenance : SwitchServerReason()
    object ServerUnreachable : SwitchServerReason()
    object ServerUnavailable : SwitchServerReason()
    object UnknownAuthFailure : SwitchServerReason()

    // Used in logging, provides readable names for the objects.
    override fun toString() = this::class.java.simpleName
}

sealed class VpnFallbackResult : Serializable {

    sealed class Switch : VpnFallbackResult() {

        abstract val log: String
        abstract val reason: SwitchServerReason?
        abstract val notifyUser: Boolean
        abstract val fromServer: Server?
        abstract val toServer: Server
        abstract val toProfile: Profile

        data class SwitchProfile(
            override val fromServer: Server?,
            override val toServer: Server,
            override val toProfile: Profile,
            override val reason: SwitchServerReason? = null,
        ) : Switch() {
            override val notifyUser = reason != null
            override val log get() = "SwitchProfile ${toProfile.name} reason: $reason"
        }

        data class SwitchServer(
            override val fromServer: Server?,
            override val toProfile: Profile,
            val preparedConnection: PrepareResult,
            override val reason: SwitchServerReason,
            val compatibleProtocol: Boolean,
            val switchedSecureCore: Boolean,
            override val notifyUser: Boolean
        ) : Switch() {
            override val toServer get() = preparedConnection.connectionParams.server
            override val log get() = "SwitchServer ${preparedConnection.connectionParams.info} " +
                "reason: $reason compatibleProtocol: $compatibleProtocol"
        }
    }

    data class Error(val type: ErrorType) : VpnFallbackResult()
}

data class PhysicalServer(val server: Server, val connectingDomain: ConnectingDomain)

@Singleton
class VpnConnectionErrorHandler @Inject constructor(
    scope: CoroutineScope,
    private val api: ProtonApiRetroFit,
    private val appConfig: AppConfig,
    private val userSettings: EffectiveCurrentUserSettings,
    private val userPlanManager: UserPlanManager,
    private val serverManager: ServerManager,
    private val stateMonitor: VpnStateMonitor,
    private val serverListUpdater: ServerListUpdater,
    private val networkManager: NetworkManager,
    private val vpnBackendProvider: VpnBackendProvider,
    private val currentUser: CurrentUser,
    private val getConnectingDomain: GetConnectingDomain,
    @Suppress("unused") errorUIManager: VpnErrorUIManager // Forces creation of a VpnErrorUiManager instance.
) {
    private var handlingAuthError = false

    val switchConnectionFlow = MutableSharedFlow<VpnFallbackResult.Switch>()

    init {
        scope.launch {
            userPlanManager.infoChangeFlow.collect { changes ->
                if (!handlingAuthError && stateMonitor.isEstablishingOrConnected) {
                    getCommonFallbackForInfoChanges(
                        stateMonitor.connectionParams!!.server,
                        changes,
                        currentUser.vpnUser()
                    )?.let {
                        switchConnectionFlow.emit(it)
                    }
                }
            }
        }
    }

    @SuppressWarnings("ReturnCount")
    private fun getCommonFallbackForInfoChanges(
        currentServer: Server,
        changes: List<UserPlanManager.InfoChange>,
        vpnUser: VpnUser?
    ): VpnFallbackResult.Switch? {
        val fallbackProfile = serverManager.defaultFallbackConnection
        val fallbackServer = serverManager.getServerForProfile(fallbackProfile, vpnUser) ?: return null
        for (change in changes) when (change) {
            is PlanChange.Downgrade -> {
                return VpnFallbackResult.Switch.SwitchProfile(
                    currentServer,
                    fallbackServer,
                    fallbackProfile,
                    SwitchServerReason.Downgrade(change.fromPlan, change.toPlan)
                )
            }
            UserBecameDelinquent ->
                return VpnFallbackResult.Switch.SwitchProfile(
                    currentServer,
                    fallbackServer,
                    fallbackProfile,
                    SwitchServerReason.UserBecameDelinquent
                )
            else -> {}
        }
        return null
    }

    enum class CompatibilityAspect {
        Features, Tier, City, Country, SecureCore
    }

    private val smartReconnectEnabled get() = appConfig.getFeatureFlags().vpnAccelerator

    suspend fun onServerNotAvailable(profile: Profile) =
        fallbackToCompatibleServer(profile, null, false, SwitchServerReason.ServerUnavailable)

    suspend fun onServerInMaintenance(profile: Profile, connectionParams: ConnectionParams?) =
        fallbackToCompatibleServer(
            profile, connectionParams, false, SwitchServerReason.ServerInMaintenance
        )

    suspend fun onUnreachableError(connectionParams: ConnectionParams): VpnFallbackResult =
        fallbackToCompatibleServer(
            connectionParams.profile,
            connectionParams,
            true,
            SwitchServerReason.ServerUnreachable
        )
            ?: VpnFallbackResult.Error(ErrorType.UNREACHABLE)

    private suspend fun fallbackToCompatibleServer(
        orgProfile: Profile,
        orgParams: ConnectionParams?,
        includeOriginalServer: Boolean,
        reason: SwitchServerReason
    ): VpnFallbackResult.Switch? {
        if (!smartReconnectEnabled) {
            ProtonLogger.logCustom(LogCategory.CONN_SERVER_SWITCH, "Smart Reconnect disabled")
            return null
        }

        if (orgProfile.isGuestHoleProfile) {
            ProtonLogger.logCustom(LogCategory.CONN_SERVER_SWITCH, "Ignoring reconnection for Guest Hole")
            return null
        }

        ProtonLogger.log(ConnServerSwitchTrigger, "reason: $reason")
        if (!networkManager.isConnectedToNetwork()) {
            ProtonLogger.log(ConnServerSwitchFailed, "No internet: aborting fallback")
            return null
        }

        if (serverListUpdater.needsUpdate) {
            serverListUpdater.updateServerList()
        }

        val settings = userSettings.effectiveSettings.first()
        val vpnUser = currentUser.vpnUser()
        val orgPhysicalServer =
            orgParams?.connectingDomain?.let { PhysicalServer(orgParams.server, it) }?.takeIf { it.exists() }
        val candidates = getCandidateServers(orgProfile, orgPhysicalServer, vpnUser, includeOriginalServer, settings)

        candidates.forEach {
            ProtonLogger.logCustom(
                LogCategory.CONN_SERVER_SWITCH,
                "Fallback server: ${it.connectingDomain.entryDomain} city=${it.server.city}"
            )
        }

        val orgProtocol = orgProfile.getProtocol(settings)
        val pingResult = vpnBackendProvider.pingAll(orgProtocol, candidates, orgPhysicalServer) ?: run {
            ProtonLogger.log(ConnServerSwitchFailed, "No server responded")
            return null
        }

        // Original server + protocol responded, don't switch
        if (
            pingResult.physicalServer == orgPhysicalServer &&
            pingResult.responses.any { it.connectionParams.hasSameProtocolParams(orgParams) }
        ) {
            ProtonLogger.log(
                ConnServerSwitchServerSelected,
                "Got response for current connection - don't switch VPN server"
            )
            return null
        }

        val expectedProtocolConnection = pingResult.getExpectedProtocolConnection(orgProtocol)
        val score = getServerScore(pingResult.physicalServer.server, orgProfile, vpnUser, settings)
        val secureCoreExpected = orgProfile.isSecureCore ?: settings.secureCore
        val switchedSecureCore = secureCoreExpected && !hasCompatibility(score, CompatibilityAspect.SecureCore)
        val isCompatible = isCompatibleServer(score, pingResult.physicalServer, orgPhysicalServer) &&
            expectedProtocolConnection != null && !switchedSecureCore


        ProtonLogger.log(
            ConnServerSwitchServerSelected,
            pingResult.profile.toLog(settings) + " " +
                with(pingResult.physicalServer) { "${server.serverName} ${connectingDomain.entryDomain}" }
        )
        return VpnFallbackResult.Switch.SwitchServer(
            orgParams?.server,
            pingResult.profile,
            expectedProtocolConnection ?: pingResult.responses.first(),
            reason,
            notifyUser = !isCompatible,
            compatibleProtocol = expectedProtocolConnection != null,
            switchedSecureCore = switchedSecureCore
        )
    }

    private fun hasCompatibility(score: Int, aspect: CompatibilityAspect) =
        score and (1 shl aspect.ordinal) != 0

    private fun isCompatibleServer(score: Int, physicalServer: PhysicalServer, orgPhysicalServer: PhysicalServer?) =
        hasCompatibility(score, CompatibilityAspect.Country) &&
        hasCompatibility(score, CompatibilityAspect.Features) &&
        hasCompatibility(score, CompatibilityAspect.SecureCore) &&
        (hasCompatibility(score, CompatibilityAspect.Tier) ||
            orgPhysicalServer == null || physicalServer.server.tier >= orgPhysicalServer.server.tier)

    // Return first response that's has protocol matching settings.
    private fun VpnBackendProvider.PingResult.getExpectedProtocolConnection(
        expectedProtocol: ProtocolSelection
    ): PrepareResult? {
        if (expectedProtocol.vpn == VpnProtocol.Smart)
            return responses.first()

        return responses.firstOrNull { it.connectionParams.protocolSelection == expectedProtocol }
    }

    private fun getCandidateServers(
        orgProfile: Profile,
        orgPhysicalServer: PhysicalServer?,
        vpnUser: VpnUser?,
        includeOrgServer: Boolean,
        settings: LocalUserSettings
    ): List<PhysicalServer> {
        val candidateList = mutableListOf<PhysicalServer>()
        if (orgPhysicalServer != null && includeOrgServer)
            candidateList += orgPhysicalServer

        val orgProfileServer = orgProfile.directServerId?.let { serverManager.getServerById(it) }

        val secureCoreExpected = orgProfile.isSecureCore ?: settings.secureCore
        val gatewayName = (orgPhysicalServer?.server ?: orgProfileServer)?.gatewayName
        val orgProtocol = orgProfile.getProtocol(settings)
        val onlineServers =
            serverManager.getOnlineAccessibleServers(secureCoreExpected, gatewayName, vpnUser, orgProtocol)
        val orgIsTor = orgPhysicalServer?.server?.isTor == true
        val orgEntryIp = orgPhysicalServer?.connectingDomain?.getEntryIp(orgProtocol)
        val scoredServers = sortServersByScore(onlineServers, orgProfile, vpnUser, settings).filter { candicate ->
            val ipCondition = orgPhysicalServer == null ||
                getConnectingDomain.online(candicate, orgProtocol).any { domain ->
                    domain.getEntryIp(orgProtocol) != orgEntryIp
                }
            val torCondition = orgIsTor || !candicate.isTor
            ipCondition && torCondition
        }

        scoredServers.take(FALLBACK_SERVERS_COUNT - candidateList.size).map { server ->
            getConnectingDomain.online(server, orgProtocol).filter {
                // Ignore connecting domains with the same IP as current connection.
                it.getEntryIp(orgProtocol) != orgPhysicalServer?.connectingDomain?.getEntryIp(orgProtocol)
            }.randomOrNull()?.let { connectingDomain ->
                candidateList += PhysicalServer(server, connectingDomain)
            }
        }

        val fallbacks = mutableListOf<Server>()
        val exitCountries = candidateList.map { it.server.exitCountry }.toSet()

        // All servers from the same country
        if (exitCountries.size == 1) {
            val country = exitCountries.first()

            // All servers from the same city, add different city
            val cities = candidateList.map { it.server.city }.toSet()
            if (cities.size == 1) {
                val city = cities.first()
                scoredServers.firstOrNull { it.exitCountry == country && it.city != city }?.let {
                    fallbacks += it
                }
            }

            // Add server from different country
            scoredServers.firstOrNull { it.exitCountry != country }?.let {
                fallbacks += it
            }
        }

        // For secure core add best scoring non-secure server as a last resort fallback
        if (secureCoreExpected) {
            sortServersByScore(
                serverManager.getOnlineAccessibleServers(false, null, vpnUser, orgProtocol),
                orgProfile,
                vpnUser,
                settings
            ).firstOrNull()?.let { fallbacks += it }
        }

        return candidateList.take(FALLBACK_SERVERS_COUNT - fallbacks.size) + fallbacks.mapNotNull { server ->
            getConnectingDomain.random(server, orgProtocol)?.let {
                PhysicalServer(server, it)
            }
        }
    }

    private fun getServerScore(
        server: Server,
        orgProfile: Profile,
        vpnUser: VpnUser?,
        settings: LocalUserSettings
    ): Int {
        var score = 0

        if (orgProfile.country.isBlank() || orgProfile.country == server.exitCountry)
            score += 1 shl CompatibilityAspect.Country.ordinal

        val orgDirectServer = orgProfile.directServerId?.let { serverManager.getServerById(it) }
        if (orgDirectServer?.city.isNullOrBlank() || orgDirectServer?.city == server.city)
            score += 1 shl CompatibilityAspect.City.ordinal

        if (vpnUser?.userTier == server.tier)
            // Prefer servers from user tier
            score += 1 shl CompatibilityAspect.Tier.ordinal

        if (orgDirectServer == null || server.features == orgDirectServer.features)
            score += 1 shl CompatibilityAspect.Features.ordinal

        val secureCoreExpected = orgProfile.isSecureCore ?: settings.secureCore
        if (!secureCoreExpected || server.isSecureCoreServer)
            score += 1 shl CompatibilityAspect.SecureCore.ordinal

        return score
    }

    private fun sortServersByScore(
        servers: List<Server>,
        profile: Profile,
        vpnUser: VpnUser?,
        settings: LocalUserSettings,
    ) = sortByScore(servers) { getServerScore(it, profile, vpnUser, settings) }

    private fun <T> sortByScore(servers: List<T>, scoreFun: (T) -> Int) =
        servers.map { Pair(it, scoreFun(it)) }.sortedByDescending { it.second }.map { it.first }

    @SuppressWarnings("ReturnCount")
    suspend fun onAuthError(connectionParams: ConnectionParams): VpnFallbackResult {
        try {
            handlingAuthError = true
            val vpnInfo = currentUser.vpnUser()
            userPlanManager.refreshVpnInfo()
            val newVpnInfo = currentUser.vpnUser()
            if (vpnInfo != null && newVpnInfo != null) {
                userPlanManager.computeUserInfoChanges(vpnInfo, newVpnInfo).let { infoChanges ->
                    val vpnUser = currentUser.vpnUser()
                    getCommonFallbackForInfoChanges(connectionParams.server, infoChanges, vpnUser)?.let {
                        return it
                    }

                    if (VpnCredentials in infoChanges) {
                        // Now that credentials are refreshed we can try reconnecting.
                        return with(connectionParams) {
                            VpnFallbackResult.Switch.SwitchProfile(server, server, profile)
                        }
                    }

                    val maxSessions = requireNotNull(vpnUser).maxConnect
                    val sessionCount = api.getSession().valueOrNull?.sessionList?.size ?: 0
                    if (maxSessions <= sessionCount)
                        return VpnFallbackResult.Error(ErrorType.MAX_SESSIONS)
                }
            }

            return getMaintenanceFallback(connectionParams)
                // We couldn't establish if server is in maintenance, attempt searching for fallback anyway. Include
                // current connection in the search.
                ?: fallbackToCompatibleServer(
                    connectionParams.profile, connectionParams, true, SwitchServerReason.UnknownAuthFailure)
                ?: VpnFallbackResult.Error(ErrorType.AUTH_FAILED)
        } finally {
            handlingAuthError = false
        }
    }

    private suspend fun getMaintenanceFallback(connectionParams: ConnectionParams): VpnFallbackResult.Switch? {
        if (!appConfig.isMaintenanceTrackerEnabled())
            return null

        ProtonLogger.logCustom(LogCategory.CONN_SERVER_SWITCH, "Checking if server is not in maintenance")
        val domainId = connectionParams.connectingDomain?.id ?: return null
        val result = api.getConnectingDomain(domainId)
        var findNewServer = false
        when {
            result is ApiResult.Success -> {
                val connectingDomain = result.value.connectingDomain
                if (!connectingDomain.isOnline) {
                    ProtonLogger.logCustom(
                        LogCategory.CONN_SERVER_SWITCH,
                        "Current server is in maintenance (${connectingDomain.entryDomain})"
                    )
                    serverManager.updateServerDomainStatus(connectingDomain)
                    serverListUpdater.updateServerList()
                    findNewServer = true
                }
            }
            result is ApiResult.Error.Http && result.httpCode == HttpResponseCodes.HTTP_UNPROCESSABLE -> {
                serverListUpdater.updateServerList()
                findNewServer = true
            }
        }
        if (findNewServer) {
            return if (smartReconnectEnabled) {
                onServerInMaintenance(connectionParams.profile, connectionParams)
            } else {
                ProtonLogger.log(
                    ConnServerSwitchServerSelected,
                    "Smart reconnect disabled, fall back to default connection"
                )
                val fallbackProfile = serverManager.defaultFallbackConnection
                val fallbackServer = serverManager.getServerForProfile(fallbackProfile, currentUser.vpnUser())
                fallbackServer?.let {
                    VpnFallbackResult.Switch.SwitchProfile(connectionParams.server, fallbackServer, fallbackProfile)
                }
            }
        } else {
            return null
        }
    }

    private fun PhysicalServer.exists(): Boolean =
        serverManager.getServerById(server.serverId)?.connectingDomains?.contains(connectingDomain) == true

    suspend fun maintenanceCheck() {
        stateMonitor.connectionParams?.let { params ->
            getMaintenanceFallback(params)?.let {
                switchConnectionFlow.emit(it)
            }
        }
    }

    companion object {
        private const val FALLBACK_SERVERS_COUNT = 5
    }
}
