package com.example.animequiz.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "user_import")
@Getter
@Setter
public class UserImport {

    @Id
    @Column(name = "user_id")
    private String userId;

    @Column(nullable = false, length = 32)
    private String source;

    @Column(nullable = false)
    private String nickname;

    @Column(name = "source_user_id")
    private Long sourceUserId;

    @Column(name = "total_count", nullable = false)
    private int totalCount;

    @Column(name = "imported_at", nullable = false)
    private Instant importedAt = Instant.now();
}
