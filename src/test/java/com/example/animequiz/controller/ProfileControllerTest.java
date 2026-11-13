package com.example.animequiz.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.animequiz.entity.ImportJob;
import com.example.animequiz.exceptions.GlobalExceptionHandler;
import com.example.animequiz.exceptions.InvalidInputException;
import com.example.animequiz.repository.UserRepository;
import com.example.animequiz.service.ActiveUserImports;
import com.example.animequiz.service.AuthService;
import com.example.animequiz.service.ImportJobService;
import com.example.animequiz.service.StorageService;
import com.example.animequiz.service.UserListService;
import com.example.animequiz.service.importer.AnimeListImporter;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class ProfileControllerTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private StorageService storageService;

    @Mock
    private UserListService userListService;

    @Mock
    private AuthService authService;

    @Mock
    private ActiveUserImports activeImports;

    @Mock
    private ImportJobService importJobService;

    private AnimeListImporter importer;

    private MockMvc mvc;

    private final Authentication auth =
            new UsernamePasswordAuthenticationToken("alice", "pw", List.of(new SimpleGrantedAuthority("ROLE_USER")));

    @BeforeEach
    void setUp() {
        importer = mock(AnimeListImporter.class);
        when(importer.sourceName()).thenReturn("shikimori");
        mvc = MockMvcBuilders.standaloneSetup(new profileController(
                        userRepository,
                        storageService,
                        userListService,
                        authService,
                        List.of(importer),
                        activeImports,
                        importJobService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void startImportAcceptsAndReturnsJobId() throws Exception {
        when(importJobService.startUserImport("alice", importer, "shiki-nick")).thenReturn(42L);

        mvc.perform(post("/profile/import/json")
                        .param("nickname", " shiki-nick ")
                        .param("source", "shikimori")
                        .principal(auth))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(42))
                .andExpect(jsonPath("$.source").value("shikimori"))
                .andExpect(jsonPath("$.nickname").value("shiki-nick"))
                .andExpect(jsonPath("$.message").value("Импорт запущен"));
    }

    @Test
    void startImportRejectsASecondRun() throws Exception {
        when(importJobService.startUserImport(eq("alice"), any(), any()))
                .thenThrow(new IllegalStateException("Импорт уже выполняется"));

        mvc.perform(post("/profile/import/json").param("nickname", "shiki-nick").principal(auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Импорт уже выполняется"));
    }

    @Test
    void startImportRequiresLogin() throws Exception {
        mvc.perform(post("/profile/import/json").param("nickname", "shiki-nick"))
                .andExpect(status().isUnauthorized());

        verify(importJobService, never()).startUserImport(any(), any(), any());
    }

    @Test
    void startImportRejectsBlankNickname() throws Exception {
        mvc.perform(post("/profile/import/json").param("nickname", "  ").principal(auth))
                .andExpect(status().isBadRequest());

        verify(importJobService, never()).startUserImport(any(), any(), any());
    }

    @Test
    void startImportRejectsAnUnknownSource() throws Exception {
        mvc.perform(post("/profile/import/json")
                        .param("nickname", "shiki-nick")
                        .param("source", "unknown")
                        .principal(auth))
                .andExpect(status().isBadRequest());
    }

    @Test
    void statusReportsImportProgress() throws Exception {
        ImportJob job = new ImportJob();
        job.setId(42L);
        job.setType("user_import");
        job.setState("running");
        job.setStage("enumeration");
        job.setProcessedCount(50);
        job.setMessage("Загружено 50 записей");

        when(activeImports.isRunning("alice")).thenReturn(true);
        when(activeImports.lastJobId("alice")).thenReturn(Optional.of(42L));
        when(importJobService.jobById(42L)).thenReturn(Optional.of(job));
        when(userListService.getUserListView("alice")).thenReturn(new UserListService.UserListView(List.of(), 3, 10));

        mvc.perform(get("/profile/import/status").principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(true))
                .andExpect(jsonPath("$.import.jobId").value(42))
                .andExpect(jsonPath("$.import.state").value("running"))
                .andExpect(jsonPath("$.import.stage").value("enumeration"))
                .andExpect(jsonPath("$.import.processed").value(50))
                .andExpect(jsonPath("$.import.message").value("Загружено 50 записей"))
                .andExpect(jsonPath("$.total").value(10))
                .andExpect(jsonPath("$.hidden").value(3))
                .andExpect(jsonPath("$.done").value(false));
    }

    @Test
    void statusWithoutAJobReportsPosterProgressOnly() throws Exception {
        when(activeImports.isRunning("alice")).thenReturn(false);
        when(activeImports.lastJobId("alice")).thenReturn(Optional.empty());
        when(userListService.getUserListView("alice")).thenReturn(new UserListService.UserListView(List.of(), 0, 0));

        mvc.perform(get("/profile/import/status").principal(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(false))
                .andExpect(jsonPath("$.import").doesNotExist())
                .andExpect(jsonPath("$.done").value(true));
    }

    @Test
    void statusRequiresLogin() throws Exception {
        mvc.perform(get("/profile/import/status")).andExpect(status().isUnauthorized());
    }

    @Test
    void setAvatarJsonRejectsAnInvalidFormat() throws Exception {
        doThrow(new InvalidInputException("Недопустимый формат: разрешены PNG, JPEG, GIF, WEBP"))
                .when(storageService)
                .saveAvatar(eq("u_alice"), any());
        MockMultipartFile file = new MockMultipartFile("avatar", "a.txt", "text/plain", new byte[] {1});

        mvc.perform(multipart("/profile/avatar/json").file(file).principal(auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Недопустимый формат: разрешены PNG, JPEG, GIF, WEBP"));
    }

    @Test
    void setAvatarJsonReportsStorageFailureAsServerError() throws Exception {
        doThrow(new IOException("disk full")).when(storageService).saveAvatar(eq("u_alice"), any());
        MockMultipartFile file = new MockMultipartFile("avatar", "a.png", "image/png", new byte[] {(byte) 0x89});

        mvc.perform(multipart("/profile/avatar/json").file(file).principal(auth))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Не удалось сохранить аватар, попробуйте позже"));
    }

    @Test
    void setAvatarRedirectsWithFriendlyErrorOnStorageFailure() throws Exception {
        doThrow(new IOException("disk full")).when(storageService).saveAvatar(eq("u_alice"), any());
        MockMultipartFile file = new MockMultipartFile("avatar", "a.png", "image/png", new byte[] {(byte) 0x89});

        mvc.perform(multipart("/profile/avatar").file(file).principal(auth))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/profile"))
                .andExpect(flash().attribute("error", "Не удалось сохранить аватар, попробуйте позже"));
    }
}
