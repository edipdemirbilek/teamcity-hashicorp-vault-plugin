package org.jetbrains.teamcity.vault.server

import assertk.assertions.contains
import jetbrains.buildServer.controllers.BaseControllerTestCase
import org.jetbrains.teamcity.vault.*
import org.mockito.Answers
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.testng.MockitoTestNGListener
import org.springframework.http.HttpStatus
import org.springframework.vault.VaultException
import org.testng.Assert
import org.testng.Assert.*
import org.testng.annotations.Listeners
import org.testng.annotations.Test

@Listeners(MockitoTestNGListener::class)
class VaultTestQueryControllerTest : BaseControllerTestCase<VaultTestQueryController>() {
    @Mock
    private lateinit var vaultResolver: VaultResolver

    @Mock
    private lateinit var hashiCorpVaultConnectionResolver: HashiCorpVaultConnectionResolver

    @Mock
    private lateinit var vaultConnector: VaultConnector

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private lateinit var sessionManagerBuilder: SessionManagerBuilder

    override fun createController(): VaultTestQueryController = VaultTestQueryController(
        myServer,
        webFixture.webManager,
        webFixture.authorizationInterceptor,
        { null },
        vaultResolver,
        myProjectManager,
        hashiCorpVaultConnectionResolver,
        sessionManagerBuilder,
        vaultConnector
    )

    @Test
    fun testQuery() {
        val serverSettings = getDefaultSettings(Auth.getServerAuthFromProperties(emptyMap()))

        Mockito.`when`(hashiCorpVaultConnectionResolver.getProjectToConnectionPairs(myProject))
            .thenReturn(
                listOf(
                    myProject.projectId to serverSettings,
                    myProject.projectId to VaultFeatureSettings("url", "vaultNamespace")
                )
            )
        val agentSettings = getDefaultSettings(Auth.getAgentAuthFromProperties(emptyMap()))

        Mockito.`when`(hashiCorpVaultConnectionResolver.serverFeatureSettingsToAgentSettings(serverSettings, NAMESPACE))
            .thenReturn(agentSettings)
        Mockito.`when`(sessionManagerBuilder.build(agentSettings).sessionToken.token)
            .thenReturn(TOKEN)

        val query = VaultQuery.extract(VAULT_QUERY)
        val result = VaultResolver.ResolvingResult(
            mapOf(
                query.full to SECRET_VALUE
            ), emptyMap()
        )

        Mockito.`when`(vaultResolver.doFetchAndPrepareReplacements(agentSettings, TOKEN, listOf(query)))
            .thenReturn(result)

        doPost(
            "prop:${VaultTestQueryController.PROJECT_ID}", myProject.externalId,
            "prop:${VaultConstants.ParameterSettings.VAULT_QUERY}", VAULT_QUERY,
            "prop:${VaultConstants.ParameterSettings.NAMESPACE}", NAMESPACE,
        )

        val response = myResponse.returnedContentAsXml.getChild("testConnectionResult")?.value
        Assert.assertNotNull(response)
        Assert.assertEquals(response, SECRET_VALUE)
    }

    @Test
    fun testQuery_NoProjectId() {
        doPost(
            "prop:${VaultConstants.ParameterSettings.VAULT_QUERY}", VAULT_QUERY,
            "prop:${VaultConstants.ParameterSettings.NAMESPACE}", NAMESPACE,
        )

        Assert.assertEquals(myResponse.status, HttpStatus.BAD_REQUEST.value())
    }

    @Test
    fun testQuery_WrongProjectId() {
        doPost(
            "prop:${VaultTestQueryController.PROJECT_ID}", "fakeProject",
            "prop:${VaultConstants.ParameterSettings.VAULT_QUERY}", VAULT_QUERY,
            "prop:${VaultConstants.ParameterSettings.NAMESPACE}", NAMESPACE,
        )

        Assert.assertEquals(myResponse.status, HttpStatus.NOT_FOUND.value())
    }

    @Test
    fun testQuery_MissingVaultQuery() {

        doPost(
            "prop:${VaultTestQueryController.PROJECT_ID}", myProject.externalId,
            "prop:${VaultConstants.ParameterSettings.NAMESPACE}", NAMESPACE,
        )

        val response = myResponse.returnedContentAsXml.getChild("errors")?.getChild("error")?.value
        Assert.assertNotNull(response)
        assertk.assertThat(response!!)
            .contains("vault query")
    }

    @Test
    fun testQuery_MissingNamespace() {

        doPost(
            "prop:${VaultTestQueryController.PROJECT_ID}", myProject.externalId,
            "prop:${VaultConstants.ParameterSettings.VAULT_QUERY}", VAULT_QUERY,
        )

        val response = myResponse.returnedContentAsXml.getChild("errors")?.getChild("error")?.value
        Assert.assertNotNull(response)
        assertk.assertThat(response!!)
            .contains("namespace")
    }

    @Test
    fun testQuery_MissingConnection() {

        Mockito.`when`(hashiCorpVaultConnectionResolver.getProjectToConnectionPairs(myProject))
            .thenReturn(
                listOf(
                    myProject.projectId to VaultFeatureSettings("url", "vaultNamespace")
                )
            )

        doPost(
            "prop:${VaultTestQueryController.PROJECT_ID}", myProject.externalId,
            "prop:${VaultConstants.ParameterSettings.VAULT_QUERY}", VAULT_QUERY,
            "prop:${VaultConstants.ParameterSettings.NAMESPACE}", NAMESPACE,
        )

        val response = myResponse.returnedContentAsXml.getChild("errors")?.getChild("error")?.value
        Assert.assertNotNull(response)
        assertk.assertThat(response!!)
            .contains("hashicorp connection")
    }

    @Test
    fun testQuery_FailureToGenerateAgentSettings() {
        val serverSettings = getDefaultSettings(Auth.getServerAuthFromProperties(emptyMap()))

        Mockito.`when`(hashiCorpVaultConnectionResolver.getProjectToConnectionPairs(myProject))
            .thenReturn(
                listOf(
                    myProject.projectId to serverSettings,
                    myProject.projectId to VaultFeatureSettings("url", "vaultNamespace")
                )
            )

        val error = "Mock error"
        Mockito.`when`(hashiCorpVaultConnectionResolver.serverFeatureSettingsToAgentSettings(serverSettings, NAMESPACE))
            .thenThrow(VaultException(error))

        doPost(
            "prop:${VaultTestQueryController.PROJECT_ID}", myProject.externalId,
            "prop:${VaultConstants.ParameterSettings.VAULT_QUERY}", VAULT_QUERY,
            "prop:${VaultConstants.ParameterSettings.NAMESPACE}", NAMESPACE,
        )

        val response = myResponse.returnedContentAsXml.getChild("errors")?.getChild("error")?.value
        Assert.assertNotNull(response)
        assertk.assertThat(response!!)
            .contains(error)
    }

    @Test
    fun testQuery_FailureToBuildSession() {
        val serverSettings = getDefaultSettings(Auth.getServerAuthFromProperties(emptyMap()))

        Mockito.`when`(hashiCorpVaultConnectionResolver.getProjectToConnectionPairs(myProject))
            .thenReturn(
                listOf(
                    myProject.projectId to serverSettings,
                    myProject.projectId to VaultFeatureSettings("url", "vaultNamespace")
                )
            )
        val agentSettings = getDefaultSettings(Auth.getAgentAuthFromProperties(emptyMap()))

        Mockito.`when`(hashiCorpVaultConnectionResolver.serverFeatureSettingsToAgentSettings(serverSettings, NAMESPACE))
            .thenReturn(agentSettings)
        val error = "Mock error"
        Mockito.`when`(sessionManagerBuilder.build(agentSettings).sessionToken.token)
            .thenThrow(VaultException(error))

        doPost(
            "prop:${VaultTestQueryController.PROJECT_ID}", myProject.externalId,
            "prop:${VaultConstants.ParameterSettings.VAULT_QUERY}", VAULT_QUERY,
            "prop:${VaultConstants.ParameterSettings.NAMESPACE}", NAMESPACE,
        )

        val response = myResponse.returnedContentAsXml.getChild("errors")?.getChild("error")?.value
        Assert.assertNotNull(response)
        assertk.assertThat(response!!)
            .contains(error)
    }

    @Test
    fun testQuery_FailureToGetReplacements() {
        val serverSettings = getDefaultSettings(Auth.getServerAuthFromProperties(emptyMap()))

        Mockito.`when`(hashiCorpVaultConnectionResolver.getProjectToConnectionPairs(myProject))
            .thenReturn(
                listOf(
                    myProject.projectId to serverSettings,
                    myProject.projectId to VaultFeatureSettings("url", "vaultNamespace")
                )
            )
        val agentSettings = getDefaultSettings(Auth.getAgentAuthFromProperties(emptyMap()))

        Mockito.`when`(hashiCorpVaultConnectionResolver.serverFeatureSettingsToAgentSettings(serverSettings, NAMESPACE))
            .thenReturn(agentSettings)
        Mockito.`when`(sessionManagerBuilder.build(agentSettings).sessionToken.token)
            .thenReturn(TOKEN)

        val query = VaultQuery.extract(VAULT_QUERY)
        VaultResolver.ResolvingResult(
            mapOf(
                query.full to SECRET_VALUE
            ), emptyMap()
        )

        val error = "Mock error"
        Mockito.`when`(vaultResolver.doFetchAndPrepareReplacements(agentSettings, TOKEN, listOf(query)))
            .thenThrow(VaultException(error))

        doPost(
            "prop:${VaultTestQueryController.PROJECT_ID}", myProject.externalId,
            "prop:${VaultConstants.ParameterSettings.VAULT_QUERY}", VAULT_QUERY,
            "prop:${VaultConstants.ParameterSettings.NAMESPACE}", NAMESPACE,
        )

        val response = myResponse.returnedContentAsXml.getChild("errors")?.getChild("error")?.value
        Assert.assertNotNull(response)
        assertk.assertThat(response!!)
            .contains(error)
    }

    @Test
    fun testQuery_FailureErrorReplacement() {
        val serverSettings = getDefaultSettings(Auth.getServerAuthFromProperties(emptyMap()))

        Mockito.`when`(hashiCorpVaultConnectionResolver.getProjectToConnectionPairs(myProject))
            .thenReturn(
                listOf(
                    myProject.projectId to serverSettings,
                    myProject.projectId to VaultFeatureSettings("url", "vaultNamespace")
                )
            )
        val agentSettings = getDefaultSettings(Auth.getAgentAuthFromProperties(emptyMap()))

        Mockito.`when`(hashiCorpVaultConnectionResolver.serverFeatureSettingsToAgentSettings(serverSettings, NAMESPACE))
            .thenReturn(agentSettings)
        Mockito.`when`(sessionManagerBuilder.build(agentSettings).sessionToken.token)
            .thenReturn(TOKEN)

        val error = "Mock error"
        val query = VaultQuery.extract(VAULT_QUERY)
        val result = VaultResolver.ResolvingResult(
            emptyMap(), mapOf(
                query.full to error
            )
        )

        Mockito.`when`(vaultResolver.doFetchAndPrepareReplacements(agentSettings, TOKEN, listOf(query)))
            .thenReturn(result)

        doPost(
            "prop:${VaultTestQueryController.PROJECT_ID}", myProject.externalId,
            "prop:${VaultConstants.ParameterSettings.VAULT_QUERY}", VAULT_QUERY,
            "prop:${VaultConstants.ParameterSettings.NAMESPACE}", NAMESPACE,
        )

        val response = myResponse.returnedContentAsXml.getChild("errors")?.getChild("error")?.value
        Assert.assertNotNull(response)
        assertk.assertThat(response!!)
            .contains(error)
    }

    private fun getDefaultSettings(auth: Auth) = VaultFeatureSettings(
        NAMESPACE, "url", "vaultNamespace", true, auth
    )

    companion object {
        const val VAULT_QUERY = "path/to!/key"
        const val NAMESPACE = "namespace"
        const val TOKEN = "token"
        const val SECRET_VALUE = "secretValue"
    }
}