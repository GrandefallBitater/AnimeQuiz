package com.example.animequiz.service.enrichment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.animequiz.entity.ImportJob;
import com.example.animequiz.repository.AnimeRepository;
import com.example.animequiz.repository.ImportJobRepository;
import com.example.animequiz.service.PosterStorageService;
import com.example.animequiz.service.catalog.AnimeCatalogService;
import com.example.animequiz.service.importer.MalService;
import com.example.animequiz.service.importer.ShikimoriService;
import com.example.animequiz.service.importer.YummyAnimeService;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskRejectedException;

@ExtendWith(MockitoExtension.class)
class EnrichmentWorkerTest {

    @Mock
    private AnimeRepository animeRepository;

    @Mock
    private AnimeCatalogService catalog;

    @Mock
    private PosterStorageService posterStorage;

    @Mock
    private ShikimoriService shikimori;

    @Mock
    private MalService mal;

    @Mock
    private YummyAnimeService yummy;

    @Mock
    private ObjectProvider<EnrichmentWorker> self;

    @Mock
    private ImportJobRepository jobRepo;

    @InjectMocks
    private EnrichmentWorker worker;

    @Test
    void tickSkipsTheBatchWhileASiteImportIsRunning() {
        when(jobRepo.findFirstByTypeAndStateOrderByIdDesc("site_import", "running"))
                .thenReturn(Optional.of(new ImportJob()));

        worker.tick();

        verifyNoInteractions(self);
        verify(animeRepository, never()).findEnrichQueue(any(), any());
    }

    @Test
    void tickHandsTheBatchToTheAsyncWorkerInsteadOfRunningItInline() {
        EnrichmentWorker asyncSelf = mock(EnrichmentWorker.class);
        when(jobRepo.findFirstByTypeAndStateOrderByIdDesc("site_import", "running"))
                .thenReturn(Optional.empty());
        when(self.getObject()).thenReturn(asyncSelf);

        worker.tick();

        verify(asyncSelf).processTick();
        verify(animeRepository, never()).findEnrichQueue(any(), any());
    }

    @Test
    void tickSurvivesARejectedTask() {
        EnrichmentWorker asyncSelf = mock(EnrichmentWorker.class);
        when(jobRepo.findFirstByTypeAndStateOrderByIdDesc("site_import", "running"))
                .thenReturn(Optional.empty());
        when(self.getObject()).thenReturn(asyncSelf);
        doThrow(new TaskRejectedException("queue is full")).when(asyncSelf).processTick();

        assertThatCode(worker::tick).doesNotThrowAnyException();
    }

    @Test
    void processTickRunsOneBatchAndReleasesTheGuardForTheNextTick() {
        when(animeRepository.findEnrichQueue(any(), any())).thenReturn(List.of());

        worker.processTick();
        worker.processTick();

        verify(animeRepository, times(2)).findEnrichQueue(any(), any());
    }

    @Test
    void processTickSkipsWhileThePreviousBatchIsStillRunning() throws InterruptedException {
        CountDownLatch batchStarted = new CountDownLatch(1);
        CountDownLatch releaseBatch = new CountDownLatch(1);
        when(animeRepository.findEnrichQueue(any(), any())).thenAnswer(invocation -> {
            batchStarted.countDown();
            releaseBatch.await(5, TimeUnit.SECONDS);
            return List.of();
        });

        Thread firstRun = new Thread(worker::processTick, "enrichment-test");
        firstRun.start();
        assertThat(batchStarted.await(5, TimeUnit.SECONDS)).isTrue();

        worker.processTick();

        releaseBatch.countDown();
        firstRun.join(5000);

        verify(animeRepository, times(1)).findEnrichQueue(any(), any());
    }
}
