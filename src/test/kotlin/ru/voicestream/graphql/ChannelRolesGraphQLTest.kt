package ru.voicestream.graphql

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.path.json.JsonPath
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.UserTransaction
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.voicestream.domain.ChannelType
import ru.voicestream.domain.PermissionEffect
import ru.voicestream.domain.RoleKind
import ru.voicestream.persistence.entity.ChannelEntity
import ru.voicestream.persistence.entity.ChannelMemberEntity
import ru.voicestream.persistence.entity.ChannelMemberRoleEntity
import ru.voicestream.persistence.entity.RoleEntity
import ru.voicestream.persistence.entity.RolePermissionEntity
import java.util.UUID

@QuarkusTest
class ChannelRolesGraphQLTest {
    @Inject
    lateinit var entityManager: EntityManager

    @Inject
    lateinit var userTransaction: UserTransaction

    @Test
    fun `channel role mutations manage custom roles and permissions`() {
        val owner = registerUser("role_owner")
        val viewer = registerUser("role_viewer")
        val fixture = createFixture(
            ownerUserId = UUID.fromString(owner.userId),
            viewerUserId = UUID.fromString(viewer.userId),
        )

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${viewer.accessToken}")
            .body(
                """
                {
                  "query": "query { channelRoles(channelId: \"${fixture.channelId}\") { id name kind system position permissions { permissionKey effect } } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.channelRoles.size()", equalTo(2))
            .body("data.channelRoles[0].kind", equalTo("USER"))

        val createdRoleResponse = given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${owner.accessToken}")
            .body(
                """
                {
                  "query": "mutation { createChannelRole(input: { channelId: \"${fixture.channelId}\", name: \"Officer\", colorHex: \"#445566\", position: 25 }) { id channelId name kind colorHex position system permissions { permissionKey effect } } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.createChannelRole.channelId", equalTo(fixture.channelId.toString()))
            .body("data.createChannelRole.kind", equalTo("CUSTOM"))
            .body("data.createChannelRole.system", equalTo(false))
            .body("data.createChannelRole.permissions.size()", equalTo(0))
            .extract()
            .body()
            .asString()

        val createdRoleId = JsonPath.from(createdRoleResponse).getString("data.createChannelRole.id")

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${owner.accessToken}")
            .body(
                """
                {
                  "query": "mutation { setRolePermission(input: { roleId: \"$createdRoleId\", permissionKey: \"message.read\", effect: ALLOW }) { id permissions { permissionKey effect } } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.setRolePermission.id", equalTo(createdRoleId))
            .body("data.setRolePermission.permissions.size()", equalTo(1))
            .body("data.setRolePermission.permissions[0].permissionKey", equalTo("message.read"))
            .body("data.setRolePermission.permissions[0].effect", equalTo("ALLOW"))

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${owner.accessToken}")
            .body(
                """
                {
                  "query": "mutation { updateChannelRole(input: { roleId: \"$createdRoleId\", name: \"Officer+\", colorHex: \"#112233\", position: 30 }) { id name colorHex position permissions { permissionKey effect } } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.updateChannelRole.id", equalTo(createdRoleId))
            .body("data.updateChannelRole.name", equalTo("Officer+"))
            .body("data.updateChannelRole.colorHex", equalTo("#112233"))
            .body("data.updateChannelRole.position", equalTo(30))
            .body("data.updateChannelRole.permissions.size()", equalTo(1))

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${owner.accessToken}")
            .body(
                """
                {
                  "query": "mutation { setRolePermission(input: { roleId: \"$createdRoleId\", permissionKey: \"message.read\" }) { id permissions { permissionKey effect } } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.setRolePermission.id", equalTo(createdRoleId))
            .body("data.setRolePermission.permissions.size()", equalTo(0))

        val rolesAfterUpdate = given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${owner.accessToken}")
            .body(
                """
                {
                  "query": "query { channelRoles(channelId: \"${fixture.channelId}\") { id name kind colorHex position permissions { permissionKey effect } } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.channelRoles.size()", equalTo(3))
            .extract()
            .body()
            .asString()

        val createdRole = JsonPath.from(rolesAfterUpdate)
            .getList<Map<String, Any?>>("data.channelRoles")
            .first { role -> role["id"] == createdRoleId }
        assertEquals("Officer+", createdRole["name"])
        assertEquals("#112233", createdRole["colorHex"])
        assertEquals(30, createdRole["position"])
        @Suppress("UNCHECKED_CAST")
        assertTrue((createdRole["permissions"] as List<Map<String, Any?>>).isEmpty())

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${owner.accessToken}")
            .body(
                """
                {
                  "query": "mutation { deleteChannelRole(roleId: \"$createdRoleId\") }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.deleteChannelRole", equalTo(true))

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${owner.accessToken}")
            .body(
                """
                {
                  "query": "query { channelRoles(channelId: \"${fixture.channelId}\") { id } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.channelRoles.size()", equalTo(2))
    }

    @Test
    fun `channel role mutations require role manage`() {
        val owner = registerUser("role_owner_perm")
        val viewer = registerUser("role_viewer_perm")
        val fixture = createFixture(
            ownerUserId = UUID.fromString(owner.userId),
            viewerUserId = UUID.fromString(viewer.userId),
        )

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${viewer.accessToken}")
            .body(
                """
                {
                  "query": "mutation { createChannelRole(input: { channelId: \"${fixture.channelId}\", name: \"Denied\", colorHex: \"#111111\", position: 1 }) { id } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("data.createChannelRole", equalTo(null))
            .body("errors", notNullValue())
    }

    private fun registerUser(prefix: String): RegisteredUser {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
        val response = given()
            .contentType("application/json")
            .body(
                """
                {
                  "username": "${prefix}_$suffix",
                  "displayName": "${prefix} user",
                  "email": "${prefix}_$suffix@example.com",
                  "password": "correct-horse-battery-staple",
                  "deviceName": "JUnit"
                }
                """.trimIndent(),
            )
            .post("/api/auth/register")
            .then()
            .statusCode(200)
            .extract()
            .body()
            .asString()

        val json = JsonPath.from(response)
        return RegisteredUser(
            userId = json.getString("user.id"),
            accessToken = json.getString("accessToken"),
        )
    }

    private fun createFixture(ownerUserId: UUID, viewerUserId: UUID): Fixture {
        val channelId = UUID.randomUUID()
        val ownerRoleId = UUID.randomUUID()
        val userRoleId = UUID.randomUUID()
        val ownerMemberId = UUID.randomUUID()
        val viewerMemberId = UUID.randomUUID()
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)

        userTransaction.begin()
        try {
            entityManager.persist(
                ChannelEntity().apply {
                    id = channelId
                    this.ownerUserId = ownerUserId
                    name = "roles-$suffix"
                    type = ChannelType.TEXT
                    privateChannel = true
                },
            )

            entityManager.persist(role(channelId, ownerRoleId, "Owner", RoleKind.OWNER, true, 100))
            entityManager.persist(role(channelId, userRoleId, "User", RoleKind.USER, true, 0))

            persistPermissions(
                channelId = channelId,
                roleId = ownerRoleId,
                permissions = mapOf(
                    PERMISSION_CHANNEL_VIEW to PermissionEffect.ALLOW,
                    PERMISSION_ROLE_MANAGE to PermissionEffect.ALLOW,
                    PERMISSION_MEMBER_MANAGE to PermissionEffect.ALLOW,
                ),
            )
            persistPermissions(
                channelId = channelId,
                roleId = userRoleId,
                permissions = mapOf(
                    PERMISSION_CHANNEL_VIEW to PermissionEffect.ALLOW,
                ),
            )

            entityManager.persist(channelMember(channelId, ownerMemberId, ownerUserId))
            entityManager.persist(channelMember(channelId, viewerMemberId, viewerUserId))
            entityManager.persist(memberRole(channelId, ownerMemberId, ownerRoleId))
            entityManager.persist(memberRole(channelId, viewerMemberId, userRoleId))

            userTransaction.commit()
        } catch (exception: Throwable) {
            userTransaction.rollback()
            throw exception
        }

        return Fixture(channelId = channelId)
    }

    private fun role(
        channelId: UUID,
        roleId: UUID,
        name: String,
        kind: RoleKind,
        system: Boolean,
        position: Int,
    ): RoleEntity =
        RoleEntity().apply {
            id = roleId
            this.channelId = channelId
            this.name = name
            this.kind = kind
            this.system = system
            this.position = position
        }

    private fun channelMember(channelId: UUID, memberId: UUID, userId: UUID): ChannelMemberEntity =
        ChannelMemberEntity().apply {
            id = memberId
            this.channelId = channelId
            this.userId = userId
        }

    private fun memberRole(channelId: UUID, channelMemberId: UUID, roleId: UUID): ChannelMemberRoleEntity =
        ChannelMemberRoleEntity().apply {
            this.channelId = channelId
            this.channelMemberId = channelMemberId
            this.roleId = roleId
        }

    private fun persistPermissions(
        channelId: UUID,
        roleId: UUID,
        permissions: Map<String, PermissionEffect>,
    ) {
        permissions.forEach { (permissionKey, effect) ->
            entityManager.persist(
                RolePermissionEntity().apply {
                    this.channelId = channelId
                    this.roleId = roleId
                    this.permissionKey = permissionKey
                    this.effect = effect
                },
            )
        }
    }

    private data class RegisteredUser(
        val userId: String,
        val accessToken: String,
    )

    private data class Fixture(
        val channelId: UUID,
    )

    companion object {
        private const val PERMISSION_CHANNEL_VIEW = "channel.view"
        private const val PERMISSION_MEMBER_MANAGE = "member.manage"
        private const val PERMISSION_ROLE_MANAGE = "role.manage"
    }
}
