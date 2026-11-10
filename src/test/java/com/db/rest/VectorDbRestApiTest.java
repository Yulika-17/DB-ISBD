package com.db.rest;

import com.db.server.VectorDbApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = VectorDbApplication.class, properties = {
        "vecdb.data-directory=target/test-data/rest-${random.uuid}",
        "vecdb.auto-save=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class VectorDbRestApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void exposesVectorDatabaseOperationsOverRest() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mvc.perform(post("/api/v1/collections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "name", "docs",
                                "dimension", 3,
                                "metric", "COSINE"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("docs"))
                .andExpect(jsonPath("$.dimension").value(3));

        mvc.perform(put("/api/v1/collections/docs/vectors/doc-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "vector", new double[]{1, 0, 0},
                                "data", "aGVsbG8="
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("doc-1"))
                .andExpect(jsonPath("$.data").value("aGVsbG8="));

        mvc.perform(get("/api/v1/collections/docs/vectors/doc-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vector[0]").value(1.0));

        mvc.perform(put("/api/v1/collections/docs/vectors/size")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "vector", new double[]{0, 1, 0}
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("size"));

        mvc.perform(get("/api/v1/collections/docs/vectors/size"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("size"))
                .andExpect(jsonPath("$.vector[1]").value(1.0));

        mvc.perform(get("/api/v1/collections/docs/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liveVectors").value(2));

        mvc.perform(post("/api/v1/collections/docs/vectors/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "vector", new double[]{1, 0, 0},
                                "k", 1
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].entry.id").value("doc-1"))
                .andExpect(jsonPath("$[0].distance").value(closeTo(0.0, 0.000001)));

        mvc.perform(delete("/api/v1/collections/docs/vectors/doc-1"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/collections/docs/vectors/doc-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("id not found: doc-1"));
    }

    @Test
    void validatesRequestsAndPublishesOpenApi() throws Exception {
        mvc.perform(post("/api/v1/collections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"bad name\",\"dimension\":0}"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Vector database REST API"));
    }
}
