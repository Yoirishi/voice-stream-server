package ru.voicestream.graphql

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import io.restassured.path.json.JsonPath
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.UserTransaction
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.voicestream.domain.ChannelType
import ru.voicestream.domain.PermissionEffect
import ru.voicestream.domain.RoleKind
import ru.voicestream.persistence.entity.ChannelEntity
import ru.voicestream.persistence.entity.ChannelGroupEntity
import ru.voicestream.persistence.entity.ChannelMemberEntity
import ru.voicestream.persistence.entity.ChannelMemberRoleEntity
import ru.voicestream.persistence.entity.RoleEntity
import ru.voicestream.persistence.entity.RolePermissionEntity
import java.util.UUID

@QuarkusTest
class MyChannelsGraphQLTest {
    @Inject
    lateinit var entityManager: EntityManager

    @Inject
    lateinit var userTransaction: UserTransaction

    @Test
    fun `myChannels filters by membership and view permission and returns role and permissions`() {
        val registeredUser = registerUser()
        val fixture = createFixture(UUID.fromString(registeredUser.userId))

        val response = given()
            .contentType("application/json")
            .header("Authorization", "Bearer ${registeredUser.accessToken}")
            .body(
                """
                {
                  "query": "query { myChannels { groups { id name type position channels { id name type topic position privateChannel voiceUserLimit voiceBitrate role { id channelId name kind colorHex position system } permissions { canView canSendMessage canConnectVoice canManageChannel canShareScreen } } } ungroupedChannels { id name type topic position privateChannel voiceUserLimit voiceBitrate role { id channelId name kind colorHex position system } permissions { canView canSendMessage canConnectVoice canManageChannel canShareScreen } } } }"
                }
                """.trimIndent(),
            )
            .post("/graphql")
            .then()
            .statusCode(200)
            .body("errors", equalTo(null))
            .extract()
            .body()
            .asString()

        val json = JsonPath.from(response)
        val groups = json.getList<Map<String, Any?>>("data.myChannels.groups")
        val groupedChannels = groups.flatMap { channels(it) }
        val groupedChannelIds = groupedChannels.map { it["id"] }

        assertEquals(2, groups.size)
        assertTrue(groupedChannelIds.contains(fixture.textChannelId.toString()))
        assertTrue(groupedChannelIds.contains(fixture.voiceChannelId.toString()))
        assertFalse(groupedChannelIds.contains(fixture.hiddenChannelId.toString()))
        assertFalse(groupedChannelIds.contains(fixture.notMemberChannelId.toString()))

        val textChannel = channelById(groupedChannels, fixture.textChannelId)
        assertEquals("USER", role(textChannel)["kind"])
        assertEquals(true, permissions(textChannel)["canView"])
        assertEquals(true, permissions(textChannel)["canSendMessage"])
        assertEquals(false, permissions(textChannel)["canConnectVoice"])
        assertEquals(false, permissions(textChannel)["canManageChannel"])
        assertEquals(false, permissions(textChannel)["canShareScreen"])

        val voiceChannel = channelById(groupedChannels, fixture.voiceChannelId)
        assertEquals("CUSTOM", role(voiceChannel)["kind"])
        assertEquals(true, permissions(voiceChannel)["canView"])
        assertEquals(false, permissions(voiceChannel)["canSendMessage"])
        assertEquals(true, permissions(voiceChannel)["canConnectVoice"])
        assertEquals(false, permissions(voiceChannel)["canManageChannel"])
        assertEquals(true, permissions(voiceChannel)["canShareScreen"])

        val ungroupedChannels = json.getList<Map<String, Any?>>("data.myChannels.ungroupedChannels")
        assertEquals(listOf(fixture.ungroupedChannelId.toString()), ungroupedChannels.map { it["id"] })
        assertEquals(true, permissions(ungroupedChannels.first())["canManageChannel"])
    }

    private fun registerUser(): RegisteredUser {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        val response = given()
            .contentType("application/json")
            .body(
                """
                {
                  "username": "my_channels_$suffix",
                  "displayName": "My Channels User",
                  "email": "my_channels_$suffix@example.com",
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

    private fun createFixture(userId: UUID): Fixture {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        val textGroupId = UUID.randomUUID()
        val voiceGroupId = UUID.randomUUID()
        val textChannelId = UUID.randomUUID()
        val voiceChannelId = UUID.randomUUID()
        val hiddenChannelId = UUID.randomUUID()
        val ungroupedChannelId = UUID.randomUUID()
        val notMemberChannelId = UUID.randomUUID()

        userTransaction.begin()
        try {
            entityManager.persist(channelGroup(textGroupId, "Text $suffix", ChannelType.TEXT, 1))
            entityManager.persist(channelGroup(voiceGroupId, "Voice $suffix", ChannelType.VOICE, 2))

            entityManager.persist(channel(textChannelId, userId, textGroupId, "text-$suffix", ChannelType.TEXT, false, 1))
            entityManager.persist(channel(voiceChannelId, userId, voiceGroupId, "voice-$suffix", ChannelType.VOICE, true, 1))
            entityManager.persist(channel(hiddenChannelId, userId, textGroupId, "hidden-$suffix", ChannelType.TEXT, true, 2))
            entityManager.persist(channel(ungroupedChannelId, userId, null, "ungrouped-$suffix", ChannelType.TEXT, false, 3))
            entityManager.persist(channel(notMemberChannelId, userId, textGroupId, "not-member-$suffix", ChannelType.TEXT, false, 4))

            assignRole(
                channelId = textChannelId,
                userId = userId,
                roleName = "User $suffix",
                roleKind = RoleKind.USER,
                position = 0,
                permissions = mapOf(
                    PERMISSION_CHANNEL_VIEW to PermissionEffect.ALLOW,
                    PERMISSION_MESSAGE_SEND to PermissionEffect.ALLOW,
                ),
            )
            assignRole(
                channelId = voiceChannelId,
                userId = userId,
                roleName = "Streamer $suffix",
                roleKind = RoleKind.CUSTOM,
                position = 10,
                permissions = mapOf(
                    PERMISSION_CHANNEL_VIEW to PermissionEffect.ALLOW,
                    PERMISSION_VOICE_CONNECT to PermissionEffect.ALLOW,
                    PERMISSION_SCREEN_SHARE to PermissionEffect.ALLOW,
                ),
            )
            assignRole(
                channelId = hiddenChannelId,
                userId = userId,
                roleName = "Hidden $suffix",
                roleKind = RoleKind.USER,
                position = 0,
                permissions = mapOf(
                    PERMISSION_CHANNEL_VIEW to PermissionEffect.DENY,
                    PERMISSION_MESSAGE_SEND to PermissionEffect.ALLOW,
                ),
            )
            assignRole(
                channelId = ungroupedChannelId,
                userId = userId,
                roleName = "Manager $suffix",
                roleKind = RoleKind.CUSTOM,
                position = 20,
                permissions = mapOf(
                    PERMISSION_CHANNEL_VIEW to PermissionEffect.ALLOW,
                    PERMISSION_MESSAGE_SEND to PermissionEffect.ALLOW,
                    PERMISSION_CHANNEL_MANAGE to PermissionEffect.ALLOW,
                ),
            )

            userTransaction.commit()
        } catch (exception: Throwable) {
            userTransaction.rollback()
            throw exception
        }

        return Fixture(
            textChannelId = textChannelId,
            voiceChannelId = voiceChannelId,
            hiddenChannelId = hiddenChannelId,
            ungroupedChannelId = ungroupedChannelId,
            notMemberChannelId = notMemberChannelId,
        )
    }

    private fun channelGroup(id: UUID, name: String, type: ChannelType, position: Int): ChannelGroupEntity =
        ChannelGroupEntity().apply {
            this.id = id
            this.name = name
            this.type = type
            this.position = position
        }

    private fun channel(
        id: UUID,
        ownerUserId: UUID,
        groupId: UUID?,
        name: String,
        type: ChannelType,
        privateChannel: Boolean,
        position: Int,
    ): ChannelEntity =
        ChannelEntity().apply {
            this.id = id
            this.ownerUserId = ownerUserId
            this.groupId = groupId
            this.name = name
            this.type = type
            this.privateChannel = privateChannel
            this.position = position
        }

    private fun assignRole(
        channelId: UUID,
        userId: UUID,
        roleName: String,
        roleKind: RoleKind,
        position: Int,
        permissions: Map<String, PermissionEffect>,
    ) {
        val memberId = UUID.randomUUID()
        val roleId = UUID.randomUUID()

        entityManager.persist(
            ChannelMemberEntity().apply {
                id = memberId
                this.channelId = channelId
                this.userId = userId
            },
        )
        entityManager.persist(
            RoleEntity().apply {
                id = roleId
                this.channelId = channelId
                name = roleName
                kind = roleKind
                system = roleKind != RoleKind.CUSTOM
                this.position = position
            },
        )
        entityManager.persist(
            ChannelMemberRoleEntity().apply {
                this.channelId = channelId
                channelMemberId = memberId
                this.roleId = roleId
            },
        )
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

    @Suppress("UNCHECKED_CAST")
    private fun channels(group: Map<String, Any?>): List<Map<String, Any?>> =
        group["channels"] as List<Map<String, Any?>>

    private fun channelById(channels: List<Map<String, Any?>>, channelId: UUID): Map<String, Any?> =
        channels.first { it["id"] == channelId.toString() }

    @Suppress("UNCHECKED_CAST")
    private fun role(channel: Map<String, Any?>): Map<String, Any?> =
        channel["role"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun permissions(channel: Map<String, Any?>): Map<String, Any?> =
        channel["permissions"] as Map<String, Any?>

    private data class RegisteredUser(
        val userId: String,
        val accessToken: String,
    )

    private data class Fixture(
        val textChannelId: UUID,
        val voiceChannelId: UUID,
        val hiddenChannelId: UUID,
        val ungroupedChannelId: UUID,
        val notMemberChannelId: UUID,
    )

    companion object {
        private const val PERMISSION_CHANNEL_MANAGE = "channel.manage"
        private const val PERMISSION_CHANNEL_VIEW = "channel.view"
        private const val PERMISSION_MESSAGE_SEND = "message.send"
        private const val PERMISSION_VOICE_CONNECT = "voice.connect"
        private const val PERMISSION_SCREEN_SHARE = "screen.share"
    }
}
