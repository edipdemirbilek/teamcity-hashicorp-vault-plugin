/*
 * Copyright 2000-2020 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.teamcity.vault.server

import jetbrains.buildServer.controllers.*
import jetbrains.buildServer.controllers.admin.projects.EditVcsRootsController
import jetbrains.buildServer.controllers.admin.projects.PluginPropertiesUtil
import jetbrains.buildServer.serverSide.*
import jetbrains.buildServer.serverSide.auth.AccessDeniedException
import jetbrains.buildServer.serverSide.auth.Permission
import jetbrains.buildServer.util.ssl.SSLTrustStoreProvider
import jetbrains.buildServer.web.openapi.WebControllerManager
import org.jdom.Element
import org.jetbrains.teamcity.vault.SessionManagerBuilder
import org.jetbrains.teamcity.vault.VaultParameterSettings
import org.jetbrains.teamcity.vault.VaultQuery
import org.jetbrains.teamcity.vault.VaultResolver
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.ModelAndView
import java.io.OutputStreamWriter
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class VaultTestQueryController(
    server: SBuildServer, wcm: WebControllerManager,
    authInterceptor: AuthorizationInterceptor,
    private val sslTrustStoreProvider: SSLTrustStoreProvider,
    private val vaultResolver: VaultResolver,
    private val projectManager: ProjectManager,
    private val hashiCorpVaultConnectionResolver: HashiCorpVaultConnectionResolver,
    private val sessionManagerBuilder: SessionManagerBuilder,
    private val connector: VaultConnector,
) : BaseFormXmlController(server), RequestPermissionsCheckerEx {

    private val scheduler: TaskScheduler = ConcurrentTaskScheduler()

    companion object {
        const val PATH = "/admin/hashicorp-vault-test-query.html"
        const val PROJECT_ID = "projectId"
    }

    init {
        wcm.registerController(PATH, this)
        authInterceptor.addPathBasedPermissionsChecker(PATH, this)
    }

    override fun doGet(request: HttpServletRequest, response: HttpServletResponse) = null

    override fun doPost(request: HttpServletRequest, response: HttpServletResponse, xmlResponse: Element) {
        val propertiesBean = BasePropertiesBean(emptyMap())
        PluginPropertiesUtil.bindPropertiesFromRequest(request, propertiesBean)
        val properties = propertiesBean.properties.toMutableMap()

        val projectId = properties[PROJECT_ID] ?: return writeError(response, HttpStatus.BAD_REQUEST, "Failed to find projectId parameter")
        val project = projectManager.findProjectByExternalId(projectId) ?: return writeError(response, HttpStatus.NOT_FOUND, "Project $projectId not found")

        doTestQuery(project, properties, xmlResponse)
    }

    private fun writeError(response: HttpServletResponse, httpStatus: HttpStatus, errorMessage: String): Unit {
        val writer = OutputStreamWriter(response.outputStream)
        response.apply {
            status = httpStatus.value()
            contentType = MediaType.TEXT_PLAIN_VALUE
        }

        writer.write(errorMessage)
        writer.flush()
    }

    private fun doTestQuery(project: SProject, properties: Map<String, String>, xmlResponse: Element) {
        val errors = ActionErrors()

        VaultParameterSettings.getInvalidProperties(properties).forEach { errors.addError(InvalidProperty(it.key, it.value)) }
        if (errors.hasErrors()) {
            errors.serialize(xmlResponse)
            return
        }

        try {
            val parameterSettings = VaultParameterSettings(properties)
            val serverFeature = hashiCorpVaultConnectionResolver
                .getProjectToConnectionPairs(project)
                .filter { it.second.namespace == parameterSettings.getNamespace() }
                .firstOrNull()?.second

            if (serverFeature == null) {
                errors.addError(EditVcsRootsController.FAILED_TEST_CONNECTION_ERR, "Failed to find hashicorp connection ${parameterSettings.getNamespace()}")
                errors.serialize(xmlResponse)
                return
            }

            IOGuard.allowNetworkCall<Exception> {
                val agentFeature = hashiCorpVaultConnectionResolver
                    .serverFeatureSettingsToAgentSettings(serverFeature, parameterSettings.getNamespace())
                val token = sessionManagerBuilder
                    .build(agentFeature).sessionToken.token
                val query = VaultQuery.extract(parameterSettings.vaultQuery)


                val result = vaultResolver.doFetchAndPrepareReplacements(agentFeature, token, listOf(query))
                if (result.errors.isNotEmpty()) {
                    errors.addError(EditVcsRootsController.FAILED_TEST_CONNECTION_ERR, "Error while fetching parameter: ${result.errors.values.first()}")
                    errors.serialize(xmlResponse)
                } else {
                    XmlResponseUtil.writeTestResult(xmlResponse, result.replacements.values.first())
                }
            }
            return
        } catch (e: Exception) {
            errors.addError(EditVcsRootsController.FAILED_TEST_CONNECTION_ERR, e.message)
        }

        if (errors.hasErrors()) {
            errors.serialize(xmlResponse)
        }
    }

    override fun checkPermissions(securityContext: SecurityContextEx, request: HttpServletRequest) {
        val projectId = request.getParameter(PROJECT_ID)
        val project = projectManager.findProjectByExternalId(projectId)
        if (project == null) {
            throw AccessDeniedException(securityContext.authorityHolder, "No project $projectId")
        } else {
            securityContext.authorityHolder.isPermissionGrantedForProject(project.projectId, Permission.EDIT_PROJECT)
        }
    }
}