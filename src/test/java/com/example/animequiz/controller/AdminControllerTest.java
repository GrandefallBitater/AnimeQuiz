package com.example.animequiz.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.example.animequiz.entity.ImportJob;
import com.example.animequiz.service.ImportJobService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    @Mock
    private ImportJobService jobService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new adminController(jobService))
                .setViewResolvers(new InternalResourceViewResolver("/WEB-INF/views/", ".html"))
                .build();
    }

    @Test
    void adminPageIsRendered() throws Exception {
        mvc.perform(get("/admin")).andExpect(status().isOk()).andExpect(view().name("admin"));
    }

    @Test
    void startImportReturnsJobId() throws Exception {
        when(jobService.startSiteImport()).thenReturn(42L);

        mvc.perform(post("/ImportSiteAnimeList"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Импорт запущен"))
                .andExpect(jsonPath("$.jobId").value(42));
    }

    @Test
    void startImportReportsAnAlreadyRunningJob() throws Exception {
        when(jobService.startSiteImport()).thenThrow(new IllegalStateException("Импорт уже выполняется"));

        mvc.perform(post("/ImportSiteAnimeList"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Импорт уже выполняется"))
                .andExpect(jsonPath("$.running").value(true));
    }

    @Test
    void statusReturnsRunningJob() throws Exception {
        ImportJob job = new ImportJob();
        job.setState("running");
        job.setStage("enumeration");
        job.setProcessedCount(10);
        job.setTotalEstimate(100);
        job.setMessage("Работаю");
        when(jobService.currentRunning("site_import")).thenReturn(Optional.of(job));

        mvc.perform(get("/ImportSiteAnimeList/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("running"))
                .andExpect(jsonPath("$.stage").value("enumeration"))
                .andExpect(jsonPath("$.processed").value(10))
                .andExpect(jsonPath("$.total").value(100))
                .andExpect(jsonPath("$.running").value(true));
    }

    @Test
    void statusFallsBackToTheLastFinishedJob() throws Exception {
        ImportJob job = new ImportJob();
        job.setState("failed");
        job.setStage("posters");
        job.setProcessedCount(5);
        job.setMessage("Упало");
        job.setError("ExternalServiceException");
        when(jobService.currentRunning("site_import")).thenReturn(Optional.empty());
        when(jobService.lastJob("site_import")).thenReturn(Optional.of(job));

        mvc.perform(get("/ImportSiteAnimeList/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("failed"))
                .andExpect(jsonPath("$.running").value(false))
                .andExpect(jsonPath("$.error").value("ExternalServiceException"));
    }

    @Test
    void statusReturnsIdleWhenThereWereNoJobs() throws Exception {
        when(jobService.currentRunning("site_import")).thenReturn(Optional.empty());
        when(jobService.lastJob("site_import")).thenReturn(Optional.empty());

        mvc.perform(get("/ImportSiteAnimeList/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("idle"))
                .andExpect(jsonPath("$.running").value(false));
    }

    @Test
    void uploadDateIsFormattedFromTheLastFinishedJob() throws Exception {
        ImportJob job = new ImportJob();
        job.setFinishedAt(Instant.parse("2024-03-05T10:15:30Z"));
        when(jobService.lastJob("site_import")).thenReturn(Optional.of(job));

        mvc.perform(get("/getUploadDate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("2024-05-03"));
    }

    @Test
    void uploadDateReportsThatImportNeverRan() throws Exception {
        when(jobService.lastJob("site_import")).thenReturn(Optional.empty());

        mvc.perform(get("/getUploadDate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Не выполнялся"));
    }
}
