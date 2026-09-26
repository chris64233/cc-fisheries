package com.chris64233.cc.fisheries;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 端到端 API 测试：账户开立、转让全流程、卸港冻结—复核、更正、幂等与各类查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void fullTransferReviewAndCorrectionFlow() throws Exception {
        // 开立账户
        mockMvc.perform(post("/api/quota-accounts").contentType(MediaType.APPLICATION_JSON).content("""
                {"season":"API-S1","species":"COD","holder":"A","initialQuantity":100}
                """)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.available").value(100.0));
        mockMvc.perform(post("/api/quota-accounts").contentType(MediaType.APPLICATION_JSON).content("""
                {"season":"API-S1","species":"COD","holder":"B","initialQuantity":10}
                """)).andExpect(status().isCreated());

        // 重复开立冲突
        mockMvc.perform(post("/api/quota-accounts").contentType(MediaType.APPLICATION_JSON).content("""
                {"season":"API-S1","species":"COD","holder":"A","initialQuantity":100}
                """)).andExpect(status().isConflict());

        // 发起转让并冻结
        String transferBody = mockMvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"season":"API-S1","species":"COD","fromHolder":"A","toHolder":"B","quantity":30}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        long transferId = Long.parseLong(transferBody.replaceAll(".*\"id\":(\\d+).*", "$1"));

        // 接受转让
        mockMvc.perform(post("/api/transfers/{id}/accept", transferId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        // 重复接受冲突
        mockMvc.perform(post("/api/transfers/{id}/accept", transferId))
                .andExpect(status().isConflict());

        // 卸港申报：先冻结、进入待复核
        String landing = """
                {"eventId":"API-EVT-1","vessel":"V1","holder":"B","species":"COD","season":"API-S1","weight":12.5}
                """;
        mockMvc.perform(post("/api/landings").contentType(MediaType.APPLICATION_JSON).content(landing))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"));
        // 申报重放幂等
        mockMvc.perform(post("/api/landings").contentType(MediaType.APPLICATION_JSON).content(landing))
                .andExpect(status().isCreated());
        // 同事件号不同内容冲突
        mockMvc.perform(post("/api/landings").contentType(MediaType.APPLICATION_JSON).content("""
                {"eventId":"API-EVT-1","vessel":"V1","holder":"B","species":"COD","season":"API-S1","weight":13}
                """)).andExpect(status().isConflict());

        // 待复核期间：B 冻结 12.5、尚未核销
        String accounts = mockMvc.perform(get("/api/quota-accounts")
                        .param("season", "API-S1").param("species", "COD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andReturn().getResponse().getContentAsString();
        long accountB = Long.parseLong(accounts.replaceAll(
                ".*\\{\"id\":(\\d+),\"season\":\"API-S1\",\"species\":\"COD\",\"holder\":\"B\".*", "$1"));
        mockMvc.perform(get("/api/quota-accounts/{id}", accountB))
                .andExpect(jsonPath("$.available").value(27.5))
                .andExpect(jsonPath("$.frozen").value(12.5))
                .andExpect(jsonPath("$.consumed").value(0));

        // 冻结明细查询
        mockMvc.perform(get("/api/quota-accounts/{id}/holds", accountB).param("activeOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].holdType").value("LANDING"))
                .andExpect(jsonPath("$[0].referenceId").value("API-EVT-1"))
                .andExpect(jsonPath("$[0].status").value("HELD"));

        // 港口复核确认，重复复核事件号幂等
        String review = """
                {"reviewEventId":"API-REV-1","reviewer":"harbor-lee"}
                """;
        mockMvc.perform(post("/api/landings/{eventId}/confirm", "API-EVT-1")
                        .contentType(MediaType.APPLICATION_JSON).content(review))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.confirmedWeight").value(12.5));
        mockMvc.perform(post("/api/landings/{eventId}/confirm", "API-EVT-1")
                        .contentType(MediaType.APPLICATION_JSON).content(review))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // 余额核对：B = 10 + 30 - 12.5 = 27.5 可用，已核销 12.5
        mockMvc.perform(get("/api/quota-accounts/{id}", accountB))
                .andExpect(jsonPath("$.available").value(27.5))
                .andExpect(jsonPath("$.frozen").value(0))
                .andExpect(jsonPath("$.consumed").value(12.5));

        // 台账：入账 + 转入 + 冻结 + 核销
        mockMvc.perform(get("/api/quota-accounts/{id}/ledger", accountB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[0].type").value("GRANT"))
                .andExpect(jsonPath("$[1].type").value("TRANSFER_IN"))
                .andExpect(jsonPath("$[2].type").value("LANDING_FREEZE"))
                .andExpect(jsonPath("$[3].type").value("LANDING_CONSUME"));

        // 台账按单号过滤
        mockMvc.perform(get("/api/quota-accounts/{id}/ledger", accountB)
                        .param("reference", "API-EVT-1"))
                .andExpect(jsonPath("$", hasSize(2)));

        // 称重更正：12.5 → 15（增重 2.5），先冻结
        mockMvc.perform(post("/api/corrections").contentType(MediaType.APPLICATION_JSON).content("""
                {"correctionId":"API-CORR-1","originalEventId":"API-EVT-1","correctedWeight":15}
                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.direction").value("INCREASE"))
                .andExpect(jsonPath("$.delta").value(2.5))
                .andExpect(jsonPath("$.status").value("PENDING"));
        mockMvc.perform(get("/api/quota-accounts/{id}", accountB))
                .andExpect(jsonPath("$.frozen").value(2.5));
        // 确认增重
        mockMvc.perform(post("/api/corrections/{id}/confirm", "API-CORR-1")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"reviewEventId":"API-CORR-REV-1","reviewer":"harbor-lee"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        // 原申报进入 CORRECTED，有效重量 15，原重量仍 12.5
        mockMvc.perform(get("/api/landings/{eventId}", "API-EVT-1"))
                .andExpect(jsonPath("$.status").value("CORRECTED"))
                .andExpect(jsonPath("$.weight").value(12.5))
                .andExpect(jsonPath("$.confirmedWeight").value(15));
        mockMvc.perform(get("/api/quota-accounts/{id}", accountB))
                .andExpect(jsonPath("$.available").value(25.0))
                .andExpect(jsonPath("$.consumed").value(15.0))
                .andExpect(jsonPath("$.frozen").value(0));

        // 减重 15 → 11，归还实际差额 4
        mockMvc.perform(post("/api/corrections").contentType(MediaType.APPLICATION_JSON).content("""
                {"correctionId":"API-CORR-2","originalEventId":"API-EVT-1","correctedWeight":11}
                """)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.direction").value("DECREASE"))
                .andExpect(jsonPath("$.delta").value(4));
        mockMvc.perform(post("/api/corrections/{id}/confirm", "API-CORR-2")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"reviewEventId":"API-CORR-REV-2","reviewer":"harbor-lee"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/quota-accounts/{id}", accountB))
                .andExpect(jsonPath("$.available").value(29.0))
                .andExpect(jsonPath("$.consumed").value(11.0));

        // 版本链：原申报 + 两次更正
        mockMvc.perform(get("/api/landings/{eventId}/chain", "API-EVT-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveWeight").value(11))
                .andExpect(jsonPath("$.entries", hasSize(3)))
                .andExpect(jsonPath("$.entries[0].nodeType").value("LANDING"))
                .andExpect(jsonPath("$.entries[1].refId").value("API-CORR-1"))
                .andExpect(jsonPath("$.entries[2].refId").value("API-CORR-2"));

        // 更正按原申报查询
        mockMvc.perform(get("/api/corrections").param("originalEventId", "API-EVT-1"))
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void rejectReviewReleasesFrozen() throws Exception {
        mockMvc.perform(post("/api/quota-accounts").contentType(MediaType.APPLICATION_JSON).content("""
                {"season":"API-R1","species":"COD","holder":"A","initialQuantity":50}
                """)).andExpect(status().isCreated());
        mockMvc.perform(post("/api/landings").contentType(MediaType.APPLICATION_JSON).content("""
                {"eventId":"API-R1-EVT","vessel":"V1","holder":"A","species":"COD","season":"API-R1","weight":20}
                """)).andExpect(status().isCreated());
        mockMvc.perform(post("/api/landings/{eventId}/reject", "API-R1-EVT")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"reviewEventId":"API-R1-REV","reviewer":"harbor-lee"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        // 释放后可用恢复
        String accounts = mockMvc.perform(get("/api/quota-accounts")
                        .param("season", "API-R1").param("species", "COD"))
                .andReturn().getResponse().getContentAsString();
        long accountA = Long.parseLong(accounts.replaceAll(".*\"id\":(\\d+).*", "$1"));
        mockMvc.perform(get("/api/quota-accounts/{id}", accountA))
                .andExpect(jsonPath("$.available").value(50))
                .andExpect(jsonPath("$.frozen").value(0))
                .andExpect(jsonPath("$.consumed").value(0));
        // 驳回后再确认冲突
        mockMvc.perform(post("/api/landings/{eventId}/confirm", "API-R1-EVT")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"reviewEventId":"API-R1-REV2","reviewer":"harbor-lee"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidRequestsReturnProperErrors() throws Exception {
        // 缺少必填字段
        mockMvc.perform(post("/api/quota-accounts").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        // 账户不存在
        mockMvc.perform(get("/api/quota-accounts/{id}", 999999))
                .andExpect(status().isNotFound());
        // 转让不存在
        mockMvc.perform(post("/api/transfers/{id}/accept", 999999))
                .andExpect(status().isNotFound());
        // 超精度数量
        mockMvc.perform(post("/api/quota-accounts").contentType(MediaType.APPLICATION_JSON).content("""
                {"season":"API-S2","species":"COD","holder":"A","initialQuantity":1.0001}
                """)).andExpect(status().isBadRequest());
        // 复核不存在的申报
        mockMvc.perform(post("/api/landings/{eventId}/confirm", "MISSING")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"reviewEventId":"X","reviewer":"r"}
                                """))
                .andExpect(status().isNotFound());
        // 更正不存在的申报
        mockMvc.perform(post("/api/corrections").contentType(MediaType.APPLICATION_JSON).content("""
                {"correctionId":"X","originalEventId":"MISSING","correctedWeight":1}
                """))
                .andExpect(status().isNotFound());
    }
}
