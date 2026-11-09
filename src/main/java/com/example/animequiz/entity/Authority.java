package com.example.animequiz.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@NoArgsConstructor
@Entity
@Table(
        name = "authorities",
        uniqueConstraints = {@UniqueConstraint(columnNames = {"username", "authority"})})
public class Authority implements org.springframework.security.core.GrantedAuthority {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "username", nullable = false, updatable = false)
    @ToString.Exclude
    private User user;

    @Column(nullable = false)
    private String authority;

    public Authority(User user, String authority) {
        this.user = user;
        this.authority = authority;
    }
}
