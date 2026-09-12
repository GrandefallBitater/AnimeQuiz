package com.example.animequiz.repository;

import com.example.animequiz.entity.Authority;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuthoritiesRepository extends JpaRepository<Authority, Long> {
//    List<Authority> findByUser_Username(String username);
        @Query("SELECT a FROM Authority a JOIN FETCH a.user WHERE a.user.username = :username")
        List<Authority> findByUserUsernameWithUser(@Param("username") String username);
}
