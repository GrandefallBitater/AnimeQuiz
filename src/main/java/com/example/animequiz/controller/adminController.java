package com.example.animequiz.controller;

import com.example.animequiz.entity.ImportJob;
import com.example.animequiz.service.ImportJobService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Controller
public class adminController {

    private final ImportJobService jobService;

    @Autowired
    public adminController(ImportJobService jobService) {
        this.jobService = jobService;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/admin")
    public String adminPage() { return "admin"; }

    @ResponseBody
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/ImportSiteAnimeList")
    public Map<String, Object> startImport() {
        try {
            Long jobId = jobService.startSiteImport();
            return Map.of(
                    "message", "Импорт запущен",
                    "jobId", jobId
            );
        } catch (IllegalStateException e) {
            return Map.of("message", e.getMessage(), "running", true);
        }
    }

    @ResponseBody
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/ImportSiteAnimeList/status")
    public Map<String, Object> importStatus() {
        Optional<ImportJob> running = jobService.currentRunning("site_import");
        Map<String, Object> resp = new LinkedHashMap<>();
        if (running.isPresent()) {
            ImportJob j = running.get();
            resp.put("state", j.getState());
            resp.put("stage", j.getStage());
            resp.put("processed", j.getProcessedCount());
            resp.put("total", j.getTotalEstimate());
            resp.put("message", j.getMessage());
            resp.put("running", true);
            return resp;
        }
        Optional<ImportJob> last = jobService.lastJob("site_import");
        if (last.isPresent()) {
            ImportJob j = last.get();
            resp.put("state", j.getState());
            resp.put("stage", j.getStage());
            resp.put("processed", j.getProcessedCount());
            resp.put("total", j.getTotalEstimate());
            resp.put("message", j.getMessage());
            resp.put("error", j.getError());
            resp.put("running", false);
            return resp;
        }
        resp.put("state", "idle");
        resp.put("running", false);
        return resp;
    }

    @ResponseBody
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/getUploadDate")
    public Map<String, String> getUploadDate() {
        Optional<ImportJob> last = jobService.lastJob("site_import");
        if (last.isEmpty() || last.get().getFinishedAt() == null) {
            return Map.of("message", "Не выполнялся");
        }
        Instant instant = last.get().getFinishedAt();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-dd-MM")
                .withZone(ZoneId.of("UTC"));
        return Map.of("message", fmt.format(instant));
    }
}