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
 * 端到端 API 测试：账户开立、转让全流程、卸港幂等与台账查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void fullTransferAndLandingFlow() throws Exception {
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

        // 卸港申报 + 幂等重放
        String landing = """
                {"eventId":"API-EVT-1","vessel":"V1","holder":"B","species":"COD","season":"API-S1","weight":12.5}
                """;
        mockMvc.perform(post("/api/landings").contentType(MediaType.APPLICATION_JSON).content(landing))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/landings").contentType(MediaType.APPLICATION_JSON).content(landing))
                .andExpect(status().isCreated());
        // 同事件号不同内容冲突
        mockMvc.perform(post("/api/landings").contentType(MediaType.APPLICATION_JSON).content("""
                {"eventId":"API-EVT-1","vessel":"V1","holder":"B","species":"COD","season":"API-S1","weight":13}
                """)).andExpect(status().isConflict());

        // 余额核对：B = 10 + 30 - 12.5 = 27.5
        String accounts = mockMvc.perform(get("/api/quota-accounts")
                        .param("season", "API-S1").param("species", "COD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andReturn().getResponse().getContentAsString();
        long accountB = Long.parseLong(accounts.replaceAll(
                ".*\\{\"id\":(\\d+),\"season\":\"API-S1\",\"species\":\"COD\",\"holder\":\"B\".*", "$1"));
        mockMvc.perform(get("/api/quota-accounts/{id}", accountB))
                .andExpect(jsonPath("$.available").value(27.5))
                .andExpect(jsonPath("$.consumed").value(12.5));

        // 台账：入账 + 转入 + 核销
        mockMvc.perform(get("/api/quota-accounts/{id}/ledger", accountB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].type").value("GRANT"))
                .andExpect(jsonPath("$[1].type").value("TRANSFER_IN"))
                .andExpect(jsonPath("$[2].type").value("LANDING_DEDUCT"));

        // 卸港记录查询
        mockMvc.perform(get("/api/landings/{eventId}", "API-EVT-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weight").value(12.5));
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
    }
}
