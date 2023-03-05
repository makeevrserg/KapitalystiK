package ru.astrainteractive.kapitalystic.exposed.api

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import ru.astrainteractive.kapitalystic.api.DBException
import ru.astrainteractive.kapitalystic.api.KapitalystiKDBApi
import ru.astrainteractive.kapitalystic.dto.*
import ru.astrainteractive.kapitalystic.exposed.api.datasource.DBCommon
import ru.astrainteractive.kapitalystic.exposed.api.enitites.invitation.InvitationDAO
import ru.astrainteractive.kapitalystic.exposed.api.enitites.invitation.InvitationTable
import ru.astrainteractive.kapitalystic.exposed.api.enitites.member.MemberDAO
import ru.astrainteractive.kapitalystic.exposed.api.enitites.member.MemberTable
import ru.astrainteractive.kapitalystic.exposed.api.enitites.org.OrgDAO
import ru.astrainteractive.kapitalystic.exposed.api.enitites.org.OrgTable
import ru.astrainteractive.kapitalystic.exposed.api.enitites.warps.WarpsDAO
import ru.astrainteractive.kapitalystic.exposed.api.enitites.warps.WarpsTable
import ru.astrainteractive.kapitalystic.exposed.api.map.MemberMapper
import ru.astrainteractive.kapitalystic.exposed.api.map.MemberMapperImpl
import ru.astrainteractive.kapitalystic.exposed.api.map.OrgMapper
import ru.astrainteractive.kapitalystic.exposed.api.map.OrgMapperImpl
import ru.astrainteractive.kapitalystic.exposed.api.map.warp.WarpMapper
import ru.astrainteractive.kapitalystic.exposed.api.map.warp.WarpMapperImpl

internal class KapitalystiKDBApiImpl(
    private val warpMapper: WarpMapper = WarpMapperImpl(),
    private val memberMapper: MemberMapper = MemberMapperImpl,
    private val orgMapper: OrgMapper = OrgMapperImpl(
        warpMapper = warpMapper,
        memberMapper = memberMapper,
    ),
    private val dbCommon: DBCommon
) : KapitalystiKDBApi {

    override suspend fun create(
        tag: String,
        name: String,
        executorDTO: UserDTO
    ): Result<OrganizationDTO> = kotlin.runCatching {
        if (dbCommon.isMember(executorDTO)) throw DBException.AlreadyInOrganization
        transaction {
            OrgDAO.new org@{
                val orgID = this.id
                this.tag = tag
                this.name = name
                this.description = ""
                this.status = ""
                this.leader = MemberDAO.new {
                    this.minecraftUUID = executorDTO.minecraftUUID.toString()
                    this.minecraftName = executorDTO.minecraftName
                    this.orgID = orgID
                }
            }
        }.let(orgMapper::toDTO)
    }

    override suspend fun setStatus(
        status: String,
        executorDTO: UserDTO
    ): Result<*> = kotlin.runCatching {
        val org = dbCommon.fetchOrg(executorDTO)
        org.status = status
    }

    override suspend fun setDescription(
        description: String,
        executorDTO: UserDTO
    ): Result<*> = kotlin.runCatching {
        val org = dbCommon.fetchOrg(executorDTO)
        org.description = description
    }

    override suspend fun setWarpPublic(
        isPublic: Boolean,
        warpTAG: String,
        executorDTO: UserDTO
    ): Result<*> = kotlin.runCatching {
        if (!dbCommon.isOwner(executorDTO)) throw DBException.NotOrganizationOwner
        val member = dbCommon.fetchMember(executorDTO)
        val warp = WarpsTable.tag.eq(warpTAG).and(WarpsTable.orgID.eq(member.orgID)).let {
            WarpsDAO.find(it)
        }.firstOrNull() ?: throw DBException.UnexpectedException
        warp.isPrivate = !isPublic
    }

    override suspend fun disband(
        executorDTO: UserDTO
    ): Result<*> = kotlin.runCatching {
        if (!dbCommon.isOwner(executorDTO)) throw DBException.NotOrganizationOwner
        transaction {
            val org = runBlocking { dbCommon.fetchOrg(executorDTO) }
            MemberTable.deleteWhere {
                MemberTable.orgID.eq(org.id)
            }
            WarpsTable.deleteWhere {
                WarpsTable.orgID.eq(org.id)
            }
            InvitationTable.deleteWhere {
                InvitationTable.orgID.eq(org.id)
            }
            org.delete()
        }
    }

    override suspend fun rename(
        newName: String,
        executorDTO: UserDTO
    ): Result<*> = kotlin.runCatching {
        if (!dbCommon.isOwner(executorDTO)) throw DBException.NotOrganizationOwner
        val org = dbCommon.fetchOrg(executorDTO)
        org.name = newName
    }

    override suspend fun invite(
        userDTO: UserDTO,
        executorDTO: UserDTO
    ): Result<*> = kotlin.runCatching {
        if (!dbCommon.isOwner(userDTO)) throw DBException.NotOrganizationOwner
        val member = dbCommon.fetchMember(executorDTO)
        val orgID = member.orgID
        if (dbCommon.isUserInvited(userDTO, orgID.value)) throw DBException.AlreadyInvited
        InvitationDAO.new {
            this.minecraftName = userDTO.minecraftName
            this.minecraftUUID = userDTO.minecraftUUID.toString()
            this.orgID = orgID
        }
    }

    override suspend fun acceptInvitation(
        executorDTO: UserDTO,
        orgTag: String
    ): Result<MemberDTO> = kotlin.runCatching {
        if (dbCommon.isMember(executorDTO)) throw DBException.AlreadyInOrganization
        val org = dbCommon.fetchOrg(orgTag)
        MemberDAO.new {
            this.minecraftName = executorDTO.minecraftName
            this.minecraftUUID = executorDTO.minecraftUUID.toString()
            this.orgID = org.id
        }.let(memberMapper::toDTO)
    }

    override suspend fun kickMember(
        userDTO: UserDTO,
        executorDTO: UserDTO
    ): Result<*> = kotlin.runCatching {
        if (!dbCommon.isOwner(executorDTO)) throw DBException.NotOrganizationOwner
        if (dbCommon.isMember(userDTO)) throw DBException.AlreadyInOrganization
        dbCommon.fetchMember(userDTO).delete()
    }

    override suspend fun transferOwnership(
        userDTO: UserDTO,
        executorDTO: UserDTO
    ): Result<*> = kotlin.runCatching {
        if (!dbCommon.isOwner(executorDTO)) throw DBException.NotOrganizationOwner
        if (dbCommon.isMember(userDTO)) throw DBException.AlreadyInOrganization
        val ownerDAO = dbCommon.fetchMember(executorDTO)
        val newOwnerDao = dbCommon.fetchMember(userDTO)
        val org = dbCommon.fetchOrg(ownerDAO)
        org.leader = newOwnerDao
    }

    override suspend fun fetchAllOrganizations(): Result<List<OrganizationDTO>> = kotlin.runCatching {
        OrgDAO.all().map(orgMapper::toDTO)
    }

    override suspend fun fetchOrganization(
        id: Long
    ): Result<OrganizationDTO> = kotlin.runCatching {
        val org = OrgDAO.findById(id)?.let(orgMapper::toDTO)
        org ?: throw DBException.UnexpectedException
    }

    override suspend fun fetchOrganization(
        tag: String
    ): Result<OrganizationDTO> = kotlin.runCatching {
        val org = OrgDAO.find(OrgTable.tag.eq(tag)).firstOrNull()
        org?.let(orgMapper::toDTO) ?: throw DBException.UnexpectedException
    }

    override suspend fun fetchUserOrganization(
        executorDTO: UserDTO
    ): Result<OrganizationDTO> = kotlin.runCatching {
        dbCommon.fetchOrg(executorDTO).let(orgMapper::toDTO)
    }

    override suspend fun setWarp(
        locationDTO: LocationDTO,
        executorDTO: UserDTO,
        tag: String
    ): Result<WarpDTO> = kotlin.runCatching {
        if (!dbCommon.isOwner(executorDTO)) throw DBException.NotOrganizationOwner
        val member = dbCommon.fetchMember(executorDTO)
        WarpsDAO.new {
            this.orgID = member.orgID
            this.tag = tag
            this.x = locationDTO.x
            this.y = locationDTO.y
            this.z = locationDTO.z
            this.worldName = locationDTO.world
            this.isPrivate = true
        }.let(warpMapper::toDTO)
    }
}
