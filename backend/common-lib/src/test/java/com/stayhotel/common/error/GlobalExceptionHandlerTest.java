package com.stayhotel.common.error;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new SampleController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void validationErrorsAreReportedPerField() throws Exception {
        mvc.perform(post("/sample").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED))
                .andExpect(jsonPath("$.path").value("/sample"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));
    }

    @Test
    void malformedJsonReturns400WithoutParserDetails() throws Exception {
        mvc.perform(post("/sample").contentType(MediaType.APPLICATION_JSON).content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.MALFORMED_REQUEST))
                .andExpect(content().string(not(containsString("JsonParseException"))));
    }

    @Test
    void businessExceptionKeepsStatusAndCode() throws Exception {
        mvc.perform(get("/sample/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROOM_SOLD_OUT"))
                .andExpect(jsonPath("$.message").value("No rooms left"));
    }

    @Test
    void unexpectedErrorDoesNotLeakInternals() throws Exception {
        mvc.perform(get("/sample/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(ErrorCode.INTERNAL_ERROR))
                .andExpect(jsonPath("$.message").value("Unexpected server error"))
                .andExpect(content().string(not(containsString("secret-db-password"))));
    }

    @RestController
    static class SampleController {
        record SampleRequest(@NotBlank String name) {
        }

        @PostMapping("/sample")
        String create(@Valid @RequestBody SampleRequest request) {
            return request.name();
        }

        @GetMapping("/sample/conflict")
        String conflict() {
            throw new BusinessException(HttpStatus.CONFLICT, "ROOM_SOLD_OUT", "No rooms left");
        }

        @GetMapping("/sample/boom")
        String boom() {
            throw new IllegalStateException("jdbc failure with secret-db-password");
        }
    }
}
