package com.chris64233.cc.fisheries;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class QuotaApiTests {

    @Autowired
    private MockMvc mockMvc;

    private String createAccount(String holder, String quantity) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/quota-accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"season":"2026-S2","species":"COD","holder":"%s","approvedQuantity":%s}
                                """.formatted(holder, quantity)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.available").value(Double.parseDouble(quantity)))
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id").toString();
    }

    @Test
    void fullTransferAndLandingFlowOverHttp() throws Exception {
        String accountId = createAccount("API-A", "100");

        mockMvc.perform(post("/api/quota-accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"season":"2026-S2","species":"COD","holder":"API-A","approvedQuantity":10}
                                """))
                .andExpect(status().isConflict());

        MvcResult transferResult = mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"season":"2026-S2","species":"COD","fromHolder":"API-A","toHolder":"API-B","quantity":40}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        String transferId = com.jayway.jsonpath.JsonPath
                .read(transferResult.getResponse().getContentAsString(), "$.id").toString();

        mockMvc.perform(get("/api/quota-accounts/" + accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(60.0))
                .andExpect(jsonPath("$.transferFrozen").value(40.0));

        mockMvc.perform(post("/api/transfers/" + transferId + "/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holder\":\"API-B\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        mockMvc.perform(get("/api/quota-accounts/lookup")
                        .param("season", "2026-S2").param("species", "COD").param("holder", "API-B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(40.0));

        mockMvc.perform(post("/api/landings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"API-EVT-1","vessel":"FV-9","holder":"API-B","species":"COD","season":"2026-S2","weight":15.5}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false));

        mockMvc.perform(post("/api/landings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"API-EVT-1","vessel":"FV-9","holder":"API-B","species":"COD","season":"2026-S2","weight":15.500}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        mockMvc.perform(post("/api/landings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"API-EVT-1","vessel":"FV-10","holder":"API-B","species":"COD","season":"2026-S2","weight":15.5}
                                """))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/landings/API-EVT-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weight").value(15.5));

        mockMvc.perform(get("/api/quota-accounts/" + accountId + "/ledger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        mockMvc.perform(post("/api/landings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"API-EVT-2","vessel":"FV-9","holder":"API-B","species":"COD","season":"2026-S2","weight":999}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectReleasesFrozenQuotaOverHttp() throws Exception {
        String accountId = createAccount("API-C", "50");
        MvcResult transferResult = mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"season":"2026-S2","species":"COD","fromHolder":"API-C","toHolder":"API-D","quantity":20}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String transferId = com.jayway.jsonpath.JsonPath
                .read(transferResult.getResponse().getContentAsString(), "$.id").toString();

        mockMvc.perform(post("/api/transfers/" + transferId + "/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holder\":\"API-D\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        mockMvc.perform(get("/api/quota-accounts/" + accountId))
                .andExpect(jsonPath("$.available").value(50.0))
                .andExpect(jsonPath("$.transferFrozen").value(0.0));

        mockMvc.perform(post("/api/transfers/" + transferId + "/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holder\":\"API-D\"}"))
                .andExpect(status().isConflict());
    }
}
