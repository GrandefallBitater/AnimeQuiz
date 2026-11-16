package com.example.animequiz.repository;

import com.example.animequiz.entity.Authority;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AuthoritiesRepository extends JpaRepository<Authority, Long> {
    //    List<Authority> findByUser_Username(String username);
    @Query("SELECT a FROM Authority a JOIN FETCH a.user WHERE a.user.username = :username")
    List<Authority> findByUserUsernameWithUser(@Param("username") String username);
}
