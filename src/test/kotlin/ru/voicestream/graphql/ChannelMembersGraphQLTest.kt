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
class ChannelMembersGraphQLTest {
    @Inject
    lateinit var entityManager: EntityManager

    @Inject
    lateinit var userTransaction: UserTransaction

    @Test
    fun `channel member mutations manage active members and roles`() {
        val manager = registerUser("manager")
        val viewer = registerUser("viewer")
        val target = registerUser("target")
        val fixture = createFixture(
            ownerUserId = UUID.fromString(manager.userId),
            viewerUserId = UUID.fromString(viewer.userId),
        )

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${manager.accessToken}")
            .body(
                """
                {
                  "query": "query { channelMembers(channelId: \"${fixture.channelId}\") { id channelId user { id username displayName avatarMediaKey } displayName joinedAt roles { id name kind } } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.channelMembers.size()", equalTo(2))

        val addedResponse = given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${manager.accessToken}")
            .body(
                """
                {
                  "query": "mutation { addChannelMember(channelId: \"${fixture.channelId}\", userId: \"${target.userId}\") { id channelId user { id username displayName } roles { name kind } } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.addChannelMember.channelId", equalTo(fixture.channelId.toString()))
            .body("data.addChannelMember.user.id", equalTo(target.userId))
            .body("data.addChannelMember.roles.size()", equalTo(1))
            .body("data.addChannelMember.roles[0].kind", equalTo("USER"))
            .extract()
            .body()
            .asString()

        val addedMemberId = JsonPath.from(addedResponse).getString("data.addChannelMember.id")

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${manager.accessToken}")
            .body(
                """
                {
                  "query": "mutation { assignChannelRole(channelMemberId: \"$addedMemberId\", roleId: \"${fixture.customRoleId}\") { id user { id } roles { name kind } } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.assignChannelRole.id", equalTo(addedMemberId))
            .body("data.assignChannelRole.user.id", equalTo(target.userId))
            .body("data.assignChannelRole.roles.size()", equalTo(2))

        val membersAfterAssign = given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${manager.accessToken}")
            .body(
                """
                {
                  "query": "query { channelMembers(channelId: \"${fixture.channelId}\") { id user { id } roles { name kind } } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.channelMembers.size()", equalTo(3))
            .extract()
            .body()
            .asString()

        val targetMember = JsonPath.from(membersAfterAssign)
            .getList<Map<String, Any?>>("data.channelMembers")
            .first { member -> ((member["user"] as Map<*, *>)["id"] == target.userId) }
        @Suppress("UNCHECKED_CAST")
        val targetRoles = targetMember["roles"] as List<Map<String, Any?>>
        assertEquals(2, targetRoles.size)
        assertTrue(targetRoles.any { it["kind"] == "USER" })
        assertTrue(targetRoles.any { it["kind"] == "CUSTOM" })

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${manager.accessToken}")
            .body(
                """
                {
                  "query": "mutation { removeChannelMember(channelId: \"${fixture.channelId}\", userId: \"${target.userId}\") }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.removeChannelMember", equalTo(true))

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${manager.accessToken}")
            .body(
                """
                {
                  "query": "query { channelMembers(channelId: \"${fixture.channelId}\") { user { id } } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.channelMembers.size()", equalTo(2))
        }

    @Test
    fun `channel member mutations require manage permissions`() {
        val manager = registerUser("manager_perm")
        val viewer = registerUser("viewer_perm")
        val target = registerUser("target_perm")
        val fixture = createFixture(
            ownerUserId = UUID.fromString(manager.userId),
            viewerUserId = UUID.fromString(viewer.userId),
        )

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${viewer.accessToken}")
            .body(
                """
                {
                  "query": "query { channelMembers(channelId: \"${fixture.channelId}\") { id user { id } } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .body("data.channelMembers.size()", equalTo(2))

        given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${viewer.accessToken}")
            .body(
                """
                {
                  "query": "mutation { addChannelMember(channelId: \"${fixture.channelId}\", userId: \"${target.userId}\") { id } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("data.addChannelMember", equalTo(null))
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
        val customRoleId = UUID.randomUUID()
        val ownerMemberId = UUID.randomUUID()
        val viewerMemberId = UUID.randomUUID()
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)

        userTransaction.begin()
        try {
            entityManager.persist(
                ChannelEntity().apply {
                    id = channelId
                    this.ownerUserId = ownerUserId
                    this.name = "members-$suffix"
                    this.type = ChannelType.TEXT
                    this.privateChannel = true
                },
            )

            entityManager.persist(role(channelId, ownerRoleId, "Owner", RoleKind.OWNER, true, 100))
            entityManager.persist(role(channelId, userRoleId, "User", RoleKind.USER, true, 0))
            entityManager.persist(role(channelId, customRoleId, "Officer", RoleKind.CUSTOM, false, 50))

            persistPermissions(
                channelId = channelId,
                roleId = ownerRoleId,
                permissions = mapOf(
                    PERMISSION_CHANNEL_VIEW to PermissionEffect.ALLOW,
                    PERMISSION_MEMBER_MANAGE to PermissionEffect.ALLOW,
                    PERMISSION_ROLE_MANAGE to PermissionEffect.ALLOW,
                    PERMISSION_MESSAGE_SEND to PermissionEffect.ALLOW,
                ),
            )
            persistPermissions(
                channelId = channelId,
                roleId = userRoleId,
                permissions = mapOf(
                    PERMISSION_CHANNEL_VIEW to PermissionEffect.ALLOW,
                ),
            )
            persistPermissions(
                channelId = channelId,
                roleId = customRoleId,
                permissions = mapOf(
                    PERMISSION_CHANNEL_VIEW to PermissionEffect.ALLOW,
                    PERMISSION_MESSAGE_SEND to PermissionEffect.ALLOW,
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

        return Fixture(
            channelId = channelId,
            customRoleId = customRoleId,
        )
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
        val customRoleId: UUID,
    )

    companion object {
        private const val PERMISSION_CHANNEL_VIEW = "channel.view"
        private const val PERMISSION_MEMBER_MANAGE = "member.manage"
        private const val PERMISSION_ROLE_MANAGE = "role.manage"
        private const val PERMISSION_MESSAGE_SEND = "message.send"
    }
}
