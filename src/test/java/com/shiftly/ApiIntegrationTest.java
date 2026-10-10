package com.shiftly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiftly.domain.Role;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;

    @Test
    void testFullAuthAndSchedulerFlow() throws Exception {
        // 1. Register Organizer
        String orgEmail = "lead_" + System.currentTimeMillis() + "@example.com";
        String orgRegJson = mapper.writeValueAsString(Map.of(
                "name", "Lead Organizer",
                "email", orgEmail,
                "password", "SecretPass123!",
                "role", Role.ORGANIZER.name()
        ));

        String orgRes = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orgRegJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.role").value("ORGANIZER"))
                .andReturn().getResponse().getContentAsString();

        JsonNode orgNode = mapper.readTree(orgRes);
        String orgToken = orgNode.get("token").asText();

        // 2. Register Volunteer
        String volEmail = "vol_" + System.currentTimeMillis() + "@example.com";
        String volRegJson = mapper.writeValueAsString(Map.of(
                "name", "Happy Volunteer",
                "email", volEmail,
                "password", "SecretPass123!",
                "role", Role.VOLUNTEER.name()
        ));

        String volRes = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(volRegJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isString())
                .andReturn().getResponse().getContentAsString();

        JsonNode volNode = mapper.readTree(volRes);
        String volToken = volNode.get("token").asText();

        // 3. Test /api/auth/me for volunteer
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + volToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(volEmail))
                .andExpect(jsonPath("$.role").value("VOLUNTEER"));

        // 4. Organizer creates an event
        String eventJson = mapper.writeValueAsString(Map.of(
                "name", "Beach Clean Up",
                "description", "Cleaning the local beach coast",
                "eventDate", LocalDate.now().plusDays(3).toString(),
                "location", "Sunny Coast"
        ));

        String eventRes = mockMvc.perform(post("/api/events")
                        .header("Authorization", "Bearer " + orgToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andReturn().getResponse().getContentAsString();

        long eventId = mapper.readTree(eventRes).get("id").asLong();

        // 5. Organizer creates a shift for the event
        LocalDateTime shiftStart = LocalDateTime.now().plusDays(3).withHour(9).withMinute(0);
        LocalDateTime shiftEnd = LocalDateTime.now().plusDays(3).withHour(12).withMinute(0);
        String shiftJson = mapper.writeValueAsString(Map.of(
                "roleName", "Trash Collector",
                "startTime", shiftStart.toString(),
                "endTime", shiftEnd.toString(),
                "capacity", 2
        ));

        String shiftRes = mockMvc.perform(post("/api/events/" + eventId + "/shifts")
                        .header("Authorization", "Bearer " + orgToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shiftJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andReturn().getResponse().getContentAsString();

        long shiftId = mapper.readTree(shiftRes).get("id").asLong();

        // 6. Public can list events and browse shifts without token
        mockMvc.perform(get("/api/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        mockMvc.perform(get("/api/shifts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        // 7. Volunteer signs up for shift
        mockMvc.perform(post("/api/shifts/" + shiftId + "/signup")
                        .header("Authorization", "Bearer " + volToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // 8. Volunteer checks /api/volunteer/dashboard
        mockMvc.perform(get("/api/volunteer/dashboard")
                        .header("Authorization", "Bearer " + volToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmedShifts").isArray())
                .andExpect(jsonPath("$.confirmedShifts[0].shift.id").value(shiftId));

        // 9. Organizer checks /api/organizer/dashboard
        mockMvc.perform(get("/api/organizer/dashboard")
                        .header("Authorization", "Bearer " + orgToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events").isArray())
                .andExpect(jsonPath("$.shifts").isArray())
                .andExpect(jsonPath("$.stats.totalConfirmed").value(1));
    }
}
