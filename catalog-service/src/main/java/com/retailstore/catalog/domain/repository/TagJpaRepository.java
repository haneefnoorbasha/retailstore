package com.retailstore.catalog.domain.repository;

import com.retailstore.catalog.domain.model.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface TagJpaRepository extends JpaRepository<Tag, Long> {
    Optional<Tag> findByName(String name);
}
