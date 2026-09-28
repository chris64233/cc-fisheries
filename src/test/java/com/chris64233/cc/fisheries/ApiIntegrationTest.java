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

        // 发起转让并冻结（外部请求号幂等）
        String transferBody = mockMvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"API-REQ-T1","season":"API-S1","species":"COD","fromHolder":"A","toHolder":"B","quantity":30}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        long transferId = Long.parseLong(transferBody.replaceAll(".*\"id\":(\\d+).*", "$1"));

        // 相同请求号重放：返回原单据、不重复冻结（A 仍冻结 30）
        mockMvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"API-REQ-T1","season":"API-S1","species":"COD","fromHolder":"A","toHolder":"B","quantity":30}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(transferId));
        // 同请求号不同内容冲突
        mockMvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"API-REQ-T1","season":"API-S1","species":"COD","fromHolder":"A","toHolder":"B","quantity":31}
                                """))
                .andExpect(status().isConflict());

        // 接受转让（接受请求号幂等）
        mockMvc.perform(post("/api/transfers/{id}/accept", transferId)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"API-REQ-A1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        // 相同接受请求号重放：幂等返回当前单据（200），不重复划转
        mockMvc.perform(post("/api/transfers/{id}/accept", transferId)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"API-REQ-A1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        // 换一个新请求号再次接受：单据已终结，冲突
        mockMvc.perform(post("/api/transfers/{id}/accept", transferId)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"API-REQ-A2"}
                                """))
                .andExpect(status().isConflict());
        // 已终结单据上的拒绝（任意请求号）同样冲突；跨单据复用请求号的冲突见服务层测试
        mockMvc.perform(post("/api/transfers/{id}/reject", transferId)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"API-REQ-A1"}
                                """))
                .andExpect(status().isConflict());

        // 转让详情：双方台账与前后余额可相互核对
        mockMvc.perform(get("/api/transfers/{id}/detail", transferId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.from.before.available").value(100))
                .andExpect(jsonPath("$.from.before.frozen").value(0))
                .andExpect(jsonPath("$.from.after.available").value(70))
                .andExpect(jsonPath("$.from.after.frozen").value(0))
                .andExpect(jsonPath("$.from.events", hasSize(2)))
                .andExpect(jsonPath("$.from.events[1].type").value("TRANSFER_OUT"))
                .andExpect(jsonPath("$.from.hold.status").value("SETTLED"))
                .andExpect(jsonPath("$.to.before.available").value(10))
                .andExpect(jsonPath("$.to.after.available").value(40))
                .andExpect(jsonPath("$.to.events", hasSize(1)))
                .andExpect(jsonPath("$.to.events[0].type").value("TRANSFER_IN"))
                .andExpect(jsonPath("$.to.hold").doesNotExist());

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
    void cancelReleasesFrozenAndIsIdempotent() throws Exception {
        mockMvc.perform(post("/api/quota-accounts").contentType(MediaType.APPLICATION_JSON).content("""
                {"season":"API-C1","species":"COD","holder":"A","initialQuantity":100}
                """)).andExpect(status().isCreated());
        String body = mockMvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON).content("""
                        {"requestId":"API-C1-REQ","season":"API-C1","species":"COD","fromHolder":"A","toHolder":"B","quantity":40}
                        """))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));

        // 非转让方不能取消
        mockMvc.perform(post("/api/transfers/{id}/cancel", id)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"API-C1-CANCEL","requesterHolder":"B"}
                                """))
                .andExpect(status().isForbidden());

        // 转让方取消，冻结完整释放
        mockMvc.perform(post("/api/transfers/{id}/cancel", id)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"API-C1-CANCEL","requesterHolder":"A"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        // 取消请求号幂等：再次取消返回当前单据、不重复释放
        mockMvc.perform(post("/api/transfers/{id}/cancel", id)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"API-C1-CANCEL","requesterHolder":"A"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        // 取消后不能再接受
        mockMvc.perform(post("/api/transfers/{id}/accept", id)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"API-C1-ACCEPT"}
                                """))
                .andExpect(status().isConflict());

        // 取消详情：from 已回到 100 可用，冻结明细 RELEASED，只释放一次
        mockMvc.perform(get("/api/transfers/{id}/detail", id))
                .andExpect(jsonPath("$.from.after.available").value(100))
                .andExpect(jsonPath("$.from.after.frozen").value(0))
                .andExpect(jsonPath("$.from.hold.status").value("RELEASED"))
                .andExpect(jsonPath("$.from.events", hasSize(2)))
                .andExpect(jsonPath("$.from.events[1].type").value("TRANSFER_RELEASE"));
    }

    @Test
    void transferAvailabilityDistinguishesLandedAndOccupied() throws Exception {
        // A 核准 100；一笔转让冻结 30、一笔卸港待复核冻结 20、一笔已上岸 10
        mockMvc.perform(post("/api/quota-accounts").contentType(MediaType.APPLICATION_JSON).content("""
                {"season":"API-A1","species":"COD","holder":"A","initialQuantity":100}
                """)).andExpect(status().isCreated());
        String body = mockMvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON).content("""
                        {"requestId":"API-A1-T1","season":"API-A1","species":"COD","fromHolder":"A","toHolder":"B","quantity":30}
                        """))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long transferId = Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
        mockMvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON).content("""
                        {"requestId":"API-A1-T2","season":"API-A1","species":"COD","fromHolder":"A","toHolder":"B","quantity":10}
                        """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/landings").contentType(MediaType.APPLICATION_JSON).content("""
                {"eventId":"API-A1-PEND","vessel":"V1","holder":"A","species":"COD","season":"API-A1","weight":20}
                """)).andExpect(status().isCreated());
        mockMvc.perform(post("/api/landings").contentType(MediaType.APPLICATION_JSON).content("""
                {"eventId":"API-A1-DONE","vessel":"V1","holder":"A","species":"COD","season":"API-A1","weight":10}
                """)).andExpect(status().isCreated());
        mockMvc.perform(post("/api/landings/{eventId}/confirm", "API-A1-DONE")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"reviewEventId":"API-A1-DONE-REV","reviewer":"r"}
                                """))
                .andExpect(status().isOk());

        // 可转 30、已上岸 10、被其他申请占用 60（转让 40 + 卸港待复核 20）
        mockMvc.perform(get("/api/quota-accounts/transfer-availability")
                        .param("season", "API-A1").param("species", "COD").param("holder", "A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(30))
                .andExpect(jsonPath("$.transferable").value(30))
                .andExpect(jsonPath("$.landed").value(10))
                .andExpect(jsonPath("$.consumed").value(10))
                .andExpect(jsonPath("$.occupiedByOthers").value(60))
                .andExpect(jsonPath("$.transferHeld").value(40))
                .andExpect(jsonPath("$.landingHeld").value(20))
                .andExpect(jsonPath("$.correctionHeld").value(0))
                .andExpect(jsonPath("$.frozen").value(60));

        // 查看第一笔转让时排除自身冻结：可转恢复为 60，其他转让占用只剩 10
        mockMvc.perform(get("/api/quota-accounts/transfer-availability")
                        .param("season", "API-A1").param("species", "COD").param("holder", "A")
                        .param("excludeTransferId", String.valueOf(transferId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferable").value(60))
                .andExpect(jsonPath("$.occupiedByOthers").value(30))
                .andExpect(jsonPath("$.transferHeld").value(10));
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
        mockMvc.perform(post("/api/transfers/{id}/accept", 999999)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"requestId":"NEVER"}
                                """))
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
