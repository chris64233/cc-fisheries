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
 * 端到端 API 测试：账户开立、转让全流程、卸港申报-复核-更正、
 * 幂等重放、版本链、冻结明细与台账查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void fullTransferLandingReviewAndCorrectionFlow() throws Exception {
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

        // 卸港申报：进入待复核并冻结配额
        String landing = """
                {"eventId":"API-EVT-1","vessel":"V1","holder":"B","species":"COD","season":"API-S1","weight":12.5}
                """;
        mockMvc.perform(post("/api/landings").contentType(MediaType.APPLICATION_JSON).content(landing))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"));
        // 幂等重放
        mockMvc.perform(post("/api/landings").contentType(MediaType.APPLICATION_JSON).content(landing))
                .andExpect(status().isCreated());
        // 同事件号不同内容冲突
        mockMvc.perform(post("/api/landings").contentType(MediaType.APPLICATION_JSON).content("""
                {"eventId":"API-EVT-1","vessel":"V1","holder":"B","species":"COD","season":"API-S1","weight":13}
                """)).andExpect(status().isConflict());

        // 账户 B：10 + 30 - 12.5(冻结) = 27.5 可用，12.5 冻结
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
                .andExpect(jsonPath("$.consumed").value(0.0));

        // 冻结明细：一笔待复核卸港
        mockMvc.perform(get("/api/quota-accounts/{id}/freezes", accountB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].type").value("LANDING"))
                .andExpect(jsonPath("$[0].ref").value("API-EVT-1"))
                .andExpect(jsonPath("$[0].quantity").value(12.5));

        // 复核确认重量（与申报一致）→ 正式核销；复核事件号幂等
        String review = """
                {"reviewEventId":"API-REV-1","reviewer":"PORT-1","confirmedWeight":12.5}
                """;
        mockMvc.perform(post("/api/landings/{eventId}/reviews/confirm", "API-EVT-1")
                        .contentType(MediaType.APPLICATION_JSON).content(review))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("CONFIRMED"));
        mockMvc.perform(post("/api/landings/{eventId}/reviews/confirm", "API-EVT-1")
                        .contentType(MediaType.APPLICATION_JSON).content(review))
                .andExpect(status().isOk());
        // 重复复核（不同事件号）冲突
        mockMvc.perform(post("/api/landings/{eventId}/reviews/confirm", "API-EVT-1")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"reviewEventId":"API-REV-2","reviewer":"PORT-1","confirmedWeight":12.5}
                        """)).andExpect(status().isConflict());

        mockMvc.perform(get("/api/quota-accounts/{id}", accountB))
                .andExpect(jsonPath("$.available").value(27.5))
                .andExpect(jsonPath("$.frozen").value(0.0))
                .andExpect(jsonPath("$.consumed").value(12.5));
        // 冻结明细清空
        mockMvc.perform(get("/api/quota-accounts/{id}/freezes", accountB))
                .andExpect(jsonPath("$", hasSize(0)));

        // 称重更正：12.5 → 10，归还差额 2.5；更正号幂等
        String correction = """
                {"correctionNo":"API-COR-1","correctedWeight":10}
                """;
        mockMvc.perform(post("/api/landings/{eventId}/corrections", "API-EVT-1")
                        .contentType(MediaType.APPLICATION_JSON).content(correction))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.originalWeight").value(12.5));
        mockMvc.perform(post("/api/landings/{eventId}/corrections", "API-EVT-1")
                        .contentType(MediaType.APPLICATION_JSON).content(correction))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/landings/corrections/{no}/confirm", "API-COR-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        // 重复确认幂等
        mockMvc.perform(post("/api/landings/corrections/{no}/confirm", "API-COR-1"))
                .andExpect(status().isOk());

        // B：可用 27.5 + 2.5 = 30，已核销 10
        mockMvc.perform(get("/api/quota-accounts/{id}", accountB))
                .andExpect(jsonPath("$.available").value(30.0))
                .andExpect(jsonPath("$.consumed").value(10.0));

        // 台账：入账 + 转入 + 冻结 + 核销 + 更正归还
        mockMvc.perform(get("/api/quota-accounts/{id}/ledger", accountB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[0].type").value("GRANT"))
                .andExpect(jsonPath("$[1].type").value("TRANSFER_IN"))
                .andExpect(jsonPath("$[2].type").value("LANDING_FREEZE"))
                .andExpect(jsonPath("$[3].type").value("LANDING_SETTLE"))
                .andExpect(jsonPath("$[4].type").value("CORRECTION_RETURN"))
                .andExpect(jsonPath("$[4].quantity").value(2.5));

        // 版本链：原申报重量不覆盖，生效重量 10，更正链一条
        mockMvc.perform(get("/api/landings/{eventId}/versions", "API-EVT-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.landing.weight").value(12.5))
                .andExpect(jsonPath("$.landing.confirmedWeight").value(10.0))
                .andExpect(jsonPath("$.landing.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.review.reviewEventId").value("API-REV-1"))
                .andExpect(jsonPath("$.corrections", hasSize(1)))
                .andExpect(jsonPath("$.corrections[0].correctionNo").value("API-COR-1"))
                .andExpect(jsonPath("$.corrections[0].status").value("CONFIRMED"));

        // 卸港记录查询
        mockMvc.perform(get("/api/landings/{eventId}", "API-EVT-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weight").value(12.5))
                .andExpect(jsonPath("$.confirmedWeight").value(10.0));
    }

    @Test
    void reviewRejectReleasesFreeze() throws Exception {
        mockMvc.perform(post("/api/quota-accounts").contentType(MediaType.APPLICATION_JSON).content("""
                {"season":"API-S3","species":"COD","holder":"A","initialQuantity":50}
                """)).andExpect(status().isCreated());
        mockMvc.perform(post("/api/landings").contentType(MediaType.APPLICATION_JSON).content("""
                {"eventId":"API-EVT-3","vessel":"V1","holder":"A","species":"COD","season":"API-S3","weight":20}
                """)).andExpect(status().isCreated());

        mockMvc.perform(post("/api/landings/{eventId}/reviews/reject", "API-EVT-3")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"reviewEventId":"API-REV-3","reviewer":"PORT-1"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("REJECTED"));

        String accounts = mockMvc.perform(get("/api/quota-accounts")
                        .param("season", "API-S3").param("species", "COD"))
                .andReturn().getResponse().getContentAsString();
        long accountA = Long.parseLong(accounts.replaceAll(".*\"id\":(\\d+).*", "$1"));
        mockMvc.perform(get("/api/quota-accounts/{id}", accountA))
                .andExpect(jsonPath("$.available").value(50.0))
                .andExpect(jsonPath("$.frozen").value(0.0))
                .andExpect(jsonPath("$.consumed").value(0.0));

        // 已拒绝的申报不能更正
        mockMvc.perform(post("/api/landings/{eventId}/corrections", "API-EVT-3")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"correctionNo":"API-COR-3","correctedWeight":15}
                        """)).andExpect(status().isUnprocessableEntity());
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
        mockMvc.perform(post("/api/landings/{eventId}/reviews/confirm", "NO-SUCH")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"reviewEventId":"API-REV-X","reviewer":"PORT-1","confirmedWeight":1}
                        """)).andExpect(status().isNotFound());
        // 确认不存在的更正
        mockMvc.perform(post("/api/landings/corrections/{no}/confirm", "NO-SUCH"))
                .andExpect(status().isNotFound());
    }
}
