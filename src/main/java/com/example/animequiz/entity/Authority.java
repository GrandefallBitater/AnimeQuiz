package com.example.animequiz.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@NoArgsConstructor
@Entity
@ToString(onlyExplicitlyIncluded = true)
@Table(
        name = "authorities",
        uniqueConstraints = {@UniqueConstraint(columnNames = {"username", "authority"})})
public class Authority implements org.springframework.security.core.GrantedAuthority {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "username", nullable = false, updatable = false)
    private User user;

    @ToString.Include
    @Column(nullable = false)
    private String authority;

    public Authority(User user, String authority) {
        this.user = user;
        this.authority = authority;
    }
}
