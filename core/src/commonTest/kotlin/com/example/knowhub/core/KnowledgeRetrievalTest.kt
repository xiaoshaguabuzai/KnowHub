package com.example.knowhub.core

import com.example.knowhub.core.model.Chunk
import com.example.knowhub.core.model.DocumentDto
import com.example.knowhub.core.model.RagConfig
import com.example.knowhub.core.model.TuneSample
import com.example.knowhub.core.model.UserDto
import com.example.knowhub.core.rag.HybridRetriever
import com.example.knowhub.core.security.Acl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KnowledgeRetrievalTest {

    private val policyDoc = DocumentDto(
        docId = "doc_policy",
        name = "员工年假管理规定",
        status = "INDEXED",
        pageCount = 2,
        chunkCount = 1,
    )

    private val salaryDoc = DocumentDto(
        docId = "doc_salary",
        name = "薪酬保密制度",
        status = "INDEXED",
        pageCount = 1,
        chunkCount = 1,
        aclRoles = listOf("hr"),
    )

    private val chunks = listOf(
        Chunk(
            chunkId = "doc_policy-p1-0",
            docId = "doc_policy",
            docName = "员工年假管理规定",
            page = 1,
            section = "年假天数",
            index = 0,
            text = "员工年假为每年 5 天，入职满一年后享受。年假可以跨年度结转，最多结转 5 天。",
        ),
        Chunk(
            chunkId = "doc_salary-p1-0",
            docId = "doc_salary",
            docName = "薪酬保密制度",
            page = 1,
            index = 0,
            text = "薪酬信息属于公司机密，禁止员工之间互相讨论工资与奖金。",
        ),
    )

    private val member = UserDto(
        userId = "u_demo",
        username = "demo",
        displayName = "演示用户",
        roles = listOf("member"),
        tenantId = "default",
    )

    private val hr = UserDto(
        userId = "u_hr",
        username = "hr",
        displayName = "HR",
        roles = listOf("member", "hr"),
        tenantId = "default",
    )

    private val documents = mapOf("doc_policy" to policyDoc, "doc_salary" to salaryDoc)

    @Test
    fun aclBlocksRestrictedDocumentForMember() {
        val retriever = HybridRetriever(
            chunks = chunks,
            documents = documents,
            rag = RagConfig(minScore = 0.0),
        )
        val results = retriever.retrieve("薪酬保密有什么要求", member)
        assertTrue(results.none { it.chunk.docId == "doc_salary" }, "普通成员不应检索到 HR 文档")
    }

    @Test
    fun hrCanRetrieveRestrictedDocument() {
        val retriever = HybridRetriever(
            chunks = chunks,
            documents = documents,
            rag = RagConfig(minScore = 0.0),
        )
        val results = retriever.retrieve("薪酬保密有什么要求", hr)
        assertTrue(results.any { it.chunk.docId == "doc_salary" }, "hr 角色应能检索到受限文档")
    }

    @Test
    fun keywordQueryRanksRelevantChunkFirst() {
        val retriever = HybridRetriever(
            chunks = chunks,
            documents = documents,
            rag = RagConfig(minScore = 0.0),
        )
        val results = retriever.retrieve("年假有几天", member)
        assertTrue(results.isNotEmpty())
        assertEquals("doc_policy", results.first().chunk.docId)
    }

    @Test
    fun positiveTuneSampleBoostsScore() {
        val withoutTune = HybridRetriever(chunks, documents, RagConfig(minScore = 0.0))
        val withTune = HybridRetriever(
            chunks = chunks,
            documents = documents,
            rag = RagConfig(minScore = 0.0),
            tuneSamples = listOf(
                TuneSample(
                    sampleId = "tune_1",
                    query = "年假有几天",
                    chunkId = "doc_salary-p1-0",
                    positive = true,
                    createdBy = "admin",
                )
            ),
        )
        val base = withoutTune.retrieve("年假有几天", hr).first { it.chunk.chunkId == "doc_salary-p1-0" }
        val boosted = withTune.retrieve("年假有几天", hr).first { it.chunk.chunkId == "doc_salary-p1-0" }
        assertTrue(boosted.score > base.score, "正向调教样本应提升该片段得分")
    }

    @Test
    fun patientCanReadOnlyPatientGuides() {
        val patient = UserDto(
            userId = "u_patient",
            username = "patient",
            displayName = "王女士",
            roles = listOf("patient"),
            tenantId = "default",
        )
        val guide = DocumentDto(
            docId = "doc_guide_appointment",
            name = "门诊预约与挂号指南",
            status = "INDEXED",
            audience = "all",
        )
        val internal = DocumentDto(
            docId = "doc_core_rules",
            name = "医疗质量安全核心制度",
            status = "INDEXED",
        )
        assertTrue(Acl.canRead(guide, patient))
        assertTrue(!Acl.canRead(internal, patient))
        assertTrue(Acl.canRead(internal, member))
        assertTrue(!Acl.canRunFlow("staff", patient))
        assertTrue(Acl.canRunFlow("all", patient))
    }

    @Test
    fun aclRespectsTenantBoundary() {
        val otherTenant = member.copy(tenantId = "tenant_b")
        assertTrue(!Acl.canRead(policyDoc, otherTenant))
        assertTrue(Acl.canRead(policyDoc, member))
    }
}
