package com.acp.patternmanager.store.repo;

import com.acp.patternmanager.store.entity.PatternEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@code pattern.pattern}. */
public interface PatternRepository extends JpaRepository<PatternEntity, UUID> {

    List<PatternEntity> findByLifecycle(String lifecycle, Pageable pageable);

    long countByLifecycle(String lifecycle);
}
