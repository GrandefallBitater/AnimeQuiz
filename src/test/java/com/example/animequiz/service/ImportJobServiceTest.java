package com.example.animequiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.animequiz.entity.ImportJob;
import com.example.animequiz.exceptions.ExternalServiceException;
import com.example.animequiz.repository.ImportJobRepository;
import com.example.animequiz.service.catalog.AnimeCatalogService;
import com.example.animequiz.service.enrichment.EnrichmentWorker;
import com.example.animequiz.service.importer.ShikimoriService;
import com.example.animequiz.service.importer.YummyAnimeService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class ImportJobServiceTest {

    @Mock
    private ImportJobRepository jobRepo;

    @Mock
    private ShikimoriService shikimori;

    @Mock
    private YummyAnimeService yummy;

    @Mock
    private AnimeCatalogService catalog;

    @Mock
    private EnrichmentWorker enrichmentWorker;

    @Mock
    private ObjectProvider<ImportJobService> self;

    @InjectMocks
    private ImportJobService service;

    @Test
    void startSiteImportCreatesJobAndRunsItAsynchronously() {
        ImportJobService asyncSelf = mock(ImportJobService.class);
        when(jobRepo.findFirstByTypeAndStateOrderByIdDesc("site_import", "running"))
                .thenReturn(Optional.empty());
        when(jobRepo.save(any(ImportJob.class))).thenAnswer(invocation -> {
            ImportJob job = invocation.getArgument(0);
            job.setId(42L);
            return job;
        });
        when(self.getObject()).thenReturn(asyncSelf);

        Long jobId = service.startSiteImport();

        assertThat(jobId).isEqualTo(42L);
        verify(asyncSelf).runSiteImportAsync(42L);
    }

    @Test
    void startSiteImportRejectsASecondRun() {
        when(jobRepo.findFirstByTypeAndStateOrderByIdDesc("site_import", "running"))
                .thenReturn(Optional.of(new ImportJob()));

        assertThatThrownBy(() -> service.startSiteImport())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Импорт уже выполняется");

        verify(jobRepo, never()).save(any());
        verifyNoInteractions(self);
    }

    @Test
    void currentRunningAndLastJobReadTheRepository() {
        ImportJob running = new ImportJob();
        when(jobRepo.findFirstByTypeAndStateOrderByIdDesc("site_import", "running"))
                .thenReturn(Optional.of(running));
        when(jobRepo.findTop10ByTypeOrderByIdDesc("site_import")).thenReturn(List.of());

        assertThat(service.currentRunning("site_import")).contains(running);
        assertThat(service.lastJob("site_import")).isEmpty();

        ImportJob last = new ImportJob();
        when(jobRepo.findTop10ByTypeOrderByIdDesc("site_import")).thenReturn(List.of(last));

        assertThat(service.lastJob("site_import")).contains(last);
    }

    @Test
    void runSiteImportAsyncSavesCatalogAndFinishesTheJob() {
        ImportJob job = job(1L);
        when(jobRepo.findById(1L)).thenReturn(Optional.of(job));
        when(jobRepo.save(any(ImportJob.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AnimeCatalogService.AnimePatch first =
                AnimeCatalogService.AnimePatch.builder().shikimoriId(10L).build();
        AnimeCatalogService.AnimePatch second =
                AnimeCatalogService.AnimePatch.builder().shikimoriId(11L).build();
        when(shikimori.fetchSiteCatalog()).thenReturn(List.of(first, second));
        when(yummy.fetchByShikimoriIds(List.of(10L, 11L))).thenReturn(List.of());

        service.runSiteImportAsync(1L);

        verify(catalog).upsert(first);
        verify(catalog).upsert(second);
        verify(enrichmentWorker).trigger();
        assertThat(job.getState()).isEqualTo("done");
        assertThat(job.getFinishedAt()).isNotNull();
        assertThat(job.getMessage()).contains("Импорт завершён");
        assertThat(job.getStage()).isEqualTo("posters");
        assertThat(job.getTotalEstimate()).isEqualTo(2);
    }

    @Test
    void runSiteImportAsyncMarksTheJobAsFailed() {
        ImportJob job = job(1L);
        when(jobRepo.findById(1L)).thenReturn(Optional.of(job));
        when(jobRepo.save(any(ImportJob.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(shikimori.fetchSiteCatalog()).thenThrow(new ExternalServiceException("boom"));

        service.runSiteImportAsync(1L);

        assertThat(job.getState()).isEqualTo("failed");
        assertThat(job.getError()).isEqualTo("ExternalServiceException");
        assertThat(job.getFinishedAt()).isNotNull();
        verify(enrichmentWorker, never()).trigger();
    }

    private static ImportJob job(Long id) {
        ImportJob job = new ImportJob();
        job.setId(id);
        job.setType("site_import");
        return job;
    }
}
